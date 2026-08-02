[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $ExpectedHead,

    [string] $ProjectsRoot = '',

    [switch] $SkipMavenVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S8 qualification targets Windows + Docker Desktop.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    $ProjectsRoot = Split-Path -Parent $RepoRoot
}
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
    throw "M29-S8 projects root does not exist: $ProjectsRoot"
}

function Invoke-NativeCapture {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments
    )
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Captured = @(& $File @Arguments 2>&1)
        $ExitCode = $LASTEXITCODE
        $Output = (($Captured | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()
    }
    finally { $ErrorActionPreference = $Previous }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Assert-NativeSuccess {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) { throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)" }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) { Write-Host $Result.Output }
    return $Result.Output
}

function Invoke-McpSession {
    param(
        [Parameter(Mandatory = $true)][string] $LauncherPath,
        [Parameter(Mandatory = $true)][string] $MinosHome,
        [Parameter(Mandatory = $true)][string[]] $RequestLines,
        [Parameter(Mandatory = $true)][string[]] $ResponseMarkers,
        [int] $TimeoutSeconds = 30
    )
    $LauncherPath = [System.IO.Path]::GetFullPath($LauncherPath)
    New-Item -ItemType Directory -Force -Path $MinosHome | Out-Null

    $Info = New-Object System.Diagnostics.ProcessStartInfo
    if ([System.IO.Path]::GetExtension($LauncherPath) -ieq '.cmd' -or
        [System.IO.Path]::GetExtension($LauncherPath) -ieq '.bat') {
        $Info.FileName = $env:ComSpec
        $Escaped = $LauncherPath.Replace('"', '""')
        $Info.Arguments = '/d /s /c ""{0}" mcp"' -f $Escaped
    }
    else {
        $Info.FileName = $LauncherPath
        $Info.Arguments = 'mcp'
    }
    $Info.WorkingDirectory = Split-Path -Parent $LauncherPath
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardInput = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    $Info.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $Info.StandardErrorEncoding = [System.Text.Encoding]::UTF8
    $Info.EnvironmentVariables['MINOS_HOME'] = $MinosHome

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $Info
    $Responses = @()
    try {
        if (-not $Process.Start()) { throw 'MCP session process did not start.' }
        $ErrorTask = $Process.StandardError.ReadToEndAsync()
        try {
            foreach ($Request in $RequestLines) {
                $Process.StandardInput.WriteLine($Request)
                $Process.StandardInput.Flush()
            }
            $Deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
            foreach ($Marker in $ResponseMarkers) {
                $Found = $false
                while ([DateTime]::UtcNow -lt $Deadline -and -not $Found) {
                    $Remaining = [int][Math]::Max(1, ($Deadline - [DateTime]::UtcNow).TotalMilliseconds)
                    $Task = $Process.StandardOutput.ReadLineAsync()
                    if (-not $Task.Wait($Remaining)) { throw "Timed out waiting for MCP response marker: $Marker" }
                    $Line = $Task.Result
                    if ($null -eq $Line) { throw "MCP server closed stdout waiting for: $Marker" }
                    if ($Line.IndexOf($Marker, [StringComparison]::Ordinal) -ge 0) {
                        $Responses += $Line
                        $Found = $true
                    }
                }
                if (-not $Found) { throw "MCP response marker not found: $Marker" }
            }
        }
        finally {
            try { $Process.StandardInput.Close() } catch { }
            if (-not $Process.WaitForExit(5000)) {
                try { $Process.Kill() } catch { }
                $Process.WaitForExit(5000) | Out-Null
            }
        }
        $Stderr = if ($ErrorTask.IsCompleted) { [string]$ErrorTask.Result } else { '' }
        foreach ($Fatal in @('NoClassDefFoundError', 'Exception in thread "main"')) {
            if ($Stderr.IndexOf($Fatal, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
                throw "MCP session emitted fatal stderr: $Stderr"
            }
        }
    }
    finally {
        $Process.Dispose()
    }
    return $Responses
}

function Get-NormalizedToolSchema([pscustomobject] $ToolsListResponse) {
    $Parsed = $ToolsListResponse | ConvertFrom-Json
    $Tools = @($Parsed.result.tools)
    $Sorted = $Tools | Sort-Object { [string]$_.name }
    return ($Sorted | ForEach-Object {
        [ordered]@{
            name        = [string]$_.name
            description = [string]$_.description
            inputSchema = $_.inputSchema | ConvertTo-Json -Depth 10 -Compress
        }
    })
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (& $Git -C $RepoRoot rev-parse HEAD 2>&1).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve M29 HEAD" }
if ($Head -ne $ExpectedHead.Trim()) {
    throw "M29-S8 exact-head mismatch: expected $ExpectedHead, found $Head"
}
$Dirty = (& $Git -C $RepoRoot status --porcelain 2>&1).Trim()
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect git status" }
if (-not [string]::IsNullOrWhiteSpace($Dirty)) {
    throw "M29-S8 requires a clean worktree. Dirty entries:`n$Dirty"
}
Write-Host "M29-S8 exact HEAD: $Head" -ForegroundColor Cyan

$ScriptsToParse = @(
    'scripts\install\probe-mcp-backend.ps1',
    'scripts\install\switch-mcp-backend.ps1',
    'scripts\install\install-windows.ps1',
    'scripts\release\build-windows-distribution.ps1',
    'scripts\m29\run-s8.ps1'
)
foreach ($Relative in $ScriptsToParse) {
    $Path = Join-Path $RepoRoot $Relative
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "M29-S8 required script is missing: $Path"
    }
    $Tokens = $null; $Errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$Tokens, [ref]$Errors) | Out-Null
    if ($Errors.Count -gt 0) {
        throw "M29-S8 PowerShell parse failed for ${Relative}: $($Errors[0].Message)"
    }
}
Write-Host 'M29-S8 PowerShell parse preflight SUCCESS' -ForegroundColor Cyan

