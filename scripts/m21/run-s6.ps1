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
    throw 'M21-S6 requires Python in PATH.'
}

function Invoke-ScriptChecked {
    param(
        [Parameter(Mandatory = $true)][string] $Script,
        [Parameter(Mandatory = $true)][string] $Failure,
        [object[]] $Arguments = @()
    )
    & $Script @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M21-S6 - IntelliJ M19/M20 parity qualification ===' -ForegroundColor Cyan

    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($Dirty.Count -gt 0) { throw "M21-S6 requires a clean worktree.`n$($Dirty -join "`n")" }

    $Head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($Head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "M21-S6 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Write-Host "HEAD: $Head"

    Write-Host '[1/6] Replaying M21 local/core qualification...'
    Invoke-ScriptChecked `
        -Script (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') `
        -Arguments @('-ExpectedHead', $Head) `
        -Failure 'M21 local qualification failed'

    Write-Host '[2/6] Checking static IntelliJ M19/M20 parity contract...'
    $Python = Resolve-Python
    & $Python 'scripts/intellij/check-m21-parity.py'
    if ($LASTEXITCODE -ne 0) { throw "M21 IntelliJ parity consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[3/6] Replaying authoritative M18 IntelliJ build, tests and Plugin Verifier on the S6 head...'
    Invoke-ScriptChecked `
        -Script (Join-Path $RepoRoot 'scripts\m18\run-final.ps1') `
        -Arguments @('-ExpectedHead', $Head) `
        -Failure 'M18 IntelliJ qualification replay failed'

    Write-Host '[4/6] Rechecking parity contract after plugin qualification...'
    & $Python 'scripts/intellij/check-m21-parity.py'
    if ($LASTEXITCODE -ne 0) { throw "M21 IntelliJ parity consistency recheck failed (exit=$LASTEXITCODE)" }

    Write-Host '[5/6] Rechecking current documentation consistency...'
    & $Python 'scripts/docs/check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw "M21 current documentation consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[6/6] Rechecking exact HEAD and clean tracked worktree...'
    $FinalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($FinalHead -ne $Head) { throw "HEAD changed during M21-S6 qualification: start=$Head end=$FinalHead" }
    $FinalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    $TrackedDirty = @($FinalDirty | Where-Object { $_ -notmatch '^\?\? minos-intellij[/\\](build|\.intellijPlatform)[/\\]' })
    if ($TrackedDirty.Count -gt 0) { throw "Tracked worktree changed during M21-S6 qualification.`n$($TrackedDirty -join "`n")" }

    Write-Host 'M21-S6 INTELLIJ PARITY VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
