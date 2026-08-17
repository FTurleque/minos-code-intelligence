package com.minos.orchestration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strong filesystem-object identity used by the pre-launch anti-TOCTOU authorization. */
final class StableFileSystemIdentity {

    private static final int WINDOWS_OUTPUT_LIMIT = 4 * 1024;
    private static final long WINDOWS_QUERY_TIMEOUT_SECONDS = 3;
    private static final Pattern WINDOWS_HEX_IDENTIFIER =
            Pattern.compile("(?i)0x[0-9a-f]{8,32}");

    private StableFileSystemIdentity() {
    }

    /**
     * Captures a stable identity for an already-canonicalized filesystem object.
     *
     * <p>The Java provider key is preferred. Unix providers can additionally expose the device and
     * inode pair. Windows' Java provider may return a null file key, so on Windows the system
     * {@code fsutil.exe} is used to obtain the NTFS file id together with the volume serial number.
     * No creation-time/size/path fingerprint is accepted because those values are reproducible and
     * would not prove physical object identity.</p>
     */
    static Optional<String> capture(Path realPath) throws IOException {
        BasicFileAttributes basic = Files.readAttributes(
                realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        Object key = basic.fileKey();
        if (key != null) {
            return Optional.of("nio:" + key);
        }

        Optional<String> unix = unixIdentity(realPath);
        if (unix.isPresent()) {
            return unix;
        }
        if (isWindows()) {
            return windowsIdentity(realPath);
        }
        return Optional.empty();
    }

    private static Optional<String> unixIdentity(Path realPath) throws IOException {
        try {
            Map<String, Object> attributes = Files.readAttributes(
                    realPath, "unix:dev,ino", LinkOption.NOFOLLOW_LINKS);
            Object device = attributes.get("dev");
            Object inode = attributes.get("ino");
            if (device == null || inode == null) {
                return Optional.empty();
            }
            return Optional.of("unix:" + device + ':' + inode);
        } catch (UnsupportedOperationException | IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }

    private static Optional<String> windowsIdentity(Path realPath) throws IOException {
        Optional<Path> fsutil = windowsFsutil();
        Path root = realPath.getRoot();
        if (fsutil.isEmpty() || root == null) {
            return Optional.empty();
        }

        Optional<String> fileId = queryHexIdentifier(
                fsutil.orElseThrow(), List.of("file", "queryfileid", realPath.toString()));
        Optional<String> volumeSerial = queryHexIdentifier(
                fsutil.orElseThrow(), List.of("fsinfo", "volumeinfo", root.toString()));
        if (fileId.isEmpty() || volumeSerial.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("windows:" + volumeSerial.orElseThrow() + ':' + fileId.orElseThrow());
    }

    private static Optional<Path> windowsFsutil() {
        String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            systemRoot = System.getenv("WINDIR");
        }
        if (systemRoot == null || systemRoot.isBlank()) {
            return Optional.empty();
        }
        Path executable = Path.of(systemRoot).toAbsolutePath().normalize()
                .resolve("System32").resolve("fsutil.exe");
        return Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty();
    }

    private static Optional<String> queryHexIdentifier(Path fsutil, List<String> arguments) throws IOException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add(fsutil.toString());
        command.addAll(arguments);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        boolean completed;
        try {
            completed = process.waitFor(WINDOWS_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while capturing Windows filesystem identity", interrupted);
        }
        if (!completed) {
            process.destroyForcibly();
            try {
                process.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return Optional.empty();
        }

        byte[] output;
        try (InputStream stream = process.getInputStream()) {
            output = stream.readNBytes(WINDOWS_OUTPUT_LIMIT + 1);
        }
        if (process.exitValue() != 0 || output.length > WINDOWS_OUTPUT_LIMIT) {
            return Optional.empty();
        }
        Matcher matcher = WINDOWS_HEX_IDENTIFIER.matcher(new String(output, StandardCharsets.UTF_8));
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group().toLowerCase(Locale.ROOT));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
