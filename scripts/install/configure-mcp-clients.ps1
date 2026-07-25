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

    [ValidateRange(1, 300)]
    [int] $NativeCommandTimeoutSeconds = 20,

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

function Format-JsonText([string] $Json) {
    if ([string]::IsNullOrWhiteSpace($Json)) {
        return $Json
    }

    $Builder = New-Object System.Text.StringBuilder
    $Indent = 0
    $InString = $false
    $Escaped = $false

    for ($Index = 0; $Index -lt $Json.Length; $Index++) {
        $Char = $Json[$Index]

        if ($InString) {
            [void]$Builder.Append($Char)
            if ($Escaped) {
                $Escaped = $false
            }
            elseif ($Char -eq '\') {
                $Escaped = $true
            }
            elseif ($Char -eq '"') {
                $InString = $false
            }
            continue
        }

        switch ($Char) {
            '"' {
                $InString = $true
                [void]$Builder.Append($Char)
            }
            '{' {
                [void]$Builder.Append($Char)
                $Indent++
                $Next = if ($Index + 1 -lt $Json.Length) { $Json[$Index + 1] } else { [char]0 }
                if ($Next -ne '}') {
                    [void]$Builder.Append([Environment]::NewLine)
                    [void]$Builder.Append('  ' * $Indent)
                }
            }
            '[' {
                [void]$Builder.Append($Char)
                $Indent++
                $Next = if ($Index + 1 -lt $Json.Length) { $Json[$Index + 1] } else { [char]0 }
                if ($Next -ne ']') {
                    [void]$Builder.Append([Environment]::NewLine)
                    [void]$Builder.Append('  ' * $Indent)
                }
            }
            '}' {
                $Indent--
                $Previous = if ($Index -gt 0) { $Json[$Index - 1] } else { [char]0 }
                if ($Previous -ne '{') {
                    [void]$Builder.Append([Environment]::NewLine)
                    [void]$Builder.Append('  ' * $Indent)
                }
                [void]$Builder.Append($Char)
            }
            ']' {
                $Indent--
                $Previous = if ($Index -gt 0) { $Json[$Index - 1] } else { [char]0 }
                if ($Previous -ne '[') {
                    [void]$Builder.Append([Environment]::NewLine)
                    [void]$Builder.Append('  ' * $Indent)
                }
                [void]$Builder.Append($Char)
            }
            ',' {
                [void]$Builder.Append($Char)
                [void]$Builder.Append([Environment]::NewLine)
                [void]$Builder.Append('  ' * $Indent)
            }
            ':' {
                [void]$Builder.Append(': ')
            }
            default {
                if (-not [char]::IsWhiteSpace($Char)) {
                    [void]$Builder.Append($Char)
                }
            }
        }
    }

    return $Builder.ToString()
}

function Write-JsonObject([string] $Path, [object] $Value) {
    $Parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($Parent)) {
        New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    }
    $CompactJson = $Value | ConvertTo-Json -Depth 32 -Compress
    $Json = Format-JsonText -Json $CompactJson
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
    $Match = $script:ManagedEntries |
        Where-Object { $_.id -eq $Id } |
        Select-Object -First 1
    return $Match
}

function Get-EntryOwnership([object] $Entry) {
    if ($null -ne $Entry -and $Entry.PSObject.Properties['ownership']) {
        return [string]$Entry.ownership
    }
    return 'managed'
}

function Upsert-ManagedEntry([object] $Entry) {
    $script:ManagedEntries = @($script:ManagedEntries | Where-Object { $_.id -ne $Entry.id }) + @($Entry)
    Save-ManagedState
}

function Remove-ManagedEntry([string] $Id) {
    $script:ManagedEntries = @($script:ManagedEntries | Where-Object { $_.id -ne $Id })
    Save-ManagedState
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
    if ($CurrentArgs.Count -ne 1) {
        return $false
    }
    if ([string]$CurrentArgs[0] -ne 'mcp') {
        return $false
    }

    $CurrentEnv = Get-ObjectPropertyValue -Root $Current -Name 'env'
    $CurrentHome = [string](Get-ObjectPropertyValue -Root $CurrentEnv -Name 'MINOS_HOME')
    $ExpectedHome = if ($Entry.PSObject.Properties['dataRoot']) { [string]$Entry.dataRoot } else { $DataRoot }
    return -not [string]::IsNullOrWhiteSpace($CurrentHome) -and
            $CurrentHome.Equals($ExpectedHome, [StringComparison]::OrdinalIgnoreCase)
}

