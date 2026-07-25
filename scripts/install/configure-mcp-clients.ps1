[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [ValidateSet('Install', 'Uninstall')]
    [string] $Action = 'Install',

    [switch] $CopilotJetBrains,
    [switch] $CopilotCli,
    [switch] $ClaudeCode,
    [switch] $ClaudeDesktop,
    [switch] $Codex,
    [switch] $Strict,

    [string] $DataRoot = '',
    [string] $StatePath = '',
    [string] $LogPath = '',
    [string] $BackupRoot = '',
    [string] $CopilotJetBrainsConfigPath = '',
    [string] $ClaudeDesktopConfigPath = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS native MCP client integration currently targets Windows hosts.'
}

$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$MinosExe = Join-Path $InstallRoot 'app\minos.exe'
if ($Action -eq 'Install' -and -not (Test-Path -LiteralPath $MinosExe -PathType Leaf)) {
    throw "MINOS native executable not found: $MinosExe"
}

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$RoamingAppData = [Environment]::GetFolderPath('ApplicationData')
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $DataRoot = Join-Path $LocalAppData 'MINOS\data'
}
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)

if ([string]::IsNullOrWhiteSpace($StatePath)) {
    $StatePath = Join-Path $LocalAppData 'MINOS\mcp-client-integrations.json'
}
if ([string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = Join-Path $LocalAppData 'MINOS\mcp-clients.log'
}
if ([string]::IsNullOrWhiteSpace($BackupRoot)) {
    $BackupRoot = Join-Path $LocalAppData 'MINOS\backups\mcp-clients'
}
if ([string]::IsNullOrWhiteSpace($CopilotJetBrainsConfigPath)) {
    $CopilotJetBrainsConfigPath = Join-Path $LocalAppData 'github-copilot\intellij\mcp.json'
}
if ([string]::IsNullOrWhiteSpace($ClaudeDesktopConfigPath)) {
    $ClaudeDesktopConfigPath = Join-Path $RoamingAppData 'Claude\claude_desktop_config.json'
}

$StatePath = [System.IO.Path]::GetFullPath($StatePath)
$LogPath = [System.IO.Path]::GetFullPath($LogPath)
$BackupRoot = [System.IO.Path]::GetFullPath($BackupRoot)
$CopilotJetBrainsConfigPath = [System.IO.Path]::GetFullPath($CopilotJetBrainsConfigPath)
$ClaudeDesktopConfigPath = [System.IO.Path]::GetFullPath($ClaudeDesktopConfigPath)

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null

function Write-IntegrationLog([string] $Message) {
    $Line = '{0} {1}' -f ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')), $Message
    Add-Content -LiteralPath $LogPath -Value $Line -Encoding UTF8
}

function Fail-Or-Warn([string] $Message) {
    Write-IntegrationLog "WARN $Message"
    if ($Strict) {
        throw $Message
    }
    Write-Warning $Message
}

function Read-JsonObject([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{}
    }
    $Raw = [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return [pscustomobject]@{}
    }
    try {
        $Value = $Raw | ConvertFrom-Json
        if ($null -eq $Value) {
            return [pscustomobject]@{}
        }
        return $Value
    }
    catch {
        throw "Invalid JSON configuration '$Path': $($_.Exception.Message)"
    }
}

function Write-JsonObject([string] $Path, [object] $Value) {
    $Parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($Parent)) {
        New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    }
    $Json = $Value | ConvertTo-Json -Depth 32
    [System.IO.File]::WriteAllText($Path, $Json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
}

function Ensure-ObjectProperty([object] $Root, [string] $Name) {
    $Property = $Root.PSObject.Properties[$Name]
    if ($null -eq $Property -or $null -eq $Property.Value) {
        $Container = [pscustomobject]@{}
        $Root | Add-Member -MemberType NoteProperty -Name $Name -Value $Container -Force
        return $Container
    }
    return $Property.Value
}

function Get-ObjectPropertyValue([object] $Root, [string] $Name) {
    if ($null -eq $Root) {
        return $null
    }
    $Property = $Root.PSObject.Properties[$Name]
    if ($null -eq $Property) {
        return $null
    }
    return $Property.Value
}

function Backup-Configuration([string] $ClientId, [string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return ''
    }
    $Stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff')
    $Directory = Join-Path $BackupRoot $Stamp
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $SafeName = ($ClientId -replace '[^A-Za-z0-9_.-]', '_') + '-' + [System.IO.Path]::GetFileName($Path)
    $Backup = Join-Path $Directory $SafeName
    Copy-Item -LiteralPath $Path -Destination $Backup -Force
    Write-IntegrationLog "BACKUP client=$ClientId source='$Path' target='$Backup'"
    return $Backup
}

function New-MinosServerConfig {
    return [pscustomobject][ordered]@{
        command = $MinosExe
        args = @('mcp')
        env = [pscustomobject][ordered]@{
            MINOS_HOME = $DataRoot
        }
    }
}

$ManagedEntries = @()
if (Test-Path -LiteralPath $StatePath -PathType Leaf) {
    try {
        $ExistingState = Read-JsonObject -Path $StatePath
        $ExistingClients = Get-ObjectPropertyValue -Root $ExistingState -Name 'clients'
        if ($null -ne $ExistingClients) {
            $ManagedEntries = @($ExistingClients)
        }
    }
    catch {
        Fail-Or-Warn "Unable to read existing MCP integration state: $($_.Exception.Message)"
        $ManagedEntries = @()
    }
}

function Get-ManagedEntry([string] $Id) {
    return @($script:ManagedEntries | Where-Object { $_.id -eq $Id } | Select-Object -First 1)[0]
}

function Upsert-ManagedEntry([object] $Entry) {
    $script:ManagedEntries = @($script:ManagedEntries | Where-Object { $_.id -ne $Entry.id }) + @($Entry)
}

function Remove-ManagedEntry([string] $Id) {
    $script:ManagedEntries = @($script:ManagedEntries | Where-Object { $_.id -ne $Id })
}

function Save-ManagedState {
    if ($script:ManagedEntries.Count -eq 0) {
        Remove-Item -LiteralPath $StatePath -Force -ErrorAction SilentlyContinue
        return
    }
    $State = [pscustomobject][ordered]@{
        formatVersion = 1
        updatedAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        installRoot = $InstallRoot
        dataRoot = $DataRoot
        clients = @($script:ManagedEntries)
    }
    Write-JsonObject -Path $StatePath -Value $State
}

function Test-ManagedJsonEntryMatches([object] $Entry, [object] $Current) {
    if ($null -eq $Current) {
        return $false
    }

    $CurrentCommand = [string](Get-ObjectPropertyValue -Root $Current -Name 'command')
    if ([string]::IsNullOrWhiteSpace($CurrentCommand) -or
        -not $CurrentCommand.Equals([string]$Entry.command, [StringComparison]::OrdinalIgnoreCase)) {
        return $false
    }

    $CurrentArgs = @(Get-ObjectPropertyValue -Root $Current -Name 'args')
    if ($CurrentArgs.Count -ne 1 -or [string]$CurrentArgs[0] -ne 'mcp') {
        return $false
    }

    $CurrentEnv = Get-ObjectPropertyValue -Root $Current -Name 'env'
    $CurrentHome = [string](Get-ObjectPropertyValue -Root $CurrentEnv -Name 'MINOS_HOME')
    $ExpectedHome = if ($Entry.PSObject.Properties['dataRoot']) { [string]$Entry.dataRoot } else { $DataRoot }
    return -not [string]::IsNullOrWhiteSpace($CurrentHome) -and
            $CurrentHome.Equals($ExpectedHome, [StringComparison]::OrdinalIgnoreCase)
}

function Install-JsonClient(
    [string] $Id,
    [string] $DisplayName,
    [string] $ConfigPath,
    [string] $ContainerName
) {
    try {
        $Root = Read-JsonObject -Path $ConfigPath
        $Container = Ensure-ObjectProperty -Root $Root -Name $ContainerName
        $ExistingProperty = $Container.PSObject.Properties['minos']
        $Existing = if ($null -eq $ExistingProperty) { $null } else { $ExistingProperty.Value }
        $Managed = Get-ManagedEntry -Id $Id

        if ($null -ne $Existing) {
            if ($null -eq $Managed) {
                Write-IntegrationLog "SKIP client=$Id reason=existing-unmanaged-minos-entry path='$ConfigPath'"
                Fail-Or-Warn "$DisplayName already contains an unmanaged MCP server named 'minos'; MINOS did not overwrite it."
                return
            }
            if (-not (Test-ManagedJsonEntryMatches -Entry $Managed -Current $Existing)) {
                Write-IntegrationLog "SKIP client=$Id reason=managed-entry-modified path='$ConfigPath'"
                Fail-Or-Warn "$DisplayName MCP entry 'minos' was modified after MINOS configured it; preserving the user's current entry."
                return
            }
        }

        $Backup = Backup-Configuration -ClientId $Id -Path $ConfigPath
        $Container | Add-Member -MemberType NoteProperty -Name 'minos' -Value (New-MinosServerConfig) -Force
        Write-JsonObject -Path $ConfigPath -Value $Root
        Upsert-ManagedEntry ([pscustomobject][ordered]@{
            id = $Id
            displayName = $DisplayName
            kind = 'json'
            configPath = $ConfigPath
            container = $ContainerName
            command = $MinosExe
            dataRoot = $DataRoot
            backupPath = $Backup
            configuredAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        })
        Write-IntegrationLog "INSTALL client=$Id kind=json path='$ConfigPath'"
        Write-Host "MINOS MCP configured for $DisplayName" -ForegroundColor Green
    }
    catch {
        Fail-Or-Warn "Failed to configure $DisplayName: $($_.Exception.Message)"
    }
}

function Uninstall-JsonClient([object] $Entry) {
    $Path = [string]$Entry.configPath
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-IntegrationLog "UNINSTALL client=$($Entry.id) status=config-missing"
        Remove-ManagedEntry -Id ([string]$Entry.id)
        return
    }
    try {
        $Root = Read-JsonObject -Path $Path
        $Container = Get-ObjectPropertyValue -Root $Root -Name ([string]$Entry.container)
        if ($null -eq $Container) {
            Remove-ManagedEntry -Id ([string]$Entry.id)
            return
        }
        $Property = $Container.PSObject.Properties['minos']
        if ($null -eq $Property) {
            Remove-ManagedEntry -Id ([string]$Entry.id)
            return
        }
        if (-not (Test-ManagedJsonEntryMatches -Entry $Entry -Current $Property.Value)) {
            Write-IntegrationLog "PRESERVE client=$($Entry.id) reason=entry-modified path='$Path'"
            Fail-Or-Warn "$($Entry.displayName) MCP entry 'minos' no longer matches the entry managed by MINOS; it was preserved."
            return
        }

        Backup-Configuration -ClientId ([string]$Entry.id) -Path $Path | Out-Null
        $Container.PSObject.Properties.Remove('minos')
        Write-JsonObject -Path $Path -Value $Root
        Write-IntegrationLog "UNINSTALL client=$($Entry.id) kind=json path='$Path'"
        Remove-ManagedEntry -Id ([string]$Entry.id)
    }
    catch {
        Fail-Or-Warn "Failed to remove MINOS from $($Entry.displayName): $($_.Exception.Message)"
    }
}

