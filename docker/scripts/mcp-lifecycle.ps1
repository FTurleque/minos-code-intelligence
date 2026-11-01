[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Install', 'Start', 'Attach', 'Admin', 'Status', 'Validate', 'Stop', 'Uninstall', 'AddProject', 'RemoveProject')]
    [string] $Action,

    [string] $Jar = '',
    [string] $Version = '',
    [string] $Commit = 'unknown',

    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,
    [Parameter(Mandatory = $true)]
    [string] $DataRoot,
    # Accepted for the callers that still pass it, and ignored with a warning: no projects root is mounted. A project
    # is exposed with -Action AddProject.
    [string] $ProjectsRoot = '',

    # AddProject: the host folder of the project to expose. AddProject and RemoveProject: -Name is the mount name,
    # [a-z0-9][a-z0-9._-]{0,62}; AddProject derives it from the folder name when it is omitted.
    [string] $ProjectPath = '',
    [string] $Name = '',

    # Status: adds the host folder of each project mount to the listing.
    [switch] $ShowHostPaths,

    # Where docker/Dockerfile.mcp.release and docker/compose-mcp.prod.yaml are read from for this
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

    # Accepted for the callers that still pass it, and ignored with a warning: there is no projects root to replace.
    [switch] $ReplaceProjectsRoot,

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject = 'minos-mcp-prod'
)

# Portable core of the packaged MINOS Docker MCP lifecycle (build/install/start/validate/stop/
# uninstall). Nothing here is Windows-specific: it only shells out to git/docker/java, all of which
# behave identically under PowerShell 7+ (pwsh) on Windows and Linux, and it never guesses a
# platform default directory - InstallRoot/DataRoot are always supplied by the caller.
#
# Projects: no projects root is mounted. /workspace/projects holds one read-only bind mount per exposed project,
# the same in minos-mcp and minos-admin. The mount table lives in the Docker data root
# (runtime/project-paths.properties) and is written only by com.minos.cli.DockerRuntimeBootstrap; this script
# regenerates the Compose override compose-mcp.projects.yaml from it before every start, admin run and validation,
# and passes that override to every Compose call. AddProject checks the host folder before the bootstrap sees it.
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

# Docker itself is probed further down, just before the action runs: the checks that only read the
# runtime files (Assert-QueryImageConfigured) must be able to refuse before any call to Docker.
$RepoRoot =[System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($SourceRoot)) { $SourceRoot = $RepoRoot }
else { $SourceRoot = [System.IO.Path]::GetFullPath($SourceRoot) }
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)

$RuntimeRoot = Join-Path $InstallRoot 'runtime'
$BackupsRoot = Join-Path $InstallRoot 'backups'
$ComposeFile = Join-Path $RuntimeRoot 'compose-mcp.prod.yaml'
# Generated from the project mount table before every start, admin run and validation; never edited by hand.
$ProjectMountsFile = Join-Path $RuntimeRoot 'compose-mcp.projects.yaml'
$ContainerProjectsRoot = '/workspace/projects'
# The runtime copy was named compose.mcp.prod.yaml until the compose files were renamed so that Dependabot's
# docker-compose ecosystem can see them (audit S11). An installation made before that still holds the old name,
# possibly with the connected profile swapped in by configure-m30-docker-services.ps1: see Move-LegacyRuntimeCompose.
$LegacyComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
$EnvironmentFile = Join-Path $RuntimeRoot '.env'
$MetadataFile = Join-Path $RuntimeRoot 'installation.json'
$ProviderInventoryFile = Join-Path $RuntimeRoot 'provider-inventory.json'
$ProviderChecksumsFile = Join-Path $RuntimeRoot 'provider-binary-sha256.txt'
# Set by Set-QueryImagePlaceholder (Stop, Uninstall) and read by Compose; '' for every other action.
$script:QueryImagePlaceholder = ''

