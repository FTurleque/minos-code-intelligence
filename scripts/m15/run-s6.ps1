[CmdletBinding()]
param(
    [switch] $SkipM14Replay,
    [switch] $SkipProviderReplays,
    [switch] $ValidateDocker
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$Branch = 'm15-s6-persistence'
$Utf8 = [System.Text.UTF8Encoding]::new($false)

function Invoke-GitChecked {
    param([Parameter(Mandatory = $true)][string[]] $Arguments)
    & git @Arguments
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed (exit=$LASTEXITCODE)" }
}

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Resolve-CurrentPowerShellHost {
    try {
        $hostPath = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        if (-not [string]::IsNullOrWhiteSpace($hostPath) -and (Test-Path -LiteralPath $hostPath -PathType Leaf)) {
            return $hostPath
        }
    } catch { }
    if ($env:OS -eq 'Windows_NT' -and -not [string]::IsNullOrWhiteSpace($env:SystemRoot)) {
        $fallback = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
        if (Test-Path -LiteralPath $fallback -PathType Leaf) { return $fallback }
    }
    foreach ($name in @('pwsh.exe','powershell.exe')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'Unable to resolve PowerShell host for runner restart.'
}

function Restart-UpdatedRunner {
    param([Parameter(Mandatory = $true)][string] $Head)
    $arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File',(Join-Path $RepoRoot 'scripts\m15\run-s6.ps1'))
    if ($SkipM14Replay) { $arguments += '-SkipM14Replay' }
    if ($SkipProviderReplays) { $arguments += '-SkipProviderReplays' }
    if ($ValidateDocker) { $arguments += '-ValidateDocker' }
    Write-Host "Runner changed after pull; restarting from exact HEAD $Head..." -ForegroundColor Yellow
    & (Resolve-CurrentPowerShellHost) @arguments
    if ($LASTEXITCODE -ne 0) { throw "Restarted M15-S6 runner failed (exit=$LASTEXITCODE)." }
}

function Read-RepoText {
    param([Parameter(Mandatory = $true)][string] $RelativePath)
    $path = Join-Path $RepoRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required file is missing: $path" }
    [System.IO.File]::ReadAllText($path, $Utf8)
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string] $Content,
        [Parameter(Mandatory = $true)][string] $Expected,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    if (-not $Content.Contains($Expected)) { throw "$Owner must contain: $Expected" }
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string] $Content,
        [Parameter(Mandatory = $true)][string] $Forbidden,
        [Parameter(Mandatory = $true)][string] $Owner
    )
    if ($Content.Contains($Forbidden)) { throw "$Owner still contains forbidden S6 responsibility: $Forbidden" }
}

