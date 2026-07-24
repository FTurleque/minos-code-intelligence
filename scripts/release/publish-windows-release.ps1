[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $TargetCommit = '',

    [switch] $SkipBuild,
    [switch] $ValidateOnly
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows releases must be built and validated on Windows.'
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

function Invoke-PowerShellScriptChecked {
    param(
        [Parameter(Mandatory = $true)][string] $Script,
        [Parameter(Mandatory = $true)][hashtable] $Parameters,
        [Parameter(Mandatory = $true)][string] $Failure
    )

    try {
        & $Script @Parameters
    }
    catch {
        throw "$Failure`: $($_.Exception.Message)"
    }
}

function Assert-VersionProvenance {
    param(
        [Parameter(Mandatory = $true)][string] $VersionFile,
        [Parameter(Mandatory = $true)][string] $ExpectedVersion,
        [Parameter(Mandatory = $true)][string] $ExpectedCommit,
        [Parameter(Mandatory = $true)][string] $Context
    )

    if (-not (Test-Path -LiteralPath $VersionFile -PathType Leaf)) {
        throw "$Context provenance file not found: $VersionFile"
    }

    $Metadata = @{}
    foreach ($Line in Get-Content -LiteralPath $VersionFile) {
        if ($Line -match '^([^=]+)=(.*)$') {
            $Metadata[$Matches[1].Trim()] = $Matches[2].Trim()
        }
    }

    $ArtifactVersion = $Metadata['version']
    $ArtifactCommit = $Metadata['commit']
    if ($ArtifactVersion -ne $ExpectedVersion) {
        throw "$Context version provenance mismatch: expected=$ExpectedVersion actual=$ArtifactVersion"
    }
    if ($ArtifactCommit -ne $ExpectedCommit) {
        throw "$Context commit provenance mismatch: expected=$ExpectedCommit actual=$ArtifactCommit"
    }
}

function Verify-Sha256 {
    param(
        [Parameter(Mandatory = $true)][string] $Artifact,
        [Parameter(Mandatory = $true)][string] $Checksum
    )

    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) {
        throw "Release artifact not found: $Artifact"
    }
    if (-not (Test-Path -LiteralPath $Checksum -PathType Leaf)) {
        throw "Release checksum not found: $Checksum"
    }

    $ExpectedHash = ((Get-Content -LiteralPath $Checksum | Select-Object -First 1) -split '\s+')[0]
    $ActualHash = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    if ([string]::IsNullOrWhiteSpace($ExpectedHash) -or $ExpectedHash.ToLowerInvariant() -ne $ActualHash) {
        throw "Release checksum mismatch for $Artifact`: expected=$ExpectedHash actual=$ActualHash"
    }
    return $ActualHash
}

$Git = Get-Command git -ErrorAction SilentlyContinue
if (-not $Git) {
    throw 'git is required to build or publish a MINOS release.'
}

$Head = ((& $Git.Source -C $RepoRoot rev-parse HEAD) | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) {
    throw 'Unable to resolve the current Git HEAD.'
}

$Dirty = @(& $Git.Source -C $RepoRoot status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the Git worktree.'
}
if ($Dirty.Count -gt 0) {
    throw "Release validation requires a clean worktree. Dirty entries:`n$($Dirty -join "`n")"
}

if ([string]::IsNullOrWhiteSpace($TargetCommit)) {
    $TargetCommit = $Head
}
if ($TargetCommit -ne $Head) {
    throw "Release target must be the exact commit used to build the assets. HEAD=$Head target=$TargetCommit"
}

$Gh = $null
$Repository = ''
if (-not $ValidateOnly) {
    $Gh = Get-Command gh -ErrorAction SilentlyContinue
    if (-not $Gh) {
        throw 'GitHub CLI (gh) is required to publish a MINOS release. Install it and run `gh auth login` first.'
    }

    Invoke-NativeChecked -File $Gh.Source -Arguments @('auth', 'status') `
        -Failure 'GitHub CLI is not authenticated'

    $Repository = $env:GITHUB_REPOSITORY
    if ([string]::IsNullOrWhiteSpace($Repository)) {
        $RepoJson = (& $Gh.Source repo view --json nameWithOwner | Out-String).Trim()
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($RepoJson)) {
            throw 'Unable to resolve the GitHub repository with `gh repo view`.'
        }
        $Repository = ($RepoJson | ConvertFrom-Json).nameWithOwner
    }
    if ([string]::IsNullOrWhiteSpace($Repository)) {
        throw 'Unable to resolve the GitHub repository name.'
    }
}

