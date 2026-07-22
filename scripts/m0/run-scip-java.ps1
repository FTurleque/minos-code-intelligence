[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectPath,

    [string] $OutputDirectory,

    [string] $ScipJavaVersion = "0.13.1",

    [string] $CoursierCommand = "cs",

    [string] $ScipCommand = "scip"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Require-Command {
    param([Parameter(Mandatory = $true)][string] $Name)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Commande requise introuvable : $Name"
    }
}

function Invoke-Checked {
    param(
        [Parameter(Mandatory = $true)][string] $Command,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Description
    )

    Write-Host "==> $Description"
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Description a échoué avec le code $LASTEXITCODE"
    }
}

$ResolvedProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $ResolvedProjectPath ".minos-m0\scip-java"
}

$ResolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $ResolvedOutputDirectory | Out-Null

Require-Command -Name "java"
Require-Command -Name $CoursierCommand
Require-Command -Name $ScipCommand

$MetadataFile = Join-Path $ResolvedOutputDirectory "environment.txt"
$IndexDestination = Join-Path $ResolvedOutputDirectory "index.scip"
$LintFile = Join-Path $ResolvedOutputDirectory "lint.txt"
$StatsFile = Join-Path $ResolvedOutputDirectory "stats.txt"
$SnapshotDirectory = Join-Path $ResolvedOutputDirectory "snapshot"

Write-Host "Projet       : $ResolvedProjectPath"
Write-Host "Sorties      : $ResolvedOutputDirectory"
Write-Host "scip-java    : $ScipJavaVersion"
Write-Host

Push-Location $ResolvedProjectPath
try {
    @(
        "date=$(Get-Date -Format o)",
        "project=$ResolvedProjectPath",
        "scipJavaVersion=$ScipJavaVersion"
    ) | Set-Content -Encoding UTF8 $MetadataFile

    "=== java -version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& java -version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    "=== scip --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ScipCommand --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    $Coordinate = "org.scip-code:scip-java:$ScipJavaVersion"

    Invoke-Checked \
        -Command $CoursierCommand \
        -Arguments @("launch", $Coordinate, "--", "index") \
        -Description "Génération de index.scip avec scip-java"

    $GeneratedIndex = Join-Path $ResolvedProjectPath "index.scip"
    if (-not (Test-Path -LiteralPath $GeneratedIndex -PathType Leaf)) {
        throw "scip-java n'a pas produit index.scip dans $ResolvedProjectPath"
    }

    Copy-Item -LiteralPath $GeneratedIndex -Destination $IndexDestination -Force

    Write-Host "==> Validation scip lint"
    & $ScipCommand lint $IndexDestination 2>&1 | Tee-Object -FilePath $LintFile
    if ($LASTEXITCODE -ne 0) {
        throw "scip lint a échoué avec le code $LASTEXITCODE"
    }

    Write-Host "==> Statistiques scip stats"
    & $ScipCommand stats --from $IndexDestination 2>&1 | Tee-Object -FilePath $StatsFile
    if ($LASTEXITCODE -ne 0) {
        throw "scip stats a échoué avec le code $LASTEXITCODE"
    }

    if (Test-Path -LiteralPath $SnapshotDirectory) {
        Remove-Item -LiteralPath $SnapshotDirectory -Recurse -Force
    }

    Invoke-Checked \
        -Command $ScipCommand \
        -Arguments @("snapshot", "--from", $IndexDestination, "--to", $SnapshotDirectory) \
        -Description "Génération du snapshot SCIP"

    Write-Host
    Write-Host "Expérience scip-java terminée."
    Write-Host "Index     : $IndexDestination"
    Write-Host "Lint      : $LintFile"
    Write-Host "Stats     : $StatsFile"
    Write-Host "Snapshot  : $SnapshotDirectory"
    Write-Host "Contexte  : $MetadataFile"
}
finally {
    Pop-Location
}
