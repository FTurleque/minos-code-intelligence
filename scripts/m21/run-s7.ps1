[CmdletBinding()]
param(
    [string] $ExpectedHead = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M21-S7 requires Python in PATH.'
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M21-S7 - Advanced provider productionization qualification ===' -ForegroundColor Cyan

    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($Dirty.Count -gt 0) { throw "M21-S7 requires a clean worktree.`n$($Dirty -join "`n")" }

    $Head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "M21-S7 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Write-Host "HEAD: $Head"

    Write-Host '[1/5] Replaying M21 local/core qualification...'
    & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M21 local qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/5] Checking advanced provider contract and versioned fixture...'
    $Python = Resolve-Python
    & $Python 'scripts/m21/check-s7-provider.py'
    if ($LASTEXITCODE -ne 0) { throw "M21 advanced provider consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[3/5] Replaying focused sidecar provider ground-truth tests...'
    & '.\mvnw.cmd' '-pl' 'minos-application' '-am' `
        '-Dtest=FileProgramGraphProviderTest,AdvancedProgramSidecarFixtureTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' 'test'
    if ($LASTEXITCODE -ne 0) { throw "M21 advanced provider focused tests failed (exit=$LASTEXITCODE)" }

    Write-Host '[4/5] Rechecking current documentation consistency...'
    & $Python 'scripts/docs/check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw "M21 current documentation consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[5/5] Rechecking exact HEAD and clean tracked worktree...'
    $FinalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($FinalHead -ne $Head) { throw "HEAD changed during M21-S7 qualification: start=$Head end=$FinalHead" }
    $FinalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($FinalDirty.Count -gt 0) { throw "Worktree changed during M21-S7 qualification.`n$($FinalDirty -join "`n")" }

    Write-Host 'M21-S7 ADVANCED PROVIDER VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
