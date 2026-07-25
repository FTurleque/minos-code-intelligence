[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $OutputRoot = '',

    [switch] $SkipVerify
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $RepoRoot 'target\dist'
}
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)

if ($env:OS -ne 'Windows_NT') {
    throw 'The Windows distribution must be built on Windows.'
}

# The setup modifies third-party MCP client configuration. Qualify this lifecycle
# on every Windows distribution build so local -ValidateOnly runs provide the
# same safety gate even when GitHub Actions is unavailable.
$McpClientVerifier = Join-Path $RepoRoot 'scripts\install\verify-mcp-client-integration.ps1'
if (-not (Test-Path -LiteralPath $McpClientVerifier -PathType Leaf)) {
    throw "MINOS MCP client integration verifier not found: $McpClientVerifier"
}
try {
    & $McpClientVerifier
}
catch {
    throw "MINOS native MCP client integration verification failed: $($_.Exception.Message)"
}

$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JAVA_HOME must point to a JDK 24 installation.'
}
$Java = Join-Path $JavaHome 'bin\java.exe'
$Jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $Java -PathType Leaf) -or
    -not (Test-Path -LiteralPath $Jpackage -PathType Leaf)) {
    throw "JAVA_HOME does not expose java.exe and jpackage.exe: $JavaHome"
}

# `java -version` writes its version banner to stderr even on success. Windows
# PowerShell 5.1 turns native stderr into ErrorRecord objects, so the global
# ErrorActionPreference=Stop would abort before the exit code can be checked.
$PreviousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $JavaVersionOutput = ((& $Java -version 2>&1) | Out-String).Trim()
    $JavaVersionExitCode = $LASTEXITCODE
}
finally {
    $ErrorActionPreference = $PreviousErrorActionPreference
}
if ($JavaVersionExitCode -ne 0) {
    throw "Unable to execute JAVA_HOME java.exe -version (exit=$JavaVersionExitCode): $JavaVersionOutput"
}
$JavaVersion = ($JavaVersionOutput -split "`r?`n" | Select-Object -First 1).Trim()
if ($JavaVersion -notmatch '"24(?:\.|"|-)') {
    throw "MINOS distribution requires JDK 24; found: $JavaVersion"
}

$BuildPom = Join-Path $RepoRoot '.minos-release-pom.xml'
$SourcePom = Join-Path $RepoRoot 'pom.xml'
$SourcePomContent = Get-Content -Raw -LiteralPath $SourcePom
$CurrentVersionMatch = [regex]::Match($SourcePomContent, '<version>([^<]+)</version>')
if (-not $CurrentVersionMatch.Success) {
    throw 'Unable to locate the MINOS project version in pom.xml.'
}
$ReleasePomContent = $SourcePomContent.Remove(
    $CurrentVersionMatch.Groups[1].Index,
    $CurrentVersionMatch.Groups[1].Length
).Insert($CurrentVersionMatch.Groups[1].Index, $Version)
[System.IO.File]::WriteAllText($BuildPom, $ReleasePomContent, [System.Text.UTF8Encoding]::new($false))

