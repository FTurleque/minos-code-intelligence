[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $Package,

    [string] $InstallRoot = '',

    [ValidateSet('none', 'native', 'docker')]
    [string] $McpBackend = 'none',

    [string] $ProjectsRoot = '',
    [string] $DataRoot = '',
    [string] $DockerInstallRoot = '',
    [string] $DockerDataRoot = '',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerContainerName = 'minos-mcp-prod',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9_.-]+$')]
    [string] $DockerComposeProject = 'minos-mcp-prod',

    # Confirms that -ProjectsRoot replaces another projects root recorded by the Docker runtime: projects outside
    # the new root stay registered with their index and are reported rootAvailable: false.
    [switch] $ReplaceDockerProjectsRoot,

    [switch] $AddToPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-MinosVersion([string] $Launcher) {
    # Keep Windows PowerShell 5.1 from promoting harmless JVM stderr warnings
    # to terminating ErrorRecord objects while still checking the real exit.
    $ProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ProcessInfo.FileName = $env:ComSpec
    $ProcessInfo.Arguments = '/d /s /c ""{0}" --version"' -f $Launcher
    $ProcessInfo.WorkingDirectory = Split-Path -Parent $Launcher
    $ProcessInfo.UseShellExecute = $false
    $ProcessInfo.CreateNoWindow = $true
    $ProcessInfo.RedirectStandardOutput = $true
    $ProcessInfo.RedirectStandardError = $true

    $Process = New-Object System.Diagnostics.Process
    $Process.StartInfo = $ProcessInfo
    try {
        if (-not $Process.Start()) {
            throw 'Installed MINOS launcher validation process did not start.'
        }
        $StandardOutput = $Process.StandardOutput.ReadToEnd().Trim()
        $StandardError = $Process.StandardError.ReadToEnd().Trim()
        $Process.WaitForExit()
        if ($Process.ExitCode -ne 0) {
            throw "Installed MINOS launcher validation failed (exit=$($Process.ExitCode)): $StandardError"
        }
        return $StandardOutput
    }
    finally {
        $Process.Dispose()
    }
}

