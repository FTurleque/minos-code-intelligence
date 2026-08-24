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

# Standalone reference/manual-recovery tool: ships in {app}\integration for
# diagnostics, but minos-installer.iss.template's own uninstall cleanup
# (RemoveMinosProgramPayload) does NOT invoke this file -- dontcopy +
# ExtractTemporaryFile is not reliably available during uninstall, so that
# cleanup runs as an inline PowerShell script block instead. Run this script
# manually only from outside {app}\integration (e.g. after copying it
# elsewhere), since it removes that directory and would otherwise race its
# own open file handle if executed from inside it.
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
