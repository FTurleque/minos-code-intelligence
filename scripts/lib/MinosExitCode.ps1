# A library: it sets no StrictMode, a dot-sourced Set-StrictMode would lower the caller's.
#
# The one PowerShell reader of scripts/lib/partial-result-commands.json (docs/audit/archive/2026-09/Q25-Q26-SUIVI.md, section 2.3).
# A script that runs a MINOS command by name and compares its exit code to 0 is wrong for the commands listed there:
# they exit 3 (a valid, partial answer) when some registry entries are unreadable. Every other non-zero code, and 3
# from any other command, stays a failure.

$script:MinosPartialResultSpec = $null

function Get-MinosPartialResultSpec {
    if ($null -eq $script:MinosPartialResultSpec) {
        $path = Join-Path $PSScriptRoot 'partial-result-commands.json'
        $script:MinosPartialResultSpec = Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
    return $script:MinosPartialResultSpec
}

# The exit codes that mean "the command answered" for these MINOS arguments (the arguments after `minos`, not the
# launcher's own). Always wrap the call in @(...): a single code is unrolled to a scalar.
function Get-MinosAcceptedExitCodes {
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $MinosArguments)
    $spec = Get-MinosPartialResultSpec
    foreach ($command in @($spec.commands)) {
        $words = @(([string] $command) -split ' ')
        if ($MinosArguments.Count -lt $words.Count) { continue }
        $same = $true
        for ($index = 0; $index -lt $words.Count; $index++) {
            if ($MinosArguments[$index] -cne $words[$index]) { $same = $false; break }
        }
        if ($same) { return [int[]] @(0, [int] $spec.partialResultExitCode) }
    }
    return [int[]] @(0)
}

function Test-MinosExitCodeAccepted {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $MinosArguments,
        [Parameter(Mandatory = $true)][int] $ExitCode
    )
    return (@(Get-MinosAcceptedExitCodes -MinosArguments $MinosArguments) -contains $ExitCode)
}

# Says in clear that an accepted exit code was the partial result, never silently.
function Write-MinosPartialResultWarning {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]] $MinosArguments,
        [Parameter(Mandatory = $true)][int] $ExitCode
    )
    $spec = Get-MinosPartialResultSpec
    if ($ExitCode -ne [int] $spec.partialResultExitCode) { return }
    Write-Warning ("MINOS exited $ExitCode (partial result) for '$($MinosArguments -join ' ')': some registry entries are " +
        'unreadable and were counted, not used. The output is valid for the entries that could be read; ' +
        'run `minos project list` to see the unreadable ones.')
}
