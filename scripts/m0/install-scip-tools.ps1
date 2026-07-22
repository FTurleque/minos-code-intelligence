[CmdletBinding()]
param(
    [string] $ScipVersion = "0.7.1",
    [switch] $Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ToolsRoot = Join-Path $RepoRoot ".minos-m0\tools"
$ToolsBin = Join-Path $ToolsRoot "bin"
$TempRoot = Join-Path $ToolsRoot "tmp"

New-Item -ItemType Directory -Force -Path $ToolsBin | Out-Null
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null

$CoursierExe = Join-Path $ToolsBin "cs.exe"
$ScipExe = Join-Path $ToolsBin "scip.exe"

$CoursierZip = Join-Path $TempRoot "cs-x86_64-pc-win32.zip"
$CoursierExtract = Join-Path $TempRoot "coursier"
$ScipArchive = Join-Path $TempRoot "scip-windows-amd64.tar.gz"
$ScipExtract = Join-Path $TempRoot "scip"

$CoursierUrl = "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip"
$ScipUrl = "https://github.com/scip-code/scip/releases/download/v$ScipVersion/scip-windows-amd64.tar.gz"

function Download-File {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)][string] $Destination
    )

    Write-Host "Téléchargement : $Uri"
    Invoke-WebRequest -Uri $Uri -OutFile $Destination -UseBasicParsing
}

function Test-Executable {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name introuvable après installation : $Path"
    }

    Write-Host "==> $Name"
    & $Path @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name a retourné le code $LASTEXITCODE"
    }
}

try {
    if ($Force -or -not (Test-Path -LiteralPath $CoursierExe -PathType Leaf)) {
        Remove-Item -LiteralPath $CoursierExtract -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $CoursierZip -Force -ErrorAction SilentlyContinue

        Download-File -Uri $CoursierUrl -Destination $CoursierZip
        Expand-Archive -LiteralPath $CoursierZip -DestinationPath $CoursierExtract -Force

        $DownloadedCoursier = Get-ChildItem -LiteralPath $CoursierExtract -Recurse -File |
            Where-Object { $_.Name -eq "cs-x86_64-pc-win32.exe" -or $_.Name -eq "cs.exe" } |
            Select-Object -First 1

        if (-not $DownloadedCoursier) {
            throw "Launcher Coursier introuvable dans l'archive téléchargée."
        }

        Copy-Item -LiteralPath $DownloadedCoursier.FullName -Destination $CoursierExe -Force
    }

    if ($Force -or -not (Test-Path -LiteralPath $ScipExe -PathType Leaf)) {
        if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
            throw "tar.exe est requis pour extraire le binaire SCIP sous Windows."
        }

        Remove-Item -LiteralPath $ScipExtract -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $ScipArchive -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $ScipExtract | Out-Null

        Download-File -Uri $ScipUrl -Destination $ScipArchive
        & tar.exe -xzf $ScipArchive -C $ScipExtract
        if ($LASTEXITCODE -ne 0) {
            throw "Extraction de SCIP échouée avec le code $LASTEXITCODE"
        }

        $DownloadedScip = Get-ChildItem -LiteralPath $ScipExtract -Recurse -File |
            Where-Object { $_.Name -eq "scip.exe" -or $_.Name -eq "scip" } |
            Select-Object -First 1

        if (-not $DownloadedScip) {
            throw "Binaire SCIP introuvable dans l'archive téléchargée."
        }

        Copy-Item -LiteralPath $DownloadedScip.FullName -Destination $ScipExe -Force
    }

    Write-Host
    Write-Host "=== OUTILS MINOS M0 ===" -ForegroundColor Cyan
    Test-Executable -Path $CoursierExe -Arguments @("version") -Name "Coursier"
    Test-Executable -Path $ScipExe -Arguments @("--version") -Name "SCIP CLI"

    Write-Host
    Write-Host "Installation locale terminée." -ForegroundColor Green
    Write-Host "Coursier : $CoursierExe"
    Write-Host "SCIP     : $ScipExe"
    Write-Host
    Write-Host "Aucune modification du PATH utilisateur ni du JDK n'a été effectuée."
}
finally {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
