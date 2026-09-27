[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ProjectPath,

    [string] $OutputDirectory,

    [string] $ScipTypeScriptVersion = "0.4.0",

    [string] $ScipTypeScriptCommand,

    [string] $ScipCommand
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$LocalIndexer = Join-Path $RepoRoot ".minos-m0\tools\scip-typescript\node_modules\.bin\scip-typescript.cmd"
$LocalScip = Join-Path $RepoRoot ".minos-m0\tools\bin\scip.exe"

function Resolve-ToolCommand {
    param(
        [string] $ExplicitCommand,
        [Parameter(Mandatory = $true)][string] $LocalPath,
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
        throw "$DisplayName not found: $ExplicitCommand"
    }

    if (Test-Path -LiteralPath $LocalPath -PathType Leaf) {
        return $LocalPath
    }

    throw "$DisplayName not found. Install the repo-local M0 tools first."
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw "Node.js command not found."
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw "npm command not found."
}

$ResolvedIndexer = Resolve-ToolCommand `
    -ExplicitCommand $ScipTypeScriptCommand `
    -LocalPath $LocalIndexer `
    -DisplayName "scip-typescript"
$ResolvedScip = Resolve-ToolCommand `
    -ExplicitCommand $ScipCommand `
    -LocalPath $LocalScip `
    -DisplayName "SCIP CLI"
$ResolvedProjectPath = (Resolve-Path -LiteralPath $ProjectPath).Path

if (-not (Test-Path -LiteralPath (Join-Path $ResolvedProjectPath "tsconfig.json") -PathType Leaf)) {
    throw "TypeScript project has no tsconfig.json: $ResolvedProjectPath"
}

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $ResolvedProjectPath ".minos-m0\scip-typescript"
}
$ResolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $ResolvedOutputDirectory | Out-Null

$MetadataFile = Join-Path $ResolvedOutputDirectory "environment.txt"
$IndexDestination = Join-Path $ResolvedOutputDirectory "index.scip"
$IndexDestinationPartial = Join-Path $ResolvedOutputDirectory "index.partial.scip"
$IndexLog = Join-Path $ResolvedOutputDirectory "index.txt"
$IndexLogPartial = Join-Path $ResolvedOutputDirectory "index.partial.txt"
$LintFile = Join-Path $ResolvedOutputDirectory "lint.txt"
$StatsFile = Join-Path $ResolvedOutputDirectory "stats.txt"
$SnapshotDirectory = Join-Path $ResolvedOutputDirectory "snapshot"
$SnapshotLog = Join-Path $ResolvedOutputDirectory "snapshot.txt"
$NonStrictSnapshotDirectory = Join-Path $ResolvedOutputDirectory "snapshot-nonstrict"
$NonStrictSnapshotLog = Join-Path $ResolvedOutputDirectory "snapshot-nonstrict.txt"
$GeneratedIndex = Join-Path $ResolvedProjectPath "index.scip"
$PreexistingIndexBackup = Join-Path $ResolvedOutputDirectory "preexisting-project-index.scip.partial"
$RestorePreexistingIndex = Test-Path -LiteralPath $GeneratedIndex -PathType Leaf
$CopiedGeneratedIndex = $false

if ([System.IO.Path]::GetFullPath($GeneratedIndex) -eq
        [System.IO.Path]::GetFullPath($IndexDestination)) {
    throw "OutputDirectory must not be the analyzed project root."
}

Remove-Item -LiteralPath $IndexDestination -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $IndexDestinationPartial -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $IndexLogPartial -Force -ErrorAction SilentlyContinue

if ($RestorePreexistingIndex) {
    if (Test-Path -LiteralPath $PreexistingIndexBackup) {
        throw "Refusing to overwrite an existing index backup: $PreexistingIndexBackup"
    }
    Move-Item -LiteralPath $GeneratedIndex -Destination $PreexistingIndexBackup
}

Write-Host "Project         : $ResolvedProjectPath"
Write-Host "Output          : $ResolvedOutputDirectory"
Write-Host "scip-typescript : $ScipTypeScriptVersion"
Write-Host "Indexer command : $ResolvedIndexer"
Write-Host "SCIP CLI        : $ResolvedScip"
Write-Host

