[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [ValidateRange(5,50)][int] $SemanticRepetitions = 5,
    [ValidateRange(5,120)][int] $BenchmarkTimeoutMinutes = 30,
    [string] $Version = '0.2.0-m21s9'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    $Py = Get-Command 'py.exe' -ErrorAction SilentlyContinue
    if ($Py) { return $Py.Source }
    throw 'M21-S9 requires Python 3 in PATH.'
}

function Assert-ExactHeadAndClean {
    param(
        [Parameter(Mandatory = $true)][string] $Head,
        [switch] $AllowIntellijBuildOutputs
    )

    $Actual = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Actual)) {
        throw 'Unable to resolve current HEAD.'
    }
    if ($Actual -ne $Head) {
        throw "M21-S9 exact-head mismatch: expected=$Head actual=$Actual"
    }

    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($AllowIntellijBuildOutputs) {
        $Dirty = @($Dirty | Where-Object { $_ -notmatch '^\?\? minos-intellij[/\\](build|\.intellijPlatform)[/\\]' })
    }
    if ($Dirty.Count -gt 0) {
        throw "M21-S9 requires a clean tracked worktree.`n$($Dirty -join "`n")"
    }
}

Push-Location $RepoRoot
try {
    if ($env:OS -ne 'Windows_NT') {
        throw 'M21-S9 final production integrity qualification must run on Windows.'
    }

    Write-Host '=== MINOS M21-S9 - FINAL Production Integrity exact-head qualification ===' -ForegroundColor Cyan

    $Head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "M21-S9 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Assert-ExactHeadAndClean -Head $Head
    Write-Host "HEAD: $Head"
    Write-Host "Release candidate version: $Version"

    Write-Host '[1/7] Replaying advanced provider productionization gate...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s7.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M21-S7 replay failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/7] Replaying STANDARD semantic scale qualification on the same HEAD...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s8.ps1') `
        -ExpectedHead $Head `
        -Repetitions $SemanticRepetitions `
        -BenchmarkTimeoutMinutes $BenchmarkTimeoutMinutes
    if ($LASTEXITCODE -ne 0) { throw "M21-S8 replay failed (exit=$LASTEXITCODE)" }

    $DecisionPath = Join-Path $RepoRoot 'target\m21-s8\decision.json'
    if (-not (Test-Path -LiteralPath $DecisionPath -PathType Leaf)) {
        throw 'M21-S9 requires the S8 decision evidence produced on this exact HEAD.'
    }
    $S8Decision = Get-Content -LiteralPath $DecisionPath -Raw | ConvertFrom-Json
    if ($S8Decision.head -ne $Head) {
        throw "M21-S9 S8 evidence head mismatch: expected=$Head actual=$($S8Decision.head)"
    }
    if ($S8Decision.status -ne 'PASS' -or $S8Decision.decision -ne 'KEEP_CURRENT_M20_BACKEND') {
        throw "M21-S9 requires PASS/KEEP_CURRENT_M20_BACKEND, actual=$($S8Decision.status)/$($S8Decision.decision)"
    }
    Write-Host 'M21-S9 captured S8 evidence: PASS / KEEP_CURRENT_M20_BACKEND'

    Write-Host '[3/7] Replaying supply-chain and Windows release qualification...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s5.ps1') -ExpectedHead $Head -Version $Version
    if ($LASTEXITCODE -ne 0) { throw "M21-S5 replay failed (exit=$LASTEXITCODE)" }

    Write-Host '[4/7] Replaying IntelliJ parity, build, tests and Plugin Verifier...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s6.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M21-S6 replay failed (exit=$LASTEXITCODE)" }

    Write-Host '[5/7] Rechecking current documentation consistency...'
    $Python = Resolve-Python
    if ([System.IO.Path]::GetFileName($Python).ToLowerInvariant() -eq 'py.exe') {
        & $Python '-3' 'scripts/docs/check-current-docs.py'
    } else {
        & $Python 'scripts/docs/check-current-docs.py'
    }
    if ($LASTEXITCODE -ne 0) { throw "M21 current documentation consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[6/7] Verifying retained S8 decision captured before downstream clean builds...'
    if ($S8Decision.head -ne $Head -or $S8Decision.status -ne 'PASS' -or $S8Decision.decision -ne 'KEEP_CURRENT_M20_BACKEND') {
        throw 'M21-S9 retained S8 evidence changed unexpectedly in memory.'
    }
    Write-Host "M21-S9 retained S8 decision: $($S8Decision.status) / $($S8Decision.decision) / head=$($S8Decision.head)"

    Write-Host '[7/7] Rechecking exact HEAD and clean tracked worktree...'
    Assert-ExactHeadAndClean -Head $Head -AllowIntellijBuildOutputs

    Write-Host 'M21 FINAL PRODUCTION INTEGRITY VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
