[CmdletBinding()]
param()

# Qualifies docker/scripts/remove-docker-instance.ps1 against a simulated Docker CLI: the docker.cmd
# placed first on PATH keeps containers, volumes, networks and images in a JSON state file and refuses
# what the real daemon refuses (removing a volume or a network still used by a container, removing an
# image a container uses). Keep this verifier ASCII-only: Windows PowerShell 5.1 reads a BOM-less
# script as ANSI.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Docker instance removal verification must run on Windows.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Remover = Join-Path $RepoRoot 'docker\scripts\remove-docker-instance.ps1'
if (-not (Test-Path -LiteralPath $Remover -PathType Leaf)) { throw "Docker instance removal script not found: $Remover" }

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw $Message }
}

$FakeDocker = @'
$ErrorActionPreference = 'Stop'
$StatePath = $env:MINOS_FAKE_DOCKER_STATE
$State = Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json
$Argv = @($args | ForEach-Object { [string]$_ })
Add-Content -LiteralPath ($StatePath + '.calls') -Value ($Argv -join ' ') -Encoding ascii

function Save { [System.IO.File]::WriteAllText($StatePath, ($State | ConvertTo-Json -Depth 8), [System.Text.Encoding]::ASCII) }
function Fail([string] $Message) { [Console]::Error.WriteLine($Message); exit 1 }
function Has-Label($Item, [string] $Filter) {
    $Pair = $Filter.Substring(6)
    $Index = $Pair.IndexOf('=')
    $Name = $Pair.Substring(0, $Index); $Value = $Pair.Substring($Index + 1)
    $Property = $Item.labels.PSObject.Properties[$Name]
    return ($null -ne $Property -and [string]$Property.Value -eq $Value)
}
function Filter-Of([string[]] $Arguments) {
    for ($i = 0; $i -lt $Arguments.Count - 1; $i++) { if ($Arguments[$i] -eq '--filter') { return $Arguments[$i + 1] } }
    return ''
}
function Forced([string] $Command) {
    foreach ($Failure in @($State.failures)) { if ([string]$Failure -eq $Command) { return $true } }
    return $false
}

if (-not $State.daemon) { Fail 'Cannot connect to the Docker daemon.' }
$Command = $Argv[0]
if ($Argv.Count -gt 1 -and @('volume', 'network', 'image') -contains $Command) { $Command = $Argv[0] + ' ' + $Argv[1] }
if (Forced $Command) { Fail "forced failure: $Command" }
$Rest = @($Argv | Select-Object -Skip ($Command.Split(' ').Count))
$Operands = @($Rest | Where-Object { -not $_.StartsWith('-') -and -not $_.StartsWith('label=') -and -not $_.StartsWith('reference=') -and -not $_.StartsWith('{{') -and $_ -ne '10' })

