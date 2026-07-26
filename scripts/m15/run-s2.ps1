[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s2-maven-multimodule'

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

function Ensure-WindowsPowerShellOnPath {
    if ($env:OS -ne 'Windows_NT') { return }
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
        throw "Windows PowerShell executable not found: $windowsPowerShell"
    }
    $directory = Split-Path -Parent $windowsPowerShell
    $present = @($env:Path -split ';') | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
        $_.TrimEnd('\').Equals($directory.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1
    if ($null -eq $present) { $env:Path = "$directory;$env:Path" }
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
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\run-s2.ps1'))
    if ($SkipM14Replay) { $arguments += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $arguments += '-SkipProviderReplays' }
    if ($ValidateDocker) { $arguments += '-ValidateDocker' }
    Write-Host "Restarting M15-S2 runner from exact HEAD $Head..." -ForegroundColor Yellow
    & (Resolve-CurrentPowerShellHost) @arguments
    if ($LASTEXITCODE -ne 0) { throw "Restarted M15-S2 runner failed (exit=$LASTEXITCODE)." }
}

function Get-Pom {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required POM is missing: $path" }
    [xml] (Get-Content -LiteralPath $path -Raw)
}

function Get-CompilerIncludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="includes"]/*[local-name()="include"]') |
        ForEach-Object { $_.InnerText.Trim() })
}

function Get-CompilerExcludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="excludes"]/*[local-name()="exclude"]') |
        ForEach-Object { $_.InnerText.Trim() })
}

function Get-DependencyCoordinates {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencies"]/*[local-name()="dependency"]') |
        ForEach-Object { "$($_.groupId):$($_.artifactId)" })
}

function Assert-DependencySet {
    param(
        [Parameter(Mandatory = $true)][xml] $Pom,
        [Parameter(Mandatory = $true)][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    $actual = @(Get-DependencyCoordinates $Pom)
    if ($actual.Count -ne $Expected.Count) {
        throw "$Owner dependency mismatch. actual=[$($actual -join ', ')] expected=[$($Expected -join ', ')]"
    }
    foreach ($coordinate in $Expected) {
        if ($actual -notcontains $coordinate) { throw "$Owner is missing dependency $coordinate." }
    }
}

function Get-ModuleMainSourceCount {
    param([Parameter(Mandatory = $true)][string] $Module)
    $root = Join-Path $RepoRoot "$Module\src\main\java"
    @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue).Count
}

function Assert-PhysicalSourceLayout {
    $expectedCounts = @{
        'minos-domain' = 24
        'minos-engine' = 17
        'minos-runtime-local' = 6
        'minos-storage-local' = 4
        'minos-provider-scip' = 22
        'minos-integration-git' = 1
        'minos-application' = 75
        'minos-nexus' = 2
        'minos-cli' = 24
        'minos-api' = 4
        'minos-mcp' = 2
        'minos-app' = 2
    }

    $legacySources = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacySources.Count -ne 0) {
        throw "Historical src/main/java must be empty after physical relocation; found $($legacySources.Count) file(s)."
    }

    foreach ($module in $expectedCounts.Keys) {
        $actual = Get-ModuleMainSourceCount -Module $module
        if ($actual -ne [int] $expectedCounts[$module]) {
            throw "$module physical source count mismatch: expected=$($expectedCounts[$module]) actual=$actual"
        }
        $pom = Get-Pom "$module\pom.xml"
        $sourceNode = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="sourceDirectory"]')
        if ($null -ne $sourceNode) {
            throw "$module still declares a non-standard sourceDirectory: $($sourceNode.InnerText.Trim())"
        }
    }

    $legacyResources = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\resources') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacyResources.Count -ne 0) {
        throw "Historical src/main/resources must be empty after physical relocation; found $($legacyResources.Count) file(s)."
    }

    foreach ($resource in @(
        'minos-provider-scip\src\main\resources\com\minos\adapter\scip\runtime\scip-java-windows-runner.ps1',
        'minos-provider-scip\src\main\resources\com\minos\adapter\scip\runtime\ScipWriter.java'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $resource) -PathType Leaf)) {
            throw "Relocated SCIP runtime resource is missing: $resource"
        }
    }

    $providerPom = Get-Pom 'minos-provider-scip\pom.xml'
    $resourceDirectory = $providerPom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="resources"]/*[local-name()="resource"]/*[local-name()="directory"]')
    if ($null -ne $resourceDirectory -and $resourceDirectory.InnerText -match 'maven.multiModuleProjectDirectory') {
        throw 'minos-provider-scip still uses an external production resource root.'
    }
}

function Assert-ReactorShape {
    $rootPom = Get-Pom 'pom.xml'
    $modules = @($rootPom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]') |
        ForEach-Object { $_.InnerText.Trim() })
    $expected = @(
        'minos-domain','minos-engine','minos-runtime-local','minos-storage-local',
        'minos-provider-scip','minos-integration-git','minos-application','minos-nexus',
        'minos-cli','minos-api','minos-mcp','minos-app'
    )
    if ($modules.Count -ne $expected.Count) { throw "Unexpected reactor modules: $($modules -join ' -> ')" }
    for ($i = 0; $i -lt $expected.Count; $i++) {
        if ($modules[$i] -ne $expected[$i]) { throw "Unexpected reactor order. Expected $($expected -join ' -> ')." }
    }

    Assert-DependencySet (Get-Pom 'minos-engine\pom.xml') @('com.minos:minos-domain') 'minos-engine'
    Assert-DependencySet (Get-Pom 'minos-runtime-local\pom.xml') @('com.minos:minos-engine') 'minos-runtime-local'
    Assert-DependencySet (Get-Pom 'minos-storage-local\pom.xml') @('com.minos:minos-engine') 'minos-storage-local'
    Assert-DependencySet (Get-Pom 'minos-provider-scip\pom.xml') @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-storage-local',
        'com.minos:minos-runtime-local','org.scip-code:scip-java-bindings'
    ) 'minos-provider-scip'
    Assert-DependencySet (Get-Pom 'minos-integration-git\pom.xml') @('org.eclipse.jgit:org.eclipse.jgit') 'minos-integration-git'
    Assert-DependencySet (Get-Pom 'minos-application\pom.xml') @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-storage-local'
    ) 'minos-application'
    Assert-DependencySet (Get-Pom 'minos-nexus\pom.xml') @(
        'com.minos:minos-domain','com.minos:minos-application','com.minos:minos-storage-local'
    ) 'minos-nexus'
    Assert-DependencySet (Get-Pom 'minos-cli\pom.xml') @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-application',
        'com.minos:minos-storage-local','com.minos:minos-provider-scip','com.minos:minos-runtime-local','com.minos:minos-nexus'
    ) 'minos-cli'
    Assert-DependencySet (Get-Pom 'minos-api\pom.xml') @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-application',
        'com.minos:minos-storage-local','com.minos:minos-cli','com.minos:minos-integration-git'
    ) 'minos-api'
    Assert-DependencySet (Get-Pom 'minos-mcp\pom.xml') @(
        'com.minos:minos-application','com.minos:minos-cli','io.modelcontextprotocol.sdk:mcp'
    ) 'minos-mcp'

    $applicationPom = Get-Pom 'minos-application\pom.xml'
    foreach ($include in @('com/minos/architecture/**/*.java','com/minos/context/**/*.java','com/minos/impact/**/*.java','com/minos/incremental/**/*.java','com/minos/registry/**/*.java','com/minos/workspace/**/*.java','com/minos/runtime/MinosVersion.java')) {
        if ((Get-CompilerIncludes $applicationPom) -notcontains $include) { throw "minos-application must own $include." }
    }
    foreach ($exclude in @('com/minos/discovery/ProjectDiscovery.java','com/minos/orchestration/IndexerRegistry.java','com/minos/orchestration/IndexingRuntimePorts.java')) {
        if ((Get-CompilerExcludes $applicationPom) -notcontains $exclude) { throw "minos-application must exclude $exclude." }
    }

    $nexusPom = Get-Pom 'minos-nexus\pom.xml'
    if ((Get-CompilerExcludes $nexusPom) -notcontains 'com/minos/integration/nexus/NexusExportBridgeMain.java') {
        throw 'minos-nexus must leave NexusExportBridgeMain to minos-app.'
    }
    $cliPom = Get-Pom 'minos-cli\pom.xml'
    if ((Get-CompilerExcludes $cliPom) -notcontains 'com/minos/cli/MinosLauncher.java') {
        throw 'minos-cli must leave MinosLauncher to minos-app.'
    }

    $mcpToolsPath = Join-Path $RepoRoot 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpTools.java'
    $mcpServerPath = Join-Path $RepoRoot 'minos-mcp\src\main\java\com\minos\mcp\MinosMcpServer.java'
    $mcpTools = Get-Content -LiteralPath $mcpToolsPath -Raw
    $mcpServer = Get-Content -LiteralPath $mcpServerPath -Raw
    if ($mcpTools -match 'MinosLauncher' -or $mcpServer -match 'MinosLauncher') {
        throw 'MCP must not depend on the minos-app system launcher.'
    }

    $appPom = Get-Pom 'minos-app\pom.xml'
    $appMinosDependencies = @(Get-DependencyCoordinates $appPom | Where-Object { $_ -like 'com.minos:*' })
    $expectedApp = @(
        'com.minos:minos-domain','com.minos:minos-engine','com.minos:minos-runtime-local','com.minos:minos-storage-local',
        'com.minos:minos-provider-scip','com.minos:minos-integration-git','com.minos:minos-application','com.minos:minos-nexus',
        'com.minos:minos-cli','com.minos:minos-api','com.minos:minos-mcp'
    )
    if ($appMinosDependencies.Count -ne $expectedApp.Count) { throw "minos-app MINOS dependency mismatch: $($appMinosDependencies -join ', ')" }
    foreach ($coordinate in $expectedApp) {
        if ($appMinosDependencies -notcontains $coordinate) { throw "minos-app is missing $coordinate." }
    }
    foreach ($externalGroup in @('org.scip-code','org.eclipse.jgit','io.modelcontextprotocol.sdk')) {
        if (@(Get-DependencyCoordinates $appPom | Where-Object { $_ -like "${externalGroup}:*" }).Count -ne 0) {
            throw "minos-app must not declare $externalGroup directly."
        }
    }
    $appIncludes = @(Get-CompilerIncludes $appPom)
    if ($appIncludes.Count -ne 2 -or
        $appIncludes -notcontains 'com/minos/cli/MinosLauncher.java' -or
        $appIncludes -notcontains 'com/minos/integration/nexus/NexusExportBridgeMain.java') {
        throw "minos-app must compile only the two composition entry points; actual=[$($appIncludes -join ', ')]"
    }

    Assert-PhysicalSourceLayout
}

