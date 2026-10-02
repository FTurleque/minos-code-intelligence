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
# Space, apostrophe, and accented characters together -- catches PowerShell
# quoting/encoding bugs across the whole path, not just the ASCII subset.
$Sandbox = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-upgrade-verify-' + [Guid]::NewGuid().ToString('N') + " O'Brien tst éè with spaces")
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
    "# configure-mcp-clients-setup $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\configure-mcp-clients-setup.ps1') -Encoding ascii
    "# configure-codex-mcp $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\configure-codex-mcp.ps1') -Encoding ascii
    "# configure-runtime-settings $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\configure-runtime-settings.ps1') -Encoding ascii
    "# uninstall-mcp-clients $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\uninstall-mcp-clients.ps1') -Encoding ascii
    "# update-installation $Version" | Set-Content -LiteralPath (Join-Path $Root 'integration\update-installation.ps1') -Encoding ascii
    "# Dockerfile $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\Dockerfile.mcp.release') -Encoding ascii
    "# compose $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\compose-mcp.prod.yaml') -Encoding ascii
    "# compose-connected $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\compose-mcp.connected.yaml') -Encoding ascii
    "# prod-mcp-release $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\prod-mcp-release.ps1') -Encoding ascii
    "# mcp-lifecycle $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\mcp-lifecycle.ps1') -Encoding ascii
    "# configure-docker-mcp $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\configure-docker-mcp.ps1') -Encoding ascii
    "# configure-m30-docker-services $Version" | Set-Content -LiteralPath (Join-Path $Root 'docker\scripts\configure-m30-docker-services.ps1') -Encoding ascii
    '{"bomFormat":"CycloneDX"}' | Set-Content -LiteralPath (Join-Path $Root 'supply-chain\minos.cdx.json') -Encoding ascii
    "Third party notices $Version" | Set-Content -LiteralPath (Join-Path $Root 'supply-chain\THIRD-PARTY-NOTICES.txt') -Encoding ascii
    "@echo off`r`nrem minos $Version" | Set-Content -LiteralPath (Join-Path $Root 'minos.cmd') -Encoding ascii
    "@echo off`r`nrem minos-mcp $Version" | Set-Content -LiteralPath (Join-Path $Root 'minos-mcp.cmd') -Encoding ascii
    "java.base" | Set-Content -LiteralPath (Join-Path $Root 'RUNTIME-MODULES.txt') -Encoding ascii
    "MINOS $Version" | Set-Content -LiteralPath (Join-Path $Root 'README.txt') -Encoding ascii
    "# install $Version" | Set-Content -LiteralPath (Join-Path $Root 'install.ps1') -Encoding ascii

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

    # --- Scenario 10: an installation made BEFORE the compose files were renamed
    # (docker\compose.mcp.*.yaml, audit S11) is updated by a package that ships the new names. `docker` is staged and
    # moved whole, so the old names must vanish with the old directory (two compose files side by side would be
    # dangerous: gates and tools glob docker\compose*.yaml), and a failed activation must bring the old names back.
    $LegacyRoot = Join-Path $Sandbox 'scenario10-pre-rename-install'
    $PackageLegacyV1 = Join-Path $Sandbox 'package-v1-scenario10'
    $PackageLegacyV2 = Join-Path $Sandbox 'package-v2-scenario10'
    New-FixturePackage -Root $PackageLegacyV1 -Version '1.0.0'
    New-FixturePackage -Root $PackageLegacyV2 -Version '1.1.0'
    Invoke-UpdaterInProcess -PackageRoot $PackageLegacyV1 -InstallRoot $LegacyRoot
    # The installed tree of a pre-rename release: same files, old names (the updater of that release produced it).
    Rename-Item -LiteralPath (Join-Path $LegacyRoot 'docker\compose-mcp.prod.yaml') -NewName 'compose.mcp.prod.yaml'
    Rename-Item -LiteralPath (Join-Path $LegacyRoot 'docker\compose-mcp.connected.yaml') -NewName 'compose.mcp.connected.yaml'
    $LegacyNames = @('compose.mcp.prod.yaml', 'compose.mcp.connected.yaml')
    $CurrentNames = @('compose-mcp.prod.yaml', 'compose-mcp.connected.yaml')

    $Threw10 = $false
    try { Invoke-UpdaterInProcess -PackageRoot $PackageLegacyV2 -InstallRoot $LegacyRoot -TestFailActivationAfterEntries 4 }
    catch { $Threw10 = $true }
    Assert-True $Threw10 'Injected activation failure (after the docker directory was replaced) did not propagate.'
    foreach ($Name in $LegacyNames) { Assert-True (Test-Path -LiteralPath (Join-Path $LegacyRoot "docker\$Name")) "Rollback did not restore docker\$Name." }
    foreach ($Name in $CurrentNames) { Assert-True (-not (Test-Path -LiteralPath (Join-Path $LegacyRoot "docker\$Name"))) "docker\$Name survived a rolled-back update." }
    Assert-True ((Get-VersionLine $LegacyRoot) -match '1\.0\.0') 'The pre-rename install was not restored to its own version.'

    Invoke-UpdaterInProcess -PackageRoot $PackageLegacyV2 -InstallRoot $LegacyRoot
    Assert-True ((Get-VersionLine $LegacyRoot) -match '1\.1\.0') 'The update of a pre-rename install did not activate the new version.'
    foreach ($Name in $CurrentNames) { Assert-True (Test-Path -LiteralPath (Join-Path $LegacyRoot "docker\$Name")) "docker\$Name is missing after the update." }
    foreach ($Name in $LegacyNames) { Assert-True (-not (Test-Path -LiteralPath (Join-Path $LegacyRoot "docker\$Name"))) "The pre-rename docker\$Name survived the update (two compose files side by side)." }
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $LegacyRoot 'docker') -Filter 'compose*' -File).Count -eq 2) 'docker\ must hold exactly the two compose files of the new package.'
    Write-Host 'Scenario 10 (update of a pre-rename install: old compose names replaced, restored on rollback) PASS' -ForegroundColor Green

    # --- Scenario 11: the runtime directory (%LOCALAPPDATA%\MINOS\docker\runtime) is NOT part of the staged tree, so the
    # update does not touch its compose copy. mcp-lifecycle.ps1 migrates it on the first action that follows. Its helper
    # functions are taken from the real script (AST) and run against a scratch runtime, without Docker.
    $LifecyclePath = Join-Path $RepoRoot 'docker\scripts\mcp-lifecycle.ps1'
    $LifecycleErrors = $null
    $LifecycleAst = [System.Management.Automation.Language.Parser]::ParseFile($LifecyclePath, [ref]$null, [ref]$LifecycleErrors)
    Assert-True (@($LifecycleErrors).Count -eq 0) 'mcp-lifecycle.ps1 has syntax errors.'
    $Wanted = @('Move-LegacyRuntimeCompose', 'Resolve-ComposeSource')
    $Definitions = @($LifecycleAst.FindAll({ param($Node) $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -in $Wanted }, $true))
    Assert-True ($Definitions.Count -eq 2) "mcp-lifecycle.ps1 must define $($Wanted -join ' and ')."
    foreach ($Definition in $Definitions) { . ([scriptblock]::Create($Definition.Extent.Text)) }
    $LifecycleText = Get-Content -LiteralPath $LifecyclePath -Raw
    Assert-True ($LifecycleText -match '(?s)Move-LegacyRuntimeCompose\s+switch \(\$Action\)') 'The migration must run before every action (just before the switch).'
    Assert-True ($LifecycleText.Contains('Resolve-ComposeSource $SourceRoot')) 'Install must read the recipe through Resolve-ComposeSource (previous releases keep the old name).'

    $RuntimeRoot = Join-Path $Sandbox 'scenario11-runtime'
    $ComposeFile = Join-Path $RuntimeRoot 'compose-mcp.prod.yaml'
    $LegacyComposeFile = Join-Path $RuntimeRoot 'compose.mcp.prod.yaml'
    function Reset-Runtime { Remove-Item -LiteralPath $RuntimeRoot -Recurse -Force -ErrorAction SilentlyContinue; New-Item -ItemType Directory -Force -Path $RuntimeRoot | Out-Null }

    # Old file only, holding the connected profile swapped in by the M30 configurator: moved, content intact.
    Reset-Runtime
    '# connected profile, pre-rename' | Set-Content -LiteralPath $LegacyComposeFile -Encoding ascii
    Move-LegacyRuntimeCompose 6>$null
    Assert-True (-not (Test-Path -LiteralPath $LegacyComposeFile)) 'The pre-rename runtime compose file was left behind.'
    Assert-True ((Get-Content -LiteralPath $ComposeFile -Raw).Trim() -eq '# connected profile, pre-rename') 'The migrated runtime compose file lost its content (the active profile must survive).'
    Move-LegacyRuntimeCompose 6>$null   # idempotent
    Assert-True ((Get-Content -LiteralPath $ComposeFile -Raw).Trim() -eq '# connected profile, pre-rename') 'A second migration changed the file.'

    # Nothing at all (not installed): no-op, the "not installed" refusal stays Require-Installed's.
    Reset-Runtime
    Move-LegacyRuntimeCompose 6>$null
    Assert-True (-not (Test-Path -LiteralPath $ComposeFile) -and -not (Test-Path -LiteralPath $LegacyComposeFile)) 'The migration invented a compose file.'

    # Both: the new name wins, nothing is deleted or overwritten, the operator is told.
    Reset-Runtime
    '# current' | Set-Content -LiteralPath $ComposeFile -Encoding ascii
    '# stale' | Set-Content -LiteralPath $LegacyComposeFile -Encoding ascii
    $Warnings = @(Move-LegacyRuntimeCompose 3>&1 6>$null | Where-Object { $_ -is [System.Management.Automation.WarningRecord] })
    Assert-True ($Warnings.Count -eq 1) 'Both runtime compose files present: exactly one warning was expected.'
    Assert-True ((Get-Content -LiteralPath $ComposeFile -Raw).Trim() -eq '# current') 'The current runtime compose file was overwritten.'
    Assert-True ((Get-Content -LiteralPath $LegacyComposeFile -Raw).Trim() -eq '# stale') 'The pre-rename runtime compose file was deleted without a decision.'

    # Resolve-ComposeSource: a tree older than the rename keeps its old name (previous release in the upgrade qualification).
    $SourceTree = Join-Path $Sandbox 'scenario11-source'
    New-Item -ItemType Directory -Force -Path (Join-Path $SourceTree 'docker') | Out-Null
    $Threw11 = $false
    try { [void](Resolve-ComposeSource $SourceTree) } catch { $Threw11 = $true }
    Assert-True $Threw11 'A tree with no compose recipe must be refused.'
    '# legacy' | Set-Content -LiteralPath (Join-Path $SourceTree 'docker\compose.mcp.prod.yaml') -Encoding ascii
    Assert-True ((Split-Path -Leaf (Resolve-ComposeSource $SourceTree)) -eq 'compose.mcp.prod.yaml') 'A pre-rename tree must be read through its old name.'
    '# current' | Set-Content -LiteralPath (Join-Path $SourceTree 'docker\compose-mcp.prod.yaml') -Encoding ascii
    Assert-True ((Split-Path -Leaf (Resolve-ComposeSource $SourceTree)) -eq 'compose-mcp.prod.yaml') 'When both names exist the new one must win.'
    # The developer entry point (docker\scripts\prod-mcp.ps1, runtime under %LOCALAPPDATA%\MINOS\runtime) carries the
    # same migration, with its own variable names.
    $ProdAst = [System.Management.Automation.Language.Parser]::ParseFile((Join-Path $RepoRoot 'docker\scripts\prod-mcp.ps1'), [ref]$null, [ref]$LifecycleErrors)
    Assert-True (@($LifecycleErrors).Count -eq 0) 'prod-mcp.ps1 has syntax errors.'
    $ProdDefinition = @($ProdAst.FindAll({ param($Node) $Node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $Node.Name -eq 'Move-LegacyMinosComposeFile' }, $true))
    Assert-True ($ProdDefinition.Count -eq 1) 'prod-mcp.ps1 must define Move-LegacyMinosComposeFile.'
    . ([scriptblock]::Create($ProdDefinition[0].Extent.Text))
    $composeFile = $ComposeFile
    $legacyComposeFile = $LegacyComposeFile
    Reset-Runtime
    '# dev runtime, pre-rename' | Set-Content -LiteralPath $LegacyComposeFile -Encoding ascii
    Move-LegacyMinosComposeFile 6>$null
    Assert-True ((-not (Test-Path -LiteralPath $LegacyComposeFile)) -and ((Get-Content -LiteralPath $ComposeFile -Raw).Trim() -eq '# dev runtime, pre-rename')) 'prod-mcp.ps1: the pre-rename runtime compose file was not migrated with its content.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $RepoRoot 'docker\scripts\prod-mcp.ps1') -Raw) -match '(?s)Move-LegacyMinosComposeFile\s+switch \(\$Action\)') 'prod-mcp.ps1: the migration must run before every action.'
    Write-Host 'Scenario 11 (pre-rename runtime compose file migrated on the first action; old-name source trees still readable) PASS' -ForegroundColor Green

    Write-Host 'WINDOWS_UPGRADE_TRANSACTION_VALID' -ForegroundColor Green
}
finally {
    Remove-Item -LiteralPath $Sandbox -Recurse -Force -ErrorAction SilentlyContinue
}
