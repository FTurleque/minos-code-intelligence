#!/usr/bin/env pwsh
# Behavioural tests for the Inno Setup compiler provenance rules.
#
# These exercise the actual parsing/assertion code used by build-windows-installer.ps1 against
# synthetic compiler output, so the negative cases (wrong version, missing line, inconsistent
# lines) are proven rather than merely asserted by a static gate. Runs on any platform with
# PowerShell; it needs neither Inno Setup nor a built distribution.

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

. (Join-Path $PSScriptRoot 'iscc-provenance.ps1')

$script:Failures = @()

function Test-Case([string] $Name, [scriptblock] $Body) {
    try {
        & $Body
        Write-Host "  PASS  $Name"
    }
    catch {
        $script:Failures += "$Name -- $($_.Exception.Message)"
        Write-Host "  FAIL  $Name -- $($_.Exception.Message)"
    }
}

function Assert-Equal([string] $Expected, [string] $Actual, [string] $Because) {
    if ($Expected -ne $Actual) { throw "expected '$Expected' but got '$Actual' ($Because)" }
}

function Assert-Throws([scriptblock] $Body, [string] $ExpectedSubstring) {
    try { & $Body }
    catch {
        if ($_.Exception.Message -notlike "*$ExpectedSubstring*") {
            throw "threw, but message did not contain '$ExpectedSubstring': $($_.Exception.Message)"
        }
        return
    }
    throw "expected a terminating error containing '$ExpectedSubstring', but none was thrown"
}

# Representative of what ISCC.exe actually prints around a successful compile.
$RealisticOutput = @(
    'Inno Setup 6 Command-Line Compiler',
    'Copyright (C) 1997-2026 Jordan Russell. All rights reserved.',
    '',
    'Compiler engine version: Inno Setup 6.7.1',
    '',
    '[Setup]',
    'Compiling...',
    'Successful compile (3.140 sec)'
)

Write-Host 'Inno Setup provenance behavioural tests'

Test-Case 'parses the engine version from realistic compiler output' {
    Assert-Equal '6.7.1' (Get-IsccEngineVersion -CompilerOutput $RealisticOutput) 'engine version line'
}

Test-Case 'tolerates surrounding whitespace' {
    Assert-Equal '6.7.1' (Get-IsccEngineVersion -CompilerOutput @('   Compiler engine version:  Inno Setup   6.7.1  ')) 'padded line'
}

Test-Case 'tolerates a parenthesised build marker after the version' {
    Assert-Equal '6.7.1' (Get-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 6.7.1 (u)')) 'unicode build marker'
}

Test-Case 'a build marker does not loosen the version comparison' {
    Assert-Throws {
        Assert-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 6.7.2 (u)') -RequiredVersion '6.7.1'
    } "requires exactly '6.7.1'"
}

Test-Case 'fails closed on trailing content that is not a build marker' {
    Assert-Throws { Get-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 6.7.1 and something else') } 'no parseable'
}

Test-Case 'fails closed when the engine version line is absent' {
    Assert-Throws { Get-IsccEngineVersion -CompilerOutput @('Successful compile (1.0 sec)') } 'no parseable'
}

Test-Case 'fails closed on empty compiler output' {
    Assert-Throws { Get-IsccEngineVersion -CompilerOutput @() } 'no parseable'
}

Test-Case 'fails closed when the version is not numeric' {
    Assert-Throws { Get-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup unstable') } 'no parseable'
}

Test-Case 'fails closed when the major version alone is reported' {
    # The bare-invocation banner names only "Inno Setup 6"; that must never satisfy a 6.7.1 pin.
    Assert-Throws { Get-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 6') } 'no parseable'
}

Test-Case 'fails closed on inconsistent engine versions' {
    Assert-Throws {
        Get-IsccEngineVersion -CompilerOutput @(
            'Compiler engine version: Inno Setup 6.7.1',
            'Compiler engine version: Inno Setup 7.0.0')
    } 'inconsistent'
}

Test-Case 'accepts a repeated but identical engine version' {
    $Repeated = @(
        'Compiler engine version: Inno Setup 6.7.1',
        'Compiling...',
        'Compiler engine version: Inno Setup 6.7.1')
    Assert-Equal '6.7.1' (Get-IsccEngineVersion -CompilerOutput $Repeated) 'repeated identical line'
}

Test-Case 'assert passes and returns the version on an exact match' {
    Assert-Equal '6.7.1' (Assert-IsccEngineVersion -CompilerOutput $RealisticOutput -RequiredVersion '6.7.1') 'exact match'
}

Test-Case 'assert rejects a different version' {
    Assert-Throws {
        Assert-IsccEngineVersion -CompilerOutput $RealisticOutput -RequiredVersion '6.7.2'
    } "requires exactly '6.7.2'"
}

Test-Case 'assert rejects a longer version sharing the required prefix' {
    # A substring/prefix match would wrongly accept 6.7.10 for a pinned 6.7.1.
    Assert-Throws {
        Assert-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 6.7.10') -RequiredVersion '6.7.1'
    } "requires exactly '6.7.1'"
}

Test-Case 'assert rejects a different major version' {
    Assert-Throws {
        Assert-IsccEngineVersion -CompilerOutput @('Compiler engine version: Inno Setup 7.0.0') -RequiredVersion '6.7.1'
    } "requires exactly '6.7.1'"
}

Test-Case 'assert names the offending compiler path when one is supplied' {
    Assert-Throws {
        Assert-IsccEngineVersion -CompilerOutput $RealisticOutput -RequiredVersion '6.7.2' -IsccPath 'C:\stray\ISCC.exe'
    } 'C:\stray\ISCC.exe'
}

if ($script:Failures.Count -gt 0) {
    Write-Host ''
    Write-Host "INNO SETUP PROVENANCE TESTS FAILED ($($script:Failures.Count))"
    foreach ($Failure in $script:Failures) { Write-Host " - $Failure" }
    exit 1
}

Write-Host ''
Write-Host 'INNO SETUP PROVENANCE TESTS SUCCESS'
