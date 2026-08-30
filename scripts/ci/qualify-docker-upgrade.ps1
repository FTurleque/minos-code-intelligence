[CmdletBinding()]
param(
    [string] $CandidateRef = 'HEAD',
    [string] $PreviousRef = '',
    [string] $EvidenceRoot = 'target\qualification\docker-upgrade'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# Portable: runs under pwsh (PowerShell 7+) on any host with git/docker/java/Maven available - a
# GitHub-hosted ubuntu-24.04 runner (the default CI path, see .github/workflows/release-promotion-gate.yml)
# or a Windows host with Docker Desktop Linux containers (for local/manual runs). Nothing below is
# Windows-specific: the real product lifecycle logic lives in docker/scripts/mcp-lifecycle.ps1,
# which this script calls directly with its own temporary install/data/projects roots.

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$EvidenceRoot = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $EvidenceRoot))
$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-docker-upgrade-' + [Guid]::NewGuid().ToString('N'))
$PreviousWorktree = Join-Path $TempRoot 'previous'
$InstallRoot = Join-Path $TempRoot 'install'
$DataRoot = Join-Path $TempRoot 'data'
$ProjectsRoot = Join-Path $TempRoot 'projects'
$FixtureRoot = Join-Path $ProjectsRoot 'upgrade-fixture'
$Suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$ContainerName = "minos-upgrade-$Suffix"
$ComposeProject = "minos-upgrade-$Suffix"
$ImageA = ''
$ImageB = ''
$WorktreeAdded = $false

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Failure (exit=$LASTEXITCODE)" }
}

function Resolve-Commit([string] $Ref) {
    $Value = ((& git -C $RepoRoot rev-parse "$Ref^{commit}") | Select-Object -First 1).Trim()
    if ($LASTEXITCODE -ne 0 -or $Value -notmatch '^[0-9a-f]{40}$') {
        throw "Cannot resolve Git commit: $Ref"
    }
    return $Value
}

function Build-ShadedJar([string] $Root) {
    Push-Location $Root
    try {
        # This function's result is captured by the caller ($JarA = Build-ShadedJar ...): any
        # unredirected output a nested native command writes would otherwise be captured too and
        # corrupt the returned path into an array of build-log lines. `| Out-Host` keeps Maven's
        # build output visible live without letting it leak into the function's return value.
        if ($env:OS -eq 'Windows_NT') {
            & '.\mvnw.cmd' -B -ntp -DskipTests -DskipITs clean package | Out-Host
        }
        else {
            & chmod +x './mvnw'
            & './mvnw' -B -ntp -DskipTests -DskipITs clean package | Out-Host
        }
        if ($LASTEXITCODE -ne 0) { throw "Maven packaging failed under $Root" }
    }
    finally { Pop-Location }

    $Jars = @(Get-ChildItem -LiteralPath (Join-Path $Root 'target') -Filter 'minos-code-intelligence-*-all.jar' -File)
    if ($Jars.Count -ne 1) { throw "Expected one shaded MINOS JAR under $Root, found $($Jars.Count)." }
    return $Jars[0].FullName
}

function Invoke-AdminCommandCapture([string[]] $MinosArgumentsToCapture) {
    # A dedicated, non-throwing capture path for admin commands we expect might fail, distinct from
    # Invoke-DockerWorkflow's Compose call (which throws on nonzero exit and, empirically, never
    # lets output already piped into `Set-Content` reach the file when that throw cuts the pipeline
    # short - fine for calls that must succeed, useless for a call whose failure we need to inspect).
    # Mirrors mcp-lifecycle.ps1's own Invoke-DockerAllowFailure: capture stdout+stderr from a single
    # native invocation expression so the output survives regardless of exit code.
    $RuntimeRootLocal = Join-Path $InstallRoot 'runtime'
    $ComposeFileLocal = Join-Path $RuntimeRootLocal 'compose.mcp.prod.yaml'
    $EnvironmentFileLocal = Join-Path $RuntimeRootLocal '.env'
    $ComposeArguments = @(
        'compose', '--project-directory', $RuntimeRootLocal, '--env-file', $EnvironmentFileLocal,
        '-f', $ComposeFileLocal, 'run', '--rm', '--no-deps', 'minos-admin') + $MinosArgumentsToCapture
    $Output = (($null | & docker @ComposeArguments 2>&1) | Out-String).Trim()
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $Output }
}

