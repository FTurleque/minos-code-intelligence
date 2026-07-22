[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectPath,

    [string] $OutputDirectory,

    [string] $ScipJavaVersion = "0.13.1",

    [string] $CoursierCommand,

    [string] $ScipCommand
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$LocalToolsBin = Join-Path $RepoRoot ".minos-m0\tools\bin"
$LocalCoursier = Join-Path $LocalToolsBin "cs.exe"
$LocalScip = Join-Path $LocalToolsBin "scip.exe"

function Resolve-ToolCommand {
    param(
        [string] $ExplicitCommand,
        [Parameter(Mandatory = $true)][string] $LocalPath,
        [Parameter(Mandatory = $true)][string] $FallbackName,
        [Parameter(Mandatory = $true)][string] $DisplayName
    )

    if (-not [string]::IsNullOrWhiteSpace($ExplicitCommand)) {
        if (Test-Path -LiteralPath $ExplicitCommand -PathType Leaf) {
            return (Resolve-Path -LiteralPath $ExplicitCommand).Path
        }
        $ResolvedExplicit = Get-Command $ExplicitCommand -ErrorAction SilentlyContinue
        if ($ResolvedExplicit) {
            return $ResolvedExplicit.Source
        }
        throw "$DisplayName introuvable : $ExplicitCommand"
    }

    if (Test-Path -LiteralPath $LocalPath -PathType Leaf) {
        return $LocalPath
    }

    $GlobalCommand = Get-Command $FallbackName -ErrorAction SilentlyContinue
    if ($GlobalCommand) {
        return $GlobalCommand.Source
    }

    throw "$DisplayName introuvable. Exécuter d'abord .\scripts\m0\install-scip-tools.ps1"
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

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw "Commande Java introuvable. Vérifier la configuration Java du poste."
}

$ResolvedCoursierCommand = Resolve-ToolCommand `
    -ExplicitCommand $CoursierCommand `
    -LocalPath $LocalCoursier `
    -FallbackName "cs" `
    -DisplayName "Coursier"

$ResolvedScipCommand = Resolve-ToolCommand `
    -ExplicitCommand $ScipCommand `
    -LocalPath $LocalScip `
    -FallbackName "scip" `
    -DisplayName "SCIP CLI"

$ResolvedProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $ResolvedProjectPath ".minos-m0\scip-java"
}

$ResolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $ResolvedOutputDirectory | Out-Null

$MetadataFile = Join-Path $ResolvedOutputDirectory "environment.txt"
$IndexDestination = Join-Path $ResolvedOutputDirectory "index.scip"
$LintFile = Join-Path $ResolvedOutputDirectory "lint.txt"
$StatsFile = Join-Path $ResolvedOutputDirectory "stats.txt"
$SnapshotDirectory = Join-Path $ResolvedOutputDirectory "snapshot"

Write-Host "Projet       : $ResolvedProjectPath"
Write-Host "Sorties      : $ResolvedOutputDirectory"
Write-Host "scip-java    : $ScipJavaVersion"
Write-Host "Coursier     : $ResolvedCoursierCommand"
Write-Host "SCIP CLI     : $ResolvedScipCommand"
Write-Host

Push-Location $ResolvedProjectPath
try {
    @(
        "date=$(Get-Date -Format o)",
        "project=$ResolvedProjectPath",
        "scipJavaVersion=$ScipJavaVersion",
        "coursierCommand=$ResolvedCoursierCommand",
        "scipCommand=$ResolvedScipCommand"
    ) | Set-Content -Encoding UTF8 $MetadataFile

    "=== java -version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& java -version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    "=== coursier version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedCoursierCommand version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    "=== scip --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedScipCommand --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    $Coordinate = "org.scip-code:scip-java:$ScipJavaVersion"

    Invoke-Checked -Command $ResolvedCoursierCommand -Arguments @(
        "launch",
        $Coordinate,
        "--",
        "index"
    ) -Description "Génération de index.scip avec scip-java"

    $GeneratedIndex = Join-Path $ResolvedProjectPath "index.scip"
    if (-not (Test-Path -LiteralPath $GeneratedIndex -PathType Leaf)) {
        throw "scip-java n'a pas produit index.scip dans $ResolvedProjectPath"
    }

    Copy-Item -LiteralPath $GeneratedIndex -Destination $IndexDestination -Force

    Write-Host "==> Validation scip lint"
    & $ResolvedScipCommand lint $IndexDestination 2>&1 | Tee-Object -FilePath $LintFile
    if ($LASTEXITCODE -ne 0) {
        throw "scip lint a échoué avec le code $LASTEXITCODE"
    }

    Write-Host "==> Statistiques scip stats"
    & $ResolvedScipCommand stats --from $IndexDestination 2>&1 | Tee-Object -FilePath $StatsFile
    if ($LASTEXITCODE -ne 0) {
        throw "scip stats a échoué avec le code $LASTEXITCODE"
    }

    if (Test-Path -LiteralPath $SnapshotDirectory) {
        Remove-Item -LiteralPath $SnapshotDirectory -Recurse -Force
    }

    Invoke-Checked -Command $ResolvedScipCommand -Arguments @(
        "snapshot",
        "--from",
        $IndexDestination,
        "--to",
        $SnapshotDirectory
    ) -Description "Génération du snapshot SCIP"

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
