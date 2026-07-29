[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$M26Base = 'e37cf39fcf4f7e417c618fa0b16590100c1e0b91'

if ($env:OS -ne 'Windows_NT') { throw 'M26 Windows final qualification must run on Windows.' }
if ($ExpectedHead -notmatch '^[0-9a-f]{40}$') { throw 'M26 requires an explicit lowercase 40-character -ExpectedHead SHA.' }

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M26 final qualification requires Python in PATH.'
}

function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) { throw 'Unable to resolve git HEAD.' }
    return $Value
}

function Assert-CleanWorktree([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) { throw "M26 requires a clean worktree during $Stage.`n$($Dirty -join "`n")" }
}

function Assert-NoWorkflowChanges {
    & git diff --quiet $M26Base HEAD -- .github/workflows
    if ($LASTEXITCODE -ne 0) { throw 'M26 forbids changes under .github/workflows.' }
}

function Invoke-PythonGate([string] $Python, [string] $Script, [string] $Failure) {
    & $Python $Script
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Invoke-SemanticDisabled([Parameter(Mandatory = $true)][scriptblock] $Action) {
    $Names = @('MINOS_SEMANTIC_PROVIDER', 'MINOS_SEMANTIC_MODEL', 'MINOS_SEMANTIC_DIMENSIONS',
               'MINOS_SEMANTIC_ENDPOINT', 'MINOS_SEMANTIC_TIMEOUT_SECONDS')
    $Saved = @{}
    foreach ($Name in $Names) {
        $Path = "Env:$Name"
        if (Test-Path $Path) { $Saved[$Name] = (Get-Item $Path).Value }
        Remove-Item $Path -ErrorAction SilentlyContinue
    }
    try {
        $env:MINOS_SEMANTIC_PROVIDER = 'disabled'
        & $Action
    }
    finally {
        foreach ($Name in $Names) {
            Remove-Item "Env:$Name" -ErrorAction SilentlyContinue
            if ($Saved.ContainsKey($Name)) { Set-Item "Env:$Name" $Saved[$Name] }
        }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M26 - FINAL Runtime & Dynamic Intelligence Windows exact-head qualification ===' -ForegroundColor Cyan
    Assert-CleanWorktree 'preflight'
    $Head = Get-Head
    if ($Head -ne $ExpectedHead) { throw "M26 exact-head mismatch: expected=$ExpectedHead actual=$Head" }
    Assert-NoWorkflowChanges
    $Python = Resolve-Python
    foreach ($CommandName in @('java', 'javac')) {
        if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) { throw "M26 requires $CommandName in PATH." }
    }
    $JavaVersion = (& java -version 2>&1 | Select-Object -First 1 | Out-String).Trim()
    if ($JavaVersion -notmatch '"24(?:\.|"|$)') { throw "M26 requires Java 24; got: $JavaVersion" }
    Write-Host "HEAD: $Head"
    Write-Host "Java: $JavaVersion"

    Write-Host '[1/7] M26 static, documentation and prior-milestone contracts...'
    Invoke-PythonGate $Python 'scripts\m26\check-runtime-dynamic.py' 'M26 consistency gate failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Current documentation consistency failed'
    Invoke-PythonGate $Python 'scripts\m25\check-remote-distributed.py' 'M25 regression gate failed'
    Invoke-PythonGate $Python 'scripts\m24\check-polyglot.py' 'M24 regression gate failed'

    Write-Host '[2/7] Full Java 24 Maven reactor...'
    Invoke-SemanticDisabled {
        & .\mvnw.cmd clean verify
        if ($LASTEXITCODE -ne 0) { throw "M26 Maven reactor failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[3/7] JaCoCo including M26 scope...'
    Invoke-PythonGate $Python 'scripts\quality\check-jacoco.py' 'M26 JaCoCo gate failed'

    Write-Host '[4/7] Historical provider and semantic regression contracts...'
    Invoke-PythonGate $Python 'scripts\m22\check-provider.py' 'M22 provider regression failed'
    Invoke-PythonGate $Python 'scripts\m23\check-semantic.py' 'M23 semantic regression failed'

    Write-Host '[5/7] Shaded CLI runtime import/correlation/storage/report e2e...'
    Invoke-SemanticDisabled {
        & $Python 'scripts\m26\run-runtime-e2e.py' --expected-head $Head `
            --output 'target/m26/runtime-e2e-windows.json'
        if ($LASTEXITCODE -ne 0) { throw "M26 Windows runtime e2e failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[6/7] Static, docs and detailed evidence recheck...'
    Invoke-PythonGate $Python 'scripts\m26\check-runtime-dynamic.py' 'M26 consistency recheck failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Documentation recheck failed'
    $Evidence = Get-Content 'target\m26\runtime-e2e-windows.json' -Raw | ConvertFrom-Json
    if ($Evidence.status -ne 'PASS' -or $Evidence.commit -ne $Head `
            -or $Evidence.format -ne 'minos-runtime-observation-v1' `
            -or $Evidence.nature -ne 'OBSERVED_PARTIAL' -or $Evidence.exhaustive -ne $false `
            -or $Evidence.session.completeness -ne 'PARTIAL' -or $Evidence.session.activeSnapshotAligned -ne $true `
            -or $Evidence.correlation.resolved -ne 4 -or $Evidence.correlation.ambiguous -ne 1 `
            -or $Evidence.correlation.unresolved -ne 1 -or $Evidence.staticSnapshot.id -ne 'snapshot-m26-e2e' `
            -or $Evidence.failClosed.completeRejected -ne $true `
            -or $Evidence.failClosed.sessionMutationRejected -ne $true) {
        throw 'M26 Windows detailed e2e evidence is not an exact-head PASS.'
    }

    Write-Host '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) { throw "HEAD changed during M26 qualification: start=$Head end=$FinalHead" }
    Assert-NoWorkflowChanges
    Assert-CleanWorktree 'final gate'

    Write-Host 'M26 FINAL RUNTIME DYNAMIC INTELLIGENCE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
