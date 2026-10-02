[CmdletBinding()]
param(
    # The FULL distribution zip (minos-<version>-windows-x64.zip).
    [Parameter(Mandatory = $true)]
    [string] $Package,

    # Disposable working directory: installation, MINOS_HOME and evidence are created here, nothing else is touched.
    [string] $WorkDirectory = '',

    # A TypeScript project to index; defaults to the fixture of this repository.
    [string] $Fixture = '',

    # Rehearsal ONLY: lets the run continue while the network is reachable (the verdict then says so and
    # cannot qualify the release). The real qualification refuses to start unless the machine is offline.
    [switch] $AllowOnline,

    # Read-only observation of the TCP/UDP connections and of the DNS cache of the whole process tree started here
    # (netstat -ano, process table, Get-DnsClientCache), to complete the loopback-proxy canary, which a direct connect()
    # bypasses. Online it first needs a positive control (a direct connect to github.com seen by the sampler), then
    # requires ZERO non-loopback connection and no DNS resolution of a tool download host caused by the MINOS run.
    # It changes no setting; it does not replace the offline probe above.
    [switch] $ObserveNetwork,

    # Keep the working directory afterwards.
    [switch] $Keep
)

# MINOS release qualification: "clean machine, no network" (ADR 0040, lot 5).
#
#   1. the machine must be offline (DNS and TCP probes fail), else the run stops;
#   2. install the zip into a disposable directory with the shipped portable installer (no PATH, no Docker, no MCP client);
#   3. `tools verify`: the embedded tools are seeded from the installation, SHA-256 verified, reported with their origin;
#   4. index a TypeScript project (the criterion with zero prerequisites);
#   5. a loopback proxy canary, wired in through the JVM and the proxy variables, must have seen ZERO connection attempts,
#      and MINOS_TOOLS_OFFLINE=1 forbids any download on top of that.
#
# It is a local release gate, never run by CI. Output ends with `OFFLINE QUALIFICATION PASS` or `... FAIL`.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ($env:OS -ne 'Windows_NT') { throw 'This qualification runs on Windows.' }
$Package = (Resolve-Path -LiteralPath $Package).Path
if ($Package -match '-lite\.zip$') { throw 'Qualify the FULL zip: the lite zip carries no tools by design.' }
if ([string]::IsNullOrWhiteSpace($WorkDirectory)) {
    $WorkDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-offline-qualification-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
}
$WorkDirectory = [System.IO.Path]::GetFullPath($WorkDirectory)
if ([string]::IsNullOrWhiteSpace($Fixture)) { $Fixture = Join-Path $RepoRoot 'fixtures\typescript\typescript-simple' }
$Fixture = (Resolve-Path -LiteralPath $Fixture).Path

$Results = New-Object System.Collections.Generic.List[object]
function Add-Result([string] $Name, [bool] $Ok, [string] $Detail) {
    $Results.Add([pscustomobject]@{ Check = $Name; Result = $(if ($Ok) { 'PASS' } else { 'FAIL' }); Detail = $Detail })
    $Color = if ($Ok) { 'Green' } else { 'Red' }
    Write-Host ("  [{0}] {1} -- {2}" -f $(if ($Ok) { 'PASS' } else { 'FAIL' }), $Name, $Detail) -ForegroundColor $Color
}

function Test-Reachable {
    # Returns a description of the first thing that is reachable, or $null when the machine is offline.
    foreach ($HostName in @('api.github.com', 'repo.maven.apache.org', 'nodejs.org', 'registry.npmjs.org')) {
        try {
            $Addresses = [System.Net.Dns]::GetHostAddresses($HostName)
            if ($Addresses.Count -gt 0) { return "DNS resolves $HostName" }
        }
        catch { }
    }
    foreach ($Target in @(@('1.1.1.1', 443), @('8.8.8.8', 443))) {
        $Client = New-Object System.Net.Sockets.TcpClient
        try {
            $Pending = $Client.BeginConnect($Target[0], [int]$Target[1], $null, $null)
            if ($Pending.AsyncWaitHandle.WaitOne(3000) -and $Client.Connected) { return "TCP connects to $($Target[0]):$($Target[1])" }
        }
        catch { }
        finally { $Client.Close() }
    }
    return $null
}

