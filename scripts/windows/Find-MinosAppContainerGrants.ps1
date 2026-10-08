<#
.SYNOPSIS
    Lists the AppContainer grants (S-1-15-2-...) that no live MINOS sandbox owns, and removes them only on request.

.DESCRIPTION
    MINOS-AUD-H22. The Windows sandbox grants its AppContainer SID read access to MINOS_HOME\tools and to toolchain
    homes such as JAVA_HOME, and write access to its run directories. A launcher killed before its cleanup leaves
    those entries behind; the next launcher removes them only for the runs journaled in
    MINOS_HOME\sandbox\appcontainer-recovery-v2. Entries left by older builds, or by a test whose temporary
    MINOS_HOME was abandoned, are never reclaimed (incident H23: 460 entries on a JDK).

    The script scans the given roots and reports, per path, the explicit AppContainer entries whose SID belongs to
    no live run (a run is live when another process holds its exclusive .lock). Without -Remove it changes nothing.
    With -Remove each removal goes through ShouldProcess, so -WhatIf and -Confirm apply.

.PARAMETER MinosHome
    MINOS data directory. Defaults to $env:MINOS_HOME, then %LOCALAPPDATA%\MINOS.

.PARAMETER Path
    Extra roots to scan, in addition to MINOS_HOME\tools and $env:JAVA_HOME.

.PARAMETER Remove
    Remove the reported entries (icacls /remove:g), each after confirmation.

.EXAMPLE
    .\Find-MinosAppContainerGrants.ps1 -Path 'C:\Program Files\Java\jdk-25'

.EXAMPLE
    .\Find-MinosAppContainerGrants.ps1 -Path 'C:\Program Files\Java\jdk-25' -Remove
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
    [string] $MinosHome = $(if ($env:MINOS_HOME) { $env:MINOS_HOME } else { Join-Path $env:LOCALAPPDATA 'MINOS' }),
    [string[]] $Path = @(),
    [switch] $Remove
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$recoveryDirectory = Join-Path $MinosHome 'sandbox\appcontainer-recovery-v2'

function Get-LiveSids([string] $RecoveryDirectory) {
    $live = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    $unproven = New-Object System.Collections.Generic.List[string]
    if (-not (Test-Path -LiteralPath $RecoveryDirectory -PathType Container)) { return @{ Live = $live; Unproven = $unproven } }
    foreach ($journal in Get-ChildItem -LiteralPath $RecoveryDirectory -Filter '*.json' -File) {
        if ($journal.Extension -ne '.json') { continue }
        $lockPath = [System.IO.Path]::ChangeExtension($journal.FullName, '.lock')
        $state = $null
        try { $state = Get-Content -LiteralPath $journal.FullName -Raw -Encoding UTF8 | ConvertFrom-Json } catch { }
        try {
            $probe = New-Object System.IO.FileStream($lockPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
            $probe.Dispose()
        } catch [System.IO.FileNotFoundException] {
        } catch [System.IO.IOException] {
            if (($_.Exception.HResult -band 0xFFFF) -eq 32) {
                if ($null -ne $state) { [void]$live.Add([string]$state.sid) } else { $unproven.Add($journal.Name) }
            } else {
                $unproven.Add($journal.Name)
            }
        } catch {
            $unproven.Add($journal.Name)
        }
    }
    return @{ Live = $live; Unproven = $unproven }
}

function Get-ExplicitAppContainerEntries([string] $Root) {
    $entries = New-Object System.Collections.Generic.List[object]
    $current = $null
    foreach ($line in (& icacls.exe $Root /t /c /q 2>$null)) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        # A header line is "<path> <first entry>"; both may contain spaces, so the path is the longest prefix
        # that exists on disk.
        if (-not [char]::IsWhiteSpace($line[0])) {
            for ($space = $line.LastIndexOf(' '); $space -gt 0; $space = $line.LastIndexOf(' ', $space - 1)) {
                if (Test-Path -LiteralPath $line.Substring(0, $space)) { $current = $line.Substring(0, $space); break }
            }
        }
        foreach ($entry in [regex]::Matches($line, '(?<sid>S-1-15-2(?:-\d+)+):\((?<first>[^)]*)\)')) {
            if ($entry.Groups['first'].Value -eq 'I') { continue }
            $entries.Add([pscustomobject]@{ Path = $current; Sid = $entry.Groups['sid'].Value })
        }
    }
    return $entries
}

$roots = New-Object System.Collections.Generic.List[string]
foreach ($candidate in @((Join-Path $MinosHome 'tools'), $env:JAVA_HOME) + $Path) {
    if ($candidate -and (Test-Path -LiteralPath $candidate)) { $roots.Add((Resolve-Path -LiteralPath $candidate).ProviderPath) }
}

$ownership = Get-LiveSids $recoveryDirectory
foreach ($name in $ownership.Unproven) {
    Write-Warning "Run journal $name has no provable owner: its grants are reported, never removed by this script"
}

$orphans = New-Object System.Collections.Generic.List[object]
foreach ($root in ($roots | Select-Object -Unique)) {
    foreach ($entry in Get-ExplicitAppContainerEntries $root) {
        if (-not $ownership.Live.Contains($entry.Sid)) { $orphans.Add($entry) }
    }
}

if ($orphans.Count -eq 0) {
    Write-Output "No orphan AppContainer grant under: $($roots -join ', ')"
    return
}

$orphans | Group-Object Path | Sort-Object Count -Descending |
    ForEach-Object { [pscustomobject]@{ Path = $_.Name; OrphanEntries = $_.Count } } | Format-Table -AutoSize | Out-String | Write-Output
Write-Output "$($orphans.Count) orphan AppContainer entr$(if ($orphans.Count -eq 1) { 'y' } else { 'ies' }) on $(@($orphans | Select-Object -ExpandProperty Path -Unique).Count) path(s)."

if (-not $Remove) {
    Write-Output 'Nothing was changed. Run again with -Remove to remove them, one confirmation per path and SID.'
    return
}

foreach ($orphan in $orphans) {
    if ($PSCmdlet.ShouldProcess($orphan.Path, "Remove the AppContainer grant of $($orphan.Sid)")) {
        & icacls.exe $orphan.Path /remove:g ('*' + $orphan.Sid) /q | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "icacls could not remove $($orphan.Sid) from $($orphan.Path)" }
    }
}
