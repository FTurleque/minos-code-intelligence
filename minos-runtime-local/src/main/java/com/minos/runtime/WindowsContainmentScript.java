package com.minos.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final int MAX_FRAGMENTS = 64;

    /** Matches the directive line only; the surrounding terminators stay in the template. */
    private static final Pattern INCLUDE_DIRECTIVE =
            Pattern.compile("^" + Pattern.quote(INCLUDE_PREFIX) + "([a-z0-9-]+)[ \\t]*$", Pattern.MULTILINE);

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
        // Only the directive itself is replaced; every other byte of the template is carried through
        // untouched, including its line terminators. Assembly therefore stays byte-exact whichever
        // end-of-line convention the working tree was checked out with.
        Matcher directive = INCLUDE_DIRECTIVE.matcher(template);
        StringBuilder assembled = new StringBuilder(template.length());
        while (directive.find()) {
            String fragment = directive.group(1);
            if (!resolved.add(fragment)) {
                throw new IOException("Windows containment template includes a fragment twice: " + fragment);
            }
            if (resolved.size() > MAX_FRAGMENTS) {
                throw new IOException("Windows containment template exceeds its fragment budget");
            }
            directive.appendReplacement(assembled,
                    Matcher.quoteReplacement(readResource(FRAGMENT_ROOT + fragment + ".ps1frag")));
        }
        directive.appendTail(assembled);
        if (assembled.indexOf(INCLUDE_PREFIX) >= 0) {
            throw new IOException("Windows containment template carries a malformed include directive: " + name);
        }
        return assembled.toString();
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
