[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$')]
    [string] $Version,

    [string] $OutputRoot = '',

    [switch] $SkipVerify,

    # full: the package embeds the indexers (tools\), nothing to install.
    # lite: the same package without tools\ (a fraction of the size) for machines that manage their own
    #       tools; `minos tools install <provider>` installs one on demand and needs the network.
    [ValidateSet('full', 'lite')]
    [string] $Variant = 'full',

    # Reuse the shaded JAR and the SBOM left by a previous build of the same version (no `mvnw clean`, which would
    # also delete the other variant's artifacts under target\dist).
    [switch] $ReuseBuild
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

function Resolve-Python {
    foreach ($Name in @('python.exe', 'python', 'python3.exe', 'python3')) {
        $Command = Get-Command $Name -ErrorAction SilentlyContinue
        if ($Command) { return $Command.Source }
    }
    throw 'Python is required to generate MINOS release supply-chain evidence.'
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments
        $Exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    if ($Exit -ne 0) { throw "$Failure (exit=$Exit)" }
}

function Resolve-JdkModules {
    param(
        [Parameter(Mandatory = $true)][string] $Jdeps,
        [Parameter(Mandatory = $true)][string] $Jar
    )

    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = @(& $Jdeps '--multi-release' '24' '--ignore-missing-deps' '--print-module-deps' $Jar 2>&1 |
            ForEach-Object { $_.ToString().Trim() })
        $Exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    if ($Exit -ne 0) {
        throw "jdeps failed while deriving the packaged runtime modules (exit=$Exit): $($Output -join [Environment]::NewLine)"
    }

    $ModuleLine = $Output |
        Where-Object { $_ -match '^[A-Za-z0-9_.]+(?:,[A-Za-z0-9_.]+)*$' } |
        Select-Object -Last 1
    if ([string]::IsNullOrWhiteSpace($ModuleLine)) {
        throw "jdeps did not return a module dependency list: $($Output -join [Environment]::NewLine)"
    }

    $Modules = @($ModuleLine -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object -Unique)
    # MINOS embeds a source provider based on the public compiler tree API. Keep
    # jdk.compiler explicit even if a future jdeps implementation reports only
    # the java.compiler surface used by a specific fixture.
    if ($Modules -notcontains 'jdk.compiler') {
        $Modules += 'jdk.compiler'
    }
    return @($Modules | Sort-Object -Unique)
}

function Assert-PackagedRuntimeModules {
    param(
        [Parameter(Mandatory = $true)][string] $RuntimeJava,
        [Parameter(Mandatory = $true)][string[]] $RequiredModules
    )

    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Lines = @(& $RuntimeJava '--list-modules' 2>&1 | ForEach-Object { $_.ToString().Trim() })
        $Exit = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $Previous
    }
    if ($Exit -ne 0) {
        throw "Packaged runtime could not enumerate modules (exit=$Exit): $($Lines -join [Environment]::NewLine)"
    }

    $Present = @($Lines |
        Where-Object { $_ -match '^[A-Za-z0-9_.]+@' } |
        ForEach-Object { ($_ -split '@', 2)[0] } |
        Sort-Object -Unique)
    $Missing = @($RequiredModules | Where-Object { $_ -notin $Present })
    if ($Missing.Count -gt 0) {
        throw "Packaged runtime is missing modules required by the release JAR: $($Missing -join ', ')"
    }
    if ('java.xml' -notin $Present) {
        throw 'Packaged runtime regression: java.xml is absent; MCP/Jackson requires org.w3c.dom.Node during schema initialization.'
    }
    return $Present
}

# The setup modifies third-party MCP client configuration and now owns the
# transactional native/docker backend lifecycle. Qualify both on every Windows
# distribution build without contacting GitHub or publishing anything.
foreach ($Verifier in @(
    'scripts\install\verify-mcp-client-integration.ps1',
    'scripts\install\verify-mcp-client-preflight.ps1',
    'scripts\install\verify-mcp-backend-lifecycle.ps1'
)) {
    $VerifierPath = Join-Path $RepoRoot $Verifier
    if (-not (Test-Path -LiteralPath $VerifierPath -PathType Leaf)) {
        throw "MINOS MCP integration/lifecycle verifier not found: $VerifierPath"
    }
    try {
        & $VerifierPath
    }
    catch {
        throw "MINOS MCP integration/lifecycle verification failed ($Verifier): $($_.Exception.Message)"
    }
}

$JavaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    throw 'JAVA_HOME must point to a JDK 24 installation.'
}
$Java = Join-Path $JavaHome 'bin\java.exe'
$Jpackage = Join-Path $JavaHome 'bin\jpackage.exe'
$Jdeps = Join-Path $JavaHome 'bin\jdeps.exe'
foreach ($RequiredJdkTool in @($Java, $Jpackage, $Jdeps)) {
    if (-not (Test-Path -LiteralPath $RequiredJdkTool -PathType Leaf)) {
        throw "JAVA_HOME does not expose the required JDK 24 tool: $RequiredJdkTool"
    }
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

# M15-S2 makes the repository POM a real multi-module reactor. Release versions
# are supplied through Maven's CI-friendly `revision` property so every module
# sees one coherent version; do not generate a temporary mono-module POM.
if (-not $ReuseBuild) {
    Push-Location $RepoRoot
    try {
        $MavenArgs = @("-Drevision=$Version")
        if ($SkipVerify) {
            $MavenArgs += '-DskipTests'
        }
        $MavenArgs += 'clean'
        $MavenArgs += if ($SkipVerify) { 'package' } else { 'verify' }
        & '.\mvnw.cmd' @MavenArgs
        if ($LASTEXITCODE -ne 0) {
            throw "MINOS Maven build failed with exit code $LASTEXITCODE"
        }

        $RootPomContent = Get-Content -LiteralPath (Join-Path $RepoRoot 'pom.xml') -Raw
        if ($RootPomContent -notmatch '<cyclonedx\.maven\.plugin\.version>\s*([^<]+)\s*</cyclonedx\.maven\.plugin\.version>') {
            throw 'Unable to resolve cyclonedx.maven.plugin.version from root pom.xml.'
        }
        $CycloneDxVersion = $Matches[1].Trim()
        $SbomOutputDirectory = Join-Path $RepoRoot 'target\sbom'
        Remove-Item -LiteralPath $SbomOutputDirectory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $SbomOutputDirectory | Out-Null

        $CycloneDxArgs = @(
            "-Drevision=$Version",
            '-DschemaVersion=1.6',
            '-DoutputFormat=json',
            '-DoutputName=minos-cyclonedx',
            "-DoutputDirectory=$SbomOutputDirectory",
            '-DoutputReactorProjects=false',
            '-DincludeTestScope=false',
            '-DincludeLicenseText=false',
            '-Dcyclonedx.skipAttach=true',
            '-DprojectType=application',
            "org.cyclonedx:cyclonedx-maven-plugin:${CycloneDxVersion}:makeAggregateBom"
        )
        & '.\mvnw.cmd' @CycloneDxArgs
        if ($LASTEXITCODE -ne 0) {
            throw "CycloneDX aggregate SBOM generation failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
}

$Jar = Join-Path $RepoRoot "target\minos-code-intelligence-$Version-all.jar"
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) {
    throw "Shaded MINOS JAR not found: $Jar"
}
$JdkModules = Resolve-JdkModules -Jdeps $Jdeps -Jar $Jar
$JdkModuleList = $JdkModules -join ','
Write-Host "jpackage runtime roots (jdeps): $JdkModuleList" -ForegroundColor Cyan

$SbomSource = Join-Path $RepoRoot 'target\sbom\minos-cyclonedx.json'
if (-not (Test-Path -LiteralPath $SbomSource -PathType Leaf)) {
    throw "CycloneDX release SBOM not found after root aggregation: $SbomSource"
}

$Stage = Join-Path $OutputRoot '.jpackage-input'
$AppImages = Join-Path $OutputRoot '.jpackage-output'
$VariantSuffix = if ($Variant -eq 'lite') { '-lite' } else { '' }
$DistributionName = "minos-$Version-windows-x64$VariantSuffix"
$Distribution = Join-Path $OutputRoot $DistributionName
$Zip = Join-Path $OutputRoot "$DistributionName.zip"
$Checksum = "$Zip.sha256"
$SbomSidecar = Join-Path $OutputRoot "minos-$Version$VariantSuffix.cdx.json"
$SbomSidecarChecksum = "$SbomSidecar.sha256"
$NoticesSidecar = Join-Path $OutputRoot "MINOS-$Version$VariantSuffix-THIRD-PARTY-NOTICES.txt"
$NoticesSidecarChecksum = "$NoticesSidecar.sha256"

Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $AppImages -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $Distribution -Recurse -Force -ErrorAction SilentlyContinue
foreach ($Artifact in @($Zip, $Checksum, $SbomSidecar, $SbomSidecarChecksum, $NoticesSidecar, $NoticesSidecarChecksum)) {
    Remove-Item -LiteralPath $Artifact -Force -ErrorAction SilentlyContinue
}
New-Item -ItemType Directory -Force -Path $Stage, $AppImages, $Distribution | Out-Null

Copy-Item -LiteralPath $Jar -Destination (Join-Path $Stage 'minos.jar')
$AppVersion = ($Version -split '[-+]')[0]
# jpackage normally asks jlink to strip native commands from the bundled runtime.
# MINOS intentionally retains java.exe so release qualification can inspect the
# exact packaged image with `java --list-modules` instead of inferring its content.
$JlinkOptions = '--strip-debug --no-man-pages --no-header-files'
& $Jpackage @(
    '--type', 'app-image',
    '--name', 'minos',
    '--app-version', $AppVersion,
    '--input', $Stage,
    '--main-jar', 'minos.jar',
    '--main-class', 'com.minos.cli.MinosLauncher',
    '--add-modules', $JdkModuleList,
    '--jlink-options', $JlinkOptions,
    '--dest', $AppImages,
    '--win-console'
)
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}

$AppImage = Join-Path $AppImages 'minos'
$PackagedLauncher = Join-Path $AppImage 'minos.exe'
$PackagedRuntimeJava = Join-Path $AppImage 'runtime\bin\java.exe'
if (-not (Test-Path -LiteralPath $PackagedLauncher -PathType Leaf)) {
    throw "jpackage app image is missing the MINOS launcher: $PackagedLauncher"
}
if (-not (Test-Path -LiteralPath $PackagedRuntimeJava -PathType Leaf)) {
    throw "jpackage app image is missing the auditable runtime java launcher: $PackagedRuntimeJava"
}
$ResolvedRuntimeModules = Assert-PackagedRuntimeModules -RuntimeJava $PackagedRuntimeJava -RequiredModules $JdkModules
$ResolvedRuntimeModuleList = $ResolvedRuntimeModules -join ','
Write-Host "jpackage runtime modules (resolved): $ResolvedRuntimeModuleList" -ForegroundColor Cyan
Move-Item -LiteralPath $AppImage -Destination (Join-Path $Distribution 'app')

$LibDirectory = Join-Path $Distribution 'lib'
$DockerDirectory = Join-Path $Distribution 'docker'
$DockerScripts = Join-Path $DockerDirectory 'scripts'
$IntegrationDirectory = Join-Path $Distribution 'integration'
$SupplyChainDirectory = Join-Path $Distribution 'supply-chain'
New-Item -ItemType Directory -Force -Path $LibDirectory, $DockerScripts, $IntegrationDirectory, $SupplyChainDirectory | Out-Null
Copy-Item -LiteralPath $Jar -Destination (Join-Path $LibDirectory 'minos.jar') -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\Dockerfile.mcp.release') `
    -Destination (Join-Path $DockerDirectory 'Dockerfile.mcp.release') -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\compose-mcp.prod.yaml') `
    -Destination (Join-Path $DockerDirectory 'compose-mcp.prod.yaml') -Force
# The M30 connected profile (managed PostgreSQL/pgvector + Ollama sidecars) must ship
# alongside the base profile: configure-docker-mcp.ps1 delegates to the M30 configurator
# whenever StorageBackend=postgresql or SemanticProvider=ollama is selected, and that
# configurator resolves the connected template relative to its own directory.
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\compose-mcp.connected.yaml') `
    -Destination (Join-Path $DockerDirectory 'compose-mcp.connected.yaml') -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1') `
    -Destination (Join-Path $DockerScripts 'prod-mcp-release.ps1') -Force
# prod-mcp-release.ps1 delegates every action to this portable core; both must ship together or an
# installed distribution's Docker workflow fails looking for a sibling script that was never copied.
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\mcp-lifecycle.ps1') `
    -Destination (Join-Path $DockerScripts 'mcp-lifecycle.ps1') -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\configure-docker-mcp.ps1') `
    -Destination (Join-Path $DockerScripts 'configure-docker-mcp.ps1') -Force
Copy-Item -LiteralPath (Join-Path $RepoRoot 'docker\scripts\configure-m30-docker-services.ps1') `
    -Destination (Join-Path $DockerScripts 'configure-m30-docker-services.ps1') -Force
foreach ($IntegrationScript in @(
    'configure-runtime-settings.ps1',
    'invoke-named-mcp-script.ps1',
    'configure-mcp-clients.ps1',
    'configure-mcp-clients-setup.ps1',
    'configure-codex-mcp.ps1',
    'detect-mcp-clients.ps1',
    'uninstall-mcp-clients.ps1',
    'probe-mcp-backend.ps1',
    'switch-mcp-backend.ps1',
    'update-installation.ps1'
)) {
    Copy-Item -LiteralPath (Join-Path $RepoRoot "scripts\install\$IntegrationScript") `
        -Destination (Join-Path $IntegrationDirectory $IntegrationScript) -Force
}

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

