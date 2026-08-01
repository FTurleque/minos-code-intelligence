[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Template = Join-Path $RepoRoot 'packaging\windows\minos-installer.iss.template'
$Builder = Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1'
if (-not (Test-Path -LiteralPath $Template -PathType Leaf)) { throw "Installer template not found: $Template" }
if (-not (Test-Path -LiteralPath $Builder -PathType Leaf)) { throw "Installer builder not found: $Builder" }

$Text = [System.IO.File]::ReadAllText($Template, [System.Text.Encoding]::UTF8)
$BuilderText = [System.IO.File]::ReadAllText($Builder, [System.Text.Encoding]::UTF8)

function Require([string] $Needle, [string] $Message) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) { throw $Message }
}
function Forbid([string] $Needle, [string] $Message) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -ge 0) { throw $Message }
}

# Keep this verifier ASCII-only so Windows PowerShell 5.1 parses it correctly
# when repository .ps1 files are UTF-8 without BOM. Human-facing French labels
# are validated by the Python documentation/release-contract gate, which reads
# the template explicitly as UTF-8.
Require 'McpModePage := CreateInputOptionPage(' 'Installer is missing the explicit MCP mode selection page.'
Require 'wpSelectTasks,' 'MCP mode selection must be placed after the standard Windows tasks page.'
Require 'McpModePage.Values[0] := True;' 'Native MCP must be the recommended/default mode.'
Require 'McpModePage.Values[1] := False;' 'Docker MCP must remain opt-in.'
Require 'McpClientsPage := CreateCustomPage(' 'Installer is missing the dedicated native MCP integration page.'
Require 'McpModePage.ID,' 'Native MCP client integration page must follow MCP mode selection.'
Require 'DockerProjectsPage := CreateInputDirPage(' 'Installer is missing the Docker projects page.'
Require 'McpClientsPage.ID,' 'Docker projects page must follow native client integration.'
Require 'Result := not NativeMcpSelected()' 'Native client integration page is not conditional on native MCP mode.'
Require 'Result := not DockerMcpSelected()' 'Docker projects page is not conditional on Docker MCP mode.'
Require 'McpCopilotJetBrains := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Copilot JetBrains MCP row.'
Require 'McpCopilotCli := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Copilot CLI MCP row.'
Require 'McpClaudeCode := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Claude Code MCP row.'
Require 'McpClaudeDesktop := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Claude Desktop MCP row.'
Require 'McpCodex := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Codex MCP row.'
Require "ExtractTemporaryFile('detect-mcp-clients.ps1')" 'Installer does not run the packaged MCP client detector.'
Require "GetIniString(Section, 'Available'" 'Installer does not consume preflight availability.'
Require "GetIniString('Codex', 'Mode'" 'Installer does not consume deterministic Codex preflight mode.'
Require 'ShouldRunGlobalCleanup' 'Installer smoke isolation does not guard global uninstall hooks.'
Require 'if IsSmokeBuild() then' 'Installer smoke mode does not guard global mutations.'
Require 'integration\uninstall-mcp-clients.ps1' 'Installer does not use the canonical MCP uninstall wrapper.'
Require 'Flags: dontcopy' 'Installer detector is not embedded for pre-install extraction.'

# Uninstall must preserve user data by default, offer an explicit destructive
# choice in interactive mode, avoid prompts/mutations in silent/smoke runs, and
# only purge %LOCALAPPDATA%\MINOS after normal uninstall cleanup has completed.
Require 'DeleteUserDataOnUninstall: Boolean;' 'Installer does not track the optional user-data purge decision.'
Require 'procedure PromptForUserDataRemoval;' 'Uninstaller does not offer an explicit user-data purge choice.'
Require "ExpandConstant('{localappdata}\MINOS')" 'Uninstaller does not target the canonical MINOS user-data directory.'
Require 'UninstallSilent' 'Silent uninstall is not guarded against an interactive data-purge prompt.'
Require 'MB_YESNO or MB_DEFBUTTON2' 'User-data purge prompt must default to preserving data.'
Require 'procedure DeleteMinosUserData;' 'Uninstaller is missing the guarded user-data purge implementation.'
Require 'DelTree(UserDataRoot, True, True, True)' 'User-data purge does not remove the full MINOS local-data tree.'
Require 'CurUninstallStep = usPostUninstall' 'User-data purge must run only after normal uninstall cleanup.'
Require 'RegDeleteKeyIncludingSubkeys' 'Full user-data purge must also remove the MINOS per-user registry state.'

Forbid 'Name: "docker"' 'Docker MCP must be selected on the explicit MCP mode page, not as a generic Inno task.'
Forbid 'Name: "mcp_copilot_jetbrains"' 'Native MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_copilot_cli"' 'Native MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_claude_code"' 'Native MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_claude_desktop"' 'Native MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_codex"' 'Native MCP clients must not be static Inno [Tasks] entries.'
Forbid "WizardIsTaskSelected('docker')" 'Docker MCP must not depend on the legacy generic task contract.'
Forbid 'Type: filesandordirs; Name: "{localappdata}\MINOS"' 'User data must never be deleted unconditionally by [UninstallDelete].'

foreach ($Token in @('@@VERSION@@','@@APP_VERSION@@','@@APP_ID@@','@@SMOKE_MODE@@','@@SOURCE_DIR@@','@@OUTPUT_DIR@@','@@OUTPUT_BASENAME@@')) {
    if ($Text.IndexOf($Token, [StringComparison]::Ordinal) -lt 0) { throw "Installer template token missing: $Token" }
}
if ($BuilderText.IndexOf("[switch] `$Smoke", [StringComparison]::Ordinal) -lt 0 -or
    $BuilderText.IndexOf("MINOS-Release-Smoke-", [StringComparison]::Ordinal) -lt 0) {
    throw 'Installer builder does not expose the isolated smoke AppId contract.'
}

Write-Host 'MINOS INSTALLER TEMPLATE VERIFICATION SUCCESS' -ForegroundColor Green