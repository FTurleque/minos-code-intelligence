[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall')]
    [string] $Action,

    [string] $Jar = '',
    [string] $Version = '',
    [string] $Commit = 'unknown',

    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,
    [Parameter(Mandatory = $true)]
    [string] $DataRoot,
    [Parameter(Mandatory = $true)]
    [string] $ProjectsRoot,

    # Where docker/Dockerfile.mcp.release and docker/compose.mcp.prod.yaml are read from for this
    # Install. Defaults to this script's own repo (the normal case: installing whatever version of
    # MINOS this checkout is). The Docker A -> B upgrade qualification overrides this per candidate
    # so each candidate installs itself with its OWN contemporary Dockerfile/Compose recipe - the
    # same thing a real user upgrading between two real releases would get - while always running
    # this one, current, portable driver rather than an older candidate's possibly-incompatible copy
    # of this script (an older candidate predating this file's introduction has no copy at all).
    [string] $SourceRoot = '',

    [string] $ImageTag = '',
    [string] $SemanticProvider = '',
    [string[]] $MinosArguments = @(),

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod'
)

# Portable core of the packaged MINOS Docker MCP lifecycle (build/install/start/validate/stop/
# uninstall). Nothing here is Windows-specific: it only shells out to git/docker/java, all of which
# behave identically under PowerShell 7+ (pwsh) on Windows and Linux, and it never guesses a
# platform default directory - InstallRoot/DataRoot/ProjectsRoot are always supplied by the caller.
#
# docker/scripts/prod-mcp-release.ps1 is the Windows PRODUCT entry point: it keeps the Windows-only
# guard and the %LocalAppData%-based default paths real end users rely on, then delegates every
# action here. scripts/ci/qualify-docker-upgrade.ps1 (the CI qualification orchestrator, portable
# to GitHub-hosted Linux runners) calls this script directly with explicit temporary paths instead,
# since a CI qualification run has no Windows product installation to default into.
#
# This file intentionally mirrors prod-mcp-release.ps1's action semantics exactly (same parameter
# names, same Compose services, same metadata format) so the two never drift into two different
# lifecycle implementations of the same product.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$DockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if (-not $DockerCommand) { throw 'Docker is required.' }
& docker version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker does not respond.' }

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($SourceRoot)) { $SourceRoot = $RepoRoot }
else { $SourceRoot = [System.IO.Path]::GetFullPath($SourceRoot) }
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
$ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)

$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$BackupsRoot = Join-Path $InstallRoot 'backups'
$ComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$MetadataFile = Join-Path $RuntimeRoot 'installation.json'
$ProviderInventoryFile = Join-Path $RuntimeRoot 'provider-inventory.json'
$ProviderChecksumsFile = Join-Path $RuntimeRoot 'provider-binary-sha256.txt'

function ConvertTo-DockerPath([string] $Path) {
    return ([System.IO.Path]::GetFullPath($Path)).Replace('\', '/')
}

# Unlike docker\Dockerfile.mcp.release and docker\compose.mcp.prod.yaml -- which are shipped
# verbatim under {app}\docker\ and so remain reachable via a $RepoRoot-relative path from both a
# git checkout and an installed distribution -- the npm lockfiles below live under a Maven
# module's src/main/resources tree. That tree is compiled INTO minos.jar and never itself shipped
# as loose files, so a $RepoRoot-relative Copy-Item only ever works from a checkout; from an
# installed product it fails with "the system cannot find the path specified". Extract the same
# bytes directly from $Jar's classpath instead -- the one dependency this script already resolves
# correctly in both contexts.
function Copy-JarResourceEntry([string] $JarPath, [string] $EntryName, [string] $Destination) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $Archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $Entry = $Archive.GetEntry($EntryName)
        if ($null -eq $Entry) { throw "packaged jar resource is missing: $EntryName" }
        $Reader = $Entry.Open()
        try {
            $Writer = [System.IO.File]::Create($Destination)
            try { $Reader.CopyTo($Writer) } finally { $Writer.Dispose() }
        }
        finally { $Reader.Dispose() }
    }
    finally { $Archive.Dispose() }
}

function Resolve-SemanticProvider([string] $Requested) {
    $Value = $Requested
    if ([string]::IsNullOrWhiteSpace($Value)) { $Value = $env:MINOS_SEMANTIC_PROVIDER }
    if ([string]::IsNullOrWhiteSpace($Value)) { return 'disabled' }
    $Normalized = $Value.Trim().ToLowerInvariant()
    if ($Normalized -notin @('disabled', 'local-hash')) {
        throw "Packaged Docker semantic provider '$Value' is not qualified by M29. Allowed: disabled, local-hash."
    }
    return $Normalized
}

