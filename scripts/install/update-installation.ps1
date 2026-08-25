[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $PackageRoot,

    [Parameter(Mandatory = $true)]
    [string] $InstallRoot,

    [switch] $SkipProcessStop,

    # Qualification/test injection points. Production setup never sets these.
    [ValidateRange(0, [int]::MaxValue)]
    [int] $TestFailActivationAfterEntries = 0,

    [ValidateRange(0, [int]::MaxValue)]
    [int] $TestCrashActivationAfterEntries = 0,

    [switch] $TestFailPostCommitCleanup
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows upgrade transaction currently targets Windows hosts.'
}

$PackageRoot = [System.IO.Path]::GetFullPath($PackageRoot)
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)

# Move-Item -Force is NOT atomic when the destination already exists: it
# deletes then moves as two separate operations (empirically confirmed via
# FileSystemWatcher -- a Deleted event fires before the Renamed event), which
# is exactly the crash window this journal's durability guarantee depends on
# not having, since transaction.json is overwritten on the 'activating' ->
# 'committed' transition. MoveFileEx with MOVEFILE_REPLACE_EXISTING is the
# genuinely atomic Windows primitive for this (the same one the reference
# transactional-upgrade design uses). Guarded because this script can be
# invoked multiple times within one PowerShell process (e.g. by the
# fault-injection test harness), and Add-Type throws if the same type is
# defined twice in one AppDomain.
if (-not ([System.Management.Automation.PSTypeName]'Minos.Update.NativeMethods').Type) {
    Add-Type -Namespace 'Minos.Update' -Name 'NativeMethods' -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("kernel32.dll", SetLastError = true, CharSet = System.Runtime.InteropServices.CharSet.Unicode)]
public static extern bool MoveFileEx(string lpExistingFileName, string lpNewFileName, uint dwFlags);
'@ | Out-Null
}
$MoveFileExReplaceExisting = 0x1
$MoveFileExWriteThrough = 0x8

function Publish-DurableFile([string] $Source, [string] $Destination) {
    $Succeeded = [Minos.Update.NativeMethods]::MoveFileEx($Source, $Destination, $MoveFileExReplaceExisting -bor $MoveFileExWriteThrough)
    if (-not $Succeeded) {
        $ErrorCode = [System.Runtime.InteropServices.Marshal]::GetLastWin32Error()
        throw "MINOS_UPDATE_DURABLE_WRITE_FAILED: MoveFileEx a echoue (code $ErrorCode) en publiant $Destination"
    }
}

$ManagedApplicationName = 'MINOS'
$TransactionSchemaVersion = '1.1'
$StageRoot = Join-Path $InstallRoot '.install-staging'
$RollbackRoot = Join-Path $InstallRoot '.install-rollback'
$RollbackOldRoot = Join-Path $RollbackRoot 'old'
$TransactionPath = Join-Path $RollbackRoot 'transaction.json'
$OwnershipMarkerPath = Join-Path $InstallRoot '.minos-installation.json'

# Directory-level entries are moved (not merged) whole -- any file present in an
# old version's directory but absent from the new package's directory is
# implicitly discarded along with the rest of that directory's rollback backup.
$StagedDirectoryRelativePaths = @('app', 'lib', 'docker', 'integration', 'supply-chain')
# install.ps1 is the portable/ZIP distribution's own bootstrapper; it has no
# function once already Inno-installed, but the pre-transactional-engine
# wildcard [Files] copy always shipped it into {app} too. Keep staging it so
# an Inno-managed install still matches that prior behavior exactly.
$StagedFileRelativePaths = @('minos.cmd', 'minos-mcp.cmd', 'RUNTIME-MODULES.txt', 'RELEASE-MANIFEST.json', 'VERSION', 'README.txt', 'install.ps1')
$ManagedRelativePaths = $StagedDirectoryRelativePaths + $StagedFileRelativePaths + @(
    '.install-staging', '.install-rollback', '.minos-installation.json'
)
$RetryDelaysMs = @(250, 500, 1000, 2000, 3000)

