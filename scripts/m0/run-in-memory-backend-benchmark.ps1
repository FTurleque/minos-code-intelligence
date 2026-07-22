[CmdletBinding()]
param(
    [string] $ConfigurationPath = "benchmarks\m0\e1-in-memory.json",

    [string] $OutputDirectory = ".minos-m0\benchmarks\e1-in-memory"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$MavenWrapper = Join-Path $RepoRoot "mvnw.cmd"
$ClasspathFile = Join-Path $RepoRoot "target\m0-classpath.txt"

function Resolve-FromRepoRoot {
    param([Parameter(Mandatory = $true)][string] $Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Write-TransactionalText {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string] $Content
    )

    $PartialPath = "$Path.partial"
    [System.IO.File]::WriteAllText($PartialPath, $Content, [System.Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $PartialPath -Destination $Path -Force
}

function Invoke-JavaBenchmark {
    param(
        [Parameter(Mandatory = $true)][string] $JavaCommand,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )

    $StartInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $StartInfo.FileName = $JavaCommand
    $StartInfo.UseShellExecute = $false
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    $StartInfo.CreateNoWindow = $true
    foreach ($Argument in $Arguments) {
        $StartInfo.ArgumentList.Add($Argument)
    }

    $Process = [System.Diagnostics.Process]::new()
    $Process.StartInfo = $StartInfo
    $Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not $Process.Start()) {
        throw "Unable to start Java benchmark process."
    }
    $StandardOutput = $Process.StandardOutput.ReadToEndAsync()
    $StandardError = $Process.StandardError.ReadToEndAsync()
    $Process.WaitForExit()
    $Stopwatch.Stop()

    return [pscustomobject]@{
        ExitCode = $Process.ExitCode
        StandardOutput = $StandardOutput.GetAwaiter().GetResult()
        StandardError = $StandardError.GetAwaiter().GetResult()
        WallClockMs = $Stopwatch.Elapsed.TotalMilliseconds
    }
}

$ResolvedConfigurationPath = Resolve-FromRepoRoot $ConfigurationPath
$ResolvedOutputDirectory = Resolve-FromRepoRoot $OutputDirectory
$Configuration = Get-Content -Raw -LiteralPath $ResolvedConfigurationPath | ConvertFrom-Json

if ($Configuration.backend -ne "InMemoryCodeKnowledgeStore") {
    throw "E1 configuration must target InMemoryCodeKnowledgeStore."
}
if ([int] $Configuration.warmupIterations -lt 1 -or
        [int] $Configuration.measurementIterations -lt 1) {
    throw "Benchmark iteration counts must be greater than zero."
}

$DatasetIds = @($Configuration.datasets | ForEach-Object { $_.id })
if (($DatasetIds | Sort-Object -Unique).Count -ne $DatasetIds.Count) {
    throw "Benchmark dataset identifiers must be unique."
}

New-Item -ItemType Directory -Force -Path $ResolvedOutputDirectory | Out-Null

Push-Location $RepoRoot
try {
    & $MavenWrapper -q test-compile dependency:build-classpath `
        "-Dmdep.outputFile=target/m0-classpath.txt"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed to compile the E1 harness (exit $LASTEXITCODE)."
    }

    $ExperimentClasspath = "$(Resolve-Path target/test-classes);" +
        "$(Resolve-Path target/classes);" +
        (Get-Content -Raw -LiteralPath $ClasspathFile)
    $JavaCommand = (Get-Command java -CommandType Application).Source
    $GitCommit = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to resolve the benchmark Git commit."
    }

    $TotalPhysicalMemory = "unavailable"
    try {
        $ComputerSystem = Get-CimInstance Win32_ComputerSystem
        $TotalPhysicalMemory = $ComputerSystem.TotalPhysicalMemory
    }
    catch {
        $TotalPhysicalMemory = "unavailable: $($_.Exception.Message)"
    }

    $EnvironmentLines = @(
        "date=$([DateTimeOffset]::Now.ToString('o'))",
        "benchmark=$($Configuration.benchmark)",
        "backend=$($Configuration.backend)",
        "configuration=$ResolvedConfigurationPath",
        "gitCommit=$GitCommit",
        "os=$([System.Environment]::OSVersion.VersionString)",
        "architecture=$([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture)",
        "processor=$env:PROCESSOR_IDENTIFIER",
        "logicalProcessors=$([System.Environment]::ProcessorCount)",
        "totalPhysicalMemoryBytes=$TotalPhysicalMemory",
        "warmupIterations=$($Configuration.warmupIterations)",
        "measurementIterations=$($Configuration.measurementIterations)",
        "=== java --version ===",
        ((& $JavaCommand --version 2>&1) -join [Environment]::NewLine)
    )
    Write-TransactionalText `
        -Path (Join-Path $ResolvedOutputDirectory "environment.txt") `
        -Content (($EnvironmentLines -join [Environment]::NewLine) + [Environment]::NewLine)

    $SummaryRows = [System.Collections.Generic.List[object]]::new()
    $Failures = [System.Collections.Generic.List[string]]::new()

    foreach ($Dataset in $Configuration.datasets) {
        $DatasetId = [string] $Dataset.id
        $ResolvedIndexPath = Resolve-FromRepoRoot ([string] $Dataset.indexPath)
        $DatasetOutputDirectory = Join-Path $ResolvedOutputDirectory $DatasetId
        New-Item -ItemType Directory -Force -Path $DatasetOutputDirectory | Out-Null

        if (-not (Test-Path -LiteralPath $ResolvedIndexPath -PathType Leaf)) {
            $Failures.Add("$DatasetId`: index not found at $ResolvedIndexPath")
            continue
        }

        Write-Host "==> $DatasetId" -ForegroundColor Cyan
        $Arguments = @(
            "-Dminos.m0.dataset=$DatasetId",
            "-Dminos.m0.projectId=$($Dataset.projectId)",
            "-Dminos.m0.providerId=$($Dataset.providerId)",
            "-Dminos.m0.providerVersion=$($Dataset.providerVersion)",
            "-Dminos.m0.warmupIterations=$($Configuration.warmupIterations)",
            "-Dminos.m0.measurementIterations=$($Configuration.measurementIterations)",
            "-classpath",
            $ExperimentClasspath,
            "io.github.fturleque.minos.adapter.scip.InMemoryBackendBenchmark",
            $ResolvedIndexPath
        ) + @($Dataset.queries)

        $Run = Invoke-JavaBenchmark -JavaCommand $JavaCommand -Arguments $Arguments
        $ResultContent = $Run.StandardOutput +
            "METRIC`tprocessWallClockMs`t$($Run.WallClockMs.ToString('F3', [Globalization.CultureInfo]::InvariantCulture))" +
            [Environment]::NewLine
        Write-TransactionalText `
            -Path (Join-Path $DatasetOutputDirectory "result.tsv") `
            -Content $ResultContent
        Write-TransactionalText `
            -Path (Join-Path $DatasetOutputDirectory "stderr.txt") `
            -Content $Run.StandardError

        if ($Run.ExitCode -ne 0) {
            $Failures.Add("$DatasetId`: Java benchmark exit $($Run.ExitCode)")
            continue
        }

        $Metrics = @{}
        $Summaries = @{}
        foreach ($Line in ($Run.StandardOutput -split "`r?`n")) {
            $Parts = $Line -split "`t"
            if ($Parts.Count -ge 3 -and $Parts[0] -eq "METRIC") {
                $Metrics[$Parts[1]] = $Parts[2]
            }
            elseif ($Parts.Count -ge 6 -and $Parts[0] -eq "SUMMARY") {
                $Summaries[$Parts[1]] = $Parts
            }
        }

        if (-not $Summaries.ContainsKey("find_symbol") -or
                -not $Summaries.ContainsKey("find_usages")) {
            $Failures.Add("$DatasetId`: missing operation summaries")
            continue
        }

        $FindSymbol = $Summaries["find_symbol"]
        $FindUsages = $Summaries["find_usages"]
        $SummaryRows.Add([pscustomobject]@{
            dataset = $DatasetId
            indexBytes = $Metrics.indexBytes
            documents = $Metrics.documents
            normalizedSymbols = $Metrics.normalizedSymbols
            occurrences = $Metrics.occurrences
            indexReadMs = $Metrics.indexReadMs
            ingestionMs = $Metrics.ingestionMs
            backendReadyMs = $Metrics.backendReadyMs
            processWallClockMs = $Run.WallClockMs.ToString(
                "F3", [Globalization.CultureInfo]::InvariantCulture)
            findSymbolP50Micros = $FindSymbol[3]
            findSymbolP95Micros = $FindSymbol[4]
            findSymbolMaxMicros = $FindSymbol[5]
            findUsagesP50Micros = $FindUsages[3]
            findUsagesP95Micros = $FindUsages[4]
            findUsagesMaxMicros = $FindUsages[5]
            peakHeapIndexingBytes = $Metrics.peakHeapIndexingBytes
            retainedHeapAfterIngestionBytes = $Metrics.retainedHeapAfterIngestionBytes
            peakHeapQueryBytes = $Metrics.peakHeapQueryBytes
            workingStoreDiskBytes = $Metrics.workingStoreDiskBytes
            resultDigestStable = $Metrics.resultDigestStable
        })
    }

    $SummaryHeader = @(
        "dataset", "indexBytes", "documents", "normalizedSymbols", "occurrences",
        "indexReadMs", "ingestionMs", "backendReadyMs", "processWallClockMs",
        "findSymbolP50Micros", "findSymbolP95Micros", "findSymbolMaxMicros",
        "findUsagesP50Micros", "findUsagesP95Micros", "findUsagesMaxMicros",
        "peakHeapIndexingBytes", "retainedHeapAfterIngestionBytes",
        "peakHeapQueryBytes", "workingStoreDiskBytes", "resultDigestStable"
    )
    $SummaryLines = [System.Collections.Generic.List[string]]::new()
    $SummaryLines.Add(($SummaryHeader -join "`t"))
    foreach ($Row in $SummaryRows) {
        $SummaryLines.Add((@($SummaryHeader | ForEach-Object { $Row.$_ }) -join "`t"))
    }
    Write-TransactionalText `
        -Path (Join-Path $ResolvedOutputDirectory "summary.tsv") `
        -Content (($SummaryLines -join [Environment]::NewLine) + [Environment]::NewLine)

    if ($Failures.Count -gt 0) {
        throw "E1 benchmark completed with failures: $($Failures -join '; ')"
    }
}
finally {
    Pop-Location
}

Write-Host
Write-Host "E1 in-memory backend benchmark completed." -ForegroundColor Green
Write-Host "Results: $ResolvedOutputDirectory"
