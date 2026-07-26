[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-finalize-s7-s11'
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Invoke-NativeChecked {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments, [Parameter(Mandatory = $true)][string] $Failure)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Resolve-Python {
    foreach ($candidate in @('python','python3')) {
        $command = Get-Command $candidate -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    $py = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($py) { return $py.Source }
    throw 'Python 3 is required for M15 quality/document consistency gates.'
}

function Read-RepoText {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
    [System.IO.File]::ReadAllText($path, $Utf8)
}

function Assert-Contains {
    param([string] $Content, [string] $Expected, [string] $Owner)
    if (-not $Content.Contains($Expected)) { throw "$Owner must contain: $Expected" }
}

function Assert-NotContains {
    param([string] $Content, [string] $Forbidden, [string] $Owner)
    if ($Content.Contains($Forbidden)) { throw "$Owner must not contain: $Forbidden" }
}

function Assert-FinalShape {
    [xml] $pom = Read-RepoText 'pom.xml'
    $modules = @($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]'))
    if ($modules.Count -ne 12) { throw "Expected 12 child Maven modules, found $($modules.Count)." }

    $application = Read-RepoText 'minos-application\src\main\java\com\minos\application\MinosApplication.java'
    Assert-Contains $application 'public final class MinosApplication' 'MinosApplication'

    $resolver = Read-RepoText 'minos-application\src\main\java\com\minos\application\ProjectResolver.java'
    foreach ($value in @('PROJECT_NOT_FOUND','PROJECT_REFERENCE_AMBIGUOUS','INVALID_PROJECT_REFERENCE')) {
        Assert-Contains $resolver $value 'ProjectResolver'
    }

    $mcpPom = Read-RepoText 'minos-mcp\pom.xml'
    Assert-NotContains $mcpPom '<artifactId>minos-cli</artifactId>' 'minos-mcp/pom.xml'
    $mcpTools = Read-RepoText 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpTools.java'
    Assert-NotContains $mcpTools 'MinosCli' 'MinosMcpTools'
    Assert-Contains $mcpTools 'TOOL_COUNT = 16' 'MinosMcpTools'

    foreach ($file in @(
        'SnapshotRepository.java','ActiveSnapshotRepository.java','SnapshotIntegrityService.java',
        'SnapshotRetentionService.java','SnapshotCodec.java','SnapshotCodecV1.java','SnapshotCodecV2.java',
        'SnapshotQueryView.java'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot "minos-storage-local\src\main\java\com\minos\store\$file") -PathType Leaf)) {
            throw "Missing M15 persistence/cache component: $file"
        }
    }

    $snapshotStore = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\FileSymbolSnapshotStore.java'
    foreach ($value in @('DEFAULT_MAX_QUERY_CACHE_ENTRIES = 32','loadActiveQueryView','CacheStats','fullSnapshotLoads','queryViewBuilds')) {
        Assert-Contains $snapshotStore $value 'FileSymbolSnapshotStore'
    }

    $queryStore = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\InMemoryCodeKnowledgeStore.java'
    foreach ($value in @('byNormalizedName','byQualifiedName','byFileId','byResolvedSymbolId','bySourceEntity','byTargetEntity','byKind','IndexMetrics')) {
        Assert-Contains $queryStore $value 'InMemoryCodeKnowledgeStore'
    }

    $queryService = Read-RepoText 'minos-application\src\main\java\com\minos\application\ProjectQueryService.java'
    Assert-Contains $queryService 'loadActiveQueryView(project.id())' 'ProjectQueryService'
    Assert-NotContains $queryService 'new InMemoryCodeKnowledgeStore()' 'ProjectQueryService'

    $rootPom = Read-RepoText 'pom.xml'
    foreach ($value in @('jacoco.version','jacoco-maven-plugin','prepare-agent')) { Assert-Contains $rootPom $value 'pom.xml' }
    $appPom = Read-RepoText 'minos-app\pom.xml'
    Assert-Contains $appPom 'report-aggregate' 'minos-app/pom.xml'

    foreach ($required in @(
        '.github\workflows\pr-ci.yml',
        'scripts\quality\check-jacoco.py',
        'scripts\docs\product-facts.py',
        'docs\generated\product-facts.md',
        'docs\developer\quality-gates.md'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $required) -PathType Leaf)) { throw "Missing M15 quality/CI/docs artifact: $required" }
    }

    $legacyMain = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -ErrorAction SilentlyContinue)
    $legacyTests = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacyMain.Count -ne 0 -or $legacyTests.Count -ne 0) { throw 'M15 final must preserve the S2 physical module layout.' }

    $mainSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    $testSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    if ($mainSources.Count -ne 204) { throw "Unexpected final production source count: expected=204 actual=$($mainSources.Count)" }
    if ($testSources.Count -ne 99) { throw "Unexpected final test source count: expected=99 actual=$($testSources.Count)" }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M15 - FINAL exact-head qualification ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "M15 final runner requires a clean worktree.`n$($dirty -join "`n")" }

    Write-Host '[1/8] Fetching finalization branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
    }
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve final HEAD.' }
    Write-Host "HEAD: $head"

    Write-Host '[2/8] Checking M15 structural gates S2-S11...'
    Assert-FinalShape

    $python = Resolve-Python
    $pythonArgsPrefix = @()
    if ([System.IO.Path]::GetFileName($python) -ieq 'py.exe') { $pythonArgsPrefix = @('-3') }

    Write-Host '[3/8] Checking mechanically generated product facts...'
    Invoke-NativeChecked -File $python -Arguments ($pythonArgsPrefix + @('scripts/docs/product-facts.py','--check')) -Failure 'M15 product-facts consistency gate failed'

    Write-Host '[4/8] Running full exact-head build + M14/provider/Windows replay...'
    $baselineArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'),'-ExpectedHead',$head)
    if ($SkipM14Replay) { $baselineArgs += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $baselineArgs += '-SkipProviderReplays' }
    if ($ValidateDocker) { $baselineArgs += '-ValidateDocker' }
    $powershell = (Get-Command 'powershell.exe' -ErrorAction SilentlyContinue)
    if (-not $powershell) { $powershell = Get-Command 'pwsh' -ErrorAction Stop }
    Invoke-NativeChecked -File $powershell.Source -Arguments $baselineArgs -Failure 'M15 full baseline/M14 replay failed'

    $baseline = Get-Content -LiteralPath (Join-Path $RepoRoot 'target\m15-baseline\baseline.json') -Raw | ConvertFrom-Json
    if ($baseline.head -ne $head) { throw "Baseline head mismatch: expected=$head actual=$($baseline.head)" }
    if ($baseline.verifyStatus -ne 'PASS') { throw 'Final clean verify did not pass.' }
    if (-not $SkipM14Replay -and $baseline.m14ReplayStatus -ne 'PASS') { throw 'Final M14 replay did not pass.' }
    if ([long] $baseline.junit.tests -ne 258L -or [long] $baseline.junit.failures -ne 0L -or [long] $baseline.junit.errors -ne 0L) {
        throw "Unexpected final JUnit totals: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([int] $baseline.reactorModules -ne 13) { throw "Unexpected reactor project count: $($baseline.reactorModules)" }

    Write-Host '[5/8] Enforcing targeted JaCoCo gates...'
    $jacoco = Join-Path $RepoRoot 'target\site\jacoco-aggregate\jacoco.xml'
    Invoke-NativeChecked -File $python -Arguments ($pythonArgsPrefix + @('scripts/quality/check-jacoco.py',$jacoco)) -Failure 'M15 JaCoCo gate failed'

    Write-Host '[6/8] Measuring final active-snapshot cache and query indexes...'
    $queryArgs = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\capture-final-query.ps1'))
    Invoke-NativeChecked -File $powershell.Source -Arguments $queryArgs -Failure 'M15 final cache/index probe failed'

    $query = Get-Content -LiteralPath (Join-Path $RepoRoot 'target\m15-final\query-final.json') -Raw | ConvertFrom-Json
    if ([long] $query.full_snapshot_load_count -ne 1L -or [long] $query.query_view_build_count -ne 1L) {
        throw 'Final query path still reloads/rebuilds the active snapshot.'
    }

    Write-Host '[7/8] Rechecking exact HEAD after all gates...'
    $after = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($after -ne $head) { throw "HEAD changed during qualification: before=$head after=$after" }
    $dirtyAfter = @(& git status --porcelain)
    if ($dirtyAfter.Count -gt 0) { throw "Qualification modified tracked files.`n$($dirtyAfter -join "`n")" }

    Write-Host '[8/8] Final verdict'
    Write-Host ''
    if ($SkipM14Replay -or $SkipProviderReplays) {
        Write-Host 'M15 DIAGNOSTIC RUN SUCCESS, BUT NOT SUFFICIENT FOR FINAL CLOSURE' -ForegroundColor Yellow
        Write-Host "HEAD=$head tests=$($baseline.junit.tests) full-loads=$($query.full_snapshot_load_count)"
        exit 0
    }

    Write-Host 'M15 FINAL EXACT-HEAD VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "HEAD=$head Java=$($baseline.javaVersion) Maven=$($baseline.mavenVersionLine) reactor=$($baseline.reactorModules) sources=$($baseline.mainSourceCount)/$($baseline.testSourceCount) tests=$($baseline.junit.tests)"
    Write-Host "cache full-loads=$($query.full_snapshot_load_count) builds=$($query.query_view_build_count) hits=$($query.cache_hits) view-build=$($query.query_view_build_ms)ms"
    Write-Host "query first=$($query.first_query_latency_ms)ms p50=$($query.repeated_query_latency_p50_ms)ms p95=$($query.repeated_query_latency_p95_ms)ms heap=$($query.heap_after_load_bytes) indexRefs=$($query.index_references)"
}
finally {
    Pop-Location
}
