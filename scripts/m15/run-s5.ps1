[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s5-project-resolution'
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Resolve-CurrentPowerShellHost {
    try {
        $hostPath = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($hostPath) -and (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
            return $hostPath
        }
    } catch { }
    if ($env:OS -eq 'Windows_NT' -and -not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    }
    foreach ($name in @('pwsh.exe','powershell.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'Unable to resolve PowerShell host for runner restart.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\run-s5.ps1'))
    if ($SkipM14Replay) { $arguments += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $arguments += '-SkipProviderReplays' }
    if ($ValidateDocker) { $arguments += '-ValidateDocker' }
    Write-Host "Runner changed after pull; restarting from exact HEAD $Head..." -ForegroundColor Yellow
    & (Resolve-CurrentPowerShellHost) @arguments
    if ($LASTEXITCODE -ne 0) { throw "Restarted M15-S5 runner failed (exit=$LASTEXITCODE)." }
}

function Read-RepoText {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
    [System.IO.File]::ReadAllText($path, $Utf8)
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string] $Content,
        [Parameter(Mandatory = $true)][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    if (-not $Content.Contains($Expected)) { throw "$Owner must contain: $Expected" }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string] $Content,
        [Parameter(Mandatory = $true)][string] $Forbidden,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    if ($Content.Contains($Forbidden)) { throw "$Owner still contains forbidden duplicated project resolution: $Forbidden" }
}

function Assert-S5Shape {
    $resolver = Read-RepoText 'minos-application\src\main\java\com\minos\application\ProjectResolver.java'
    foreach ($required in @(
        'RegisteredProject resolve(String reference)',
        'RegisteredProject resolveById(UUID projectId)',
        'RegisteredProject resolveByName(String displayName)',
        'List<RegisteredProject> listCandidates(String reference)',
        'PROJECT_NOT_FOUND',
        'PROJECT_REFERENCE_AMBIGUOUS',
        'INVALID_PROJECT_REFERENCE'
    )) {
        Assert-Contains $resolver $required 'ProjectResolver'
    }

    $consumers = @(
        @{ Name = 'ProjectQueryService'; Path = 'minos-application\src\main\java\com\minos\application\ProjectQueryService.java' },
        @{ Name = 'ProjectInspectionService'; Path = 'minos-application\src\main\java\com\minos\application\ProjectInspectionService.java' },
        @{ Name = 'LocalProjectArchitectureQuery'; Path = 'minos-application\src\main\java\com\minos\architecture\LocalProjectArchitectureQuery.java' },
        @{ Name = 'LocalProjectImpactQuery'; Path = 'minos-application\src\main\java\com\minos\impact\LocalProjectImpactQuery.java' },
        @{ Name = 'LocalProjectOperations'; Path = 'minos-cli\src\main\java\com\minos\cli\LocalProjectOperations.java' },
        @{ Name = 'LocalAutonomousIndexOperations'; Path = 'minos-cli\src\main\java\com\minos\cli\LocalAutonomousIndexOperations.java' }
    )
    foreach ($consumer in $consumers) {
        $content = Read-RepoText $consumer.Path
        Assert-Contains $content 'ProjectResolver' $consumer.Name
        Assert-Contains $content 'projectResolver.resolve(' $consumer.Name
        foreach ($forbidden in @('UUID.fromString(', 'ambiguous project name, use its UUID:', 'unknown project: ')) {
            Assert-NotContains $content $forbidden $consumer.Name
        }
    }

    $resolverTest = Read-RepoText 'minos-application\src\test\java\com\minos\application\ProjectResolverTest.java'
    foreach ($expected in @(
        'resolvesUuidAndExactDisplayName',
        'reportsInvalidAndMissingReferencesWithStableDiagnostics',
        'reportsAmbiguousDisplayNameAndDeterministicCandidates',
        'PROJECT_NOT_FOUND',
        'PROJECT_REFERENCE_AMBIGUOUS',
        'INVALID_PROJECT_REFERENCE'
    )) {
        Assert-Contains $resolverTest $expected 'ProjectResolverTest'
    }

    $mcpBackend = Read-RepoText 'minos-mcp\src\main\java\com\minos\mcp\MinosApplicationMcpBackend.java'
    foreach ($forbidden in @('UUID.fromString(', 'ambiguous project name, use its UUID:', 'unknown project: ')) {
        Assert-NotContains $mcpBackend $forbidden 'MinosApplicationMcpBackend'
    }

    $api = Read-RepoText 'minos-api\src\main\java\com\minos\api\LocalMinosApi.java'
    foreach ($forbidden in @('ambiguous project name, use its UUID:', 'unknown project: ')) {
        Assert-NotContains $api $forbidden 'LocalMinosApi'
    }

    $legacyMain = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -ErrorAction SilentlyContinue)
    $legacyTests = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacyMain.Count -ne 0 -or $legacyTests.Count -ne 0) {
        throw "S5 must preserve the S2 physical layout; legacy main=$($legacyMain.Count) tests=$($legacyTests.Count)."
    }

    $mainSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    $testSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    if ($mainSources.Count -ne 194) { throw "Unexpected S5 production source count: expected=194 actual=$($mainSources.Count)" }
    if ($testSources.Count -ne 95) { throw "Unexpected S5 test source count: expected=95 actual=$($testSources.Count)" }
}