function Compose([string[]] $Arguments) {
    # Stdin MUST be a non-terminal pipe here: some invocation contexts (notably an
    # installer's inherited console) leave stdin attached to a handle that satisfies
    # isatty() without any human able to answer it. Compose only prompts interactively
    # ("... Recreate (data will be lost)?") when it believes stdin is a real terminal;
    # forcing it through a PowerShell pipe guarantees non-interactive, fail-fast behavior.
    $null | & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -ne 0) { throw "docker compose failed: $($Arguments -join ' ')" }
}

function Invoke-DockerAllowFailure([string[]] $Arguments) {
    $PreviousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Output = ((& $DockerCommand.Source @Arguments 2>&1) | Out-String).Trim()
        $ExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $PreviousErrorActionPreference }
    return [pscustomobject]@{ ExitCode = $ExitCode; Output = $Output }
}

function Read-ImageFile([string] $Image, [string] $Path) {
    $Result = Invoke-DockerAllowFailure -Arguments @('run', '--rm', '--network', 'none', '--entrypoint', 'cat', $Image, $Path)
    if ($Result.ExitCode -ne 0) {
        throw "MINOS Docker image evidence is missing: $Path (exit=$($Result.ExitCode)): $($Result.Output)"
    }
    return [string] $Result.Output
}

function Assert-DockerJavaRuntime([string] $Image, [string] $Failure) {
    $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ProcessInfo.FileName = $DockerCommand.Source
    $ProcessInfo.Arguments = 'run --rm --network none --entrypoint java "{0}" -version' -f $Image
    $ProcessInfo.UseShellExecute = $false
    $ProcessInfo.CreateNoWindow = $true
    $ProcessInfo.RedirectStandardOutput = $true
    $ProcessInfo.RedirectStandardError = $true
    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $ProcessInfo
    try {
        if (-not $Process.Start()) { throw "$Failure (process did not start)" }
        $StandardOutput = $Process.StandardOutput.ReadToEnd().Trim()
        $StandardError = $Process.StandardError.ReadToEnd().Trim()
        $Process.WaitForExit()
        $ExitCode = $Process.ExitCode
    }
    finally { $Process.Dispose() }
    $Output = @($StandardError, $StandardOutput) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | ForEach-Object { $_.Trim() }
    $Output = $Output -join [Environment]::NewLine
    if ($ExitCode -ne 0) { throw "$Failure (exit=$ExitCode): $Output" }
    if (-not [string]::IsNullOrWhiteSpace($Output)) { Write-Host $Output }
}

function Require-Installed {
    foreach ($File in @($ComposeFile, $EnvironmentFile, $MetadataFile, $ProviderInventoryFile, $ProviderChecksumsFile)) {
        if (-not (Test-Path -LiteralPath $File -PathType Leaf)) { throw "MINOS Docker PROD is not installed: missing $File" }
    }
}

function Initialize-And-VerifyProviderTools {
    Compose @('run', '--rm', '--no-deps', 'minos-tools-bootstrap')
    Compose @('run', '--rm', '--no-deps', 'minos-provider-probe')
    Compose @('run', '--rm', '--no-deps', 'minos-admin', 'tools', 'list', '--format', 'json')
    Compose @('run', '--rm', '--no-deps', 'minos-admin', 'tools', 'verify', '--all', '--format', 'json')
}

function Resolve-Image([string] $RequestedTag, [string] $RequestedVersion, [string] $RequestedCommit) {
    if ([string]::IsNullOrWhiteSpace($RequestedTag)) {
        $SafeVersion = $RequestedVersion.ToLowerInvariant().Replace('+', '-').Replace('SNAPSHOT', 'snapshot')
        $ShortCommit = $RequestedCommit.Substring(0, [Math]::Min(12, $RequestedCommit.Length))
        $RequestedTag = "$SafeVersion-$ShortCommit"
    }
    return "minos-code-intelligence:$RequestedTag"
}

function Test-ExactImage([string] $Image, [string] $ExpectedVersion, [string] $ExpectedCommit) {
    # Read the inspect document as JSON instead of embedding quoted label keys in
    # a Docker Go template. Windows PowerShell 5.1 rewrites those native argument
    # quotes and can make Docker interpret "org" as a template function.
    $Inspect = Invoke-DockerAllowFailure -Arguments @('image', 'inspect', $Image)
    if ($Inspect.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$Inspect.Output)) { return $false }

    try { $Images = @(([string]$Inspect.Output) | ConvertFrom-Json) }
    catch { return $false }
    if ($Images.Count -eq 0 -or $null -eq $Images[0].Config -or $null -eq $Images[0].Config.Labels) { return $false }

    $Labels = $Images[0].Config.Labels
    $VersionLabel = $Labels.PSObject.Properties['org.opencontainers.image.version']
    $RevisionLabel = $Labels.PSObject.Properties['org.opencontainers.image.revision']
    $PreparedLabel = $Labels.PSObject.Properties['io.minos.providers.prepared']
    return $null -ne $VersionLabel -and
        $null -ne $RevisionLabel -and
        $null -ne $PreparedLabel -and
        [string]$VersionLabel.Value -eq $ExpectedVersion -and
        [string]$RevisionLabel.Value -eq $ExpectedCommit -and
        [string]$PreparedLabel.Value -eq 'true'
}