function Invoke-AdminShellCommand([string] $ShellCommand) {
    # minos-data-bootstrap chown's the MINOS data root to uid 10001 and chmod's it 0700 (see
    # compose.mcp.prod.yaml) so nothing outside the container can read or write it - real,
    # intentional hardening that a real Linux bind mount enforces on the HOST directory too (a
    # Docker Desktop/WSL2 host, where this script was first validated, translates ownership
    # differently and does not enforce this the same way - a real environment difference, not a
    # flake). Persistence must therefore be proven by writing/reading through the admin plane,
    # which mounts the data root writable, not by reaching into $DataRoot directly from the host.
    $RuntimeRootLocal = Join-Path $InstallRoot 'runtime'
    $ComposeFileLocal = Join-Path $RuntimeRootLocal 'compose.mcp.prod.yaml'
    $EnvironmentFileLocal = Join-Path $RuntimeRootLocal '.env'
    $ComposeArguments = @(
        'compose', '--project-directory', $RuntimeRootLocal, '--env-file', $EnvironmentFileLocal,
        '-f', $ComposeFileLocal, 'run', '--rm', '--no-deps', '--entrypoint', 'sh', 'minos-admin', '-c', $ShellCommand)
    $Output = (($null | & docker @ComposeArguments 2>&1) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "Admin shell command failed: $ShellCommand -- $Output" }
    return $Output
}

function Invoke-DockerWorkflow {
    param(
        [Parameter(Mandatory = $true)][string] $Action,
        [string] $SourceRoot = '',
        [string] $Jar = '',
        [string] $Version = '',
        [string] $Commit = '',
        [string] $ImageTag = '',
        [string[]] $MinosArguments = @()
    )

    $Arguments = @{
        Action = $Action
        InstallRoot = $InstallRoot
        DataRoot = $DataRoot
        ProjectsRoot = $ProjectsRoot
        ContainerName = $ContainerName
        ComposeProject = $ComposeProject
    }
    if (-not [string]::IsNullOrWhiteSpace($SourceRoot)) { $Arguments.SourceRoot = $SourceRoot }
    if (-not [string]::IsNullOrWhiteSpace($Jar)) { $Arguments.Jar = $Jar }
    if (-not [string]::IsNullOrWhiteSpace($Version)) { $Arguments.Version = $Version }
    if (-not [string]::IsNullOrWhiteSpace($Commit)) { $Arguments.Commit = $Commit }
    if (-not [string]::IsNullOrWhiteSpace($ImageTag)) { $Arguments.ImageTag = $ImageTag }
    if ($MinosArguments.Count -gt 0) { $Arguments.MinosArguments = $MinosArguments }
    # Always run this repo's OWN, current, portable mcp-lifecycle.ps1 as the driver - never a
    # candidate worktree's copy. An older candidate A predating this file's introduction has no
    # copy of it at all, and even when it does, the orchestration logic is qualification tooling,
    # not the product surface under test; -SourceRoot (set per candidate below) is what makes each
    # install use that candidate's own Dockerfile/Compose recipe, matching a real upgrade.
    $LifecycleScript = Join-Path $RepoRoot 'docker\scripts\mcp-lifecycle.ps1'
    & $LifecycleScript @Arguments
}

function Invoke-McpSmoke([string] $SourceRoot, [string] $Label) {
    $Smoke = Join-Path $SourceRoot 'docker\scripts\MinosDockerMcpSmoke.java'
    $Compose = Join-Path $InstallRoot 'runtime\compose.mcp.prod.yaml'
    $Environment = Join-Path $InstallRoot 'runtime\.env'
    $Output = Join-Path $EvidenceRoot "mcp-$Label.txt"
    $ErrorOutput = Join-Path $EvidenceRoot "mcp-$Label.stderr.log"
    & java $Smoke $Compose $Environment $ContainerName 1> $Output 2> $ErrorOutput
    if ($LASTEXITCODE -ne 0) { throw "Docker MCP smoke failed for $Label." }
    if (-not (Select-String -LiteralPath $Output -SimpleMatch 'MINOS Docker MCP smoke SUCCESS' -Quiet)) {
        throw "Docker MCP smoke did not report success for $Label."
    }
}

