[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Package,

    [string] $InstallRoot = '',

    [switch] $AddToPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows installer can only run on Windows.'
}

$ResolvedPackage = (Resolve-Path -LiteralPath $Package).Path
if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
    $InstallRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Programs\MINOS'
}
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)

$Temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("minos-install-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Force -Path $Temporary | Out-Null
try {
    if ((Get-Item -LiteralPath $ResolvedPackage).Extension -ieq '.zip') {
        Expand-Archive -LiteralPath $ResolvedPackage -DestinationPath $Temporary -Force
        $Candidates = @(Get-ChildItem -LiteralPath $Temporary -Directory)
        if ($Candidates.Count -ne 1) {
            throw 'MINOS package must contain exactly one distribution root directory.'
        }
        $Source = $Candidates[0].FullName
    }
    elseif (Test-Path -LiteralPath $ResolvedPackage -PathType Container) {
        $Source = $ResolvedPackage
    }
    else {
        throw 'Package must be a MINOS distribution ZIP or directory.'
    }

    foreach ($Required in @('minos.cmd', 'minos-mcp.cmd', 'VERSION', 'app\minos.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $Source $Required))) {
            throw "Invalid MINOS distribution: missing $Required"
        }
    }

    $Backup = $null
    if (Test-Path -LiteralPath $InstallRoot) {
        $Backup = "$InstallRoot.backup-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))"
        Move-Item -LiteralPath $InstallRoot -Destination $Backup
    }
    try {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $InstallRoot) | Out-Null
        Copy-Item -LiteralPath $Source -Destination $InstallRoot -Recurse
    }
    catch {
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
        if ($null -ne $Backup -and (Test-Path -LiteralPath $Backup)) {
            Move-Item -LiteralPath $Backup -Destination $InstallRoot
        }
        throw
    }

    if ($AddToPath) {
        $UserPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        $Parts = @($UserPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($Parts -notcontains $InstallRoot) {
            $NewPath = (($Parts + $InstallRoot) -join ';')
            [Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')
        }
    }

    & (Join-Path $InstallRoot 'minos.cmd') --version
    if ($LASTEXITCODE -ne 0) {
        throw 'Installed MINOS launcher validation failed.'
    }

    Write-Host ''
    Write-Host 'MINOS installation SUCCESS' -ForegroundColor Green
    Write-Host "Install : $InstallRoot"
    Write-Host "Data    : $([Environment]::GetFolderPath('LocalApplicationData'))\MINOS\data"
    Write-Host "Command : $(Join-Path $InstallRoot 'minos.cmd')"
    if ($AddToPath) {
        Write-Host 'PATH    : added for the current user; open a new terminal before using `minos.cmd` by name.'
    }
    if ($null -ne $Backup) {
        Write-Host "Backup  : $Backup"
    }
}
finally {
    Remove-Item -LiteralPath $Temporary -Recurse -Force -ErrorAction SilentlyContinue
}
