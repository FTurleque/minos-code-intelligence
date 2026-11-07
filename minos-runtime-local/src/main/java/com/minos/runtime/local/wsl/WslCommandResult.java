package com.minos.runtime.local.wsl;

import java.util.Arrays;
import java.util.Objects;

/**
 * Exit code and raw standard output of one {@code wsl.exe} command.
 *
 * @param exitCode exit code of the process
 * @param stdout   raw bytes written on standard output (UTF-16LE for {@code wsl.exe} itself)
 */
public record WslCommandResult(int exitCode, byte[] stdout) {

    public WslCommandResult {
        Objects.requireNonNull(stdout, "stdout");
        stdout = stdout.clone();
    }

    @Override
    public byte[] stdout() {
        return stdout.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WslCommandResult that && exitCode == that.exitCode
                && Arrays.equals(stdout, that.stdout);
    }

    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(exitCode) + Arrays.hashCode(stdout);
    }

    @Override
    public String toString() {
        return "WslCommandResult[exitCode=" + exitCode + ", stdoutBytes=" + stdout.length + "]";
    }
}
