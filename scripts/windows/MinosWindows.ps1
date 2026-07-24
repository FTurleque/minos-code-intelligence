Set-StrictMode -Version 2.0

function Get-MinosJavaVersion {
    param([Parameter(Mandatory = $true)][string]$JavaExecutable)

    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $JavaExecutable
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $startInfo
    try {
        if (-not $process.Start()) {
            return $null
        }
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()
        $combined = ($standardOutput + "`n" + $standardError).Trim()
        $firstLine = $combined -split "`r?`n" | Select-Object -First 1
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = $combined
            FirstLine = [string]$firstLine
        }
    } catch {
        return $null
    } finally {
        $process.Dispose()
    }
}

function Resolve-MinosJava24 {
    $candidates = @()
    foreach ($configuredHome in @($env:MINOS_JAVA_HOME, $env:JAVA_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($configuredHome)) {
            $candidates += [Environment]::ExpandEnvironmentVariables($configuredHome)
        }
    }

    $searchRoots = @(
        (Join-Path $env:USERPROFILE '.jdks'),
        'C:\Program Files\Java',
        'C:\Program Files\Eclipse Adoptium',
        'C:\Program Files\Microsoft'
    )
    foreach ($searchRoot in $searchRoots) {
        if (Test-Path -LiteralPath $searchRoot -PathType Container) {
            $candidates += @(
                Get-ChildItem -LiteralPath $searchRoot -Directory -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -match '^(?:openjdk-|jdk-)?24(?:\.|$)' } |
                    Sort-Object Name -Descending |
                    Select-Object -ExpandProperty FullName
            )
        }
    }

    $pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -ne $pathJava) {
        $candidates += Split-Path -Parent (Split-Path -Parent $pathJava.Source)
    }

    $seen = @{}
    foreach ($candidate in $candidates) {
        if ([string]::IsNullOrWhiteSpace($candidate)) {
            continue
        }
        $javaHome = [System.IO.Path]::GetFullPath($candidate)
        $key = $javaHome.ToLowerInvariant()
        if ($seen.ContainsKey($key)) {
            continue
        }
        $seen[$key] = $true

        $javaExecutable = Join-Path $javaHome 'bin\java.exe'
        $javacExecutable = Join-Path $javaHome 'bin\javac.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf) -or
            -not (Test-Path -LiteralPath $javacExecutable -PathType Leaf)) {
            continue
        }
        $version = Get-MinosJavaVersion -JavaExecutable $javaExecutable
        if ($null -ne $version -and $version.ExitCode -eq 0 -and
            $version.Output -match '(?m)^(?:openjdk|java) version "24(?:\.|\")') {
            return [pscustomobject]@{
                JavaHome = $javaHome
                JavaExecutable = $javaExecutable
                JavacExecutable = $javacExecutable
                Version = $version.Output
                VersionLine = $version.FirstLine
            }
        }
    }

    throw 'JDK 24 introuvable. Configurez MINOS_JAVA_HOME ou installez un JDK 24 sous %USERPROFILE%\.jdks.'
}

function Invoke-MinosNative {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$ArgumentList,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )

    & $FilePath @ArgumentList
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "$FailureMessage (code $exitCode)."
    }
}

function Write-MinosUtf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding -ArgumentList $false
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function ConvertTo-MinosDockerPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return ([System.IO.Path]::GetFullPath($Path)).Replace('\', '/')
}