try {
    Push-Location $RepoRoot
    try {
        $MavenArgs = @('-f', $BuildPom, 'clean')
        $MavenArgs += if ($SkipVerify) { 'package' } else { 'verify' }
        & '.\mvnw.cmd' @MavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "MINOS Maven build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $Jar = Join-Path $RepoRoot "target\minos-code-intelligence-$Version-all.jar"
    if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
        throw "Shaded MINOS JAR not found: $Jar"
    }

    $Stage = Join-Path $OutputRoot '.jpackage-input'
    $AppImages = Join-Path $OutputRoot '.jpackage-output'
    $DistributionName = "minos-$Version-windows-x64"
    $Distribution = Join-Path $OutputRoot $DistributionName
    $Zip = Join-Path $OutputRoot "$DistributionName.zip"
    $Checksum = "$Zip.sha256"

    Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $AppImages -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Distribution -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Zip -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $Checksum -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $Stage, $AppImages, $Distribution | Out-Null

    Copy-Item -LiteralPath $Jar -Destination (Join-Path $Stage 'minos.jar')
    $AppVersion = ($Version -split '[-+]')[0]
    & $Jpackage @(
        '--type', 'app-image',
        '--name', 'minos',
        '--app-version', $AppVersion,
        '--input', $Stage,
        '--main-jar', 'minos.jar',
        '--main-class', 'com.minos.cli.MinosLauncher',
        '--dest', $AppImages,
        '--win-console'
    )
    if ($LASTEXITCODE -ne 0) {
        throw "jpackage failed with exit code $LASTEXITCODE"
    }

    $AppImage = Join-Path $AppImages 'minos'
    if (-not (Test-Path -LiteralPath (Join-Path $AppImage 'minos.exe') -PathType Leaf)) {
        throw "jpackage app image is incomplete: $AppImage"
    }
    Move-Item -LiteralPath $AppImage -Destination (Join-Path $Distribution 'app')

    $LibDirectory = Join-Path $Distribution 'lib'
    $DockerDirectory = Join-Path $Distribution 'docker'
    $DockerScripts = Join-Path $DockerDirectory 'scripts'
    $IntegrationDirectory = Join-Path $Distribution 'integration'
    New-Item -ItemType Directory -Force -Path $LibDirectory, $DockerScripts, $IntegrationDirectory | Out-Null
    Copy-Item -LiteralPath $Jar -Destination (Join-Path $LibDirectory 'minos.jar') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\Dockerfile.mcp.release') `
        -Destination (Join-Path $DockerDirectory 'Dockerfile.mcp.release') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\compose.mcp.prod.yaml') `
        -Destination (Join-Path $DockerDirectory 'compose.mcp.prod.yaml') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1') `
        -Destination (Join-Path $DockerScripts 'prod-mcp-release.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\configure-docker-mcp.ps1') `
        -Destination (Join-Path $DockerScripts 'configure-docker-mcp.ps1') -Force
    Copy-Item -LiteralPath (Join-Path $RepoRoot 'scripts\install\configure-mcp-clients.ps1') `
        -Destination (Join-Path $IntegrationDirectory 'configure-mcp-clients.ps1') -Force

    Copy-Item -LiteralPath (Join-Path $RepoRoot 'scripts\install\install-windows.ps1') `
        -Destination (Join-Path $Distribution 'install.ps1')

    @'
@echo off
setlocal
if not defined MINOS_HOME set "MINOS_HOME=%LOCALAPPDATA%\MINOS\data"
"%~dp0app\minos.exe" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $Distribution 'minos.cmd') -Encoding ascii

    @'
@echo off
setlocal
if not defined MINOS_HOME set "MINOS_HOME=%LOCALAPPDATA%\MINOS\data"
"%~dp0app\minos.exe" mcp
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $Distribution 'minos-mcp.cmd') -Encoding ascii

    @"
MINOS Code Intelligence $Version

Quick start:
  minos.cmd --version
  minos.cmd doctor
  minos.cmd tools install scip-java
  minos.cmd project add N:\workspace-dev\my-project --name my-project
  minos.cmd index my-project

Default data directory:
  %LOCALAPPDATA%\MINOS\data

MCP native:
  command = <installation>\app\minos.exe
  args    = mcp
  env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data

The Windows setup can register this native MCP server in:
  - GitHub Copilot for JetBrains / IntelliJ
  - GitHub Copilot CLI
  - Claude Code
  - Claude Desktop
  - OpenAI Codex

Portable/manual client integration:
  & "<installation>\integration\configure-mcp-clients.ps1" `
    -InstallRoot "<installation>" `
    -CopilotJetBrains -ClaudeCode -ClaudeDesktop -Codex

Optional hardened Docker MCP:
  & "<installation>\docker\scripts\configure-docker-mcp.ps1" `
    -InstallRoot "<installation>" `
    -ProjectsRoot N:\workspace-dev `
    -Start

Docker Desktop must already be installed and running. Docker is optional and is not required by the native MCP integrations above.
"@ | Set-Content -LiteralPath (Join-Path $Distribution 'README.txt') -Encoding utf8

    $Commit = (& git -C $RepoRoot rev-parse HEAD | Select-Object -First 1).Trim()
    @"
version=$Version
commit=$Commit
java=$JavaVersion
builtAt=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
"@ | Set-Content -LiteralPath (Join-Path $Distribution 'VERSION') -Encoding ascii

    Compress-Archive -LiteralPath $Distribution -DestinationPath $Zip -CompressionLevel Optimal
    $Hash = (Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$Hash  $([System.IO.Path]::GetFileName($Zip))" | Set-Content -LiteralPath $Checksum -Encoding ascii

    Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $AppImages -Recurse -Force -ErrorAction SilentlyContinue

    Write-Host ''
    Write-Host 'MINOS Windows distribution SUCCESS' -ForegroundColor Green
    Write-Host "Distribution : $Distribution"
    Write-Host "ZIP          : $Zip"
    Write-Host "SHA-256      : $Hash"
}
finally {
    Remove-Item -LiteralPath $BuildPom -Force -ErrorAction SilentlyContinue
}
