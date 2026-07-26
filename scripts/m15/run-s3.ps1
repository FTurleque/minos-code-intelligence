[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s3-minos-application'
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
    $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    throw 'Unable to resolve PowerShell host for runner restart.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\run-s3.ps1'))
    if ($SkipM14Replay) { $arguments += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $arguments += '-SkipProviderReplays' }
    if ($ValidateDocker) { $arguments += '-ValidateDocker' }
    Write-Host "Runner changed after pull; restarting from exact HEAD $Head..." -ForegroundColor Yellow
    & (Resolve-CurrentPowerShellHost) @arguments
    if ($LASTEXITCODE -ne 0) { throw "Restarted M15-S3 runner failed (exit=$LASTEXITCODE)." }
}

function Read-RepoText {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
    [System.IO.File]::ReadAllText($path, $Utf8)
}

function Get-PomDependencies {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $text = Read-RepoText $RelativePath
    [xml] $pom = $text
    @($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencies"]/*[local-name()="dependency"]') |
        ForEach-Object { "$($_.groupId):$($_.artifactId)" })
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
    if ($Content.Contains($Forbidden)) { throw "$Owner still contains forbidden composition: $Forbidden" }
}

function Assert-S3Shape {
    $application = Read-RepoText 'minos-application\src\main\java\com\minos\application\MinosApplication.java'
    Assert-Contains $application 'public final class MinosApplication' 'MinosApplication'
    Assert-Contains $application 'public static MinosApplication open(Path home)' 'MinosApplication'
    Assert-Contains $application 'public static Builder builder(Path home)' 'MinosApplication'
    foreach ($signature in @(
        'ProviderRuntimeManager providerRuntimeManager()',
        'SnapshotStager snapshotStager()',
        'SnapshotPromoter snapshotPromoter()',
        'ProjectArchitectureQuery architectureQuery()',
        'ProjectImpactQuery impactQuery()',
        'WorkspaceIntelligenceService workspaceIntelligence()'
    )) {
        Assert-Contains $application $signature 'MinosApplication'
    }

    $applicationDependencies = @(Get-PomDependencies 'minos-application\pom.xml')
    $expectedApplicationDependencies = @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-runtime-local',
        'com.minos:minos-storage-local','com.minos:minos-provider-scip','com.minos:minos-integration-git'
    )
    if ($applicationDependencies.Count -ne $expectedApplicationDependencies.Count) {
        throw "minos-application dependency mismatch: $($applicationDependencies -join ', ')"
    }
    foreach ($dependency in $expectedApplicationDependencies) {
        if ($applicationDependencies -notcontains $dependency) { throw "minos-application is missing $dependency" }
    }

    $cliRunner = Read-RepoText 'minos-cli\src\main\java\com\minos\cli\MinosCliRunner.java'
    Assert-Contains $cliRunner 'MinosApplication application' 'MinosCliRunner'
    Assert-Contains $cliRunner 'new LocalProjectOperations(app)' 'MinosCliRunner'
    Assert-Contains $cliRunner 'new LocalAutonomousIndexOperations(app)' 'MinosCliRunner'
    Assert-NotContains $cliRunner 'new LocalProjectRegistry(' 'MinosCliRunner'
    Assert-NotContains $cliRunner 'new FileSymbolSnapshotStore(' 'MinosCliRunner'

    $projectOperations = Read-RepoText 'minos-cli\src\main\java\com\minos\cli\LocalProjectOperations.java'
    Assert-Contains $projectOperations 'LocalProjectOperations(MinosApplication application)' 'LocalProjectOperations'
    Assert-NotContains $projectOperations 'new LocalProjectRegistry(' 'LocalProjectOperations'
    Assert-NotContains $projectOperations 'new FileSymbolSnapshotStore(' 'LocalProjectOperations'
    Assert-NotContains $projectOperations 'ScipIndexerCatalog.qualifiedM1Descriptors()' 'LocalProjectOperations'

    $autonomous = Read-RepoText 'minos-cli\src\main\java\com\minos\cli\LocalAutonomousIndexOperations.java'
    Assert-Contains $autonomous 'LocalAutonomousIndexOperations(MinosApplication application)' 'LocalAutonomousIndexOperations'
    Assert-NotContains $autonomous 'new ManagedScipProviderRuntimeManager(' 'LocalAutonomousIndexOperations'
    Assert-NotContains $autonomous 'new ScipProjectSnapshotLifecycle(' 'LocalAutonomousIndexOperations'
    Assert-NotContains $autonomous 'ScipIndexerCatalog.qualifiedM1Descriptors()' 'LocalAutonomousIndexOperations'

    $api = Read-RepoText 'minos-api\src\main\java\com\minos\api\LocalMinosApi.java'
    Assert-Contains $api 'LocalMinosApi(MinosApplication application)' 'LocalMinosApi'
    Assert-NotContains $api 'new LocalProjectRegistry(' 'LocalMinosApi'
    Assert-NotContains $api 'new FileSymbolSnapshotStore(' 'LocalMinosApi'

    $multiApi = Read-RepoText 'minos-api\src\main\java\com\minos\api\LocalMinosMultiRepositoryApi.java'
    Assert-Contains $multiApi 'LocalMinosMultiRepositoryApi(MinosApplication application)' 'LocalMinosMultiRepositoryApi'
    Assert-Contains $multiApi 'app.workspaceIntelligence()' 'LocalMinosMultiRepositoryApi'
    Assert-Contains $multiApi 'app.gitIntelligence()' 'LocalMinosMultiRepositoryApi'
    Assert-NotContains $multiApi 'new LocalProjectRegistry(' 'LocalMinosMultiRepositoryApi'
    Assert-NotContains $multiApi 'new FileSymbolSnapshotStore(' 'LocalMinosMultiRepositoryApi'

    $mcpServer = Read-RepoText 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpServer.java'
    Assert-Contains $mcpServer 'run(MinosApplication application)' 'MinosMcpServer'
    Assert-Contains $mcpServer 'MinosMcpApplicationTools.specifications(app)' 'MinosMcpServer'
    $mcpBridge = Read-RepoText 'minos-mcp\src\main\java\com\minos\mcp\MinosApplicationCommandExecutor.java'
    Assert-Contains $mcpBridge 'MinosCliRunner.run(' 'MinosApplicationCommandExecutor'
    Assert-Contains $mcpBridge 'application,' 'MinosApplicationCommandExecutor'

    $launcher = Read-RepoText 'minos-app\src\main\java\com\minos\cli\MinosLauncher.java'
    Assert-Contains $launcher 'MinosApplication application = MinosApplication.open(home);' 'MinosLauncher'
    Assert-Contains $launcher 'MinosMcpServer.run(application);' 'MinosLauncher'
    Assert-Contains $launcher 'run(application, arguments, System.out, System.err)' 'MinosLauncher'

    $crossSurfaceTest = Read-RepoText 'minos-app\src\test\java\com\minos\application\SharedMinosApplicationIntegrationTest.java'
    Assert-Contains $crossSurfaceTest 'new LocalMinosApi(application)' 'SharedMinosApplicationIntegrationTest'
    Assert-Contains $crossSurfaceTest 'MinosCliRunner.run(' 'SharedMinosApplicationIntegrationTest'
    Assert-Contains $crossSurfaceTest 'new LocalMinosMultiRepositoryApi(application)' 'SharedMinosApplicationIntegrationTest'
    Assert-Contains $crossSurfaceTest 'MinosMcpApplicationTools.specifications(application)' 'SharedMinosApplicationIntegrationTest'

    $legacyMain = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -ErrorAction SilentlyContinue)
    $legacyTests = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacyMain.Count -ne 0 -or $legacyTests.Count -ne 0) {
        throw "S3 must preserve the S2 physical layout; legacy main=$($legacyMain.Count) tests=$($legacyTests.Count)."
    }

    $mainSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    $testSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    if ($mainSources.Count -ne 186) { throw "Unexpected S3 production source count: expected=186 actual=$($mainSources.Count)" }
    if ($testSources.Count -ne 94) { throw "Unexpected S3 test source count: expected=94 actual=$($testSources.Count)" }
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
    $mcpJar = Get-LatestJar 'minos-mcp' 'minos-mcp-*.jar'
    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shadedJar) { throw 'Final shaded MINOS JAR is missing.' }

    $applicationEntries = @(& $jarTool tf $applicationJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect minos-application JAR.' }
    $mcpEntries = @(& $jarTool tf $mcpJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect minos-mcp JAR.' }
    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect shaded JAR.' }

    foreach ($entry in @('com/minos/application/MinosApplication.class','com/minos/application/MinosApplication$Builder.class')) {
        if ($applicationEntries -notcontains $entry) { throw "minos-application does not own $entry" }
        if ($shadedEntries -notcontains $entry) { throw "shaded JAR does not contain $entry" }
    }
    foreach ($entry in @('com/minos/mcp/MinosApplicationCommandExecutor.class','com/minos/mcp/MinosMcpApplicationTools.class')) {
        if ($mcpEntries -notcontains $entry) { throw "minos-mcp does not own $entry" }
        if ($shadedEntries -notcontains $entry) { throw "shaded JAR does not contain $entry" }
    }

    [pscustomobject]@{
        ApplicationJar = $applicationJar.FullName
        McpJar = $mcpJar.FullName
        ShadedJar = $shadedJar.FullName
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M15-S3 - shared MinosApplication exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "M15-S3 runner requires a clean worktree.`n$($dirty -join "`n")" }

    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialHead)) { throw 'Unable to resolve initial HEAD.' }

    Write-Host '[1/7] Fetching M15-S3 branch...'
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

    Write-Host '[4/7] Checking shared composition shape and preserved S2 layout...'
    Assert-S3Shape

    . (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host '[5/7] Building application, API and MCP surfaces with their upstream modules...'
    Invoke-NativeChecked '.\mvnw.cmd' @('-pl','minos-api,minos-mcp','-am','test') 'Focused M15-S3 Maven verification failed'

    Write-Host "[6/7] Replaying full S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @parameters

    $baselinePath = Join-Path $RepoRoot 'target\m15-baseline\baseline.json'
    if (-not (Test-Path -LiteralPath $baselinePath -PathType Leaf)) { throw 'M15 baseline JSON is missing.' }
    $baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
    if ($baseline.verifyStatus -ne 'PASS') { throw "M15-S3 verify status is $($baseline.verifyStatus)." }
    if (-not $SkipM14Replay -and $baseline.m14ReplayStatus -ne 'PASS') { throw "M15-S3 M14 replay status is $($baseline.m14ReplayStatus)." }
    if ([long] $baseline.junit.tests -ne 241 -or [long] $baseline.junit.failures -ne 0 -or [long] $baseline.junit.errors -ne 0) {
        throw "M15-S3 test summary mismatch: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([long] $baseline.mainSourceCount -ne 186 -or [long] $baseline.testSourceCount -ne 94) {
        throw "M15-S3 source count mismatch: main=$($baseline.mainSourceCount) tests=$($baseline.testSourceCount)"
    }

    Write-Host '[7/7] Verifying application/MCP artifacts and repeated-query baseline...' -ForegroundColor Cyan
    $artifacts = Assert-Artifacts
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host 'M15-S3 SHARED APPLICATION VALIDATION SUCCESS' -ForegroundColor Green
    } else {
        Write-Host 'M15-S3 diagnostic validation finished (not sufficient to close S3)' -ForegroundColor Yellow
    }
    Write-Host "HEAD            : $head"
    Write-Host "Application JAR : $($artifacts.ApplicationJar)"
    Write-Host "MCP JAR         : $($artifacts.McpJar)"
    Write-Host "Shaded JAR      : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
