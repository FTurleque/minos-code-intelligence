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
    if ($env:OS -ne 'Windows_NT') {
        return
    }

    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
        throw "Windows PowerShell executable not found at the standard path: $windowsPowerShell"
    }

    $powerShellDirectory = Split-Path -Parent $windowsPowerShell
    $alreadyPresent = @($env:Path -split ';') | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
        $_.TrimEnd('\').Equals($powerShellDirectory.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1

    if ($null -eq $alreadyPresent) {
        $env:Path = "$powerShellDirectory;$env:Path"
    }
}

function Resolve-CurrentPowerShellHost {
    try {
        $hostPath = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($hostPath) -and
            (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
            return $hostPath
        }
    }
    catch {
    }

    if ($env:OS -eq 'Windows_NT') {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) {
            return $fallback
        }
    }

    throw 'Unable to resolve the PowerShell host used to restart the updated M15-S2 runner.'
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
    if ($LASTEXITCODE -ne 0) {
        throw "Restarted M15-S2 runner failed (exit=$LASTEXITCODE)."
    }
}

function Get-Pom {
    param([Parameter(Mandatory = $true)][string] $RelativePath)

    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required Maven POM is missing: $path"
    }
    return [xml] (Get-Content -LiteralPath $path -Raw)
}

function Get-CompilerIncludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)

    return @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="includes"]/*[local-name()="include"]') |
        ForEach-Object { $_.InnerText.Trim() })
}

function Get-CompilerExcludes {
    param([Parameter(Mandatory = $true)][xml] $Pom)

    return @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="excludes"]/*[local-name()="exclude"]') |
        ForEach-Object { $_.InnerText.Trim() })
}

function Get-Dependencies {
    param([Parameter(Mandatory = $true)][xml] $Pom)

    return @($Pom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencies"]/*[local-name()="dependency"]'))
}

function Assert-SingleDependency {
    param(
        [Parameter(Mandatory = $true)][xml] $Pom,
        [Parameter(Mandatory = $true)][string] $GroupId,
        [Parameter(Mandatory = $true)][string] $ArtifactId,
        [Parameter(Mandatory = $true)][string] $Message
    )

    $dependencies = Get-Dependencies -Pom $Pom
    if ($dependencies.Count -ne 1 -or
        [string] $dependencies[0].groupId -ne $GroupId -or
        [string] $dependencies[0].artifactId -ne $ArtifactId) {
        throw $Message
    }
}

function Assert-ReactorShape {
    $rootPom = Get-Pom -RelativePath 'pom.xml'
    $project = $rootPom.project

    if ([string] $project.packaging -ne 'pom') {
        throw "M15-S2 root packaging must be 'pom'; found '$($project.packaging)'."
    }
    if ([string] $project.artifactId -ne 'minos-parent') {
        throw "M15-S2 root artifactId must be 'minos-parent'; found '$($project.artifactId)'."
    }

    $modules = @($rootPom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]') |
        ForEach-Object { $_.InnerText.Trim() })
    $expectedOrder = @(
        'minos-domain',
        'minos-engine',
        'minos-storage-local',
        'minos-integration-git',
        'minos-app'
    )
    foreach ($requiredModule in $expectedOrder) {
        if ($modules -notcontains $requiredModule) {
            throw "M15-S2 reactor must contain $requiredModule; modules=$($modules -join ',')."
        }
    }
    for ($index = 0; $index -lt ($expectedOrder.Count - 1); $index++) {
        if ([Array]::IndexOf($modules, $expectedOrder[$index]) -gt
            [Array]::IndexOf($modules, $expectedOrder[$index + 1])) {
            throw "Unexpected reactor order. Expected: $($expectedOrder -join ' -> ')."
        }
    }

    $domainPom = Get-Pom -RelativePath 'minos-domain\pom.xml'
    if ([string] $domainPom.project.artifactId -ne 'minos-domain') {
        throw "Domain artifact coordinate changed unexpectedly: $($domainPom.project.artifactId)"
    }
    if ((Get-CompilerIncludes -Pom $domainPom) -notcontains 'com/minos/domain/**/*.java') {
        throw 'minos-domain must own com/minos/domain/**/*.java during the S2 bridge.'
    }

    $enginePom = Get-Pom -RelativePath 'minos-engine\pom.xml'
    if ([string] $enginePom.project.artifactId -ne 'minos-engine') {
        throw "Engine artifact coordinate changed unexpectedly: $($enginePom.project.artifactId)"
    }
    $engineIncludes = Get-CompilerIncludes -Pom $enginePom
    foreach ($requiredInclude in @('com/minos/query/**/*.java', 'com/minos/store/CodeKnowledgeStore.java')) {
        if ($engineIncludes -notcontains $requiredInclude) {
            throw "minos-engine must own $requiredInclude during the S2 bridge."
        }
    }
    Assert-SingleDependency -Pom $enginePom -GroupId 'com.minos' -ArtifactId 'minos-domain' `
        -Message 'minos-engine must depend only on com.minos:minos-domain at this checkpoint.'

    $storagePom = Get-Pom -RelativePath 'minos-storage-local\pom.xml'
    if ([string] $storagePom.project.artifactId -ne 'minos-storage-local') {
        throw "Storage artifact coordinate changed unexpectedly: $($storagePom.project.artifactId)"
    }
    if ((Get-CompilerIncludes -Pom $storagePom) -notcontains 'com/minos/store/**/*.java') {
        throw 'minos-storage-local must own the historical com/minos/store source tree.'
    }
    if ((Get-CompilerExcludes -Pom $storagePom) -notcontains 'com/minos/store/CodeKnowledgeStore.java') {
        throw 'minos-storage-local must not recompile the CodeKnowledgeStore engine port.'
    }
    Assert-SingleDependency -Pom $storagePom -GroupId 'com.minos' -ArtifactId 'minos-engine' `
        -Message 'minos-storage-local must depend only on com.minos:minos-engine at this checkpoint.'

    $gitPom = Get-Pom -RelativePath 'minos-integration-git\pom.xml'
    if ([string] $gitPom.project.artifactId -ne 'minos-integration-git') {
        throw "Git integration artifact coordinate changed unexpectedly: $($gitPom.project.artifactId)"
    }
    if ((Get-CompilerIncludes -Pom $gitPom) -notcontains 'com/minos/git/**/*.java') {
        throw 'minos-integration-git must own com/minos/git/**/*.java.'
    }
    Assert-SingleDependency -Pom $gitPom -GroupId 'org.eclipse.jgit' -ArtifactId 'org.eclipse.jgit' `
        -Message 'minos-integration-git must be the only S2 module directly declaring JGit at this checkpoint.'

    $appPom = Get-Pom -RelativePath 'minos-app\pom.xml'
    if ([string] $appPom.project.artifactId -ne 'minos-code-intelligence') {
        throw "Public artifact coordinate changed unexpectedly: $($appPom.project.artifactId)"
    }

    foreach ($internalDependency in @(
        'minos-domain',
        'minos-engine',
        'minos-storage-local',
        'minos-integration-git'
    )) {
        $matches = @($appPom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId' and text()='com.minos'] and *[local-name()='artifactId' and text()='$internalDependency']]"))
        if ($matches.Count -ne 1) {
            throw "minos-app must have exactly one com.minos:$internalDependency dependency; found $($matches.Count)."
        }
    }

    $directJgit = @($appPom.SelectNodes("/*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency'][*[local-name()='groupId' and text()='org.eclipse.jgit']]"))
    if ($directJgit.Count -ne 0) {
        throw 'minos-app must no longer declare JGit directly; JGit belongs to minos-integration-git.'
    }

    $appExcludes = Get-CompilerExcludes -Pom $appPom
    foreach ($requiredExclude in @(
        'com/minos/domain/**/*.java',
        'com/minos/query/**/*.java',
        'com/minos/store/**/*.java',
        'com/minos/git/**/*.java'
    )) {
        if ($appExcludes -notcontains $requiredExclude) {
            throw "minos-app must exclude $requiredExclude so Maven enforces module ownership."
        }
    }
}

function Get-LatestJar {
    param(
        [Parameter(Mandatory = $true)][string] $Module,
        [Parameter(Mandatory = $true)][string] $Filter
    )

    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$Module\target") -File `
        -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "$Module JAR is missing after reactor verification."
    }
    return $jar
}

