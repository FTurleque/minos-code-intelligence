[CmdletBinding()]
param(
    [string] $Distribution = "Ubuntu",
    [string] $GleanVersion = "0.2.0.1",
    [switch] $InstallSystemDependencies,
    [switch] $Force
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$ExperimentRoot = Join-Path $RepoRoot ".minos-m0\experiments\glean-c1"
$InstallLog = Join-Path $ExperimentRoot "install.txt"
$EnvironmentFile = Join-Path $ExperimentRoot "environment.txt"

New-Item -ItemType Directory -Force -Path $ExperimentRoot | Out-Null

$Wsl = Get-Command wsl.exe -ErrorAction SilentlyContinue
if (-not $Wsl) {
    throw "wsl.exe is required for the Glean M0 experiment."
}

function Invoke-Wsl {
    param(
        [Parameter(Mandatory = $true)][string[]] $Arguments,
        [Parameter(Mandatory = $true)][string] $FailureMessage
    )

    & $Wsl.Source @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$FailureMessage (exit $LASTEXITCODE)."
    }
}

$DistributionNames = & $Wsl.Source --list --quiet |
    ForEach-Object { $_ -replace "`0", "" } |
    ForEach-Object { $_.Trim() } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($DistributionNames -notcontains $Distribution) {
    throw "WSL distribution '$Distribution' was not found. Available: $($DistributionNames -join ', ')"
}

$PortableRepoRoot = $RepoRoot -replace "\\", "/"
$WslRepoRoot = (& $Wsl.Source -d $Distribution -- wslpath -a -u $PortableRepoRoot) -replace "`0", ""
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($WslRepoRoot)) {
    throw "Unable to resolve the MINOS repository path inside WSL."
}
$WslRepoRoot = $WslRepoRoot.Trim()
$WslExperimentRoot = "$WslRepoRoot/.minos-m0/experiments/glean-c1"
$WslInstallLog = "$WslExperimentRoot/install.txt"
$WslEnvironmentFile = "$WslExperimentRoot/environment.txt"

$SystemPackages = @(
    "ghc",
    "cabal-install",
    "g++",
    "cmake",
    "make",
    "ninja-build",
    "bison",
    "flex",
    "git",
    "curl",
    "rsync",
    "m4",
    "pkg-config",
    "binutils-dev",
    "libboost-all-dev",
    "libdouble-conversion-dev",
    "libdwarf-dev",
    "libevent-dev",
    "libfast-float-dev",
    "libfftw3-dev",
    "libfmt-dev",
    "libgflags-dev",
    "libgmock-dev",
    "libgoogle-glog-dev",
    "libgtest-dev",
    "libiberty-dev",
    "libjemalloc-dev",
    "liblz4-dev",
    "liblzma-dev",
    "libpcre3-dev",
    "librocksdb-dev",
    "libsnappy-dev",
    "libsodium-dev",
    "libssl-dev",
    "libtinfo-dev",
    "libunwind-dev",
    "libxxhash-dev",
    "libzstd-dev",
    "zlib1g-dev",
    "libgmp-dev",
    "libnuma-dev"
)

if ($InstallSystemDependencies) {
    Write-Host "Installing official Glean build dependencies in WSL '$Distribution'..."
    Invoke-Wsl `
        -Arguments @("-d", $Distribution, "--user", "root", "--", "apt-get", "update") `
        -FailureMessage "Unable to update APT metadata"
    Invoke-Wsl `
        -Arguments (@("-d", $Distribution, "--user", "root", "--", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y") + $SystemPackages) `
        -FailureMessage "Unable to install Glean system dependencies"
}

$InstalledPackageLines = & $Wsl.Source -d $Distribution -- dpkg-query --show @SystemPackages 2>$null
$InstalledPackages = @($InstalledPackageLines | ForEach-Object {
    (($_ -split "`t", 2)[0]) -replace ":[^:]+$", ""
})
$MissingPackages = @($SystemPackages | Where-Object { $InstalledPackages -notcontains $_ })
if ($MissingPackages.Count -gt 0) {
    throw "Glean system dependencies are missing: $($MissingPackages -join ', '). Re-run with -InstallSystemDependencies."
}

$ForceValue = if ($Force) { "1" } else { "0" }
$InstallScript = @'
set -euo pipefail

glean_version='__GLEAN_VERSION__'
force='__FORCE__'
tools_root="$HOME/.minos-m0/glean"
bin_dir="$tools_root/$glean_version/bin"
cabal_dir="$tools_root/cabal"
cache_dir="$tools_root/cache"
source_root="$tools_root/source"
source_dir="$source_root/glean-$glean_version"
experiment_root='__EXPERIMENT_ROOT__'
install_log='__INSTALL_LOG__'
environment_file='__ENVIRONMENT_FILE__'

mkdir -p "$bin_dir" "$cabal_dir" "$cache_dir" "$source_root" "$experiment_root"
export CABAL_DIR="$cabal_dir"
export XDG_CACHE_HOME="$cache_dir"
cd "$tools_root"

if [[ -f "$install_log.partial" ]]; then
  previous_attempt="$experiment_root/install-attempt-$(date -u +%Y%m%dT%H%M%SZ)-failed.txt"
  mv "$install_log.partial" "$previous_attempt"
fi
exec > >(tee "$install_log.partial") 2>&1

