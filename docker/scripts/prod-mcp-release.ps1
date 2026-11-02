[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall', 'AddProject', 'RemoveProject')]
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

    # AddProject / RemoveProject: the project folder and the mount name; Status: -ShowHostPaths.
    [string] $ProjectPath = '',
    [string] $Name = '',
    [switch] $ShowHostPaths,

    # Passed through and ignored by mcp-lifecycle.ps1 (with a warning): no projects root is mounted.
    [switch] $ReplaceProjectsRoot,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod'
)

# Windows PRODUCT entry point for the packaged MINOS Docker MCP lifecycle. This is the interface
# real users and the Windows installer/release tooling call: it owns the Windows-only contract
# (host guard, %LocalAppData%-based default install/data roots) and then delegates every
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

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
if ([string]::IsNullOrWhiteSpace($InstallRoot)) { $InstallRoot = Join-Path $LocalAppData 'MINOS\docker' }
if ([string]::IsNullOrWhiteSpace($DataRoot)) { $DataRoot = Join-Path $LocalAppData 'MINOS\docker-data' }

$LifecycleScript = Join-Path $PSScriptRoot 'mcp-lifecycle.ps1'
$LifecycleArguments = @{
    Action        = $Action
    InstallRoot   = $InstallRoot
    DataRoot      = $DataRoot
    ContainerName = $ContainerName
    ComposeProject = $ComposeProject
}
if (-not [string]::IsNullOrWhiteSpace($Jar)) { $LifecycleArguments.Jar = $Jar }
if (-not [string]::IsNullOrWhiteSpace($Version)) { $LifecycleArguments.Version = $Version }
if (-not [string]::IsNullOrWhiteSpace($Commit)) { $LifecycleArguments.Commit = $Commit }
if (-not [string]::IsNullOrWhiteSpace($ImageTag)) { $LifecycleArguments.ImageTag = $ImageTag }
if (-not [string]::IsNullOrWhiteSpace($SemanticProvider)) { $LifecycleArguments.SemanticProvider = $SemanticProvider }
if ($MinosArguments.Count -gt 0) { $LifecycleArguments.MinosArguments = $MinosArguments }
if (-not [string]::IsNullOrWhiteSpace($ProjectsRoot)) { $LifecycleArguments.ProjectsRoot = $ProjectsRoot }
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) { $LifecycleArguments.ProjectPath = $ProjectPath }
if (-not [string]::IsNullOrWhiteSpace($Name)) { $LifecycleArguments.Name = $Name }
if ($ShowHostPaths) { $LifecycleArguments.ShowHostPaths = $true }
if ($ReplaceProjectsRoot) { $LifecycleArguments.ReplaceProjectsRoot = $true }

& $LifecycleScript @LifecycleArguments
