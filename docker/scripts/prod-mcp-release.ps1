[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall')]
    [string] $Action,

    [string] $Jar = '',
    [string] $Version = '',
    [string] $Commit = 'unknown',
    [string] $InstallRoot = '',
    [string] $DataRoot = '',
    [string] $ProjectsRoot = '',
    [string] $ImageTag = '',
    [string] $SemanticProvider = '',
    [string[]] $MinosArguments = @(),

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod'
)

# Windows PRODUCT entry point for the packaged MINOS Docker MCP lifecycle. This is the interface
# real users and the Windows installer/release tooling call: it owns the Windows-only contract
# (host guard, %LocalAppData%-based default install/data/projects roots) and then delegates every
# action to the portable core in mcp-lifecycle.ps1, which holds the actual install/start/validate/
# stop/uninstall logic. Keeping that logic in exactly one place means this wrapper and the
# GitHub-hosted Linux CI qualification path (scripts/ci/qualify-docker-upgrade.ps1, which calls
# mcp-lifecycle.ps1 directly with its own temporary paths) can never drift into two different
# implementations of the same product behavior.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The packaged MINOS Docker workflow currently targets Windows hosts.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($InstallRoot)) { $InstallRoot = Join-Path $LocalAppData 'MINOS\docker' }
if ([string]::IsNullOrWhiteSpace($DataRoot)) { $DataRoot = Join-Path $LocalAppData 'MINOS\docker-data' }
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { $ProjectsRoot = Split-Path -Parent $RepoRoot }

$LifecycleScript = Join-Path $PSScriptRoot 'mcp-lifecycle.ps1'
$LifecycleArguments = @{
    Action        = $Action
    InstallRoot   = $InstallRoot
    DataRoot      = $DataRoot
    ProjectsRoot  = $ProjectsRoot
    ContainerName = $ContainerName
    ComposeProject = $ComposeProject
}
if (-not [string]::IsNullOrWhiteSpace($Jar)) { $LifecycleArguments.Jar = $Jar }
if (-not [string]::IsNullOrWhiteSpace($Version)) { $LifecycleArguments.Version = $Version }
if (-not [string]::IsNullOrWhiteSpace($Commit)) { $LifecycleArguments.Commit = $Commit }
if (-not [string]::IsNullOrWhiteSpace($ImageTag)) { $LifecycleArguments.ImageTag = $ImageTag }
if (-not [string]::IsNullOrWhiteSpace($SemanticProvider)) { $LifecycleArguments.SemanticProvider = $SemanticProvider }
if ($MinosArguments.Count -gt 0) { $LifecycleArguments.MinosArguments = $MinosArguments }

& $LifecycleScript @LifecycleArguments
