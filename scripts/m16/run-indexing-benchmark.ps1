[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $JarPath,
    [Parameter(Mandatory = $true)][string] $ValidationHome,
    [Parameter(Mandatory = $true)][string] $OutputJson
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
$java = Resolve-MinosJava24
$jar = [System.IO.Path]::GetFullPath($JarPath)
$validationHomePath = [System.IO.Path]::GetFullPath($ValidationHome)
$output = [System.IO.Path]::GetFullPath($OutputJson)
$fixtureSource = Join-Path $RepoRoot 'fixtures\java\java-simple'
$workRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'minos-m16-indexing'
$fixture = Join-Path $workRoot 'java-simple'

function Quote-Arg([string] $Value) {
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function Invoke-MinosMeasured {
    param([string[]] $Arguments)
    $stdout = Join-Path $workRoot ([Guid]::NewGuid().ToString() + '.out')
    $stderr = Join-Path $workRoot ([Guid]::NewGuid().ToString() + '.err')
    $argList = @('-jar', $jar) + $Arguments
    $encoded = ($argList | ForEach-Object { Quote-Arg $_ }) -join ' '
    $previous = $env:MINOS_HOME
    try {
        $env:MINOS_HOME = $validationHomePath
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $process = Start-Process -FilePath $java.JavaExecutable -ArgumentList $encoded -PassThru -NoNewWindow `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        [long] $peak = 0
        while (-not $process.HasExited) {
            try {
                $sample = Get-Process -Id $process.Id -ErrorAction Stop
                if ($sample.WorkingSet64 -gt $peak) { $peak = $sample.WorkingSet64 }
            } catch { }
            Start-Sleep -Milliseconds 100
            $process.Refresh()
        }
        $watch.Stop()
        $process.WaitForExit()
        $outText = if (Test-Path $stdout) { (Get-Content -LiteralPath $stdout -Raw).Trim() } else { '' }
        $errText = if (Test-Path $stderr) { (Get-Content -LiteralPath $stderr -Raw).Trim() } else { '' }
        if ($process.ExitCode -ne 0) {
            throw "Measured MINOS command failed: $($Arguments -join ' ')`n$errText`n$outText"
        }
        return [pscustomobject]@{
            DurationMs = [Math]::Round($watch.Elapsed.TotalMilliseconds, 4)
            PeakRssBytes = $peak
            Stdout = $outText
            Stderr = $errText
        }
    } finally {
        if ($null -eq $previous) { Remove-Item Env:MINOS_HOME -ErrorAction SilentlyContinue }
        else { $env:MINOS_HOME = $previous }
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
}

function Invoke-Minos {
    param([string[]] $Arguments)
    # Keep stdout protocol-clean. JVM/SLF4J warnings remain isolated on stderr so JSON callers
    # can safely pipe the returned text to ConvertFrom-Json.
    return (Invoke-MinosMeasured $Arguments).Stdout
}

Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
Copy-Item -LiteralPath $fixtureSource -Destination $fixture -Recurse -Force
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($output)) | Out-Null

# M14 qualification installs scip-java in ValidationHome. Verify rather than silently reinstalling.
$providers = Invoke-Minos @('tools','list','--format','json') | ConvertFrom-Json
$javaProvider = @($providers.providers | Where-Object { $_.id -eq 'scip-java' }) | Select-Object -First 1
if ($null -eq $javaProvider -or $javaProvider.state -ne 'READY') {
    throw 'M16 indexing benchmark requires the scip-java provider prepared by the M14 replay.'
}

$projectName = 'm16-index-java'
$null = Invoke-Minos @('project','add',$fixture,'--name',$projectName,'--format','json')

$full = Invoke-MinosMeasured @('index',$projectName,'--provider','scip-java','--force-full','--format','json')
$fullJson = $full.Stdout | ConvertFrom-Json
if ($fullJson.plan.mode -ne 'FULL' -or $fullJson.status -ne 'SUCCEEDED') {
    throw "Expected FULL/SUCCEEDED initial index, got mode=$($fullJson.plan.mode) status=$($fullJson.status)"
}

$none = Invoke-MinosMeasured @('index',$projectName,'--provider','scip-java','--format','json')
$noneJson = $none.Stdout | ConvertFrom-Json
if ($noneJson.plan.mode -ne 'NONE' -or $noneJson.status -ne 'NO_CHANGES') {
    throw "Expected NONE/NO_CHANGES unchanged index, got mode=$($noneJson.plan.mode) status=$($noneJson.status)"
}

$sourceFile = Get-ChildItem -LiteralPath $fixture -Recurse -File -Filter '*.java' | Select-Object -First 1
Add-Content -LiteralPath $sourceFile.FullName -Value '// M16 benchmark deterministic source change'
$changedPlanText = Invoke-Minos @('index',$projectName,'--provider','scip-java','--dry-run','--format','json')
$changedPlan = $changedPlanText | ConvertFrom-Json

$files = @(Get-ChildItem -LiteralPath $fixture -Recurse -File | Where-Object { $_.Name -notin @('desktop.ini') })
$sourceFiles = @($files | Where-Object { $_.Extension -eq '.java' })
[long] $loc = 0
foreach ($file in $sourceFiles) { $loc += @(Get-Content -LiteralPath $file.FullName).Count }
$seconds = [Math]::Max($full.DurationMs / 1000.0, 0.001)

$result = [ordered]@{
    provider = 'scip-java'
    provider_version = $javaProvider.version
    project = $projectName
    file_count = $files.Count
    source_file_count = $sourceFiles.Count
    loc = $loc
    full_index_duration_ms = $full.DurationMs
    none_index_duration_ms = $none.DurationMs
    full_peak_rss_bytes = $full.PeakRssBytes
    none_peak_rss_bytes = $none.PeakRssBytes
    files_per_second = [Math]::Round($files.Count / $seconds, 4)
    loc_per_second = [Math]::Round($loc / $seconds, 4)
    full_mode = $fullJson.plan.mode
    none_mode = $noneJson.plan.mode
    changed_workspace_plan_mode = $changedPlan.mode
    incremental_capability_qualified = $false
    incremental_measurement = 'NOT_APPLICABLE_PROVIDER_CAPABILITY_ABSENT'
}
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $output -Encoding UTF8

Write-Host 'M16 INDEXING BENCHMARK SUCCESS' -ForegroundColor Green
Write-Host "FULL=$($full.DurationMs)ms NONE=$($none.DurationMs)ms files/s=$($result.files_per_second) LOC/s=$($result.loc_per_second) peakRSS=$($full.PeakRssBytes) changedPlan=$($changedPlan.mode)"