function Get-UnsafeDockerProjectsRootReason(
    [string] $ProjectsRoot, [string] $UserProfile, [string[]] $ProtectedRoots, [string] $TemporaryRoot) {
    # The projects root is bind-mounted into the MINOS containers. Returns why it must be refused, or an empty
    # string when it may be mounted. The reason carries no path. A strict subfolder of the temporary folder is
    # not refused for lying inside a protected folder that holds the temporary folder (application data, where
    # qualification runs place their roots); it is refused when it lies inside or contains any other one.
    function ConvertTo-ComparableRoot([string] $Path) {
        return [System.IO.Path]::GetFullPath($Path).TrimEnd([char[]] @('\', '/'))
    }
    function Test-SameOrInside([string] $Child, [string] $Parent) {
        return $Child.Equals($Parent, [System.StringComparison]::OrdinalIgnoreCase) -or
            $Child.StartsWith($Parent + '\', [System.StringComparison]::OrdinalIgnoreCase)
    }
    if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) { return 'empty' }
    if ([string]::IsNullOrWhiteSpace($UserProfile)) { return 'not verifiable because the user profile is unknown' }
    $Candidate = ConvertTo-ComparableRoot $ProjectsRoot
    $VolumeRoot = [System.IO.Path]::GetPathRoot($Candidate)
    if ([string]::IsNullOrEmpty($VolumeRoot) -or $Candidate.Length -le $VolumeRoot.TrimEnd([char[]] @('\', '/')).Length) {
        return 'a volume root'
    }
    if (Test-SameOrInside (ConvertTo-ComparableRoot $UserProfile) $Candidate) {
        return 'the user profile or one of its parents'
    }
    $Temporary = ''
    if (-not [string]::IsNullOrWhiteSpace($TemporaryRoot)) {
        $Temporary = ConvertTo-ComparableRoot $TemporaryRoot
        if (-not $Candidate.StartsWith($Temporary + '\', [System.StringComparison]::OrdinalIgnoreCase)) { $Temporary = '' }
    }
    foreach ($Protected in @($ProtectedRoots)) {
        if ([string]::IsNullOrWhiteSpace($Protected)) { continue }
        $ProtectedRoot = ConvertTo-ComparableRoot $Protected
        $HoldsTemporary = ($Temporary -ne '') -and (Test-SameOrInside $Temporary $ProtectedRoot)
        $Inside = (Test-SameOrInside $Candidate $ProtectedRoot) -and -not $HoldsTemporary
        if ($Inside -or (Test-SameOrInside $ProtectedRoot $Candidate)) {
            return 'an application data or MINOS folder, one of its parents or one of its subfolders'
        }
    }
    return ''
}

function Get-RecordedDockerProjectsRoot([string] $DockerDataRoot, [string] $ProjectsRoot) {
    # The host root of the project path mapping that minos-bootstrap saved in the Docker data root (a Java
    # properties file) when it differs from $ProjectsRoot; an empty string when there is none or when it is the
    # same root, separators, trailing separator and case ignored. Reads only.
    $MappingFile = Join-Path (Join-Path $DockerDataRoot 'runtime') 'project-paths.properties'
    if (-not (Test-Path -LiteralPath $MappingFile -PathType Leaf)) { return '' }
    $Recorded = ''
    foreach ($Line in [System.IO.File]::ReadAllLines($MappingFile, [System.Text.Encoding]::UTF8)) {
        if ($Line -match '^\s*hostRoot\s*[=:]\s*(.*)$') {
            $Recorded = [regex]::Replace($Matches[1].Trim(), '\\(.)', '$1')
            break
        }
    }
    if ([string]::IsNullOrWhiteSpace($Recorded)) { return '' }
    $RecordedComparable = $Recorded.Replace('\', '/').TrimEnd('/')
    $ChosenComparable = $ProjectsRoot.Replace('\', '/').TrimEnd('/')
    if ($RecordedComparable.Equals($ChosenComparable, [System.StringComparison]::OrdinalIgnoreCase)) { return '' }
    return $Recorded
}

if ($env:OS -ne 'Windows_NT') {
    throw 'MINOS Windows installer can only run on Windows.'
}

$LocalAppData = [Environment]::GetFolderPath('LocalApplicationData')
$ResolvedPackage = (Resolve-Path -LiteralPath $Package).Path
if ([string]::IsNullOrWhiteSpace($InstallRoot)) {
    $InstallRoot = Join-Path $LocalAppData 'Programs\MINOS'
}
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
if ([string]::IsNullOrWhiteSpace($DataRoot)) {
    $DataRoot = Join-Path $LocalAppData 'MINOS\data'
}
$DataRoot = [System.IO.Path]::GetFullPath($DataRoot)
if ([string]::IsNullOrWhiteSpace($DockerInstallRoot)) {
    $DockerInstallRoot = Join-Path $LocalAppData 'MINOS\docker'
}
$DockerInstallRoot = [System.IO.Path]::GetFullPath($DockerInstallRoot)
if ([string]::IsNullOrWhiteSpace($DockerDataRoot)) {
    $DockerDataRoot = Join-Path $LocalAppData 'MINOS\docker-data'
}
$DockerDataRoot = [System.IO.Path]::GetFullPath($DockerDataRoot)

if ($McpBackend -eq 'docker') {
    if ([string]::IsNullOrWhiteSpace($ProjectsRoot)) {
        throw '-ProjectsRoot is required when -McpBackend docker is selected.'
    }
    $ProjectsRoot = [System.IO.Path]::GetFullPath($ProjectsRoot)
    $UnsafeProjectsRoot = Get-UnsafeDockerProjectsRootReason -ProjectsRoot $ProjectsRoot `
        -UserProfile ([Environment]::GetFolderPath('UserProfile')) `
        -TemporaryRoot ([System.IO.Path]::GetTempPath()) `
        -ProtectedRoots @([Environment]::GetFolderPath('LocalApplicationData'), [Environment]::GetFolderPath('ApplicationData'),
            $InstallRoot, $DataRoot, $DockerInstallRoot, $DockerDataRoot)
    if (-not [string]::IsNullOrEmpty($UnsafeProjectsRoot)) {
        throw ("MINOS refuses this Docker projects root: it is $UnsafeProjectsRoot. It is mounted into " +
            'the MINOS containers; choose a folder dedicated to your projects.')
    }
    if (-not (Test-Path -LiteralPath $ProjectsRoot -PathType Container)) {
        throw "Docker projects root does not exist: $ProjectsRoot"
    }
    $RecordedProjectsRoot = Get-RecordedDockerProjectsRoot -DockerDataRoot $DockerDataRoot -ProjectsRoot $ProjectsRoot
    if (-not [string]::IsNullOrEmpty($RecordedProjectsRoot) -and -not $ReplaceDockerProjectsRoot) {
        throw ("The MINOS Docker runtime already uses another projects root ($RecordedProjectsRoot). Replacing it " +
            'keeps every registered project and its index: projects under the new root are rebased, the others ' +
            'are reported rootAvailable: false. Confirm the change with -ReplaceDockerProjectsRoot ' +
            '(/REPLACEDOCKERPROJECTSROOT=1 for a silent run of the Windows installer).')
    }
}

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

    foreach ($Required in @(
        'minos.cmd',
        'minos-mcp.cmd',
        'VERSION',
        'app\minos.exe',
        'lib\minos.jar',
        'integration\probe-mcp-backend.ps1',
        'integration\switch-mcp-backend.ps1',
        'docker\Dockerfile.mcp.release',
        'docker\compose-mcp.prod.yaml',
        'docker\scripts\prod-mcp-release.ps1',
        'docker\scripts\mcp-lifecycle.ps1',
        'docker\scripts\configure-docker-mcp.ps1'
    )) {
        if (-not (Test-Path -LiteralPath (Join-Path $Source $Required))) {
            throw "Invalid MINOS distribution: missing $Required"
        }
    }

    $Backup = $null
    if (Test-Path -LiteralPath $InstallRoot) {
        $Backup = "$InstallRoot.backup-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))"
        Move-Item -LiteralPath $InstallRoot -Destination $Backup
    }

    $OriginalUserPath = if ($AddToPath) { [Environment]::GetEnvironmentVariable('Path', 'User') } else { $null }
    $PathChanged = $false

    try {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $InstallRoot) | Out-Null
        Copy-Item -LiteralPath $Source -Destination $InstallRoot -Recurse

        # The Docker ownership marker contains the previous managed runtime
        # identity and custom roots. Preserve it across a ZIP payload replacement
        # so the switcher can reuse or transactionally upgrade that runtime.
        if ($null -ne $Backup) {
            $PreviousMarker = Join-Path $Backup '.docker-mcp-managed'
            $CurrentMarker = Join-Path $InstallRoot '.docker-mcp-managed'
            if (Test-Path -LiteralPath $PreviousMarker -PathType Leaf) {
                Copy-Item -LiteralPath $PreviousMarker -Destination $CurrentMarker -Force
            }
        }

        $InstalledVersion = Invoke-MinosVersion -Launcher (Join-Path $InstallRoot 'minos.cmd')

        # PATH is transactional with the payload. Apply it before backend
        # switching so a backend failure can restore both PATH and program
        # payload; after a successful backend transaction no fallible install
        # mutation remains.
        if ($AddToPath) {
            $Parts = @($OriginalUserPath -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($Parts -notcontains $InstallRoot) {
                $NewPath = (($Parts + $InstallRoot) -join ';')
                [Environment]::SetEnvironmentVariable('Path', $NewPath, 'User')
                $PathChanged = $true
            }
        }

        if ($McpBackend -ne 'none') {
            $Switcher = Join-Path $InstallRoot 'integration\switch-mcp-backend.ps1'
            $SwitchParameters = @{
                InstallRoot = $InstallRoot
                TargetBackend = $McpBackend
                DataRoot = $DataRoot
                DockerInstallRoot = $DockerInstallRoot
                DockerDataRoot = $DockerDataRoot
                DockerContainerName = $DockerContainerName
                DockerComposeProject = $DockerComposeProject
            }
            if ($McpBackend -eq 'docker') {
                $SwitchParameters['ProjectsRoot'] = $ProjectsRoot
                if ($ReplaceDockerProjectsRoot) { $SwitchParameters['ReplaceDockerProjectsRoot'] = $true }
            }
            & $Switcher @SwitchParameters
        }
    }
    catch {
        if ($PathChanged) {
            try {
                [Environment]::SetEnvironmentVariable('Path', $OriginalUserPath, 'User')
            }
            catch {
                Write-Warning "MINOS install rollback could not restore the user PATH: $($_.Exception.Message)"
            }
        }
        Remove-Item -LiteralPath $InstallRoot -Recurse -Force -ErrorAction SilentlyContinue
        if ($null -ne $Backup -and (Test-Path -LiteralPath $Backup)) {
            Move-Item -LiteralPath $Backup -Destination $InstallRoot
        }
        throw
    }

    Write-Host $InstalledVersion
    Write-Host ''
    Write-Host 'MINOS installation SUCCESS' -ForegroundColor Green
    Write-Host "Install : $InstallRoot"
    Write-Host "Data    : $DataRoot"
    Write-Host "Command : $(Join-Path $InstallRoot 'minos.cmd')"
    Write-Host "MCP     : $McpBackend"
    Write-Host "Switch  : $(Join-Path $InstallRoot 'integration\switch-mcp-backend.ps1')"
    if ($McpBackend -eq 'docker') {
        Write-Host "Projects: $ProjectsRoot"
        Write-Host "Docker  : $DockerInstallRoot"
    }
    if ($AddToPath) {
        Write-Host 'PATH    : added/preserved for the current user; open a new terminal before using `minos.cmd` by name.'
    }
    if ($null -ne $Backup) {
        Write-Host "Backup  : $Backup"
    }
}
finally {
    Remove-Item -LiteralPath $Temporary -Recurse -Force -ErrorAction SilentlyContinue
}
