[CmdletBinding()]
param(
    [ValidateRange(1, 200)]
    [int] $Repetitions = 20
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')

function Invoke-NativeCaptured {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $LogPath,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $LogPath
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0) { throw "$Failure (exit=$exitCode)" }
}

Push-Location $RepoRoot
try {
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve current Git HEAD.' }

    $validationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m14-" + $head.Substring(0, 12))
    $validationHome = Join-Path $validationRoot 'home'
    $installedJar = Join-Path $validationRoot 'installed\lib\minos.jar'
    $probeSource = Join-Path $RepoRoot 'scripts\m15\M15FinalQueryProbe.java'
    $outputRoot = Join-Path $RepoRoot 'target\m15-final'
    $queryJson = Join-Path $outputRoot 'query-final.json'
    $queryLog = Join-Path $outputRoot 'query-final.log'

    foreach ($required in @($validationHome, $installedJar, $probeSource)) {
        if (-not (Test-Path -LiteralPath $required)) { throw "Required final-query input is missing: $required" }
    }

    New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null
    Remove-Item -LiteralPath $queryJson, $queryLog -Force -ErrorAction SilentlyContinue

    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host ''
    Write-Host '=== M15 final repeated-query/cache/index probe ===' -ForegroundColor Cyan
    Write-Host "HEAD        : $head"
    Write-Host "Project     : m14-java"
    Write-Host "Repetitions : $Repetitions"

    Invoke-NativeCaptured -File $java.JavaExecutable -Arguments @(
        '--class-path', $installedJar,
        $probeSource,
        $validationHome,
        'm14-java',
        [string] $Repetitions,
        $queryJson
    ) -LogPath $queryLog -Failure 'M15 final query probe failed'

    $metrics = Get-Content -LiteralPath $queryJson -Raw | ConvertFrom-Json
    foreach ($name in @(
        'full_snapshot_load_count','query_view_build_count','cache_hits','cache_entries','cache_maximum_entries',
        'first_query_latency_ms','repeated_query_latency_p50_ms','repeated_query_latency_p95_ms',
        'query_view_build_ms','heap_after_load_bytes','index_symbol_entries','index_references'
    )) {
        if ($null -eq $metrics.PSObject.Properties[$name]) { throw "Final query metrics missing '$name'." }
    }
    if ([long] $metrics.full_snapshot_load_count -ne 1L) { throw "Expected full_snapshot_load_count=1." }
    if ([long] $metrics.query_view_build_count -ne 1L) { throw "Expected query_view_build_count=1." }
    if ([int] $metrics.cache_entries -gt [int] $metrics.cache_maximum_entries) { throw 'Cache memory bound violated.' }

    Write-Host ''
    Write-Host 'M15 FINAL QUERY CACHE/INDEX SUCCESS' -ForegroundColor Green
    Write-Host "first=$($metrics.first_query_latency_ms)ms p50=$($metrics.repeated_query_latency_p50_ms)ms p95=$($metrics.repeated_query_latency_p95_ms)ms full-loads=$($metrics.full_snapshot_load_count) builds=$($metrics.query_view_build_count)"
    Write-Host "view-build=$($metrics.query_view_build_ms)ms index-references=$($metrics.index_references) heap=$($metrics.heap_after_load_bytes)"
}
finally {
    Pop-Location
}
