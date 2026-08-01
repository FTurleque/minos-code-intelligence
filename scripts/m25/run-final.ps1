[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$M25Base = 'b17631de59871848351a4139b12be6e0354989bc'

if ($env:OS -ne 'Windows_NT') { throw 'M25 Windows final qualification must run on Windows.' }
if ($ExpectedHead -notmatch '^[0-9a-f]{40}$') { throw 'M25 requires an explicit lowercase 40-character -ExpectedHead SHA.' }

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M25 final qualification requires Python in PATH.'
}

function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) { throw 'Unable to resolve git HEAD.' }
    return $Value
}

function Assert-CleanWorktree([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) { throw "M25 requires a clean worktree during $Stage.`n$($Dirty -join "`n")" }
}

function Assert-NoWorkflowChanges {
    & git diff --quiet $M25Base HEAD -- .github/workflows
    if ($LASTEXITCODE -ne 0) { throw 'M25 forbids changes under .github/workflows.' }
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
    Write-Host '=== MINOS M25 - FINAL Remote & Distributed Indexing Windows exact-head qualification ===' -ForegroundColor Cyan
    Assert-CleanWorktree 'preflight'
    $Head = Get-Head
    if ($Head -ne $ExpectedHead) { throw "M25 exact-head mismatch: expected=$ExpectedHead actual=$Head" }
    Assert-NoWorkflowChanges
    $Python = Resolve-Python

    $Go = Get-Command 'go' -ErrorAction SilentlyContinue
    if (-not $Go) { throw 'M25 Windows e2e requires Go in the current process PATH.' }
    $GoVersion = (& $Go.Source version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw "M25 requires a usable Go toolchain; got: $GoVersion"
    }
    Write-Host "HEAD: $Head"
    Write-Host "Go: $GoVersion"

    Write-Host '[1/7] M25 static, documentation and M24 regression contracts...'
    Invoke-PythonGate $Python 'scripts\m25\check-remote-distributed.py' 'M25 consistency gate failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Current documentation consistency failed'
    Invoke-PythonGate $Python 'scripts\m24\check-polyglot.py' 'M24 regression gate failed'

    Write-Host '[2/7] Full Java 24 Maven reactor...'
    Invoke-SemanticDisabled {
        & .\mvnw.cmd clean verify
        if ($LASTEXITCODE -ne 0) { throw "M25 Maven reactor failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[3/7] JaCoCo including M25 scope...'
    Invoke-PythonGate $Python 'scripts\quality\check-jacoco.py' 'M25 JaCoCo gate failed'

    Write-Host '[4/7] Historical capability/provider regressions...'
    Invoke-PythonGate $Python 'scripts\m22\check-provider.py' 'M22 provider regression failed'
    Invoke-PythonGate $Python 'scripts\m23\check-semantic.py' 'M23 semantic regression failed'

    Write-Host '[5/7] Real GitHub exact-revision/cache/worker/artifact/snapshot e2e...'
    Invoke-SemanticDisabled {
        & $Python 'scripts\m25\run-remote-e2e.py' --expected-head $Head `
            --output 'target/m25/remote-e2e-windows.json'
        if ($LASTEXITCODE -ne 0) { throw "M25 Windows remote e2e failed (exit=$LASTEXITCODE)" }
    }

    Write-Host '[6/7] Static, docs and evidence recheck...'
    Invoke-PythonGate $Python 'scripts\m25\check-remote-distributed.py' 'M25 consistency recheck failed'
    Invoke-PythonGate $Python 'scripts\docs\check-current-docs.py' 'Documentation recheck failed'
    $Evidence = Get-Content 'target\m25\remote-e2e-windows.json' -Raw | ConvertFrom-Json
    if ($Evidence.status -ne 'PASS' -or $Evidence.commit -ne $Head -or $Evidence.provider.id -ne 'scip-go' `
            -or $Evidence.provider.version -ne '0.2.7' -or $Evidence.sourceCache.first -ne 'MISS' `
            -or $Evidence.sourceCache.second -ne 'HIT' -or $Evidence.sourceCache.index -ne 'HIT') {
        throw 'M25 Windows detailed e2e evidence is not an exact-head PASS.'
    }

    Write-Host '[7/7] Exact HEAD, workflow diff and clean-worktree final gate...'
    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) { throw "HEAD changed during M25 qualification: start=$Head end=$FinalHead" }
    Assert-NoWorkflowChanges
    Assert-CleanWorktree 'final gate'

    Write-Host 'M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
