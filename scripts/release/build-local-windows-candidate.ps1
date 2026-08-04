[CmdletBinding()]
param(
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version = '1.0.1',

    [switch] $SkipMavenVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The local MINOS Windows candidate must be built on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$BuildDistribution = Join-Path $RepoRoot 'scripts\release\build-windows-distribution.ps1'
$BuildInstaller = Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1'
$McpSmoke = Join-Path $RepoRoot 'scripts\m14\MinosNativeMcpSmoke.java'
foreach ($Required in @($BuildDistribution, $BuildInstaller, $McpSmoke)) {
    if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Missing candidate-build input: $Required" }
}

$Java = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { '' }
if ([string]::IsNullOrWhiteSpace($Java) -or -not (Test-Path -LiteralPath $Java -PathType Leaf)) {
    throw 'JAVA_HOME must point to a JDK 24 installation before building the Windows candidate.'
}
$Python = @('python.exe','python','python3.exe','python3') |
    ForEach-Object { Get-Command $_ -ErrorAction SilentlyContinue } |
    Where-Object { $_ } |
    Select-Object -First 1
if (-not $Python) {
    throw 'Python 3 is required for Product Facts, documentation and supply-chain validation.'
}

$Head = (git -C $RepoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve repository HEAD.' }
$Dirty = @(git -C $RepoRoot status --porcelain=v1 --untracked-files=all)
if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect repository worktree.' }
if ($Dirty.Count -gt 0) {
    throw "Local candidate build requires a clean worktree so VERSION provenance is unambiguous. Dirty entries:`n$($Dirty -join "`n")"
}

Write-Host '=== MINOS local Windows candidate ===' -ForegroundColor Cyan
Write-Host "Version : $Version"
Write-Host "HEAD    : $Head"
Write-Host 'Network publication: DISABLED (this script never creates tags/releases or invokes GitHub Actions).'

Push-Location $RepoRoot
try {
    & $Python.Source 'scripts/docs/product-facts.py' '--check'
    if ($LASTEXITCODE -ne 0) { throw 'Product Facts are stale; refusing to build a release candidate.' }
    & $Python.Source 'scripts/docs/check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw 'Current documentation/release contract is inconsistent; refusing to build a release candidate.' }
}
finally {
    Pop-Location
}

$DistributionParameters = @{ Version = $Version }
if ($SkipMavenVerify) { $DistributionParameters['SkipVerify'] = $true }
& $BuildDistribution @DistributionParameters

$DistRoot = Join-Path $RepoRoot "target\dist\minos-$Version-windows-x64"
$Launcher = Join-Path $DistRoot 'minos.cmd'
$RuntimeModules = Join-Path $DistRoot 'RUNTIME-MODULES.txt'
if (-not (Test-Path -LiteralPath $Launcher -PathType Leaf)) { throw "Candidate launcher missing: $Launcher" }
if (-not (Test-Path -LiteralPath $RuntimeModules -PathType Leaf)) { throw "Runtime module evidence missing: $RuntimeModules" }
$Modules = @([System.IO.File]::ReadAllLines($RuntimeModules, [System.Text.Encoding]::ASCII))
if ($Modules -notcontains 'java.xml') { throw 'Candidate runtime does not contain java.xml.' }

$SmokeHome = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-local-candidate-mcp-' + [Guid]::NewGuid())
try {
    New-Item -ItemType Directory -Force -Path $SmokeHome | Out-Null
    & $Java $McpSmoke $Launcher $SmokeHome
    if ($LASTEXITCODE -ne 0) { throw "Native MCP handshake failed for local candidate (exit=$LASTEXITCODE)." }
}
finally {
    Remove-Item -LiteralPath $SmokeHome -Recurse -Force -ErrorAction SilentlyContinue
}

# Build the real user-facing installer but do NOT install it automatically on the
# maintainer workstation. Visual and real-client verification is intentionally a
# human gate before v1.0.1 publication.
& $BuildInstaller -Version $Version

$Setup = Join-Path $RepoRoot "target\dist\MINOS-$Version-windows-x64-setup.exe"
$SetupChecksum = "$Setup.sha256"
$Zip = Join-Path $RepoRoot "target\dist\minos-$Version-windows-x64.zip"
$ZipChecksum = "$Zip.sha256"
foreach ($Artifact in @($Setup, $SetupChecksum, $Zip, $ZipChecksum)) {
    if (-not (Test-Path -LiteralPath $Artifact -PathType Leaf)) { throw "Candidate artifact missing: $Artifact" }
}

Write-Host ''
Write-Host 'MINOS LOCAL WINDOWS CANDIDATE SUCCESS' -ForegroundColor Green
Write-Host "HEAD          : $Head"
Write-Host "Version       : $Version"
Write-Host "Setup         : $Setup"
Write-Host "Setup SHA-256 : $((Get-FileHash -LiteralPath $Setup -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Host "ZIP           : $Zip"
Write-Host "ZIP SHA-256   : $((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant())"
Write-Host 'Publication   : NOT PERFORMED'
Write-Host ''
Write-Host 'Next human gate: launch the setup.exe above, inspect the MCP detection page, then verify Copilot connects to MINOS before any v1.0.1 release.' -ForegroundColor Yellow
