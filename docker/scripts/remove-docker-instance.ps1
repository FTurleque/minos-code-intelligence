[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $ComposeProject,

    # Also removes the volumes of the instance: the MINOS data. Only on the user's explicit purge.
    [switch] $Purge,

    # Also removes every image of $ImageRepository that no container uses.
    [switch] $RemoveImages,

    [ValidatePattern('^[a-z0-9][a-z0-9._/-]*$')]
    [string] $ImageRepository = 'minos-code-intelligence',

    [string] $LogPath = ''
)

# Removes what the MINOS installer created in Docker for one instance, whatever state its runtime
# folder is in: it finds the resources by label, not through the Compose files. The containers of
# the Compose project (the PostgreSQL and Ollama services of the Compose profiles included) are
# stopped then removed, and so are its networks. With -Purge, its volumes go too: the volumes Compose
# created for the project and the external volumes the installer created for the instance.
#
# Every Docker call is checked. Each step writes one line "MINOS-DOCKER-REMOVAL step=... result=..."
# to the output and to -LogPath; the last line is the verdict. Exit codes: 0 everything requested was
# removed, 1 at least one removal failed, 3 Docker is absent or its daemon does not respond.
# Lines carry Docker object names only, never a host path.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:Failed = $false

function Write-Step([string] $Line) {
    $Text = 'MINOS-DOCKER-REMOVAL ' + $Line
    [Console]::Out.WriteLine($Text)
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        [System.IO.File]::AppendAllText($LogPath, $Text + "`r`n", [System.Text.Encoding]::ASCII)
    }
}

# First line of Docker's own message, printable ASCII only, bounded: enough to diagnose, never a dump.
function Format-Detail([string] $Output) {
    $First = @(([string]$Output) -split "`r?`n" | Where-Object { $_.Trim() -ne '' } | Select-Object -First 1)
    if ($First.Count -eq 0) { return '' }
    $Clean = ($First[0] -replace '[^\x20-\x7E]', '?').Trim()
    if ($Clean.Length -gt 200) { $Clean = $Clean.Substring(0, 200) }
    return $Clean
}

# Windows PowerShell 5.1 turns a native stderr line into an error record, which ErrorActionPreference
# Stop would make terminating before the exit code can be read.
function Invoke-Docker([string[]] $Arguments) {
    $Previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $Lines = @(& $script:Docker @Arguments 2>&1 | ForEach-Object { [string]$_ })
        $ExitCode = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $Previous }
    return [pscustomobject]@{ ExitCode = $ExitCode; Lines = $Lines; Output = ($Lines -join "`n") }
}

# Names or ids printed one per line by a successful listing; $null when the listing itself failed.
function Get-Listing([string] $Step, [string[]] $Arguments) {
    $Result = Invoke-Docker $Arguments
    if ($Result.ExitCode -ne 0) {
        $script:Failed = $true
        Write-Step ("step=$Step result=failed exit=$($Result.ExitCode) detail=" + (Format-Detail $Result.Output))
        return $null
    }
    return @($Result.Lines | ForEach-Object { $_.Trim() } | Where-Object { $_ -ne '' } | Sort-Object -Unique)
}

function Get-LabelledObjects([string] $Kind, [string[]] $Labels) {
    $Names = @()
    foreach ($Label in $Labels) {
        $Listing = Get-Listing "$Kind-list" @($Kind, 'ls', '-q', '--filter', "label=$Label=$ComposeProject")
        if ($null -eq $Listing) { continue }
        $Names += $Listing
    }
    return @($Names | Sort-Object -Unique)
}

function Remove-Object([string] $Step, [string[]] $Command, [string] $Name) {
    $Result = Invoke-Docker ($Command + @($Name))
    if ($Result.ExitCode -eq 0) {
        Write-Step "step=$Step result=removed name=$Name"
        return
    }
    $script:Failed = $true
    Write-Step ("step=$Step result=failed name=$Name exit=$($Result.ExitCode) detail=" + (Format-Detail $Result.Output))
}

if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
    $LogPath = [System.IO.Path]::GetFullPath($LogPath)
    $LogDirectory = Split-Path -Parent $LogPath
    if (-not (Test-Path -LiteralPath $LogDirectory -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $LogDirectory | Out-Null
    }
}