echo "Glean C1 installation"
echo "distribution=__DISTRIBUTION__"
echo "gleanVersion=$glean_version"
echo "toolsRoot=$tools_root"

install_start_ns=$(date +%s%N)
if [[ "$force" == "1" || ! -x "$bin_dir/glean" ]]; then
  cabal update
  if [[ ! -f "$source_dir/glean.cabal" ]]; then
    source_download_dir="$source_root/download-$(date -u +%Y%m%dT%H%M%SZ)-$$"
    mkdir -p "$source_download_dir"
    cabal get "glean-$glean_version" --destdir="$source_download_dir"
    mv "$source_download_dir/glean-$glean_version" "$source_dir"
    rmdir "$source_download_dir"
  fi
  publish_source=""
  store_root="$cabal_dir/store/ghc-$(ghc --numeric-version)"
  mapfile -t store_binaries < <(
    find "$store_root" -type f \
      -path "*/glean-$glean_version-e-glean-*/bin/glean" -print
  )
  if [[ "${#store_binaries[@]}" == "1" ]]; then
    publish_source="${store_binaries[0]}"
  elif [[ "${#store_binaries[@]}" -gt "1" ]]; then
    echo "Expected at most one built Glean binary, found ${#store_binaries[@]}." >&2
    exit 1
  else
    cd "$source_dir"
    cabal build exe:glean \
      -fuse-folly-clib \
      -f-s3-support \
      -f-clang-tests \
      -f-hack-tests \
      -f-typescript-tests \
      -f-python-tests \
      -f-dotnet-tests \
      -f-rust-tests \
      -f-flow-tests \
      -f-java-lsif-tests

    local_binary=$(cabal list-bin exe:glean)
    if [[ -f "$local_binary" ]]; then
      publish_source="$local_binary"
    fi
  fi

  if [[ -z "$publish_source" ]]; then
    mapfile -t store_binaries < <(
      find "$store_root" -type f \
        -path "*/glean-$glean_version-e-glean-*/bin/glean" -print
    )
    if [[ "${#store_binaries[@]}" != "1" ]]; then
      echo "Expected one built Glean binary, found ${#store_binaries[@]}." >&2
      exit 1
    fi
    publish_source="${store_binaries[0]}"
  fi

  install -m 0755 "$publish_source" "$bin_dir/glean.partial"
  "$bin_dir/glean.partial" --version 2>/dev/null
  "$bin_dir/glean.partial" index scip --help >/dev/null 2>&1
  mv -f "$bin_dir/glean.partial" "$bin_dir/glean"
else
  echo "Glean $glean_version is already installed; use -Force to reinstall."
fi
install_duration_ms=$(( ($(date +%s%N) - install_start_ns) / 1000000 ))

"$bin_dir/glean" --version 2>/dev/null
"$bin_dir/glean" index scip --help >/dev/null 2>&1

{
  echo "capturedAt=$(date --iso-8601=seconds)"
  echo "distribution=__DISTRIBUTION__"
  sed -n 's/^PRETTY_NAME=/os=/p' /etc/os-release
  echo "kernel=$(uname -r)"
  echo "architecture=$(uname -m)"
  echo "processors=$(nproc)"
  echo "memoryBytes=$(awk '/MemTotal/ { print $2 * 1024 }' /proc/meminfo)"
  echo "ghc=$(ghc --numeric-version)"
  echo "cabal=$(cabal --numeric-version)"
  echo "gleanVersion=$glean_version"
  echo "gleanBinary=$bin_dir/glean"
  echo "gleanSource=$source_dir"
  echo "installDurationMs=$install_duration_ms"
  echo "gleanInstallBytes=$(du -sb "$tools_root/$glean_version" | cut -f1)"
  echo "cabalStoreBytes=$(du -sb "$cabal_dir" | cut -f1)"
} > "$environment_file.partial"

mv -f "$environment_file.partial" "$environment_file"
mv -f "$install_log.partial" "$install_log"
'@

$InstallScript = $InstallScript.Replace("__GLEAN_VERSION__", $GleanVersion)
$InstallScript = $InstallScript.Replace("__FORCE__", $ForceValue)
$InstallScript = $InstallScript.Replace("__EXPERIMENT_ROOT__", $WslExperimentRoot)
$InstallScript = $InstallScript.Replace("__INSTALL_LOG__", $WslInstallLog)
$InstallScript = $InstallScript.Replace("__ENVIRONMENT_FILE__", $WslEnvironmentFile)
$InstallScript = $InstallScript.Replace("__DISTRIBUTION__", $Distribution)

$EncodedScript = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($InstallScript))
$Bootstrap = "echo '$EncodedScript' | base64 --decode | bash"

Write-Host "Installing Glean $GleanVersion into the WSL user-local MINOS tool root..."
Invoke-Wsl `
    -Arguments @("-d", $Distribution, "--", "bash", "-lc", $Bootstrap) `
    -FailureMessage "Glean installation failed"

if (-not (Test-Path -LiteralPath $InstallLog -PathType Leaf)) {
    throw "Glean installation log was not published transactionally: $InstallLog"
}
if (-not (Test-Path -LiteralPath $EnvironmentFile -PathType Leaf)) {
    throw "Glean environment report was not published transactionally: $EnvironmentFile"
}

Write-Host
Write-Host "Glean WSL installation completed." -ForegroundColor Green
Write-Host "Log        : $InstallLog"
Write-Host "Environment: $EnvironmentFile"