$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) { throw 'M29-S8 BLOCKED: docker.exe not in PATH.' }
$DockerServer = Invoke-NativeCapture -File $Docker.Source -Arguments @('version', '--format', '{{.Server.Version}}')
if ($DockerServer.ExitCode -ne 0) {
    throw "M29-S8 BLOCKED: Docker Desktop Linux daemon unavailable. Probe: $($DockerServer.Output)"
}
Assert-NativeSuccess -File $Docker.Source -Arguments @('compose', 'version') -Failure 'Docker Compose unavailable' | Out-Null
Write-Host "Docker server: $($DockerServer.Output)" -ForegroundColor Cyan

$Version = '1.0.1-SNAPSHOT'
$Suffix = $Head.Substring(0, [Math]::Min(12, $Head.Length))
$QualificationRoot = Join-Path $env:TEMP "minos-m29-s8-$Suffix"
$OutputRoot = Join-Path $RepoRoot "target\m29\s8-dist-$Suffix"
$Distribution = Join-Path $OutputRoot "minos-$Version-windows-x64"
$NativeInstallRoot = Join-Path $QualificationRoot 'native-install'
$NativeDataRoot = Join-Path $QualificationRoot 'native-data'
$DockerRuntimeRoot = Join-Path $QualificationRoot 'docker-runtime'
$DockerDataRoot = Join-Path $QualificationRoot 'docker-data'
$ContainerName = "minos-m29-s8-$Suffix"
$ComposeProject = "minos-m29-s8-$Suffix"
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$ReportPath = Join-Path $ReportRoot "s8-parity-$Head.json"
$StartedAt = [DateTime]::UtcNow
$Passed = $false
$DockerRunning = $false
$NativeToolCount = 0
$DockerToolCount = 0
$SchemaMatchCount = 0
$NativeCallStructure = ''
$DockerCallStructure = ''

$MCP_INIT = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"minos-m29-s8-parity","version":"1"}}}'
$MCP_INITIALIZED = '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
$MCP_TOOLS_LIST = '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'
$MCP_SEARCH = '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"minos_search_code","arguments":{"query":"backend properties","maxResults":3}}}'