function Resolve-CommandPath([string] $Name) {
    $Command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $Command) {
        return ''
    }
    if (-not [string]::IsNullOrWhiteSpace($Command.Source)) {
        return $Command.Source
    }
    if ($Command.PSObject.Properties['Path'] -and -not [string]::IsNullOrWhiteSpace($Command.Path)) {
        return $Command.Path
    }
    return $Command.Name
}

function Invoke-NativeCapture([string] $File, [string[]] $Arguments) {
    $PreviousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ((& $File @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    catch {
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    }
    finally {
        $ErrorActionPreference = $PreviousPreference
    }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Test-ManagedCliProbeMatches([object] $Entry, [object] $Probe) {
    if ($Probe.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$Probe.Output)) {
        return $false
    }
    $Normalized = ([string]$Probe.Output).Replace('\\', '\')
    $Command = [string]$Entry.command
    $ExpectedHome = if ($Entry.PSObject.Properties['dataRoot']) { [string]$Entry.dataRoot } else { $DataRoot }
    return $Normalized.IndexOf($Command, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
            $Normalized.IndexOf($ExpectedHome, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Install-CliClient(
    [string] $Id,
    [string] $DisplayName,
    [string] $ToolName,
    [string[]] $GetArguments,
    [string[]] $AddArguments,
    [string[]] $RemoveArguments
) {
    try {
        $ToolPath = Resolve-CommandPath -Name $ToolName
        if ([string]::IsNullOrWhiteSpace($ToolPath)) {
            Fail-Or-Warn "$DisplayName was selected, but '$ToolName' is not installed or not available in PATH."
            return
        }

        $Managed = Get-ManagedEntry -Id $Id
        $Probe = Invoke-NativeCapture -File $ToolPath -Arguments $GetArguments
        if ($Probe.ExitCode -eq 0 -and $null -eq $Managed) {
            Write-IntegrationLog "SKIP client=$Id reason=existing-unmanaged-minos-entry tool='$ToolPath'"
            Fail-Or-Warn "$DisplayName already contains an unmanaged MCP server named 'minos'; MINOS did not overwrite it."
            return
        }

        if ($Probe.ExitCode -eq 0 -and $null -ne $Managed) {
            if (Test-ManagedCliProbeMatches -Entry $Managed -Probe $Probe) {
                Write-IntegrationLog "KEEP client=$Id reason=already-managed tool='$ToolPath'"
                return
            }
            Write-IntegrationLog "PRESERVE client=$Id reason=managed-entry-modified tool='$ToolPath'"
            Fail-Or-Warn "$DisplayName MCP entry 'minos' no longer matches the entry managed by MINOS; preserving the user's current entry."
            return
        }

        $Add = Invoke-NativeCapture -File $ToolPath -Arguments $AddArguments
        if ($Add.ExitCode -ne 0) {
            throw "add command failed (exit=$($Add.ExitCode)): $($Add.Output)"
        }
        Upsert-ManagedEntry ([pscustomobject][ordered]@{
            id = $Id
            displayName = $DisplayName
            kind = 'cli'
            toolName = $ToolName
            toolPath = $ToolPath
            command = $MinosExe
            dataRoot = $DataRoot
            getArguments = @($GetArguments)
            removeArguments = @($RemoveArguments)
            configuredAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        })
        Write-IntegrationLog "INSTALL client=$Id kind=cli tool='$ToolPath'"
        Write-Host "MINOS MCP configured for $DisplayName" -ForegroundColor Green
    }
    catch {
        Fail-Or-Warn "Failed to configure $DisplayName: $($_.Exception.Message)"
    }
}

function Uninstall-CliClient([object] $Entry) {
    try {
        $ToolPath = Resolve-CommandPath -Name ([string]$Entry.toolName)
        if ([string]::IsNullOrWhiteSpace($ToolPath)) {
            Fail-Or-Warn "Cannot remove MINOS from $($Entry.displayName): '$($Entry.toolName)' is no longer available in PATH."
            return
        }

        $Probe = Invoke-NativeCapture -File $ToolPath -Arguments @($Entry.getArguments)
        if ($Probe.ExitCode -ne 0) {
            Write-IntegrationLog "UNINSTALL client=$($Entry.id) status=entry-missing tool='$ToolPath'"
            Remove-ManagedEntry -Id ([string]$Entry.id)
            return
        }
        if (-not (Test-ManagedCliProbeMatches -Entry $Entry -Probe $Probe)) {
            Write-IntegrationLog "PRESERVE client=$($Entry.id) reason=entry-modified tool='$ToolPath'"
            Fail-Or-Warn "$($Entry.displayName) MCP entry 'minos' no longer matches the entry managed by MINOS; it was preserved."
            return
        }

        $Remove = Invoke-NativeCapture -File $ToolPath -Arguments @($Entry.removeArguments)
        if ($Remove.ExitCode -ne 0) {
            throw "remove command failed (exit=$($Remove.ExitCode)): $($Remove.Output)"
        }
        Write-IntegrationLog "UNINSTALL client=$($Entry.id) kind=cli tool='$ToolPath'"
        Remove-ManagedEntry -Id ([string]$Entry.id)
    }
    catch {
        Fail-Or-Warn "Failed to remove MINOS from $($Entry.displayName): $($_.Exception.Message)"
    }
}

Write-IntegrationLog "BEGIN action=$Action installRoot='$InstallRoot' dataRoot='$DataRoot'"

if ($Action -eq 'Install') {
    if ($CopilotJetBrains) {
        Install-JsonClient -Id 'copilot-jetbrains' -DisplayName 'GitHub Copilot (JetBrains / IntelliJ)' `
            -ConfigPath $CopilotJetBrainsConfigPath -ContainerName 'servers'
    }
    if ($ClaudeDesktop) {
        Install-JsonClient -Id 'claude-desktop' -DisplayName 'Claude Desktop' `
            -ConfigPath $ClaudeDesktopConfigPath -ContainerName 'mcpServers'
    }
    if ($CopilotCli) {
        Install-CliClient -Id 'copilot-cli' -DisplayName 'GitHub Copilot CLI' -ToolName 'copilot' `
            -GetArguments @('mcp', 'get', 'minos', '--json') `
            -AddArguments @('mcp', 'add', 'minos', '--env', "MINOS_HOME=$DataRoot", '--', $MinosExe, 'mcp') `
            -RemoveArguments @('mcp', 'remove', 'minos')
    }
    if ($ClaudeCode) {
        Install-CliClient -Id 'claude-code' -DisplayName 'Claude Code' -ToolName 'claude' `
            -GetArguments @('mcp', 'get', 'minos') `
            -AddArguments @('mcp', 'add', '--scope', 'user', '--env', "MINOS_HOME=$DataRoot", 'minos', '--', $MinosExe, 'mcp') `
            -RemoveArguments @('mcp', 'remove', 'minos')
    }
    if ($Codex) {
        Install-CliClient -Id 'codex' -DisplayName 'OpenAI Codex' -ToolName 'codex' `
            -GetArguments @('mcp', 'get', 'minos') `
            -AddArguments @('mcp', 'add', 'minos', '--env', "MINOS_HOME=$DataRoot", '--', $MinosExe, 'mcp') `
            -RemoveArguments @('mcp', 'remove', 'minos')
    }

    Save-ManagedState
    Write-IntegrationLog "END action=Install managed=$($ManagedEntries.Count)"
    Write-Host "MINOS native MCP client integration complete. Managed clients: $($ManagedEntries.Count)"
    Write-Host "Log: $LogPath"
    return
}

# Uninstall is driven exclusively by the state file. This prevents MINOS from
# removing an identically named MCP server that it did not create.
foreach ($Entry in @($ManagedEntries)) {
    if ([string]$Entry.kind -eq 'json') {
        Uninstall-JsonClient -Entry $Entry
    }
    elseif ([string]$Entry.kind -eq 'cli') {
        Uninstall-CliClient -Entry $Entry
    }
    else {
        Fail-Or-Warn "Unknown managed MCP client integration kind '$($Entry.kind)' for '$($Entry.id)'."
    }
}

Save-ManagedState
Write-IntegrationLog "END action=Uninstall remaining=$($ManagedEntries.Count)"
if ($ManagedEntries.Count -eq 0) {
    Write-Host 'MINOS native MCP client integrations removed.' -ForegroundColor Green
}
else {
    Write-Warning "Some MINOS MCP client integrations could not be removed. See $LogPath"
}
