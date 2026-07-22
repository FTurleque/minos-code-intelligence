[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $IndexPath,

    [Parameter(Mandatory = $true)]
    [string] $OutputPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ResolvedIndexPath = (Resolve-Path -LiteralPath $IndexPath).Path
$ResolvedOutputPath = if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    [System.IO.Path]::GetFullPath($OutputPath)
}
else {
    [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $OutputPath))
}
$MavenWrapper = Join-Path $RepoRoot "mvnw.cmd"
$ClasspathFile = Join-Path $RepoRoot "target\m0-classpath.txt"

$OutputParent = Split-Path -Parent $ResolvedOutputPath
New-Item -ItemType Directory -Force -Path $OutputParent | Out-Null

Push-Location $RepoRoot
try {
    & $MavenWrapper -q test-compile dependency:build-classpath `
        "-Dmdep.outputFile=target/m0-classpath.txt"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed to compile the Glean compatibility harness (exit $LASTEXITCODE)."
    }

    $ExperimentClasspath = "$(Resolve-Path target/test-classes);" +
        "$(Resolve-Path target/classes);" +
        (Get-Content -Raw -LiteralPath $ClasspathFile)

    & java `
        -classpath $ExperimentClasspath `
        com.minos.adapter.scip.ScipLegacyRangeCompatibilityExperiment `
        $ResolvedIndexPath `
        $ResolvedOutputPath
    if ($LASTEXITCODE -ne 0) {
        throw "SCIP range compatibility export failed (exit $LASTEXITCODE)."
    }
}
finally {
    Pop-Location
}

Write-Host
Write-Host "Glean-compatible SCIP copy created." -ForegroundColor Green
Write-Host "Source: $ResolvedIndexPath"
Write-Host "Copy  : $ResolvedOutputPath"
