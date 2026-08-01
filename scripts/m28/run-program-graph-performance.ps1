[CmdletBinding()]
param(
    [ValidateRange(4, 2000)][int] $FileCount = 1000,
    [string] $OutputJson = ""
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$maven = Join-Path $RepoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
    throw "Maven wrapper not found: $maven"
}

if ([string]::IsNullOrWhiteSpace($OutputJson)) {
    $OutputJson = Join-Path $RepoRoot 'target\qualification\m28\program-graph-windows.json'
}
$output = [System.IO.Path]::GetFullPath($OutputJson)
New-Item -ItemType Directory -Force -Path ([System.IO.Path]::GetDirectoryName($output)) | Out-Null
Remove-Item -LiteralPath $output -Force -ErrorAction SilentlyContinue

Push-Location $RepoRoot
try {
    & $maven `
        '-pl' 'minos-application' `
        '-am' `
        '-Dtest=ProgramGraphPerformanceQualificationTest' `
        '-Dsurefire.failIfNoSpecifiedTests=false' `
        "-Dminos.m28.programGraph.files=$FileCount" `
        "-Dminos.m28.programGraph.result=$output" `
        'test'
    if ($LASTEXITCODE -ne 0) {
        throw "M28 ProgramGraph performance test failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
    throw "M28 ProgramGraph performance result was not produced: $output"
}
$result = Get-Content -LiteralPath $output -Raw | ConvertFrom-Json
if ($result.profile -ne 'M28_PROGRAM_GRAPH_JAVA') {
    throw "Unexpected profile: $($result.profile)"
}
if ([int] $result.file_count -ne $FileCount) {
    throw "Unexpected file count: $($result.file_count)"
}
if ([long] $result.cache_hits -ne 1 -or [long] $result.cache_misses -ne 1) {
    throw "Expected one cache hit and one miss, got hits=$($result.cache_hits) misses=$($result.cache_misses)"
}
if ($result.warm_identity_hit -ne $true) {
    throw 'Warm ProgramGraph path was not an identity cache hit.'
}
if ($result.modified_source_disposition -ne 'JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT') {
    throw "Unexpected modified-source disposition: $($result.modified_source_disposition)"
}
if ($result.decision -ne 'KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE') {
    throw "Unexpected backend decision: $($result.decision)"
}

$head = (& git -C $RepoRoot rev-parse HEAD).Trim()
Write-Host 'M28 PROGRAM GRAPH PERFORMANCE QUALIFICATION SUCCESS' -ForegroundColor Green
Write-Host "Validated HEAD: $head"
Write-Host "Files=$($result.file_count) bytes=$($result.source_bytes) cold=$($result.cold_nanos)ns warm=$($result.warm_nanos)ns modified=$($result.modified_source_nanos)ns"
Write-Host "Result: $output"