try {
    Remove-Item -LiteralPath $QualificationRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $OutputRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $QualificationRoot, $ReportRoot | Out-Null

    Push-Location $RepoRoot
    try {
        if (-not $SkipMavenVerify) {
            & '.\mvnw.cmd' clean verify
            if ($LASTEXITCODE -ne 0) { throw "M29-S8 Maven qualification failed with exit code $LASTEXITCODE" }
        }

        & '.\scripts\release\build-windows-distribution.ps1' `
            -Version $Version `
            -OutputRoot $OutputRoot `
            -SkipVerify
        if ($LASTEXITCODE -ne 0) { throw 'M29-S8 distribution build failed' }
    }
    finally { Pop-Location }

    if (-not (Test-Path -LiteralPath $Distribution -PathType Container)) {
        throw "M29-S8 distribution directory not found after build: $Distribution"
    }

    $Launcher = Join-Path $NativeInstallRoot 'minos.cmd'
    $Switcher = Join-Path $NativeInstallRoot 'integration\switch-mcp-backend.ps1'

    # 1) Native install.
    & (Join-Path $RepoRoot 'scripts\install\install-windows.ps1') `
        -Package $Distribution `
        -InstallRoot $NativeInstallRoot `
        -McpBackend native `
        -DataRoot $NativeDataRoot `
        -DockerInstallRoot $DockerRuntimeRoot `
        -DockerDataRoot $DockerDataRoot `
        -DockerContainerName $ContainerName `
        -DockerComposeProject $ComposeProject
    Write-Host 'M29-S8 native install SUCCESS' -ForegroundColor Cyan

    # 2) Probe native: tools/list + one tools/call.
    $NativeResponses = Invoke-McpSession `
        -LauncherPath $Launcher `
        -MinosHome $NativeDataRoot `
        -RequestLines @($MCP_INIT, $MCP_INITIALIZED, $MCP_TOOLS_LIST, $MCP_SEARCH) `
        -ResponseMarkers @('"id":1', '"id":2', '"id":3')
    $NativeInitLine = $NativeResponses[0]
    $NativeToolsLine = $NativeResponses[1]
    $NativeSearchLine = $NativeResponses[2]

    if ($NativeInitLine.IndexOf('minos-code-intelligence', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "M29-S8 native initialize response does not identify MINOS: $NativeInitLine"
    }

    $NativeToolSchemas = Get-NormalizedToolSchema -ToolsListResponse $NativeToolsLine
    $NativeToolCount = $NativeToolSchemas.Count
    if ($NativeToolCount -lt 2) {
        throw "M29-S8 native tools/list returned fewer than 2 tools: $NativeToolsLine"
    }
    if (-not ($NativeToolSchemas | Where-Object { $_.name -eq 'minos_search_code' })) {
        throw 'M29-S8 native tools/list is missing minos_search_code'
    }
    if (-not ($NativeToolSchemas | Where-Object { $_.name -eq 'minos_impact' })) {
        throw 'M29-S8 native tools/list is missing minos_impact'
    }

    $NativeSearchParsed = $NativeSearchLine | ConvertFrom-Json
    $NativeCallStructure = [pscustomobject]@{
        hasResult  = $null -ne $NativeSearchParsed.result
        hasError   = $null -ne $NativeSearchParsed.error
        contentIsArray = ($NativeSearchParsed.result.content -is [System.Array]) -or ($NativeSearchParsed.result.content -is [System.Collections.IEnumerable])
    } | ConvertTo-Json -Compress
    Write-Host "M29-S8 native MCP probe SUCCESS ($NativeToolCount tools)" -ForegroundColor Cyan

    # 3) Switch to Docker — reuses the Docker image built during the S7 native->Docker
    # reuse scenario if still present; otherwise builds fresh.
    & $Switcher `
        -TargetBackend docker `
        -InstallRoot $NativeInstallRoot `
        -DataRoot $NativeDataRoot `
        -DockerInstallRoot $DockerRuntimeRoot `
        -DockerDataRoot $DockerDataRoot `
        -DockerContainerName $ContainerName `
        -DockerComposeProject $ComposeProject `
        -ProjectsRoot $ProjectsRoot
    $DockerRunning = $true
    $BackendFile = Join-Path $NativeDataRoot 'runtime\backend.properties'
    $BackendContent = [System.IO.File]::ReadAllText($BackendFile, [System.Text.Encoding]::UTF8)
    if ($BackendContent -notlike '*backend=docker*') {
        throw "M29-S8 backend.properties did not switch to docker: $BackendContent"
    }
    Write-Host 'M29-S8 native -> Docker switch SUCCESS' -ForegroundColor Cyan

    # 4) Probe Docker: same session protocol, same launcher (now routes to Docker).
    $DockerResponses = Invoke-McpSession `
        -LauncherPath $Launcher `
        -MinosHome $NativeDataRoot `
        -RequestLines @($MCP_INIT, $MCP_INITIALIZED, $MCP_TOOLS_LIST, $MCP_SEARCH) `
        -ResponseMarkers @('"id":1', '"id":2', '"id":3')
    $DockerInitLine = $DockerResponses[0]
    $DockerToolsLine = $DockerResponses[1]
    $DockerSearchLine = $DockerResponses[2]

    if ($DockerInitLine.IndexOf('minos-code-intelligence', [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "M29-S8 Docker initialize response does not identify MINOS: $DockerInitLine"
    }

    $DockerToolSchemas = Get-NormalizedToolSchema -ToolsListResponse $DockerToolsLine
    $DockerToolCount = $DockerToolSchemas.Count
    if ($DockerToolCount -lt 2) {
        throw "M29-S8 Docker tools/list returned fewer than 2 tools: $DockerToolsLine"
    }

    $DockerSearchParsed = $DockerSearchLine | ConvertFrom-Json
    $DockerCallStructure = [pscustomobject]@{
        hasResult  = $null -ne $DockerSearchParsed.result
        hasError   = $null -ne $DockerSearchParsed.error
        contentIsArray = ($DockerSearchParsed.result.content -is [System.Array]) -or ($DockerSearchParsed.result.content -is [System.Collections.IEnumerable])
    } | ConvertTo-Json -Compress
    Write-Host "M29-S8 Docker MCP probe SUCCESS ($DockerToolCount tools)" -ForegroundColor Cyan

    # 5) Parity comparison.
    if ($NativeToolCount -ne $DockerToolCount) {
        throw "M29-S8 tool count mismatch: native=$NativeToolCount, docker=$DockerToolCount"
    }

    for ($Index = 0; $Index -lt $NativeToolSchemas.Count; $Index++) {
        $NativeEntry = $NativeToolSchemas[$Index]
        $DockerEntry = $DockerToolSchemas[$Index]
        if ($NativeEntry.name -ne $DockerEntry.name) {
            throw ("M29-S8 tool name mismatch at index " + $Index + ": native='" + $NativeEntry.name + "', docker='" + $DockerEntry.name + "'")
        }
        if ($NativeEntry.inputSchema -ne $DockerEntry.inputSchema) {
            throw "M29-S8 tool schema mismatch for '$($NativeEntry.name)': schemas differ between native and Docker"
        }
        $SchemaMatchCount++
    }
    Write-Host "M29-S8 tool schema parity PASS ($SchemaMatchCount/$NativeToolCount schemas match)" -ForegroundColor Cyan

    if ($NativeCallStructure -ne $DockerCallStructure) {
        throw "M29-S8 tools/call response structure mismatch:`n  native : $NativeCallStructure`n  docker : $DockerCallStructure"
    }
    Write-Host "M29-S8 tools/call response structure parity PASS" -ForegroundColor Cyan

    $FinalDirty = (& $Git -C $RepoRoot status --porcelain 2>&1).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect final git status" }
    if (-not [string]::IsNullOrWhiteSpace($FinalDirty)) {
        throw "M29-S8 qualification modified the source checkout:`n$FinalDirty"
    }

    $Passed = $true
    Write-Host 'M29-S8 NATIVE/DOCKER PARITY QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    if ($DockerRunning) {
        $DockerWorkflow = Join-Path $NativeInstallRoot 'docker\scripts\prod-mcp-release.ps1'
        if (Test-Path -LiteralPath $DockerWorkflow -PathType Leaf) {
            try {
                & $DockerWorkflow `
                    -Action Uninstall `
                    -InstallRoot $DockerRuntimeRoot `
                    -DataRoot $DockerDataRoot `
                    -ContainerName $ContainerName `
                    -ComposeProject $ComposeProject
            }
            catch { Write-Warning "M29-S8 Docker cleanup warning: $($_.Exception.Message)" }
        }
    }

    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null
    [ordered]@{
        formatVersion         = 1
        milestone             = 'M29-S8'
        head                  = $Head
        startedAt             = $StartedAt.ToString('o')
        finishedAt            = [DateTime]::UtcNow.ToString('o')
        result                = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        dockerServer          = [string]$DockerServer.Output
        projectsRoot          = $ProjectsRoot
        distribution          = $Distribution
        nativeToolCount       = $NativeToolCount
        dockerToolCount       = $DockerToolCount
        schemaMatchCount      = $SchemaMatchCount
        allSchemasMatch       = ($SchemaMatchCount -eq $NativeToolCount -and $NativeToolCount -gt 0)
        callStructureParity   = ($NativeCallStructure -eq $DockerCallStructure)
        nativeCallStructure   = $NativeCallStructure
        dockerCallStructure   = $DockerCallStructure
        allowedDifferences    = @('physical-paths', 'runtime-provenance', 'timestamps', 'result-content')
        parityScope           = 'MCP tool schemas + tools/call response structure'
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S8 report: $ReportPath"

    Remove-Item -LiteralPath $QualificationRoot -Recurse -Force -ErrorAction SilentlyContinue
}