# ---------------------------------------------------------------------------
# Path safety: every managed write is re-validated immediately before use, not
# just once at startup, so a reparse point swapped in mid-run (TOCTOU) is
# still caught.
# ---------------------------------------------------------------------------

function Get-NormalizedComparablePath([string] $Path) {
    return ([System.IO.Path]::GetFullPath($Path)).TrimEnd('\')
}

function Test-PathInsideRoot([string] $Path, [string] $Root) {
    $NormalizedRoot = Get-NormalizedComparablePath $Root
    $NormalizedPath = Get-NormalizedComparablePath $Path
    if ($NormalizedPath.Equals($NormalizedRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return $true }
    return $NormalizedPath.StartsWith($NormalizedRoot + '\', [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-PathInsideRoot([string] $Path, [string] $Root) {
    if (-not (Test-PathInsideRoot -Path $Path -Root $Root)) {
        throw "MINOS_UPDATE_UNSAFE_PATH: chemin hors de la racine autorisee: $Path"
    }
    $NormalizedRoot = Get-NormalizedComparablePath $Root
    if (Test-Path -LiteralPath $NormalizedRoot) {
        $RootItem = Get-Item -LiteralPath $NormalizedRoot -Force
        if ($RootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            throw "MINOS_UPDATE_REPARSE_POINT: la racine geree est un point de reparse: $NormalizedRoot"
        }
    }
    $NormalizedPath = Get-NormalizedComparablePath $Path
    if ($NormalizedPath.Equals($NormalizedRoot, [System.StringComparison]::OrdinalIgnoreCase)) { return }
    $Cursor = $NormalizedRoot
    foreach ($Segment in $NormalizedPath.Substring($NormalizedRoot.Length + 1).Split('\')) {
        $Cursor = Join-Path $Cursor $Segment
        if (Test-Path -LiteralPath $Cursor) {
            $Item = Get-Item -LiteralPath $Cursor -Force
            if ($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
                throw "MINOS_UPDATE_REPARSE_POINT: point de reparse detecte sur un chemin gere: $Cursor"
            }
        }
    }
}

function Assert-ManagedTreeSafe {
    foreach ($Relative in $ManagedRelativePaths) {
        Assert-PathInsideRoot -Path (Join-Path $InstallRoot $Relative) -Root $InstallRoot
    }
}

function Get-ForbiddenInstallRoots {
    $Roots = New-Object System.Collections.Generic.List[string]
    $Roots.Add([System.IO.Path]::GetPathRoot($InstallRoot))
    foreach ($Variable in @('USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'ProgramData', 'ProgramFiles', 'ProgramFiles(x86)', 'SystemRoot', 'TEMP', 'TMP')) {
        $Value = [System.Environment]::GetEnvironmentVariable($Variable)
        if (-not [string]::IsNullOrWhiteSpace($Value)) { $Roots.Add($Value) }
    }
    $Roots.Add([System.IO.Path]::GetTempPath())
    # Unary comma: without it, a returned collection is unrolled onto the
    # pipeline element-by-element (0 elements -> $null, 1 element -> a bare
    # scalar), silently losing its collection-ness at the caller.
    return , $Roots
}

function Assert-SafeInstallRootLocation {
    $Comparable = Get-NormalizedComparablePath $InstallRoot
    foreach ($Forbidden in (Get-ForbiddenInstallRoots)) {
        if ([string]::IsNullOrWhiteSpace($Forbidden)) { continue }
        if ($Comparable.Equals((Get-NormalizedComparablePath $Forbidden), [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "MINOS_UPDATE_UNSAFE_INSTALL_ROOT: racine d'installation interdite: $InstallRoot"
        }
    }
}

# ---------------------------------------------------------------------------
# Durable I/O: write-temp-then-atomic-rename, matching the pattern already
# used by switch-mcp-backend.ps1's Write-BackendConfiguration.
# ---------------------------------------------------------------------------

function Write-DurableJson([string] $Path, [object] $Object, [int] $Depth = 6) {
    $Directory = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $Directory)) {
        New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    }
    $Temporary = Join-Path $Directory ('.' + (Split-Path -Leaf $Path) + '.tmp-' + [Guid]::NewGuid().ToString('N'))
    $Json = $Object | ConvertTo-Json -Depth $Depth
    try {
        [System.IO.File]::WriteAllText($Temporary, $Json, [System.Text.UTF8Encoding]::new($false))
        Publish-DurableFile -Source $Temporary -Destination $Path
    }
    finally {
        Remove-Item -LiteralPath $Temporary -Force -ErrorAction SilentlyContinue
    }
}

# ---------------------------------------------------------------------------
# Ownership: a non-empty install root must prove it is MINOS's own before this
# script mutates anything inside it.
# ---------------------------------------------------------------------------

function Test-OwnershipMarker {
    if (-not (Test-Path -LiteralPath $OwnershipMarkerPath -PathType Leaf)) { return $false }
    try { $Marker = Get-Content -LiteralPath $OwnershipMarkerPath -Raw | ConvertFrom-Json }
    catch { return $false }
    if ([string]$Marker.schemaVersion -ne '1.0') { return $false }
    if ([string]$Marker.name -ne $ManagedApplicationName) { return $false }
    try { [void][guid]::Parse([string]$Marker.installationId) } catch { return $false }
    return $true
}

function Test-LegacyOwnedInstallation {
    if (-not (Test-Path -LiteralPath (Join-Path $InstallRoot 'RELEASE-MANIFEST.json') -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $InstallRoot 'app\minos.exe') -PathType Leaf)) { return $false }
    if (-not (Test-Path -LiteralPath (Join-Path $InstallRoot 'integration\switch-mcp-backend.ps1') -PathType Leaf)) { return $false }
    return $true
}

function Test-RecoverableTransactionOwnership {
    if (-not (Test-Path -LiteralPath $TransactionPath -PathType Leaf)) { return $false }
    try { $Transaction = Read-TransactionManifest }
    catch { return $false }
    if ([string]$Transaction.phase -notin @('activating', 'committed')) { return $false }
    $Entries = @($Transaction.entries)
    if ($Entries.Count -lt 3) { return $false }
    $Paths = @($Entries | ForEach-Object { [string]$_.relativePath })
    foreach ($Needle in @('app', 'integration', 'RELEASE-MANIFEST.json')) {
        if ($Paths -notcontains $Needle) { return $false }
    }
    return $true
}

function Assert-SafeInstallRoot {
    Assert-SafeInstallRootLocation
    if (-not (Test-Path -LiteralPath $InstallRoot)) { return }
    $Item = Get-Item -LiteralPath $InstallRoot -Force
    if (-not $Item.PSIsContainer) {
        throw "MINOS_UPDATE_UNSAFE_INSTALL_ROOT: la racine d'installation n'est pas un dossier: $InstallRoot"
    }
    if ($Item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
        throw "MINOS_UPDATE_REPARSE_POINT: la racine d'installation est un point de reparse: $InstallRoot"
    }
    $HasContent = @(Get-ChildItem -LiteralPath $InstallRoot -Force -ErrorAction SilentlyContinue).Count -gt 0
    if (-not $HasContent) { return }
    if ((Test-OwnershipMarker) -or (Test-LegacyOwnedInstallation) -or (Test-RecoverableTransactionOwnership)) { return }
    throw "MINOS_UPDATE_UNSAFE_INSTALL_ROOT: dossier non vide sans preuve d'ownership MINOS: $InstallRoot"
}

function Ensure-OwnershipMarker {
    if (Test-OwnershipMarker) { return }
    New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
    Write-DurableJson -Path $OwnershipMarkerPath -Object ([ordered]@{
        schemaVersion  = '1.0'
        name           = $ManagedApplicationName
        installationId = [guid]::NewGuid().ToString('D')
        createdAt      = [DateTime]::UtcNow.ToString('o')
    })
}

# ---------------------------------------------------------------------------
# Package validation.
# ---------------------------------------------------------------------------

function Assert-Package {
    $Required = @(
        'app\minos.exe',
        'lib\minos.jar',
        'integration\switch-mcp-backend.ps1',
        'integration\probe-mcp-backend.ps1',
        'integration\detect-mcp-clients.ps1',
        'integration\configure-mcp-clients.ps1',
        'integration\configure-mcp-clients-setup.ps1',
        'integration\configure-codex-mcp.ps1',
        'integration\configure-runtime-settings.ps1',
        'integration\uninstall-mcp-clients.ps1',
        'integration\update-installation.ps1',
        'docker\Dockerfile.mcp.release',
        'docker\compose.mcp.prod.yaml',
        'docker\scripts\prod-mcp-release.ps1',
        'docker\scripts\configure-docker-mcp.ps1',
        'supply-chain\minos.cdx.json',
        'supply-chain\THIRD-PARTY-NOTICES.txt',
        'minos.cmd',
        'minos-mcp.cmd',
        'RUNTIME-MODULES.txt',
        'RELEASE-MANIFEST.json',
        'VERSION',
        'README.txt',
        'install.ps1'
    )
    $Missing = @($Required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $PackageRoot $_)) })
    if ($Missing.Count -gt 0) {
        throw "MINOS_UPDATE_INVALID_PACKAGE: fichiers requis absents du paquet: $($Missing -join ', ')"
    }
    try {
        $Manifest = Get-Content -LiteralPath (Join-Path $PackageRoot 'RELEASE-MANIFEST.json') -Raw | ConvertFrom-Json
    }
    catch {
        throw "MINOS_UPDATE_INVALID_PACKAGE: RELEASE-MANIFEST.json illisible: $($_.Exception.Message)"
    }
    if ([int]$Manifest.schemaVersion -ne 1 -or [string]::IsNullOrWhiteSpace([string]$Manifest.version)) {
        throw 'MINOS_UPDATE_INVALID_PACKAGE: RELEASE-MANIFEST.json invalide (schemaVersion/version).'
    }
    $VersionLine = (Get-Content -LiteralPath (Join-Path $PackageRoot 'VERSION') |
        Where-Object { $_ -match '^version=(.+)$' } | Select-Object -First 1)
    if ($null -eq $VersionLine -or ($Matches[1].Trim() -ne [string]$Manifest.version)) {
        throw 'MINOS_UPDATE_INVALID_PACKAGE: VERSION et RELEASE-MANIFEST.json ne concordent pas.'
    }
    return $Manifest
}

# ---------------------------------------------------------------------------
# Transaction journal: self-checksummed, verified on every read. A torn or
# tampered journal is a hard failure, never silently accepted.
# ---------------------------------------------------------------------------

function Get-Sha256Hex([string] $Text) {
    $Hasher = [System.Security.Cryptography.SHA256]::Create()
    try { $Hash = $Hasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Text)) }
    finally { $Hasher.Dispose() }
    return -join ($Hash | ForEach-Object { $_.ToString('x2') })
}

