package com.minos.runtime.local.acl;

import com.minos.io.PlatformAccessControl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Windows {@link PlatformAccessControl} of private storage, registered in {@code META-INF/services} and
 * discovered by {@code PrivateLocalStorage} (ADR 0068, AUD-ARC-05). On any other system it has nothing to do.
 *
 * <p><b>Inheritance.</b> Java writes a DACL but cannot mark it protected, so an inheritable grant added later to
 * a parent would flow back into the location (measured: {@code icacls <parent> /grant Everyone:(OI)(CI)R}
 * reached every hardened child). {@code icacls /inheritance:d} is the smallest reliable way to set that bit. It is
 * {@code :d}, not {@code :r}: a file just created in a private directory holds nothing but an inherited owner
 * entry, which Java reads back as equal to the expected one; {@code :r} would drop it and leave an empty DACL
 * (measured), {@code :d} turns it into an explicit entry.</p>
 *
 * <p><b>Conditional deny.</b> An object whose DACL holds a conditional DENY entry, or whose DACL cannot be read,
 * is refused: the text is the one {@code icacls /save} writes, searched for an XD entry without being parsed
 * further. Any failure to obtain the text fails the call, never lets it through.</p>
 */
public final class WindowsIcaclsAccessControl implements PlatformAccessControl {

    private static final String PROTECT_REFUSAL = "cannot protect private storage from inheritance";
    private static final String READ_REFUSAL = "cannot read the access rules of private storage";

    private final WindowsIcacls icacls;
    private final Supplier<List<String>> saveDirectories;
    private final BooleanSupplier windows;

    /** The implementation {@code ServiceLoader} instantiates: the real {@code icacls}, the account's directories. */
    public WindowsIcaclsAccessControl() {
        this(WindowsIcacls.system(),
                () -> Arrays.asList(System.getenv("TEMP"), System.getenv("TMP"), System.getProperty("user.home")),
                () -> System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"));
    }

    WindowsIcaclsAccessControl(WindowsIcacls icacls, Supplier<List<String>> saveDirectories, BooleanSupplier windows) {
        this.icacls = Objects.requireNonNull(icacls, "icacls");
        this.saveDirectories = Objects.requireNonNull(saveDirectories, "saveDirectories");
        this.windows = Objects.requireNonNull(windows, "windows");
    }

    @Override
    public void protectFromInheritance(Path target) throws IOException {
        if (!windows.getAsBoolean()) return;
        icacls.run(target, List.of("/inheritance:d", "/L", "/q"), PROTECT_REFUSAL);
    }

    @Override
    public void requireNoConditionalDeny(Path target) throws IOException {
        if (!windows.getAsBoolean()) return;
        icacls.requireUsable(target, READ_REFUSAL);
        Path saved = createSddlFile(saveDirectories.get());
        try {
            icacls.run(target, List.of("/save", saved.toString(), "/L", "/q"), READ_REFUSAL);
            String text = new String(readSaved(saved), StandardCharsets.UTF_16LE);
            String byteOrderMark = String.valueOf((char) 0xFEFF);
            if (text.lines().map(line -> line.replace(byteOrderMark, "")).noneMatch(line -> line.startsWith("D:"))) {
                throw new IOException(READ_REFUSAL + ": no DACL in the answer");
            }
            if (text.contains("(XD;")) {
                throw new IOException("a conditional deny entry on private storage prevents MINOS from making it "
                        + "private; an administrator must remove that restriction, or MINOS_HOME must be moved");
            }
        } finally {
            deleteSaved(saved);
        }
    }

    /** The saved text; a failure to read it fails the call with a message that names no path. */
    private static byte[] readSaved(Path saved) throws IOException {
        try {
            return Files.readAllBytes(saved);
        } catch (IOException _) {
            throw new IOException(READ_REFUSAL + ": the saved rules cannot be read");
        }
    }

    /**
     * Deletes the saved text without hiding the outcome of the read: a file that cannot be deleted (held by another
     * process) leaves only a copy of a DACL in the account's own temporary directory.
     */
    private static void deleteSaved(Path saved) {
        try {
            Files.deleteIfExists(saved);
        } catch (IOException _) {
            // Best effort: the refusal or the success of the read stands.
        }
    }

    /**
     * The file {@code icacls /save} writes: in the first usable directory of {@code candidates} -- in practice the
     * temporary directory of the current Windows account ({@code TEMP}, then {@code TMP}), else the profile of the
     * account ({@code user.home}) -- never in a directory shared by all. A process started with a sanitized
     * environment has neither variable. Beside or inside the object itself does not work: its parent may deny the
     * write (the case this class exists for) and {@code icacls} refuses a save file inside the tree it saves
     * (measured: exit 87). It cannot be created through {@code PrivateLocalStorage}, which would call this port
     * again. Nothing is kept: the caller deletes it.
     */
    private static Path createSddlFile(List<String> candidates) throws IOException {
        for (String value : candidates) {
            if (value == null || value.isBlank()) continue;
            Path directory = Path.of(value);
            if (directory.isAbsolute() && Files.isDirectory(directory)) {
                try {
                    return Files.createTempFile(directory, "minos-dacl-", ".sddl");
                } catch (IOException _) {
                    throw new IOException(READ_REFUSAL + ": no place to save them");
                }
            }
        }
        throw new IOException(READ_REFUSAL + ": no place to save them");
    }
}
