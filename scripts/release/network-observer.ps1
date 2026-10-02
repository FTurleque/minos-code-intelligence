# Read-only observation of the network activity of a process tree (dot-source this file).
#
# It changes no setting of the machine: it only reads `netstat -ano`, the process table and the DNS client cache.
# Used by qualify-offline-install.ps1 -ObserveNetwork to complete the loopback-proxy canary, which only sees traffic
# that goes through a proxy: a direct connect() bypasses it, a netstat sample does not.
#
#   Start-NetworkObserver -RootProcessId $PID   -> an observer
#   Set-NetworkObserverPhase $o 'minos'         -> hits are tagged with the phase in which they were seen
#   Stop-NetworkObserver $o                     -> a summary (ticks, tree size, hits, other non-loopback connections)
#
# Limits, by construction: a TCP connection that opens and closes between two samples (about 150 ms) is not seen
# (it may still show as an unattributed TIME_WAIT entry, listed as information), and UDP has no connection to observe,
# only endpoints, which are listed as information. A positive control (a direct connect held open from the same tree)
# must be seen before "zero connections" means anything.

Set-StrictMode -Version Latest

function Test-LoopbackOrWildcardAddress([string] $Address) {
    $Host_ = $Address.Trim('[', ']')
    if ($Host_ -eq '*' -or $Host_ -eq '0.0.0.0' -or $Host_ -eq '::' -or $Host_ -eq '') { return $true }
    if ($Host_ -eq '::1' -or $Host_.StartsWith('127.')) { return $true }
    return $false
}

function ConvertFrom-NetstatLine([string] $Line) {
    # TCP  192.168.1.5:50123  140.82.121.4:443  ESTABLISHED  1234      UDP  0.0.0.0:5353  *:*  1234
    if ($Line -notmatch '^\s*(TCP|UDP)\s+(\S+)\s+(\S+)\s+(?:([A-Z_]+)\s+)?(\d+)\s*$') { return $null }
    $Local = $Matches[2]; $Remote = $Matches[3]
    $RemoteHost = $Remote.Substring(0, [Math]::Max(0, $Remote.LastIndexOf(':')))
    $LocalHost = $Local.Substring(0, [Math]::Max(0, $Local.LastIndexOf(':')))
    return [pscustomobject]@{
        Protocol   = $Matches[1]
        Local      = $Local
        Remote     = $Remote
        State      = $Matches[4]
        ProcessId  = [int]$Matches[5]
        RemoteHost = $RemoteHost
        LocalHost  = $LocalHost
        RemoteIsExternal = -not (Test-LoopbackOrWildcardAddress $RemoteHost)
        LocalIsExternal  = -not (Test-LoopbackOrWildcardAddress $LocalHost)
    }
}

