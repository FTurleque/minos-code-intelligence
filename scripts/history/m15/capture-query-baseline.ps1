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

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments 2>&1 |
            ForEach-Object { $_.ToString() } |
            Tee-Object -FilePath $LogPath
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "$Failure (exit=$exitCode)"
    }
}

Push-Location $RepoRoot
try {
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
        throw 'Unable to resolve current Git HEAD.'
    }

    $validationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m14-" + $head.Substring(0, 12))
    $validationHome = Join-Path $validationRoot 'home'
    $installedJar = Join-Path $validationRoot 'installed\lib\minos.jar'
    $probeSource = Join-Path $RepoRoot 'scripts\m15\M15RepeatedQueryProbe.java'
    $baselineRoot = Join-Path $RepoRoot 'target\m15-baseline'
    $queryJson = Join-Path $baselineRoot 'query-baseline.json'
    $queryMarkdown = Join-Path $baselineRoot 'query-baseline.md'
    $queryLog = Join-Path $baselineRoot 'query-baseline.log'

    if (-not (Test-Path -LiteralPath $validationHome -PathType Container)) {
        throw "M14 validation home is missing: $validationHome. Run scripts/m15/run-s1.ps1 without -SkipM14Replay."
    }
    if (-not (Test-Path -LiteralPath $installedJar -PathType Leaf)) {
        throw "Installed M14 replay JAR is missing: $installedJar"
    }
    if (-not (Test-Path -LiteralPath $probeSource -PathType Leaf)) {
        throw "M15 repeated-query probe is missing: $probeSource"
    }

    New-Item -ItemType Directory -Force -Path $baselineRoot | Out-Null
    Remove-Item -LiteralPath $queryJson, $queryMarkdown, $queryLog -Force -ErrorAction SilentlyContinue

    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host ''
    Write-Host '=== M15-S1 repeated-query baseline ===' -ForegroundColor Cyan
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
    ) -LogPath $queryLog -Failure 'M15-S1 repeated-query baseline probe failed'

    if (-not (Test-Path -LiteralPath $queryJson -PathType Leaf)) {
        throw "Repeated-query probe did not produce $queryJson"
    }

    $metrics = Get-Content -LiteralPath $queryJson -Raw | ConvertFrom-Json
    $required = @(
        'active_snapshot_load_count',
        'first_query_latency_ms',
        'repeated_query_latency_average_ms',
        'repeated_query_latency_p50_ms',
        'repeated_query_latency_p95_ms',
        'heap_after_load_bytes',
        'symbol_count',
        'occurrence_count',
        'relationship_count'
    )
    foreach ($name in $required) {
        if ($null -eq $metrics.PSObject.Properties[$name]) {
            throw "Repeated-query baseline is missing required metric '$name'."
        }
    }

    $markdown = @(
        '# M15-S1 — Repeated-query baseline',
        '',
        "- HEAD: ``$head``",
        "- project: ``$($metrics.projectName)``",
        "- snapshot: ``$($metrics.snapshotId)``",
        "- query: ``$($metrics.queryText)``",
        "- repetitions: ``$($metrics.repetitions)``",
        '',
        '## Required S1 metrics',
        '',
        "- active_snapshot_load_count: ``$($metrics.active_snapshot_load_count)``",
        "- first_query_latency_ms: ``$($metrics.first_query_latency_ms)``",
        "- repeated_query_latency_average_ms: ``$($metrics.repeated_query_latency_average_ms)``",
        "- repeated_query_latency_p50_ms: ``$($metrics.repeated_query_latency_p50_ms)``",
        "- repeated_query_latency_p95_ms: ``$($metrics.repeated_query_latency_p95_ms)``",
        "- heap_after_load_bytes: ``$($metrics.heap_after_load_bytes)``",
        "- symbol_count: ``$($metrics.symbol_count)``",
        "- occurrence_count: ``$($metrics.occurrence_count)``",
        "- relationship_count: ``$($metrics.relationship_count)``",
        '',
        '## Load-count basis',
        '',
        $metrics.activeSnapshotLoadCountBasis
    )
    [System.IO.File]::WriteAllLines($queryMarkdown, $markdown, [System.Text.UTF8Encoding]::new($false))

    Write-Host ''
    Write-Host 'M15-S1 REPEATED-QUERY BASELINE SUCCESS' -ForegroundColor Green
    Write-Host "first=$($metrics.first_query_latency_ms)ms p50=$($metrics.repeated_query_latency_p50_ms)ms p95=$($metrics.repeated_query_latency_p95_ms)ms loads=$($metrics.active_snapshot_load_count)"
    Write-Host "symbols=$($metrics.symbol_count) occurrences=$($metrics.occurrence_count) relationships=$($metrics.relationship_count) heap=$($metrics.heap_after_load_bytes)"
}
finally {
    Pop-Location
}
