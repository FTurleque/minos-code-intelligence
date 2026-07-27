[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $JarPath,
    [Parameter(Mandatory = $true)][string] $BenchmarkHome,
    [ValidateSet('SMOKE','STANDARD')][string] $BenchmarkProfile = 'STANDARD',
    [ValidateRange(5,50)][int] $Repetitions = 5,
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
$source = Join-Path $RepoRoot 'scripts\m21\M21SemanticScaleProbe.java'
$work = Join-Path ([System.IO.Path]::GetDirectoryName($output)) 'process'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$stdout = Join-Path $work 'semantic-scale.out.log'
$stderr = Join-Path $work 'semantic-scale.err.log'
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
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) { throw 'Unable to start java -version.' }
        $outTask = $process.StandardOutput.ReadToEndAsync()
        $errTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw 'java -version failed.' }
        $lines = @(($errTask.Result + "`n" + $outTask.Result) -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($lines.Count -eq 0) { throw 'java -version produced no output.' }
        return $lines[0].Trim()
    } finally {
        $process.Dispose()
    }
}

$arguments = @('--class-path', $jar, $source, $homePath, $BenchmarkProfile, [string]$Repetitions, $output)
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
if (-not $process.Start()) { throw 'Unable to start M21-S8 semantic scale probe.' }
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
    throw "M21-S8 semantic scale probe failed (exit=$exitCode).`n$stderrText`n$stdoutText"
}
if (-not (Test-Path -LiteralPath $output -PathType Leaf)) { throw "M21-S8 probe did not produce $output" }

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
}
$data | Add-Member -NotePropertyName machine -NotePropertyValue $machine -Force
$data | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $output -Encoding UTF8

Write-Host 'M21 S8 SEMANTIC SCALE BENCHMARK SUCCESS' -ForegroundColor Green
Get-Content -LiteralPath $stdout | ForEach-Object { Write-Host $_ }
Write-Host "process-rss=$peakRss elapsed=$([Math]::Round($watch.Elapsed.TotalMilliseconds,4))ms"