function Assert-S6Shape {
    $store = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\FileSymbolSnapshotStore.java'
    foreach ($required in @(
        'SnapshotRepository snapshotRepository',
        'ActiveSnapshotRepository activeSnapshotRepository',
        'SnapshotIntegrityService integrityService',
        'SnapshotRetentionService retentionService',
        'SnapshotCodec codecV1',
        'SnapshotCodec codecV2',
        'publishSnapshot(snapshot, codecV1)',
        'publishSnapshot(snapshot, codecV2)',
        'integrityService.verifyChecksum',
        'integrityService.verifyMetadata'
    )) {
        Assert-Contains $store $required 'FileSymbolSnapshotStore'
    }
    foreach ($forbidden in @(
        'DataInputStream',
        'DataOutputStream',
        'DigestOutputStream',
        'SNAPSHOT_MAGIC',
        'POINTER_MAGIC',
        'active.pointer',
        'writeKnowledgeSnapshotV2(',
        'readKnowledgeSnapshotV2(',
        'writePointer(',
        'readPointer('
    )) {
        Assert-NotContains $store $forbidden 'FileSymbolSnapshotStore'
    }
    $storeLines = ($store -split "`r?`n").Count
    if ($storeLines -gt 260) { throw "FileSymbolSnapshotStore remains too concentrated: lines=$storeLines" }

    $expectedFiles = @(
        'SnapshotDescriptor.java',
        'SnapshotRepository.java',
        'ActiveSnapshotRepository.java',
        'SnapshotIntegrityService.java',
        'SnapshotRetentionService.java',
        'SnapshotCodec.java',
        'SnapshotCodecV1.java',
        'SnapshotCodecV2.java',
        'SnapshotBinaryCodecSupport.java'
    )
    foreach ($file in $expectedFiles) {
        $path = Join-Path $RepoRoot "minos-storage-local\src\main\java\com\minos\store\$file"
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing S6 persistence component: $file" }
    }

    $codecV1 = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\SnapshotCodecV1.java'
    $codecV2 = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\SnapshotCodecV2.java'
    Assert-Contains $codecV1 'SnapshotBinaryCodecSupport.writeSymbolSnapshotV1' 'SnapshotCodecV1'
    Assert-Contains $codecV1 'SnapshotBinaryCodecSupport.readSymbolSnapshotV1' 'SnapshotCodecV1'
    Assert-Contains $codecV2 'SnapshotBinaryCodecSupport.writeKnowledgeSnapshotV2' 'SnapshotCodecV2'
    Assert-Contains $codecV2 'SnapshotBinaryCodecSupport.readKnowledgeSnapshotV2' 'SnapshotCodecV2'

    $active = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\ActiveSnapshotRepository.java'
    Assert-Contains $active 'active.pointer' 'ActiveSnapshotRepository'
    Assert-Contains $active 'void promote(' 'ActiveSnapshotRepository'
    Assert-Contains $active 'Optional<SnapshotDescriptor> read(' 'ActiveSnapshotRepository'

    $retention = Read-RepoText 'minos-storage-local\src\main\java\com\minos\store\SnapshotRetentionService.java'
    Assert-Contains $retention 'deleteHistoricalSnapshots(' 'SnapshotRetentionService'
    Assert-Contains $retention 'active snapshot must not be deleted by retention' 'SnapshotRetentionService'

    $codecTest = Read-RepoText 'minos-storage-local\src\test\java\com\minos\store\SnapshotCodecTest.java'
    foreach ($expected in @(
        'v1RoundTripsIndependentlyFromRepository',
        'v2RoundTripsIndependentlyFromRepository',
        'v1RejectsM3Collections'
    )) { Assert-Contains $codecTest $expected 'SnapshotCodecTest' }

    $componentTest = Read-RepoText 'minos-storage-local\src\test\java\com\minos\store\SnapshotPersistenceComponentsTest.java'
    foreach ($expected in @(
        'activePointerRoundTripsV1AndV2Metadata',
        'retentionDeletesOnlyExplicitHistoricalFiles',
        'integrityDetectsChecksumAndPointerMetadataMismatch'
    )) { Assert-Contains $componentTest $expected 'SnapshotPersistenceComponentsTest' }

    $legacyMain = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\main\java') -Recurse -File -ErrorAction SilentlyContinue)
    $legacyTests = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'src\test\java') -Recurse -File -ErrorAction SilentlyContinue)
    if ($legacyMain.Count -ne 0 -or $legacyTests.Count -ne 0) {
        throw "S6 must preserve the S2 physical layout; legacy main=$($legacyMain.Count) tests=$($legacyTests.Count)."
    }

    $mainSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\main\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    $testSources = @(
        Get-ChildItem -LiteralPath $RepoRoot -Directory -Filter 'minos-*' |
            ForEach-Object { Get-ChildItem -LiteralPath (Join-Path $_.FullName 'src\test\java') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue }
    )
    if ($mainSources.Count -ne 203) { throw "Unexpected S6 production source count: expected=203 actual=$($mainSources.Count)" }
    if ($testSources.Count -ne 97) { throw "Unexpected S6 test source count: expected=97 actual=$($testSources.Count)" }
}

function Get-LatestJar {
    param([Parameter(Mandatory = $true)][string] $Module, [Parameter(Mandatory = $true)][string] $Filter)
    $jar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot "$Module\target") -File -Filter $Filter -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $jar) { throw "$Module JAR is missing after verification." }
    $jar
}