switch ($Action) {
    'Install' {
        if ([string]::IsNullOrWhiteSpace($Jar)) { throw '-Jar is required for Install. Use the shaded JAR from the same MINOS release.' }
        $Jar = (Resolve-Path -LiteralPath $Jar).Path
        if ([string]::IsNullOrWhiteSpace($Version)) { throw '-Version is required for Install.' }
        if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) { throw "Projects root does not exist: $ProjectsRoot" }
        $ResolvedSemanticProvider = Resolve-SemanticProvider $SemanticProvider

        New-Item -ItemType Directory -Force -Path $RuntimeRoot, $DataRoot, $BackupsRoot | Out-Null
        if (Test-Path -LiteralPath $MetadataFile) {
            $Backup = Join-Path $BackupsRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
            New-Item -ItemType Directory -Force -Path $Backup | Out-Null
            if (Test-Path -LiteralPath $RuntimeRoot) { Copy-Item -LiteralPath $RuntimeRoot -Destination (Join-Path $Backup 'runtime') -Recurse }
        }

        $Timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        $Image = Resolve-Image -RequestedTag $ImageTag -RequestedVersion $Version -RequestedCommit $Commit
        if (Test-ExactImage -Image $Image -ExpectedVersion $Version -ExpectedCommit $Commit) {
            Write-Host "MINOS exact Docker image already exists; skipping provider-complete rebuild: $Image" -ForegroundColor Cyan
        }
        else {
            $BuildContext = Join-Path $RuntimeRoot 'build'
            Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $BuildContext | Out-Null
            Copy-Item -LiteralPath $Jar -Destination (Join-Path $BuildContext 'minos.jar')
            Copy-JarResourceEntry -JarPath $Jar -EntryName 'com/minos/adapter/scip/runtime/scip-typescript-package-lock.json' -Destination (Join-Path $BuildContext 'scip-typescript-package-lock.json')
            Copy-JarResourceEntry -JarPath $Jar -EntryName 'com/minos/adapter/scip/runtime/scip-python-package-lock.json' -Destination (Join-Path $BuildContext 'scip-python-package-lock.json')
            $Dockerfile = Join-Path $SourceRoot 'docker\Dockerfile.mcp.release'
            # --network=host: BuildKit's default isolated build network has shown
            # reproducible indefinite hangs against certain external hosts (observed
            # against github.com release-asset redirects) with no timeout to recover.
            # The host network stack reaches the same URLs reliably; this only affects
            # the build-time RUN steps, not the resulting image's own network config.
            & docker build --network=host --file $Dockerfile --tag $Image `
                --build-arg "MINOS_VERSION=$Version" `
                --build-arg "MINOS_GIT_COMMIT=$Commit" `
                --build-arg "MINOS_BUILD_TIMESTAMP=$Timestamp" $BuildContext
            if ($LASTEXITCODE -ne 0) { throw 'MINOS Docker image build failed.' }
            Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue
        }

        Copy-Item -LiteralPath (Join-Path $SourceRoot 'docker\compose.mcp.prod.yaml') -Destination $ComposeFile -Force
        @"
MINOS_COMPOSE_PROJECT=$ComposeProject
MINOS_CONTAINER_NAME=$ContainerName
MINOS_IMAGE=$Image
MINOS_DATA_DIR="$(ConvertTo-DockerPath $DataRoot)"
MINOS_PROJECTS_DIR="$(ConvertTo-DockerPath $ProjectsRoot)"
MINOS_HOST_PROJECTS_ROOT="$(ConvertTo-DockerPath $ProjectsRoot)"
MINOS_VERSION=$Version
MINOS_GIT_COMMIT=$Commit
MINOS_SEMANTIC_PROVIDER=$ResolvedSemanticProvider
"@ | Set-Content -LiteralPath $EnvironmentFile -Encoding ascii

        Compose @('config', '--quiet')
        Assert-DockerJavaRuntime -Image $Image -Failure 'The MINOS Docker image does not expose a valid Java runtime.'
        Read-ImageFile -Image $Image -Path '/opt/minos/provider-evidence/provider-inventory.json' | Set-Content -LiteralPath $ProviderInventoryFile -Encoding utf8
        Read-ImageFile -Image $Image -Path '/opt/minos/provider-evidence/binary-sha256.txt' | Set-Content -LiteralPath $ProviderChecksumsFile -Encoding ascii

        Compose @('run', '--rm', '--no-deps', 'minos-data-bootstrap')
        Initialize-And-VerifyProviderTools
        Compose @('run', '--rm', '--no-deps', 'minos-bootstrap')
        Compose @('run', '--rm', '--no-deps', 'minos-admin', '--help')

        [ordered]@{
            formatVersion = 5
            installedAt = $Timestamp
            image = $Image
            version = $Version
            gitCommit = $Commit
            dataRoot = $DataRoot
            projectsRoot = $ProjectsRoot
            containerProjectsRoot = '/workspace/projects'
            containerName = $ContainerName
            composeProject = $ComposeProject
            semanticProvider = $ResolvedSemanticProvider
            providerInventory = $ProviderInventoryFile
            providerChecksums = $ProviderChecksumsFile
            providerToolsVolume = 'minos-provider-tools'
            providerProbe = 'minos-provider-probe'
            queryPlane = [ordered]@{ service = 'minos-mcp'; dataReadOnly = $true; providerToolsReadOnly = $true; projectsReadOnly = $true; network = 'none' }
            adminPlane = [ordered]@{ service = 'minos-admin'; ephemeral = $true; dataReadOnly = $false; providerToolsReadOnly = $true; projectsReadOnly = $true; network = 'dependency-egress' }
        } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $MetadataFile -Encoding utf8

        Write-Host 'MINOS packaged Docker installation SUCCESS' -ForegroundColor Green
        Write-Host "Image     : $Image"
        Write-Host "Data      : $DataRoot -> /var/lib/minos"
        Write-Host "Projects  : $ProjectsRoot -> /workspace/projects (read-only)"
        Write-Host "Semantic  : $ResolvedSemanticProvider"
        Write-Host 'Network   : persistent query/bootstrap/provider-probe none; ephemeral admin/indexing may resolve project dependencies'
        Write-Host 'Providers : image-prepared, executable-probed offline, isolated named volume mounted read-only in query/admin planes'
        Write-Host 'Query     : persistent hardened minos-mcp plane; MINOS data read-only; network none'
        Write-Host 'Admin     : ephemeral minos-admin plane; MINOS data writable, projects read-only, dependency egress enabled'
    }
    'Start' {
        Require-Installed
        Compose @('up', '-d', '--force-recreate', 'minos-mcp')
        Write-Host 'MINOS Docker MCP query plane started.' -ForegroundColor Green
    }
    'Attach' {
        Require-Installed
        & docker exec -i $ContainerName java -cp /opt/minos/minos.jar com.minos.mcp.MinosMcpServer
        if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 130) { throw "MCP STDIO session failed with exit code $LASTEXITCODE" }
    }
    'Admin' {
        Require-Installed
        if ($null -eq $MinosArguments -or $MinosArguments.Count -eq 0) { throw '-MinosArguments is required for Admin.' }
        $ComposeArguments = @('run', '--rm', '--no-deps', 'minos-admin') + $MinosArguments
        Compose $ComposeArguments
    }
    'Status' {
        Require-Installed
        Get-Content -LiteralPath $MetadataFile
        Get-Content -LiteralPath $ProviderInventoryFile
        & docker ps -a --filter "name=^/$ContainerName$"
    }
    'Validate' {
        Require-Installed
        Compose @('config', '--quiet')
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        Assert-DockerJavaRuntime -Image $Metadata.image -Failure 'MINOS Docker validation failed.'
        Compose @('run', '--rm', '--no-deps', 'minos-data-bootstrap')
        Initialize-And-VerifyProviderTools
        Compose @('run', '--rm', '--no-deps', 'minos-bootstrap')
        Compose @('run', '--rm', '--no-deps', 'minos-admin', '--help')
        Write-Host 'MINOS Docker query/admin/provider configuration validated.' -ForegroundColor Green
    }
    'Stop' {
        Require-Installed
        Compose @('stop', '--timeout', '10', 'minos-mcp')
        Write-Host 'MINOS Docker MCP query plane stopped.' -ForegroundColor Green
    }
    'Uninstall' {
        Require-Installed
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        $ManagedImage = [string] $Metadata.image
        Compose @('down', '--timeout', '10', '--remove-orphans', '--volumes')
        if (-not [string]::IsNullOrWhiteSpace($ManagedImage)) {
            $ImageRemoval = Invoke-DockerAllowFailure -Arguments @('image', 'rm', $ManagedImage)
            if ($ImageRemoval.ExitCode -ne 0) { Write-Warning "MINOS Docker containers/provider volume were removed, but image '$ManagedImage' could not be removed: $($ImageRemoval.Output)" }
        }
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host 'MINOS Docker MCP/admin/provider runtime configuration removed.' -ForegroundColor Green
        Write-Host "Persistent data preserved: $DataRoot"
    }
}
