[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $TargetCommit = '',

    [switch] $SkipBuild
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows releases must be published from Windows.'
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

$Git = Get-Command git -ErrorAction SilentlyContinue
if (-not $Git) {
    throw 'git is required to publish a MINOS release.'
}

$Gh = Get-Command gh -ErrorAction SilentlyContinue
if (-not $Gh) {
    throw 'GitHub CLI (gh) is required. Install it and run `gh auth login` first.'
}

Invoke-NativeChecked -File $Gh.Source -Arguments @('auth', 'status') `
    -Failure 'GitHub CLI is not authenticated'

$Head = ((& $Git.Source -C $RepoRoot rev-parse HEAD) | Select-Object -First 1).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) {
    throw 'Unable to resolve the current Git HEAD.'
}

$Dirty = @(& $Git.Source -C $RepoRoot status --porcelain)
if ($LASTEXITCODE -ne 0) {
    throw 'Unable to inspect the Git worktree.'
}
if ($Dirty.Count -gt 0) {
    throw "Release publication requires a clean worktree. Dirty entries:`n$($Dirty -join "`n")"
}

if ([string]::IsNullOrWhiteSpace($TargetCommit)) {
    $TargetCommit = $Head
}
if ($TargetCommit -ne $Head) {
    throw "Release target must be the exact commit used to build the assets. HEAD=$Head target=$TargetCommit"
}

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

if (-not $SkipBuild) {
    & (Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1') -Version $Version
    if ($LASTEXITCODE -ne 0) {
        throw "Windows release build failed with exit code $LASTEXITCODE"
    }
}

$DistributionName = "minos-$Version-windows-x64"
$Zip = Join-Path $RepoRoot "target\dist\$DistributionName.zip"
$Checksum = "$Zip.sha256"

if (-not (Test-Path -LiteralPath $Zip -PathType Leaf)) {
    throw "Windows release ZIP not found: $Zip"
}
if (-not (Test-Path -LiteralPath $Checksum -PathType Leaf)) {
    throw "Windows release checksum not found: $Checksum"
}

$ExpectedHash = ((Get-Content -LiteralPath $Checksum | Select-Object -First 1) -split '\s+')[0]
$ActualHash = (Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($ExpectedHash) -or $ExpectedHash.ToLowerInvariant() -ne $ActualHash) {
    throw "Distribution checksum mismatch: expected=$ExpectedHash actual=$ActualHash"
}

# Validate the installer that is actually shipped in the ZIP, then use that
# installer to consume the ZIP itself. This catches packaging/installer drift
# before an immutable GitHub Release is created.
$SmokeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-release-smoke-" + [Guid]::NewGuid())
$ExtractRoot = Join-Path $SmokeRoot 'package'
$InstallRoot = Join-Path $SmokeRoot 'installed'
try {
    New-Item -ItemType Directory -Force -Path $ExtractRoot | Out-Null
    Expand-Archive -LiteralPath $Zip -DestinationPath $ExtractRoot -Force

    $PackagedInstaller = Join-Path $ExtractRoot "$DistributionName\install.ps1"
    if (-not (Test-Path -LiteralPath $PackagedInstaller -PathType Leaf)) {
        throw "Packaged installer not found: $PackagedInstaller"
    }

    & $PackagedInstaller -Package $Zip -InstallRoot $InstallRoot
    if ($LASTEXITCODE -ne 0) {
        throw "Packaged installer smoke test failed with exit code $LASTEXITCODE"
    }

    $InstalledMinos = Join-Path $InstallRoot 'minos.cmd'
    $VersionOutput = ((& $InstalledMinos --version) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Installed MINOS --version failed with exit code $LASTEXITCODE"
    }
    if ($VersionOutput -ne "MINOS $Version") {
        throw "Installed MINOS version mismatch: expected='MINOS $Version' actual='$VersionOutput'"
    }
}
finally {
    Remove-Item -LiteralPath $SmokeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$Tag = "v$Version"
$ExistingRelease = & $Gh.Source release view $Tag --repo $Repository 2>$null
if ($LASTEXITCODE -eq 0) {
    throw "GitHub Release $Tag already exists. Releases are immutable; publish a new version instead."
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
    $Zip,
    $Checksum,
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
Write-Host "Repository : $Repository"
Write-Host "Tag        : $Tag"
Write-Host "Commit     : $TargetCommit"
Write-Host "ZIP        : $Zip"
Write-Host "SHA-256    : $ActualHash"
