[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead,
    [string] $ProjectsRoot = '',
    [switch] $SkipMavenVerify,
    [switch] $KeepArtifacts
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S4 qualification currently targets Windows host + Docker Desktop linux/amd64.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { $ProjectsRoot = Split-Path -Parent $RepoRoot }
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

function Invoke-NativeCapture {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments)
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ((& $File @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $Previous }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Assert-NativeSuccess {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments, [Parameter(Mandatory = $true)][string] $Failure)
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) { throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)" }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) { Write-Host $Result.Output }
    return $Result.Output
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD') -Failure 'Unable to resolve M29 HEAD').Trim()
if ($Head -ne $ExpectedHead.Trim()) { throw "M29-S4 exact-head mismatch: expected $ExpectedHead, found $Head" }
$Dirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect git status').Trim()
if (-not [string]::IsNullOrWhiteSpace($Dirty)) { throw "M29-S4 requires a clean worktree. Dirty entries:`n$Dirty" }
Write-Host "M29-S4 exact HEAD: $Head" -ForegroundColor Cyan

if (-not $SkipMavenVerify) {
    Push-Location $RepoRoot
    try {
        & '.\mvnw.cmd' clean verify
        if ($LASTEXITCODE -ne 0) { throw "M29-S4 Maven qualification failed with exit code $LASTEXITCODE" }
        $Python = Get-Command python -ErrorAction SilentlyContinue
        if (-not $Python) { $Python = Get-Command python3 -ErrorAction Stop }
        & $Python.Source '.\scripts\docs\check-current-docs.py'
        if ($LASTEXITCODE -ne 0) { throw "M29-S4 documentation consistency failed with exit code $LASTEXITCODE" }
    }
    finally { Pop-Location }
}

$Docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $Docker) { throw 'M29-S4 BLOCKED: docker.exe is not installed or not present in PATH.' }
$DockerServer = Invoke-NativeCapture -File $Docker.Source -Arguments @('version', '--format', '{{.Server.Version}}')
if ($DockerServer.ExitCode -ne 0) { throw "M29-S4 BLOCKED: Docker Desktop Linux daemon is unavailable: $($DockerServer.Output)" }
$Architecture = (Assert-NativeSuccess -File $Docker.Source -Arguments @('info', '--format', '{{.Architecture}}') -Failure 'Unable to resolve Docker server architecture').Trim()
if ($Architecture -ne 'x86_64') { throw "M29-S4 provider image is currently qualified only for linux/amd64; Docker server architecture is $Architecture" }
Assert-NativeSuccess -File $Docker.Source -Arguments @('compose', 'version') -Failure 'Docker Compose is unavailable' | Out-Null

$Jar = Join-Path $RepoRoot 'target\minos-code-intelligence-1.0.1-SNAPSHOT-all.jar'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "M29-S4 shaded JAR is missing: $Jar" }
$Workflow = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
if (-not (Test-Path -LiteralPath $Workflow -PathType Leaf)) { throw "M29-S4 Docker workflow is missing: $Workflow" }

$Suffix = $Head.Substring(0, [Math]::Min(12, $Head.Length))
$InstallRoot = Join-Path $env:TEMP "minos-m29-s4-runtime-$Suffix"
$DataRoot = Join-Path $env:TEMP "minos-m29-s4-data-$Suffix"
$ContainerName = "minos-m29-s4-$Suffix"
$ComposeProject = "minos-m29-s4-$Suffix"
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$ReportPath = Join-Path $ReportRoot "s4-qualification-$Head.json"
$StartedAt = [DateTime]::UtcNow
$Passed = $false
$Inventory = $null

function Invoke-Workflow {
    param([Parameter(Mandatory = $true)][ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall')][string] $Action, [string[]] $MinosArguments = @(), [switch] $Install)
    $Parameters = @{ Action = $Action; InstallRoot = $InstallRoot; DataRoot = $DataRoot; ContainerName = $ContainerName; ComposeProject = $ComposeProject }
    if ($Install) {
        $Parameters['Jar'] = $Jar
        $Parameters['Version'] = '1.0.1-SNAPSHOT'
        $Parameters['Commit'] = $Head
        $Parameters['ProjectsRoot'] = $ProjectsRoot
    }
    if ($MinosArguments.Count -gt 0) { $Parameters['MinosArguments'] = $MinosArguments }
    & $Workflow @Parameters
}

try {
    Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null

    Invoke-Workflow -Action Install -Install
    Invoke-Workflow -Action Validate

    # Install/Validate already execute the real offline executable probe and all-provider MINOS verification.
    Invoke-Workflow -Action Admin -MinosArguments @('tools', 'verify', '--all', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('providers', '--format', 'json')
    Invoke-Workflow -Action Admin -MinosArguments @('doctor', '--format', 'json')

    $InventoryPath = Join-Path $InstallRoot 'runtime\provider-inventory.json'
    $ChecksumsPath = Join-Path $InstallRoot 'runtime\provider-binary-sha256.txt'
    if (-not (Test-Path -LiteralPath $InventoryPath -PathType Leaf)) { throw "provider inventory missing: $InventoryPath" }
    if (-not (Test-Path -LiteralPath $ChecksumsPath -PathType Leaf)) { throw "provider checksum evidence missing: $ChecksumsPath" }
    $Inventory = Get-Content -Raw -LiteralPath $InventoryPath | ConvertFrom-Json
    if ($Inventory.formatVersion -ne 1) { throw "unsupported provider inventory format: $($Inventory.formatVersion)" }
    if ($Inventory.platform -ne 'linux/amd64') { throw "unexpected provider inventory platform: $($Inventory.platform)" }
    if ($Inventory.minosCommit -ne $Head) { throw "provider inventory commit mismatch: $($Inventory.minosCommit)" }

    $ExpectedProviders = @('scip-java', 'scip-typescript', 'scip-python', 'scip-clang', 'scip-dotnet', 'scip-go', 'rust-analyzer-scip')
    Write-Host 'M29-S4 expected inventory: 7 provider IDs'
    $Ids = @($Inventory.providers | ForEach-Object { [string] $_.id })
    $Missing = @($ExpectedProviders | Where-Object { $_ -notin $Ids })
    if ($Missing.Count -gt 0) { throw "provider inventory is incomplete: $($Missing -join ', ')" }
    if ((Get-Item -LiteralPath $ChecksumsPath).Length -le 0) { throw 'provider checksum evidence is empty' }

    $Passed = $true
    Write-Host 'M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    [ordered]@{
        formatVersion = 1
        milestone = 'M29-S4'
        head = $Head
        startedAt = $StartedAt.ToString('o')
        finishedAt = [DateTime]::UtcNow.ToString('o')
        result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        dockerServer = [string] $DockerServer.Output
        dockerArchitecture = $Architecture
        installRoot = $InstallRoot
        dataRoot = $DataRoot
        providerCount = if ($null -eq $Inventory) { 0 } else { @($Inventory.providers).Count }
        runtimeNetwork = 'none'
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S4 report: $ReportPath"

    if ($Passed -and -not $KeepArtifacts) {
        try { Invoke-Workflow -Action Uninstall } catch { Write-Warning $_.Exception.Message }
        Remove-Item -LiteralPath $DataRoot -Recurse -Force -ErrorAction SilentlyContinue
    } elseif (-not $Passed) {
        Write-Host "M29-S4 diagnostic artifacts preserved: $InstallRoot ; $DataRoot" -ForegroundColor Yellow
    }
}
