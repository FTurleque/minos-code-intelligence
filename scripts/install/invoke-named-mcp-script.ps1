[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $SourcePath,

    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{0,63}$')]
    [string] $McpServerName = 'minos',

    [Parameter(Mandatory = $true)]
    [hashtable] $Parameters,

    [switch] $CodexScript
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$SourcePath = [System.IO.Path]::GetFullPath($SourcePath)
if (-not (Test-Path -LiteralPath $SourcePath -PathType Leaf)) {
    throw "MINOS MCP integration script not found: $SourcePath"
}

if ($McpServerName -eq 'minos') {
    & $SourcePath @Parameters
    return
}

# The mature integration managers historically used the literal server key 'minos'.
# Instantiate that already-qualified ownership/idempotence logic with the validated
# advanced-install server name instead of maintaining a divergent second manager.
$Text = [System.IO.File]::ReadAllText($SourcePath, [System.Text.Encoding]::UTF8)
$Text = $Text.Replace("'minos'", "'$McpServerName'")

if ($CodexScript) {
    $Text = $Text.Replace('[mcp_servers.minos]', "[mcp_servers.$McpServerName]")
    $Text = $Text.Replace('mcp_servers\\.minos', 'mcp_servers\\.' + [regex]::Escape($McpServerName))
}

$Temporary = Join-Path ([System.IO.Path]::GetTempPath()) (
    'minos-mcp-named-' + [Guid]::NewGuid().ToString('N') + '.ps1')
try {
    [System.IO.File]::WriteAllText($Temporary, $Text, [System.Text.UTF8Encoding]::new($false))
    & $Temporary @Parameters
}
finally {
    Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
}
