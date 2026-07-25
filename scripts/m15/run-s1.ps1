[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s1-baseline'

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)

    & git @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)"
    }
}

function Ensure-WindowsPowerShellOnPath {
    if ($env:OS -ne 'Windows_NT') {
        return
    }

    # Some developer shells can start Windows PowerShell successfully while its
    # installation directory is absent from PATH. M15-S1 and the historical M14
    # replay intentionally spawn a fresh PowerShell process, so make the standard
    # Windows host discoverable before either script resolves powershell.exe.
    $windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    if (-not (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf)) {
        throw "Windows PowerShell executable not found at the standard path: $windowsPowerShell"
    }

    $powerShellDirectory = Split-Path -Parent $windowsPowerShell
    $alreadyPresent = @($env:Path -split ';') | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_) -and
        $_.TrimEnd('\').Equals($powerShellDirectory.TrimEnd('\'), [StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1

    if ($null -eq $alreadyPresent) {
        $env:Path = "$powerShellDirectory;$env:Path"
    }
}

Push-Location $RepoRoot
try {
    Ensure-WindowsPowerShellOnPath

    Write-Host '=== MINOS M15-S1 - update + exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree status.'
    }
    if ($dirty.Count -gt 0) {
        throw "M15-S1 runner requires a clean worktree. Dirty entries:`n$($dirty -join "`n")"
    }

    Write-Host '[1/5] Fetching M15-S1 branch...'
    Invoke-GitChecked -Arguments @('fetch', 'origin', $Branch)

    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve current branch.'
    }

    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        $localBranchExists = ($LASTEXITCODE -eq 0)

        Write-Host "[2/5] Switching from '$currentBranch' to '$Branch'..."
        if ($localBranchExists) {
            Invoke-GitChecked -Arguments @('switch', $Branch)
        }
        else {
            Invoke-GitChecked -Arguments @('switch', '-c', $Branch, '--track', "origin/$Branch")
        }
    }
    else {
        Write-Host "[2/5] Already on '$Branch'."
    }

    Write-Host '[3/5] Fast-forwarding to the latest remote head...'
    Invoke-GitChecked -Arguments @('pull', '--ff-only', 'origin', $Branch)

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
        throw 'Unable to resolve exact HEAD after update.'
    }

    # Re-apply after the pull so a future runner update cannot accidentally lose
    # the host-path guarantee before capture-baseline.ps1 starts its M14 child.
    Ensure-WindowsPowerShellOnPath

    Write-Host "[4/5] Validating exact HEAD $head..." -ForegroundColor Cyan

    $captureScript = Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1'
    $parameters = @{
        ExpectedHead = $head
    }
    if ($SkipM14Replay) {
        $parameters['SkipM14Replay'] = $true
    }
    if ($SkipProviderReplays) {
        $parameters['SkipProviderReplays'] = $true
    }
    if ($ValidateDocker) {
        $parameters['ValidateDocker'] = $true
    }

    & $captureScript @parameters

    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host '[5/5] Capturing repeated-query cost baseline...' -ForegroundColor Cyan
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    }
    else {
        Write-Host '[5/5] Repeated-query baseline skipped because the full M14/provider replay was disabled.' -ForegroundColor Yellow
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host "M15-S1 FULL VALIDATION SUCCESS for $head" -ForegroundColor Green
    }
    else {
        Write-Host "M15-S1 diagnostic validation finished for $head (not sufficient to close S1)" -ForegroundColor Yellow
    }
}
finally {
    Pop-Location
}