function Get-LatestJar {
    param([string] $Module, [string] $Filter)
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$Module\target") -File -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw "$Module JAR is missing after reactor verification." }
    $jar
}

function Assert-Entries {
    param([string[]] $Entries, [string[]] $Required, [string] $Owner)
    foreach ($entry in $Required) {
        if ($Entries -notcontains $entry) { throw "$Owner does not own required entry $entry." }
    }
}

function Assert-ModuleOwnershipArtifacts {
    $jars = @{
        Domain = Get-LatestJar 'minos-domain' 'minos-domain-*.jar'
        Engine = Get-LatestJar 'minos-engine' 'minos-engine-*.jar'
        Runtime = Get-LatestJar 'minos-runtime-local' 'minos-runtime-local-*.jar'
        Storage = Get-LatestJar 'minos-storage-local' 'minos-storage-local-*.jar'
        Provider = Get-LatestJar 'minos-provider-scip' 'minos-provider-scip-*.jar'
        Git = Get-LatestJar 'minos-integration-git' 'minos-integration-git-*.jar'
        Application = Get-LatestJar 'minos-application' 'minos-application-*.jar'
        Nexus = Get-LatestJar 'minos-nexus' 'minos-nexus-*.jar'
        Cli = Get-LatestJar 'minos-cli' 'minos-cli-*.jar'
        Api = Get-LatestJar 'minos-api' 'minos-api-*.jar'
        Mcp = Get-LatestJar 'minos-mcp' 'minos-mcp-*.jar'
    }
    $shaded = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shaded) { throw 'Final shaded MINOS JAR is missing.' }

    foreach ($relative in @(
        'target\classes\com\minos\domain\Symbol.class','target\classes\com\minos\query\SymbolQueryService.class',
        'target\classes\com\minos\architecture\ArchitectureDependencyService.class','target\classes\com\minos\runtime\CommandLocator.class',
        'target\classes\com\minos\store\FileSymbolSnapshotStore.class','target\classes\com\minos\adapter\scip\ScipIndexReader.class',
        'target\classes\com\minos\git\GitIntelligenceService.class','target\classes\com\minos\integration\nexus\NexusExportService.class',
        'target\classes\com\minos\cli\MinosCliRunner.class','target\classes\com\minos\api\MinosApi.class',
        'target\classes\com\minos\mcp\MinosMcpServer.class'
    )) {
        if (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf) { throw "minos-app directly owns extracted entry: $relative" }
    }
    foreach ($relative in @('target\classes\com\minos\cli\MinosLauncher.class','target\classes\com\minos\integration\nexus\NexusExportBridgeMain.class')) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf)) { throw "minos-app composition entry is missing: $relative" }
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME unavailable for JAR inspection.' }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "JDK jar.exe is missing: $jarTool" }

    $entries = @{}
    foreach ($name in $jars.Keys) { $entries[$name] = @(& $jarTool tf $jars[$name].FullName) }
    $entries['Shaded'] = @(& $jarTool tf $shaded.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect one or more module JARs.' }

    Assert-Entries $entries.Domain @('com/minos/domain/Symbol.class') 'minos-domain'
    Assert-Entries $entries.Engine @('com/minos/query/SymbolQueryService.class','com/minos/store/CodeKnowledgeStore.class','com/minos/discovery/ProjectDiscovery.class','com/minos/orchestration/IndexingRuntimePorts.class') 'minos-engine'
    Assert-Entries $entries.Runtime @('com/minos/runtime/CommandLocator.class','com/minos/runtime/ProcessIndexerExecutor.class') 'minos-runtime-local'
    Assert-Entries $entries.Storage @('com/minos/store/FileSymbolSnapshotStore.class') 'minos-storage-local'
    Assert-Entries $entries.Provider @('com/minos/adapter/scip/ScipIndexReader.class','com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.class','com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1','com/minos/adapter/scip/runtime/ScipWriter.java') 'minos-provider-scip'
    Assert-Entries $entries.Git @('com/minos/git/GitIntelligenceService.class') 'minos-integration-git'
    Assert-Entries $entries.Application @('com/minos/architecture/ArchitectureDependencyService.class','com/minos/registry/LocalProjectRegistry.class','com/minos/runtime/MinosVersion.class') 'minos-application'
    Assert-Entries $entries.Nexus @('com/minos/integration/nexus/NexusExportContract.class','com/minos/integration/nexus/NexusExportService.class') 'minos-nexus'
    if ($entries.Nexus -contains 'com/minos/integration/nexus/NexusExportBridgeMain.class') { throw 'minos-nexus owns the process bridge unexpectedly.' }
    Assert-Entries $entries.Cli @('com/minos/cli/MinosCli.class','com/minos/cli/MinosCliRunner.class','com/minos/cli/LocalProjectOperations.class') 'minos-cli'
    if ($entries.Cli -contains 'com/minos/cli/MinosLauncher.class') { throw 'minos-cli owns the system launcher unexpectedly.' }
    Assert-Entries $entries.Api @('com/minos/api/MinosApi.class','com/minos/api/LocalMinosApi.class') 'minos-api'
    Assert-Entries $entries.Mcp @('com/minos/mcp/MinosMcpServer.class','com/minos/mcp/MinosMcpTools.class') 'minos-mcp'

    Assert-Entries $entries.Shaded @(
        'com/minos/domain/Symbol.class','com/minos/query/SymbolQueryService.class','com/minos/architecture/ArchitectureDependencyService.class',
        'com/minos/runtime/CommandLocator.class','com/minos/store/FileSymbolSnapshotStore.class','com/minos/adapter/scip/ScipIndexReader.class',
        'com/minos/git/GitIntelligenceService.class','com/minos/integration/nexus/NexusExportService.class',
        'com/minos/integration/nexus/NexusExportBridgeMain.class','com/minos/cli/MinosCliRunner.class','com/minos/cli/MinosLauncher.class',
        'com/minos/api/MinosApi.class','com/minos/mcp/MinosMcpServer.class'
    ) 'final shaded MINOS JAR'

    [pscustomobject]@{
        DomainJar = $jars.Domain.FullName; EngineJar = $jars.Engine.FullName; RuntimeJar = $jars.Runtime.FullName
        StorageJar = $jars.Storage.FullName; ProviderJar = $jars.Provider.FullName; GitJar = $jars.Git.FullName
        ApplicationJar = $jars.Application.FullName; NexusJar = $jars.Nexus.FullName; CliJar = $jars.Cli.FullName
        ApiJar = $jars.Api.FullName; McpJar = $jars.Mcp.FullName; ShadedJar = $shaded.FullName
    }
}