function Get-LatestJar {
    param([Parameter(Mandatory = $true)][string] $Module, [Parameter(Mandatory = $true)][string] $Filter)
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$Module\target") -File -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw "$Module JAR is missing after verification." }
    $jar
}

function Assert-Artifacts {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME unavailable for JAR inspection.' }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "JDK jar.exe is missing: $jarTool" }

    $applicationJar = Get-LatestJar 'minos-application' 'minos-application-*.jar'
    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shadedJar) { throw 'Final shaded MINOS JAR is missing.' }

    $applicationEntries = @(& $jarTool tf $applicationJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect minos-application JAR.' }
    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect shaded JAR.' }

    foreach ($entry in @(
        'com/minos/application/ProjectResolver.class',
        'com/minos/application/ProjectResolver$ErrorCode.class',
        'com/minos/application/ProjectResolver$ResolutionException.class'
    )) {
        if ($applicationEntries -notcontains $entry) { throw "minos-application does not own $entry" }
        if ($shadedEntries -notcontains $entry) { throw "shaded JAR does not contain $entry" }
    }

    [pscustomobject]@{
        ApplicationJar = $applicationJar.FullName
        ShadedJar = $shadedJar.FullName
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M15-S5 - common project resolution exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "M15-S5 runner requires a clean worktree.`n$($dirty -join "`n")" }

    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialHead)) { throw 'Unable to resolve initial HEAD.' }

    Write-Host '[1/7] Fetching M15-S5 branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
        Write-Host "[2/7] Switched to '$Branch'."
    } else {
        Write-Host "[2/7] Already on '$Branch'."
    }

    Write-Host '[3/7] Fast-forwarding to latest remote head...'
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve exact HEAD.' }
    if ($head -ne $initialHead) { Restart-UpdatedRunner $head; return }

    Write-Host '[4/7] Checking common project-resolution shape and preserved boundaries...'
    Assert-S5Shape

    . (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host '[5/7] Building CLI, API and MCP surfaces with shared resolution upstreams...'
    Invoke-NativeChecked '.\mvnw.cmd' @('-pl','minos-cli,minos-api,minos-mcp','-am','test') 'Focused M15-S5 Maven verification failed'

    Write-Host "[6/7] Replaying full S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @parameters

    $baselinePath = Join-Path $RepoRoot 'target\m15-baseline\baseline.json'
    if (-not (Test-Path -LiteralPath $baselinePath -PathType Leaf)) { throw 'M15 baseline JSON is missing.' }
    $baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
    if ($baseline.verifyStatus -ne 'PASS') { throw "M15-S5 verify status is $($baseline.verifyStatus)." }
    if (-not $SkipM14Replay -and $baseline.m14ReplayStatus -ne 'PASS') { throw "M15-S5 M14 replay status is $($baseline.m14ReplayStatus)." }
    if ([long] $baseline.junit.tests -ne 245 -or [long] $baseline.junit.failures -ne 0 -or [long] $baseline.junit.errors -ne 0) {
        throw "M15-S5 test summary mismatch: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([long] $baseline.mainSourceCount -ne 194 -or [long] $baseline.testSourceCount -ne 95) {
        throw "M15-S5 source count mismatch: main=$($baseline.mainSourceCount) tests=$($baseline.testSourceCount)"
    }

    Write-Host '[7/7] Verifying resolver ownership and repeated-query baseline...' -ForegroundColor Cyan
    $artifacts = Assert-Artifacts
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host 'M15-S5 COMMON PROJECT RESOLUTION VALIDATION SUCCESS' -ForegroundColor Green
    } else {
        Write-Host 'M15-S5 diagnostic validation finished (not sufficient to close S5)' -ForegroundColor Yellow
    }
    Write-Host "HEAD            : $head"
    Write-Host "Application JAR : $($artifacts.ApplicationJar)"
    Write-Host "Shaded JAR      : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
