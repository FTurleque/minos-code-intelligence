[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows program payload removal currently targets Windows hosts.'
}

# Runs from a {tmp} extraction (see PrepareToInstall/CurUninstallStepChanged in
# minos-installer.iss.template), never from inside {app}\integration itself,
# so it can safely remove the integration\ directory it would otherwise live
# in without racing its own open file handle.
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$NormalizedRoot = $InstallRoot.TrimEnd('\')

# Mirrors the managed relative paths in update-installation.ps1: only program
# payload and the transactional engine's own working state are removed here.
# Persistent user data/config live entirely outside $InstallRoot; the single
# exception (.docker-mcp-managed) is handled separately by [UninstallDelete].
$ManagedRelativePaths = @(
    'app', 'lib', 'docker', 'integration', 'supply-chain',
    'minos.cmd', 'minos-mcp.cmd', 'RUNTIME-MODULES.txt', 'RELEASE-MANIFEST.json', 'VERSION', 'README.txt',
    '.install-staging', '.install-rollback', '.minos-installation.json'
)

foreach ($Relative in $ManagedRelativePaths) {
    $Full = Join-Path $InstallRoot $Relative
    $NormalizedFull = ([System.IO.Path]::GetFullPath($Full)).TrimEnd('\')
    if (-not $NormalizedFull.StartsWith($NormalizedRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)) { continue }
    if (Test-Path -LiteralPath $Full) {
        Remove-Item -LiteralPath $Full -Recurse -Force -ErrorAction SilentlyContinue
    }
}
