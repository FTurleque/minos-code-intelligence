<#
.SYNOPSIS
Starts the PostgreSQL/pgvector server the integration tests of minos-storage connect to (AUD-DEP-14, R-27).

.DESCRIPTION
Same contract as start-test-postgres.sh. The image is the pinned default of MINOS_POSTGRES_IMAGE in
docker/compose-mcp.connected.yaml, its single source. The container listens on 127.0.0.1 only, its password is
generated at every start and reaches docker through its environment, never through its arguments.
Compatible with Windows PowerShell 5.1 and pwsh.

Exit codes: 0 success; 1 image missing or unpinned, Docker missing, container not healthy in time.

.PARAMETER Port
Local port (default 55432).

.PARAMETER PrintImage
Print the pinned image and exit.

.PARAMETER Stop
Remove the container.
#>
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)]
    [int] $Port = 55432,
    [switch] $PrintImage,
    [switch] $Stop
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Container = 'minos-test-postgres'
$UserName = 'minos'
$AdminDatabase = 'postgres'
$HealthTimeoutSeconds = 60
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$Compose = Join-Path (Join-Path $Root 'docker') 'compose-mcp.connected.yaml'

function Get-PinnedImage {
    if (-not (Test-Path -LiteralPath $Compose -PathType Leaf)) {
        throw 'docker/compose-mcp.connected.yaml is missing: the default of MINOS_POSTGRES_IMAGE cannot be read'
    }
    $text = [System.IO.File]::ReadAllText($Compose, [System.Text.Encoding]::UTF8)
    $defaults = @([regex]::Matches($text, 'MINOS_POSTGRES_IMAGE:-([^}"\s]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
    if ($defaults.Count -ne 1) {
        throw "docker/compose-mcp.connected.yaml must give MINOS_POSTGRES_IMAGE exactly one default, found $($defaults.Count)"
    }
    $image = $defaults[0]
    if ($image -cnotmatch '^[a-z0-9][a-z0-9._/:-]*:[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}@sha256:[0-9a-f]{64}$') {
        throw "the default of MINOS_POSTGRES_IMAGE is not <image>:<tag>@sha256:<64 hex>: $image"
    }
    return $image
}

function New-TestPassword {
    # RandomNumberGenerator.Create() + GetBytes: Fill does not exist in Windows PowerShell 5.1.
    $bytes = New-Object byte[] 24
    $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Remove-TestContainer {
    # docker writes "No such container" on stderr when there is nothing to remove: not an error here.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker rm -f $Container 2>$null | Out-Null } finally { $ErrorActionPreference = $previous }
}

try {
    if ($PrintImage) {
        Write-Output (Get-PinnedImage)
        exit 0
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'docker is not on PATH: the PostgreSQL test server cannot be started'
    }
    if ($Stop) {
        & docker rm -f $Container | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "docker rm -f $Container failed (exit code $LASTEXITCODE)" }
        [Console]::Error.WriteLine("container $Container removed")
        exit 0
    }

    $image = Get-PinnedImage
    $password = New-TestPassword
    $url = "jdbc:postgresql://127.0.0.1:$Port/$AdminDatabase"
    Remove-TestContainer
    [Console]::Error.WriteLine("starting $Container from $image on 127.0.0.1:$Port")
    # --env POSTGRES_PASSWORD without a value: docker copies it from its own environment, so it is not an argument.
    $env:POSTGRES_PASSWORD = $password
    try {
        & docker run --detach --name $Container --pull=missing `
            --publish "127.0.0.1:${Port}:5432" `
            --env POSTGRES_PASSWORD --env "POSTGRES_USER=$UserName" --env "POSTGRES_DB=$AdminDatabase" `
            --health-cmd "pg_isready -h 127.0.0.1 -p 5432 -U $UserName -d $AdminDatabase" `
            --health-interval 2s --health-timeout 5s --health-retries 30 `
            $image | Out-Null
        $runExit = $LASTEXITCODE
    } finally {
        Remove-Item Env:POSTGRES_PASSWORD -ErrorAction SilentlyContinue
    }
    if ($runExit -ne 0) { throw "docker run failed (exit code $runExit)" }

    # The TCP check of the healthcheck only succeeds on the final server: the initialization server of the image
    # listens on its Unix socket only.
    $deadline = (Get-Date).AddSeconds($HealthTimeoutSeconds)
    while ($true) {
        $status = (& docker inspect --format '{{.State.Health.Status}}' $Container | Out-String).Trim()
        if ($LASTEXITCODE -ne 0) { $status = '' }
        if ($status -eq 'healthy') { break }
        if ($status -eq 'unhealthy' -or (Get-Date) -ge $deadline) {
            Remove-TestContainer
            if (-not $status) { $status = 'unknown' }
            throw "$Container is not healthy (status: $status) within $HealthTimeoutSeconds s: removed"
        }
        Start-Sleep -Seconds 1
    }
    [Console]::Error.WriteLine("$Container is healthy")

    # The password goes through the environment of mvnw, never through its arguments.
    Write-Output "-Dminos.postgresql.test.url=$url"
    Write-Output "-Dminos.postgresql.test.user=$UserName"
    Write-Output "`$env:MINOS_TEST_POSTGRES_PASSWORD = '$password'"
    [Console]::Error.WriteLine("then: .\mvnw.cmd -B -ntp -pl minos-storage -am verify `"-Dminos.postgresql.tests.required=true`" `"-Dminos.postgresql.test.url=$url`" `"-Dminos.postgresql.test.user=$UserName`"")
    [Console]::Error.WriteLine('stop: scripts\dev\start-test-postgres.ps1 -Stop')
    exit 0
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    exit 1
}