function Assert-Artifacts {
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'JAVA_HOME unavailable for JAR inspection.' }
    $jarTool = Join-Path $env:JAVA_HOME 'bin\jar.exe'
    if (-not (Test-Path -LiteralPath $jarTool -PathType Leaf)) { throw "JDK jar.exe is missing: $jarTool" }

    $storageJar = Get-LatestJar 'minos-storage-local' 'minos-storage-local-*.jar'
    $shadedJar = Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'target') -File -Filter 'minos-code-intelligence-*-all.jar' -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($null -eq $shadedJar) { throw 'Final shaded MINOS JAR is missing.' }

    $storageEntries = @(& $jarTool tf $storageJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect minos-storage-local JAR.' }
    $shadedEntries = @(& $jarTool tf $shadedJar.FullName)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect shaded JAR.' }

    foreach ($entry in @(
        'com/minos/store/FileSymbolSnapshotStore.class',
        'com/minos/store/SnapshotDescriptor.class',
        'com/minos/store/SnapshotRepository.class',
        'com/minos/store/ActiveSnapshotRepository.class',
        'com/minos/store/SnapshotIntegrityService.class',
        'com/minos/store/SnapshotRetentionService.class',
        'com/minos/store/SnapshotCodec.class',
        'com/minos/store/SnapshotCodecV1.class',
        'com/minos/store/SnapshotCodecV2.class',
        'com/minos/store/SnapshotBinaryCodecSupport.class'
    )) {
        if ($storageEntries -notcontains $entry) { throw "minos-storage-local does not own $entry" }
        if ($shadedEntries -notcontains $entry) { throw "shaded JAR does not contain $entry" }
    }

    [pscustomobject]@{
        StorageJar = $storageJar.FullName
        ShadedJar = $shadedJar.FullName
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M15-S6 - decomposed persistence exact-head validation ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect Git worktree status.' }
    if ($dirty.Count -gt 0) { throw "M15-S6 runner requires a clean worktree.`n$($dirty -join "`n")" }

    $initialHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($initialHead)) { throw 'Unable to resolve initial HEAD.' }

    Write-Host '[1/7] Fetching M15-S6 branch...'
    Invoke-GitChecked @('fetch','origin',$Branch)
    $currentBranch = ((& git branch --show-current) | Select-Object -First 1).Trim()
    if ($currentBranch -ne $Branch) {
        & git show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) { Invoke-GitChecked @('switch',$Branch) }
        else { Invoke-GitChecked @('switch','-c',$Branch,'--track',"origin/$Branch") }
        Write-Host "[2/7] Switched to '$Branch'."
    } else {
        Write-Host "[2/7] Already on '$Branch'."
    }

    Write-Host '[3/7] Fast-forwarding to latest remote head...'
    Invoke-GitChecked @('pull','--ff-only','origin',$Branch)
    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve exact HEAD.' }
    if ($head -ne $initialHead) { Restart-UpdatedRunner $head; return }

    Write-Host '[4/7] Checking persistence decomposition and preserved S2 layout...'
    Assert-S6Shape

    . (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"

    Write-Host '[5/7] Building storage codecs/repositories with upstreams...'
    Invoke-NativeChecked '.\mvnw.cmd' @('-pl','minos-storage-local','-am','test') 'Focused M15-S6 Maven verification failed'

    Write-Host "[6/7] Replaying full S1/M14 qualification on exact HEAD $head..." -ForegroundColor Cyan
    $parameters = @{ ExpectedHead = $head }
    if ($SkipM14Replay) { $parameters['SkipM14Replay'] = $true }
    if ($SkipProviderReplays) { $parameters['SkipProviderReplays'] = $true }
    if ($ValidateDocker) { $parameters['ValidateDocker'] = $true }
    & (Join-Path $RepoRoot 'scripts\m15\capture-baseline.ps1') @parameters

    $baselinePath = Join-Path $RepoRoot 'target\m15-baseline\baseline.json'
    if (-not (Test-Path -LiteralPath $baselinePath -PathType Leaf)) { throw 'M15 baseline JSON is missing.' }
    $baseline = Get-Content -LiteralPath $baselinePath -Raw | ConvertFrom-Json
    if ($baseline.verifyStatus -ne 'PASS') { throw "M15-S6 verify status is $($baseline.verifyStatus)." }
    if (-not $SkipM14Replay -and $baseline.m14ReplayStatus -ne 'PASS') { throw "M15-S6 M14 replay status is $($baseline.m14ReplayStatus)." }
    if ([long] $baseline.junit.tests -ne 251 -or [long] $baseline.junit.failures -ne 0 -or [long] $baseline.junit.errors -ne 0) {
        throw "M15-S6 test summary mismatch: tests=$($baseline.junit.tests) failures=$($baseline.junit.failures) errors=$($baseline.junit.errors)"
    }
    if ([long] $baseline.mainSourceCount -ne 203 -or [long] $baseline.testSourceCount -ne 97) {
        throw "M15-S6 source count mismatch: main=$($baseline.mainSourceCount) tests=$($baseline.testSourceCount)"
    }

    Write-Host '[7/7] Verifying persistence ownership and repeated-query baseline...' -ForegroundColor Cyan
    $artifacts = Assert-Artifacts
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        & (Join-Path $RepoRoot 'scripts\m15\capture-query-baseline.ps1')
    }

    Write-Host ''
    if (-not $SkipM14Replay -and -not $SkipProviderReplays) {
        Write-Host 'M15-S6 DECOMPOSED PERSISTENCE VALIDATION SUCCESS' -ForegroundColor Green
    } else {
        Write-Host 'M15-S6 diagnostic validation finished (not sufficient to close S6)' -ForegroundColor Yellow
    }
    Write-Host "HEAD        : $head"
    Write-Host "Storage JAR : $($artifacts.StorageJar)"
    Write-Host "Shaded JAR  : $($artifacts.ShadedJar)"
}
finally {
    Pop-Location
}
