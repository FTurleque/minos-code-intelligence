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
    if ($env:OS -eq 'Windows_NT') {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    }
    throw 'Unable to resolve PowerShell host for runner restart.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    $hostPath = Resolve-CurrentPowerShellHost
    $runnerPath = Join-Path $RepoRoot 'scripts\m15\run-s2.ps1'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runnerPath)
    if ($SkipM14Replay) { $arguments += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $arguments += '-SkipProviderReplays' }
    if ($ValidateDocker) { $arguments += '-ValidateDocker' }
    Write-Host "Runner changed after pull; restarting from exact HEAD $Head..." -ForegroundColor Yellow
    & $hostPath @arguments
    if ($LASTEXITCODE -ne 0) { throw "Restarted M15-S2 runner failed (exit=$LASTEXITCODE)." }
}

function Get-Pom {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required POM is missing: $path" }
    return [xml] (Get-Content -LiteralPath $path -Raw)
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

function Assert-ExactCoordinates {
    param(
        [Parameter(Mandatory = $true)][string[]] $Actual,
        [Parameter(Mandatory = $true)][string[]] $Expected,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    if ($Actual.Count -ne $Expected.Count) {
        throw "$Owner dependency count mismatch. actual=[$($Actual -join ', ')] expected=[$($Expected -join ', ')]"
    }
    foreach ($coordinate in $Expected) {
        if ($Actual -notcontains $coordinate) { throw "$Owner is missing dependency $coordinate." }
    }
}

function Assert-ReactorShape {
    $rootPom = Get-Pom 'pom.xml'
    if ([string] $rootPom.project.packaging -ne 'pom' -or [string] $rootPom.project.artifactId -ne 'minos-parent') {
        throw 'M15-S2 root must remain com.minos:minos-parent with packaging=pom.'
    }

    $modules = @($rootPom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]') |
        ForEach-Object { $_.InnerText.Trim() })
    $expectedOrder = @(
        'minos-domain',
        'minos-engine',
        'minos-runtime-local',
        'minos-storage-local',
        'minos-provider-scip',
        'minos-integration-git',
        'minos-application',
        'minos-nexus',
        'minos-cli',
        'minos-api',
        'minos-mcp',
        'minos-app'
    )
    if ($modules.Count -ne $expectedOrder.Count) {
        throw "Unexpected reactor module count/order. actual=[$($modules -join ' -> ')]"
    }
    for ($i = 0; $i -lt $expectedOrder.Count; $i++) {
        if ($modules[$i] -ne $expectedOrder[$i]) {
            throw "Unexpected reactor order. Expected $($expectedOrder -join ' -> ')."
        }
    }

    $domainPom = Get-Pom 'minos-domain\pom.xml'
    if ((Get-CompilerIncludes $domainPom) -notcontains 'com/minos/domain/**/*.java') {
        throw 'minos-domain must own com/minos/domain/**/*.java.'
    }

    $enginePom = Get-Pom 'minos-engine\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $enginePom) @('com.minos:minos-domain') 'minos-engine'
    $engineIncludes = Get-CompilerIncludes $enginePom
    foreach ($include in @(
        'com/minos/query/**/*.java',
        'com/minos/store/CodeKnowledgeStore.java',
        'com/minos/discovery/ProjectDiscovery.java',
        'com/minos/orchestration/IndexerRegistry.java',
        'com/minos/orchestration/IndexingRuntimePorts.java'
    )) {
        if ($engineIncludes -notcontains $include) { throw "minos-engine must own $include." }
    }

    $runtimePom = Get-Pom 'minos-runtime-local\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $runtimePom) @('com.minos:minos-engine') 'minos-runtime-local'

    $storagePom = Get-Pom 'minos-storage-local\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $storagePom) @('com.minos:minos-engine') 'minos-storage-local'
    if ((Get-CompilerIncludes $storagePom) -notcontains 'com/minos/store/**/*.java' -or
        (Get-CompilerExcludes $storagePom) -notcontains 'com/minos/store/CodeKnowledgeStore.java') {
        throw 'minos-storage-local ownership is invalid.'
    }

    $providerPom = Get-Pom 'minos-provider-scip\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $providerPom) @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-storage-local',
        'com.minos:minos-runtime-local',
        'org.scip-code:scip-java-bindings'
    ) 'minos-provider-scip'
    if ((Get-CompilerIncludes $providerPom) -notcontains 'com/minos/adapter/scip/**/*.java') {
        throw 'minos-provider-scip must own com/minos/adapter/scip/**/*.java.'
    }

    $gitPom = Get-Pom 'minos-integration-git\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $gitPom) @('org.eclipse.jgit:org.eclipse.jgit') 'minos-integration-git'

    $applicationPom = Get-Pom 'minos-application\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $applicationPom) @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-storage-local'
    ) 'minos-application'
    $applicationIncludes = Get-CompilerIncludes $applicationPom
    foreach ($include in @(
        'com/minos/architecture/**/*.java',
        'com/minos/context/**/*.java',
        'com/minos/impact/**/*.java',
        'com/minos/incremental/**/*.java',
        'com/minos/registry/**/*.java',
        'com/minos/workspace/**/*.java',
        'com/minos/runtime/MinosVersion.java'
    )) {
        if ($applicationIncludes -notcontains $include) { throw "minos-application must own $include." }
    }
    foreach ($exclude in @('com/minos/discovery/ProjectDiscovery.java','com/minos/orchestration/IndexerRegistry.java','com/minos/orchestration/IndexingRuntimePorts.java')) {
        if ((Get-CompilerExcludes $applicationPom) -notcontains $exclude) { throw "minos-application must exclude engine-owned $exclude." }
    }

    $nexusPom = Get-Pom 'minos-nexus\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $nexusPom) @(
        'com.minos:minos-domain',
        'com.minos:minos-application',
        'com.minos:minos-storage-local'
    ) 'minos-nexus'
    if ((Get-CompilerIncludes $nexusPom) -notcontains 'com/minos/integration/nexus/**/*.java' -or
        (Get-CompilerExcludes $nexusPom) -notcontains 'com/minos/integration/nexus/NexusExportBridgeMain.java') {
        throw 'minos-nexus ownership is invalid.'
    }

    $cliPom = Get-Pom 'minos-cli\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $cliPom) @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-application',
        'com.minos:minos-storage-local',
        'com.minos:minos-provider-scip',
        'com.minos:minos-runtime-local',
        'com.minos:minos-nexus'
    ) 'minos-cli'
    if ((Get-CompilerIncludes $cliPom) -notcontains 'com/minos/cli/**/*.java' -or
        (Get-CompilerExcludes $cliPom) -notcontains 'com/minos/cli/MinosLauncher.java') {
        throw 'minos-cli must own CLI code except the system launcher.'
    }

    $apiPom = Get-Pom 'minos-api\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $apiPom) @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-application',
        'com.minos:minos-storage-local',
        'com.minos:minos-cli',
        'com.minos:minos-integration-git'
    ) 'minos-api'
    if ((Get-CompilerIncludes $apiPom) -notcontains 'com/minos/api/**/*.java') { throw 'minos-api ownership is invalid.' }

    $mcpPom = Get-Pom 'minos-mcp\pom.xml'
    Assert-ExactCoordinates (Get-DependencyCoordinates $mcpPom) @(
        'com.minos:minos-application',
        'com.minos:minos-cli',
        'io.modelcontextprotocol.sdk:mcp'
    ) 'minos-mcp'
    if ((Get-CompilerIncludes $mcpPom) -notcontains 'com/minos/mcp/**/*.java') { throw 'minos-mcp ownership is invalid.' }

    $mcpToolsSource = Get-Content -LiteralPath (Join-Path $RepoRoot 'src\main\java\com\minos\mcp\MinosMcpTools.java') -Raw
    $mcpServerSource = Get-Content -LiteralPath (Join-Path $RepoRoot 'src\main\java\com\minos\mcp\MinosMcpServer.java') -Raw
    if ($mcpToolsSource -match 'MinosLauncher' -or $mcpServerSource -match 'MinosLauncher') {
        throw 'minos-mcp must not depend on the minos-app system launcher.'
    }

    $appPom = Get-Pom 'minos-app\pom.xml'
    $appMinosDependencies = @(Get-DependencyCoordinates $appPom | Where-Object { $_ -like 'com.minos:*' })
    Assert-ExactCoordinates $appMinosDependencies @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-runtime-local',
        'com.minos:minos-storage-local',
        'com.minos:minos-provider-scip',
        'com.minos:minos-integration-git',
        'com.minos:minos-application',
        'com.minos:minos-nexus',
        'com.minos:minos-cli',
        'com.minos:minos-api',
        'com.minos:minos-mcp'
    ) 'minos-app'
    $appIncludes = Get-CompilerIncludes $appPom
    Assert-ExactCoordinates @($appIncludes | ForEach-Object { "source:$($_)" }) @(
        'source:com/minos/cli/MinosLauncher.java',
        'source:com/minos/integration/nexus/NexusExportBridgeMain.java'
    ) 'minos-app source ownership'
    foreach ($externalGroup in @('org.scip-code','org.eclipse.jgit','io.modelcontextprotocol.sdk')) {
        if (@(Get-DependencyCoordinates $appPom | Where-Object { $_ -like "$externalGroup:*" }).Count -ne 0) {
            throw "minos-app must not declare $externalGroup directly."
        }
    }
}

