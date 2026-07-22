[CmdletBinding()]
param(
    [string] $ScipVersion = "0.7.1",
    [switch] $Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ToolsRoot = Join-Path $RepoRoot ".minos-m0\tools"
$ToolsBin = Join-Path $ToolsRoot "bin"
$TempRoot = Join-Path $ToolsRoot "tmp"

New-Item -ItemType Directory -Force -Path $ToolsBin | Out-Null
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null

$CoursierExe = Join-Path $ToolsBin "cs.exe"
$ScipExe = Join-Path $ToolsBin "scip.exe"

$CoursierArchive = Join-Path $TempRoot "cs-x86_64-pc-win32.zip"
$CoursierExtract = Join-Path $TempRoot "coursier"
$ScipArchive = Join-Path $TempRoot "scip-windows-amd64.tar.gz"
$ScipExtract = Join-Path $TempRoot "scip"

# Official Coursier Windows command-line installation launcher.
# Coursier is only a bootstrap tool for M0, so MINOS does not pin its launcher version.
$CoursierUrl = "https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip"
$ScipUrl = "https://github.com/scip-code/scip/releases/download/v$ScipVersion/scip-windows-amd64.tar.gz"

function Download-File {
    param(
        [Parameter(Mandatory = $true)][string] $Uri,
        [Parameter(Mandatory = $true)][string] $Destination
    )

    $Partial = "$Destination.partial"
    Remove-Item -LiteralPath $Partial -Force -ErrorAction SilentlyContinue

    Write-Host "Download: $Uri"

    $Curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($Curl) {
        for ($Attempt = 1; $Attempt -le 4; $Attempt++) {
            Write-Host "  curl attempt $Attempt/4"
            & $Curl.Source --fail --location --connect-timeout 30 --user-agent "MINOS-M0" --output $Partial $Uri
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $Partial -PathType Leaf)) {
                break
            }

            Remove-Item -LiteralPath $Partial -Force -ErrorAction SilentlyContinue
            if ($Attempt -lt 4) {
                Start-Sleep -Seconds (2 * $Attempt)
            }
        }

        if (-not (Test-Path -LiteralPath $Partial -PathType Leaf)) {
            throw "Download failed with curl.exe after 4 attempts: $Uri"
        }
    }
    else {
        $PreviousProtocol = [Net.ServicePointManager]::SecurityProtocol
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

            $Downloaded = $false
            for ($Attempt = 1; $Attempt -le 4; $Attempt++) {
                Write-Host "  PowerShell attempt $Attempt/4 (TLS 1.2)"
                try {
                    Invoke-WebRequest `
                        -Uri $Uri `
                        -OutFile $Partial `
                        -UseBasicParsing `
                        -Headers @{ "User-Agent" = "MINOS-M0" }
                    $Downloaded = $true
                    break
                }
                catch {
                    Remove-Item -LiteralPath $Partial -Force -ErrorAction SilentlyContinue
                    if ($Attempt -eq 4) {
                        throw
                    }
                    Start-Sleep -Seconds (2 * $Attempt)
                }
            }

            if (-not $Downloaded) {
                throw "Download failed with Windows PowerShell: $Uri"
            }
        }
        finally {
            [Net.ServicePointManager]::SecurityProtocol = $PreviousProtocol
        }
    }

    $DownloadedFile = Get-Item -LiteralPath $Partial -ErrorAction Stop
    if ($DownloadedFile.Length -le 0) {
        Remove-Item -LiteralPath $Partial -Force -ErrorAction SilentlyContinue
        throw "Downloaded file is empty: $Uri"
    }

    Move-Item -LiteralPath $Partial -Destination $Destination -Force
}

function Test-Executable {
    param(
        [Parameter(Mandatory = $true)][string] $Path,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Name
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "$Name not found after installation: $Path"
    }

    Write-Host "==> $Name"
    & $Path @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Name returned exit code $LASTEXITCODE"
    }
}

try {
    if ($Force -or -not (Test-Path -LiteralPath $CoursierExe -PathType Leaf)) {
        Remove-Item -LiteralPath $CoursierArchive -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $CoursierExtract -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $CoursierExtract | Out-Null

        Download-File -Uri $CoursierUrl -Destination $CoursierArchive
        Expand-Archive -LiteralPath $CoursierArchive -DestinationPath $CoursierExtract -Force

        $DownloadedCoursier = Get-ChildItem -LiteralPath $CoursierExtract -Recurse -File |
            Where-Object { $_.Name -eq "cs-x86_64-pc-win32.exe" -or $_.Name -eq "cs.exe" } |
            Select-Object -First 1

        if (-not $DownloadedCoursier) {
            throw "Coursier launcher not found in downloaded archive."
        }

        Copy-Item -LiteralPath $DownloadedCoursier.FullName -Destination $CoursierExe -Force
    }

    if ($Force -or -not (Test-Path -LiteralPath $ScipExe -PathType Leaf)) {
        if (-not (Get-Command tar.exe -ErrorAction SilentlyContinue)) {
            throw "tar.exe is required to extract the SCIP binary on Windows."
        }

        Remove-Item -LiteralPath $ScipExtract -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $ScipArchive -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $ScipExtract | Out-Null

        Download-File -Uri $ScipUrl -Destination $ScipArchive
        & tar.exe -xzf $ScipArchive -C $ScipExtract
        if ($LASTEXITCODE -ne 0) {
            throw "SCIP extraction failed with exit code $LASTEXITCODE"
        }

        $DownloadedScip = Get-ChildItem -LiteralPath $ScipExtract -Recurse -File |
            Where-Object { $_.Name -eq "scip.exe" -or $_.Name -eq "scip" } |
            Select-Object -First 1

        if (-not $DownloadedScip) {
            throw "SCIP binary not found in downloaded archive."
        }

        Copy-Item -LiteralPath $DownloadedScip.FullName -Destination $ScipExe -Force
    }

    Write-Host
    Write-Host "=== MINOS M0 TOOLS ===" -ForegroundColor Cyan
    Test-Executable -Path $CoursierExe -Arguments @("--help") -Name "Coursier launcher"
    Test-Executable -Path $ScipExe -Arguments @("--version") -Name "SCIP CLI $ScipVersion"

    Write-Host
    Write-Host "Local installation completed." -ForegroundColor Green
    Write-Host "Coursier : $CoursierExe"
    Write-Host "SCIP     : $ScipExe"
    Write-Host
    Write-Host "No user PATH or JDK configuration was modified."
}
finally {
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
