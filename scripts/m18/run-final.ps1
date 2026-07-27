[CmdletBinding()]
param(
    [string] $ExpectedHead = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $RepoRoot 'scripts\windows\MinosWindows.ps1')

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory = $true)][string] $File,
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $Failure
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $File @Arguments
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($exit -ne 0) { throw "$Failure (exit=$exit)" }
}

function Resolve-Python {
    foreach ($name in @('python.exe','python','python3.exe','python3')) {
        $command = Get-Command $name -ErrorAction SilentlyContinue
        if ($command) { return $command.Source }
    }
    throw 'M18 requires Python in PATH for product-facts verification.'
}

function Get-GradleVersion {
    param([Parameter(Mandatory = $true)][string] $Gradle)

    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Gradle --version 2>&1)
        if ($LASTEXITCODE -ne 0) { return '' }
    } finally {
        $ErrorActionPreference = $previous
    }
    foreach ($line in $output) {
        if ([string]$line -match '^Gradle\s+([0-9]+(?:\.[0-9]+)+)') {
            return $Matches[1]
        }
    }
    return ''
}

function Resolve-Gradle {
    $requiredVersion = '9.6.1'
    $requiredSha256 = '9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14'

    $command = Get-Command 'gradle' -ErrorAction SilentlyContinue
    if ($command) {
        $version = Get-GradleVersion -Gradle $command.Source
        if ($version -eq $requiredVersion) {
            Write-Host "Using Gradle $version from PATH: $($command.Source)"
            return $command.Source
        }
        if (-not [string]::IsNullOrWhiteSpace($version)) {
            Write-Host "Gradle $version found in PATH; M18 qualification is pinned to $requiredVersion."
        }
    }

    $cacheBase = if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        Join-Path $env:LOCALAPPDATA 'MINOS\tool-cache\gradle'
    } else {
        Join-Path ([System.IO.Path]::GetTempPath()) 'MINOS\tool-cache\gradle'
    }
    $cacheRoot = Join-Path $cacheBase $requiredVersion
    $gradleHome = Join-Path $cacheRoot "gradle-$requiredVersion"
    $gradle = if ($env:OS -eq 'Windows_NT') {
        Join-Path $gradleHome 'bin\gradle.bat'
    } else {
        Join-Path $gradleHome 'bin/gradle'
    }

    if (Test-Path -LiteralPath $gradle -PathType Leaf) {
        Write-Host "Using cached Gradle ${requiredVersion}: $gradle"
        return $gradle
    }

    New-Item -ItemType Directory -Path $cacheRoot -Force | Out-Null
    $archive = Join-Path $cacheRoot "gradle-$requiredVersion-bin.zip"
    $distributionUrl = "https://services.gradle.org/distributions/gradle-$requiredVersion-bin.zip"

    $needsDownload = $true
    if (Test-Path -LiteralPath $archive -PathType Leaf) {
        $cachedHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($cachedHash -eq $requiredSha256) {
            $needsDownload = $false
        } else {
            Remove-Item -LiteralPath $archive -Force
        }
    }

    if ($needsDownload) {
        Write-Host "Downloading Gradle $requiredVersion from the official distribution service..."
        if ($PSVersionTable.PSVersion.Major -lt 6) {
            [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        }
        Invoke-WebRequest -Uri $distributionUrl -OutFile $archive -UseBasicParsing
    }

    $actualSha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $requiredSha256) {
        Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
        throw "Gradle $requiredVersion SHA-256 mismatch: expected=$requiredSha256 actual=$actualSha256"
    }

    if (Test-Path -LiteralPath $gradleHome) {
        Remove-Item -LiteralPath $gradleHome -Recurse -Force
    }
    Write-Host "Extracting verified Gradle $requiredVersion to $cacheRoot ..."
    Expand-Archive -LiteralPath $archive -DestinationPath $cacheRoot -Force

    if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
        throw "Gradle $requiredVersion bootstrap failed: executable not found at $gradle"
    }
    Write-Host "Using bootstrapped Gradle ${requiredVersion}: $gradle"
    return $gradle
}

