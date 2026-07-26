[CmdletBinding()]
param(
    [ValidateRange(5,500)][int] $Repetitions = 30,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
$Branch = 'm16-scalability'

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure,
        [string] $LogPath = ''
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        if ([string]::IsNullOrWhiteSpace($LogPath)) {
            & $File @Arguments
        } else {
            & $File @Arguments 2>&1 | ForEach-Object { $_.ToString() } | Tee-Object -FilePath $LogPath
        }
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) { throw "$Failure (exit=$exit)" }
}

function Resolve-CurrentPowerShellHost {
    try {
        $path = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($path) -and (Test-Path -LiteralPath $path -PathType Leaf)) {
            return $path
        }
    } catch { }
    if ($env:OS -eq 'Windows_NT' -and -not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    }
    foreach ($name in @('pwsh.exe','powershell.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'Unable to resolve a PowerShell host.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    Write-Host "Runner changed after pull; restarting on exact HEAD $Head..." -ForegroundColor Yellow
    $args = @(
        '-NoProfile','-ExecutionPolicy','Bypass','-File',
        (Join-Path $RepoRoot 'scripts\m16\run-final.ps1'),
        '-Repetitions',[string]$Repetitions
    )
    if ($ValidateDocker) { $args += '-ValidateDocker' }
    & (Resolve-CurrentPowerShellHost) @args
    if ($LASTEXITCODE -ne 0) { throw "Restarted M16 runner failed (exit=$LASTEXITCODE)." }
}

function Resolve-Python {
    foreach ($name in @('python.exe','python')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return [pscustomobject]@{ File = $command.Source; Prefix = @() } }
    }
    $py = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($py) { return [pscustomobject]@{ File = $py.Source; Prefix = @('-3') } }
    throw 'M16 requires Python 3 for benchmark result processing.'
}

function Invoke-PythonChecked {
    param(
        [Parameter(Mandatory = $true)] $Python,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    Invoke-NativeChecked -File $Python.File -Arguments (@($Python.Prefix) + $Arguments) -Failure $Failure
}

function Get-ShadedJar {
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File `
        -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw 'Shaded MINOS JAR is missing.' }
    return $jar.FullName
}

function Assert-Structure {
    $required = @(
        'docs\roadmap\M16_EXECUTION.md',
        'docs\adr\0025-measurement-gated-storage-backend-evolution.md',
        'scripts\m16\datasets.json',
        'scripts\m16\M16ScaleBenchmark.java',
        'scripts\m16\M16McpSustainedBenchmark.java',
        'scripts\m16\M16RetentionProbe.java',
        'scripts\m16\run-scale-benchmark.ps1',
        'scripts\m16\run-indexing-benchmark.ps1',
        'scripts\m16\sqlite-backend-probe.py',
        'scripts\m16\evaluate-backend.py',
        'scripts\m16\check-results.py',
        'minos-storage-local\src\main\java\com\minos\store\SnapshotRetentionPolicy.java',
        'minos-storage-local\src\main\java\com\minos\store\SnapshotCompactionService.java',
        'minos-application\src\main\java\com\minos\orchestration\IndexRunRetentionPolicy.java',
        'minos-application\src\main\java\com\minos\orchestration\IndexRunRetentionService.java'
    )
    foreach ($relative in $required) {
        $path = Join-Path $RepoRoot $relative
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Required M16 file is missing: $relative"
        }
    }

    $poms = Get-ChildItem -LiteralPath $RepoRoot -Recurse -File -Filter 'pom.xml' |
        Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' }
    foreach ($pom in $poms) {
        $text = Get-Content -LiteralPath $pom.FullName -Raw
        if ($text -match 'sqlite-jdbc|org\.xerial|lucene|rocksdb') {
            throw "M16 must not add an unratified runtime backend dependency: $($pom.FullName)"
        }
    }

    $datasets = Get-Content -LiteralPath (Join-Path $RepoRoot 'scripts\m16\datasets.json') -Raw | ConvertFrom-Json
    if ($datasets.schemaVersion -ne 1 -or $datasets.seed -ne 16000031) {
        throw 'Unexpected M16 dataset manifest version/seed.'
    }
    if ($datasets.syntheticProfiles.STANDARD.logicalFiles -ne 10000 -or
        $datasets.syntheticProfiles.STANDARD.symbols -ne 100000 -or
        $datasets.syntheticProfiles.STANDARD.occurrences -ne 500000 -or
        $datasets.syntheticProfiles.STANDARD.relationships -ne 250000) {
        throw 'Unexpected M16 STANDARD dataset cardinalities.'
    }

    $mainSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object {
                Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') `
                    -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
            }
    )
    $testSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object {
                Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') `
                    -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
            }
    )
    if ($mainSources.Count -ne 208) {
        throw "Unexpected M16 production source count: expected=208 actual=$($mainSources.Count)"
    }
    if ($testSources.Count -ne 100) {
        throw "Unexpected M16 test source count: expected=100 actual=$($testSources.Count)"
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M16 - FINAL scalability exact-head qualification ===' -ForegroundColor Cyan
    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) {
        throw "M16 final runner requires a clean worktree.`n$($dirty -join "`n")"
    }
    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()

    Write-Host '[1/10] Fetching/finalizing M16 branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $current = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($current -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
    }
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($head -ne $initialHead) { Restart-UpdatedRunner $head; return }
    Write-Host "HEAD: $head"

    Write-Host '[2/10] Checking M16 structural/data/backend boundaries...'
    Assert-Structure
    $python = Resolve-Python
    Invoke-PythonChecked -Python $python `
        -Arguments @('scripts/docs/product-facts.py','--check') `
        -Failure 'Product facts consistency failed'
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host '[3/10] Running fast SMOKE compile/source-launcher preflight...'
    Invoke-NativeChecked -File '.\mvnw.cmd' -Arguments @('-DskipTests','package') `
        -Failure 'M16 preflight Maven package failed'
    $preflightJar = Get-ShadedJar
    $preflightRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m16-preflight-" + $head.Substring(0,12))
    Remove-Item -LiteralPath $preflightRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $preflightRoot | Out-Null
    $preflightScaleHome = Join-Path $preflightRoot 'scale-home'
    & (Join-Path $RepoRoot 'scripts\m16\run-scale-benchmark.ps1') `
        -JarPath $preflightJar -Home $preflightScaleHome -Profile SMOKE -Repetitions 5 `
        -OutputJson (Join-Path $preflightRoot 'scale.json')
    Invoke-NativeChecked -File $java.JavaExecutable -Arguments @(
        '--class-path',$preflightJar,
        (Join-Path $RepoRoot 'scripts\m16\M16McpSustainedBenchmark.java'),
        $preflightScaleHome,'5',(Join-Path $preflightRoot 'mcp.json')
    ) -Failure 'M16 MCP source-launcher preflight failed'

    Write-Host '[4/10] Replaying clean verify + complete M14/providers/Windows qualification...'
    $baselineParams = @{ ExpectedHead = $head }
    if ($ValidateDocker) { $baselineParams['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @baselineParams
    $baseline = Get-Content -LiteralPath (Join-Path $RepoRoot 'target\m15-baseline\baseline.json') -Raw | ConvertFrom-Json
    if ($baseline.verifyStatus -ne 'PASS' -or $baseline.m14ReplayStatus -ne 'PASS') {
        throw "M16 inherited qualification failed: verify=$($baseline.verifyStatus) m14=$($baseline.m14ReplayStatus)"
    }
    if ([long]$baseline.junit.tests -ne 260 -or
        [long]$baseline.junit.failures -ne 0 -or
        [long]$baseline.junit.errors -ne 0) {
        throw "Unexpected M16 JUnit summary: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([long]$baseline.mainSourceCount -ne 208 -or
        [long]$baseline.testSourceCount -ne 100 -or
        [long]$baseline.reactorModules -ne 13) {
        throw "Unexpected M16 source/reactor counts: main=$($baseline.mainSourceCount) test=$($baseline.testSourceCount) reactor=$($baseline.reactorModules)"
    }
    Invoke-PythonChecked -Python $python -Arguments @('scripts/quality/check-jacoco.py') `
        -Failure 'JaCoCo targeted gates failed'

    $validationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m14-" + $head.Substring(0,12))
    $validationHome = Join-Path $validationRoot 'home'
    $installedJar = Join-Path $validationRoot 'installed\lib\minos.jar'
    if (-not (Test-Path -LiteralPath $installedJar -PathType Leaf)) {
        throw "M14 installed JAR is missing: $installedJar"
    }
    if (-not (Test-Path -LiteralPath $validationHome -PathType Container)) {
        throw "M14 validation home is missing: $validationHome"
    }

    $resultRoot = Join-Path $RepoRoot 'target\m16'
    Remove-Item -LiteralPath $resultRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null
    $m16Temp = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-m16-" + $head.Substring(0,12))
    Remove-Item -LiteralPath $m16Temp -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $m16Temp | Out-Null
    $scaleHome = Join-Path $m16Temp 'scale-home'

    Write-Host '[5/10] Running STANDARD scale/query/memory/disk benchmark...'
    & (Join-Path $RepoRoot 'scripts\m16\run-scale-benchmark.ps1') `
        -JarPath $installedJar -Home $scaleHome -Profile STANDARD -Repetitions $Repetitions `
        -OutputJson (Join-Path $resultRoot 'scale.json')

    Write-Host '[6/10] Running sustained MCP benchmark on one long-lived server...'
    Invoke-NativeChecked -File $java.JavaExecutable -Arguments @(
        '--class-path',$installedJar,
        (Join-Path $RepoRoot 'scripts\m16\M16McpSustainedBenchmark.java'),
        $scaleHome,[string]$Repetitions,(Join-Path $resultRoot 'mcp.json')
    ) -Failure 'M16 MCP sustained benchmark failed' -LogPath (Join-Path $resultRoot 'mcp.log')

    Write-Host '[7/10] Running real-provider FULL/NONE indexing benchmark...'
    & (Join-Path $RepoRoot 'scripts\m16\run-indexing-benchmark.ps1') `
        -JarPath $installedJar -ValidationHome $validationHome `
        -OutputJson (Join-Path $resultRoot 'indexing.json')

    Write-Host '[8/10] Comparing experimental SQLite access paths and applying backend decision rule...'
    Invoke-PythonChecked -Python $python -Arguments @(
        'scripts/m16/sqlite-backend-probe.py',
        '--profile','STANDARD',
        '--repetitions',[string]$Repetitions,
        '--database',(Join-Path $resultRoot 'sqlite.db'),
        '--output',(Join-Path $resultRoot 'sqlite.json')
    ) -Failure 'M16 SQLite comparison failed'
    Invoke-PythonChecked -Python $python -Arguments @(
        'scripts/m16/evaluate-backend.py',
        '--current',(Join-Path $resultRoot 'scale.json'),
        '--sqlite',(Join-Path $resultRoot 'sqlite.json'),
        '--mcp',(Join-Path $resultRoot 'mcp.json'),
        '--output',(Join-Path $resultRoot 'backend-decision.json')
    ) -Failure 'M16 backend decision gate failed'

    Write-Host '[9/10] Verifying snapshot/run retention and final metric gates...'
    Invoke-NativeChecked -File $java.JavaExecutable -Arguments @(
        '--class-path',$installedJar,
        (Join-Path $RepoRoot 'scripts\m16\M16RetentionProbe.java'),
        (Join-Path $m16Temp 'retention'),
        (Join-Path $resultRoot 'retention.json')
    ) -Failure 'M16 retention probe failed' -LogPath (Join-Path $resultRoot 'retention.log')
    Invoke-PythonChecked -Python $python -Arguments @(
        'scripts/m16/check-results.py',
        '--scale',(Join-Path $resultRoot 'scale.json'),
        '--mcp',(Join-Path $resultRoot 'mcp.json'),
        '--indexing',(Join-Path $resultRoot 'indexing.json'),
        '--retention',(Join-Path $resultRoot 'retention.json'),
        '--backend',(Join-Path $resultRoot 'backend-decision.json'),
        '--output-json',(Join-Path $resultRoot 'summary.json'),
        '--output-md',(Join-Path $resultRoot 'summary.md')
    ) -Failure 'M16 final benchmark result gates failed'

    Write-Host '[10/10] Verifying exact HEAD did not move...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) {
        throw "HEAD moved during M16 qualification: start=$head final=$finalHead"
    }
    $finalDirty = @(& git status --porcelain)
    if ($finalDirty.Count -gt 0) {
        throw "M16 qualification dirtied the worktree.`n$($finalDirty -join "`n")"
    }

    $summary = Get-Content -LiteralPath (Join-Path $resultRoot 'summary.json') -Raw | ConvertFrom-Json
    Write-Host ''
    Write-Host 'M16 FINAL SCALABILITY VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "HEAD=$head profile=$($summary.profile) tests=$($baseline.junit.tests)"
    Write-Host "heap=$($summary.memory.peak_heap_bytes) rss=$($summary.memory.process_rss_bytes) disk=$($summary.disk.snapshot_disk_size_bytes)"
    Write-Host "loads=$($summary.cache.full_loads) builds=$($summary.cache.query_view_builds) hits=$($summary.cache.cache_hits) indexRefs=$($summary.cache.index_references)"
    Write-Host "backend=$($summary.backend.decision)"
    Write-Host "artifacts=$resultRoot"
}
finally {
    Pop-Location
}