function Get-LatestJar {
    param(
        [Parameter(Mandatory = $true)][string] $Module,
        [Parameter(Mandatory = $true)][string] $Filter
    )
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$Module\target") -File -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw "$Module JAR is missing after reactor verification." }
    return $jar
}

function Assert-ContainsEntries {
    param(
        [Parameter(Mandatory = $true)][string[]] $Entries,
        [Parameter(Mandatory = $true)][string[]] $Required,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    foreach ($entry in $Required) {
        if ($Entries -notcontains $entry) { throw "$Owner does not own required entry $entry." }
    }
}

function Assert-NotContainsEntries {
    param(
        [Parameter(Mandatory = $true)][string[]] $Entries,
        [Parameter(Mandatory = $true)][string[]] $Forbidden,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    foreach ($entry in $Forbidden) {
        if ($Entries -contains $entry) { throw "$Owner unexpectedly owns $entry." }
    }
}

function Assert-ModuleOwnershipArtifacts {
    $domainJar = Get-LatestJar 'minos-domain' 'minos-domain-*.jar'
    $engineJar = Get-LatestJar 'minos-engine' 'minos-engine-*.jar'
    $runtimeJar = Get-LatestJar 'minos-runtime-local' 'minos-runtime-local-*.jar'
    $storageJar = Get-LatestJar 'minos-storage-local' 'minos-storage-local-*.jar'
    $providerJar = Get-LatestJar 'minos-provider-scip' 'minos-provider-scip-*.jar'
    $gitJar = Get-LatestJar 'minos-integration-git' 'minos-integration-git-*.jar'
    $applicationJar = Get-LatestJar 'minos-application' 'minos-application-*.jar'
    $nexusJar = Get-LatestJar 'minos-nexus' 'minos-nexus-*.jar'
    $cliJar = Get-LatestJar 'minos-cli' 'minos-cli-*.jar'
    $apiJar = Get-LatestJar 'minos-api' 'minos-api-*.jar'
    $mcpJar = Get-LatestJar 'minos-mcp' 'minos-mcp-*.jar'
    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shadedJar) { throw 'Final shaded MINOS JAR is missing.' }

    foreach ($relativePath in @(
        'target\classes\com\minos\domain\Symbol.class',
        'target\classes\com\minos\query\SymbolQueryService.class',
        'target\classes\com\minos\architecture\ArchitectureDependencyService.class',
        'target\classes\com\minos\runtime\CommandLocator.class',
        'target\classes\com\minos\store\FileSymbolSnapshotStore.class',
        'target\classes\com\minos\adapter\scip\ScipIndexReader.class',
        'target\classes\com\minos\git\GitIntelligenceService.class',
        'target\classes\com\minos\integration\nexus\NexusExportService.class',
        'target\classes\com\minos\cli\MinosCliRunner.class',
        'target\classes\com\minos\api\MinosApi.class',
        'target\classes\com\minos\mcp\MinosMcpServer.class'
    )) {
        $path = Join-Path $RepoRoot $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) { throw "minos-app directly owns extracted entry: $path" }
    }
    foreach ($relativePath in @(
        'target\classes\com\minos\cli\MinosLauncher.class',
        'target\classes\com\minos\integration\nexus\NexusExportBridgeMain.class'
    )) {
        $path = Join-Path $RepoRoot $relativePath
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "minos-app composition entry is missing: $path" }
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME unavailable for JAR inspection.' }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "JDK jar.exe is missing: $jarTool" }

    $entriesByOwner = @{
        Domain = @(& $jarTool tf $domainJar.FullName)
        Engine = @(& $jarTool tf $engineJar.FullName)
        Runtime = @(& $jarTool tf $runtimeJar.FullName)
        Storage = @(& $jarTool tf $storageJar.FullName)
        Provider = @(& $jarTool tf $providerJar.FullName)
        Git = @(& $jarTool tf $gitJar.FullName)
        Application = @(& $jarTool tf $applicationJar.FullName)
        Nexus = @(& $jarTool tf $nexusJar.FullName)
        Cli = @(& $jarTool tf $cliJar.FullName)
        Api = @(& $jarTool tf $apiJar.FullName)
        Mcp = @(& $jarTool tf $mcpJar.FullName)
        Shaded = @(& $jarTool tf $shadedJar.FullName)
    }
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect one or more module JARs.' }

    Assert-ContainsEntries $entriesByOwner.Domain @('com/minos/domain/Symbol.class') 'minos-domain'
    Assert-ContainsEntries $entriesByOwner.Engine @('com/minos/query/SymbolQueryService.class','com/minos/store/CodeKnowledgeStore.class','com/minos/discovery/ProjectDiscovery.class','com/minos/orchestration/IndexingRuntimePorts.class') 'minos-engine'
    Assert-ContainsEntries $entriesByOwner.Runtime @('com/minos/runtime/CommandLocator.class','com/minos/runtime/ProcessIndexerExecutor.class') 'minos-runtime-local'
    Assert-ContainsEntries $entriesByOwner.Storage @('com/minos/store/FileSymbolSnapshotStore.class','com/minos/store/InMemoryCodeKnowledgeStore.class') 'minos-storage-local'
    Assert-ContainsEntries $entriesByOwner.Provider @('com/minos/adapter/scip/ScipIndexReader.class','com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.class','com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1','com/minos/adapter/scip/runtime/ScipWriter.java') 'minos-provider-scip'
    Assert-ContainsEntries $entriesByOwner.Git @('com/minos/git/GitIntelligenceService.class') 'minos-integration-git'
    Assert-ContainsEntries $entriesByOwner.Application @('com/minos/architecture/ArchitectureDependencyService.class','com/minos/registry/LocalProjectRegistry.class','com/minos/runtime/MinosVersion.class') 'minos-application'
    Assert-ContainsEntries $entriesByOwner.Nexus @('com/minos/integration/nexus/NexusExportContract.class','com/minos/integration/nexus/NexusExportService.class') 'minos-nexus'
    Assert-NotContainsEntries $entriesByOwner.Nexus @('com/minos/integration/nexus/NexusExportBridgeMain.class') 'minos-nexus'
    Assert-ContainsEntries $entriesByOwner.Cli @('com/minos/cli/MinosCli.class','com/minos/cli/MinosCliRunner.class','com/minos/cli/LocalProjectOperations.class') 'minos-cli'
    Assert-NotContainsEntries $entriesByOwner.Cli @('com/minos/cli/MinosLauncher.class') 'minos-cli'
    Assert-ContainsEntries $entriesByOwner.Api @('com/minos/api/MinosApi.class','com/minos/api/LocalMinosApi.class') 'minos-api'
    Assert-ContainsEntries $entriesByOwner.Mcp @('com/minos/mcp/MinosMcpServer.class','com/minos/mcp/MinosMcpTools.class') 'minos-mcp'
    Assert-NotContainsEntries $entriesByOwner.Mcp @('com/minos/cli/MinosLauncher.class') 'minos-mcp'

    Assert-ContainsEntries $entriesByOwner.Shaded @(
        'com/minos/domain/Symbol.class',
        'com/minos/query/SymbolQueryService.class',
        'com/minos/architecture/ArchitectureDependencyService.class',
        'com/minos/runtime/CommandLocator.class',
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/adapter/scip/ScipIndexReader.class',
        'com/minos/git/GitIntelligenceService.class',
        'com/minos/integration/nexus/NexusExportService.class',
        'com/minos/integration/nexus/NexusExportBridgeMain.class',
        'com/minos/cli/MinosCliRunner.class',
        'com/minos/cli/MinosLauncher.class',
        'com/minos/api/MinosApi.class',
        'com/minos/mcp/MinosMcpServer.class'
    ) 'final shaded MINOS JAR'

    return [pscustomobject]@{
        DomainJar = $domainJar.FullName
        EngineJar = $engineJar.FullName
        RuntimeJar = $runtimeJar.FullName
        StorageJar = $storageJar.FullName
        ProviderJar = $providerJar.FullName
        GitJar = $gitJar.FullName
        ApplicationJar = $applicationJar.FullName
        NexusJar = $nexusJar.FullName
        CliJar = $cliJar.FullName
        ApiJar = $apiJar.FullName
        McpJar = $mcpJar.FullName
        ShadedJar = $shadedJar.FullName
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
    } else {
        Write-Host "[2/8] Already on '$Branch'."
    }
    if ($currentBranch -ne $Branch) { Write-Host "[2/8] Switched to '$Branch'." }

    Write-Host '[3/8] Fast-forwarding to latest remote head...'
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve exact HEAD.' }
    if ($head -ne $initialHead) {
        Restart-UpdatedRunner $head
        return
    }

    Write-Host '[4/8] Checking 12-project reactor and composition ownership shape...'
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
