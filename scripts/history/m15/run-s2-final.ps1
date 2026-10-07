[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Push-Location $RepoRoot
try {
    & (Join-Path $RepoRoot 'scripts\m15\relocate-tests.ps1')

    $parameters = @{}
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }

    & (Join-Path $RepoRoot 'scripts\m15\run-s2.ps1') @parameters
}
finally {
    Pop-Location
}