$ResolvedRuntimeModules | Set-Content -LiteralPath (Join-Path $Distribution 'RUNTIME-MODULES.txt') -Encoding ascii

$FullToolsSection = @'
Quick start (the indexers for TypeScript and Java are shipped in this package, nothing to install for them):
  minos.cmd --version
  minos.cmd doctor
  minos.cmd project add C:\path\to\my-project --name my-project
  minos.cmd index my-project

Tools shipped in this package (tools\TOOLS-MANIFEST.json), verified against pinned SHA-256 digests
before their first use, with no network access:
  scip-java        Coursier, Apache Maven, the scip-java classpath
  scip-typescript  Node.js and the scip-typescript packages
Their licenses are listed in supply-chain\THIRD-PARTY-NOTICES.txt. A shipped tool that is altered, deleted
or replaced by a link is refused (minos.cmd doctor says so): reinstall this package to restore it.

NOT shipped, and never installed by MINOS: the toolchain of the project you analyse.
"minos.cmd doctor" names what is missing on your machine:
  TypeScript projects  nothing more is required
  Java projects        a full JDK (JAVA_HOME with javac and jar), Git for Windows (Git Bash),
                       Windows PowerShell 5.1 and csc.exe (both part of Windows); the build of
                       your project resolves its own Maven dependencies

