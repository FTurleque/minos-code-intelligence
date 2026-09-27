[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $JarPath,
    [Parameter(Mandatory = $true)][Alias('Home')][string] $BenchmarkHome,
    [ValidateSet('SMOKE','STANDARD','EXTENDED','STRESS')][string] $Profile = 'STANDARD',
    [ValidateRange(5,500)][int] $Repetitions = 30,
    [Parameter(Mandatory = $true)][string] $OutputJson
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
$java = Resolve-MinosJava24
$jar = [System.IO.Path]::GetFullPath($JarPath)
$homePath = [System.IO.Path]::GetFullPath($BenchmarkHome)
$output = [System.IO.Path]::GetFullPath($OutputJson)
$source = Join-Path $RepoRoot 'scripts\m16\M16ScaleBenchmark.java'
$work = Join-Path ([System.IO.Path]::GetDirectoryName($output)) 'process'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$stdout = Join-Path $work 'scale.out.log'
$stderr = Join-Path $work 'scale.err.log'
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue

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
        if (-not $versionProcess.Start()) {
            throw 'Unable to start java -version.'
        }
        $outTask = $versionProcess.StandardOutput.ReadToEndAsync()
        $errTask = $versionProcess.StandardError.ReadToEndAsync()
        $versionProcess.WaitForExit()
        $outText = $outTask.Result
        $errText = $errTask.Result
        if ($versionProcess.ExitCode -ne 0) {
            throw "java -version failed (exit=$($versionProcess.ExitCode)).`n$errText`n$outText"
        }
        $firstLine = @(($errText + "`n" + $outText) -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -First 1)
        if ($firstLine.Count -eq 0) {
            throw 'java -version produced no version line.'
        }
        return $firstLine[0].Trim()
    }
    finally {
        $versionProcess.Dispose()
    }
}

$arguments = @('--class-path', $jar, $source, $homePath, $Profile, [string]$Repetitions, $output)
$encoded = ($arguments | ForEach-Object { Quote-Arg $_ }) -join ' '
$startInfo = New-Object System.Diagnostics.ProcessStartInfo
$startInfo.FileName = $java.JavaExecutable
$startInfo.Arguments = $encoded
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $startInfo
$watch = [System.Diagnostics.Stopwatch]::StartNew()
if (-not $process.Start()) {
    throw 'Unable to start M16 scale benchmark process.'
}
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
[long] $peakRss = 0
while (-not $process.HasExited) {
    try {
        $sample = Get-Process -Id $process.Id -ErrorAction Stop
        if ($sample.WorkingSet64 -gt $peakRss) { $peakRss = $sample.WorkingSet64 }
    } catch { }
    Start-Sleep -Milliseconds 100
    $process.Refresh()
}
$process.WaitForExit()
$watch.Stop()
$stdoutText = $stdoutTask.Result
$stderrText = $stderrTask.Result
$exitCode = $process.ExitCode
[System.IO.File]::WriteAllText($stdout, $stdoutText)
[System.IO.File]::WriteAllText($stderr, $stderrText)
$process.Dispose()
if ($exitCode -ne 0) {
    throw "M16 scale benchmark process failed (exit=$exitCode).`n$stderrText`n$stdoutText"
}
if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw "M16 scale benchmark did not produce $output"
}

$data = Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
$data | Add-Member -NotePropertyName process_rss_bytes -NotePropertyValue $peakRss -Force
$data | Add-Member -NotePropertyName process_elapsed_ms -NotePropertyValue ([Math]::Round($watch.Elapsed.TotalMilliseconds,4)) -Force

$head = ((& git -C $RepoRoot rev-parse HEAD) | Select-Object -First 1).Trim()
$os = [System.Environment]::OSVersion.VersionString
$cpuName = $null
$totalMemory = $null
try {
    $cpuName = (Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name)
    $totalMemory = [long](Get-CimInstance Win32_ComputerSystem | Select-Object -ExpandProperty TotalPhysicalMemory)
} catch { }
$machine = [ordered]@{
    head = $head
    os = $os
    processor = $cpuName
    logical_processors = [System.Environment]::ProcessorCount
    total_physical_memory_bytes = $totalMemory
    java_home = $java.JavaHome
    java_version = Get-JavaVersionLine -JavaExecutable $java.JavaExecutable
    profile = $Profile
    repetitions = $Repetitions
}
$data | Add-Member -NotePropertyName machine -NotePropertyValue $machine -Force
$data | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $output -Encoding UTF8

Write-Host 'M16 SCALE BENCHMARK SUCCESS' -ForegroundColor Green
Get-Content -LiteralPath $stdout | ForEach-Object { Write-Host $_ }
Write-Host "process-rss=$peakRss elapsed=$([Math]::Round($watch.Elapsed.TotalMilliseconds,4))ms"