function Test-DesiredJsonEntryMatches([object] $Current) {
    $Expected = [pscustomobject]@{
        command = $MinosExe
        dataRoot = $DataRoot
    }
    return Test-ManagedJsonEntryMatches -Entry $Expected -Current $Current
}

function New-JsonStateEntry(
    [string] $Id,
    [string] $DisplayName,
    [string] $ConfigPath,
    [string] $ContainerName,
    [string] $Ownership,
    [string] $BackupPath
) {
    return [pscustomobject][ordered]@{
        id = $Id
        displayName = $DisplayName
        kind = 'json'
        ownership = $Ownership
        configPath = $ConfigPath
        container = $ContainerName
        command = $MinosExe
        dataRoot = $DataRoot
        backupPath = $BackupPath
        configuredAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
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
            if (Test-DesiredJsonEntryMatches -Current $Existing) {
                if ($null -eq $Managed) {
                    Upsert-ManagedEntry (New-JsonStateEntry -Id $Id -DisplayName $DisplayName `
                        -ConfigPath $ConfigPath -ContainerName $ContainerName -Ownership 'preexisting' -BackupPath '')
                    Write-IntegrationLog "KEEP client=$Id reason=existing-compatible-minos-entry path='$ConfigPath'"
                }
                else {
                    $Ownership = Get-EntryOwnership -Entry $Managed
                    $BackupPath = if ($Managed.PSObject.Properties['backupPath']) { [string]$Managed.backupPath } else { '' }
                    Upsert-ManagedEntry (New-JsonStateEntry -Id $Id -DisplayName $DisplayName `
                        -ConfigPath $ConfigPath -ContainerName $ContainerName -Ownership $Ownership -BackupPath $BackupPath)
                    Write-IntegrationLog "KEEP client=$Id reason=already-compatible path='$ConfigPath'"
                }
                Write-Host "MINOS MCP already configured for $DisplayName" -ForegroundColor Green
                return
            }

            if ($null -eq $Managed) {
                Write-IntegrationLog "SKIP client=$Id reason=existing-unmanaged-minos-entry path='$ConfigPath'"
                Fail-Or-Warn "$DisplayName already contains a different unmanaged MCP server named 'minos'; MINOS did not overwrite it."
                return
            }

            if ((Get-EntryOwnership -Entry $Managed) -eq 'preexisting') {
                Write-IntegrationLog "PRESERVE client=$Id reason=preexisting-entry-changed path='$ConfigPath'"
                Fail-Or-Warn "$DisplayName had a pre-existing MINOS MCP entry and it no longer matches the expected configuration; preserving it."
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
        Upsert-ManagedEntry (New-JsonStateEntry -Id $Id -DisplayName $DisplayName `
            -ConfigPath $ConfigPath -ContainerName $ContainerName -Ownership 'managed' -BackupPath $Backup)
        Write-IntegrationLog "INSTALL client=$Id kind=json path='$ConfigPath'"
        Write-Host "MINOS MCP configured for $DisplayName" -ForegroundColor Green
    }
    catch {
        Fail-Or-Warn "Failed to configure ${DisplayName}: $($_.Exception.Message)"
    }
}

function Uninstall-JsonClient([object] $Entry) {
    if ((Get-EntryOwnership -Entry $Entry) -eq 'preexisting') {
        Write-IntegrationLog "PRESERVE client=$($Entry.id) reason=preexisting-compatible-entry"
        Remove-ManagedEntry -Id ([string]$Entry.id)
        return
    }

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

function Stop-NativeProcessTree([System.Diagnostics.Process] $Process) {
    if ($null -eq $Process) {
        return
    }

    try {
        if ($Process.HasExited) {
            return
        }
    }
    catch {
        return
    }

    try {
        $TaskKill = Join-Path ([Environment]::SystemDirectory) 'taskkill.exe'
        if (Test-Path -LiteralPath $TaskKill -PathType Leaf) {
            & $TaskKill /PID ([string]$Process.Id) /T /F 2>&1 | Out-Null
        }
    }
    catch {
    }

    try {
        $StillRunning = -not $Process.HasExited
    }
    catch {
        $StillRunning = $false
    }
    if ($StillRunning) {
        try {
            $Process.Kill()
        }
        catch {
        }
    }
}

function Invoke-NativeCapture([string] $File, [string[]] $Arguments) {
    $PowerShellHost = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
    if ([System.IO.Path]::GetExtension($File).Equals('.ps1', [StringComparison]::OrdinalIgnoreCase)) {
        $PowerShellHost = Resolve-CommandPath -Name 'pwsh'
        if ([string]::IsNullOrWhiteSpace($PowerShellHost)) {
            return [pscustomobject]@{
                ExitCode = -2
                Output = "PowerShell 6+ (pwsh.exe) is required to invoke '$File' on Windows."
            }
        }
    }

    # Run every client command in a separate, non-interactive PowerShell host.
    # The JSON payload avoids lossy Windows command-line quoting for executable
    # paths and arguments containing spaces. A bounded wait prevents a hidden
    # setup window from waiting forever on an interactive client bootstrapper.
    $Payload = [pscustomobject][ordered]@{
        file = $File
        arguments = @($Arguments)
    } | ConvertTo-Json -Depth 4 -Compress
    $PayloadBase64 = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($Payload))
    $ChildScript = @'
$ErrorActionPreference = 'Continue'
try {
    $PayloadJson = [System.Text.Encoding]::UTF8.GetString(
        [Convert]::FromBase64String($env:MINOS_MCP_NATIVE_INVOCATION))
    $Request = $PayloadJson | ConvertFrom-Json
    $InvocationArguments = @($Request.arguments | ForEach-Object { [string]$_ })
    & ([string]$Request.file) @InvocationArguments
    $ExitCode = $LASTEXITCODE
    if ($null -eq $ExitCode) {
        $ExitCode = if ($?) { 0 } else { 1 }
    }
    exit ([int]$ExitCode)
}
catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
'@
    $EncodedCommand = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($ChildScript))

    $StartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $StartInfo.FileName = $PowerShellHost
    $StartInfo.Arguments = "-NoLogo -NoProfile -NonInteractive -EncodedCommand $EncodedCommand"
    $StartInfo.UseShellExecute = $false
    $StartInfo.CreateNoWindow = $true
    $StartInfo.RedirectStandardInput = $true
    $StartInfo.RedirectStandardOutput = $true
    $StartInfo.RedirectStandardError = $true
    $StartInfo.EnvironmentVariables['MINOS_MCP_NATIVE_INVOCATION'] = $PayloadBase64

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $StartInfo
    try {
        if (-not $Process.Start()) {
            throw "Failed to start '$PowerShellHost'."
        }
        $Process.StandardInput.Close()
        $StandardOutput = $Process.StandardOutput.ReadToEndAsync()
        $StandardError = $Process.StandardError.ReadToEndAsync()
        $TimeoutMilliseconds = [int]([Math]::Min([int]::MaxValue, $NativeCommandTimeoutSeconds * 1000))
        if (-not $Process.WaitForExit($TimeoutMilliseconds)) {
            Stop-NativeProcessTree -Process $Process
            [void]$Process.WaitForExit(5000)
            return [pscustomobject]@{
                ExitCode = -3
                Output = "Command '$File' timed out after $NativeCommandTimeoutSeconds seconds."
            }
        }

        # Complete asynchronous stream reads after process exit to avoid losing
        # the final diagnostic lines emitted by a CLI.
        $Process.WaitForExit()
        $OutputParts = @()
        if (-not [string]::IsNullOrWhiteSpace($StandardOutput.Result)) {
            $OutputParts += $StandardOutput.Result.Trim()
        }
        if (-not [string]::IsNullOrWhiteSpace($StandardError.Result)) {
            $OutputParts += $StandardError.Result.Trim()
        }
        return [pscustomobject]@{
            ExitCode = $Process.ExitCode
            Output = ($OutputParts -join [Environment]::NewLine).Trim()
        }
    }
    catch {
        Stop-NativeProcessTree -Process $Process
        return [pscustomobject]@{ ExitCode = -1; Output = $_.Exception.Message }
    }
    finally {
        $Process.Dispose()
    }
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

function Test-DesiredCliProbeMatches([object] $Probe) {
    $Expected = [pscustomobject]@{
        command = $MinosExe
        dataRoot = $DataRoot
    }
    return Test-ManagedCliProbeMatches -Entry $Expected -Probe $Probe
}

function New-CliStateEntry(
    [string] $Id,
    [string] $DisplayName,
    [string] $ToolName,
    [string] $ToolPath,
    [string[]] $GetArguments,
    [string[]] $RemoveArguments,
    [string] $Ownership
) {
    return [pscustomobject][ordered]@{
        id = $Id
        displayName = $DisplayName
        kind = 'cli'
        ownership = $Ownership
        toolName = $ToolName
        toolPath = $ToolPath
        command = $MinosExe
        dataRoot = $DataRoot
        getArguments = @($GetArguments)
        removeArguments = @($RemoveArguments)
        configuredAt = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
    }
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
        if ($Probe.ExitCode -eq 0) {
            if (Test-DesiredCliProbeMatches -Probe $Probe) {
                if ($null -eq $Managed) {
                    Upsert-ManagedEntry (New-CliStateEntry -Id $Id -DisplayName $DisplayName -ToolName $ToolName `
                        -ToolPath $ToolPath -GetArguments $GetArguments -RemoveArguments $RemoveArguments -Ownership 'preexisting')
                    Write-IntegrationLog "KEEP client=$Id reason=existing-compatible-minos-entry tool='$ToolPath'"
                }
                else {
                    Write-IntegrationLog "KEEP client=$Id reason=already-compatible tool='$ToolPath'"
                }
                Write-Host "MINOS MCP already configured for $DisplayName" -ForegroundColor Green
                return
            }

            if ($null -eq $Managed) {
                Write-IntegrationLog "SKIP client=$Id reason=existing-unmanaged-minos-entry tool='$ToolPath'"
                Fail-Or-Warn "$DisplayName already contains a different unmanaged MCP server named 'minos'; MINOS did not overwrite it."
                return
            }

            if ((Get-EntryOwnership -Entry $Managed) -eq 'preexisting') {
                Write-IntegrationLog "PRESERVE client=$Id reason=preexisting-entry-changed tool='$ToolPath'"
                Fail-Or-Warn "$DisplayName had a pre-existing MINOS MCP entry and it no longer matches the expected configuration; preserving it."
                return
            }

            if (Test-ManagedCliProbeMatches -Entry $Managed -Probe $Probe) {
                Write-IntegrationLog "KEEP client=$Id reason=already-managed tool='$ToolPath'"
                return
            }

            Write-IntegrationLog "PRESERVE client=$Id reason=managed-entry-modified tool='$ToolPath'"
            Fail-Or-Warn "$DisplayName MCP entry 'minos' no longer matches the entry managed by MINOS; preserving the user's current entry."
            return
        }

        if ($Probe.ExitCode -eq -2) {
            throw $Probe.Output
        }

        $Add = Invoke-NativeCapture -File $ToolPath -Arguments $AddArguments
        if ($Add.ExitCode -ne 0) {
            throw "add command failed (exit=$($Add.ExitCode)): $($Add.Output)"
        }
        Upsert-ManagedEntry (New-CliStateEntry -Id $Id -DisplayName $DisplayName -ToolName $ToolName `
            -ToolPath $ToolPath -GetArguments $GetArguments -RemoveArguments $RemoveArguments -Ownership 'managed')
        Write-IntegrationLog "INSTALL client=$Id kind=cli tool='$ToolPath'"
        Write-Host "MINOS MCP configured for $DisplayName" -ForegroundColor Green
    }
    catch {
        Fail-Or-Warn "Failed to configure ${DisplayName}: $($_.Exception.Message)"
    }
}

function Uninstall-CliClient([object] $Entry) {
    if ((Get-EntryOwnership -Entry $Entry) -eq 'preexisting') {
        Write-IntegrationLog "PRESERVE client=$($Entry.id) reason=preexisting-compatible-entry"
        Remove-ManagedEntry -Id ([string]$Entry.id)
        return
    }

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
# removing an identically named MCP server that it did not create. Entries that
# were already present and compatible at install time are tracked as
# ownership=preexisting and are never removed by MINOS.
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