function ConvertTo-DockerPath([string] $Path) {
    return ([System.IO.Path]::GetFullPath($Path)).Replace('\', '/')
}

# Unlike docker\Dockerfile.mcp.release and docker\compose-mcp.prod.yaml -- which are shipped
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
    #
    # AUD-DEP-15: the runtime .env is the ONLY source of the query image. For Compose the process environment
    # wins over --env-file, so an ambient MINOS_QUERY_IMAGE (a leftover of the calling shell) would silently
    # put the query plane on another image than the one the guards checked: it is hidden for the duration of
    # the call and restored afterwards. The only value ever injected is the placeholder of
    # Set-QueryImagePlaceholder, for `stop` and `down`, which use no image.
    $AmbientQueryImage = $env:MINOS_QUERY_IMAGE
    try {
        if ($script:QueryImagePlaceholder -ne '') { $env:MINOS_QUERY_IMAGE = $script:QueryImagePlaceholder }
        else { Remove-Item -LiteralPath Env:MINOS_QUERY_IMAGE -ErrorAction SilentlyContinue }
        $ComposeFiles = @(Get-ComposeFileArguments)
        $null | & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile @ComposeFiles @Arguments
        if ($LASTEXITCODE -notin $AcceptedExitCodes) { throw "docker compose failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
    }
    finally {
        if ($null -eq $AmbientQueryImage) { Remove-Item -LiteralPath Env:MINOS_QUERY_IMAGE -ErrorAction SilentlyContinue }
        else { $env:MINOS_QUERY_IMAGE = $AmbientQueryImage }
    }
}

# The -f arguments of every Compose call: the runtime compose file, then the generated project mounts override
# when it exists.
function Get-ComposeFileArguments {
    $Files = @('-f', $ComposeFile)
    if (Test-Path -LiteralPath $ProjectMountsFile -PathType Leaf) { $Files += @('-f', $ProjectMountsFile) }
    return , $Files
}

# A YAML scalar for any string: a JSON string (YAML is a superset of JSON) escaping the quote, the backslash, every
# control character and every non-ASCII character, so that no folder name can end the scalar or inject a key. Compose
# interpolates ${...} in every string of every file it reads: '$' is doubled, so that the mounted folder is exactly
# the one that was checked.
function ConvertTo-YamlString([string] $Value) {
    $Builder = New-Object System.Text.StringBuilder
    [void] $Builder.Append('"')
    foreach ($Character in $Value.ToCharArray()) {
        $Code = [int] $Character
        if ($Character -eq '"') { [void] $Builder.Append('\"') }
        elseif ($Character -eq '\') { [void] $Builder.Append('\\') }
        elseif ($Character -eq '$') { [void] $Builder.Append('$$') }
        elseif ($Code -lt 32 -or $Code -gt 126) { [void] $Builder.Append(('\u{0:x4}' -f $Code)) }
        else { [void] $Builder.Append($Character) }
    }
    [void] $Builder.Append('"')
    return $Builder.ToString()
}

# The text of the project mounts override for $Mounts (objects with Name and HostRoot): one read-only bind mount per
# project, the same list for minos-mcp and minos-admin, the host folder never created by Docker.
function Format-ProjectMountsOverride([object[]] $Mounts) {
    $Lines = @(
        '# Generated by mcp-lifecycle.ps1 from the MINOS project mount table. Do not edit: it is rewritten before',
        '# every start, admin run and validation. Expose a project with -Action AddProject.')
    if (@($Mounts).Count -eq 0) {
        $Lines += 'services: {}'
        return ($Lines -join "`n") + "`n"
    }
    $Lines += 'services:'
    foreach ($Service in @('minos-mcp', 'minos-admin')) {
        $Lines += "  ${Service}:"
        $Lines += '    volumes:'
        foreach ($Mount in @($Mounts)) {
            $Target = ConvertTo-YamlString ($ContainerProjectsRoot + '/' + [string] $Mount.Name)
            $Source = ConvertTo-YamlString ([string] $Mount.HostRoot)
            $Lines += ('      - {"type": "bind", "source": ' + $Source + ', "target": ' + $Target +
                ', "read_only": true, "bind": {"create_host_path": false}}')
        }
    }
    return ($Lines -join "`n") + "`n"
}

function Write-ProjectMountsOverride([object[]] $Mounts) {
    $Text = Format-ProjectMountsOverride -Mounts $Mounts
    [System.IO.File]::WriteAllText($ProjectMountsFile, $Text, (New-Object System.Text.UTF8Encoding $false))
}

# Runs one subcommand of com.minos.cli.DockerRuntimeBootstrap and returns its standard output. The bootstrap runs in
# minos-bootstrap (no network); -InAdmin runs the same entry point in minos-admin, the only plane that reaches a
# managed PostgreSQL. A non-zero exit fails with the bootstrap's own message, which names no host path.
function Invoke-ProjectMountBootstrap([string[]] $Arguments, [switch] $InAdmin) {
    if ($InAdmin) {
        $RunArguments = @('run', '-T', '--rm', '--no-deps', '--entrypoint', 'java', 'minos-admin',
            '-cp', '/opt/minos/minos.jar', 'com.minos.cli.DockerRuntimeBootstrap') + $Arguments
    }
    else {
        $RunArguments = @('run', '-T', '--rm', '--no-deps', 'minos-bootstrap') + $Arguments
    }
    $AmbientQueryImage = $env:MINOS_QUERY_IMAGE
    try {
        Remove-Item -LiteralPath Env:MINOS_QUERY_IMAGE -ErrorAction SilentlyContinue
        $ComposeFiles = @(Get-ComposeFileArguments)
        $Output = ($null | & docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile @ComposeFiles @RunArguments | Out-String)
        if ($LASTEXITCODE -ne 0) { throw "MINOS Docker project mount command failed (exit $LASTEXITCODE): $($Arguments[0])" }
    }
    finally {
        if ($null -eq $AmbientQueryImage) { Remove-Item -LiteralPath Env:MINOS_QUERY_IMAGE -ErrorAction SilentlyContinue }
        else { $env:MINOS_QUERY_IMAGE = $AmbientQueryImage }
    }
    return $Output
}

# The saved project mapping as list-project-mounts prints it: formatVersion 0 (none), 1 (a single projects root,
# not migrated yet) or 2 (the mount table).
function Get-ProjectMountTable {
    $Table = ConvertFrom-BootstrapJson (Invoke-ProjectMountBootstrap -Arguments @('list-project-mounts', '--format', 'json'))
    if ($null -eq $Table.PSObject.Properties['formatVersion'] -or $null -eq $Table.PSObject.Properties['mounts']) {
        throw 'The MINOS Docker bootstrap printed an unreadable project mount table; nothing was mounted.'
    }
    return $Table
}

# The JSON document a bootstrap subcommand printed: its last line that opens an object. Fails closed without one.
function ConvertFrom-BootstrapJson([string] $Output) {
    $Line = @($Output -split "`r?`n" | Where-Object { $_.TrimStart().StartsWith('{') }) | Select-Object -Last 1
    if ($null -eq $Line) { throw 'The MINOS Docker bootstrap printed no project mount table; nothing was mounted.' }
    return ($Line | ConvertFrom-Json)
}

# True when the MINOS data root selects the PostgreSQL storage backend (config/minos.properties).
function Test-PostgresqlStorage {
    $Settings = Join-Path (Join-Path $DataRoot 'config') 'minos.properties'
    if (-not (Test-Path -LiteralPath $Settings -PathType Leaf)) { return $false }
    foreach ($Line in @(Get-Content -LiteralPath $Settings)) {
        if ($Line -match '^\s*minos\.storage\.backend\s*[=:]\s*postgresql\s*$') { return $true }
    }
    return $false
}

# Each mount of the table with whether its host folder still exists. A mount whose folder is gone is reported, not
# mounted: Compose would otherwise refuse to start minos-mcp for one deleted folder.
function Get-ProjectMountStates {
    $States = @()
    if (Test-ComposeMountsProjectsRoot $ComposeFile) { return , $States }
    $Table = Get-ProjectMountTable
    if ([int] $Table.formatVersion -ne 2) { return , $States }
    foreach ($Mount in @($Table.mounts)) {
        $States += [pscustomobject]@{
            Name = [string] $Mount.name
            HostRoot = [string] $Mount.hostRoot
            Available = (Test-Path -LiteralPath ([string] $Mount.hostRoot) -PathType Container)
        }
    }
    return , $States
}

# Whether a compose file mounts one projects root (MINOS_PROJECTS_DIR). Only the recipe of a release that predates
# the project mounts does: the Docker A -> B upgrade qualification installs such a release with its own recipe, and
# its runtime keeps running as that release left it until the next Install, which migrates it.
function Test-ComposeMountsProjectsRoot([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    return ((Get-Content -Raw -LiteralPath $Path) -match '\$\{MINOS_PROJECTS_DIR\b')
}

# Regenerates the override from the table: written empty first, so that a stale or hand-edited override never
# takes part in the Compose call that reads the table. A runtime that still mounts one projects root has no table
# and gets an empty override.
function Update-ProjectMountsOverride {
    Write-ProjectMountsOverride -Mounts @()
    if (Test-ComposeMountsProjectsRoot $ComposeFile) { return , @() }
    $States = Get-ProjectMountStates
    $Mounted = @()
    foreach ($State in $States) {
        if (-not $State.Available) {
            Write-Warning "MINOS Docker project mount '$($State.Name)' is not mounted: its host folder no longer exists. Its projects are reported rootAvailable: false; expose the folder again, or remove the mount with -Action RemoveProject -Name $($State.Name)."
            continue
        }
        # The folder may have changed since it was exposed (a link checked out in its place, for example): the host
        # check of AddProject is replayed before every mount.
        $Refusal = Get-ProjectFolderRefusal $State.HostRoot
        if ($Refusal -ne '') {
            Write-Warning "MINOS Docker project mount '$($State.Name)' is not mounted: $Refusal. Its projects are reported rootAvailable: false; remove the mount with -Action RemoveProject -Name $($State.Name)."
            continue
        }
        $Mounted += $State
    }
    Write-ProjectMountsOverride -Mounts $Mounted
    return , $States
}

function Get-UnsafeDockerProjectsRootReason(
    [string] $ProjectsRoot, [string] $UserProfile, [string[]] $ProtectedRoots, [string] $TemporaryRoot) {
    # A project folder is bind-mounted into the MINOS containers. Returns why it must be refused, or an empty
    # string when it may be mounted. The reason carries no path. A strict subfolder of the temporary folder is
    # not refused for lying inside a protected folder that holds the temporary folder (application data, where
    # qualification runs place their folders); it is refused when it lies inside or contains any other one.
    # Windows paths compare without case; the separator is the platform's, so the rule holds on Linux too.
    $Separator = [string] [System.IO.Path]::DirectorySeparatorChar
    function ConvertTo-ComparableRoot([string] $Path) {
        $Full = [System.IO.Path]::GetFullPath($Path)
        if ($Full.Length -gt 1) { $Full = $Full.TrimEnd([char[]] @('\', '/')) }
        return $Full
    }
    function Test-SameOrInside([string] $Child, [string] $Parent) {
        $Prefix = $Parent
        if (-not $Prefix.EndsWith($Separator)) { $Prefix += $Separator }
        return $Child.Equals($Parent, [System.StringComparison]::OrdinalIgnoreCase) -or
            $Child.StartsWith($Prefix, [System.StringComparison]::OrdinalIgnoreCase)
    }
    if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { return 'empty' }
    if ([string]::IsNullOrWhiteSpace($UserProfile)) { return 'not verifiable because the user profile is unknown' }
    $Candidate = ConvertTo-ComparableRoot $ProjectsRoot
    $VolumeRoot = [System.IO.Path]::GetPathRoot($Candidate)
    if ([string]::IsNullOrEmpty($VolumeRoot) -or $Candidate.Length -le $VolumeRoot.TrimEnd([char[]] @('\', '/')).Length) {
        return 'a volume root'
    }
    if (Test-SameOrInside (ConvertTo-ComparableRoot $UserProfile) $Candidate) {
        return 'the user profile or one of its parents'
    }
    $Temporary = ''
    if (-not [string]::IsNullOrWhiteSpace($TemporaryRoot)) {
        $Temporary = ConvertTo-ComparableRoot $TemporaryRoot
        if (-not $Candidate.StartsWith($Temporary + $Separator, [System.StringComparison]::OrdinalIgnoreCase)) { $Temporary = '' }
    }
    foreach ($Protected in @($ProtectedRoots)) {
        if ([string]::IsNullOrWhiteSpace($Protected)) { continue }
        $ProtectedRoot = ConvertTo-ComparableRoot $Protected
        $HoldsTemporary = ($Temporary -ne '') -and (Test-SameOrInside $Temporary $ProtectedRoot)
        $Inside = (Test-SameOrInside $Candidate $ProtectedRoot) -and -not $HoldsTemporary
        if ($Inside -or (Test-SameOrInside $ProtectedRoot $Candidate)) {
            return 'an application data or MINOS folder, one of its parents or one of its subfolders'
        }
    }
    return ''
}

# True when $Path, or one of its existing parents, is a reparse point (junction, symbolic link). Docker Desktop does
# not follow them, and a link would expose another folder than the one named.
function Test-ReparsePointInPath([string] $Path) {
    $Current = [System.IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrEmpty($Current)) {
        if (Test-Path -LiteralPath $Current) {
            $Item = Get-Item -LiteralPath $Current -Force
            if (($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { return $true }
        }
        $Parent = [System.IO.Path]::GetDirectoryName($Current)
        if ($Parent -eq $Current) { break }
        $Current = $Parent
    }
    return $false
}

# Why the host folder $Folder must not be exposed, or '' when it may be. Checked on the host, before the bootstrap
# sees it. The reason carries no path.
function Get-ProjectFolderRefusal([string] $Folder) {
    if ([string]::IsNullOrWhiteSpace($Folder)) { return 'no folder was given' }
    if (-not [System.IO.Path]::IsPathRooted($Folder)) { return 'the folder is not an absolute path' }
    if ($Folder.Contains('$')) { return 'the folder path contains a dollar sign, which Docker Compose would interpret' }
    if (-not (Test-Path -LiteralPath $Folder -PathType Container)) { return 'the folder does not exist' }
    if (Test-ReparsePointInPath $Folder) {
        return 'the folder or one of its parents is a junction or a symbolic link; give the real folder'
    }
    $Unsafe = Get-UnsafeDockerProjectsRootReason -ProjectsRoot $Folder `
        -UserProfile ([Environment]::GetFolderPath('UserProfile')) `
        -TemporaryRoot ([System.IO.Path]::GetTempPath()) `
        -ProtectedRoots @([Environment]::GetFolderPath('LocalApplicationData'), [Environment]::GetFolderPath('ApplicationData'),
            $InstallRoot, $DataRoot)
    if ($Unsafe -ne '') { return "the folder is $Unsafe" }
    return ''
}

# The default mount name of a folder, as the bootstrap validates it: the folder name in lower case, every character
# outside [a-z0-9._-] replaced by '-'; '' when the result is not a valid name.
function Get-DefaultProjectMountName([string] $Folder) {
    $Leaf = [System.IO.Path]::GetFileName([System.IO.Path]::GetFullPath($Folder).TrimEnd([char[]] @('\', '/')))
    $Candidate = [regex]::Replace($Leaf.ToLowerInvariant(), '[^a-z0-9._-]', '-')
    if ($Candidate -cmatch '^[a-z0-9][a-z0-9._-]{0,62}$') { return $Candidate }
    return ''
}

# Migration of a runtime that mounted one projects root (mapping format 1): one mount per registered project on its
# current container path, so that no registry entry is rewritten. The plan is computed first, each folder is checked
# on the host, then the table is written with the refused folders skipped. An unreadable registry entry refuses the
# migration before anything is written; the caller's runtime snapshot is then restored.
function Invoke-ProjectMountMigration {
    $InAdmin = Test-PostgresqlStorage
    $Plan = ConvertFrom-BootstrapJson (Invoke-ProjectMountBootstrap -Arguments @('migrate-project-mounts', '--dry-run') -InAdmin:$InAdmin)
    $Arguments = @('migrate-project-mounts')
    foreach ($Mount in @($Plan.mounts)) {
        $Refusal = Get-ProjectFolderRefusal ([string] $Mount.hostRoot)
        if ($Refusal -ne '') {
            Write-Warning "Project mount '$($Mount.name)' is not created by the migration: $Refusal."
            $Arguments += @('--skip', [string] $Mount.path)
        }
    }
    $Result = ConvertFrom-BootstrapJson (Invoke-ProjectMountBootstrap -Arguments $Arguments -InAdmin:$InAdmin)
    foreach ($Skipped in @($Result.skipped)) {
        $Why = switch ([string] $Skipped.reason) {
            'ROOT' { 'it is registered at the old projects root itself, which is no longer mounted' }
            'DETACHED' { 'it is detached from every projects root' }
            default { 'its folder is refused' }
        }
        Write-Warning "Project '$($Skipped.project)' is not mounted after the migration: $Why. It stays registered with its index (rootAvailable: false)."
    }
    Write-Host "MINOS Docker projects root migrated to $(@($Result.mounts).Count) project mount(s); a folder of the old root that was not registered is no longer visible to the containers." -ForegroundColor Cyan
}

# Resource ceilings (docker/compose-mcp.*.yaml, x-limits-<role>; defaults in docker/.env.example). Hitting
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
        $ComposeFiles = @(Get-ComposeFileArguments)
        $Json = (& docker compose --project-directory $RuntimeRoot --env-file $EnvironmentFile @ComposeFiles config --format json 2>$null | Out-String)
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

# Where the compose recipe of $Root (a checkout, a distribution, or the worktree of a previous release) lives.
# Current trees ship compose-mcp.prod.yaml only. A tree older than the rename ships compose.mcp.prod.yaml: the Docker
# A -> B upgrade qualification (scripts/ci/qualify-docker-upgrade.ps1) installs the PREVIOUS release with its own
# recipe, and must keep doing so. When both exist, the new name wins.
function Resolve-ComposeSource([string] $Root) {
    foreach ($Name in @('compose-mcp.prod.yaml', 'compose.mcp.prod.yaml')) {
        $Candidate = Join-Path $Root (Join-Path 'docker' $Name)
        if (Test-Path -LiteralPath $Candidate -PathType Leaf) { return $Candidate }
    }
    throw "No MINOS Docker compose recipe under $Root\docker (expected compose-mcp.prod.yaml)."
}

# Upgrade of an existing installation: the runtime directory (outside the installed program tree, so never replaced by
# update-installation.ps1) may still hold the pre-rename file. Nothing persisted names the compose file (.env,
# installation.json, .docker-mcp-managed carry the project, container and image, not the file), and the Compose
# project is fixed by `name: ${MINOS_COMPOSE_PROJECT}`: renaming the file keeps the containers and volumes. So the old
# file is MOVED to the new name, content intact (it may be the connected profile). If both exist the new name wins
# (it is the one every action runs) and the old one is left alone, with a warning, rather than guessed at.
function Move-LegacyRuntimeCompose {
    if (-not (Test-Path -LiteralPath $LegacyComposeFile -PathType Leaf)) { return }
    if (Test-Path -LiteralPath $ComposeFile -PathType Leaf) {
        Write-Warning "Both $ComposeFile and the pre-rename $LegacyComposeFile exist; $ComposeFile is the one used. Remove the old file once you have checked it is not needed."
        return
    }
    Move-Item -LiteralPath $LegacyComposeFile -Destination $ComposeFile
    Write-Host "Migrated the runtime compose file to its new name: $ComposeFile" -ForegroundColor Cyan
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

# AUD-DEP-15: a release builds TWO images from docker/Dockerfile.mcp.release. The administration image
# (target `admin`, MINOS_IMAGE: indexing, provider tools, provider probe) is the provider-complete image; the
# query image (target `query`, MINOS_QUERY_IMAGE: the persistent minos-mcp plane and the jobs that only need
# java or sh) is a JRE and the MINOS jar, with no compiler and no provider tool.
#
# The targets are read from the Dockerfile that is about to be built, not assumed: the Docker A -> B upgrade
# qualification installs the PREVIOUS release with its own recipe (-SourceRoot), and that recipe may predate
# the split. Such a recipe has neither target and builds one image, as it always did. A recipe that names only
# one of the two targets is refused: guessing which image it meant could put the query plane on the wrong one.
function Get-ReleaseImageTargets([string] $DockerfilePath) {
    $Stages = @(Get-Content -LiteralPath $DockerfilePath | ForEach-Object {
            if ($_ -match '^\s*FROM\s+(?:--platform=\S+\s+)?\S+\s+AS\s+([\w.-]+)\s*$') { $Matches[1].ToLowerInvariant() }
        })
    $HasAdmin = $Stages -contains 'admin'
    $HasQuery = $Stages -contains 'query'
    if ($HasAdmin -and $HasQuery) { return @('admin', 'query') }
    if ($HasAdmin -or $HasQuery) {
        throw 'The MINOS Docker release recipe is inconsistent: Dockerfile.mcp.release must define both the admin and the query targets, or neither (a release with a single image).'
    }
    return @()
}

# The query image is named after the administration image: same repository, tag suffixed with -query.
function Resolve-QueryImage([string] $AdministrationImage) {
    return "$AdministrationImage-query"
}

# The value of a variable of the runtime .env, '' when the file or the variable is absent. The last
# definition wins, as for Compose; surrounding quotes are not part of the value.
function Read-EnvironmentValue([string] $Path, [string] $Name) {
    $Value = ''
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $Value }
    $Pattern = '^\s*' + [regex]::Escape($Name) + '\s*=(.*)$'
    foreach ($Line in @(Get-Content -LiteralPath $Path)) {
        if ($Line -match $Pattern) { $Value = $Matches[1].Trim().Trim('"').Trim("'").Trim() }
    }
    return $Value
}

# Whether a compose file gives a service the query image. The runtime copy of a release that had a single
# image does not: that installation keeps running as that release left it until the next Install.
function Test-ComposeUsesQueryImage([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    foreach ($Line in @(Get-Content -LiteralPath $Path)) {
        if ($Line -match '^\s*image:\s*["'']?\$\{MINOS_QUERY_IMAGE\b') { return $true }
    }
    return $false
}

# Fail-closed guard of the two-image contract. It reads two files and calls nothing: when the runtime compose
# file asks for the query image and the runtime .env does not define it (program files updated, or the
# connected profile copied, over a runtime that was installed with a single image), the action is refused
# before any call to Docker. The query plane NEVER falls back to the administration image. The message names
# the repair and carries no path.
function Assert-QueryImageConfigured {
    if (-not (Test-ComposeUsesQueryImage $ComposeFile)) { return }
    if (-not [string]::IsNullOrWhiteSpace((Read-EnvironmentValue -Path $EnvironmentFile -Name 'MINOS_QUERY_IMAGE'))) { return }
    throw ('This MINOS Docker installation is not migrated to the separate query image: MINOS_QUERY_IMAGE is missing or empty ' +
        'in the runtime .env, and the MCP query plane never falls back to the administration image. No container was started ' +
        'and nothing was changed. Repair: run the MINOS installer again, or prod-mcp-release.ps1 -Action Install -Jar <minos.jar> ' +
        '-Version <version>; both build the two images and keep the MINOS data.')
}

# Stop and Uninstall start nothing, so a runtime that is not migrated stays stoppable and removable: Compose
# refuses to interpolate a file whose required variable is unset, even for `stop` and `down`, which never use
# the image. A placeholder that names no image satisfies the interpolation for those two actions only.
function Set-QueryImagePlaceholder {
    if (-not (Test-ComposeUsesQueryImage $ComposeFile)) { return }
    if (-not [string]::IsNullOrWhiteSpace((Read-EnvironmentValue -Path $EnvironmentFile -Name 'MINOS_QUERY_IMAGE'))) { return }
    # Handed to Compose by the Compose function only, for the duration of its call: it never reaches the
    # environment of the caller (this script is also called in-process, several actions in a row).
    $script:QueryImagePlaceholder = 'minos-query-image-not-configured'
}

# Second half of the same contract, for the actions that (re)create the query plane: the image the runtime
# .env names for it must BE a query image (label io.minos.image.plane=query, written by the `query` target).
# A MINOS_QUERY_IMAGE pointed by hand at the administration image, or at an image that no longer exists, is
# refused here: one read-only `docker image inspect`, nothing started.
function Assert-QueryImagePlane {
    if (-not (Test-ComposeUsesQueryImage $ComposeFile)) { return }
    $QueryImageName = Read-EnvironmentValue -Path $EnvironmentFile -Name 'MINOS_QUERY_IMAGE'
    if ((Get-ImagePlane $QueryImageName) -eq 'query') { return }
    throw ('The image named by MINOS_QUERY_IMAGE in the runtime .env is missing or is not a MINOS query image ' +
        '(label io.minos.image.plane=query); the MCP query plane never runs on the administration image. No container was ' +
        'started. Repair: run the MINOS installer again, or prod-mcp-release.ps1 -Action Install -Jar <minos.jar> -Version <version>.')
}

# The io.minos.image.plane label of a local image, '' when the image or the label is absent.
function Get-ImagePlane([string] $Image) {
    if ([string]::IsNullOrWhiteSpace($Image)) { return '' }
    $Inspect = Invoke-DockerAllowFailure -Arguments @('image', 'inspect', $Image)
    if ($Inspect.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace([string]$Inspect.Output)) { return '' }
    try { $Images = @(([string]$Inspect.Output) | ConvertFrom-Json) }
    catch { return '' }
    if ($Images.Count -eq 0 -or $null -eq $Images[0].Config -or $null -eq $Images[0].Config.Labels) { return '' }
    $PlaneLabel = $Images[0].Config.Labels.PSObject.Properties['io.minos.image.plane']
    if ($null -eq $PlaneLabel) { return '' }
    return [string]$PlaneLabel.Value
}

# $ExpectedPlane: 'admin' or 'query' for the two images of a current release; '' for the single image of a
# release that predates the split (no plane label, provider-complete by definition).
function Test-ExactImage([string] $Image, [string] $ExpectedVersion, [string] $ExpectedCommit, [string] $ExpectedPlane = '') {
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
    $PlaneLabel = $Labels.PSObject.Properties['io.minos.image.plane']
    if ($null -eq $VersionLabel -or $null -eq $RevisionLabel -or
        [string]$VersionLabel.Value -ne $ExpectedVersion -or
        [string]$RevisionLabel.Value -ne $ExpectedCommit) { return $false }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedPlane)) {
        # An image of the other plane under the expected name is not "the exact image": it is rebuilt.
        if ($null -eq $PlaneLabel -or [string]$PlaneLabel.Value -ne $ExpectedPlane) { return $false }
        if ($ExpectedPlane -eq 'query') { return $true }
    }
    return $null -ne $PreparedLabel -and [string]$PreparedLabel.Value -eq 'true'
}

Move-LegacyRuntimeCompose

# The recipe this action runs: the one about to be installed, or the installed runtime copy.
$RecipeMountsProjectsRoot = $false
if ($Action -eq 'Install') { $RecipeMountsProjectsRoot = Test-ComposeMountsProjectsRoot (Resolve-ComposeSource $SourceRoot) }
else { $RecipeMountsProjectsRoot = Test-ComposeMountsProjectsRoot $ComposeFile }
if (-not $RecipeMountsProjectsRoot -and -not [string]::IsNullOrWhiteSpace($ProjectsRoot)) {
    Write-Warning 'The -ProjectsRoot parameter is ignored: the MINOS Docker runtime mounts no projects root, only the projects exposed with -Action AddProject.'
}
if (-not $RecipeMountsProjectsRoot -and $ReplaceProjectsRoot) {
    Write-Warning 'The -ReplaceProjectsRoot switch is ignored: the MINOS Docker runtime has no projects root to replace.'
}
if ($RecipeMountsProjectsRoot -and $Action -in @('AddProject', 'RemoveProject')) {
    throw 'This MINOS Docker runtime still mounts one projects root: run -Action Install of this release first, which migrates it to one mount per registered project. Nothing was changed.'
}

# Before any call to Docker (see Assert-QueryImageConfigured). Stop, Status, Attach and Uninstall are not
# guarded: they start nothing, and a runtime that is not migrated must remain stoppable and removable.
if ($Action -in @('Start', 'Validate', 'Admin', 'AddProject', 'RemoveProject')) { Assert-QueryImageConfigured }

$DockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if (-not $DockerCommand) { throw 'Docker is required.' }
& docker version --format '{{.Server.Version}}' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Docker does not respond.' }

switch ($Action) {
    'Install' {
        if ([string]::IsNullOrWhiteSpace($Jar)) { throw '-Jar is required for Install. Use the shaded JAR from the same MINOS release.' }
        $Jar = (Resolve-Path -LiteralPath $Jar).Path
        if ([string]::IsNullOrWhiteSpace($Version)) { throw '-Version is required for Install.' }
        if ($RecipeMountsProjectsRoot) {
            # Only the recipe of a release that predates the project mounts gets here (see Test-ComposeMountsProjectsRoot).
            if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { throw 'This release recipe mounts one projects root: -ProjectsRoot is required.' }
            $ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
            if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) { throw 'The projects root of this release recipe does not exist.' }
            $MappingFile = Join-Path (Join-Path $DataRoot 'runtime') 'project-paths.properties'
            if ((Test-Path -LiteralPath $MappingFile -PathType Leaf) -and
                (@(Get-Content -LiteralPath $MappingFile) -match '^\s*formatVersion\s*[=:]\s*2\s*$').Count -gt 0) {
                throw 'This release recipe mounts one projects root, and this MINOS Docker data root exposes one mount per project: it is refused, so that no projects root is mounted again. Nothing was changed.'
            }
        }
        $ResolvedSemanticProvider = Resolve-SemanticProvider $SemanticProvider

        New-Item -ItemType Directory -Force -Path $RuntimeRoot, $DataRoot, $BackupsRoot | Out-Null
        if (Test-Path -LiteralPath $MetadataFile) {
            $Backup = Join-Path $BackupsRoot ([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))
            New-Item -ItemType Directory -Force -Path $Backup | Out-Null
            if (Test-Path -LiteralPath $RuntimeRoot) { Copy-Item -LiteralPath $RuntimeRoot -Destination (Join-Path $Backup 'runtime') -Recurse }
        }

        $Timestamp = [DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')
        $Image = Resolve-Image -RequestedTag $ImageTag -RequestedVersion $Version -RequestedCommit $Commit
        $Dockerfile = Join-Path $SourceRoot 'docker\Dockerfile.mcp.release'
        # AUD-DEP-15: the images this recipe builds (see Get-ReleaseImageTargets). $QueryImage stays '' for the
        # recipe of a release that had a single image; MINOS_QUERY_IMAGE is then not written, and that
        # release's compose file does not ask for it.
        $ImageTargets = @(Get-ReleaseImageTargets $Dockerfile)
        $QueryImage = ''
        $Builds = @()
        if ($ImageTargets.Count -eq 0) {
            if (-not (Test-ExactImage -Image $Image -ExpectedVersion $Version -ExpectedCommit $Commit)) {
                $Builds += [pscustomobject]@{ Target = ''; Image = $Image }
            }
        }
        else {
            $QueryImage = Resolve-QueryImage $Image
            if (-not (Test-ExactImage -Image $Image -ExpectedVersion $Version -ExpectedCommit $Commit -ExpectedPlane 'admin')) {
                $Builds += [pscustomobject]@{ Target = 'admin'; Image = $Image }
            }
            if (-not (Test-ExactImage -Image $QueryImage -ExpectedVersion $Version -ExpectedCommit $Commit -ExpectedPlane 'query')) {
                $Builds += [pscustomobject]@{ Target = 'query'; Image = $QueryImage }
            }
        }
        # The Dockerfile and the compose file of one release agree on the number of images; a recipe where
        # they do not is refused before anything is built or written.
        if ((Test-ComposeUsesQueryImage (Resolve-ComposeSource $SourceRoot)) -ne ($QueryImage -ne '')) {
            throw 'The MINOS Docker release recipe is inconsistent: its compose file and its Dockerfile.mcp.release do not agree on the separate query image. Nothing was built or changed.'
        }
        if ($Builds.Count -eq 0) {
            Write-Host "MINOS exact Docker image already exists; skipping provider-complete rebuild: $Image" -ForegroundColor Cyan
            if ($QueryImage -ne '') { Write-Host "MINOS exact Docker query image already exists: $QueryImage" -ForegroundColor Cyan }
        }
        else {
            $BuildContext = Join-Path $RuntimeRoot 'build'
            Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue
            New-Item -ItemType Directory -Force -Path $BuildContext | Out-Null
            Copy-Item -LiteralPath $Jar -Destination (Join-Path $BuildContext 'minos.jar')
            Copy-JarResourceEntry -JarPath $Jar -EntryName 'com/minos/adapter/scip/runtime/scip-typescript-package-lock.json' -Destination (Join-Path $BuildContext 'scip-typescript-package-lock.json')
            Copy-JarResourceEntry -JarPath $Jar -EntryName 'com/minos/adapter/scip/runtime/scip-python-package-lock.json' -Destination (Join-Path $BuildContext 'scip-python-package-lock.json')
            # Every image is built BEFORE the runtime compose file and .env are replaced below: a candidate
            # that cannot be built leaves the installed runtime exactly as it was.
            foreach ($Build in $Builds) {
                # --network=host: BuildKit's default isolated build network has shown
                # reproducible indefinite hangs against certain external hosts (observed
                # against github.com release-asset redirects) with no timeout to recover.
                # The host network stack reaches the same URLs reliably; this only affects
                # the build-time RUN steps, not the resulting image's own network config.
                $BuildArguments = @('--network=host', '--file', $Dockerfile, '--tag', $Build.Image)
                if ($Build.Target -ne '') { $BuildArguments += @('--target', $Build.Target) }
                $BuildArguments += @(
                    '--build-arg', "MINOS_VERSION=$Version",
                    '--build-arg', "MINOS_GIT_COMMIT=$Commit",
                    '--build-arg', "MINOS_BUILD_TIMESTAMP=$Timestamp",
                    $BuildContext)
                & docker build @BuildArguments
                if ($LASTEXITCODE -ne 0) { throw "MINOS Docker image build failed: $($Build.Image)" }
            }
            Remove-Item -LiteralPath $BuildContext -Recurse -Force -ErrorAction SilentlyContinue
        }

        Copy-Item -LiteralPath (Resolve-ComposeSource $SourceRoot) -Destination $ComposeFile -Force
        Remove-Item -LiteralPath $LegacyComposeFile -Force -ErrorAction SilentlyContinue
        $PreservedOverrides = @(Get-PreservedCeilingOverrides -Path $EnvironmentFile)
        @"
MINOS_COMPOSE_PROJECT=$ComposeProject
MINOS_CONTAINER_NAME=$ContainerName
MINOS_IMAGE=$Image
MINOS_DATA_DIR="$(ConvertTo-DockerPath $DataRoot)"
MINOS_VERSION=$Version
MINOS_GIT_COMMIT=$Commit
MINOS_SEMANTIC_PROVIDER=$ResolvedSemanticProvider
"@ | Set-Content -LiteralPath $EnvironmentFile -Encoding ascii
        # Written only by a recipe that has the query target: an installation made with a single image
        # receives the variable here, at the Install that follows the update of its program files.
        if ($QueryImage -ne '') { Add-Content -LiteralPath $EnvironmentFile -Value "MINOS_QUERY_IMAGE=$QueryImage" -Encoding ascii }
        if ($RecipeMountsProjectsRoot) {
            Add-Content -LiteralPath $EnvironmentFile -Encoding ascii -Value @(
                "MINOS_PROJECTS_DIR=`"$(ConvertTo-DockerPath $ProjectsRoot)`"",
                "MINOS_HOST_PROJECTS_ROOT=`"$(ConvertTo-DockerPath $ProjectsRoot)`"")
        }
        if ($PreservedOverrides.Count -gt 0) { Add-Content -LiteralPath $EnvironmentFile -Value $PreservedOverrides -Encoding ascii }

        # No project is mounted until the table is read below: a previous override is replaced by an empty one.
        Write-ProjectMountsOverride -Mounts @()
        Compose @('config', '--quiet')
        Assert-DockerJavaRuntime -Image $Image -Failure 'The MINOS Docker image does not expose a valid Java runtime.'
        if ($QueryImage -ne '') {
            Assert-DockerJavaRuntime -Image $QueryImage -Failure 'The MINOS Docker query image does not expose a valid Java runtime.'
        }
        Read-ImageFile -Image $Image -Path '/opt/minos/provider-evidence/provider-inventory.json' | Set-Content -LiteralPath $ProviderInventoryFile -Encoding utf8
        Read-ImageFile -Image $Image -Path '/opt/minos/provider-evidence/binary-sha256.txt' | Set-Content -LiteralPath $ProviderChecksumsFile -Encoding ascii

        Compose @('run', '--rm', '--no-deps', 'minos-data-bootstrap')
        Initialize-And-VerifyProviderTools
        # A runtime that mounted one projects root (format 1) migrates to one mount per registered project; any other
        # runtime gets its mount table from the composed bootstrap command (init-project-mounts).
        if ($RecipeMountsProjectsRoot) { Compose @('run', '--rm', '--no-deps', 'minos-bootstrap') }
        elseif ([int] (Get-ProjectMountTable).formatVersion -eq 1) { Invoke-ProjectMountMigration }
        else { Compose @('run', '--rm', '--no-deps', 'minos-bootstrap') }
        $MountStates = Update-ProjectMountsOverride
        Compose @('run', '--rm', '--no-deps', 'minos-admin', '--help')

        [ordered]@{
            formatVersion = 6
            installedAt = $Timestamp
            image = $Image
            # Additive (AUD-DEP-15): '' when the recipe has a single image. Read with PSObject.Properties:
            # the metadata of an installation made before the split has no such field.
            queryImage = $QueryImage
            version = $Version
            gitCommit = $Commit
            dataRoot = $DataRoot
            # Names only: the host folder of a mount is listed by -Action Status -ShowHostPaths.
            projectMounts = [ordered]@{ containerRoot = $ContainerProjectsRoot; count = @($MountStates).Count; names = @($MountStates | ForEach-Object { $_.Name }) }
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
        if ($QueryImage -ne '') { Write-Host "Query img : $QueryImage (JRE and MINOS jar only)" }
        Write-Host "Data      : $DataRoot -> /var/lib/minos"
        Write-Host "Projects  : $(@($MountStates).Count) exposed under $ContainerProjectsRoot (read-only, one mount per project); expose one with -Action AddProject -ProjectPath <folder>"
        Write-Host "Semantic  : $ResolvedSemanticProvider"
        Write-Host 'Network   : persistent query/bootstrap/provider-probe none; ephemeral admin has egress and launches no provider'
        Write-Host 'Providers : image-prepared, executable-probed offline, isolated named volume mounted read-only in query/admin planes'
        Write-Host 'Query     : persistent hardened minos-mcp plane; MINOS data read-only; network none'
        Write-Host 'Admin     : ephemeral minos-admin plane; MINOS data writable, projects read-only, egress enabled, no provider launched'
    }
    'Start' {
        Require-Installed
        Assert-QueryImagePlane
        if (-not (Test-ComposeUsesQueryImage $ComposeFile)) {
            Write-Warning 'This MINOS Docker runtime was installed by a release with a single image: the MCP query plane still runs on the provider-complete image. Run the MINOS installer again (or prod-mcp-release.ps1 -Action Install) to give it the separate query image.'
        }
        $null = Update-ProjectMountsOverride
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
        $null = Update-ProjectMountsOverride
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
        # Mounts by name; a host folder is printed only on request, for the user of this machine.
        $Mounts = @(Get-ProjectMountStates)
        Write-Host "Project mounts ($($Mounts.Count) of 64) under ${ContainerProjectsRoot}:"
        foreach ($Mount in $Mounts) {
            $State = 'mounted'
            if (-not $Mount.Available) { $State = 'not mounted: host folder missing' }
            $Line = "  $($Mount.Name) ($State)"
            if ($ShowHostPaths) { $Line += " <- $($Mount.HostRoot)" }
            Write-Host $Line
        }
        & docker ps -a --filter "name=^/$ContainerName$"
        Write-CeilingDiagnostics -Project $ComposeProject
    }
    'Validate' {
        Require-Installed
        $null = Update-ProjectMountsOverride
        Compose @('config', '--quiet')
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        Assert-DockerJavaRuntime -Image $Metadata.image -Failure 'MINOS Docker validation failed.'
        if (Test-ComposeUsesQueryImage $ComposeFile) {
            Assert-QueryImagePlane
            Assert-DockerJavaRuntime -Image (Read-EnvironmentValue -Path $EnvironmentFile -Name 'MINOS_QUERY_IMAGE') -Failure 'MINOS Docker query image validation failed.'
        }
        Compose @('run', '--rm', '--no-deps', 'minos-data-bootstrap')
        Initialize-And-VerifyProviderTools
        Compose @('run', '--rm', '--no-deps', 'minos-bootstrap')
        Compose @('run', '--rm', '--no-deps', 'minos-admin', '--help')
        Write-Host 'MINOS Docker query/admin/provider configuration validated.' -ForegroundColor Green
    }
    'AddProject' {
        Require-Installed
        # Every check happens on the host and before any write: the bootstrap only revalidates what it can see.
        $Refusal = Get-ProjectFolderRefusal $ProjectPath
        if ($Refusal -ne '') { throw "This folder cannot be exposed to the MINOS Docker runtime: $Refusal. Nothing was changed." }
        $Folder = [System.IO.Path]::GetFullPath($ProjectPath)
        $MountName = $Name
        if ([string]::IsNullOrWhiteSpace($MountName)) { $MountName = Get-DefaultProjectMountName $Folder }
        if ($MountName -cnotmatch '^[a-z0-9][a-z0-9._-]{0,62}$') {
            throw 'No valid mount name: pass -Name with [a-z0-9][a-z0-9._-]{0,62} (lower case, digits, dot, dash, underscore). Nothing was changed.'
        }
        Assert-QueryImagePlane
        $null = Update-ProjectMountsOverride
        $null = Invoke-ProjectMountBootstrap -Arguments @('add-project-mount', $MountName, (ConvertTo-DockerPath $Folder))
        $null = Update-ProjectMountsOverride
        Write-Host 'Recreating minos-mcp to mount the project: attached MCP sessions are interrupted and must reconnect.' -ForegroundColor Cyan
        Compose @('up', '-d', '--force-recreate', 'minos-mcp')
        Compose @('run', '--rm', '--no-deps', 'minos-admin', 'project', 'add', "$ContainerProjectsRoot/$MountName")
        Write-Host "MINOS Docker project exposed: $MountName -> $ContainerProjectsRoot/$MountName (read-only)." -ForegroundColor Green
    }
    'RemoveProject' {
        Require-Installed
        if ([string]::IsNullOrWhiteSpace($Name)) { throw '-Name is required for RemoveProject (see -Action Status).' }
        Assert-QueryImagePlane
        $null = Update-ProjectMountsOverride
        # An unknown name is refused by the bootstrap before anything is written or recreated.
        Write-Host (Invoke-ProjectMountBootstrap -Arguments @('remove-project-mount', $Name) -InAdmin:(Test-PostgresqlStorage)).TrimEnd()
        $null = Update-ProjectMountsOverride
        Write-Host 'Recreating minos-mcp to unmount the project: attached MCP sessions are interrupted and must reconnect.' -ForegroundColor Cyan
        Compose @('up', '-d', '--force-recreate', 'minos-mcp')
        Write-Host "MINOS Docker project mount removed: $Name. Its projects stay registered with their index (rootAvailable: false)." -ForegroundColor Green
    }
    'Stop' {
        Require-Installed
        Set-QueryImagePlaceholder
        Compose @('stop', '--timeout', '10', 'minos-mcp')
        Write-Host 'MINOS Docker MCP query plane stopped.' -ForegroundColor Green
    }
    'Uninstall' {
        Require-Installed
        $Metadata = Get-Content -Raw -LiteralPath $MetadataFile | ConvertFrom-Json
        $ManagedImages = @([string] $Metadata.image)
        $QueryImageProperty = $Metadata.PSObject.Properties['queryImage']
        if ($null -ne $QueryImageProperty) { $ManagedImages += [string] $QueryImageProperty.Value }
        Set-QueryImagePlaceholder
        # Compose only acts on the services of the active profiles: without these two, the PostgreSQL and
        # Ollama services of the connected profile keep running and their network cannot be removed. Their
        # data volumes are external, so --volumes never removes them.
        Compose @('--profile', 'postgresql', '--profile', 'ollama', 'down', '--timeout', '10', '--remove-orphans', '--volumes')
        foreach ($ManagedImage in $ManagedImages) {
            if ([string]::IsNullOrWhiteSpace($ManagedImage)) { continue }
            $ImageRemoval = Invoke-DockerAllowFailure -Arguments @('image', 'rm', $ManagedImage)
            if ($ImageRemoval.ExitCode -ne 0) { Write-Warning "MINOS Docker containers/provider volume were removed, but image '$ManagedImage' could not be removed: $($ImageRemoval.Output)" }
        }
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
        Write-Host 'MINOS Docker MCP/admin/provider runtime configuration removed.' -ForegroundColor Green
        Write-Host "Persistent data preserved: $DataRoot"
    }
}