function Assert-M18Structure {
    $required = @(
        'docs\roadmap\M18_EXECUTION.md',
        'docs\adr\0027-intellij-external-client-and-versioned-cli-protocol.md',
        'docs\user\intellij-plugin.md',
        'minos-cli\src\main\java\com\minos\cli\IdeCommand.java',
        'minos-cli\src\main\java\com\minos\cli\GitActivityCommand.java',
        'minos-intellij\build.gradle.kts',
        'minos-intellij\src\main\resources\META-INF\plugin.xml',
        'minos-intellij\src\main\java\com\minos\intellij\protocol\MinosCliClient.java',
        'minos-intellij\src\main\java\com\minos\intellij\navigation\MinosNavigation.java',
        'minos-intellij\src\main\java\com\minos\intellij\ui\MinosToolWindowFactory.java',
        '.github\workflows\intellij-plugin.yml',
        '.github\workflows\intellij-plugin-release.yml'
    )
    foreach ($relative in $required) {
        if (-not (Test-Path -LiteralPath (Join-Path $RepoRoot $relative) -PathType Leaf)) {
            throw "Required M18 file is missing: $relative"
        }
    }

    $pluginBuild = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-intellij\build.gradle.kts') -Raw
    if ($pluginBuild -match 'implementation\("com\.minos:') {
        throw 'M18 plugin must not depend on com.minos:* Maven artifacts.'
    }
    if ($pluginBuild -notmatch 'org\.jetbrains\.intellij\.platform.*2\.18\.1') {
        throw 'M18 plugin must use the qualified IntelliJ Platform Gradle Plugin 2.18.1.'
    }
    if ($pluginBuild -notmatch 'providers\.gradleProperty\("minosVersion"\)') {
        throw 'M18 plugin release version must be overridable with -PminosVersion.'
    }
    if ($pluginBuild -notmatch 'testRuntimeOnly\("org\.junit\.platform:junit-platform-launcher:1\.14\.4"\)') {
        throw 'M18 plugin tests must include the JUnit Platform launcher on the runtime classpath.'
    }
    if ($pluginBuild -notmatch 'id\s*=\s*"com\.minos\.codeintelligence"') {
        throw 'M18 plugin Gradle configuration must use the JetBrains-compliant plugin id com.minos.codeintelligence.'
    }
    if ($pluginBuild -notmatch 'sourceCompatibility\s*=\s*JavaVersion\.VERSION_21' -or
        $pluginBuild -notmatch 'targetCompatibility\s*=\s*JavaVersion\.VERSION_21' -or
        $pluginBuild -notmatch 'options\.release\s*=\s*21') {
        throw 'M18 plugin must target Java 21 source/target/release compatibility.'
    }

    $protocol = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-cli\src\main\java\com\minos\cli\IdeCommand.java') -Raw
    if ($protocol -notmatch 'PROTOCOL_ID\s*=\s*"minos-ide"' -or $protocol -notmatch 'PROTOCOL_VERSION\s*=\s*"1"') {
        throw 'M18 IDE protocol id/version is not the qualified v1 contract.'
    }

    $gitActivity = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-cli\src\main\java\com\minos\cli\GitActivityCommand.java') -Raw
    if ($gitActivity -notmatch 'importanceInference' -or $gitActivity -notmatch 'FACTUAL_ACTIVITY') {
        throw 'M18 Git adapter must explicitly preserve activity != importance.'
    }

    $pluginXml = Get-Content -LiteralPath (Join-Path $RepoRoot 'minos-intellij\src\main\resources\META-INF\plugin.xml') -Raw
    if ($pluginXml -notmatch '<id>com\.minos\.codeintelligence</id>') {
        throw 'M18 plugin.xml must use the JetBrains-compliant plugin id com.minos.codeintelligence.'
    }
    foreach ($action in @('OpenDefinition','FindUsages','Dependents','Implementations','RelatedTests','Impact','Architecture','CopyIdentity')) {
        if ($pluginXml -notmatch "Minos\.$action") { throw "M18 plugin action missing: $action" }
    }
}

Push-Location $RepoRoot
try {
    Write-Host '=== MINOS M18 - FINAL IntelliJ integration exact-head qualification ===' -ForegroundColor Cyan

    $dirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to inspect worktree.' }
    if ($dirty.Count -gt 0) { throw "M18 final runner requires a clean worktree.`n$($dirty -join "`n")" }

    $head = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ([string]::IsNullOrWhiteSpace($head)) { throw 'Unable to resolve HEAD.' }
    if (-not [string]::IsNullOrWhiteSpace($ExpectedHead) -and $head -ne $ExpectedHead) {
        throw "M18 exact-head mismatch: expected=$ExpectedHead actual=$head"
    }
    Write-Host "HEAD: $head"

    Write-Host '[1/6] Checking M18 structure and architecture boundaries...'
    Assert-M18Structure

    Write-Host '[2/6] Checking generated product facts...'
    $python = Resolve-Python
    Invoke-NativeChecked -File $python -Arguments @('scripts/docs/product-facts.py','--check') -Failure 'Product facts consistency failed'

    Write-Host '[3/6] Running complete Maven Java 24 verification...'
    $java = Resolve-MinosJava24
    $env:JAVA_HOME = $java.JavaHome
    $env:Path = "$($java.JavaHome)\bin;$env:Path"
    $maven = if ($env:OS -eq 'Windows_NT') { Join-Path $RepoRoot 'mvnw.cmd' } else { Join-Path $RepoRoot 'mvnw' }
    Invoke-NativeChecked -File $maven -Arguments @('-B','-ntp','clean','verify') -Failure 'Maven clean verify failed'
    Invoke-NativeChecked -File $python -Arguments @('scripts/quality/check-jacoco.py') -Failure 'JaCoCo gate failed'

    Write-Host '[4/6] Verifying IntelliJ plugin Java 21 target with pinned Gradle 9.6.1...'
    $gradle = Resolve-Gradle
    Invoke-NativeChecked -File $gradle -Arguments @(
        '-p','minos-intellij','--no-daemon',
        'test','buildPlugin','verifyPluginProjectConfiguration','verifyPluginStructure','verifyPlugin'
    ) -Failure 'IntelliJ plugin qualification failed'

    $distribution = @(Get-ChildItem -LiteralPath (Join-Path $RepoRoot 'minos-intellij\build\distributions') -Filter '*.zip' -File -ErrorAction SilentlyContinue)
    if ($distribution.Count -lt 1) { throw 'M18 plugin ZIP was not produced.' }
    Write-Host "Plugin ZIP: $($distribution[0].FullName)"

    Write-Host '[5/6] Rechecking exact HEAD and clean worktree...'
    $finalHead = ((& git rev-parse HEAD) | Select-Object -First 1).Trim()
    if ($finalHead -ne $head) { throw "HEAD changed during M18 qualification: start=$head end=$finalHead" }
    $finalDirty = @(& git status --porcelain)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to re-inspect worktree.' }
    $trackedDirty = @($finalDirty | Where-Object { $_ -notmatch '^\?\? minos-intellij[/\\](build|\.intellijPlatform)[/\\]' })
    if ($trackedDirty.Count -gt 0) { throw "Tracked worktree changed during M18 qualification.`n$($trackedDirty -join "`n")" }

    Write-Host '[6/6] Qualification complete.'
    Write-Host 'M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS' -ForegroundColor Green
    Write-Host "Validated HEAD: $head"
} finally {
    Pop-Location
}
