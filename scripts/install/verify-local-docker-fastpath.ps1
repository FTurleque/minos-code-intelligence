[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS local Docker fast-path verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$DockerWorkflow = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
$DockerConfigurator = Join-Path $RepoRoot 'docker\scripts\configure-docker-mcp.ps1'
$LocalCandidate = Join-Path $RepoRoot 'scripts\release\build-local-windows-candidate.ps1'

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

function Read-Text([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Required fast-path script is missing: $Path" }
    return [System.IO.File]::ReadAllText($Path, [System.Text.Encoding]::UTF8)
}

function Assert-PowerShellSyntax([string] $Path) {
    $Tokens = $null
    $Errors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile($Path, [ref]$Tokens, [ref]$Errors)
    if ($Errors.Count -gt 0) {
        $Details = @($Errors | ForEach-Object { "line=$($_.Extent.StartLineNumber) message=$($_.Message)" }) -join [Environment]::NewLine
        throw "PowerShell syntax verification failed for ${Path}:`n$Details"
    }
}

foreach ($Path in @($DockerWorkflow, $DockerConfigurator, $LocalCandidate)) {
    Assert-PowerShellSyntax -Path $Path
}

$DockerText = Read-Text $DockerWorkflow
Assert-True ($DockerText.Contains("'PrepareImage'")) 'Docker workflow does not expose PrepareImage.'
Assert-True ($DockerText.Contains('Test-ExactPreparedImage')) 'Docker workflow does not verify exact version/commit image labels.'
Assert-True ($DockerText.Contains('io.minos.image.prepared-by=local-candidate')) 'Local candidate image cache ownership label is missing.'
Assert-True ($DockerText.Contains('imageOwnedByInstallation')) 'Docker installation metadata does not track image ownership.'
Assert-True ($DockerText.Contains('Preserved externally prepared local candidate image')) 'Docker uninstall does not preserve the local validation cache.'

$ConfiguratorText = Read-Text $DockerConfigurator
Assert-True (-not $ConfiguratorText.Contains('Invoke-DockerWorkflow -Action Validate')) 'Fresh Docker setup still repeats the expensive provider Validate pass.'
Assert-True ($ConfiguratorText.Contains('duplicate validation skipped')) 'Fresh Docker setup does not document the skipped duplicate validation.'

$LocalCandidateText = Read-Text $LocalCandidate
Assert-True ($LocalCandidateText.Contains('[switch] $PrebuildDockerImage')) 'Local Windows candidate builder does not expose -PrebuildDockerImage.'
Assert-True ($LocalCandidateText.Contains('-Action PrepareImage')) 'Local Windows candidate builder does not prepare the exact Docker image before setup generation.'
Assert-True ($LocalCandidateText.Contains('Publication   : NOT PERFORMED')) 'Local candidate builder lost its no-publication contract.'

Write-Host 'MINOS LOCAL DOCKER FAST-PATH VERIFICATION SUCCESS' -ForegroundColor Green