Write-Step ("step=start project=$ComposeProject purge=" + [bool]$Purge + ' images=' + [bool]$RemoveImages)

$DockerCommand = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
if ($null -eq $DockerCommand) {
    Write-Step 'step=docker result=unavailable detail=docker-not-found'
    Write-Step 'MINOS DOCKER INSTANCE REMOVAL UNAVAILABLE'
    exit 3
}
$script:Docker = $DockerCommand.Source
$Version = Invoke-Docker @('version', '--format', '{{.Server.Version}}')
if ($Version.ExitCode -ne 0) {
    Write-Step ('step=docker result=unavailable detail=' + (Format-Detail $Version.Output))
    Write-Step 'MINOS DOCKER INSTANCE REMOVAL UNAVAILABLE'
    exit 3
}

# 1. Containers of the Compose project, every profile included: a graceful stop first (PostgreSQL
# flushes its data), then a forced removal of whatever remains.
$Containers = Get-Listing 'container-list' @('ps', '-a', '-q', '--filter', "label=com.docker.compose.project=$ComposeProject")
if ($null -ne $Containers -and $Containers.Count -gt 0) {
    $Stop = Invoke-Docker (@('stop', '--time', '10') + $Containers)
    if ($Stop.ExitCode -ne 0) {
        Write-Step ("step=container-stop result=incomplete exit=$($Stop.ExitCode) detail=" + (Format-Detail $Stop.Output))
    }
    foreach ($Container in $Containers) { Remove-Object 'container' @('rm', '-f') $Container }
}
elseif ($null -ne $Containers) {
    Write-Step 'step=container result=none'
}

# 2. Networks of the project (Compose labels them; the installer's own label too).
$Networks = Get-LabelledObjects 'network' @('com.docker.compose.project', 'io.minos.installation')
if ($Networks.Count -eq 0) { Write-Step 'step=network result=none' }
foreach ($Network in $Networks) { Remove-Object 'network' @('network', 'rm') $Network }

# 3. Volumes, on purge only: the project's Compose volumes, the volumes labelled for the instance and
# the two external volumes the installer names after it (PostgreSQL data and Ollama models).
if ($Purge) {
    $Volumes = @(Get-LabelledObjects 'volume' @('com.docker.compose.project', 'io.minos.installation'))
    $AllVolumes = Get-Listing 'volume-list' @('volume', 'ls', '-q')
    if ($null -ne $AllVolumes) {
        foreach ($Named in @("$ComposeProject-postgres-data", "$ComposeProject-ollama-models")) {
            if ($AllVolumes -contains $Named) { $Volumes += $Named }
        }
    }
    if ($Volumes.Count -eq 0) { Write-Step 'step=volume result=none' }
    foreach ($Volume in @($Volumes | Sort-Object -Unique)) { Remove-Object 'volume' @('volume', 'rm') $Volume }
}
else {
    Write-Step 'step=volume result=kept reason=no-purge'
}

# 4. Images, on request: every tag of the MINOS repository. An image a container still uses (another
# instance) is kept and said so; that is not a failure.
if ($RemoveImages) {
    $Images = Get-Listing 'image-list' @('image', 'ls', $ImageRepository, '--format', '{{.Repository}}:{{.Tag}}')
    if ($null -ne $Images) {
        $Images = @($Images | Where-Object { $_.StartsWith($ImageRepository + ':') -and -not $_.EndsWith(':<none>') })
        if ($Images.Count -eq 0) { Write-Step 'step=image result=none' }
        foreach ($Image in $Images) {
            $Result = Invoke-Docker @('image', 'rm', $Image)
            if ($Result.ExitCode -eq 0) { Write-Step "step=image result=removed name=$Image"; continue }
            if ($Result.Output -match 'conflict|is being used|is using its referenced image') {
                Write-Step "step=image result=kept-in-use name=$Image"
                continue
            }
            $script:Failed = $true
            Write-Step ("step=image result=failed name=$Image exit=$($Result.ExitCode) detail=" + (Format-Detail $Result.Output))
        }
    }
}

if ($script:Failed) {
    Write-Step 'MINOS DOCKER INSTANCE REMOVAL FAILED'
    exit 1
}
Write-Step 'MINOS DOCKER INSTANCE REMOVAL SUCCESS'
exit 0
