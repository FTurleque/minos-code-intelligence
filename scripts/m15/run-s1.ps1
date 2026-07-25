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

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M15-S1 — update + exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to inspect Git worktree status.'
    }
    if ($dirty.Count -gt 0) {
        throw "M15-S1 runner requires a clean worktree. Dirty entries:`n$($dirty -join "`n")"
    }

    Write-Host '[1/4] Fetching M15-S1 branch...'
    Invoke-GitChecked -Arguments @('fetch', 'origin', $Branch)

    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to resolve current branch.'
    }

    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        $localBranchExists = ($LASTEXITCODE -eq 0)

        Write-Host "[2/4] Switching from '$currentBranch' to '$Branch'..."
        if ($localBranchExists) {
            Invoke-GitChecked -Arguments @('switch', $Branch)
        }
        else {
            Invoke-GitChecked -Arguments @('switch', '-c', $Branch, '--track', "origin/$Branch")
        }
    }
    else {
        Write-Host "[2/4] Already on '$Branch'."
    }

    Write-Host '[3/4] Fast-forwarding to the latest remote head...'
    Invoke-GitChecked -Arguments @('pull', '--ff-only', 'origin', $Branch)

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) {
        throw 'Unable to resolve exact HEAD after update.'
    }

    Write-Host "[4/4] Validating exact HEAD $head..." -ForegroundColor Cyan

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

    Write-Host ''
    Write-Host "M15-S1 validation finished for $head" -ForegroundColor Green
}
finally {
    Pop-Location
}
