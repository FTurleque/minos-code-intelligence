package com.minos.runtime.local.wsl;

import java.io.IOException;

/** A {@code wsl.exe} command that did not finish within its timeout; its process tree was destroyed. */
public final class WslCommandTimeoutException extends IOException {

    public WslCommandTimeoutException(String message) {
        super(message);
    }
}
