[CmdletBinding()]
param(
    # The FULL distribution directory (target\dist\minos-<version>-windows-x64), or the directory that holds tools\.
    [Parameter(Mandatory = $true)]
    [string] $Distribution,

    # Any local image that has a Java 24 `java` on its PATH.
    [string] $Image = 'minos-code-intelligence:1.2.0-730b760020b8'
)

# Seeding and verification of the embedded tools in a container with NO network (docker --network none).
# The tools are the Windows payload, so nothing is executed: the point is that MINOS copies, verifies (SHA-256 of the
# catalogue) and extracts them with the network unavailable, refuses an altered payload, and never falls back to a download.
# Local release evidence, never run by CI. Ends with `SEEDING CONTAINER QUALIFICATION PASS` or `FAIL`.

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Distribution = (Resolve-Path -LiteralPath $Distribution).Path
$Jar = Join-Path $Distribution 'lib\minos.jar'
$Tools = Join-Path $Distribution 'tools'
if (-not (Test-Path -LiteralPath $Jar -PathType Leaf)) { throw "lib\minos.jar not found under $Distribution" }
if (-not (Test-Path -LiteralPath (Join-Path $Tools 'TOOLS-MANIFEST.json') -PathType Leaf)) { throw "tools\TOOLS-MANIFEST.json not found under $Distribution" }

$Work = Join-Path ([System.IO.Path]::GetTempPath()) ('minos-seeding-container-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$Altered = Join-Path $Work 'altered-tools'
New-Item -ItemType Directory -Force -Path $Work | Out-Null
Copy-Item -LiteralPath $Tools -Destination $Altered -Recurse
$Victim = Join-Path (Join-Path $Altered 'artifacts') 'nodejs-24.20.0-windows-x64.zip'
$Bytes = [System.IO.File]::ReadAllBytes($Victim)
$Bytes[1000] = $Bytes[1000] -bxor 1
[System.IO.File]::WriteAllBytes($Victim, $Bytes)

function Invoke-InContainer([string] $ToolsDirectory, [string] $Name) {
    $Script = @'
set -e
export MINOS_HOME=/tmp/home MINOS_EMBEDDED_TOOLS_DIR=/payload MINOS_TOOLS_OFFLINE=1
export JAVA_TOOL_OPTIONS="-Dminos.tools.platform=windows-x64"
java -jar /minos.jar tools verify --all --format json > /tmp/verify.json 2>/tmp/verify.err || true
echo "--- seeded under MINOS_HOME/tools"; find /tmp/home/tools -maxdepth 2 -mindepth 1 | sort | head -20
echo "--- origins"; cat /tmp/home/tools/.tool-origins.json 2>/dev/null || echo "(none)"
echo "--- providers"; python3 -c "
import json
for p in json.load(open('/tmp/verify.json'))['providers']:
    if p['id'] in ('scip-typescript','scip-java'):
        print(p['id'], p['state']); [print('   ', d) for d in p['diagnostics']]
" 2>/dev/null || cat /tmp/verify.json
'@
    $ScriptFile = Join-Path $Work ('qualify-' + [Guid]::NewGuid().ToString('N').Substring(0, 6) + '.sh')
    [System.IO.File]::WriteAllText($ScriptFile, ($Script -replace "`r", ''), (New-Object System.Text.UTF8Encoding($false)))
    $Output = & docker run --rm --network none --entrypoint sh `
        -v "${Jar}:/minos.jar:ro" -v "${ToolsDirectory}:/payload:ro" -v "${ScriptFile}:/qualify.sh:ro" $Image /qualify.sh 2>&1 | Out-String
    Write-Host "== $Name" -ForegroundColor Cyan
    Write-Host $Output
    return $Output
}

try {
    $Clean = Invoke-InContainer $Tools 'valid payload, no network'
    $Tampered = Invoke-InContainer $Altered 'payload with one byte of the Node.js archive flipped, no network'
    $CleanOk = ($Clean -match 'scip-typescript-modules=embedded') -and ($Clean -match 'nodejs=embedded') -and ($Clean -notmatch 'refused')
    $TamperedOk = ($Tampered -match 'refused') -and ($Tampered -notmatch 'nodejs=') -and ($Tampered -notmatch 'scip-typescript-modules=embedded')
    Write-Host ''
    Write-Host ("  [{0}] valid payload seeded and verified with --network none" -f $(if ($CleanOk) { 'PASS' } else { 'FAIL' }))
    Write-Host ("  [{0}] altered payload refused, no download attempted" -f $(if ($TamperedOk) { 'PASS' } else { 'FAIL' }))
    if ($CleanOk -and $TamperedOk) { Write-Host 'SEEDING CONTAINER QUALIFICATION PASS' -ForegroundColor Green; exit 0 }
    Write-Host 'SEEDING CONTAINER QUALIFICATION FAIL' -ForegroundColor Red
    exit 1
}
finally { Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue }
