[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [ValidateRange(5,50)][int] $Repetitions = 5
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-Python {
    foreach ($name in @('python.exe','python','python3.exe','python3')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return [pscustomobject]@{ File = $command.Source; Prefix = @() } }
    }
    $py = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($py) { return [pscustomobject]@{ File = $py.Source; Prefix = @('-3') } }
    throw 'M21-S8 requires Python 3 in PATH.'
}

function Invoke-PythonChecked {
    param(
        [Parameter(Mandatory = $true)] $Python,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $all = @($Python.Prefix) + $Arguments
    & $Python.File @all
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Get-ShadedJar {
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File `
        -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($null -eq $jar) { throw 'Shaded MINOS JAR is missing after M21 local qualification.' }
    return $jar.FullName
}

function Assert-NoUnratifiedSemanticBackend {
    $poms = Get-ChildItem -LiteralPath $RepoRoot -Recurse -File -Filter 'pom.xml' |
        Where-Object { $_.FullName -notmatch '[\\/]target[\\/]' }
    foreach ($pom in $poms) {
        $text = Get-Content -LiteralPath $pom.FullName -Raw
        if ($text -match 'lucene|hnsw|rocksdb|sqlite-jdbc|org\.xerial|qdrant|milvus|weaviate') {
            throw "M21-S8 must measure before adding an unratified semantic/vector backend dependency: $($pom.FullName)"
        }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M21-S8 - Semantic scale qualification ===' -ForegroundColor Cyan
    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M21-S8 requires a clean worktree.`n$($dirty -join "`n")" }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M21-S8 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/6] Replaying M21 local/core qualification...'
    & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $head
    if ($LASTEXITCODE -ne 0) { throw "M21 local qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/6] Enforcing measurement-before-migration boundary...'
    Assert-NoUnratifiedSemanticBackend
    $semanticSearch = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-application\src\main\java\com\minos\semantic\SemanticSearchService.java') -Raw
    if ($semanticSearch -notmatch 'VECTOR_SEARCH_LINEAR_SCAN') {
        throw 'M21-S8 baseline must explicitly identify the current linear vector scan.'
    }

    Write-Host '[3/6] Running M16-derived STANDARD semantic/hybrid benchmark...'
    $outDir = Join-Path $RepoRoot 'target\m21-s8'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $result = Join-Path $outDir 'standard.json'
    $benchmarkHome = Join-Path $outDir 'home'
    $jar = Get-ShadedJar
    & (Join-Path $RepoRoot 'scripts\m21\run-s8-benchmark.ps1') `
        -JarPath $jar -BenchmarkHome $benchmarkHome -BenchmarkProfile STANDARD -Repetitions $Repetitions -OutputJson $result
    if ($LASTEXITCODE -ne 0) { throw "M21-S8 STANDARD benchmark failed (exit=$LASTEXITCODE)" }

    Write-Host '[4/6] Applying semantic scale decision gate...'
    $python = Resolve-Python
    Invoke-PythonChecked -Python $python `
        -Arguments @('scripts/m21/check-s8-results.py', $result, '--expected-head', $head, '--output', 'target/m21-s8/decision.json') `
        -Failure 'M21-S8 measured thresholds require a targeted optimization before S8 can close'

    Write-Host '[5/6] Rechecking current documentation consistency...'
    Invoke-PythonChecked -Python $python -Arguments @('scripts/docs/check-current-docs.py') `
        -Failure 'M21 current documentation consistency failed'

    Write-Host '[6/6] Rechecking exact HEAD and clean tracked worktree...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD changed during M21-S8 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($finalDirty.Count -gt 0) { throw "Worktree changed during M21-S8 qualification.`n$($finalDirty -join "`n")" }

    Write-Host 'M21-S8 SEMANTIC SCALE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
}
finally {
    Pop-Location
}