function Read-InstallationMetadata {
    $MetadataPath = Join-Path $InstallRoot 'runtime\installation.json'
    if (-not (Test-Path -LiteralPath $MetadataPath -PathType Leaf)) { throw 'Docker installation metadata is missing.' }
    return Get-Content -Raw -LiteralPath $MetadataPath | ConvertFrom-Json
}

function Assert-RunningCandidate([string] $ExpectedImage, [string] $ExpectedCommit) {
    $InspectText = ((& docker inspect $ContainerName) | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($InspectText)) {
        throw 'The upgraded MINOS MCP container cannot be inspected.'
    }
    $Inspect = @($InspectText | ConvertFrom-Json)
    if ($Inspect.Count -ne 1 -or -not $Inspect[0].State.Running) { throw 'The upgraded MINOS MCP container is not running.' }
    if ([string]$Inspect[0].Config.Image -ne $ExpectedImage) {
        throw "Unexpected running image: $($Inspect[0].Config.Image) != $ExpectedImage"
    }
    $Revision = [string]$Inspect[0].Config.Labels.'org.opencontainers.image.revision'
    if ($Revision -ne $ExpectedCommit) { throw "Running image revision mismatch: $Revision != $ExpectedCommit" }
}

New-Item -ItemType Directory -Force -Path $TempRoot, $ProjectsRoot | Out-Null

