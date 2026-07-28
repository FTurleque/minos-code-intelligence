[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $JarPath,
    [Parameter(Mandatory = $true)][string] $BenchmarkHome,
    [ValidateSet('SMOKE','STANDARD')][string] $BenchmarkProfile = 'STANDARD',
    [ValidateRange(5,50)][int] $Repetitions = 5,
    [ValidateRange(5,120)][int] $TimeoutMinutes = 30,
    [Parameter(Mandatory = $true)][string] $OutputJson
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
$java = Resolve-MinosJava24
$sourceJar = [System.IO.Path]::GetFullPath($JarPath)
$homePath = [System.IO.Path]::GetFullPath($BenchmarkHome)
$output = [System.IO.Path]::GetFullPath($OutputJson)
$source = Join-Path $RepoRoot 'scripts\m21\M21SemanticScaleProbe.java'
$work = Join-Path ([System.IO.Path]::GetDirectoryName($output)) 'process'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$progress = Join-Path $work 'semantic-scale.progress.log'
Remove-Item -LiteralPath $progress -Force -ErrorAction SilentlyContinue

function Quote-Arg([string] $Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Get-JavaVersionLine {
    param([Parameter(Mandatory = $true)][string] $JavaExecutable)
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaExecutable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $versionProcess = New-Object System.Diagnostics.Process
    $versionProcess.StartInfo = $startInfo
    try {
        if (-not $versionProcess.Start()) { throw 'Unable to start java -version.' }
        $outTask = $versionProcess.StandardOutput.ReadToEndAsync()
        $errTask = $versionProcess.StandardError.ReadToEndAsync()
        $versionProcess.WaitForExit()
        if ($versionProcess.ExitCode -ne 0) { throw 'java -version failed.' }
        $lines = @(($errTask.Result + "`n" + $outTask.Result) -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($lines.Count -eq 0) { throw 'java -version produced no output.' }
        return $lines[0].Trim()
    } finally {
        $versionProcess.Dispose()
    }
}

function Get-ProgressTail {
    param([int] $Lines = 8)
    if (-not (Test-Path -LiteralPath $progress -PathType Leaf)) { return '<no progress emitted yet>' }
    return ((Get-Content -LiteralPath $progress -Tail $Lines -ErrorAction SilentlyContinue) -join "`n")
}

# Never execute the benchmark directly against Maven's root target artifact on Windows.
# A Java/classpath or scanner handle must not be able to block the next exact-head `clean verify`.
$shadowRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'minos-m21-s8'
$shadowDir = Join-Path $shadowRoot ([Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $shadowDir | Out-Null
$shadowJar = Join-Path $shadowDir ([System.IO.Path]::GetFileName($sourceJar))
Copy-Item -LiteralPath $sourceJar -Destination $shadowJar -Force

$process = $null
try {
    $arguments = @('--class-path', $shadowJar, $source, $homePath, $BenchmarkProfile, [string]$Repetitions, $output)
    $encoded = ($arguments | ForEach-Object { Quote-Arg $_ }) -join ' '
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java.JavaExecutable
    $startInfo.Arguments = $encoded
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
    # Inherit the console: probe progress and Java failures must be visible immediately on Windows PowerShell 5.1.
    $startInfo.RedirectStandardOutput = $false
    $startInfo.RedirectStandardError = $false
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    if (-not $process.Start()) { throw 'Unable to start M21-S8 semantic scale probe.' }

    Write-Host "M21-S8 benchmark PID=$($process.Id) profile=$BenchmarkProfile repetitions=$Repetitions watchdog=${TimeoutMinutes}m" -ForegroundColor Cyan
    [long] $peakRss = 0
    [double] $lastHeartbeatSeconds = -15.0

    while (-not $process.WaitForExit(1000)) {
        try {
            $sample = Get-Process -Id $process.Id -ErrorAction Stop
            if ($sample.WorkingSet64 -gt $peakRss) { $peakRss = $sample.WorkingSet64 }
        } catch { }

        if (($watch.Elapsed.TotalSeconds - $lastHeartbeatSeconds) -ge 15.0) {
            $lastHeartbeatSeconds = $watch.Elapsed.TotalSeconds
            $currentStage = if (Test-Path -LiteralPath $progress -PathType Leaf) {
                (Get-Content -LiteralPath $progress -Tail 1 -ErrorAction SilentlyContinue)
            } else {
                '<starting JVM/source launcher>'
            }
            Write-Host "M21-S8 heartbeat elapsed=$([Math]::Round($watch.Elapsed.TotalSeconds,1))s rss=$peakRss stage=$currentStage"
        }

        if ($watch.Elapsed.TotalMinutes -ge $TimeoutMinutes) {
            try { Stop-Process -Id $process.Id -Force -ErrorAction Stop } catch { }
            try { $null = $process.WaitForExit(5000) } catch { }
            throw "M21-S8 benchmark watchdog timeout after $TimeoutMinutes minute(s). Last progress:`n$(Get-ProgressTail)"
        }
    }

    $process.WaitForExit()
    $watch.Stop()
    try {
        $sample = Get-Process -Id $process.Id -ErrorAction Stop
        if ($sample.WorkingSet64 -gt $peakRss) { $peakRss = $sample.WorkingSet64 }
    } catch { }
    $exitCode = $process.ExitCode
    if ($exitCode -ne 0) {
        throw "M21-S8 semantic scale probe failed (exit=$exitCode). Last progress:`n$(Get-ProgressTail)"
    }
    if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
        throw "M21-S8 probe exited successfully but did not produce $output. Last progress:`n$(Get-ProgressTail)"
    }

    $data = Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
    $data | Add-Member -NotePropertyName process_rss_bytes -NotePropertyValue $peakRss -Force
    $data | Add-Member -NotePropertyName process_elapsed_ms -NotePropertyValue ([Math]::Round($watch.Elapsed.TotalMilliseconds,4)) -Force
    $head = ((& git -C $RepoRoot rev-parse HEAD) | Select-Object -First 1).Trim()
    $cpuName = $null
    $totalMemory = $null
    try {
        $cpuName = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
        $totalMemory = [long](Get-CimInstance Win32_ComputerSystem | Select-Object -ExpandProperty TotalPhysicalMemory)
    } catch { }
    $machine = [ordered]@{
        head = $head
        os = [System.Environment]::OSVersion.VersionString
        processor = $cpuName
        logical_processors = [System.Environment]::ProcessorCount
        total_physical_memory_bytes = $totalMemory
        java_home = $java.JavaHome
        java_version = Get-JavaVersionLine -JavaExecutable $java.JavaExecutable
        profile = $BenchmarkProfile
        repetitions = $Repetitions
        benchmark_jar_isolated = $true
        benchmark_watchdog_minutes = $TimeoutMinutes
    }
    $data | Add-Member -NotePropertyName machine -NotePropertyValue $machine -Force
    $data | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $output -Encoding UTF8

    Write-Host 'M21 S8 SEMANTIC SCALE BENCHMARK SUCCESS' -ForegroundColor Green
    Write-Host "process-rss=$peakRss elapsed=$([Math]::Round($watch.Elapsed.TotalMilliseconds,4))ms"
}
finally {
    # Windows PowerShell 5.1 runs on .NET Framework where Process.Kill(Boolean) is not a reliable API.
    # Stop-Process is the compatible cleanup path and prevents an interrupted S8 run from leaving java.exe behind.
    if ($null -ne $process) {
        try {
            if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
        } catch { }
        try { $process.Dispose() } catch { }
    }
    Remove-Item -LiteralPath $shadowDir -Recurse -Force -ErrorAction SilentlyContinue
}