function ConvertTo-TransactionTimestampString($Value) {
    # Windows PowerShell 5.1 (the runtime the installed product actually uses)
    # leaves an ISO-8601-looking JSON string as [string]; PowerShell 7's
    # ConvertFrom-Json silently coerces it to [DateTime]. A naive [string]
    # cast on the coerced value would render it in the current culture's
    # format instead of the original text and break the checksum. Round-trip
    # explicitly instead.
    if ($Value -is [DateTime]) {
        return $Value.ToUniversalTime().ToString('o', [System.Globalization.CultureInfo]::InvariantCulture)
    }
    return [string]$Value
}

function ConvertTo-NormalizedTransactionEntries([array] $Entries) {
    $Normalized = @($Entries | ForEach-Object {
        [ordered]@{
            order        = [int]$_.order
            relativePath = [string]$_.relativePath
            hadOriginal  = [bool]$_.hadOriginal
        }
    })
    # Unary comma: an @() array is still unrolled element-by-element when
    # placed on the pipeline via return, which would collapse a single-entry
    # (or empty) result back to a bare scalar/$null at the caller.
    return , $Normalized
}

function Get-TransactionCoreJson([string] $SchemaVersion, [string] $Phase, [array] $NormalizedEntries, [string] $UpdatedAt) {
    $Core = [ordered]@{
        schemaVersion = $SchemaVersion
        phase         = $Phase
        entries       = $NormalizedEntries
        updatedAt     = $UpdatedAt
    }
    return ($Core | ConvertTo-Json -Depth 6 -Compress)
}