Other languages: their indexers are NOT in this package. Install the toolchain, then the indexer on demand
(this needs the network):
  Python projects      Python 3.10 or newer with pip, and Node.js 16 or newer with npm;
                       then: minos.cmd tools install scip-python
  Go projects          the Go toolchain; then: minos.cmd tools install scip-go
  C# projects          the .NET SDK 10; then: minos.cmd tools install scip-dotnet
  Rust projects        cargo, rustc and rust-analyzer 0.3.2989 (release 2026-07-27) on the PATH:
                       MINOS installs none of them
  C and C++ projects   scip-clang publishes no Windows binary (Linux x86-64 only): not available here

For a shipped tool, "minos.cmd tools install" downloads nothing new: it reuses the files of this package.
Newer versions of the shipped tools come with a newer MINOS package.
Set MINOS_TOOLS_OFFLINE=1 to forbid any download.
'@

$LiteToolsSection = @'
This is the LITE package: it does not contain the indexers (no tools\ directory). Use the full package
(minos-<version>-windows-x64.zip) when the machine has no network access or you want nothing to install.

Quick start:
  minos.cmd --version
  minos.cmd doctor
  minos.cmd tools install scip-typescript     (or scip-java: downloads pinned, SHA-256 verified tools)
  minos.cmd project add C:\path\to\my-project --name my-project
  minos.cmd index my-project

The toolchain of the project you analyse is never installed by MINOS ("minos.cmd doctor" names what is
missing): a full JDK (JAVA_HOME with javac and jar), Git for Windows (Git Bash), Windows PowerShell 5.1
and csc.exe for Java projects; Python 3.10+ with Node.js 16+, Go, the .NET SDK 10, or cargo, rustc and
rust-analyzer for the other languages. C and C++: scip-clang has no Windows binary.
'@

$ReadmeToolsSection = if ($Variant -eq 'lite') { $LiteToolsSection } else { $FullToolsSection }

@"
MINOS Code Intelligence $Version

$ReadmeToolsSection

Default data directory:
  %LOCALAPPDATA%\MINOS\data

Stable MCP entrypoint (native or Docker):
  command = <installation>\app\minos.exe
  args    = mcp
  env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data

Backend selection:
  integration\switch-mcp-backend.ps1
  native | docker
  Docker selection is fail-closed when Docker Desktop is unavailable.

The Windows setup detects supported clients before offering integration:
  - GitHub Copilot for JetBrains / IntelliJ
  - GitHub Copilot CLI (capability-probed; editor shims are rejected)
  - Claude Code
  - Claude Desktop
  - OpenAI Codex CLI / Codex Desktop user configuration

Client configuration is backend-agnostic; switching changes MINOS backend state, not client files.

Supply-chain evidence:
  supply-chain\minos.cdx.json
  supply-chain\THIRD-PARTY-NOTICES.txt
  RELEASE-MANIFEST.json
  RUNTIME-MODULES.txt
"@ | Set-Content -LiteralPath (Join-Path $Distribution 'README.txt') -Encoding utf8

