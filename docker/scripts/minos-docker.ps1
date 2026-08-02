[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $InstallRoot = '',
    [string] $DataRoot = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod',

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MinosArguments
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($null -eq $MinosArguments -or $MinosArguments.Count -eq 0) {
    throw 'A MINOS command is required, for example: .\minos-docker.ps1 project list'
}

$Workflow = Join-Path $PSScriptRoot 'prod-mcp-release.ps1'
if (-not (Test-Path -LiteralPath $Workflow -PathType Leaf)) {
    throw "MINOS Docker workflow is missing: $Workflow"
}

$Parameters = @{
    Action = 'Admin'
    MinosArguments = $MinosArguments
    ContainerName = $ContainerName
    ComposeProject = $ComposeProject
}
if (-not [string]::IsNullOrWhiteSpace($InstallRoot)) {
    $Parameters['InstallRoot'] = $InstallRoot
}
if (-not [string]::IsNullOrWhiteSpace($DataRoot)) {
    $Parameters['DataRoot'] = $DataRoot
}

& $Workflow @Parameters
