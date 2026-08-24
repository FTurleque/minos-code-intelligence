[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows upgrade transaction verification currently targets Windows hosts.'
}

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Updater = Join-Path $RepoRoot 'scripts\install\update-installation.ps1'
if (-not (Test-Path -LiteralPath $Updater -PathType Leaf)) {
    throw "Transactional updater not found: $Updater"
}

# Deliberately embeds a space to catch path-quoting bugs across PowerShell/Exec boundaries.
$Sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-upgrade-verify-' + [Guid]::NewGuid().ToString('N') + ' with spaces')
New-Item -ItemType Directory -Force -Path $Sandbox | Out-Null

function Assert-True([bool] $Condition, [string] $Message) {
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
}

function Get-VersionLine([string] $InstallRoot) {
    return (Get-Content -LiteralPath (Join-Path $InstallRoot 'VERSION') | Where-Object { $_ -match '^version=' } | Select-Object -First 1)
}

function New-FixturePackage {
    param(
        [string] $Root,
        [string] $Version,
        [switch] $IncludeObsoleteFile,
        [switch] $IncludeNewMarker
    )
    foreach ($Directory in @('app', 'lib', 'docker\scripts', 'integration', 'supply-chain')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $Root $Directory) | Out-Null
    }
    "fake-exe-$Version" | Set-Content -LiteralPath (Join-Path $Root 'app\minos.exe') -Encoding ascii
    "fake-jar-$Version" | Set-Content -LiteralPath (Join-Path $Root 'lib\minos.jar') -Encoding ascii
    "# switch-mcp-backend $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\switch-mcp-backend.ps1') -Encoding ascii
    "# probe-mcp-backend $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\probe-mcp-backend.ps1') -Encoding ascii
    "# detect-mcp-clients $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\detect-mcp-clients.ps1') -Encoding ascii
    "# configure-mcp-clients $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\configure-mcp-clients.ps1') -Encoding ascii
    "# Dockerfile $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\Dockerfile.mcp.release') -Encoding ascii
    "# compose $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\compose.mcp.prod.yaml') -Encoding ascii
    "# prod-mcp-release $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\prod-mcp-release.ps1') -Encoding ascii
    "# configure-docker-mcp $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\configure-docker-mcp.ps1') -Encoding ascii
    '{"bomFormat":"CycloneDX"}' | Set-Content -LiteralPath (Join-Path $Root 'supply-chain\minos.cdx.json') -Encoding ascii
    "Third party notices $Version" | Set-Content -LiteralPath (Join-Path $Root 'supply-chain\THIRD-PARTY-NOTICES.txt') -Encoding ascii
    "@echo off`r`nrem minos $Version" | Set-Content -LiteralPath (Join-Path $Root 'minos.cmd') -Encoding ascii
    "@echo off`r`nrem minos-mcp $Version" | Set-Content -LiteralPath (Join-Path $Root 'minos-mcp.cmd') -Encoding ascii
    "java.base" | Set-Content -LiteralPath (Join-Path $Root 'RUNTIME-MODULES.txt') -Encoding ascii
    "MINOS $Version" | Set-Content -LiteralPath (Join-Path $Root 'README.txt') -Encoding ascii

    $Commit = '0' * 40
    ([ordered]@{ schemaVersion = 1; version = $Version; commit = $Commit } | ConvertTo-Json) |
        Set-Content -LiteralPath (Join-Path $Root 'RELEASE-MANIFEST.json') -Encoding ascii
    @("version=$Version", "commit=$Commit", 'java=24', 'builtAt=2026-01-01T00:00:00Z') -join "`r`n" |
        Set-Content -LiteralPath (Join-Path $Root 'VERSION') -Encoding ascii

    if ($IncludeObsoleteFile) {
        'obsolete' | Set-Content -LiteralPath (Join-Path $Root 'app\obsolete.txt') -Encoding ascii
    }
    if ($IncludeNewMarker) {
        "new-$Version" | Set-Content -LiteralPath (Join-Path $Root 'app\new.txt') -Encoding ascii
    }
}

function Invoke-UpdaterInProcess {
    param(
        [string] $PackageRoot,
        [string] $InstallRoot,
        [int] $TestFailActivationAfterEntries = 0,
        [switch] $TestFailPostCommitCleanup
    )
    $Params = @{ PackageRoot = $PackageRoot; InstallRoot = $InstallRoot; SkipProcessStop = $true }
    if ($TestFailActivationAfterEntries -gt 0) { $Params['TestFailActivationAfterEntries'] = $TestFailActivationAfterEntries }
    if ($TestFailPostCommitCleanup) { $Params['TestFailPostCommitCleanup'] = $true }
    & $Updater @Params
}

