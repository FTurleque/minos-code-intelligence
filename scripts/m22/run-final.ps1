[CmdletBinding()]
param(
    [string] $ExpectedHead = '',
    [string] $Version = '0.2.0-m22'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))

if ($env:OS -ne 'Windows_NT') {
    throw 'M22 final qualification must run on Windows.'
}

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'M22 final qualification requires Python in PATH.'
}

function Get-Head {
    $Value = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Value)) {
        throw 'Unable to resolve git HEAD.'
    }
    return $Value
}

function Assert-CleanWorktree([string] $Stage) {
    $Dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect worktree during $Stage." }
    if ($Dirty.Count -gt 0) {
        throw "M22 requires a clean worktree during $Stage.`n$($Dirty -join "`n")"
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M22 - FINAL Advanced Provider Intelligence exact-head qualification ===' -ForegroundColor Cyan

    Assert-CleanWorktree 'preflight'
    $Head = Get-Head
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $Head -ne $ExpectedHead) {
        throw "M22 exact-head mismatch: expected=$ExpectedHead actual=$Head"
    }
    Write-Host "HEAD: $Head"
    Write-Host "Release candidate version: $Version"

    $Python = Resolve-Python

    Write-Host '[1/7] Checking M22 Java provider contract and controlled ground truth...'
    & $Python 'scripts\m22\check-provider.py'
    if ($LASTEXITCODE -ne 0) { throw "M22 provider consistency gate failed (exit=$LASTEXITCODE)" }

    Write-Host '[2/7] Replaying authoritative local core, module boundaries, full Maven tests and JaCoCo...'
    & (Join-Path $RepoRoot 'scripts\m21\run-local.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M21/M20 local qualification failed on the M22 head (exit=$LASTEXITCODE)" }

    Write-Host '[3/7] Replaying supply-chain and Windows release qualification for the M22 candidate...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s5.ps1') -ExpectedHead $Head -Version $Version
    if ($LASTEXITCODE -ne 0) { throw "M22 Windows release qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[4/7] Proving the packaged Windows runtime contains jdk.compiler for the Java provider...'
    $Distribution = Join-Path $RepoRoot "target\dist\minos-$Version-windows-x64"
    $RuntimeJava = Join-Path $Distribution 'app\runtime\bin\java.exe'
    if (-not (Test-Path -LiteralPath $RuntimeJava -PathType Leaf)) {
        throw "Packaged runtime java.exe not found: $RuntimeJava"
    }
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Modules = ((& $RuntimeJava --list-modules 2>&1) | Out-String)
        $ModulesExit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $PreviousErrorActionPreference
    }
    if ($ModulesExit -ne 0) {
        throw "Packaged runtime --list-modules failed (exit=$ModulesExit): $Modules"
    }
    if ($Modules -notmatch '(?m)^jdk\.compiler(?:@|$)') {
        throw 'Packaged MINOS runtime does not contain jdk.compiler; Java Advanced Provider would be unavailable in production.'
    }
    Write-Host 'M22 PACKAGED JDK.COMPILER RUNTIME SUCCESS' -ForegroundColor Green

    Write-Host '[5/7] Replaying IntelliJ external-client parity, tests, build and Plugin Verifier...'
    & (Join-Path $RepoRoot 'scripts\m21\run-s6.ps1') -ExpectedHead $Head
    if ($LASTEXITCODE -ne 0) { throw "M22 IntelliJ parity qualification failed (exit=$LASTEXITCODE)" }

    Write-Host '[6/7] Rechecking M22 and current documentation consistency after all generated/build gates...'
    & $Python 'scripts\m22\check-provider.py'
    if ($LASTEXITCODE -ne 0) { throw "M22 provider consistency recheck failed (exit=$LASTEXITCODE)" }
    & $Python 'scripts\docs\check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw "Current documentation consistency failed (exit=$LASTEXITCODE)" }

    Write-Host '[7/7] Rechecking exact HEAD and clean worktree...'
    $FinalHead = Get-Head
    if ($FinalHead -ne $Head) {
        throw "HEAD changed during M22 final qualification: start=$Head end=$FinalHead"
    }
    Assert-CleanWorktree 'final gate'

    Write-Host 'M22 FINAL ADVANCED PROVIDER INTELLIGENCE VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $Head"
}
finally {
    Pop-Location
}
