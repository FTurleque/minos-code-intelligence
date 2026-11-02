[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $InstallRoot = '',
    [string] $DataRoot = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod',

    # Admin (the default) runs a MINOS command in minos-admin. AddProject exposes a project folder to the Docker
    # runtime (-ProjectPath, optional -Name); RemoveProject removes its mount (-Name); Status lists the mounts.
    [ValidateSet('Admin', 'AddProject', 'RemoveProject', 'Status')]
    [string] $Action = 'Admin',
    [string] $ProjectPath = '',
    [string] $Name = '',
    [switch] $ShowHostPaths,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MinosArguments
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Action -eq 'Admin' -and ($null -eq $MinosArguments -or $MinosArguments.Count -eq 0)) {
    throw 'A MINOS command is required, for example: .\minos-docker.ps1 project list'
}
if ($Action -ne 'Admin' -and $null -ne $MinosArguments -and $MinosArguments.Count -gt 0) {
    throw "-Action $Action takes no MINOS command."
}

$Workflow = Join-Path $PSScriptRoot 'prod-mcp-release.ps1'
if (-not (Test-Path -LiteralPath $Workflow -PathType Leaf)) {
    throw "MINOS Docker workflow is missing: $Workflow"
}

$Parameters = @{
    Action = $Action
    ContainerName = $ContainerName
    ComposeProject = $ComposeProject
}
if ($Action -eq 'Admin') { $Parameters['MinosArguments'] = $MinosArguments }
if (-not [string]::IsNullOrWhiteSpace($ProjectPath)) { $Parameters['ProjectPath'] = $ProjectPath }
if (-not [string]::IsNullOrWhiteSpace($Name)) { $Parameters['Name'] = $Name }
if ($ShowHostPaths) { $Parameters['ShowHostPaths'] = $true }
if (-not [string]::IsNullOrWhiteSpace($InstallRoot)) {
    $Parameters['InstallRoot'] = $InstallRoot
}
if (-not [string]::IsNullOrWhiteSpace($DataRoot)) {
    $Parameters['DataRoot'] = $DataRoot
}

& $Workflow @Parameters