Push-Location $ResolvedProjectPath
try {
    @(
        "date=$(Get-Date -Format o)",
        "project=$ResolvedProjectPath",
        "scipTypeScriptVersion=$ScipTypeScriptVersion",
        "scipTypeScriptCommand=$ResolvedIndexer",
        "scipCommand=$ResolvedScip"
    ) | Set-Content -Encoding UTF8 $MetadataFile

    "=== node --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& node --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile
    "=== npm --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& npm --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile
    "=== scip-typescript --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedIndexer --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile
    "=== scip --version ===" | Add-Content -Encoding UTF8 $MetadataFile
    (& $ResolvedScip --version 2>&1 | Out-String) | Add-Content -Encoding UTF8 $MetadataFile

    Write-Host "==> Generate index.scip with scip-typescript"
    $IndexStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    & $ResolvedIndexer index 2>&1 | Tee-Object -FilePath $IndexLogPartial
    $IndexExitCode = $LASTEXITCODE
    $IndexStopwatch.Stop()
    $IndexDurationMs = $IndexStopwatch.ElapsedMilliseconds
    if (Test-Path -LiteralPath $IndexLogPartial -PathType Leaf) {
        Move-Item -LiteralPath $IndexLogPartial -Destination $IndexLog -Force
    }
    else {
        New-Item -ItemType File -Force -Path $IndexLog | Out-Null
    }

    $IndexProduced = Test-Path -LiteralPath $GeneratedIndex -PathType Leaf
    $IndexBytes = if ($IndexProduced) { (Get-Item -LiteralPath $GeneratedIndex).Length } else { 0 }
    $LintExitCode = "not-run"
    $StatsExitCode = "not-run"
    $SnapshotExitCode = "not-run"
    $NonStrictSnapshotExitCode = "not-run"

    if ($IndexProduced) {
        Copy-Item -LiteralPath $GeneratedIndex -Destination $IndexDestinationPartial -Force
        Move-Item -LiteralPath $IndexDestinationPartial -Destination $IndexDestination -Force
        $CopiedGeneratedIndex = $true
        Remove-Item -LiteralPath $GeneratedIndex -Force

        Write-Host "==> Run scip lint"
        & $ResolvedScip lint $IndexDestination 2>&1 | Tee-Object -FilePath $LintFile
        $LintExitCode = $LASTEXITCODE

        Write-Host "==> Run scip stats"
        & $ResolvedScip stats --from $IndexDestination --project-root $ResolvedProjectPath 2>&1 |
            Tee-Object -FilePath $StatsFile
        $StatsExitCode = $LASTEXITCODE

        Remove-Item -LiteralPath $SnapshotDirectory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $SnapshotDirectory | Out-Null
        Write-Host "==> Generate SCIP snapshot"
        & $ResolvedScip snapshot `
            --from $IndexDestination `
            --to $SnapshotDirectory `
            --project-root $ResolvedProjectPath 2>&1 |
            Tee-Object -FilePath $SnapshotLog
        $SnapshotExitCode = $LASTEXITCODE

        if ($SnapshotExitCode -ne 0) {
            Remove-Item -LiteralPath $NonStrictSnapshotDirectory -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $NonStrictSnapshotDirectory | Out-Null
            Write-Host "==> Generate non-strict SCIP snapshot after strict snapshot failure"
            & $ResolvedScip snapshot `
                --from $IndexDestination `
                --to $NonStrictSnapshotDirectory `
                --project-root $ResolvedProjectPath `
                --strict=false 2>&1 |
                Tee-Object -FilePath $NonStrictSnapshotLog
            $NonStrictSnapshotExitCode = $LASTEXITCODE
        }
        else {
            Remove-Item -LiteralPath $NonStrictSnapshotDirectory -Recurse -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $NonStrictSnapshotLog -Force -ErrorAction SilentlyContinue
        }
    }
    else {
        Remove-Item -LiteralPath $LintFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $StatsFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $SnapshotDirectory -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $SnapshotLog -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $NonStrictSnapshotDirectory -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $NonStrictSnapshotLog -Force -ErrorAction SilentlyContinue
    }

    @(
        "indexExitCode=$IndexExitCode",
        "indexProduced=$IndexProduced",
        "indexDurationMs=$IndexDurationMs",
        "indexBytes=$IndexBytes",
        "lintExitCode=$LintExitCode",
        "statsExitCode=$StatsExitCode",
        "snapshotExitCode=$SnapshotExitCode"
        "snapshotNonStrictExitCode=$NonStrictSnapshotExitCode"
    ) | Add-Content -Encoding UTF8 $MetadataFile

    Write-Host
    Write-Host "scip-typescript experiment artifacts preserved." -ForegroundColor Green
    Write-Host "Index       : $IndexDestination (produced=$IndexProduced)"
    Write-Host "Index log   : $IndexLog"
    Write-Host "Lint        : $LintFile"
    Write-Host "Stats       : $StatsFile"
    Write-Host "Snapshot    : $SnapshotDirectory"
    Write-Host "Snapshot log: $SnapshotLog"
    Write-Host "Non-strict snapshot    : $NonStrictSnapshotDirectory"
    Write-Host "Non-strict snapshot log: $NonStrictSnapshotLog"
    Write-Host "Context     : $MetadataFile"

    if ($IndexExitCode -ne 0 -or -not $IndexProduced -or
            $LintExitCode -ne 0 -or $StatsExitCode -ne 0 -or $SnapshotExitCode -ne 0) {
        throw "SCIP TypeScript experiment completed with failures: index=$IndexExitCode, indexProduced=$IndexProduced, lint=$LintExitCode, stats=$StatsExitCode, snapshot=$SnapshotExitCode"
    }
}
finally {
    Pop-Location
    if ($RestorePreexistingIndex -and (Test-Path -LiteralPath $PreexistingIndexBackup -PathType Leaf)) {
        Remove-Item -LiteralPath $GeneratedIndex -Force -ErrorAction SilentlyContinue
        Move-Item -LiteralPath $PreexistingIndexBackup -Destination $GeneratedIndex -Force
    }
    elseif ($CopiedGeneratedIndex -and (Test-Path -LiteralPath $GeneratedIndex -PathType Leaf)) {
        Remove-Item -LiteralPath $GeneratedIndex -Force
    }
}
