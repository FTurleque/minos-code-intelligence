[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Template = Join-Path $RepoRoot 'packaging\windows\minos-installer.iss.template'
$Builder = Join-Path $RepoRoot 'scripts\release\build-windows-installer.ps1'
if (-not (Test-Path -LiteralPath $Template -PathType Leaf)) { throw "Installer template not found: $Template" }
if (-not (Test-Path -LiteralPath $Builder -PathType Leaf)) { throw "Installer builder not found: $Builder" }

$Text = [System.IO.File]::ReadAllText($Template, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n").Replace("`r", "`n")
$BuilderText = [System.IO.File]::ReadAllText($Builder, [System.Text.Encoding]::UTF8).Replace("`r`n", "`n").Replace("`r", "`n")

function Require([string] $Needle, [string] $Message) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) { throw $Message }
}
function Forbid([string] $Needle, [string] $Message) {
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -ge 0) { throw $Message }
}
function Require-Builder([string] $Needle, [string] $Message) {
    if ($BuilderText.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) { throw $Message }
}

# Keep this verifier ASCII-only so Windows PowerShell 5.1 parses it correctly
# when repository .ps1 files are UTF-8 without BOM. Human-facing French labels
# are validated by the Python documentation/release-contract gate, which reads
# the template explicitly as UTF-8.
Require 'McpModePage := CreateInputOptionPage(' 'Installer is missing the explicit MCP mode selection page.'
Require 'wpSelectTasks,' 'MCP mode selection must be placed after the standard Windows tasks page.'
Require "    True,`n    True);" 'MCP mode selection must be exclusive and require exactly one choice.'
Require 'McpModePage.Values[2]' 'Installer is missing the explicit no-MCP selection.'
Require 'function NoMcpSelected(): Boolean;' 'Installer does not model the no-MCP selection.'
Require 'function McpBackendSelected(): Boolean;' 'Installer does not model a shared backend-selected state.'
Require 'function ExistingMcpBackend(): String;' 'Installer upgrade does not preserve the existing backend selection.'
Require 'LoadStringFromFile(ConfigPath, ConfigText)' 'Installer does not inspect persisted backend.properties during upgrade.'
Require "ExpandConstant('{localappdata}\MINOS\data\runtime\backend.properties')" 'Installer does not read the canonical backend configuration.'
Require "Result := 'docker'" 'Installer cannot restore a persisted Docker selection during upgrade.'
Require 'McpClientsPage := CreateCustomPage(' 'Installer is missing the shared MCP client integration page.'
Require 'DockerProjectsPage := CreateInputDirPage(' 'Installer is missing the Docker projects page.'
Require 'McpClientsPage.ID,' 'Docker projects page must follow client integration.'
Require 'Result := NoMcpSelected()' 'Shared MCP client page must be skipped only when MCP configuration is disabled.'
Require 'Result := not DockerMcpSelected()' 'Docker projects page is not conditional on Docker MCP mode.'
Require 'McpCopilotJetBrains := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Copilot JetBrains MCP row.'
Require 'McpCopilotCli := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Copilot CLI MCP row.'
Require 'McpClaudeCode := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Claude CLI / Code MCP row.'
Require 'McpClaudeDesktop := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the Claude Desktop MCP row.'
Require 'McpCodexCli := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the explicit Codex CLI MCP row.'
Require 'McpCodexDesktop := TNewCheckBox.Create(McpClientsPage)' 'Installer is missing the explicit Codex Desktop MCP row.'
Require 'Claude CLI / Claude Code' 'Installer does not expose Claude CLI / Code explicitly.'
Require 'OpenAI Codex CLI' 'Installer does not expose Codex CLI explicitly.'
Require 'OpenAI Codex Desktop' 'Installer does not expose Codex Desktop explicitly.'
Require "ExtractTemporaryFile('detect-mcp-clients.ps1')" 'Installer does not run the packaged MCP client detector.'
Require "GetIniString(Section, 'Available'" 'Installer does not consume preflight availability.'
Require "'CodexCli'" 'Installer does not consume the explicit Codex CLI preflight section.'
Require "'CodexDesktop'" 'Installer does not consume the explicit Codex Desktop preflight section.'
Require 'McpCodexCli.Checked and McpCodexDesktop.Checked' 'Installer does not reject an ambiguous dual Codex selection.'
Require '-Codex -CodexMode cli' 'Installer does not configure explicit Codex CLI mode.'
Require '-Codex -CodexMode desktop' 'Installer does not configure explicit Codex Desktop mode.'

# NEXUS-aligned review page: the user must see exactly what MINOS manages before
# installation, including managed PostgreSQL/pgvector and Ollama provisioning.
# Unicode labels are checked by scripts/docs/check-current-docs.py; keep the
# Windows PowerShell 5.1 verifier on ASCII structural invariants only.
Require 'SummaryPage := CreateCustomPage(' 'Installer is missing the pre-install summary page.'
Require "ManagedComponents := 'runtime MINOS';" 'Installer summary does not initialize its managed-components inventory.'
Require 'PostgreSQL/pgvector Docker' 'Installer summary does not identify managed PostgreSQL/pgvector.'
Require 'Ollama Docker' 'Installer summary does not identify managed Ollama.'
Require 'if OllamaProvisionCheck.Checked then ManagedComponents := ManagedComponents +' 'Installer summary does not disclose requested Ollama model provisioning.'
Require 'Ollama : instance locale/externe existante' 'Installer does not distinguish native external Ollama from managed Docker Ollama.'
Require 'SummaryMemo.Text := Text;' 'Installer summary is not rendered to the review page.'

