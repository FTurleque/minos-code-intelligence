[CmdletBinding()]
param(
    [string] $ExpectedHead = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) { throw "$Failure (exit=$exit)" }
}

function Resolve-Python {
    foreach ($name in @('python.exe','python','python3.exe','python3')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'M21 requires Python in PATH for documentation consistency verification.'
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M21 - Production Integrity local consolidation gate ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) {
        throw "M21 local runner requires a clean worktree.`n$($dirty -join "`n")"
    }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M21 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/4] Checking M21 current documentation consistency...'
    $python = Resolve-Python
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/check-current-docs.py') `
        -Failure 'M21 current documentation consistency failed'

    Write-Host '[2/4] Replaying the current authoritative M20 core qualification on the M21 head...'
    & (Join-Path $RepoRoot 'scripts\m20\run-final.ps1') -ExpectedHead $head
    if ($LASTEXITCODE -ne 0) { throw "M20 regression qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[3/4] Rechecking M21 documentation after generated/build gates...'
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/check-current-docs.py') `
        -Failure 'M21 documentation consistency changed during qualification'

    Write-Host '[4/4] Rechecking exact HEAD and clean worktree...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD changed during M21 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    if ($finalDirty.Count -gt 0) {
        throw "Worktree changed during M21 qualification.`n$($finalDirty -join "`n")"
    }

    Write-Host 'M21 LOCAL CONSOLIDATION VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
} finally {
    Pop-Location
}