function Write-TransactionManifest([string] $Phase, [array] $Entries) {
    $UpdatedAt = [DateTime]::UtcNow.ToString('o')
    $NormalizedEntries = ConvertTo-NormalizedTransactionEntries $Entries
    $Checksum = Get-Sha256Hex (Get-TransactionCoreJson -SchemaVersion $TransactionSchemaVersion -Phase $Phase -NormalizedEntries $NormalizedEntries -UpdatedAt $UpdatedAt)
    $Document = [ordered]@{
        schemaVersion  = $TransactionSchemaVersion
        phase          = $Phase
        entries        = $NormalizedEntries
        updatedAt      = $UpdatedAt
        checksumSha256 = $Checksum
    }
    New-Item -ItemType Directory -Force -Path $RollbackRoot | Out-Null
    Write-DurableJson -Path $TransactionPath -Object $Document
}

function Read-TransactionManifest {
    $Parsed = (Get-Content -LiteralPath $TransactionPath -Raw) | ConvertFrom-Json
    $UpdatedAt = ConvertTo-TransactionTimestampString $Parsed.updatedAt
    $NormalizedEntries = ConvertTo-NormalizedTransactionEntries @($Parsed.entries)
    # Recompute using the schemaVersion actually recorded in the journal, not this
    # script's own $TransactionSchemaVersion constant -- a future schema bump must
    # not make every journal written by a prior version look tampered with.
    $ExpectedChecksum = Get-Sha256Hex (Get-TransactionCoreJson -SchemaVersion ([string]$Parsed.schemaVersion) -Phase ([string]$Parsed.phase) -NormalizedEntries $NormalizedEntries -UpdatedAt $UpdatedAt)
    if ([string]$Parsed.checksumSha256 -ne $ExpectedChecksum) {
        throw 'MINOS_UPDATE_RECOVERY_REQUIRED: checksum du journal de transaction invalide.'
    }
    return $Parsed
}

