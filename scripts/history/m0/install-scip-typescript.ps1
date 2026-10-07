[CmdletBinding()]
param(
    [string] $Version = "0.4.0",

    [switch] $Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ToolsRoot = Join-Path $RepoRoot ".minos-m0\tools"
$InstallDirectory = Join-Path $ToolsRoot "scip-typescript"
$PartialDirectory = "$InstallDirectory.partial"
$PackageRelativePath = "node_modules\@sourcegraph\scip-typescript\package.json"
$CommandRelativePath = "node_modules\.bin\scip-typescript.cmd"

function Get-InstalledVersion {
    param([Parameter(Mandatory = $true)][string] $Directory)

    $PackageFile = Join-Path $Directory $PackageRelativePath
    if (-not (Test-Path -LiteralPath $PackageFile -PathType Leaf)) {
        return $null
    }

    return (Get-Content -Raw -LiteralPath $PackageFile | ConvertFrom-Json).version
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js is required to run scip-typescript."
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm is required to install scip-typescript locally."
}

$InstalledVersion = Get-InstalledVersion -Directory $InstallDirectory
$InstalledCommand = Join-Path $InstallDirectory $CommandRelativePath
if (-not $Force -and $InstalledVersion -eq $Version -and
        (Test-Path -LiteralPath $InstalledCommand -PathType Leaf)) {
    Write-Host "scip-typescript $Version is already installed locally." -ForegroundColor Green
    Write-Host "Command: $InstalledCommand"
    exit 0
}

New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
Remove-Item -LiteralPath $PartialDirectory -Recurse -Force -ErrorAction SilentlyContinue

try {
    Write-Host "==> Install @sourcegraph/scip-typescript@$Version in a transactional local directory"
    & npm install `
        --prefix $PartialDirectory `
        --no-audit `
        --no-fund `
        --ignore-scripts `
        "@sourcegraph/scip-typescript@$Version"
    if ($LASTEXITCODE -ne 0) {
        throw "Local scip-typescript installation failed with exit code $LASTEXITCODE"
    }

    $PartialVersion = Get-InstalledVersion -Directory $PartialDirectory
    $PartialCommand = Join-Path $PartialDirectory $CommandRelativePath
    if ($PartialVersion -ne $Version) {
        throw "Expected scip-typescript $Version, installed $PartialVersion"
    }
    if (-not (Test-Path -LiteralPath $PartialCommand -PathType Leaf)) {
        throw "scip-typescript command was not created: $PartialCommand"
    }

    & $PartialCommand --version
    if ($LASTEXITCODE -ne 0) {
        throw "scip-typescript validation failed with exit code $LASTEXITCODE"
    }

    Remove-Item -LiteralPath $InstallDirectory -Recurse -Force -ErrorAction SilentlyContinue
    Move-Item -LiteralPath $PartialDirectory -Destination $InstallDirectory
}
finally {
    Remove-Item -LiteralPath $PartialDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

$FinalCommand = Join-Path $InstallDirectory $CommandRelativePath
Write-Host
Write-Host "scip-typescript installed locally." -ForegroundColor Green
Write-Host "Version: $Version"
Write-Host "Command: $FinalCommand"
Write-Host "No user PATH or global npm installation was modified."
