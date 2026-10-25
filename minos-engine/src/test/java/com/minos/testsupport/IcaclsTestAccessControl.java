package com.minos.testsupport;

import com.minos.io.PlatformAccessControl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Test double of the {@link PlatformAccessControl} port for the modules that cannot see minos-runtime-local
 * (minos-engine, the two storage adapters, minos-application, minos-integration-git): their Windows tests need a
 * DACL protected from inheritance, or the privacy check that follows the hardening refuses.
 *
 * <p>It does what the production implementation ({@code com.minos.runtime.local.acl.WindowsIcaclsAccessControl})
 * does, with {@code icacls}, and a parity test of minos-runtime-local compares the two DACLs on Windows. Each of
 * those modules registers it in {@code src/test/platform-access-control/META-INF/services}, a directory added to
 * the Surefire classpath of that module only. It must not move to {@code src/test/resources}: the test classes and
 * the test-jar would then carry the registration, and a module that also sees the local runtime would find two
 * implementations and refuse every private directory.</p>
 */
public final class IcaclsTestAccessControl implements PlatformAccessControl {

    private static final long TIMEOUT_SECONDS = 10L;

    public IcaclsTestAccessControl() {
        // ServiceLoader needs a public constructor without arguments.
    }

    @Override
    public void protectFromInheritance(Path target) throws IOException {
        if (!windows()) return;
        icacls(target, List.of("/inheritance:d", "/L", "/q"), "cannot protect private storage from inheritance");
    }

    @Override
    public void requireNoConditionalDeny(Path target) throws IOException {
        if (!windows()) return;
        Path saved = Files.createTempFile(Path.of(System.getProperty("java.io.tmpdir")), "minos-dacl-test-", ".sddl");
        try {
            icacls(target, List.of("/save", saved.toString(), "/L", "/q"), "cannot read the access rules of private storage");
            String text = new String(Files.readAllBytes(saved), StandardCharsets.UTF_16LE);
            String byteOrderMark = String.valueOf((char) 0xFEFF);
            if (text.lines().map(line -> line.replace(byteOrderMark, "")).noneMatch(line -> line.startsWith("D:"))) {
                throw new IOException("cannot read the access rules of private storage: no DACL in the answer");
            }
            if (text.contains("(XD;")) {
                throw new IOException("a conditional deny entry on private storage prevents MINOS from making it "
                        + "private; an administrator must remove that restriction, or MINOS_HOME must be moved");
            }
        } finally {
            Files.deleteIfExists(saved);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void icacls(Path target, List<String> arguments, String refusal) throws IOException {
        String path = target.toString();
        if (!target.isAbsolute() || path.chars().anyMatch(c -> c < 0x20 || "*?\"<>|".indexOf(c) >= 0)) {
            throw new IOException(refusal + ": unsupported path");
        }
        for (Path element : target) {
            String name = element.toString();
            if (name.endsWith(".") || name.endsWith(" ")
                    || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?")) {
                throw new IOException(refusal + ": unsupported path");
            }
        }
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) throw new IOException(refusal + ": Windows directory unknown");
        List<String> command = new ArrayList<>();
        command.add(Path.of(systemRoot, "System32", "icacls.exe").toString());
        command.add(path.startsWith("\\\\") ? "\\\\?\\UNC\\" + path.substring(2) : "\\\\?\\" + path);
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(name -> !name.equalsIgnoreCase("SystemRoot"));
        Process process = builder.start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(refusal + ": timed out");
            }
        } catch (InterruptedException _) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(refusal + ": interrupted");
        }
        if (process.exitValue() != 0) throw new IOException(refusal + " (exit " + process.exitValue() + ")");
    }
}