# ---------------------------------------------------------------------------
# Stop any MINOS process the upgrade must not race (native launcher, or the
# router process regardless of the active native/docker backend).
# ---------------------------------------------------------------------------

function Get-CurrentProcessLineage {
    $Chain = New-Object System.Collections.Generic.HashSet[int]
    $CurrentId = $PID
    for ($Depth = 0; $Depth -lt 32; $Depth++) {
        if (-not $Chain.Add($CurrentId)) { break }
        try { $Process = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId=$CurrentId" -ErrorAction Stop }
        catch { break }
        if ($null -eq $Process -or [int]$Process.ParentProcessId -eq 0) { break }
        $CurrentId = [int]$Process.ParentProcessId
    }
    # Unary comma: see the note on Get-ForbiddenInstallRoots above -- without
    # it a HashSet is unrolled on return and .Contains() at the caller fails.
    return , $Chain
}

function Test-ProcessRunsMinosExe([object] $Candidate, [string] $Needle) {
    # Prefer WMI's own resolved executable path -- an exact match, not a
    # substring search, so an unrelated process that merely references this
    # path in one of its arguments (a log viewer, an editor, a grep-like
    # tool) is never mistaken for a running minos.exe and force-killed.
    $ExecutablePath = [string]$Candidate.ExecutablePath
    if (-not [string]::IsNullOrEmpty($ExecutablePath)) {
        return $ExecutablePath.Equals($Needle, [System.StringComparison]::OrdinalIgnoreCase)
    }
    # ExecutablePath can be unresolved by WMI in rare cases; fall back to the
    # command line's own leading token (the invoked executable itself, not
    # its arguments), still an exact match rather than IndexOf-anywhere.
    $CommandLine = [string]$Candidate.CommandLine
    if ([string]::IsNullOrEmpty($CommandLine)) { return $false }
    $Leading = if ($CommandLine.StartsWith('"')) {
        $EndQuote = $CommandLine.IndexOf('"', 1)
        if ($EndQuote -lt 0) { $CommandLine.Substring(1) } else { $CommandLine.Substring(1, $EndQuote - 1) }
    }
    else {
        $CommandLine.Split(' ')[0]
    }
    return $Leading.Equals($Needle, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-InstalledMcpProcesses {
    $Lineage = Get-CurrentProcessLineage
    $Needle = Join-Path $InstallRoot 'app\minos.exe'
    # Named to avoid shadowing the $Matches automatic variable populated by -match.
    $MatchedProcesses = New-Object System.Collections.Generic.List[object]
    try { $Candidates = Get-CimInstance -ClassName Win32_Process -ErrorAction Stop }
    catch { return , $MatchedProcesses }
    foreach ($Candidate in $Candidates) {
        if ($Lineage.Contains([int]$Candidate.ProcessId)) { continue }
        if (Test-ProcessRunsMinosExe -Candidate $Candidate -Needle $Needle) {
            $MatchedProcesses.Add($Candidate)
        }
    }
    # Unary comma: see the note on Get-ForbiddenInstallRoots above.
    return , $MatchedProcesses
}

function Stop-InstalledMcpProcesses {
    if ($SkipProcessStop) { return }
    $Running = Get-InstalledMcpProcesses
    if ($Running.Count -eq 0) { return }
    foreach ($Process in $Running) {
        try { Stop-Process -Id ([int]$Process.ProcessId) -Force -ErrorAction Stop } catch { }
    }
    Start-Sleep -Milliseconds 500
    $Remaining = Get-InstalledMcpProcesses
    if ($Remaining.Count -gt 0) {
        throw "MINOS_UPDATE_PROCESS_LOCK: $($Remaining.Count) processus MINOS restent actifs."
    }
}

# ---------------------------------------------------------------------------
# Staging: whole directories/files copied into $StageRoot, recorded in order.
# ---------------------------------------------------------------------------

$script:StagedEntries = New-Object System.Collections.Generic.List[object]
$script:NextOrder = 0

function Register-StagedEntry([string] $RelativePath) {
    $InstallTarget = Join-Path $InstallRoot $RelativePath
    Assert-PathInsideRoot -Path $InstallTarget -Root $InstallRoot
    $script:StagedEntries.Add([pscustomobject]@{
        order        = $script:NextOrder
        relativePath = $RelativePath
        hadOriginal  = (Test-Path -LiteralPath $InstallTarget)
    })
    $script:NextOrder++
}

function Add-StagedDirectory([string] $RelativePath) {
    $Source = Join-Path $PackageRoot $RelativePath
    $StageTarget = Join-Path $StageRoot $RelativePath
    Assert-PathInsideRoot -Path $StageTarget -Root $StageRoot
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StageTarget) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $StageTarget -Recurse -Force
    Register-StagedEntry -RelativePath $RelativePath
}

function Add-StagedFile([string] $RelativePath) {
    $Source = Join-Path $PackageRoot $RelativePath
    $StageTarget = Join-Path $StageRoot $RelativePath
    Assert-PathInsideRoot -Path $StageTarget -Root $StageRoot
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $StageTarget) | Out-Null
    Copy-Item -LiteralPath $Source -Destination $StageTarget -Force
    Register-StagedEntry -RelativePath $RelativePath
}

# ---------------------------------------------------------------------------
# Move/remove with retry-with-backoff to absorb transient AV/indexer locks.
# ---------------------------------------------------------------------------

function Move-PathWithRetry([string] $Source, [string] $Destination) {
    $Parent = Split-Path -Parent $Destination
    if (-not (Test-Path -LiteralPath $Parent)) {
        New-Item -ItemType Directory -Force -Path $Parent | Out-Null
    }
    $Attempt = 0
    while ($true) {
        try { Move-Item -LiteralPath $Source -Destination $Destination -Force; return }
        catch {
            if ($Attempt -ge $RetryDelaysMs.Count) { throw }
            Start-Sleep -Milliseconds $RetryDelaysMs[$Attempt]
            $Attempt++
        }
    }
}

function Remove-PathWithRetry([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $Attempt = 0
    while ($true) {
        try { Remove-Item -LiteralPath $Path -Recurse -Force; return }
        catch {
            if ($Attempt -ge $RetryDelaysMs.Count) { throw }
            Start-Sleep -Milliseconds $RetryDelaysMs[$Attempt]
            $Attempt++
        }
    }
}

# ---------------------------------------------------------------------------
# Activation: the transactional core. Journal phase 'activating' is the point
# of no return for crash recovery; phase 'committed' means the upgrade is
# durably active regardless of what happens to cleanup afterward.
# ---------------------------------------------------------------------------

function Invoke-Activation {
    New-Item -ItemType Directory -Force -Path $RollbackOldRoot | Out-Null
    Write-TransactionManifest -Phase 'activating' -Entries $script:StagedEntries

    $ActivationCount = 0
    foreach ($Entry in ($script:StagedEntries | Sort-Object order)) {
        $StageSource = Join-Path $StageRoot $Entry.relativePath
        $InstallTarget = Join-Path $InstallRoot $Entry.relativePath
        $BackupTarget = Join-Path $RollbackOldRoot $Entry.relativePath
        Assert-PathInsideRoot -Path $StageSource -Root $StageRoot
        Assert-PathInsideRoot -Path $InstallTarget -Root $InstallRoot
        Assert-PathInsideRoot -Path $BackupTarget -Root $RollbackOldRoot

        if ($Entry.hadOriginal) {
            Move-PathWithRetry -Source $InstallTarget -Destination $BackupTarget
        }
        Move-PathWithRetry -Source $StageSource -Destination $InstallTarget
        $ActivationCount++

        if ($TestFailActivationAfterEntries -gt 0 -and $ActivationCount -eq $TestFailActivationAfterEntries) {
            throw "MINOS_UPDATE_TEST_ACTIVATION_FAILURE:$ActivationCount"
        }
        if ($TestCrashActivationAfterEntries -gt 0 -and $ActivationCount -eq $TestCrashActivationAfterEntries) {
            [System.Environment]::FailFast("MINOS_UPDATE_TEST_CRASH_AFTER_ENTRY:$ActivationCount")
        }
    }

    Write-TransactionManifest -Phase 'committed' -Entries $script:StagedEntries
}

function Restore-Transaction([object] $Transaction) {
    $Entries = @($Transaction.entries | Sort-Object { [int]$_.order } -Descending)
    foreach ($Entry in $Entries) {
        $RelativePath = [string]$Entry.relativePath
        $InstallTarget = Join-Path $InstallRoot $RelativePath
        $BackupTarget = Join-Path $RollbackOldRoot $RelativePath
        Assert-PathInsideRoot -Path $InstallTarget -Root $InstallRoot
        Assert-PathInsideRoot -Path $BackupTarget -Root $RollbackOldRoot
        if ([bool]$Entry.hadOriginal) {
            if (Test-Path -LiteralPath $BackupTarget) {
                Remove-PathWithRetry -Path $InstallTarget
                Move-PathWithRetry -Source $BackupTarget -Destination $InstallTarget
            }
        }
        elseif (Test-Path -LiteralPath $InstallTarget) {
            Remove-PathWithRetry -Path $InstallTarget
        }
    }
    Remove-PathWithRetry -Path $StageRoot
    Remove-PathWithRetry -Path $RollbackRoot
}

function Invoke-PostCommitCleanup {
    $Failures = New-Object System.Collections.Generic.List[string]
    if ($TestFailPostCommitCleanup) {
        $Failures.Add('fault-injection')
        # Unary comma: see the note on Get-ForbiddenInstallRoots above -- a
        # single-entry List would otherwise unroll to a bare string at the
        # caller, and an empty one would unroll to $null.
        return , $Failures
    }
    try { Remove-PathWithRetry -Path $StageRoot } catch { $Failures.Add($_.Exception.Message) }
    try { Remove-PathWithRetry -Path $RollbackRoot } catch { $Failures.Add($_.Exception.Message) }
    return , $Failures
}

# ---------------------------------------------------------------------------
# Crash recovery: runs at the very start of every invocation, right after the
# ownership marker is ensured, before anything else touches the install root.
# ---------------------------------------------------------------------------

function Recover-InterruptedTransaction {
    if (-not (Test-Path -LiteralPath $RollbackRoot)) {
        Remove-PathWithRetry -Path $StageRoot
        return
    }
    if (-not (Test-Path -LiteralPath $TransactionPath -PathType Leaf)) {
        $HasBackupContent = (Test-Path -LiteralPath $RollbackOldRoot) -and
            (@(Get-ChildItem -LiteralPath $RollbackOldRoot -Force -ErrorAction SilentlyContinue).Count -gt 0)
        if ($HasBackupContent) {
            throw "MINOS_UPDATE_RECOVERY_REQUIRED: journal absent alors que des sauvegardes d'activation existent."
        }
        Remove-PathWithRetry -Path $StageRoot
        Remove-PathWithRetry -Path $RollbackRoot
        return
    }

    $Transaction = Read-TransactionManifest
    switch ([string]$Transaction.phase) {
        'committed' { [void](Invoke-PostCommitCleanup) }
        'activating' { Restore-Transaction -Transaction $Transaction }
        default { throw "MINOS_UPDATE_RECOVERY_REQUIRED: phase de transaction inconnue '$($Transaction.phase)'." }
    }
}

# ---------------------------------------------------------------------------
# Main.
# ---------------------------------------------------------------------------

$PackageManifest = Assert-Package
Assert-SafeInstallRoot
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
Ensure-OwnershipMarker
Assert-ManagedTreeSafe
Recover-InterruptedTransaction
Assert-ManagedTreeSafe

Stop-InstalledMcpProcesses

New-Item -ItemType Directory -Force -Path $StageRoot | Out-Null
foreach ($Directory in $StagedDirectoryRelativePaths) { Add-StagedDirectory -RelativePath $Directory }
foreach ($File in $StagedFileRelativePaths) { Add-StagedFile -RelativePath $File }

try {
    Invoke-Activation
}
catch {
    $ActivationFailure = $_
    try {
        Restore-Transaction -Transaction (Read-TransactionManifest)
    }
    catch {
        throw "MINOS_UPDATE_ROLLBACK_FAILED: activation='$($ActivationFailure.Exception.Message)' rollback='$($_.Exception.Message)'"
    }
    throw $ActivationFailure
}

$CleanupFailures = Invoke-PostCommitCleanup
$CleanupState = if ($CleanupFailures.Count -gt 0) { 'pending' } else { 'complete' }
if ($CleanupFailures.Count -gt 0) {
    Write-Warning "MINOS_UPDATE_CLEANUP_PENDING: $($CleanupFailures -join '; ')"
}

# RELEASE-MANIFEST.json is one of the staged flat files, so the copy now at
# $InstallRoot is byte-identical to what Assert-Package already parsed and
# validated at $PackageRoot -- no need to re-read and re-parse it here.
Write-Host "MINOS_UPDATE_COMMITTED version=$($PackageManifest.version) root=$InstallRoot cleanup=$CleanupState" -ForegroundColor Green
