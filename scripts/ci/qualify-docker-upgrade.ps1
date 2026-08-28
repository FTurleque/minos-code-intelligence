[CmdletBinding()]
param(
    [string] $CandidateRef = 'HEAD',
    [string] $PreviousRef = '',
    [string] $EvidenceRoot = 'target\qualification\docker-upgrade'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'The real Docker A -> B upgrade qualification requires a Windows host with Docker Desktop Linux containers.'
}

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
        & '.\mvnw.cmd' -B -ntp -DskipTests -DskipITs clean package
        if ($LASTEXITCODE -ne 0) { throw "Maven packaging failed under $Root" }
    }
    finally { Pop-Location }

    $Jars = @(Get-ChildItem -LiteralPath (Join-Path $Root 'target') -Filter 'minos-code-intelligence-*-all.jar' -File)
    if ($Jars.Count -ne 1) { throw "Expected one shaded MINOS JAR under $Root, found $($Jars.Count)." }
    return $Jars[0].FullName
}

function Invoke-DockerWorkflow {
    param(
        [Parameter(Mandatory = $true)][string] $Script,
        [Parameter(Mandatory = $true)][string] $Action,
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
    if (-not [string]::IsNullOrWhiteSpace($Jar)) { $Arguments.Jar = $Jar }
    if (-not [string]::IsNullOrWhiteSpace($Version)) { $Arguments.Version = $Version }
    if (-not [string]::IsNullOrWhiteSpace($Commit)) { $Arguments.Commit = $Commit }
    if (-not [string]::IsNullOrWhiteSpace($ImageTag)) { $Arguments.ImageTag = $ImageTag }
    if ($MinosArguments.Count -gt 0) { $Arguments.MinosArguments = $MinosArguments }
    & $Script @Arguments
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

Remove-Item -LiteralPath $EvidenceRoot -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $EvidenceRoot, $TempRoot, $ProjectsRoot | Out-Null

try {
    Invoke-NativeChecked -File 'docker' -Arguments @('version', '--format', '{{.Server.Version}}') `
        -Failure 'Docker Desktop Linux engine is unavailable'

    $CandidateSha = Resolve-Commit $CandidateRef
    if ([string]::IsNullOrWhiteSpace($PreviousRef)) {
        $PreviousRef = "$CandidateSha^1"
    }
    $PreviousSha = Resolve-Commit $PreviousRef
    if ($PreviousSha -eq $CandidateSha) { throw 'Previous and candidate commits must differ.' }

    Invoke-NativeChecked -File 'git' -Arguments @('-C', $RepoRoot, 'worktree', 'add', '--detach', $PreviousWorktree, $PreviousSha) `
        -Failure 'Unable to create previous-candidate worktree'
    $WorktreeAdded = $true

    $JarA = Build-ShadedJar $PreviousWorktree
    $JarB = Build-ShadedJar $RepoRoot
    $WorkflowA = Join-Path $PreviousWorktree 'docker\scripts\prod-mcp-release.ps1'
    $WorkflowB = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
    foreach ($Required in @($WorkflowA, $WorkflowB)) {
        if (-not (Test-Path -LiteralPath $Required -PathType Leaf)) { throw "Missing real Docker workflow: $Required" }
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
    Invoke-DockerWorkflow -Script $WorkflowA -Action Install -Jar $JarA -Version $VersionA -Commit $PreviousSha -ImageTag $TagA
    Invoke-DockerWorkflow -Script $WorkflowA -Action Start
    Assert-RunningCandidate -ExpectedImage $ImageA -ExpectedCommit $PreviousSha
    Invoke-McpSmoke -SourceRoot $PreviousWorktree -Label 'a'

    Invoke-DockerWorkflow -Script $WorkflowA -Action Admin -MinosArguments @(
        'project', 'add', '/workspace/projects/upgrade-fixture', '--name', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'project-add-a.log') -Encoding utf8
    Invoke-DockerWorkflow -Script $WorkflowA -Action Admin -MinosArguments @(
        'index', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'index-a.log') -Encoding utf8
    Invoke-DockerWorkflow -Script $WorkflowA -Action Admin -MinosArguments @(
        'index-status', 'upgrade-fixture', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'index-status-a.log') -Encoding utf8

    $Sentinel = [Guid]::NewGuid().ToString('N')
    Set-Content -LiteralPath (Join-Path $DataRoot 'upgrade-qualification.sentinel') -Value $Sentinel -Encoding ascii
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\installation.json') -Destination (Join-Path $EvidenceRoot 'installation-a.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-inventory.json') -Destination (Join-Path $EvidenceRoot 'provider-inventory-a.json')
    Copy-Item -LiteralPath (Join-Path $InstallRoot 'runtime\provider-binary-sha256.txt') -Destination (Join-Path $EvidenceRoot 'provider-binary-sha256-a.txt')

    # Stop only the persistent query plane. The next install must exercise the real Docker/Compose
    # upgrade path, including non-interactive provider-volume reconciliation fixed by #246.
    Invoke-DockerWorkflow -Script $WorkflowA -Action Stop

    # Candidate B: different Git commit and JAR, same durable MINOS data root.
    Invoke-DockerWorkflow -Script $WorkflowB -Action Install -Jar $JarB -Version $VersionB -Commit $CandidateSha -ImageTag $TagB
    Invoke-DockerWorkflow -Script $WorkflowB -Action Start
    Invoke-DockerWorkflow -Script $WorkflowB -Action Validate
    Assert-RunningCandidate -ExpectedImage $ImageB -ExpectedCommit $CandidateSha
    Invoke-McpSmoke -SourceRoot $RepoRoot -Label 'b'

    $MetadataB = Read-InstallationMetadata
    if ([string]$MetadataB.version -ne $VersionB -or [string]$MetadataB.gitCommit -ne $CandidateSha) {
        throw 'Candidate B installation metadata does not identify the upgraded candidate.'
    }
    $SentinelAfter = (Get-Content -Raw -LiteralPath (Join-Path $DataRoot 'upgrade-qualification.sentinel')).Trim()
    if ($SentinelAfter -ne $Sentinel) { throw 'Persistent MINOS data sentinel was not preserved across Docker upgrade.' }

    Invoke-DockerWorkflow -Script $WorkflowB -Action Admin -MinosArguments @('project', 'list', '--format', 'json') `
        6>&1 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'project-list-b.log') -Encoding utf8
    if (-not (Select-String -LiteralPath (Join-Path $EvidenceRoot 'project-list-b.log') -SimpleMatch 'upgrade-fixture' -Quiet)) {
        throw 'Registered project did not survive Docker A -> B upgrade.'
    }
    Invoke-DockerWorkflow -Script $WorkflowB -Action Admin -MinosArguments @(
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
        Invoke-DockerWorkflow -Script $WorkflowB -Action Install -Jar $BrokenJar `
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
            $CurrentWorkflow = Join-Path $RepoRoot 'docker\scripts\prod-mcp-release.ps1'
            try { Invoke-DockerWorkflow -Script $CurrentWorkflow -Action Uninstall } catch { Write-Warning $_ }
        }
    } catch { Write-Warning $_ }
    foreach ($Image in @($ImageA, $ImageB)) {
        if (-not [string]::IsNullOrWhiteSpace($Image)) { & docker image rm --force $Image *> $null }
    }
    if ($WorktreeAdded) { & git -C $RepoRoot worktree remove --force $PreviousWorktree *> $null }
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
