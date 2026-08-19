# Inno Setup compiler provenance helpers.
#
# Dot-source this file. It defines functions only and performs no side effects, so the parsing and
# fail-closed rules below can be exercised directly by scripts/release/test-iscc-provenance.ps1
# without a Windows toolchain, an Inno Setup installation or a built distribution tree.

Set-StrictMode -Version Latest

function Get-IsccEngineVersion {
    <#
    .SYNOPSIS
        Extracts the engine version ISCC.exe reported while compiling.
    .DESCRIPTION
        When ISCC.exe actually compiles a script it prints exactly one line of the form

            Compiler engine version: Inno Setup 6.7.1

        That line is the only version signal that comes from the binary that just ran: ISCC.exe
        leaves its PE FileVersion/ProductVersion resources unset (both report 0.0.0.0) and its
        bare-invocation usage banner names only the major version. Chocolatey package metadata
        proves what was installed, not what executed, so it cannot stand in for this.

        Fails closed: a missing line, an unparseable version, or several mutually inconsistent
        versions are all errors rather than a silently accepted build.
    #>
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]   # real compiler output contains blank separator lines
        [string[]] $CompilerOutput
    )

    # An optional parenthesised build marker is tolerated because Inno Setup has historically
    # appended one (for example "(u)" for Unicode builds). The version itself stays strictly
    # anchored, and callers compare it exactly, so tolerating that suffix cannot widen what counts
    # as a matching version.
    $Pattern = '^\s*Compiler engine version:\s*Inno Setup\s+(?<version>\d+(?:\.\d+)+)\s*(?:\([^)]*\))?\s*$'
    $Reported = @(
        foreach ($Line in $CompilerOutput) {
            $Match = [regex]::Match([string] $Line, $Pattern)
            if ($Match.Success) { $Match.Groups['version'].Value }
        }
    )
    $Distinct = @($Reported | Select-Object -Unique)

    if ($Distinct.Count -eq 0) {
        throw "Inno Setup compiler output contains no parseable 'Compiler engine version: Inno Setup <x.y.z>' line; the version of the compiler that produced this setup cannot be proven."
    }
    if ($Distinct.Count -gt 1) {
        throw "Inno Setup compiler output reports inconsistent engine versions ($($Distinct -join ', ')); refusing to guess which compiler produced this setup."
    }
    return $Distinct[0]
}

function Assert-IsccEngineVersion {
    <#
    .SYNOPSIS
        Fails unless the compiler that just ran reported exactly $RequiredVersion.
    .DESCRIPTION
        Exact string comparison on purpose: a prefix or substring match would accept 6.7.10 for a
        pinned 6.7.1, which is precisely the silent-substitution class of defect this guards.
        Returns the asserted version so callers can record it as build evidence.
    #>
    [OutputType([string])]
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [AllowEmptyString()]   # real compiler output contains blank separator lines
        [string[]] $CompilerOutput,

        [Parameter(Mandatory = $true)]
        [ValidateNotNullOrEmpty()]
        [string] $RequiredVersion,

        [string] $IsccPath = ''
    )

    $Actual = Get-IsccEngineVersion -CompilerOutput $CompilerOutput
    if ($Actual -ne $RequiredVersion) {
        $Where = if ([string]::IsNullOrWhiteSpace($IsccPath)) { 'The Inno Setup compiler' } else { "Inno Setup compiler '$IsccPath'" }
        throw "$Where reported engine version '$Actual' but the qualified release requires exactly '$RequiredVersion'. Refusing to keep a setup built by an unqualified compiler."
    }
    return $Actual
}