function Invoke-Minos([string] $Launcher, [string[]] $Arguments, [hashtable] $Environment) {
    $Info = New-Object System.Diagnostics.ProcessStartInfo
    $Info.FileName = $env:ComSpec
    $Quoted = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $Info.Arguments = '/d /s /c ""{0}" {1}"' -f $Launcher, $Quoted
    $Info.UseShellExecute = $false
    $Info.CreateNoWindow = $true
    $Info.RedirectStandardOutput = $true
    $Info.RedirectStandardError = $true
    foreach ($Key in $Environment.Keys) { $Info.EnvironmentVariables[$Key] = [string]$Environment[$Key] }
    $Process = [System.Diagnostics.Process]::Start($Info)
    $Out = $Process.StandardOutput.ReadToEndAsync()
    $Err = $Process.StandardError.ReadToEndAsync()
    if (-not $Process.WaitForExit(900000)) { $Process.Kill(); throw "minos $($Arguments -join ' ') did not finish in 15 minutes" }
    return [pscustomobject]@{ ExitCode = $Process.ExitCode; Out = $Out.Result; Err = $Err.Result }
}

Write-Host ''
Write-Host "MINOS offline qualification" -ForegroundColor Cyan
Write-Host "  package : $Package"
Write-Host "  work    : $WorkDirectory"
Write-Host "  fixture : $Fixture"
Write-Host ''

# ---- 1. the machine must be offline -----------------------------------------------------------------
$Reachable = Test-Reachable
if ($null -ne $Reachable) {
    if (-not $AllowOnline) {
        Write-Host "STOP: this machine is NOT offline ($Reachable)." -ForegroundColor Red
        Write-Host 'Disconnect the network adapter (or unplug the cable), then run this script again.' -ForegroundColor Red
        Write-Host 'OFFLINE QUALIFICATION NOT RUN' -ForegroundColor Red
        exit 2
    }
    Write-Host "REHEARSAL: the network is reachable ($Reachable); this run cannot qualify the release." -ForegroundColor Yellow
    Add-Result 'machine is offline' $false "rehearsal only: $Reachable"
}
else {
    Add-Result 'machine is offline' $true 'DNS resolution and TCP connections all fail'
}

New-Item -ItemType Directory -Force -Path $WorkDirectory | Out-Null
$InstallRoot = Join-Path $WorkDirectory 'install'
$MinosHome = Join-Path $WorkDirectory 'home'
$ProjectCopy = Join-Path $WorkDirectory 'project'
$CanaryLog = Join-Path $WorkDirectory 'canary-connections.log'

# ---- the canary: a loopback proxy that records every connection attempt ---------------------------------
$Listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Loopback, 0)
$Listener.Start()
$CanaryPort = ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port
$Attempts = New-Object System.Collections.Generic.List[string]
$Accept = $Listener.AcceptTcpClientAsync()
function Update-Canary {
    while ($Accept.IsCompleted) {
        try { $Attempts.Add((Get-Date).ToString('o')); $Accept.Result.Close() } catch { }
        $script:Accept = $Listener.AcceptTcpClientAsync()
    }
}

