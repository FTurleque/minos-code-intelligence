[CmdletBinding()]
param()

# Self-test of network-observer.ps1, needs no internet: a direct connection to this machine's own non-loopback address
# is "external" for the observer, a loopback one is not. Ends with `NETWORK OBSERVER SELF-TEST PASS` or `FAIL`.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'network-observer.ps1')

$Failures = New-Object System.Collections.Generic.List[string]
function Assert-That([bool] $Condition, [string] $Message) {
    if (-not $Condition) { $Failures.Add($Message); Write-Host "  [FAIL] $Message" -ForegroundColor Red }
    else { Write-Host "  [PASS] $Message" -ForegroundColor Green }
}

# ---- parsing --------------------------------------------------------------------------------------------
$Tcp = ConvertFrom-NetstatLine '  TCP    192.168.1.5:50123      140.82.121.4:443       ESTABLISHED     1234'
Assert-That ($Tcp.RemoteIsExternal -and $Tcp.ProcessId -eq 1234 -and $Tcp.State -eq 'ESTABLISHED') 'a public TCP peer is external and carries its PID and state'
Assert-That (-not (ConvertFrom-NetstatLine '  TCP    127.0.0.1:50123        127.0.0.1:8080         ESTABLISHED     7').RemoteIsExternal) 'a 127.0.0.1 peer is loopback'
Assert-That (-not (ConvertFrom-NetstatLine '  TCP    [::1]:50123            [::1]:8080             ESTABLISHED     7').RemoteIsExternal) 'a [::1] peer is loopback'
Assert-That ((ConvertFrom-NetstatLine '  TCP    [2606:4700::1]:50123   [2606:4700::2]:443     ESTABLISHED     7').RemoteIsExternal) 'a public IPv6 peer is external'
Assert-That (-not (ConvertFrom-NetstatLine '  TCP    0.0.0.0:135            0.0.0.0:0              LISTENING       900').RemoteIsExternal) 'a listening socket is not a connection'
$Udp = ConvertFrom-NetstatLine '  UDP    192.168.1.5:5353       *:*                                    4321'
Assert-That ($Udp.Protocol -eq 'UDP' -and $Udp.LocalIsExternal -and $Udp.ProcessId -eq 4321) 'a UDP endpoint is parsed without a state'
Assert-That ($null -eq (ConvertFrom-NetstatLine '  Proto  Local Address          Foreign Address        State           PID')) 'the header line is ignored'

# ---- live: a direct connection to our own non-loopback address ---------------------------------------------------
$Address = $null
try {
    $Address = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
        Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } | Select-Object -First 1).IPAddress
}
catch { }
if ($null -eq $Address) {
    Write-Host '  [SKIP] no non-loopback IPv4 address on this machine: live observation not exercised'
}
else {
    $Listener = New-Object System.Net.Sockets.TcpListener([System.Net.IPAddress]::Any, 0)
    $Listener.Start()
    $Port = ([System.Net.IPEndPoint]$Listener.LocalEndpoint).Port
    try {
        $Observer = Start-NetworkObserver -RootProcessId $PID
        # negative: loopback traffic only
        $Loop = New-Object System.Net.Sockets.TcpClient
        $Loop.Connect('127.0.0.1', $Port)
        $Server1 = $Listener.AcceptTcpClient()
        Start-Sleep -Milliseconds 600
        $Loop.Close(); $Server1.Close()
        $Observer.State.Phase = 'after-loopback'
        Start-Sleep -Milliseconds 300
        # positive: a direct connect() to the machine's own address, held open
        $Direct = New-Object System.Net.Sockets.TcpClient
        $Direct.Connect($Address, $Port)
        $Server2 = $Listener.AcceptTcpClient()
        Start-Sleep -Milliseconds 800
        $Direct.Close(); $Server2.Close()
        $Summary = Stop-NetworkObserver $Observer
        $Loopback = @($Summary.Hits | Where-Object { $_.Phase -ne 'after-loopback' })
        $DirectHits = @($Summary.Hits | Where-Object { $_.Phase -eq 'after-loopback' -and $_.ProcessId -eq $PID })
        Assert-That ($Summary.Ticks -ge 3) "the sampler ran ($($Summary.Ticks) ticks, about $($Summary.MeanIntervalMs) ms each)"
        Assert-That ($Loopback.Count -eq 0) 'loopback traffic is not reported'
        Assert-That ($DirectHits.Count -ge 1) 'a direct connect() to a non-loopback address owned by the tree is reported with its PID'
    }
    finally { $Listener.Stop() }
}

# ---- DNS cache comparison --------------------------------------------------------------------------------------
$Refreshed = Compare-DnsCacheSnapshots @{ 'a.example' = 100; 'b.example' = 50 } @{ 'a.example' = 90; 'b.example' = 300; 'c.example' = 10 }
Assert-That (($Refreshed -join ',') -eq 'b.example,c.example') 'a new name and a name whose TTL went up are reported; a name whose TTL only decreased is not'

if ($Failures.Count -eq 0) { Write-Host 'NETWORK OBSERVER SELF-TEST PASS' -ForegroundColor Green; exit 0 }
Write-Host 'NETWORK OBSERVER SELF-TEST FAIL' -ForegroundColor Red
exit 1
