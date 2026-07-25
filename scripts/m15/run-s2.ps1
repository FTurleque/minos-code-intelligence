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

function Assert-ReactorShape {
    [xml] $rootPom = Get-Content -LiteralPath (Join-Path $RepoRoot 'pom.xml') -Raw
    $project = $rootPom.project

    if ([string] $project.packaging -ne 'pom') {
        throw "M15-S2 root packaging must be 'pom'; found '$($project.packaging)'."
    }
    if ([string] $project.artifactId -ne 'minos-parent') {
        throw "M15-S2 root artifactId must be 'minos-parent'; found '$($project.artifactId)'."
    }

    $modules = @($rootPom.SelectNodes('/*[local-name()="project"]/*[local-name()="modules"]/*[local-name()="module"]') |
        ForEach-Object { $_.InnerText.Trim() })
    foreach ($requiredModule in @('minos-domain', 'minos-app')) {
        if ($modules -notcontains $requiredModule) {
            throw "M15-S2 reactor must contain $requiredModule; modules=$($modules -join ',')."
        }
    }
    if ([Array]::IndexOf($modules, 'minos-domain') -gt [Array]::IndexOf($modules, 'minos-app')) {
        throw 'minos-domain must precede minos-app in the reactor.'
    }

    $domainPomPath = Join-Path $RepoRoot 'minos-domain\pom.xml'
    if (-not (Test-Path -LiteralPath $domainPomPath -PathType Leaf)) {
        throw "M15-S2 domain POM is missing: $domainPomPath"
    }
    [xml] $domainPom = Get-Content -LiteralPath $domainPomPath -Raw
    if ([string] $domainPom.project.artifactId -ne 'minos-domain') {
        throw "Domain artifact coordinate changed unexpectedly: $($domainPom.project.artifactId)"
    }
    $domainIncludes = @($domainPom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="includes"]/*[local-name()="include"]') |
        ForEach-Object { $_.InnerText.Trim() })
    if ($domainIncludes -notcontains 'com/minos/domain/**/*.java') {
        throw 'minos-domain must exclusively compile the historical com/minos/domain source tree during the S2 bridge.'
    }

    $appPomPath = Join-Path $RepoRoot 'minos-app\pom.xml'
    if (-not (Test-Path -LiteralPath $appPomPath -PathType Leaf)) {
        throw "M15-S2 application POM is missing: $appPomPath"
    }
    [xml] $appPom = Get-Content -LiteralPath $appPomPath -Raw
    if ([string] $appPom.project.artifactId -ne 'minos-code-intelligence') {
        throw "Public artifact coordinate changed unexpectedly: $($appPom.project.artifactId)"
    }

    $domainDependencies = @($appPom.SelectNodes('/*[local-name()="project"]/*[local-name()="dependencies"]/*[local-name()="dependency"][*[local-name()="groupId" and text()="com.minos"] and *[local-name()="artifactId" and text()="minos-domain"]]'))
    if ($domainDependencies.Count -ne 1) {
        throw "minos-app must have exactly one com.minos:minos-domain dependency; found $($domainDependencies.Count)."
    }

    $appExcludes = @($appPom.SelectNodes('/*[local-name()="project"]/*[local-name()="build"]/*[local-name()="plugins"]/*[local-name()="plugin"]/*[local-name()="configuration"]/*[local-name()="excludes"]/*[local-name()="exclude"]') |
        ForEach-Object { $_.InnerText.Trim() })
    if ($appExcludes -notcontains 'com/minos/domain/**/*.java') {
        throw 'minos-app must exclude com/minos/domain sources so Maven enforces the ownership boundary.'
    }
}

function Assert-DomainOwnershipArtifacts {
    $domainJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'minos-domain\target') -File `
        -Filter 'minos-domain-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $domainJar) {
        throw 'minos-domain JAR is missing after reactor verification.'
    }

    $appDomainClass = Join-Path $RepoRoot 'target\classes\com\minos\domain\Symbol.class'
    if (Test-Path -LiteralPath $appDomainClass -PathType Leaf) {
        throw "minos-app compiled domain classes directly; ownership boundary is not enforced: $appDomainClass"
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

    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0 -or $shadedEntries -notcontains 'com/minos/domain/Symbol.class') {
        throw 'Final shaded MINOS JAR no longer contains com/minos/domain/Symbol.class.'
    }

    return [pscustomobject]@{
        DomainJar = $domainJar.FullName
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

    Write-Host '[4/8] Checking reactor and minos-domain ownership shape...'
    Assert-ReactorShape
    Ensure-WindowsPowerShellOnPath

    Write-Host '[5/8] Building the domain boundary in isolation...'
    Invoke-NativeChecked -File '.\mvnw.cmd' -Arguments @('-pl', 'minos-domain', '-am', 'test') `
        -Failure 'Focused minos-domain Maven verification failed'

    Write-Host "[6/8] Replaying functional S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $captureScript = Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }

    & $captureScript @parameters

    Write-Host '[7/8] Verifying compiled artifact ownership...' -ForegroundColor Cyan
    $artifacts = Assert-DomainOwnershipArtifacts

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
    Write-Host "HEAD       : $head"
    Write-Host "Domain JAR : $($artifacts.DomainJar)"
    Write-Host "Shaded JAR : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