try {
    Invoke-NativeChecked -File 'docker' -Arguments @('version', '--format', '{{.Server.Version}}') `
        -Failure 'Docker Linux engine is unavailable'
    Invoke-NativeChecked -File 'docker' -Arguments @('compose', 'version') `
        -Failure 'Docker Compose is unavailable'

    $CandidateSha = Resolve-Commit $CandidateRef
    if ([string]::IsNullOrWhiteSpace($PreviousRef)) {
        $MergeBase = ((& git -C $RepoRoot merge-base $CandidateSha origin/develop) | Select-Object -First 1).Trim()
        if ($LASTEXITCODE -eq 0 -and $MergeBase -match '^[0-9a-f]{40}$' -and $MergeBase -ne $CandidateSha) {
            $PreviousRef = $MergeBase
        }
        else {
            $PreviousRef = "$CandidateSha^1"
        }
    }
    $PreviousSha = Resolve-Commit $PreviousRef
    if ($PreviousSha -eq $CandidateSha) { throw 'Previous and candidate commits must differ.' }

    Invoke-NativeChecked -File 'git' -Arguments @('-C', $RepoRoot, 'worktree', 'add', '--detach', $PreviousWorktree, $PreviousSha) `
        -Failure 'Unable to create previous-candidate worktree'
    $WorktreeAdded = $true

    $JarA = Build-ShadedJar $PreviousWorktree
    # $EvidenceRoot lives under $RepoRoot/target/... and `mvn clean` deletes $RepoRoot/target/
    # wholesale (not just Maven's own outputs) before rebuilding it - creating the evidence
    # directory before this second build would silently wipe it out from under the rest of the
    # script. Create it only once both builds (and their `clean` phases) are behind us.
    $JarB = Build-ShadedJar $RepoRoot
    Remove-Item -LiteralPath $EvidenceRoot -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $EvidenceRoot | Out-Null
    # Each candidate installs itself with its OWN Dockerfile/Compose recipe (real upgrade fidelity),
    # but both are driven by this repo's current mcp-lifecycle.ps1 - see Invoke-DockerWorkflow.
    foreach ($Required in @(
        (Join-Path $PreviousWorktree 'docker\Dockerfile.mcp.release'),
        (Join-Path $PreviousWorktree 'docker\compose.mcp.prod.yaml'),
        (Join-Path $RepoRoot 'docker\Dockerfile.mcp.release'),
        (Join-Path $RepoRoot 'docker\compose.mcp.prod.yaml')
    )) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Missing real Docker release recipe: $Required" }
    }

    $VersionA = "qualification-a-$($PreviousSha.Substring(0, 12))"
    $VersionB = "qualification-b-$($CandidateSha.Substring(0, 12))"
    $TagA = "upgrade-a-$($PreviousSha.Substring(0, 12))-$Suffix"
    $TagB = "upgrade-b-$($CandidateSha.Substring(0, 12))-$Suffix"
    $ImageA = "minos-code-intelligence:$TagA"
    $ImageB = "minos-code-intelligence:$TagB"

    New-Item -ItemType Directory -Force -Path (Join-Path $FixtureRoot 'src\main\java') | Out-Null
    @'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>qualification</groupId>
  <artifactId>upgrade-fixture</artifactId>
  <version>1.0.0</version>
  <properties><maven.compiler.release>24</maven.compiler.release></properties>
</project>
'@ | Set-Content -LiteralPath (Join-Path $FixtureRoot 'pom.xml') -Encoding utf8
    @'
package qualification;
public final class UpgradeFixture {
    public static String marker() { return "minos-docker-upgrade"; }
}
'@ | Set-Content -LiteralPath (Join-Path $FixtureRoot 'src\main\java\UpgradeFixture.java') -Encoding utf8

    # Candidate A: real provider-complete image, real Compose, real admin plane and real MCP handshake.
    Invoke-DockerWorkflow -SourceRoot $PreviousWorktree -Action Install -Jar $JarA -Version $VersionA -Commit $PreviousSha -ImageTag $TagA
    Invoke-DockerWorkflow -Action Start
    Assert-RunningCandidate -ExpectedImage $ImageA -ExpectedCommit $PreviousSha
    Invoke-McpSmoke -SourceRoot $PreviousWorktree -Label 'a'

    Invoke-DockerWorkflow -Action Admin -MinosArguments @(
        'project', 'add', '/workspace/projects/upgrade-fixture', '--name', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'project-add-a.log') -Encoding utf8

    # docs/developer/remote-worker-sandbox-disposition.md: the Docker admin plane cannot nest a
    # second OS sandbox inside its own already-hardened container, so a managed provider (scip-java,
    # scip-typescript, ...) is reported UNSUPPORTED_BY_BACKEND - never READY - by design, and
    # `index` (which must launch the provider) always refuses. This is documented, expected,
    # capability-honest behavior, not a bug this qualification should paper over: assert the refusal
    # happens, and that it happens for exactly this documented reason, rather than assuming
    # execution succeeds or silently swallowing whatever `index` does.
    $IndexAttempt = Invoke-AdminCommandCapture @('index', 'upgrade-fixture', '--format', 'json')
    Set-Content -LiteralPath (Join-Path $EvidenceRoot 'index-attempt-a.log') -Value $IndexAttempt.Output -Encoding utf8
    if ($IndexAttempt.ExitCode -eq 0) {
        throw 'Expected the Docker admin plane to capability-honestly refuse managed-provider indexing (see docs/developer/remote-worker-sandbox-disposition.md), but the index command succeeded.'
    }
    if ($IndexAttempt.Output -notmatch 'UNSUPPORTED_BY_BACKEND|provider runtime is not ready') {
        throw "Managed-provider indexing failed inside the Docker admin plane for an unexpected reason (expected the documented capability-honest UNSUPPORTED_BY_BACKEND refusal): $($IndexAttempt.Output)"
    }

    Invoke-DockerWorkflow -Action Admin -MinosArguments @(
        'index-status', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'index-status-a.log') -Encoding utf8

    $Sentinel = [Guid]::NewGuid().ToString('N')
    Invoke-AdminShellCommand "printf '%s' '$Sentinel' > /var/lib/minos/upgrade-qualification.sentinel" | Out-Null
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\installation.json') -Destination (Join-Path $EvidenceRoot 'installation-a.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-inventory.json') -Destination (Join-Path $EvidenceRoot 'provider-inventory-a.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-binary-sha256.txt') -Destination (Join-Path $EvidenceRoot 'provider-binary-sha256-a.txt')

    # Stop only the persistent query plane. The next install must exercise the real Docker/Compose
    # upgrade path, including non-interactive provider-volume reconciliation fixed by #246.
    Invoke-DockerWorkflow -Action Stop

    # Candidate A's image is no longer needed once B is installed. Removing it now (rather than
    # only in the final cleanup) keeps peak disk usage on a standard GitHub-hosted runner close to
    # a single provider-complete image build instead of two simultaneous ones.
    & docker image rm --force $ImageA *> $null

    # Candidate B: different Git commit and JAR, same durable MINOS data root.
    Invoke-DockerWorkflow -SourceRoot $RepoRoot -Action Install -Jar $JarB -Version $VersionB -Commit $CandidateSha -ImageTag $TagB
    Invoke-DockerWorkflow -Action Start
    Invoke-DockerWorkflow -Action Validate
    Assert-RunningCandidate -ExpectedImage $ImageB -ExpectedCommit $CandidateSha
    Invoke-McpSmoke -SourceRoot $RepoRoot -Label 'b'

    $MetadataB = Read-InstallationMetadata
    if ([string]$MetadataB.version -ne $VersionB -or [string]$MetadataB.gitCommit -ne $CandidateSha) {
        throw 'Candidate B installation metadata does not identify the upgraded candidate.'
    }
    $SentinelAfter = (Invoke-AdminShellCommand 'cat /var/lib/minos/upgrade-qualification.sentinel').Trim()
    if ($SentinelAfter -ne $Sentinel) { throw 'Persistent MINOS data sentinel was not preserved across Docker upgrade.' }

    Invoke-DockerWorkflow -Action Admin -MinosArguments @('project', 'list', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'project-list-b.log') -Encoding utf8
    if (-not (Select-String -LiteralPath (Join-Path $EvidenceRoot 'project-list-b.log') -SimpleMatch 'upgrade-fixture' -Quiet)) {
        throw 'Registered project did not survive Docker A -> B upgrade.'
    }
    Invoke-DockerWorkflow -Action Admin -MinosArguments @(
        'index-status', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'index-status-b.log') -Encoding utf8

    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\installation.json') -Destination (Join-Path $EvidenceRoot 'installation-b.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-inventory.json') -Destination (Join-Path $EvidenceRoot 'provider-inventory-b.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-binary-sha256.txt') -Destination (Join-Path $EvidenceRoot 'provider-binary-sha256-b.txt')

    # A deliberately unusable next JAR must fail before replacing the qualified B runtime. This is
    # the real-path complement to the deterministic switch-mcp-backend rollback contract tests.
    $BrokenJar = Join-Path $TempRoot 'broken-candidate.jar'
    Set-Content -LiteralPath $BrokenJar -Value 'not-a-jar' -Encoding ascii
    $FailedAsExpected = $false
    try {
        Invoke-DockerWorkflow -SourceRoot $RepoRoot -Action Install -Jar $BrokenJar `
            -Version 'qualification-c-broken' -Commit 'ffffffffffffffffffffffffffffffffffffffff' -ImageTag "upgrade-c-broken-$Suffix"
    }
    catch {
        $FailedAsExpected = $true
        $_ | Out-String | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'failed-candidate.log') -Encoding utf8
    }
    if (-not $FailedAsExpected) { throw 'Deliberately broken next Docker candidate unexpectedly installed.' }

    $MetadataAfterFailure = Read-InstallationMetadata
    if ([string]$MetadataAfterFailure.version -ne $VersionB -or [string]$MetadataAfterFailure.gitCommit -ne $CandidateSha) {
        throw 'Failed next candidate replaced qualified B installation metadata.'
    }
    Assert-RunningCandidate -ExpectedImage $ImageB -ExpectedCommit $CandidateSha
    Invoke-McpSmoke -SourceRoot $RepoRoot -Label 'b-after-failed-candidate'

    [ordered]@{
        formatVersion = 1
        previous = $PreviousSha
        candidate = $CandidateSha
        imageA = $ImageA
        imageB = $ImageB
        persistentDataPreserved = $true
        registeredProjectPreserved = $true
        failedNextCandidatePreservedB = $true
        result = 'PASS'
    } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'qualification.json') -Encoding utf8

    Write-Host "MINOS real Docker A -> B upgrade qualification SUCCESS: $PreviousSha -> $CandidateSha" -ForegroundColor Green
}
finally {
    try {
        if (Test-Path -LiteralPath (Join-Path $InstallRoot 'runtime\installation.json')) {
            try { Invoke-DockerWorkflow -Action Uninstall } catch { Write-Warning $_ }
        }
    } catch { Write-Warning $_ }
    foreach ($Image in @($ImageA, $ImageB)) {
        if (-not [string]::IsNullOrWhiteSpace($Image)) { & docker image rm --force $Image *> $null }
    }
    if ($WorktreeAdded) { & git -C $RepoRoot worktree remove --force $PreviousWorktree *> $null }
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