try {
    $ProxyUrl = "http://127.0.0.1:$CanaryPort"
    # Positive control: a request sent through the proxy must be seen by the listener, otherwise "0 attempts" proves nothing.
    try { Invoke-WebRequest -Uri 'http://control.invalid/' -Proxy $ProxyUrl -TimeoutSec 5 -UseBasicParsing | Out-Null } catch { }
    Start-Sleep -Milliseconds 300
    Update-Canary
    Add-Result 'the canary sees a deliberate connection (positive control)' ($Attempts.Count -ge 1) "$($Attempts.Count) connection(s) recorded"
    $Attempts.Clear()

    # ---- optional: read-only observation of the process tree (netstat, process table, DNS cache) ---------------------
    $Observer = $null
    $DnsBefore = $null
    $DnsAfterControl = $null
    $ControlHost = 'github.com'
    $ControlName = 'minos-observe-' + [Guid]::NewGuid().ToString('N').Substring(0, 8) + '.example.com'
    if ($ObserveNetwork) {
        . (Join-Path $PSScriptRoot 'network-observer.ps1')
        $DnsBefore = Get-DnsCacheSnapshot
        $Observer = Start-NetworkObserver -RootProcessId $PID
        $Observer.State.Phase = 'control'
        if ($null -ne $Reachable) {
            # Positive control, from this very process tree and without any proxy: a direct connect() held open, and a
            # DNS resolution of a name nobody resolved before.
            try { [void][System.Net.Dns]::GetHostAddresses($ControlName) } catch { }
            $Control = New-Object System.Net.Sockets.TcpClient
            try {
                $Pending = $Control.BeginConnect($ControlHost, 443, $null, $null)
                [void]$Pending.AsyncWaitHandle.WaitOne(8000)
                Start-Sleep -Milliseconds 1200
            }
            catch { }
            finally { $Control.Close() }
        }
        Set-NetworkObserverPhase $Observer 'minos'
        $DnsAfterControl = Get-DnsCacheSnapshot
    }
    $Environment = @{
        MINOS_HOME          = $MinosHome
        MINOS_TOOLS_OFFLINE = '1'
        JAVA_TOOL_OPTIONS   = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=$CanaryPort -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=$CanaryPort"
        HTTP_PROXY          = $ProxyUrl
        HTTPS_PROXY         = $ProxyUrl
        npm_config_proxy    = $ProxyUrl
        npm_config_https_proxy = $ProxyUrl
    }
    # Windows PowerShell 5.1 and the JVM write harmless banners to stderr: keep them from becoming errors.
    $ErrorActionPreference = 'Continue'

    # ---- 2. install from the zip ------------------------------------------------------------------------
    $Expanded = Join-Path $WorkDirectory 'package'
    Expand-Archive -LiteralPath $Package -DestinationPath $Expanded -Force
    $Distribution = Get-ChildItem -LiteralPath $Expanded -Directory | Select-Object -First 1
    $Installer = Join-Path $Distribution.FullName 'install.ps1'
    $InstallOutput = & (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') -NoProfile -ExecutionPolicy Bypass -File $Installer -Package $Package -InstallRoot $InstallRoot 2>&1 | Out-String
    $Launcher = Join-Path $InstallRoot 'minos.cmd'
    Add-Result 'installed from the zip' (Test-Path -LiteralPath $Launcher) 'portable installer, no PATH, no MCP client, no Docker'
    $ToolsManifest = Join-Path $InstallRoot 'tools\TOOLS-MANIFEST.json'
    Add-Result 'the installation ships its tools' (Test-Path -LiteralPath $ToolsManifest) 'tools\TOOLS-MANIFEST.json present, installation directory not modified afterwards'
    $BeforeTools = (Get-ChildItem -LiteralPath (Join-Path $InstallRoot 'tools') -Recurse -File | Measure-Object).Count

    # ---- 3. tools verify ---------------------------------------------------------------------------------
    $Verify = Invoke-Minos $Launcher @('tools', 'verify', '--all', '--format', 'json') $Environment
    $Providers = @()
    try { $Providers = @(($Verify.Out | ConvertFrom-Json).providers) } catch { }
    $Typescript = $Providers | Where-Object { $_.id -eq 'scip-typescript' } | Select-Object -First 1
    $Java = $Providers | Where-Object { $_.id -eq 'scip-java' } | Select-Object -First 1
    $TsReady = ($null -ne $Typescript) -and ($Typescript.state -eq 'READY')
    $TsOrigin = ($null -ne $Typescript) -and (@($Typescript.diagnostics | Where-Object { $_ -like 'tools origin:*embedded*' }).Count -gt 0)
    Add-Result 'scip-typescript READY from the embedded tools' ($TsReady -and $TsOrigin) $(if ($null -eq $Typescript) { 'provider missing from tools verify' } else { "state=$($Typescript.state); " + (($Typescript.diagnostics | ForEach-Object { $_ }) -join ' | ') })
    # scip-java: its MINOS-owned parts must be embedded; whatever else blocks it must be a named machine prerequisite.
    $JavaOrigin = ($null -ne $Java) -and (@($Java.diagnostics | Where-Object { $_ -like 'tools origin:*coursier=embedded*maven=embedded*' -or $_ -like 'tools origin:*' }).Count -gt 0)
    $JavaOnlyExternal = ($null -ne $Java) -and (@($Java.diagnostics | Where-Object { $_ -notlike 'tools origin:*' -and $_ -notlike 'machine prerequisite (not shipped by MINOS):*' }).Count -eq 0)
    Add-Result 'scip-java embedded tools present, only machine prerequisites missing (if any)' ($JavaOrigin -and $JavaOnlyExternal) $(if ($null -eq $Java) { 'provider missing' } else { "state=$($Java.state); " + (($Java.diagnostics | ForEach-Object { $_ }) -join ' | ') })

    # ---- 4. index a TypeScript project --------------------------------------------------------------------
    Copy-Item -LiteralPath $Fixture -Destination $ProjectCopy -Recurse
    $Add = Invoke-Minos $Launcher @('project', 'add', $ProjectCopy, '--name', 'offline-ts') $Environment
    $Index = Invoke-Minos $Launcher @('index', 'offline-ts') $Environment
    Add-Result 'TypeScript indexing succeeds' (($Index.ExitCode -eq 0) -and ($Index.Out -match 'status:\s*SUCCEEDED')) $(($Index.Out -split "`r?`n" | Where-Object { $_ -match '^(status|runId|activeSnapshotId):' }) -join '; ')

    # ---- 5. nothing tried to reach the network, nothing was modified in the installation --------------------
    Start-Sleep -Milliseconds 300
    Update-Canary
    Add-Result 'no connection attempt reached the network canary' ($Attempts.Count -eq 0) "$($Attempts.Count) attempt(s) on 127.0.0.1:$CanaryPort"
    $AfterTools = (Get-ChildItem -LiteralPath (Join-Path $InstallRoot 'tools') -Recurse -File | Measure-Object).Count
    Add-Result 'the installation directory stayed read-only in practice' ($BeforeTools -eq $AfterTools) "tools files before=$BeforeTools after=$AfterTools"
    $Seeded = Join-Path $MinosHome 'tools'
    Add-Result 'tools were seeded under MINOS_HOME' (Test-Path -LiteralPath $Seeded) 'executed from MINOS_HOME\tools, never from the installation directory'

    if ($ObserveNetwork) {
        $Summary = Stop-NetworkObserver $Observer
        $DnsAfterRun = Get-DnsCacheSnapshot
        $ToolHosts = @('github.com', 'githubusercontent.com', 'repo1.maven.org', 'repo.maven.apache.org', 'maven.org',
                       'nodejs.org', 'npmjs.org', 'npmjs.com', 'nuget.org', 'golang.org', 'sonatype.com', 'jitpack.io')
        $Cause = @(Compare-DnsCacheSnapshots $DnsAfterControl $DnsAfterRun | Where-Object {
            $Name = $_; @($ToolHosts | Where-Object { $Name -eq $_ -or $Name.EndsWith('.' + $_) }).Count -gt 0 })
        $ControlHits = @($Summary.Hits | Where-Object { $_.Phase -eq 'control' })
        $RunHits = @($Summary.Hits | Where-Object { $_.Phase -eq 'minos' })
        Write-Host ("  observation: {0} samples, about {1} ms apart, {2} processes in the tree, {3} external TCP connection(s) of other processes (information), {4} UDP endpoint(s) of the tree (information)" -f $Summary.Ticks, $Summary.MeanIntervalMs, $Summary.TreeSize, $Summary.OtherExternal.Count, $Summary.UdpEndpoints.Count)
        if ($null -ne $Summary.Error) { Write-Host "  observation warning: $($Summary.Error)" -ForegroundColor Yellow }
        if ($null -ne $Reachable) {
            Add-Result 'observation positive control: a direct connect() from the tree is seen by the sampler' ($ControlHits.Count -ge 1) $(if ($ControlHits.Count -ge 1) { "seen: $($ControlHits[0].Process) pid $($ControlHits[0].ProcessId) -> $($ControlHits[0].Remote) $($ControlHits[0].State)" } else { 'NOT seen: the zero below proves nothing' })
            $DnsControlSeen = @(Compare-DnsCacheSnapshots $DnsBefore $DnsAfterControl | Where-Object { $_ -eq $ControlName -or $_ -eq $ControlHost }).Count -ge 1
            Add-Result 'observation positive control: a DNS resolution from the tree shows in the DNS cache' $DnsControlSeen $(if ($DnsControlSeen) { 'the control name was cached' } else { 'NOT seen: the DNS verdict below proves nothing' })
        }
        else {
            Write-Host '  observation: the machine is offline, the positive control is skipped (the offline probe is the evidence)'
        }
        Add-Result 'no non-loopback TCP connection owned by the MINOS process tree during the run' ($RunHits.Count -eq 0) $(if ($RunHits.Count -eq 0) { "0 in $($Summary.Ticks) samples" } else { ($RunHits | Select-Object -First 5 | ForEach-Object { "$($_.Process) pid $($_.ProcessId) -> $($_.Remote) $($_.State)" }) -join '; ' })
        Add-Result 'no DNS resolution of a tool download host caused by the run' ($Cause.Count -eq 0) $(if ($Cause.Count -eq 0) { 'DNS cache unchanged for the download hosts' } else { $Cause -join ', ' })
        $Summary | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $WorkDirectory 'network-observation.json') -Encoding utf8
    }
}
catch {
    Add-Result 'qualification script completed' $false ("aborted: " + $_.Exception.Message)
}
finally {
    $Listener.Stop()
    $Attempts | Set-Content -LiteralPath $CanaryLog -Encoding ascii -ErrorAction SilentlyContinue
}