function Start-NetworkObserver {
    param([Parameter(Mandatory = $true)][int] $RootProcessId, [int] $IntervalMilliseconds = 100)
    $State = [hashtable]::Synchronized(@{
        Stop     = $false
        Phase    = 'start'
        Ticks    = 0
        Hits     = New-Object System.Collections.ArrayList
        Udp      = New-Object System.Collections.ArrayList
        Others   = New-Object System.Collections.ArrayList
        TreeSize = 1
        Error    = $null
    })
    $Body = {
        param($State, $RootPid, $Interval, $ParserText)
        . ([scriptblock]::Create($ParserText))
        $Tree = New-Object System.Collections.Generic.HashSet[int]
        [void]$Tree.Add($RootPid)
        $Seen = New-Object System.Collections.Generic.HashSet[string]
        $Baseline = New-Object System.Collections.Generic.HashSet[string]
        $First = $true
        while (-not $State.Stop) {
            $Started = Get-Date
            try {
                $Names = @{}
                $Processes = Get-CimInstance -ClassName Win32_Process -Property ProcessId, ParentProcessId, Name -ErrorAction Stop
                foreach ($P in $Processes) { $Names[[int]$P.ProcessId] = [string]$P.Name }
                do {
                    $Grew = $false
                    foreach ($P in $Processes) {
                        if ($Tree.Contains([int]$P.ParentProcessId) -and $Tree.Add([int]$P.ProcessId)) { $Grew = $true }
                    }
                } while ($Grew)
                $State.TreeSize = $Tree.Count
                foreach ($Line in (netstat.exe -ano)) {
                    $Entry = ConvertFrom-NetstatLine $Line
                    if ($null -eq $Entry) { continue }
                    $InTree = $Tree.Contains($Entry.ProcessId)
                    if ($Entry.Protocol -eq 'TCP' -and $Entry.RemoteIsExternal) {
                        $Key = "TCP|$($Entry.Local)|$($Entry.Remote)"
                        if ($First) { [void]$Baseline.Add($Key); continue }
                        if ($Seen.Add("$Key|$InTree")) {
                            $Record = [pscustomobject]@{
                                Time = Get-Date; Phase = $State.Phase; ProcessId = $Entry.ProcessId
                                Process = $(if ($Names.ContainsKey($Entry.ProcessId)) { $Names[$Entry.ProcessId] } else { '?' })
                                Remote = $Entry.Remote; State = $Entry.State
                            }
                            if ($InTree) { [void]$State.Hits.Add($Record) }
                            elseif (-not $Baseline.Contains($Key)) { [void]$State.Others.Add($Record) }
                        }
                    }
                    elseif ($Entry.Protocol -eq 'UDP' -and $InTree -and $Entry.LocalIsExternal) {
                        if ($Seen.Add("UDP|$($Entry.Local)|$($Entry.ProcessId)")) {
                            [void]$State.Udp.Add([pscustomobject]@{ Phase = $State.Phase; ProcessId = $Entry.ProcessId; Local = $Entry.Local })
                        }
                    }
                }
                $First = $false
                $State.Ticks = $State.Ticks + 1
            }
            catch { $State.Error = $_.Exception.Message }
            $Remaining = $Interval - [int]((Get-Date) - $Started).TotalMilliseconds
            if ($Remaining -gt 0) { Start-Sleep -Milliseconds $Remaining }
        }
    }
    $ParserText = "function Test-LoopbackOrWildcardAddress { ${function:Test-LoopbackOrWildcardAddress} }`n" +
                  "function ConvertFrom-NetstatLine { ${function:ConvertFrom-NetstatLine} }"
    $Runspace = [runspacefactory]::CreateRunspace()
    $Runspace.Open()
    $Shell = [powershell]::Create()
    $Shell.Runspace = $Runspace
    [void]$Shell.AddScript($Body).AddArgument($State).AddArgument($RootProcessId).AddArgument($IntervalMilliseconds).AddArgument($ParserText)
    $Handle = $Shell.BeginInvoke()
    # Wait for the baseline tick: connections that exist before the run are not the run's.
    $Deadline = (Get-Date).AddSeconds(15)
    while ($State.Ticks -lt 1 -and (Get-Date) -lt $Deadline) { Start-Sleep -Milliseconds 50 }
    return [pscustomobject]@{ State = $State; Shell = $Shell; Handle = $Handle; Runspace = $Runspace; Started = Get-Date }
}

function Set-NetworkObserverPhase($Observer, [string] $Phase) {
    # Let the sampler finish the tick that straddles the change.
    Start-Sleep -Milliseconds 250
    $Observer.State.Phase = $Phase
}

function Stop-NetworkObserver($Observer) {
    Start-Sleep -Milliseconds 400
    $Observer.State.Stop = $true
    [void]$Observer.Handle.AsyncWaitHandle.WaitOne(10000)
    $Observer.Shell.Dispose()
    $Observer.Runspace.Dispose()
    $Elapsed = ((Get-Date) - $Observer.Started).TotalMilliseconds
    $State = $Observer.State
    return [pscustomobject]@{
        Ticks          = $State.Ticks
        MeanIntervalMs = $(if ($State.Ticks -gt 0) { [int]($Elapsed / $State.Ticks) } else { 0 })
        TreeSize       = $State.TreeSize
        Hits           = @($State.Hits)
        UdpEndpoints   = @($State.Udp)
        OtherExternal  = @($State.Others)
        Error          = $State.Error
    }
}

function Get-DnsCacheSnapshot {
    # Name -> highest remaining TTL. A name that is new, or whose TTL went UP, was resolved again since the last snapshot.
    $Snapshot = @{}
    try {
        foreach ($Entry in @(Get-DnsClientCache -ErrorAction Stop)) {
            $Name = ([string]$Entry.Entry).ToLowerInvariant().TrimEnd('.')
            $Ttl = [int]$Entry.TimeToLive
            if (-not $Snapshot.ContainsKey($Name) -or $Snapshot[$Name] -lt $Ttl) { $Snapshot[$Name] = $Ttl }
        }
    }
    catch { }
    return $Snapshot
}

function Compare-DnsCacheSnapshots($Before, $After) {
    $Refreshed = New-Object System.Collections.Generic.List[string]
    foreach ($Name in $After.Keys) {
        if (-not $Before.ContainsKey($Name) -or $After[$Name] -gt $Before[$Name]) { $Refreshed.Add($Name) }
    }
    return @($Refreshed | Sort-Object)
}
