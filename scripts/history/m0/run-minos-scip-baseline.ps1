[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $IndexPath,

    [string] $OutputFile,

    [string] $ProjectId = "m0-real-index",

    [string] $ProviderId = "scip-java",

    [string] $ProviderVersion = "0.13.1",

    [string] $IndexRunId = "m0-real-index",

    [string[]] $Queries = @()
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ResolvedIndexPath = (Resolve-Path -LiteralPath $IndexPath).Path
$MavenWrapper = Join-Path $RepoRoot "mvnw.cmd"
$ClasspathFile = Join-Path $RepoRoot "target\m0-classpath.txt"

if ([string]::IsNullOrWhiteSpace($OutputFile)) {
    $OutputFile = Join-Path (Split-Path -Parent $ResolvedIndexPath) "minos-baseline.txt"
}
elseif (-not [System.IO.Path]::IsPathRooted($OutputFile)) {
    $OutputFile = Join-Path (Get-Location).Path $OutputFile
}

$OutputParent = Split-Path -Parent $OutputFile
New-Item -ItemType Directory -Force -Path $OutputParent | Out-Null

Push-Location $RepoRoot
try {
    & $MavenWrapper -q test-compile dependency:build-classpath `
        "-Dmdep.outputFile=target/m0-classpath.txt"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed to compile the M0 experiment harness (exit $LASTEXITCODE)."
    }

    $ExperimentClasspath = "$(Resolve-Path target/test-classes);" +
        "$(Resolve-Path target/classes);" +
        (Get-Content -Raw -LiteralPath $ClasspathFile)

    $Arguments = @(
        "-Dminos.m0.projectId=$ProjectId",
        "-Dminos.m0.providerId=$ProviderId",
        "-Dminos.m0.providerVersion=$ProviderVersion",
        "-Dminos.m0.indexRunId=$IndexRunId",
        "-classpath",
        $ExperimentClasspath,
        "com.minos.adapter.scip.ScipRealIndexExperiment",
        $ResolvedIndexPath
    ) + $Queries

    & java @Arguments 2>&1 | Tee-Object -FilePath $OutputFile
    $ExperimentExitCode = $LASTEXITCODE
    if ($ExperimentExitCode -ne 0) {
        throw "MINOS real-index experiment failed with exit code $ExperimentExitCode."
    }
}
finally {
    Pop-Location
}

Write-Host
Write-Host "MINOS baseline experiment completed." -ForegroundColor Green
Write-Host "Index  : $ResolvedIndexPath"
Write-Host "Results: $OutputFile"