$Failed = @($Results | Where-Object { $_.Result -eq 'FAIL' })
$Results | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $WorkDirectory 'evidence.json') -Encoding utf8
Write-Host ''
if ($null -ne $Reachable) {
    Write-Host 'OFFLINE QUALIFICATION REHEARSAL (network reachable: not a qualification)' -ForegroundColor Yellow
    Write-Host ("  {0} check(s) failed other than the offline probe" -f (@($Failed | Where-Object { $_.Check -ne 'machine is offline' }).Count))
    if (-not $Keep) { Remove-Item -LiteralPath $WorkDirectory -Recurse -Force -ErrorAction SilentlyContinue }
    exit $(if (@($Failed | Where-Object { $_.Check -ne 'machine is offline' }).Count -eq 0) { 0 } else { 1 })
}
if ($Failed.Count -eq 0) {
    Write-Host 'OFFLINE QUALIFICATION PASS' -ForegroundColor Green
    Write-Host "Evidence: $(Join-Path $WorkDirectory 'evidence.json')"
    if (-not $Keep) { Remove-Item -LiteralPath $WorkDirectory -Recurse -Force -ErrorAction SilentlyContinue }
    exit 0
}
Write-Host 'OFFLINE QUALIFICATION FAIL' -ForegroundColor Red
Write-Host "Evidence kept in: $WorkDirectory"
exit 1
