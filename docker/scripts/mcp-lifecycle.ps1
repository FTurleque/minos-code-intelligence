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

function Compose([string[]] $Arguments, [int[]] $AcceptedExitCodes = @(0)) {
    # Stdin MUST be a non-terminal pipe here: some invocation contexts (notably an
    # installer's inherited console) leave stdin attached to a handle that satisfies
    # isatty() without any human able to answer it. Compose only prompts interactively
    # ("... Recreate (data will be lost)?") when it believes stdin is a real terminal;
    # forcing it through a PowerShell pipe guarantees non-interactive, fail-fast behavior.
    $null | & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile -f $ComposeFile @Arguments
    if ($LASTEXITCODE -notin $AcceptedExitCodes) { throw "docker compose failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
}

# Resource ceilings (docker/compose.mcp.*.yaml, x-limits-<role>; defaults in docker/.env.example). Hitting
# one is not reported by Compose as anything but a failed command (exit 137 for a kill; for a PID ceiling
# a JVM stack trace saying OutOfMemoryError: unable to create native thread), and a process killed inside
# a still-running container (an Ollama model runner) only surfaces as an HTTP 500 from that service. These
# helpers state facts the container itself reports and name the variable to raise and the ceiling in
# force. They never claim to know WHY a ceiling was reached (for example whether a model is too large).
function Get-CeilingVariablePrefix([string] $Service) {
    switch ($Service) {
        'minos-mcp' { 'MINOS_MCP' }
        'minos-admin' { 'MINOS_ADMIN' }
        'minos-postgres' { 'MINOS_POSTGRES' }
        'minos-ollama' { 'MINOS_OLLAMA' }
        default { 'MINOS_JOB' }
    }
}

function Format-CeilingBytes($Bytes) {
    if ($null -eq $Bytes -or [int64]$Bytes -le 0) { return 'none' }
    return ('{0} MiB' -f [math]::Round([int64]$Bytes / 1MB))
}

# Ceilings in force for a service, as Compose resolves them with the runtime .env (override included).
function Get-EffectiveCeilings([string] $Service) {
    $Json = $null
    try {
        $Json = (& docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile -f $ComposeFile config --format json 2>$null | Out-String)
        $Config = $Json | ConvertFrom-Json
        $Definition = $Config.services.$Service
        if ($null -ne $Definition) {
            return [pscustomobject]@{ Memory = (Format-CeilingBytes $Definition.mem_limit); Pids = [string]$Definition.pids_limit }
        }
    }
    catch { }
    return [pscustomobject]@{ Memory = 'unknown'; Pids = 'unknown' }
}

function Write-AdminCeilingHint {
    $Ceilings = Get-EffectiveCeilings 'minos-admin'
    Write-Warning ("If the error above is 'unable to create native thread', 'Resource temporarily unavailable' or a kill (exit 137), " +
        "the admin container may have reached a resource ceiling. In force: memory $($Ceilings.Memory) (MINOS_ADMIN_MEM_LIMIT), " +
        "processes and threads $($Ceilings.Pids) (MINOS_ADMIN_PIDS_LIMIT). Raise the matching variable in $EnvironmentFile and retry. " +
        "See docs/user/docker-runtime.md, 'Depannage'.")
}

function Write-CeilingDiagnostics([string] $Project) {
    $Containers = @(& docker ps -a --filter "label=com.docker.compose.project=$Project" --format '{{.Names}}' 2>$null)
    foreach ($Name in $Containers) {
        $Line = (& docker inspect --format '{{index .Config.Labels "com.docker.compose.service"}}|{{.State.Running}}|{{.State.OOMKilled}}|{{.HostConfig.Memory}}|{{.HostConfig.PidsLimit}}' $Name 2>$null)
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($Line)) { continue }
        $Parts = $Line.Split('|')
        $Service = $Parts[0]; $Running = ($Parts[1] -eq 'true'); $OomKilled = ($Parts[2] -eq 'true')
        $Prefix = Get-CeilingVariablePrefix $Service
        $OomEvents = 0; $PidRefusals = 0
        if ($Running) {
            $MemoryEvents = (& docker exec $Name cat /sys/fs/cgroup/memory.events 2>$null | Out-String)
            if ($MemoryEvents -match 'oom_kill (\d+)') { $OomEvents = [int]$Matches[1] }
            $PidEvents = (& docker exec $Name cat /sys/fs/cgroup/pids.events 2>$null | Out-String)
            if ($PidEvents -match 'max (\d+)') { $PidRefusals = [int]$Matches[1] }
        }
        if ($OomKilled -or $OomEvents -gt 0) {
            Write-Warning ("$Name ($Service): the kernel killed a process for reaching the memory ceiling (OOMKilled=$OomKilled, oom_kill events=$OomEvents; " +
                "ceiling in force $(Format-CeilingBytes $Parts[3])). Raise $($Prefix)_MEM_LIMIT in $EnvironmentFile and recreate the service. See docs/user/docker-runtime.md, 'Depannage'.")
        }
        if ($PidRefusals -gt 0) {
            Write-Warning ("$Name ($Service): $PidRefusals process or thread creations were refused by the PID ceiling (in force $($Parts[4])). " +
                "Raise $($Prefix)_PIDS_LIMIT in $EnvironmentFile and recreate the service. See docs/user/docker-runtime.md, 'Depannage'.")
        }
    }
}