switch ($Command) {
    'version' { '27.0.0'; exit 0 }
    'ps' {
        $Filter = Filter-Of $Rest
        foreach ($Container in @($State.containers)) { if (Has-Label $Container $Filter) { $Container.id } }
        exit 0
    }
    'stop' {
        foreach ($Id in $Operands) { foreach ($Container in @($State.containers)) { if ($Container.id -eq $Id) { $Container.running = $false } } }
        Save; exit 0
    }
    'rm' {
        $Force = $Rest -contains '-f'
        foreach ($Id in $Operands) {
            $Container = @($State.containers | Where-Object { $_.id -eq $Id })
            if ($Container.Count -eq 0) { Fail "No such container: $Id" }
            if ($Container[0].running -and -not $Force) { Fail "cannot remove a running container $Id" }
        }
        $State.containers = @($State.containers | Where-Object { $Operands -notcontains $_.id })
        Save; exit 0
    }
    'volume ls' {
        $Filter = Filter-Of $Rest
        foreach ($Volume in @($State.volumes)) { if ($Filter -eq '' -or (Has-Label $Volume $Filter)) { $Volume.name } }
        exit 0
    }
    'volume rm' {
        foreach ($Name in $Operands) {
            if (@($State.containers | Where-Object { @($_.volumes) -contains $Name }).Count -gt 0) { Fail "volume is in use - $Name" }
            if (@($State.volumes | Where-Object { $_.name -eq $Name }).Count -eq 0) { Fail "no such volume: $Name" }
        }
        $State.volumes = @($State.volumes | Where-Object { $Operands -notcontains $_.name })
        Save; exit 0
    }
    'network ls' {
        $Filter = Filter-Of $Rest
        foreach ($Network in @($State.networks)) { if (Has-Label $Network $Filter) { $Network.id } }
        exit 0
    }
    'network rm' {
        foreach ($Id in $Operands) {
            if (@($State.containers | Where-Object { @($_.networks) -contains $Id }).Count -gt 0) { Fail "error while removing network: network $Id has active endpoints" }
        }
        $State.networks = @($State.networks | Where-Object { $Operands -notcontains $_.id })
        Save; exit 0
    }
    'image ls' {
        foreach ($Image in @($State.images)) { if ($Image.ref.StartsWith($Operands[0] + ':')) { $Image.ref } }
        exit 0
    }
    'image rm' {
        foreach ($Ref in $Operands) {
            if (@($State.containers | Where-Object { $_.image -eq $Ref }).Count -gt 0) { Fail "Error response from daemon: conflict: unable to remove repository reference `"$Ref`" - container is using its referenced image" }
        }
        $State.images = @($State.images | Where-Object { $Operands -notcontains $_.ref })
        Save; exit 0
    }
    default { Fail "fake docker: unsupported command $Command" }
}
'@

function New-Labels([string] $Project, [string] $Installation = '') {
    $Labels = [ordered]@{}
    if ($Project -ne '') { $Labels['com.docker.compose.project'] = $Project }
    if ($Installation -ne '') { $Labels['io.minos.installation'] = $Installation }
    return [pscustomobject]$Labels
}

function New-Container([string] $Id, [string] $Project, [bool] $Running, [string[]] $Volumes, [string[]] $Networks, [string] $Image) {
    return [pscustomobject][ordered]@{ id = $Id; labels = (New-Labels $Project); running = $Running; volumes = $Volumes; networks = $Networks; image = $Image }
}

# Instance minos-mcp-prod as the 1.3.0 installer leaves it with PostgreSQL and Ollama managed, next to
# a second instance (other-instance) and an unrelated project that must both survive.
function New-InstanceState {
    $P = 'minos-mcp-prod'
    return [pscustomobject][ordered]@{
        daemon = $true
        failures = @()
        containers = @(
            (New-Container 'c-mcp' $P $true @("${P}_minos-provider-tools") @("${P}_minos-runtime") "minos-code-intelligence:1.3.0-def-query"),
            (New-Container 'c-postgres' $P $true @("$P-postgres-data") @("${P}_minos-runtime") 'fake-postgres:1'),
            (New-Container 'c-ollama' $P $true @("$P-ollama-models") @("${P}_minos-runtime", "${P}_default") 'fake-ollama:1'),
            (New-Container 'c-ollama-bootstrap' $P $false @("$P-ollama-models") @("${P}_default") 'fake-ollama:1'),
            (New-Container 'c-other' 'other-instance' $true @('other-instance-postgres-data') @('other-instance_default') 'minos-code-intelligence:1.2.0-abc-query'),
            (New-Container 'c-unrelated' 'unrelated' $true @('unrelated-data') @('unrelated_default') 'nginx:1')
        )
        volumes = @(
            [pscustomobject]@{ name = "${P}_minos-provider-tools"; labels = (New-Labels $P $P) },
            [pscustomobject]@{ name = "$P-postgres-data"; labels = (New-Labels '' $P) },
            [pscustomobject]@{ name = "$P-ollama-models"; labels = (New-Labels '' '') },
            [pscustomobject]@{ name = 'other-instance-postgres-data'; labels = (New-Labels '' 'other-instance') },
            [pscustomobject]@{ name = 'unrelated-data'; labels = (New-Labels 'unrelated') }
        )
        networks = @(
            [pscustomobject]@{ id = "${P}_default"; labels = (New-Labels $P) },
            [pscustomobject]@{ id = "${P}_minos-runtime"; labels = (New-Labels $P $P) },
            [pscustomobject]@{ id = 'other-instance_default'; labels = (New-Labels 'other-instance') },
            [pscustomobject]@{ id = 'unrelated_default'; labels = (New-Labels 'unrelated') }
        )
        images = @(
            [pscustomobject]@{ ref = 'minos-code-intelligence:1.1.0-aaa' },
            [pscustomobject]@{ ref = 'minos-code-intelligence:1.3.0-def' },
            [pscustomobject]@{ ref = 'minos-code-intelligence:1.3.0-def-query' },
            [pscustomobject]@{ ref = 'minos-code-intelligence:1.2.0-abc-query' },
            [pscustomobject]@{ ref = 'nginx:1' }
        )
    }
}

$Root = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-docker-removal-' + [Guid]::NewGuid())
$PreviousPath = $env:Path
$PreviousState = $env:MINOS_FAKE_DOCKER_STATE
try {
    $Bin = Join-Path $Root 'bin'
    New-Item -ItemType Directory -Force -Path $Bin | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $Bin 'fake-docker.ps1'), $FakeDocker, [System.Text.Encoding]::ASCII)
    $PowerShellExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    [System.IO.File]::WriteAllText((Join-Path $Bin 'docker.cmd'),
        "@`"$PowerShellExe`" -NoProfile -ExecutionPolicy Bypass -File `"%~dp0fake-docker.ps1`" %*`r`n@exit /b %ERRORLEVEL%`r`n",
        [System.Text.Encoding]::ASCII)
    $env:Path = "$Bin;$PreviousPath"

    function Invoke-Scenario([string] $Name, [object] $State, [hashtable] $Parameters) {
        $Directory = Join-Path $Root $Name
        New-Item -ItemType Directory -Force -Path $Directory | Out-Null
        $StatePath = Join-Path $Directory 'docker-state.json'
        [System.IO.File]::WriteAllText($StatePath, ($State | ConvertTo-Json -Depth 8), [System.Text.Encoding]::ASCII)
        $env:MINOS_FAKE_DOCKER_STATE = $StatePath
        $LogPath = Join-Path $Directory 'removal.log'
        $Switches = @()
        foreach ($Key in $Parameters.Keys) { $Switches += "-$Key" }
        # A native stderr line must not abort this verifier under Windows PowerShell 5.1: read the exit code.
        $PreviousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $Output = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $Remover -ComposeProject 'minos-mcp-prod' -LogPath $LogPath @Switches 2>&1 | Out-String
            $ExitCode = $LASTEXITCODE
        }
        finally { $ErrorActionPreference = $PreviousPreference }
        $Log = if (Test-Path -LiteralPath $LogPath -PathType Leaf) { [System.IO.File]::ReadAllText($LogPath) } else { '' }
        return [pscustomobject]@{
            ExitCode = $ExitCode
            Output = $Output
            Log = $Log
            State = (Get-Content -Raw -LiteralPath $StatePath | ConvertFrom-Json)
        }
    }

    function Names([object] $Items, [string] $Property) { return @(@($Items) | ForEach-Object { [string]$_.$Property }) }

    # 1. Purge: every container, network and volume of the instance goes, including the PostgreSQL and
    # Ollama services of the Compose profiles and the external volumes they mount. Nothing of another
    # instance or project is touched.
    $Purge = Invoke-Scenario 'purge' (New-InstanceState) @{ Purge = $true }
    Assert-True ($Purge.ExitCode -eq 0) "Purge removal failed (exit $($Purge.ExitCode)): $($Purge.Output)"
    $Containers = Names $Purge.State.containers 'id'
    Assert-True (-not ($Containers -contains 'c-postgres') -and -not ($Containers -contains 'c-ollama')) 'Purge left the PostgreSQL or Ollama container of the instance.'
    Assert-True (-not ($Containers -contains 'c-mcp') -and -not ($Containers -contains 'c-ollama-bootstrap')) 'Purge left a container of the instance.'
    Assert-True (($Containers -contains 'c-other') -and ($Containers -contains 'c-unrelated')) 'Purge removed a container of another project.'
    $Volumes = Names $Purge.State.volumes 'name'
    foreach ($Volume in @('minos-mcp-prod-postgres-data', 'minos-mcp-prod-ollama-models', 'minos-mcp-prod_minos-provider-tools')) {
        Assert-True (-not ($Volumes -contains $Volume)) "Purge left the volume $Volume."
    }
    Assert-True (($Volumes -contains 'other-instance-postgres-data') -and ($Volumes -contains 'unrelated-data')) 'Purge removed a volume of another project.'
    $Networks = Names $Purge.State.networks 'id'
    Assert-True (-not ($Networks -contains 'minos-mcp-prod_default') -and -not ($Networks -contains 'minos-mcp-prod_minos-runtime')) 'Purge left a network of the instance.'
    Assert-True (($Networks -contains 'other-instance_default') -and ($Networks -contains 'unrelated_default')) 'Purge removed a network of another project.'
    Assert-True ((Names $Purge.State.images 'ref').Count -eq 5) 'Purge without -RemoveImages removed an image.'
    Assert-True ($Purge.Log.Contains('MINOS DOCKER INSTANCE REMOVAL SUCCESS')) 'Purge removal did not log its success.'
    Assert-True ($Purge.Log.Contains('step=volume result=removed name=minos-mcp-prod-postgres-data')) 'Purge removal did not log the PostgreSQL volume removal.'

    # 2. Without purge: the containers of the instance are stopped and removed, its networks go, and
    # every volume (the data) stays.
    $Keep = Invoke-Scenario 'keep' (New-InstanceState) @{}
    Assert-True ($Keep.ExitCode -eq 0) "Removal without purge failed (exit $($Keep.ExitCode)): $($Keep.Output)"
    $Containers = Names $Keep.State.containers 'id'
    Assert-True (-not ($Containers -contains 'c-postgres') -and -not ($Containers -contains 'c-ollama') -and -not ($Containers -contains 'c-mcp')) 'Removal without purge left a container of the instance running.'
    Assert-True ((Names $Keep.State.volumes 'name').Count -eq 5) 'Removal without purge removed a volume.'
    Assert-True (-not ((Names $Keep.State.networks 'id') -contains 'minos-mcp-prod_minos-runtime')) 'Removal without purge left a network of the instance.'
    $Calls = [System.IO.File]::ReadAllText((Join-Path $Root 'keep\docker-state.json.calls'))
    Assert-True (-not $Calls.Contains('volume rm')) 'Removal without purge called docker volume rm.'
    Assert-True ($Calls.Contains('stop --time 10')) 'Removal without purge did not stop the containers gracefully first.'

    # 3. A refused volume removal is reported: non-zero exit, a failed line naming the volume, and the
    # final line says the removal failed. Nothing is masked.
    $FailingState = New-InstanceState
    $FailingState.failures = @('volume rm')
    $Failing = Invoke-Scenario 'failing' $FailingState @{ Purge = $true }
    Assert-True ($Failing.ExitCode -eq 1) "A refused volume removal did not fail the script (exit $($Failing.ExitCode))."
    Assert-True ($Failing.Log.Contains('step=volume result=failed name=minos-mcp-prod-postgres-data')) 'A refused volume removal was not logged with the volume name.'
    Assert-True ($Failing.Log.Contains('MINOS DOCKER INSTANCE REMOVAL FAILED')) 'A refused volume removal did not log the failure verdict.'
    Assert-True (-not $Failing.Log.Contains('MINOS DOCKER INSTANCE REMOVAL SUCCESS')) 'A refused volume removal was logged as a success.'

    # 4. Docker absent or its daemon stopped: a distinct exit code, never a silent success.
    $DownState = New-InstanceState
    $DownState.daemon = $false
    $Down = Invoke-Scenario 'daemon-down' $DownState @{ Purge = $true }
    Assert-True ($Down.ExitCode -eq 3) "An unavailable Docker daemon did not exit with code 3 (exit $($Down.ExitCode))."
    Assert-True ($Down.Log.Contains('step=docker result=unavailable')) 'An unavailable Docker daemon was not logged.'

    # 5. Images: with -RemoveImages, every MINOS image no container uses is removed; an image another
    # instance still runs is kept and logged, which is not a failure; other repositories are untouched.
    $Images = Invoke-Scenario 'images' (New-InstanceState) @{ Purge = $true; RemoveImages = $true }
    Assert-True ($Images.ExitCode -eq 0) "Image removal failed (exit $($Images.ExitCode)): $($Images.Output)"
    $Refs = Names $Images.State.images 'ref'
    foreach ($Ref in @('minos-code-intelligence:1.1.0-aaa', 'minos-code-intelligence:1.3.0-def', 'minos-code-intelligence:1.3.0-def-query')) {
        Assert-True (-not ($Refs -contains $Ref)) "Image removal left $Ref."
    }
    Assert-True ($Refs -contains 'minos-code-intelligence:1.2.0-abc-query') 'Image removal removed an image another instance still uses.'
    Assert-True ($Refs -contains 'nginx:1') 'Image removal removed an image of another repository.'
    Assert-True ($Images.Log.Contains('step=image result=kept-in-use name=minos-code-intelligence:1.2.0-abc-query')) 'An image kept because it is in use was not logged.'

    # 6. An instance with nothing left (a second uninstall, or a native-only machine with Docker): every
    # step says so and the removal succeeds; nothing of the other projects is touched.
    $EmptyState = New-InstanceState
    $EmptyState.containers = @($EmptyState.containers | Where-Object { $_.id -in @('c-other', 'c-unrelated') })
    $EmptyState.volumes = @($EmptyState.volumes | Where-Object { $_.name -in @('other-instance-postgres-data', 'unrelated-data') })
    $EmptyState.networks = @($EmptyState.networks | Where-Object { $_.id -in @('other-instance_default', 'unrelated_default') })
    $EmptyState.images = @($EmptyState.images | Where-Object { $_.ref -eq 'nginx:1' })
    $Empty = Invoke-Scenario 'empty' $EmptyState @{ Purge = $true; RemoveImages = $true }
    Assert-True ($Empty.ExitCode -eq 0) "Removal of an instance with nothing left failed (exit $($Empty.ExitCode)): $($Empty.Output)"
    foreach ($Step in @('container', 'network', 'volume', 'image')) {
        Assert-True ($Empty.Log.Contains("step=$Step result=none")) "Removal of an instance with nothing left did not log step=$Step result=none."
    }
    Assert-True ((Names $Empty.State.containers 'id').Count -eq 2 -and (Names $Empty.State.volumes 'name').Count -eq 2) 'Removal of an empty instance touched another project.'

    # 7. The usual single-network project: one container, one network, one volume. A one-element result
    # must not be unrolled into a string (Set-StrictMode Latest refuses .Count on it).
    $SingleState = New-InstanceState
    $SingleState.containers = @($SingleState.containers | Where-Object { $_.id -in @('c-postgres', 'c-unrelated') })
    $SingleState.containers[0].networks = @('minos-mcp-prod_default')
    $SingleState.volumes = @($SingleState.volumes | Where-Object { $_.name -in @('minos-mcp-prod-postgres-data', 'unrelated-data') })
    $SingleState.networks = @($SingleState.networks | Where-Object { $_.id -in @('minos-mcp-prod_default', 'unrelated_default') })
    $SingleState.images = @($SingleState.images | Where-Object { $_.ref -in @('minos-code-intelligence:1.3.0-def', 'nginx:1') })
    $Single = Invoke-Scenario 'single' $SingleState @{ Purge = $true; RemoveImages = $true }
    Assert-True ($Single.ExitCode -eq 0) "Removal of a single-container instance failed (exit $($Single.ExitCode)): $($Single.Output)"
    foreach ($Line in @('step=container result=removed name=c-postgres', 'step=network result=removed name=minos-mcp-prod_default',
            'step=volume result=removed name=minos-mcp-prod-postgres-data', 'step=image result=removed name=minos-code-intelligence:1.3.0-def')) {
        Assert-True ($Single.Log.Contains($Line)) "Removal of a single-container instance did not log: $Line"
    }
    Assert-True ((Names $Single.State.containers 'id') -contains 'c-unrelated') 'Removal of a single-container instance touched another project.'

    # 8. The script ships ASCII-only, for Windows PowerShell 5.1.
    $Bytes = [System.IO.File]::ReadAllBytes($Remover)
    Assert-True (@($Bytes | Where-Object { $_ -gt 127 }).Count -eq 0) 'remove-docker-instance.ps1 must be ASCII-only.'

    Write-Host 'MINOS DOCKER INSTANCE REMOVAL VERIFICATION SUCCESS' -ForegroundColor Green
}
finally {
    $env:Path = $PreviousPath
    $env:MINOS_FAKE_DOCKER_STATE = $PreviousState
    Remove-Item -LiteralPath $Root -Recurse -Force -ErrorAction SilentlyContinue
}