$Commit = (& git -C $RepoRoot rev-parse HEAD | Select-Object -First 1).Trim()
@"
version=$Version
commit=$Commit
java=$JavaVersion
variant=$Variant
runtimeModuleRoots=$JdkModuleList
runtimeModules=$ResolvedRuntimeModuleList
builtAt=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))
sbom=supply-chain/minos.cdx.json
manifest=RELEASE-MANIFEST.json
"@ | Set-Content -LiteralPath (Join-Path $Distribution 'VERSION') -Encoding ascii

Copy-Item -LiteralPath $SbomSource -Destination (Join-Path $SupplyChainDirectory 'minos.cdx.json') -Force
$Python = Resolve-Python
# The tools payload comes from embedded-tools.json, the single description shared with the Docker release
# image: this script owns no list and no hash of its own (scripts/quality/check-tools-manifest.py checks it).
if ($Variant -eq 'full') {
    Invoke-NativeChecked -File $Python -Arguments @(
        (Join-Path $RepoRoot 'scripts\release\build-embedded-tools.py'),
        '--platform', 'windows-x64',
        '--output', (Join-Path $Distribution 'tools'),
        '--cache', (Join-Path $RepoRoot 'target\tools-cache'),
        '--sbom', (Join-Path $SupplyChainDirectory 'minos.cdx.json')
    ) -Failure 'Embedded tools payload build failed'
}
Invoke-NativeChecked -File $Python -Arguments @(
    'scripts/release/generate-third-party-notices.py',
    '--sbom', (Join-Path $SupplyChainDirectory 'minos.cdx.json'),
    '--output', (Join-Path $SupplyChainDirectory 'THIRD-PARTY-NOTICES.txt'),
    '--strict'
) -Failure 'Third-party notice generation failed'
Invoke-NativeChecked -File $Python -Arguments @(
    'scripts/release/create-release-manifest.py',
    '--distribution', $Distribution,
    '--version', $Version,
    '--commit', $Commit,
    '--output', (Join-Path $Distribution 'RELEASE-MANIFEST.json')
) -Failure 'Release manifest generation failed'
Invoke-NativeChecked -File $Python -Arguments @(
    'scripts/release/check-supply-chain.py',
    '--distribution', $Distribution,
    '--version', $Version,
    '--commit', $Commit,
    '--strict-licenses'
) -Failure 'Release supply-chain evidence validation failed'
Invoke-NativeChecked -File $Python -Arguments @(
    (Join-Path $RepoRoot 'scripts\quality\check-tools-manifest.py'),
    '--root', $RepoRoot,
    '--distribution', $Distribution,
    '--variant', $Variant
) -Failure 'The embedded tools payload diverges from the tools catalogue'

Copy-Item -LiteralPath (Join-Path $SupplyChainDirectory 'minos.cdx.json') -Destination $SbomSidecar -Force
Copy-Item -LiteralPath (Join-Path $SupplyChainDirectory 'THIRD-PARTY-NOTICES.txt') -Destination $NoticesSidecar -Force

Compress-Archive -LiteralPath $Distribution -DestinationPath $Zip -CompressionLevel Optimal

function Write-Sha256Sidecar([string] $Artifact, [string] $Sidecar) {
    $Hash = (Get-FileHash -LiteralPath $Artifact -Algorithm SHA256).Hash.ToLowerInvariant()
    "$Hash  $([System.IO.Path]::GetFileName($Artifact))" | Set-Content -LiteralPath $Sidecar -Encoding ascii
    return $Hash
}

$Hash = Write-Sha256Sidecar -Artifact $Zip -Sidecar $Checksum
$SbomHash = Write-Sha256Sidecar -Artifact $SbomSidecar -Sidecar $SbomSidecarChecksum
$NoticesHash = Write-Sha256Sidecar -Artifact $NoticesSidecar -Sidecar $NoticesSidecarChecksum

Remove-Item -LiteralPath $Stage -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $AppImages -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host 'MINOS Windows distribution SUCCESS' -ForegroundColor Green
Write-Host "Variant      : $Variant"
Write-Host "Distribution : $Distribution"
Write-Host "Runtime roots : $JdkModuleList"
Write-Host "Runtime count : $($ResolvedRuntimeModules.Count)"
Write-Host "ZIP          : $Zip"
Write-Host "SHA-256      : $Hash"
Write-Host "SBOM         : $SbomSidecar"
Write-Host "SBOM SHA-256 : $SbomHash"
Write-Host "Notices      : $NoticesSidecar"
Write-Host "Notices SHA  : $NoticesHash"