# Resource-ceiling overrides an operator put in the runtime .env (MINOS_<ROLE>_CPUS|MEM_LIMIT|PIDS_LIMIT)
# survive an Install/update: the .env is regenerated, these lines are carried over. Each value is
# validated first, with the rule of scripts/quality/check-compose-limits.py: memory and PID are real
# ceilings (0, -1 and empty mean "unlimited" to Docker); CPU is 0 (none) or a positive number the
# daemon accepts (whether it exceeds the host's CPU count is the daemon's to say). An invalid value is
# refused here, naming the variable, and is never copied silently into the new .env.
function Test-CeilingOverride([string] $Name, [string] $Value) {
    $Candidate = $Value.Trim().Trim('"').Trim("'")
    if ($Name -like '*_MEM_LIMIT') {
        if ($Candidate -match '^[1-9][0-9]*[kKmMgG]$') { return $true }
        return ($Candidate -match '^[1-9][0-9]{0,17}$' -and [int64]$Candidate -ge 6291456)
    }
    if ($Name -like '*_PIDS_LIMIT') { return ($Candidate -match '^[1-9][0-9]{0,17}$') }
    if ($Candidate -notmatch '^([0-9]+(\.[0-9]+)?|\.[0-9]+)$') { return $false }
    $Cpus = 0.0
    if (-not [double]::TryParse($Candidate, [System.Globalization.NumberStyles]::Float, [System.Globalization.CultureInfo]::InvariantCulture, [ref]$Cpus)) { return $false }
    return ($Cpus -eq 0 -or $Cpus -ge 0.01)
}

function Get-PreservedCeilingOverrides([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $Lines = @(Get-Content -LiteralPath $Path | Where-Object { $_ -match '^MINOS_(MCP|ADMIN|JOB|POSTGRES|OLLAMA)_(CPUS|MEM_LIMIT|PIDS_LIMIT)=' })
    foreach ($Line in $Lines) {
        $Name, $Value = $Line.Split('=', 2)
        if (-not (Test-CeilingOverride -Name $Name -Value $Value)) {
            throw "Invalid value for $Name in ${Path}: '$Value'. A memory or PID ceiling must be a real limit (0, -1 and empty mean unlimited; memory is <n>k, <n>m, <n>g or at least 6291456 bytes; PID a positive integer) and a CPU ceiling 0 (none) or a positive number. Fix or remove the line, then retry; nothing was written."
        }
    }
    return $Lines
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
        $PreservedOverrides = @(Get-PreservedCeilingOverrides -Path $EnvironmentFile)
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
        if ($PreservedOverrides.Count -gt 0) { Add-Content -LiteralPath $EnvironmentFile -Value $PreservedOverrides -Encoding ascii }

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
        Write-Host 'Network   : persistent query/bootstrap/provider-probe none; ephemeral admin has egress and launches no provider'
        Write-Host 'Providers : image-prepared, executable-probed offline, isolated named volume mounted read-only in query/admin planes'
        Write-Host 'Query     : persistent hardened minos-mcp plane; MINOS data read-only; network none'
        Write-Host 'Admin     : ephemeral minos-admin plane; MINOS data writable, projects read-only, egress enabled, no provider launched'
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
        # Exit 3 is a MINOS partial result (some registry entries are unreadable): the output printed above is valid
        # for the entries that could be read, so it is not a failure of the admin command. Every other Compose call
        # (Install, Start, Validate, Uninstall) keeps treating any non-zero code as a failure.
        try { Compose $ComposeArguments -AcceptedExitCodes @(0, 3) }
        catch { Write-AdminCeilingHint; throw }
        if ($LASTEXITCODE -eq 3) {
            Write-Warning 'MINOS exited 3 (partial result): some registry entries are unreadable and were counted, not used. Run `project list` to see them.'
            # The partial result is reported by the warning; do not leave a stale 3 for an in-process caller that reads
            # $LASTEXITCODE after this action (qualify-docker-upgrade.ps1, run-s3.ps1, minos-docker.ps1).
            $global:LASTEXITCODE = 0
        }
    }
    'Status' {
        Require-Installed
        Get-Content -LiteralPath $MetadataFile
        Get-Content -LiteralPath $ProviderInventoryFile
        & docker ps -a --filter "name=^/$ContainerName$"
        Write-CeilingDiagnostics -Project $ComposeProject
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
