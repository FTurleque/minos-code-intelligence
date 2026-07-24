[CmdletBinding()]
param(
    [switch]$ValidateOnly
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version 2.0

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
. (Join-Path $repositoryRoot "scripts\windows\MinosWindows.ps1")
$java = Resolve-MinosJava24

$env:JAVA_HOME = $java.JavaHome
$env:Path = "$($java.JavaHome)\bin;$env:Path"
Push-Location $repositoryRoot
try {
    Invoke-MinosNative -FilePath ".\mvnw.cmd" -ArgumentList @('-q', '-DskipTests', 'package') `
        -FailureMessage 'Le packaging DEV MINOS a echoue'
} finally {
    Pop-Location
}

$jar = Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'target') -File `
    -Filter 'minos-code-intelligence-*-all.jar' |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$minosHome = Join-Path $repositoryRoot "target\minos-dev-home"

if ([string]::IsNullOrWhiteSpace($jar) -or -not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "JAR shaded MINOS absent. Lancez d'abord le profil DEV ou .\mvnw.cmd package sous Java 24."
}

if ($ValidateOnly) {
    Write-Host "MINOS DEV launch validation SUCCESS"
    Write-Host "Java: $($java.VersionLine)"
    Write-Host "Home: $minosHome"
    Write-Host "JAR : $jar"
    return
}

New-Item -ItemType Directory -Path $minosHome -Force | Out-Null
& $java.JavaExecutable "-Dminos.home=$minosHome" -jar $jar mcp
exit $LASTEXITCODE