# S7 backend lifecycle: a selected backend is prepared/validated/handshaken by
# one transactional helper before client integration is attempted. Docker is
# fail-closed when explicitly selected and unavailable.
Require 'function ConfigureSelectedMcpBackend(): Boolean;' 'Installer is missing the transactional backend setup function.'
Require 'integration\switch-mcp-backend.ps1' 'Installer does not invoke the canonical backend switcher.'
Require '-TargetBackend ' 'Installer does not pass the selected backend to the switcher.'
Require '-ProjectsRoot ' 'Installer does not pass the Docker project root to the switcher.'
Require 'if ConfigureSelectedMcpBackend() then' 'Installer does not gate client setup on backend validation success.'
Require 'ConfigureMcpClients;' 'Installer does not configure shared clients after backend activation.'
Require 'if not DockerReady() then' 'Installer does not block an explicitly selected unavailable Docker backend.'
Require 'Result := False;' 'Installer Docker prerequisite failure does not block wizard progression.'
Require 'aucun fallback silencieux' 'Installer does not explain fail-closed backend selection.'
Forbid 'configuration du MCP Docker sera ignoree' 'Installer must not silently ignore an explicitly selected Docker backend.'
Forbid 'configuration du MCP Docker sera ignor' 'Installer must not silently ignore an explicitly selected Docker backend.'
Forbid 'ConfigureDockerMcp;' 'Installer still owns a non-transactional direct Docker configuration path.'
Forbid 'ConfigureNativeMcpClients;' 'Installer still treats MCP client integration as native-only.'

Require 'ShouldRunGlobalCleanup' 'Installer smoke isolation does not guard global uninstall hooks.'
Require 'if IsSmokeBuild() then' 'Installer smoke mode does not guard global mutations.'
Require 'integration\uninstall-mcp-clients.ps1' 'Installer does not use the canonical MCP uninstall wrapper.'
Require 'Flags: dontcopy' 'Installer detector is not embedded for pre-install extraction.'

# Transactional program-payload upgrade: {app} is never overwritten directly.
# PrepareToInstall runs update-installation.ps1 (staging/journal/rollback/
# crash-recovery) against the embedded payload zip before Inno commits any of
# its own setup metadata, and blocks the install by returning a non-empty
# Result on failure -- Inno's own contract for aborting before ssInstall.
Forbid 'DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs' 'Installer must not copy the payload directly into {app} -- upgrades must go through the transactional updater.'
Require 'Source: "{#MinosPayloadZip}"; Flags: dontcopy noencryption' 'Installer does not embed the transactional payload zip.'
Require 'function PrepareToInstall(var NeedsRestart: Boolean): String;' 'Installer is missing the transactional PrepareToInstall hook.'
Require "ExtractTemporaryFile('update-installation.ps1')" 'Installer does not extract the transactional updater before installing.'
Require '-PackageRoot "' 'Installer does not point the transactional updater at the extracted payload.'
Require 'minos-payload' 'Installer does not reference the extracted payload directory.'
Require 'UsePreviousAppDir=yes' 'Installer does not reuse the previous install directory on upgrade.'
Require 'UninstallLogMode=overwrite' 'Installer does not keep the uninstall log consistent with updater-owned payload files.'
Require 'procedure RemoveMinosProgramPayload;' 'Uninstaller is missing the managed-directory payload cleanup.'
Require "ExtractTemporaryFile('uninstall-program-payload.ps1')" 'Uninstaller does not extract the payload-cleanup helper.'
Require 'RemoveMinosProgramPayload;' 'Uninstaller does not invoke managed-directory payload cleanup at usPostUninstall.'

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
Forbid 'Name: "mcp_copilot_jetbrains"' 'MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_copilot_cli"' 'MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_claude_code"' 'MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_claude_desktop"' 'MCP clients must not be static Inno [Tasks] entries.'
Forbid 'Name: "mcp_codex"' 'MCP clients must not be static Inno [Tasks] entries.'
Forbid "WizardIsTaskSelected('docker')" 'Docker MCP must not depend on the legacy generic task contract.'
Forbid 'Type: filesandordirs; Name: "{localappdata}\MINOS"' 'User data must never be deleted unconditionally by [UninstallDelete].'

foreach ($Token in @('@@VERSION@@','@@APP_VERSION@@','@@APP_ID@@','@@SMOKE_MODE@@','@@SOURCE_DIR@@','@@PAYLOAD_ZIP@@','@@OUTPUT_DIR@@','@@OUTPUT_BASENAME@@')) {
    if ($Text.IndexOf($Token, [StringComparison]::Ordinal) -lt 0) { throw "Installer template token missing: $Token" }
}
Require-Builder "[switch] `$Smoke" 'Installer builder does not expose smoke mode.'
Require-Builder 'MINOS-Release-Smoke-' 'Installer builder does not expose the isolated smoke AppId contract.'
Require-Builder 'integration\probe-mcp-backend.ps1' 'Installer builder does not require the packaged backend handshake probe.'
Require-Builder 'integration\switch-mcp-backend.ps1' 'Installer builder does not require the packaged backend switcher.'
Require-Builder 'integration\update-installation.ps1' 'Installer builder does not require the packaged transactional updater.'
Require-Builder 'integration\uninstall-program-payload.ps1' 'Installer builder does not require the packaged uninstall payload-cleanup helper.'
Require-Builder 'Compress-Archive' 'Installer builder does not produce the transactional payload zip.'
Require-Builder '@@PAYLOAD_ZIP@@' 'Installer builder does not substitute the payload zip token.'

Write-Host 'MINOS INSTALLER TEMPLATE VERIFICATION SUCCESS' -ForegroundColor Green
