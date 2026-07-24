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

$JavaVersion = (& $Java -version 2>&1 | Select-Object -First 1) -join ''
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

MCP:
  command = <installation>\minos.cmd
  args    = mcp
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