function Invoke-UpdaterAsChildProcess {
    param(
        [string] $PackageRoot,
        [string] $InstallRoot,
        [int] $TestCrashActivationAfterEntries
    )
    # Start-Process -ArgumentList does not reliably quote array elements that
    # contain spaces (it joins them with a bare space internally), so build a
    # single pre-quoted argument string instead -- the same convention already
    # used by every Exec() call in minos-installer.iss.template.
    $Arguments =
        '-NoProfile -ExecutionPolicy Bypass -File "' + $Updater +
        '" -PackageRoot "' + $PackageRoot +
        '" -InstallRoot "' + $InstallRoot +
        '" -SkipProcessStop -TestCrashActivationAfterEntries ' + $TestCrashActivationAfterEntries
    $Process = Start-Process -FilePath 'powershell.exe' -ArgumentList $Arguments -PassThru -Wait -WindowStyle Hidden
    return $Process.ExitCode
}

try {
    # --- Scenario 1: unsafe/foreign non-empty root rejected before any mutation ---
    $Scenario1Root = Join-Path $Sandbox 'scenario1-unsafe-root'
    New-Item -ItemType Directory -Force -Path (Join-Path $Scenario1Root 'app') | Out-Null
    'foreign' | Set-Content -LiteralPath (Join-Path $Scenario1Root 'app\foreign.marker') -Encoding ascii
    $Package1 = Join-Path $Sandbox 'package-v1-scenario1'
    New-FixturePackage -Root $Package1 -Version '1.0.0' -IncludeObsoleteFile

    $Threw1 = $false
    try { Invoke-UpdaterInProcess -PackageRoot $Package1 -InstallRoot $Scenario1Root }
    catch {
        $Threw1 = $true
        Assert-True ($_.Exception.Message -like '*MINOS_UPDATE_UNSAFE_INSTALL_ROOT*') "Expected MINOS_UPDATE_UNSAFE_INSTALL_ROOT, got: $($_.Exception.Message)"
    }
    Assert-True $Threw1 'Unsafe non-empty install root was not rejected.'
    Assert-True (Test-Path -LiteralPath (Join-Path $Scenario1Root 'app\foreign.marker')) 'Foreign content was touched despite rejection.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $Scenario1Root '.minos-installation.json'))) 'Ownership marker was written despite rejection.'
    Write-Host 'Scenario 1 (unsafe non-empty root rejected pre-mutation) PASS' -ForegroundColor Green

    # --- Scenario 2: reparse point (junction) inside the managed tree rejected ---
    $Scenario2Root = Join-Path $Sandbox 'scenario2-junction'
    $Package2a = Join-Path $Sandbox 'package-v1-scenario2'
    New-FixturePackage -Root $Package2a -Version '1.0.0'
    Invoke-UpdaterInProcess -PackageRoot $Package2a -InstallRoot $Scenario2Root
    Assert-True (Test-Path -LiteralPath (Join-Path $Scenario2Root '.minos-installation.json')) 'Fresh install did not create an ownership marker.'

    $ExternalTarget = Join-Path $Sandbox 'scenario2-external'
    New-Item -ItemType Directory -Force -Path $ExternalTarget | Out-Null
    'external' | Set-Content -LiteralPath (Join-Path $ExternalTarget 'external.marker') -Encoding ascii

    $IntegrationPath = Join-Path $Scenario2Root 'integration'
    Remove-Item -LiteralPath $IntegrationPath -Recurse -Force
    New-Item -ItemType Junction -Path $IntegrationPath -Target $ExternalTarget | Out-Null

    $Package2b = Join-Path $Sandbox 'package-v2-scenario2'
    New-FixturePackage -Root $Package2b -Version '1.1.0'

    $Threw2 = $false
    try { Invoke-UpdaterInProcess -PackageRoot $Package2b -InstallRoot $Scenario2Root }
    catch {
        $Threw2 = $true
        Assert-True ($_.Exception.Message -like '*MINOS_UPDATE_REPARSE_POINT*') "Expected MINOS_UPDATE_REPARSE_POINT, got: $($_.Exception.Message)"
    }
    Assert-True $Threw2 'Reparse point (junction) inside the managed tree was not rejected.'
    Assert-True (Test-Path -LiteralPath (Join-Path $ExternalTarget 'external.marker')) 'External junction target content was disturbed.'
    Assert-True (@(Get-ChildItem -LiteralPath $ExternalTarget -Force).Count -eq 1) 'Something was written through the junction into the external target.'
    Assert-True ((Get-VersionLine $Scenario2Root) -match '1\.0\.0') 'Install root did not remain at the pre-upgrade version after reparse-point rejection.'
    Write-Host 'Scenario 2 (reparse point inside managed tree rejected) PASS' -ForegroundColor Green

    # --- Scenario 3 + 5: fresh install, then a clean upgrade with stale-file
    # removal and preservation of everything the engine does not manage ---
    $MainRoot = Join-Path $Sandbox 'scenario-upgrade-root with space'
    $PackageV1 = Join-Path $Sandbox 'package-v1-main'
    $PackageV2 = Join-Path $Sandbox 'package-v2-main'
    $PackageV3 = Join-Path $Sandbox 'package-v3-main'
    New-FixturePackage -Root $PackageV1 -Version '1.0.0' -IncludeObsoleteFile
    New-FixturePackage -Root $PackageV2 -Version '1.1.0' -IncludeNewMarker
    New-FixturePackage -Root $PackageV3 -Version '1.2.0' -IncludeNewMarker

    Invoke-UpdaterInProcess -PackageRoot $PackageV1 -InstallRoot $MainRoot
    Assert-True (Test-Path -LiteralPath (Join-Path $MainRoot 'app\obsolete.txt')) 'Fresh v1 install is missing its own fixture file.'
    $Marker = Get-Content -LiteralPath (Join-Path $MainRoot '.minos-installation.json') -Raw | ConvertFrom-Json
    Assert-True ($Marker.name -eq 'MINOS') 'Ownership marker has the wrong application name.'
    [void][guid]::Parse([string]$Marker.installationId)

    # Simulate a real installation: .docker-mcp-managed sits at the InstallRoot
    # root (sibling of the staged directories, per switch-mcp-backend.ps1), and
    # all other persistent state lives entirely outside InstallRoot.
    'docker-marker-content' | Set-Content -LiteralPath (Join-Path $MainRoot '.docker-mcp-managed') -Encoding ascii
    $OutsideDataRoot = Join-Path $Sandbox 'outside-data-root'
    New-Item -ItemType Directory -Force -Path $OutsideDataRoot | Out-Null
    'user-data' | Set-Content -LiteralPath (Join-Path $OutsideDataRoot 'preserve.marker') -Encoding ascii

    Invoke-UpdaterInProcess -PackageRoot $PackageV2 -InstallRoot $MainRoot

    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot 'app\obsolete.txt'))) 'Stale program file from v1 survived the upgrade to v2.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $MainRoot 'app\new.txt') -Raw).Trim() -eq 'new-1.1.0') 'v2 content was not activated.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $MainRoot '.docker-mcp-managed') -Raw).Trim() -eq 'docker-marker-content') '.docker-mcp-managed was not preserved across the upgrade.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $OutsideDataRoot 'preserve.marker') -Raw).Trim() -eq 'user-data') 'Data outside InstallRoot was touched by the upgrade.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-staging'))) 'Staging residue left after a clean upgrade.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-rollback'))) 'Rollback residue left after a clean upgrade.'
    Write-Host 'Scenario 3/5 (fresh install + clean upgrade, stale-file cleanup, preservation) PASS' -ForegroundColor Green

    # --- Scenario 6: synchronous rollback on an injected activation failure ---
    $PreFailureVersion = Get-VersionLine $MainRoot
    $Threw6 = $false
    try { Invoke-UpdaterInProcess -PackageRoot $PackageV3 -InstallRoot $MainRoot -TestFailActivationAfterEntries 3 }
    catch {
        $Threw6 = $true
        Assert-True ($_.Exception.Message -like '*MINOS_UPDATE_TEST_ACTIVATION_FAILURE*') "Expected MINOS_UPDATE_TEST_ACTIVATION_FAILURE, got: $($_.Exception.Message)"
    }
    Assert-True $Threw6 'Injected activation failure did not propagate.'
    Assert-True ((Get-VersionLine $MainRoot) -eq $PreFailureVersion) 'Install root was not fully restored to the pre-upgrade state after rollback.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $MainRoot 'app\new.txt') -Raw).Trim() -eq 'new-1.1.0') 'app\new.txt was not restored to its pre-upgrade content after rollback.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-staging'))) 'Staging residue left after rollback.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-rollback'))) 'Rollback residue left after rollback.'
    Write-Host 'Scenario 6 (synchronous rollback on injected activation failure) PASS' -ForegroundColor Green

    # --- Scenario 7: crash mid-activation (FailFast, detached child process),
    # then next-launch recovery converges to a clean, requested state ---
    $ExitCode7 = Invoke-UpdaterAsChildProcess -PackageRoot $PackageV3 -InstallRoot $MainRoot -TestCrashActivationAfterEntries 3
    Assert-True ($ExitCode7 -ne 0) 'Crash-injected child process exited with code 0.'
    $TransactionPath = Join-Path $MainRoot '.install-rollback\transaction.json'
    Assert-True (Test-Path -LiteralPath $TransactionPath) 'No transaction journal survived the simulated crash.'
    $Journal7 = Get-Content -LiteralPath $TransactionPath -Raw | ConvertFrom-Json
    Assert-True ($Journal7.phase -eq 'activating') 'Journal phase after crash is not activating.'
    Assert-True ($Journal7.schemaVersion -eq '1.1') 'Journal schemaVersion mismatch.'
    Assert-True ($Journal7.checksumSha256 -match '^[0-9a-f]{64}$') 'Journal checksum is not a well-formed SHA-256 hex string.'

    Invoke-UpdaterInProcess -PackageRoot $PackageV2 -InstallRoot $MainRoot
    Assert-True ((Get-VersionLine $MainRoot) -match '1\.1\.0') 'Recovery did not converge to the requested clean state.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-staging'))) 'Staging residue left after crash recovery.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-rollback'))) 'Rollback residue left after crash recovery.'
    Write-Host 'Scenario 7 (crash mid-activation + next-launch recovery) PASS' -ForegroundColor Green

    # --- Scenario 8: a post-commit cleanup failure is non-fatal and is
    # retried (and completed) by a later invocation ---
    Invoke-UpdaterInProcess -PackageRoot $PackageV3 -InstallRoot $MainRoot -TestFailPostCommitCleanup
    Assert-True ((Get-VersionLine $MainRoot) -match '1\.2\.0') 'v3 was not activated despite a cleanup-only fault injection.'
    $Journal8Path = Join-Path $MainRoot '.install-rollback\transaction.json'
    Assert-True (Test-Path -LiteralPath $Journal8Path) 'Journal missing after a cleanup-only failure -- the committed state must remain provable.'
    $Journal8 = Get-Content -LiteralPath $Journal8Path -Raw | ConvertFrom-Json
    Assert-True ($Journal8.phase -eq 'committed') 'Journal phase after a cleanup-only failure is not committed.'

    Invoke-UpdaterInProcess -PackageRoot $PackageV3 -InstallRoot $MainRoot
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-staging'))) 'Staging residue left after the deferred cleanup retry.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $MainRoot '.install-rollback'))) 'Rollback residue left after the deferred cleanup retry.'
    Write-Host 'Scenario 8 (post-commit cleanup failure is non-fatal, retried later) PASS' -ForegroundColor Green

    # --- Scenario 9: a corrupted transaction journal must fail closed, never
    # be silently accepted or drive an unsafe recovery ---
    $CorruptRoot = Join-Path $Sandbox 'scenario9-corrupt-journal'
    $PackageCorruptA = Join-Path $Sandbox 'package-v1-scenario9'
    $PackageCorruptB = Join-Path $Sandbox 'package-v2-scenario9'
    New-FixturePackage -Root $PackageCorruptA -Version '1.0.0'
    New-FixturePackage -Root $PackageCorruptB -Version '1.1.0'
    Invoke-UpdaterInProcess -PackageRoot $PackageCorruptA -InstallRoot $CorruptRoot

    $ExitCode9 = Invoke-UpdaterAsChildProcess -PackageRoot $PackageCorruptB -InstallRoot $CorruptRoot -TestCrashActivationAfterEntries 2
    Assert-True ($ExitCode9 -ne 0) 'Crash-injected child process (scenario 9 setup) exited with code 0.'
    $CorruptJournalPath = Join-Path $CorruptRoot '.install-rollback\transaction.json'
    Assert-True (Test-Path -LiteralPath $CorruptJournalPath) 'No journal to corrupt after the simulated crash.'

    # Tamper the checksum without touching the rest of the document -- the
    # self-check must catch this rather than trusting the file's own claim.
    $OriginalJournalText = Get-Content -LiteralPath $CorruptJournalPath -Raw
    $TamperedJournal = $OriginalJournalText -replace '"checksumSha256"\s*:\s*"[0-9a-f]{64}"', ('"checksumSha256": "' + ('0' * 64) + '"')
    Assert-True ($TamperedJournal -ne $OriginalJournalText) 'Failed to tamper the journal checksum for the corruption test.'
    Set-Content -LiteralPath $CorruptJournalPath -Value $TamperedJournal -Encoding ascii -NoNewline

    $Threw9 = $false
    try { Invoke-UpdaterInProcess -PackageRoot $PackageCorruptA -InstallRoot $CorruptRoot }
    catch {
        $Threw9 = $true
        Assert-True ($_.Exception.Message -like '*MINOS_UPDATE_RECOVERY_REQUIRED*') "Expected MINOS_UPDATE_RECOVERY_REQUIRED for a corrupted journal, got: $($_.Exception.Message)"
    }
    Assert-True $Threw9 'A corrupted transaction journal was not rejected -- recovery must fail closed.'
    Write-Host 'Scenario 9 (corrupted transaction journal fails closed) PASS' -ForegroundColor Green

    Write-Host 'WINDOWS_UPGRADE_TRANSACTION_VALID' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $Sandbox -Recurse -Force -ErrorAction SilentlyContinue
}
