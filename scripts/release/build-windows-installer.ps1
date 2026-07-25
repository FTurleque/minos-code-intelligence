[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $DistributionRoot = '',
    [string] $OutputRoot = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $RepoRoot 'target\dist'
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

$DistributionName = "minos-$Version-windows-x64"
if ([string]::IsNullOrWhiteSpace($DistributionRoot)) {
    $DistributionRoot = Join-Path $OutputRoot $DistributionName
}
$DistributionRoot = [System.IO.Path]::GetFullPath($DistributionRoot)

if ($env:OS -ne 'Windows_NT') {
    throw 'The MINOS Windows setup must be built on Windows.'
}
if (-not (Test-Path -LiteralPath $DistributionRoot -PathType Container)) {
    throw "MINOS distribution directory not found: $DistributionRoot"
}

foreach ($Required in @(
    'minos.cmd',
    'minos-mcp.cmd',
    'VERSION',
    'app\minos.exe',
    'lib\minos.jar',
    'docker\Dockerfile.mcp.release',
    'docker\compose.mcp.prod.yaml',
    'docker\scripts\prod-mcp-release.ps1',
    'docker\scripts\configure-docker-mcp.ps1'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $DistributionRoot $Required))) {
        throw "Invalid MINOS distribution for setup: missing $Required"
    }
}

$IsccCandidates = @()
$IsccCommand = Get-Command ISCC.exe -ErrorAction SilentlyContinue
if ($IsccCommand) {
    $IsccCandidates += $IsccCommand.Source
}
if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $IsccCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 7\ISCC.exe')
    $IsccCandidates += (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe')
}
if (-not [string]::IsNullOrWhiteSpace(${env:ProgramFiles(x86)})) {
    $IsccCandidates += (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 7\ISCC.exe')
    $IsccCandidates += (Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6\ISCC.exe')
}
if (-not [string]::IsNullOrWhiteSpace($env:ProgramFiles)) {
    $IsccCandidates += (Join-Path $env:ProgramFiles 'Inno Setup 7\ISCC.exe')
    $IsccCandidates += (Join-Path $env:ProgramFiles 'Inno Setup 6\ISCC.exe')
}
$IsccCandidates += 'C:\ProgramData\chocolatey\bin\ISCC.exe'

$Iscc = $IsccCandidates |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($Iscc)) {
    throw 'Inno Setup is required to build MINOS setup.exe. Install Inno Setup 6/7 or expose ISCC.exe in PATH.'
}

$Template = Join-Path $RepoRoot 'packaging\windows\minos-installer.iss.template'
if (-not (Test-Path -LiteralPath $Template -PathType Leaf)) {
    throw "Inno Setup template not found: $Template"
}

$InstallerWork = Join-Path $OutputRoot '.installer'
New-Item -ItemType Directory -Force -Path $InstallerWork, $OutputRoot | Out-Null
$GeneratedIss = Join-Path $InstallerWork "$DistributionName.iss"
$OutputBaseFilename = "MINOS-$Version-windows-x64-setup"
$Setup = Join-Path $OutputRoot "$OutputBaseFilename.exe"
$Checksum = "$Setup.sha256"

Remove-Item -LiteralPath $Setup -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Checksum -Force -ErrorAction SilentlyContinue

function Escape-InnoString([string] $Value) {
    return $Value.Replace('"', '""')
}

$BaseVersion = ($Version -split '[-+]')[0]
$NumericVersion = "$BaseVersion.0"
$Iss = Get-Content -Raw -LiteralPath $Template
$Iss = $Iss.Replace('@@VERSION@@', (Escape-InnoString $Version))
$Iss = $Iss.Replace('@@APP_VERSION@@', (Escape-InnoString $NumericVersion))
$Iss = $Iss.Replace('@@SOURCE_DIR@@', (Escape-InnoString $DistributionRoot))
$Iss = $Iss.Replace('@@OUTPUT_DIR@@', (Escape-InnoString $OutputRoot))
$Iss = $Iss.Replace('@@OUTPUT_BASENAME@@', (Escape-InnoString $OutputBaseFilename))
[System.IO.File]::WriteAllText($GeneratedIss, $Iss, [System.Text.UTF8Encoding]::new($false))

try {
    & $Iscc $GeneratedIss
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup compilation failed with exit code $LASTEXITCODE"
    }
    if (-not (Test-Path -LiteralPath $Setup -PathType Leaf)) {
        throw "MINOS setup executable was not produced: $Setup"
    }

    $Hash = (Get-FileHash -LiteralPath $Setup -Algorithm SHA256).Hash.ToLowerInvariant()
    "$Hash  $([System.IO.Path]::GetFileName($Setup))" | Set-Content -LiteralPath $Checksum -Encoding ascii

    Write-Host ''
    Write-Host 'MINOS Windows setup SUCCESS' -ForegroundColor Green
    Write-Host "Setup        : $Setup"
    Write-Host "SHA-256      : $Hash"
    Write-Host "Distribution : $DistributionRoot"
    Write-Host "Inno Setup   : $Iscc"
}
finally {
    Remove-Item -LiteralPath $GeneratedIss -Force -ErrorAction SilentlyContinue
}