if (-not $SkipBuild) {
    Invoke-PowerShellScriptChecked `
        -Script (Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1') `
        -Parameters @{ Version = $Version } `
        -Failure 'Windows release distribution build failed'
    Invoke-PowerShellScriptChecked `
        -Script (Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1') `
        -Parameters @{ Version = $Version } `
        -Failure 'Windows release setup build failed'
}

$DistributionName = "minos-$Version-windows-x64"
$Zip = Join-Path $RepoRoot "target\dist\$DistributionName.zip"
$ZipChecksum = "$Zip.sha256"
$Setup = Join-Path $RepoRoot "target\dist\MINOS-$Version-windows-x64-setup.exe"
$SetupChecksum = "$Setup.sha256"
$RequiredInstalledFiles = @(
    'minos.cmd',
    'minos-mcp.cmd',
    'VERSION',
    'app\minos.exe',
    'app\runtime\bin\java.exe',
    'lib\minos.jar',
    'docker\Dockerfile.mcp.release',
    'docker\compose.mcp.prod.yaml',
    'docker\scripts\prod-mcp-release.ps1',
    'docker\scripts\configure-docker-mcp.ps1'
)

$ZipHash = Verify-Sha256 -Artifact $Zip -Checksum $ZipChecksum
$SetupHash = Verify-Sha256 -Artifact $Setup -Checksum $SetupChecksum

# Validate the portable installer actually shipped in the ZIP.
$ZipSmokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-release-zip-smoke-" + [Guid]::NewGuid())
$ExtractRoot = Join-Path $ZipSmokeRoot 'package'
$ZipInstallRoot = Join-Path $ZipSmokeRoot 'installed'
try {
    New-Item -ItemType Directory -Force -Path $ExtractRoot | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force

    $PackagedInstaller = Join-Path $ExtractRoot "$DistributionName\install.ps1"
    if (-not (Test-Path -LiteralPath $PackagedInstaller -PathType Leaf)) {
        throw "Packaged portable installer not found: $PackagedInstaller"
    }

    Assert-VersionProvenance `
        -VersionFile (Join-Path $ExtractRoot "$DistributionName\VERSION") `
        -ExpectedVersion $Version `
        -ExpectedCommit $TargetCommit `
        -Context 'ZIP'

    Invoke-PowerShellScriptChecked `
        -Script $PackagedInstaller `
        -Parameters @{ Package = $Zip; InstallRoot = $ZipInstallRoot } `
        -Failure 'Packaged portable installer smoke test failed'
    foreach ($Required in $RequiredInstalledFiles) {
        $InstalledFile = Join-Path $ZipInstallRoot $Required
        if (-not (Test-Path -LiteralPath $InstalledFile -PathType Leaf)) {
            throw "Portable installer did not install required file: $InstalledFile"
        }
    }

    $InstalledMinos = Join-Path $ZipInstallRoot 'minos.cmd'
    $VersionOutput = ((& $InstalledMinos --version) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Portable MINOS --version failed with exit code $LASTEXITCODE"
    }
    if ($VersionOutput -ne "MINOS $Version") {
        throw "Portable MINOS version mismatch: expected='MINOS $Version' actual='$VersionOutput'"
    }
    Assert-VersionProvenance `
        -VersionFile (Join-Path $ZipInstallRoot 'VERSION') `
        -ExpectedVersion $Version `
        -ExpectedCommit $TargetCommit `
        -Context 'Portable installation'
}
finally {
    Remove-Item -LiteralPath $ZipSmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Smoke-test the user-facing setup.exe without touching the caller's PATH or
# Docker configuration. The setup is installed and then uninstalled silently.
$SetupSmokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-release-setup-smoke-" + [Guid]::NewGuid())
$SetupInstallRoot = Join-Path $SetupSmokeRoot 'installed'
try {
    New-Item -ItemType Directory -Force -Path $SetupSmokeRoot | Out-Null
    Invoke-NativeChecked -File $Setup -Arguments @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART',
        "/DIR=$SetupInstallRoot",
        '/TASKS="!addtopath,!docker"'
    ) -Failure 'MINOS setup.exe silent installation failed'

    foreach ($Required in $RequiredInstalledFiles) {
        $InstalledFile = Join-Path $SetupInstallRoot $Required
        if (-not (Test-Path -LiteralPath $InstalledFile -PathType Leaf)) {
            throw "MINOS setup.exe did not install required file: $InstalledFile"
        }
    }
    $InstalledMinos = Join-Path $SetupInstallRoot 'minos.cmd'
    $VersionOutput = ((& $InstalledMinos --version) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "setup.exe installed MINOS --version failed with exit code $LASTEXITCODE"
    }
    if ($VersionOutput -ne "MINOS $Version") {
        throw "setup.exe installed MINOS version mismatch: expected='MINOS $Version' actual='$VersionOutput'"
    }
    Assert-VersionProvenance `
        -VersionFile (Join-Path $SetupInstallRoot 'VERSION') `
        -ExpectedVersion $Version `
        -ExpectedCommit $TargetCommit `
        -Context 'setup.exe installation'

    $Uninstaller = Get-ChildItem -LiteralPath $SetupInstallRoot -File -Filter 'unins*.exe' |
        Sort-Object Name |
        Select-Object -First 1 -ExpandProperty FullName
    if ([string]::IsNullOrWhiteSpace($Uninstaller)) {
        throw "MINOS setup.exe did not register an uninstaller under $SetupInstallRoot"
    }

    Invoke-NativeChecked -File $Uninstaller -Arguments @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART'
    ) -Failure 'MINOS setup.exe silent uninstall failed'

    if (Test-Path -LiteralPath $SetupInstallRoot) {
        throw "MINOS setup.exe uninstall left the program directory behind: $SetupInstallRoot"
    }
}
finally {
    Remove-Item -LiteralPath $SetupSmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

if ($ValidateOnly) {
    Write-Host ''
    Write-Host 'MINOS Windows release VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Commit        : $TargetCommit"
    Write-Host "Setup         : $Setup"
    Write-Host "Setup SHA-256 : $SetupHash"
    Write-Host "ZIP           : $Zip"
    Write-Host "ZIP SHA-256   : $ZipHash"
    return
}

$Tag = "v$Version"
# Windows PowerShell 5.1 surfaces native stderr as an ErrorRecord. With the
# script-wide ErrorActionPreference=Stop, the expected `release not found`
# response would terminate the script before LASTEXITCODE could be inspected.
$PreviousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $ReleaseProbeOutput = ((& $Gh.Source release view $Tag --repo $Repository 2>&1) | Out-String).Trim()
    $ReleaseProbeExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $PreviousErrorActionPreference
}
if ($ReleaseProbeExitCode -eq 0) {
    throw "GitHub Release $Tag already exists. Releases are immutable; publish a new version instead."
}
if ($ReleaseProbeOutput -notmatch '(?i)release not found') {
    throw "Unable to check GitHub Release $Tag (exit=$ReleaseProbeExitCode): $ReleaseProbeOutput"
}

