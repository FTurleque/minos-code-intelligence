package com.minos.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Assembles a Windows containment launcher from its template and shared fragments.
 *
 * <p>The Job Object launcher and the AppContainer sandbox launcher are different programs, but they
 * drive the same Win32 surface: the same {@code STARTUPINFO}/{@code JOBOBJECT} layouts, the same
 * {@code kernel32} signatures, the same suspended-create/assign/verify/resume sequence, the same
 * command-line quoting and the same plan-file parser. Keeping two verbatim copies of that surface
 * invited exactly the drift that a containment boundary cannot afford -- a struct corrected in one
 * launcher and not the other is a silent breach, not a compile error.</p>
 *
 * <p>Each shared region lives once under {@code windows-fragments/} and each launcher is a template
 * that names the fragments it needs. Assembly happens before the script is written to disk, so the
 * launcher that actually executes is still a single self-contained file with a single hash: nothing
 * is dot-sourced at run time and there is no include path an attacker could redirect.</p>
 */
final class WindowsContainmentScript {

    private static final String RESOURCE_ROOT = "/com/minos/runtime/";
    private static final String FRAGMENT_ROOT = RESOURCE_ROOT + "windows-fragments/";
    private static final String INCLUDE_PREFIX = "#minos-include:";
    private static final String LINE_SEPARATOR = "\r\n";
    private static final int MAX_FRAGMENTS = 64;

    private WindowsContainmentScript() {
    }

    /**
     * Reads {@code <name>.ps1.template} and substitutes every include directive with its fragment.
     *
     * @throws IOException if the template or any named fragment is missing, or if a fragment is
     *                     requested twice, which would mean a malformed template rather than a
     *                     legitimate launcher
     */
    static String assemble(String launcherName) throws IOException {
        String name = Objects.requireNonNull(launcherName, "launcherName");
        String template = readResource(RESOURCE_ROOT + name + ".template");
        Set<String> resolved = new LinkedHashSet<>();
        StringBuilder assembled = new StringBuilder(template.length());
        String[] lines = template.split("\r\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) assembled.append(LINE_SEPARATOR);
            String line = lines[index];
            if (!line.startsWith(INCLUDE_PREFIX)) {
                assembled.append(line);
                continue;
            }
            String fragment = line.substring(INCLUDE_PREFIX.length()).trim();
            requireSafeFragmentName(fragment);
            if (!resolved.add(fragment)) {
                throw new IOException("Windows containment template includes a fragment twice: " + fragment);
            }
            if (resolved.size() > MAX_FRAGMENTS) {
                throw new IOException("Windows containment template exceeds its fragment budget");
            }
            assembled.append(readResource(FRAGMENT_ROOT + fragment + ".ps1frag"));
        }
        return assembled.toString();
    }

    /**
     * Fragment names come from packaged templates, never from user input, but the check is kept so a
     * future template edit cannot turn an include into a path traversal out of the resource root.
     */
    private static void requireSafeFragmentName(String fragment) throws IOException {
        if (fragment.isEmpty() || !fragment.matches("[a-z0-9-]+")) {
            throw new IOException("invalid Windows containment fragment name: " + fragment);
        }
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream input = WindowsContainmentScript.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("packaged Windows containment resource is missing: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