function Assert-CoreOwnershipArtifacts {
    $domainJar = Get-LatestJar -Module 'minos-domain' -Filter 'minos-domain-*.jar'
    $engineJar = Get-LatestJar -Module 'minos-engine' -Filter 'minos-engine-*.jar'
    $storageJar = Get-LatestJar -Module 'minos-storage-local' -Filter 'minos-storage-local-*.jar'
    $gitJar = Get-LatestJar -Module 'minos-integration-git' -Filter 'minos-integration-git-*.jar'

    foreach ($appOwnedClass in @(
        'target\classes\com\minos\domain\Symbol.class',
        'target\classes\com\minos\query\SymbolQueryService.class',
        'target\classes\com\minos\store\CodeKnowledgeStore.class',
        'target\classes\com\minos\store\FileSymbolSnapshotStore.class',
        'target\classes\com\minos\git\GitIntelligenceService.class'
    )) {
        $path = Join-Path $RepoRoot $appOwnedClass
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            throw "minos-app compiled a class owned by another module: $path"
        }
    }

    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File `
        -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $shadedJar) {
        throw 'M15-S2 shaded application JAR is missing from repository target directory.'
    }

    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        throw 'JAVA_HOME is unavailable after qualification; cannot inspect module artifacts.'
    }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) {
        throw "JDK jar.exe is missing: $jarTool"
    }

    $domainEntries = @(& $jarTool tf $domainJar.FullName)
    if ($LASTEXITCODE -ne 0 -or $domainEntries -notcontains 'com/minos/domain/Symbol.class') {
        throw 'minos-domain JAR does not own com/minos/domain/Symbol.class.'
    }

    $engineEntries = @(& $jarTool tf $engineJar.FullName)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect minos-engine JAR.'
    }
    foreach ($requiredEngineEntry in @(
        'com/minos/query/SymbolQueryService.class',
        'com/minos/store/CodeKnowledgeStore.class'
    )) {
        if ($engineEntries -notcontains $requiredEngineEntry) {
            throw "minos-engine JAR does not own $requiredEngineEntry."
        }
    }
    if ($engineEntries -contains 'com/minos/domain/Symbol.class') {
        throw 'minos-engine JAR embeds domain classes instead of depending on minos-domain.'
    }

    $storageEntries = @(& $jarTool tf $storageJar.FullName)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect minos-storage-local JAR.'
    }
    foreach ($requiredStorageEntry in @(
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/store/InMemoryCodeKnowledgeStore.class'
    )) {
        if ($storageEntries -notcontains $requiredStorageEntry) {
            throw "minos-storage-local JAR does not own $requiredStorageEntry."
        }
    }
    if ($storageEntries -contains 'com/minos/store/CodeKnowledgeStore.class') {
        throw 'minos-storage-local recompiled the CodeKnowledgeStore engine port.'
    }

    $gitEntries = @(& $jarTool tf $gitJar.FullName)
    if ($LASTEXITCODE -ne 0 -or $gitEntries -notcontains 'com/minos/git/GitIntelligenceService.class') {
        throw 'minos-integration-git JAR does not own com/minos/git/GitIntelligenceService.class.'
    }

    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect final shaded MINOS JAR.'
    }
    foreach ($requiredShadedEntry in @(
        'com/minos/domain/Symbol.class',
        'com/minos/query/SymbolQueryService.class',
        'com/minos/store/CodeKnowledgeStore.class',
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/git/GitIntelligenceService.class'
    )) {
        if ($shadedEntries -notcontains $requiredShadedEntry) {
            throw "Final shaded MINOS JAR no longer contains $requiredShadedEntry."
        }
    }

    return [pscustomobject]@{
        DomainJar = $domainJar.FullName
        EngineJar = $engineJar.FullName
        StorageJar = $storageJar.FullName
        GitJar = $gitJar.FullName
        ShadedJar = $shadedJar.FullName
    }
}

Push-Location $RepoRoot
try {
    Ensure-WindowsPowerShellOnPath

    Write-Host '=== MINOS M15-S2 - reactor + module-boundary exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree status.'
    }
    if ($dirty.Count -gt 0) {
        throw "M15-S2 runner requires a clean worktree. Dirty entries:`n$($dirty -join "`n")"
    }

    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialHead)) {
        throw 'Unable to resolve initial HEAD.'
    }

    Write-Host '[1/8] Fetching M15-S2 branch...'
    Invoke-GitChecked -Arguments @('fetch', 'origin', $Branch)

    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve current branch.'
    }

    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        $localBranchExists = ($LASTEXITCODE -eq 0)

        Write-Host "[2/8] Switching from '$currentBranch' to '$Branch'..."
        if ($localBranchExists) {
            Invoke-GitChecked -Arguments @('switch', $Branch)
        }
        else {
            Invoke-GitChecked -Arguments @('switch', '-c', $Branch, '--track', "origin/$Branch")
        }
    }
    else {
        Write-Host "[2/8] Already on '$Branch'."
    }

    Write-Host '[3/8] Fast-forwarding to the latest remote head...'
    Invoke-GitChecked -Arguments @('pull', '--ff-only', 'origin', $Branch)

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
        throw 'Unable to resolve exact HEAD after update.'
    }

    if ($head -ne $initialHead) {
        Restart-UpdatedRunner -Head $head
        return
    }

    Write-Host '[4/8] Checking domain, engine, storage and Git module ownership shape...'
    Assert-ReactorShape
    Ensure-WindowsPowerShellOnPath

    Write-Host '[5/8] Building storage/Git boundaries and upstream modules in isolation...'
    Invoke-NativeChecked -File '.\mvnw.cmd' `
        -Arguments @('-pl', 'minos-storage-local,minos-integration-git', '-am', 'test') `
        -Failure 'Focused storage/Git Maven verification failed'

    Write-Host "[6/8] Replaying functional S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $captureScript = Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }

    & $captureScript @parameters

    Write-Host '[7/8] Verifying compiled artifact ownership...' -ForegroundColor Cyan
    $artifacts = Assert-CoreOwnershipArtifacts

    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host '[8/8] Capturing repeated-query cost baseline...' -ForegroundColor Cyan
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    }
    else {
        Write-Host '[8/8] Repeated-query baseline skipped because the full M14/provider replay was disabled.' -ForegroundColor Yellow
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host 'M15-S2 FULL MODULE-BOUNDARY VALIDATION SUCCESS' -ForegroundColor Green
    }
    else {
        Write-Host 'M15-S2 diagnostic module-boundary validation finished (not sufficient to close S2)' -ForegroundColor Yellow
    }
    Write-Host "HEAD        : $head"
    Write-Host "Domain JAR  : $($artifacts.DomainJar)"
    Write-Host "Engine JAR  : $($artifacts.EngineJar)"
    Write-Host "Storage JAR : $($artifacts.StorageJar)"
    Write-Host "Git JAR     : $($artifacts.GitJar)"
    Write-Host "Shaded JAR  : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
