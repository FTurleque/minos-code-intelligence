[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead,
    [string] $ProjectsRoot = '',
    [switch] $SkipMavenVerify,
    [switch] $KeepArtifacts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S5 qualification currently targets Windows host + Docker Desktop linux/amd64.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { $ProjectsRoot = Split-Path -Parent $RepoRoot }
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
$FixtureRelativePath = 'minos-code-intelligence/fixtures/polyglot/m29-scoped-modules'
$FixtureHostPath = [System.IO.Path]::GetFullPath((Join-Path $ProjectsRoot $FixtureRelativePath))
if (-not (Test-Path -LiteralPath $FixtureHostPath -PathType Container)) {
    throw "M29-S5 controlled polyglot fixture is missing: $FixtureHostPath"
}
if (-not (Test-Path -LiteralPath (Join-Path $FixtureHostPath 'pom.xml') -PathType Leaf)) {
    throw 'M29-S5 fixture must expose the Maven root used by scip-java.'
}
if (Test-Path -LiteralPath (Join-Path $FixtureHostPath 'package.json') -PathType Leaf) {
    throw 'M29-S5 fixture must not hide provider->module routing behind a root package.json.'
}
if (Test-Path -LiteralPath (Join-Path $FixtureHostPath 'tsconfig.json') -PathType Leaf) {
    throw 'M29-S5 fixture must not hide provider->module routing behind a root tsconfig.json.'
}
foreach ($NestedManifest in @('ui\app\tsconfig.json', 'ui\lib\tsconfig.json')) {
    if (-not (Test-Path -LiteralPath (Join-Path $FixtureHostPath $NestedManifest) -PathType Leaf)) {
        throw "M29-S5 nested TypeScript manifest is missing: $NestedManifest"
    }
}

function Invoke-NativeCapture {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments)
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ((& $File @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $Previous }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments, [Parameter(Mandatory = $true)][string] $Failure)
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) { throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)" }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) { Write-Host $Result.Output }
    return $Result.Output
}

