package com.minos.runtime.local;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Who this process is, as the operating system says it, never as a name the command line can set.
 *
 * <p>{@code user.name} and the environment are inputs: {@code -Duser.name=*S-1-1-0} is accepted by
 * {@code icacls} as readily as a real account. The SID of the token of the process is read from
 * {@code whoami /user} (elevated or not), once per JVM.</p>
 */
final class ProcessIdentity {

    private static final Pattern SECURITY_IDENTIFIER = Pattern.compile("S-1-[0-9]+(?:-[0-9]+){1,14}");
    private static volatile String sid;

    private ProcessIdentity() {
    }

    /** The SID of the user of the token of this process. */
    static String sid() {
        String known = sid;
        if (known != null) return known;
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) throw new IOException("Windows directory unknown");
            Process process = new ProcessBuilder(
                    Path.of(systemRoot, "System32", "whoami.exe").toString(), "/user", "/fo", "csv", "/nh")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readNBytes(4096), StandardCharsets.ISO_8859_1).trim();
            if (process.waitFor() == 0) {
                Matcher matcher = SECURITY_IDENTIFIER.matcher(output.substring(output.lastIndexOf(',') + 1));
                if (matcher.find()) {
                    sid = matcher.group();
                    return sid;
                }
            }
        } catch (IOException failure) {
            // Reported below, without the cause: it may carry a path.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        throw new IllegalStateException("cannot determine the identity of the current process");
    }
}