Push-Location $RepoRoot
try {
    Ensure-WindowsPowerShellOnPath
    Write-Host '=== MINOS M15-S2 - reactor + module-boundary exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "M15-S2 runner requires a clean worktree.`n$($dirty -join "`n")" }

    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialHead)) { throw 'Unable to resolve initial HEAD.' }

    Write-Host '[1/8] Fetching M15-S2 branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
    } else { Write-Host "[2/8] Already on '$Branch'." }
    if ($currentBranch -ne $Branch) { Write-Host "[2/8] Switched to '$Branch'." }

    Write-Host '[3/8] Fast-forwarding to latest remote head...'
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve exact HEAD.' }
    if ($head -ne $initialHead) { Restart-UpdatedRunner $head; return }

    $legacySources = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    if ($legacySources.Count -gt 0) {
        $relocator = Join-Path $RepoRoot 'scripts\m15\relocate-main-sources.ps1'
        if (-not (Test-Path -LiteralPath $relocator -PathType Leaf)) {
            throw "Physical relocation is pending but helper is missing: $relocator"
        }
        Write-Host ''
        Write-Host "M15-S2 one-time production relocation required ($($legacySources.Count) sources)." -ForegroundColor Cyan
        & $relocator
        $relocatedHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($relocatedHead)) {
            throw 'Unable to resolve HEAD after production relocation.'
        }
        Restart-UpdatedRunner $relocatedHead
        return
    }

    Write-Host '[4/8] Checking 12-module reactor and physical production-source ownership shape...'
    Assert-ReactorShape

    Write-Host '[5/8] Building API/MCP surfaces and all upstream modules independently of minos-app...'
    Invoke-NativeChecked '.\mvnw.cmd' @('-pl','minos-api,minos-mcp','-am','test') 'Focused public-surface Maven verification failed'

    Write-Host "[6/8] Replaying full S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @parameters

    Write-Host '[7/8] Verifying module JARs and two-class minos-app ownership...' -ForegroundColor Cyan
    $artifacts = Assert-ModuleOwnershipArtifacts

    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host '[8/8] Capturing repeated-query cost baseline...' -ForegroundColor Cyan
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    } else {
        Write-Host '[8/8] Repeated-query baseline skipped because full replay was disabled.' -ForegroundColor Yellow
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host 'M15-S2 FULL MODULE-BOUNDARY VALIDATION SUCCESS' -ForegroundColor Green
    } else {
        Write-Host 'M15-S2 diagnostic module-boundary validation finished (not sufficient to close S2)' -ForegroundColor Yellow
    }
    Write-Host "HEAD            : $head"
    Write-Host "Domain JAR      : $($artifacts.DomainJar)"
    Write-Host "Engine JAR      : $($artifacts.EngineJar)"
    Write-Host "Runtime JAR     : $($artifacts.RuntimeJar)"
    Write-Host "Storage JAR     : $($artifacts.StorageJar)"
    Write-Host "Provider JAR    : $($artifacts.ProviderJar)"
    Write-Host "Git JAR         : $($artifacts.GitJar)"
    Write-Host "Application JAR : $($artifacts.ApplicationJar)"
    Write-Host "NEXUS JAR       : $($artifacts.NexusJar)"
    Write-Host "CLI JAR         : $($artifacts.CliJar)"
    Write-Host "API JAR         : $($artifacts.ApiJar)"
    Write-Host "MCP JAR         : $($artifacts.McpJar)"
    Write-Host "Shaded JAR      : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