function ConvertFrom-LastJsonLine([string] $Output, [string] $Label) {
    $Lines = @($Output -split "`r?`n")
    for ($Index = $Lines.Count - 1; $Index -ge 0; $Index--) {
        $Candidate = $Lines[$Index].Trim()
        if (-not $Candidate.StartsWith('{') -and -not $Candidate.StartsWith('[')) { continue }
        try { return $Candidate | ConvertFrom-Json }
        catch { }
    }
    throw "M29-S5 could not find JSON output for $Label. Output: $Output"
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD') -Failure 'Unable to resolve M29 HEAD').Trim()
if ($Head -ne $ExpectedHead.Trim()) { throw "M29-S5 exact-head mismatch: expected $ExpectedHead, found $Head" }
$Dirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect git status').Trim()
if (-not [string]::IsNullOrWhiteSpace($Dirty)) { throw "M29-S5 requires a clean worktree. Dirty entries:`n$Dirty" }
Write-Host "M29-S5 exact HEAD: $Head" -ForegroundColor Cyan
Write-Host "M29-S5 controlled fixture: $FixtureRelativePath" -ForegroundColor Cyan

$S4Runner = Join-Path $RepoRoot 'scripts\m29\run-s4.ps1'
$S4Parameters = @{
    ExpectedHead = $Head
    ProjectsRoot = $ProjectsRoot
    SemanticProvider = 'local-hash'
    KeepArtifacts = $true
}
if ($SkipMavenVerify) { $S4Parameters['SkipMavenVerify'] = $true }
& $S4Runner @S4Parameters

$Suffix = $Head.Substring(0, [Math]::Min(12, $Head.Length))
$InstallRoot = Join-Path $env:TEMP "minos-m29-s4-runtime-$Suffix"
$DataRoot = Join-Path $env:TEMP "minos-m29-s4-data-$Suffix"
$ContainerName = "minos-m29-s4-$Suffix"
$ComposeProject = "minos-m29-s4-$Suffix"
$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$ComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$Workflow = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$ReportPath = Join-Path $ReportRoot "s5-qualification-$Head.json"
$Docker = (Get-Command docker -ErrorAction Stop).Source
$StartedAt = [DateTime]::UtcNow
$Passed = $false
$ProjectId = $null
$FirstRunId = $null
$FirstSnapshot = $null
$RecoveryRunId = $null
$RecoverySnapshot = $null
$SemanticStatus = $null
$HybridStatus = $null
$QuerySemanticStatus = $null
$QueryHybridStatus = $null
$ScopedRoots = @()

function Invoke-AdminJson {
    param([Parameter(Mandatory = $true)][string[]] $MinosArguments, [Parameter(Mandatory = $true)][string] $Label)
    $Arguments = @(
        'compose', '--project-directory', $RuntimeRoot,
        '--env-file', $EnvironmentFile, '-f', $ComposeFile,
        'run', '--rm', '--no-deps', 'minos-admin'
    ) + $MinosArguments
    $Output = Assert-NativeSuccess -File $Docker -Arguments $Arguments -Failure "M29-S5 admin command failed: $Label"
    return ConvertFrom-LastJsonLine -Output $Output -Label $Label
}

function Invoke-QueryJson {
    param([Parameter(Mandatory = $true)][string[]] $MinosArguments, [Parameter(Mandatory = $true)][string] $Label)
    $Arguments = @('exec', $ContainerName, 'java', '-cp', '/opt/minos/minos.jar', 'com.minos.cli.MinosLauncher') + $MinosArguments
    $Output = Assert-NativeSuccess -File $Docker -Arguments $Arguments -Failure "M29-S5 query-plane command failed: $Label"
    return ConvertFrom-LastJsonLine -Output $Output -Label $Label
}

function Invoke-Workflow([string] $Action) {
    & $Workflow -Action $Action -InstallRoot $InstallRoot -DataRoot $DataRoot `
        -ContainerName $ContainerName -ComposeProject $ComposeProject
}

try {
    $InitialProjects = Invoke-AdminJson -MinosArguments @('project', 'list', '--format', 'json') -Label 'fresh project list'
    if ([int] $InitialProjects.count -ne 0) { throw "M29-S5 expected a fresh project registry, found $($InitialProjects.count)" }

    $ContainerFixture = '/workspace/projects/' + $FixtureRelativePath
    $Added = Invoke-AdminJson -MinosArguments @('project', 'add', $ContainerFixture, '--name', 'm29-s5-polyglot', '--format', 'json') -Label 'project add'
    $ProjectId = [string] $Added.id
    if ([string]::IsNullOrWhiteSpace($ProjectId)) { throw 'M29-S5 project add returned no project id.' }
    if (@($Added.languages) -notcontains 'JAVA' -or @($Added.languages) -notcontains 'TYPESCRIPT') {
        throw "M29-S5 discovery did not expose JAVA+TYPESCRIPT: $(@($Added.languages) -join ',')"
    }
    if (@($Added.buildSystems) -notcontains 'MAVEN' -or @($Added.buildSystems) -notcontains 'NPM') {
        throw "M29-S5 discovery did not expose MAVEN+NPM: $(@($Added.buildSystems) -join ',')"
    }
    if ([int] $Added.moduleCount -lt 3) { throw "M29-S5 expected at least three discovered modules, found $($Added.moduleCount)" }

    $First = Invoke-AdminJson -MinosArguments @('index', 'm29-s5-polyglot', '--format', 'json') -Label 'first autonomous polyglot index'
    if ([string] $First.status -ne 'SUCCEEDED') { throw "M29-S5 first index did not succeed: $($First.status)" }
    if (-not [bool] $First.fingerprintPromoted) { throw 'M29-S5 first index did not promote the project fingerprint.' }
    $Providers = @($First.plan.providers)
    if ($Providers -notcontains 'scip-java' -or $Providers -notcontains 'scip-typescript') {
        throw "M29-S5 provider negotiation did not include Java+TypeScript providers: $($Providers -join ',')"
    }
    $FirstRunId = [string] $First.runId
    $FirstSnapshot = [string] $First.activeSnapshotId
    if ([string]::IsNullOrWhiteSpace($FirstRunId) -or [string]::IsNullOrWhiteSpace($FirstSnapshot)) {
        throw 'M29-S5 first index returned incomplete run/snapshot identity.'
    }

    $TypeScriptScopesRoot = Join-Path $DataRoot ("runs\$FirstRunId\scip-typescript\scopes")
    if (-not (Test-Path -LiteralPath $TypeScriptScopesRoot -PathType Container)) {
        throw "M29-S5 TypeScript scoped run directory is missing: $TypeScriptScopesRoot"
    }
    $ScopeMetadata = @(Get-ChildItem -LiteralPath $TypeScriptScopesRoot -Directory | ForEach-Object {
        $ProcessFile = Join-Path $_.FullName 'process.txt'
        if (-not (Test-Path -LiteralPath $ProcessFile -PathType Leaf)) {
            throw "M29-S5 scoped provider metadata is missing: $ProcessFile"
        }
        $Match = Select-String -LiteralPath $ProcessFile -Pattern '^projectRelativeRoot=(.+)$' | Select-Object -First 1
        if ($null -eq $Match) { throw "M29-S5 projectRelativeRoot metadata missing: $ProcessFile" }
        $Match.Matches[0].Groups[1].Value.Replace('\', '/')
    } | Sort-Object)
    if (($ScopeMetadata -join ',') -ne 'ui/app,ui/lib') {
        throw "M29-S5 TypeScript provider executed at unexpected scopes: $($ScopeMetadata -join ',')"
    }
    $ScopedRoots = $ScopeMetadata
    $JavaProcess = Join-Path $DataRoot ("runs\$FirstRunId\scip-java\process.txt")
    if (-not (Test-Path -LiteralPath $JavaProcess -PathType Leaf)) { throw "M29-S5 root Java execution metadata is missing: $JavaProcess" }

    $IndexStatus = Invoke-AdminJson -MinosArguments @('index-status', 'm29-s5-polyglot', '--format', 'json') -Label 'index status'
    if ([string] $IndexStatus.state -ne 'READY' -or [string] $IndexStatus.activeSnapshotId -ne $FirstSnapshot) {
        throw 'M29-S5 structured index is not READY on the promoted snapshot.'
    }

    $SemanticStatus = Invoke-AdminJson -MinosArguments @('semantic', 'status', 'm29-s5-polyglot', '--format', 'json') -Label 'semantic status'
    if ([string] $SemanticStatus.state -ne 'READY' -or -not [bool] $SemanticStatus.semanticAvailable) {
        throw "M29-S5 semantic index is not READY: $($SemanticStatus.state)"
    }
    if ([string] $SemanticStatus.providerId -ne 'minos-local-hash') { throw "M29-S5 unexpected semantic provider: $($SemanticStatus.providerId)" }
    if ([int] $SemanticStatus.dimensions -ne 384) { throw "M29-S5 unexpected semantic dimensions: $($SemanticStatus.dimensions)" }
    if ([int] $SemanticStatus.documentCount -le 0 -or [long] $SemanticStatus.indexSizeBytes -le 0) {
        throw 'M29-S5 semantic index contains no persisted documents.'
    }
    if ([string] $SemanticStatus.activeSnapshotId -ne $FirstSnapshot) { throw 'M29-S5 semantic index is not aligned with the structured snapshot.' }
    $VectorFile = Join-Path $DataRoot ("semantic-index\$ProjectId\index-v2.bin")
    if (-not (Test-Path -LiteralPath $VectorFile -PathType Leaf) -or (Get-Item -LiteralPath $VectorFile).Length -le 0) {
        throw "M29-S5 persisted float32 vector store is missing: $VectorFile"
    }

    $HybridStatus = Invoke-AdminJson -MinosArguments @('hybrid', 'status', 'm29-s5-polyglot', '--format', 'json') -Label 'hybrid status'
    if ([string] $HybridStatus.state -ne 'READY_WITH_SEMANTIC' -or -not [bool] $HybridStatus.semanticAvailable) {
        throw "M29-S5 hybrid retrieval did not enable semantic signal: $($HybridStatus.state)"
    }
    if (@($HybridStatus.limitations) -notcontains 'SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT') {
        throw 'M29-S5 hybrid status lost the required HEURISTIC semantic limitation.'
    }

    $Unchanged = Invoke-AdminJson -MinosArguments @('index', 'm29-s5-polyglot', '--format', 'json') -Label 'unchanged autonomous index'
    if ([string] $Unchanged.status -ne 'NO_CHANGES') { throw "M29-S5 unchanged plan should be NONE/NO_CHANGES, found $($Unchanged.status)" }
    if ([string] $Unchanged.plan.mode -ne 'NONE') { throw "M29-S5 unchanged plan mode is not NONE: $($Unchanged.plan.mode)" }
    if ([string] $Unchanged.activeSnapshotId -ne $FirstSnapshot) { throw 'M29-S5 unchanged indexing changed the active snapshot.' }

    $Recovery = Invoke-AdminJson -MinosArguments @('index', 'm29-s5-polyglot', '--force-full', '--format', 'json') -Label 'forced full recovery index'
    if ([string] $Recovery.status -ne 'SUCCEEDED' -or -not [bool] $Recovery.fingerprintPromoted) {
        throw "M29-S5 forced full recovery failed: $($Recovery.status)"
    }
    $RecoveryRunId = [string] $Recovery.runId
    $RecoverySnapshot = [string] $Recovery.activeSnapshotId
    if ([string]::IsNullOrWhiteSpace($RecoveryRunId) -or [string]::IsNullOrWhiteSpace($RecoverySnapshot) -or $RecoverySnapshot -eq $FirstSnapshot) {
        throw 'M29-S5 forced full recovery did not promote a fresh snapshot.'
    }
    $SemanticAfterRecovery = Invoke-AdminJson -MinosArguments @('semantic', 'status', 'm29-s5-polyglot', '--format', 'json') -Label 'semantic status after full recovery'
    if ([string] $SemanticAfterRecovery.state -ne 'READY' -or [string] $SemanticAfterRecovery.activeSnapshotId -ne $RecoverySnapshot) {
        throw 'M29-S5 semantic index did not realign to the recovery snapshot.'
    }

    Invoke-Workflow -Action Start
    $QuerySemanticStatus = Invoke-QueryJson -MinosArguments @('semantic', 'status', 'm29-s5-polyglot', '--format', 'json') -Label 'query-plane semantic status after recreate'
    $QueryHybridStatus = Invoke-QueryJson -MinosArguments @('hybrid', 'status', 'm29-s5-polyglot', '--format', 'json') -Label 'query-plane hybrid status after recreate'
    if ([string] $QuerySemanticStatus.state -ne 'READY' -or [string] $QuerySemanticStatus.activeSnapshotId -ne $RecoverySnapshot) {
        throw 'M29-S5 query plane did not reopen the persisted semantic index after recreate.'
    }
    if ([string] $QueryHybridStatus.state -ne 'READY_WITH_SEMANTIC' -or -not [bool] $QueryHybridStatus.semanticAvailable) {
        throw 'M29-S5 query plane did not reopen hybrid semantic readiness after recreate.'
    }

    $FinalDirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect final git status').Trim()
    if (-not [string]::IsNullOrWhiteSpace($FinalDirty)) {
        throw "M29-S5 provider execution modified the source checkout:`n$FinalDirty"
    }

    $Passed = $true
    Write-Host 'M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null
    [ordered]@{
        formatVersion = 1
        milestone = 'M29-S5'
        head = $Head
        startedAt = $StartedAt.ToString('o')
        finishedAt = [DateTime]::UtcNow.ToString('o')
        result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        fixtureRelativePath = $FixtureRelativePath
        semanticProvider = 'local-hash'
        projectId = $ProjectId
        firstRunId = $FirstRunId
        firstSnapshotId = $FirstSnapshot
        providerScopes = @($ScopedRoots)
        recoveryRunId = $RecoveryRunId
        recoverySnapshotId = $RecoverySnapshot
        semanticState = if ($null -eq $SemanticStatus) { $null } else { [string] $SemanticStatus.state }
        semanticModel = if ($null -eq $SemanticStatus) { $null } else { [string] $SemanticStatus.modelId }
        semanticDimensions = if ($null -eq $SemanticStatus) { 0 } else { [int] $SemanticStatus.dimensions }
        semanticDocumentCount = if ($null -eq $SemanticStatus) { 0 } else { [int] $SemanticStatus.documentCount }
        semanticIndexSizeBytes = if ($null -eq $SemanticStatus) { 0 } else { [long] $SemanticStatus.indexSizeBytes }
        hybridState = if ($null -eq $HybridStatus) { $null } else { [string] $HybridStatus.state }
        querySemanticState = if ($null -eq $QuerySemanticStatus) { $null } else { [string] $QuerySemanticStatus.state }
        queryHybridState = if ($null -eq $QueryHybridStatus) { $null } else { [string] $QueryHybridStatus.state }
        vectorStore = 'semantic-index/<projectId>/index-v2.bin'
        vectorRepresentation = 'float32 exact scan; HEURISTIC signal'
        scopedFailureRecoveryProof = 'IndexingLifecycleScopedExecutionTest'
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S5 report: $ReportPath"

    if ($Passed -and -not $KeepArtifacts) {
        try { Invoke-Workflow -Action Uninstall } catch { Write-Warning $_.Exception.Message }
        Remove-Item -LiteralPath $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
    } elseif (-not $Passed) {
        Write-Host "M29-S5 diagnostic artifacts preserved: $InstallRoot ; $DataRoot" -ForegroundColor Yellow
    }
}
