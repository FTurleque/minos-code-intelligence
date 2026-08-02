[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string] $ExpectedHead,
    [switch] $SkipMavenVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'M29-S6 qualification currently targets Windows hosts.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$ReportRoot = Join-Path $RepoRoot 'target\m29'
$StartedAt = [DateTime]::UtcNow
$Passed = $false

function Invoke-NativeCapture {
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments)
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
    param([Parameter(Mandatory = $true)][string] $File, [Parameter(Mandatory = $true)][string[]] $Arguments, [Parameter(Mandatory = $true)][string] $Failure)
    $Result = Invoke-NativeCapture -File $File -Arguments $Arguments
    if ($Result.ExitCode -ne 0) { throw "$Failure (exit=$($Result.ExitCode)): $($Result.Output)" }
    if (-not [string]::IsNullOrWhiteSpace($Result.Output)) { Write-Host $Result.Output }
    return $Result.Output
}

$Git = (Get-Command git -ErrorAction Stop).Source
$Head = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'rev-parse', 'HEAD') -Failure 'Unable to resolve M29 HEAD').Trim()
if ($Head -ne $ExpectedHead.Trim()) { throw "M29-S6 exact-head mismatch: expected $ExpectedHead, found $Head" }
$Dirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect git status').Trim()
if (-not [string]::IsNullOrWhiteSpace($Dirty)) { throw "M29-S6 requires a clean worktree. Dirty entries:`n$Dirty" }
Write-Host "M29-S6 exact HEAD: $Head" -ForegroundColor Cyan

$ScriptsToParse = @(
    'scripts\install\configure-mcp-clients.ps1',
    'scripts\install\configure-codex-mcp.ps1',
    'scripts\install\detect-mcp-clients.ps1',
    'scripts\install\verify-mcp-client-integration.ps1',
    'scripts\install\verify-mcp-client-preflight.ps1',
    'scripts\install\verify-codex-mcp-integration.ps1',
    'scripts\install\verify-mcp-client-backend-routing.ps1',
    'scripts\m29\run-s6.ps1'
)
foreach ($Relative in $ScriptsToParse) {
    $Path = Join-Path $RepoRoot $Relative
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "M29-S6 required script is missing: $Path" }
    $Tokens = $null
    $Errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$Tokens, [ref]$Errors) | Out-Null
    if ($Errors.Count -gt 0) {
        throw "M29-S6 PowerShell parse failed for $Relative: $($Errors[0].Message)"
    }
}
Write-Host 'M29-S6 PowerShell parse preflight SUCCESS' -ForegroundColor Cyan

Push-Location $RepoRoot
try {
    if (-not $SkipMavenVerify) {
        & '.\mvnw.cmd' clean verify
        if ($LASTEXITCODE -ne 0) { throw "M29-S6 Maven qualification failed with exit code $LASTEXITCODE" }
    }

    $Python = Get-Command python -ErrorAction SilentlyContinue
    if (-not $Python) { $Python = Get-Command python3 -ErrorAction Stop }
    & $Python.Source '.\scripts\docs\check-current-docs.py'
    if ($LASTEXITCODE -ne 0) { throw "M29-S6 documentation consistency failed with exit code $LASTEXITCODE" }

    foreach ($Verifier in @(
        'scripts\install\verify-mcp-client-integration.ps1',
        'scripts\install\verify-mcp-client-preflight.ps1',
        'scripts\install\verify-mcp-client-backend-routing.ps1'
    )) {
        Write-Host "M29-S6 verifier: $Verifier" -ForegroundColor Cyan
        & (Join-Path $RepoRoot $Verifier)
    }

    $FinalDirty = (Assert-NativeSuccess -File $Git -Arguments @('-C', $RepoRoot, 'status', '--porcelain') -Failure 'Unable to inspect final git status').Trim()
    if (-not [string]::IsNullOrWhiteSpace($FinalDirty)) {
        throw "M29-S6 qualification modified the source checkout:`n$FinalDirty"
    }

    $Passed = $true
    Write-Host 'M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    Pop-Location
    New-Item -ItemType Directory -Force -Path $ReportRoot | Out-Null
    $ReportPath = Join-Path $ReportRoot "s6-qualification-$Head.json"
    [ordered]@{
        formatVersion = 1
        milestone = 'M29-S6'
        head = $Head
        startedAt = $StartedAt.ToString('o')
        finishedAt = [DateTime]::UtcNow.ToString('o')
        result = if ($Passed) { 'PASS' } else { 'FAIL_OR_BLOCKED' }
        clients = @(
            'copilot-jetbrains',
            'copilot-cli',
            'claude-code',
            'claude-desktop',
            'codex-cli',
            'codex-desktop'
        )
        stableEntrypoint = 'minos.exe mcp'
        backendSelection = '<MINOS_HOME>/runtime/backend.properties'
        backendValues = @('native', 'docker')
        clientConfigOwnsDockerTransport = $false
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $ReportPath -Encoding utf8
    Write-Host "M29-S6 report: $ReportPath"
}