$ExistingTag = @(& $Git.Source -C $RepoRoot ls-remote --tags origin "refs/tags/$Tag")
if ($LASTEXITCODE -ne 0) {
    throw "Unable to check whether tag $Tag already exists on origin."
}
if ($ExistingTag.Count -gt 0) {
    throw "Git tag $Tag already exists on origin without a release. Resolve that tag explicitly before publishing."
}

$ReleaseArguments = @(
    'release', 'create', $Tag,
    $Setup,
    $SetupChecksum,
    $Zip,
    $ZipChecksum,
    '--repo', $Repository,
    '--target', $TargetCommit,
    '--title', "MINOS $Version",
    '--generate-notes'
)
if ($Version -match '-') {
    $ReleaseArguments += '--prerelease'
}

Invoke-NativeChecked -File $Gh.Source -Arguments $ReleaseArguments `
    -Failure "GitHub Release $Tag publication failed"

Write-Host ''
Write-Host 'MINOS GitHub Release SUCCESS' -ForegroundColor Green
Write-Host "Repository    : $Repository"
Write-Host "Tag           : $Tag"
Write-Host "Commit        : $TargetCommit"
Write-Host "Setup         : $Setup"
Write-Host "Setup SHA-256 : $SetupHash"
Write-Host "ZIP           : $Zip"
Write-Host "ZIP SHA-256   : $ZipHash"
