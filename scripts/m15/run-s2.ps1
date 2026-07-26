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
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)"
    }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Failure (exit=$LASTEXITCODE)"
    }
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
    $Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="includes"]/*[local-name()="include"]') |
        ForEach-Object { $_.InnerText.Trim() }
}

function Get-CompilerExcludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    $Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="excludes"]/*[local-name()="exclude"]') |
        ForEach-Object { $_.InnerText.Trim() }
}

function Get-ResourceIncludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    $Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="resources"]/*[local-name()="resource"]/*[local-name()="includes"]/*[local-name()="include"]') |
        ForEach-Object { $_.InnerText.Trim() }
}

function Get-ResourceExcludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    $Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="resources"]/*[local-name()="resource"]/*[local-name()="excludes"]/*[local-name()="exclude"]') |
        ForEach-Object { $_.InnerText.Trim() }
}

function Get-Dependencies {
    param([Parameter(Mandatory = $true)][xml] $Pom)
    $Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencies"]/*[local-name()="dependency"]')
}

function Assert-OnlyDependency {
    param(
        [Parameter(Mandatory = $true)][xml] $Pom,
        [Parameter(Mandatory = $true)][string] $GroupId,
        [Parameter(Mandatory = $true)][string] $ArtifactId,
        [Parameter(Mandatory = $true)][string] $Message
    )
    $dependencies = @(Get-Dependencies -Pom $Pom)
    if ($dependencies.Count -ne 1 -or
        [string] $dependencies[0].groupId -ne $GroupId -or
        [string] $dependencies[0].artifactId -ne $ArtifactId) {
        throw $Message
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
        'minos-app'
    )
    foreach ($module in $expectedOrder) {
        if ($modules -notcontains $module) { throw "Missing reactor module: $module" }
    }
    for ($i = 0; $i -lt ($expectedOrder.Count - 1); $i++) {
        if ([Array]::IndexOf($modules, $expectedOrder[$i]) -gt [Array]::IndexOf($modules, $expectedOrder[$i + 1])) {
            throw "Unexpected reactor order. Expected $($expectedOrder -join ' -> ')."
        }
    }

    $domainPom = Get-Pom 'minos-domain\pom.xml'
    if ((@(Get-CompilerIncludes $domainPom)) -notcontains 'com/minos/domain/**/*.java') {
        throw 'minos-domain must own com/minos/domain/**/*.java.'
    }

    $enginePom = Get-Pom 'minos-engine\pom.xml'
    Assert-OnlyDependency $enginePom 'com.minos' 'minos-domain' 'minos-engine must depend only on minos-domain.'
    $engineIncludes = @(Get-CompilerIncludes $enginePom)
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
    Assert-OnlyDependency $runtimePom 'com.minos' 'minos-engine' 'minos-runtime-local must depend only on minos-engine.'
    $runtimeIncludes = @(Get-CompilerIncludes $runtimePom)
    foreach ($include in @(
        'com/minos/runtime/CommandLocator.java',
        'com/minos/runtime/ProcessIndexerExecutor.java',
        'com/minos/runtime/ProviderRuntimeManager.java'
    )) {
        if ($runtimeIncludes -notcontains $include) { throw "minos-runtime-local must own $include." }
    }

    $storagePom = Get-Pom 'minos-storage-local\pom.xml'
    Assert-OnlyDependency $storagePom 'com.minos' 'minos-engine' 'minos-storage-local must depend only on minos-engine.'
    if ((@(Get-CompilerIncludes $storagePom)) -notcontains 'com/minos/store/**/*.java' -or
        (@(Get-CompilerExcludes $storagePom)) -notcontains 'com/minos/store/CodeKnowledgeStore.java') {
        throw 'minos-storage-local ownership is invalid.'
    }

    $providerPom = Get-Pom 'minos-provider-scip\pom.xml'
    $providerIncludes = @(Get-CompilerIncludes $providerPom)
    if ($providerIncludes -notcontains 'com/minos/adapter/scip/**/*.java') {
        throw 'minos-provider-scip must own com/minos/adapter/scip/**/*.java.'
    }
    if ((@(Get-ResourceIncludes $providerPom)) -notcontains 'com/minos/adapter/scip/runtime/**') {
        throw 'minos-provider-scip must own its packaged runtime resources.'
    }
    $providerDependencies = @(Get-Dependencies $providerPom | ForEach-Object { "$($_.groupId):$($_.artifactId)" })
    $expectedProviderDependencies = @(
        'com.minos:minos-domain',
        'com.minos:minos-engine',
        'com.minos:minos-storage-local',
        'com.minos:minos-runtime-local',
        'org.scip-code:scip-java-bindings'
    )
    if ($providerDependencies.Count -ne $expectedProviderDependencies.Count) {
        throw "Unexpected minos-provider-scip dependency count: $($providerDependencies -join ', ')."
    }
    foreach ($dependency in $expectedProviderDependencies) {
        if ($providerDependencies -notcontains $dependency) { throw "minos-provider-scip is missing dependency $dependency." }
    }

    $gitPom = Get-Pom 'minos-integration-git\pom.xml'
    Assert-OnlyDependency $gitPom 'org.eclipse.jgit' 'org.eclipse.jgit' 'minos-integration-git must directly own JGit.'

    $appPom = Get-Pom 'minos-app\pom.xml'
    foreach ($dependency in @('minos-domain','minos-engine','minos-runtime-local','minos-storage-local','minos-provider-scip','minos-integration-git')) {
        $matches = @($appPom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId' and text()='com.minos'] and *[local-name()='artifactId' and text()='$dependency']]"))
        if ($matches.Count -ne 1) { throw "minos-app must depend exactly once on com.minos:$dependency." }
    }
    if (@($appPom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId' and text()='org.scip-code']]")).Count -ne 0) {
        throw 'minos-app must not declare SCIP bindings directly.'
    }
    if (@($appPom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId' and text()='org.eclipse.jgit']]")).Count -ne 0) {
        throw 'minos-app must not declare JGit directly.'
    }
    $appExcludes = @(Get-CompilerExcludes $appPom)
    foreach ($exclude in @(
        'com/minos/domain/**/*.java',
        'com/minos/query/**/*.java',
        'com/minos/store/**/*.java',
        'com/minos/git/**/*.java',
        'com/minos/adapter/scip/**/*.java',
        'com/minos/discovery/ProjectDiscovery.java',
        'com/minos/orchestration/IndexerRegistry.java',
        'com/minos/orchestration/IndexingRuntimePorts.java',
        'com/minos/runtime/CommandLocator.java',
        'com/minos/runtime/ProcessIndexerExecutor.java',
        'com/minos/runtime/ProviderRuntimeManager.java'
    )) {
        if ($appExcludes -notcontains $exclude) { throw "minos-app must exclude $exclude." }
    }
    if ((@(Get-ResourceExcludes $appPom)) -notcontains 'com/minos/adapter/scip/runtime/**') {
        throw 'minos-app must not directly package SCIP runtime resources.'
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

function Assert-CoreOwnershipArtifacts {
    $domainJar = Get-LatestJar 'minos-domain' 'minos-domain-*.jar'
    $engineJar = Get-LatestJar 'minos-engine' 'minos-engine-*.jar'
    $runtimeJar = Get-LatestJar 'minos-runtime-local' 'minos-runtime-local-*.jar'
    $storageJar = Get-LatestJar 'minos-storage-local' 'minos-storage-local-*.jar'
    $providerJar = Get-LatestJar 'minos-provider-scip' 'minos-provider-scip-*.jar'
    $gitJar = Get-LatestJar 'minos-integration-git' 'minos-integration-git-*.jar'
    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shadedJar) { throw 'Final shaded MINOS JAR is missing.' }

    foreach ($relativePath in @(
        'target\classes\com\minos\domain\Symbol.class',
        'target\classes\com\minos\query\SymbolQueryService.class',
        'target\classes\com\minos\discovery\ProjectDiscovery.class',
        'target\classes\com\minos\orchestration\IndexingRuntimePorts.class',
        'target\classes\com\minos\runtime\CommandLocator.class',
        'target\classes\com\minos\store\FileSymbolSnapshotStore.class',
        'target\classes\com\minos\adapter\scip\ScipIndexReader.class',
        'target\classes\com\minos\git\GitIntelligenceService.class',
        'target\classes\com\minos\adapter\scip\runtime\scip-java-windows-runner.ps1',
        'target\classes\com\minos\adapter\scip\runtime\ScipWriter.java'
    )) {
        $path = Join-Path $RepoRoot $relativePath
        if (Test-Path -LiteralPath $path -PathType Leaf) { throw "minos-app directly owns extracted entry: $path" }
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME unavailable for JAR inspection.' }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "JDK jar.exe is missing: $jarTool" }

    $domainEntries = @(& $jarTool tf $domainJar.FullName)
    $engineEntries = @(& $jarTool tf $engineJar.FullName)
    $runtimeEntries = @(& $jarTool tf $runtimeJar.FullName)
    $storageEntries = @(& $jarTool tf $storageJar.FullName)
    $providerEntries = @(& $jarTool tf $providerJar.FullName)
    $gitEntries = @(& $jarTool tf $gitJar.FullName)
    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect one or more module JARs.' }

    Assert-ContainsEntries $domainEntries @('com/minos/domain/Symbol.class') 'minos-domain'
    Assert-ContainsEntries $engineEntries @(
        'com/minos/query/SymbolQueryService.class',
        'com/minos/store/CodeKnowledgeStore.class',
        'com/minos/discovery/ProjectDiscovery.class',
        'com/minos/orchestration/IndexerRegistry.class',
        'com/minos/orchestration/IndexingRuntimePorts.class'
    ) 'minos-engine'
    if ($engineEntries -contains 'com/minos/domain/Symbol.class') { throw 'minos-engine embeds domain classes.' }

    Assert-ContainsEntries $runtimeEntries @(
        'com/minos/runtime/CommandLocator.class',
        'com/minos/runtime/ProcessIndexerExecutor.class',
        'com/minos/runtime/ProviderRuntimeManager.class'
    ) 'minos-runtime-local'
    Assert-ContainsEntries $storageEntries @(
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/store/InMemoryCodeKnowledgeStore.class'
    ) 'minos-storage-local'
    if ($storageEntries -contains 'com/minos/store/CodeKnowledgeStore.class') { throw 'minos-storage-local recompiled the engine store port.' }

    Assert-ContainsEntries $providerEntries @(
        'com/minos/adapter/scip/ScipIndexReader.class',
        'com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.class',
        'com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1',
        'com/minos/adapter/scip/runtime/ScipWriter.java'
    ) 'minos-provider-scip'
    if ($providerEntries -contains 'com/minos/runtime/CommandLocator.class') { throw 'minos-provider-scip embeds generic runtime classes.' }

    Assert-ContainsEntries $gitEntries @('com/minos/git/GitIntelligenceService.class') 'minos-integration-git'

    Assert-ContainsEntries $shadedEntries @(
        'com/minos/domain/Symbol.class',
        'com/minos/query/SymbolQueryService.class',
        'com/minos/discovery/ProjectDiscovery.class',
        'com/minos/orchestration/IndexingRuntimePorts.class',
        'com/minos/runtime/CommandLocator.class',
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/adapter/scip/ScipIndexReader.class',
        'com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.class',
        'com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1',
        'com/minos/adapter/scip/runtime/ScipWriter.java',
        'com/minos/git/GitIntelligenceService.class'
    ) 'final shaded MINOS JAR'

    return [pscustomobject]@{
        DomainJar = $domainJar.FullName
        EngineJar = $engineJar.FullName
        RuntimeJar = $runtimeJar.FullName
        StorageJar = $storageJar.FullName
        ProviderJar = $providerJar.FullName
        GitJar = $gitJar.FullName
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

    Write-Host '[4/8] Checking domain/engine/runtime/storage/SCIP/Git ownership shape...'
    Assert-ReactorShape

    Write-Host '[5/8] Building SCIP provider, Git integration and all upstream boundaries...'
    Invoke-NativeChecked '.\mvnw.cmd' @('-pl','minos-provider-scip,minos-integration-git','-am','test') 'Focused provider/Git Maven verification failed'

    Write-Host "[6/8] Replaying full S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @parameters

    Write-Host '[7/8] Verifying compiled class/resource ownership...' -ForegroundColor Cyan
    $artifacts = Assert-CoreOwnershipArtifacts

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
    Write-Host "HEAD         : $head"
    Write-Host "Domain JAR   : $($artifacts.DomainJar)"
    Write-Host "Engine JAR   : $($artifacts.EngineJar)"
    Write-Host "Runtime JAR  : $($artifacts.RuntimeJar)"
    Write-Host "Storage JAR  : $($artifacts.StorageJar)"
    Write-Host "Provider JAR : $($artifacts.ProviderJar)"
    Write-Host "Git JAR      : $($artifacts.GitJar)"
    Write-Host "Shaded JAR   : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
