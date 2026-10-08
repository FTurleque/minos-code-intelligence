package com.minos.runtime.local;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-H22 : une classe de tests qui démarre le vrai bac à sable AppContainer ne doit laisser aucune entrée
 * {@code S-1-15-2-…} sur le JDK qu'elle a accordé en lecture. Le JDK est relevé avant la classe et après : toute
 * entrée explicite apparue entre-temps est une fuite (incident H23 : 460 entrées accumulées par des tests tués).
 */
final class AppContainerGrantLeakCheck implements BeforeAllCallback, AfterAllCallback {
    private static final Pattern APPCONTAINER_ENTRY = Pattern.compile("(S-1-15-2(?:-\\d+)+):\\(([^)]*)\\)");
    private static final String BASELINE = "appcontainer-grants-before";

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        if (!windows()) return;
        context.getStore(ExtensionContext.Namespace.GLOBAL).put(BASELINE, explicitEntries(jdk()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterAll(ExtensionContext context) throws Exception {
        if (!windows()) return;
        Set<String> before = (Set<String>) context.getStore(ExtensionContext.Namespace.GLOBAL).get(BASELINE);
        Set<String> leaked = explicitEntries(jdk());
        leaked.removeAll(before);
        assertTrue(leaked.isEmpty(), "AppContainer grants left on the JDK by " + context.getDisplayName() + ": " + leaked);
    }

    /** Explicit (non-inherited) AppContainer entries under {@code root}, as {@code path|SID}. */
    static Set<String> explicitEntries(Path root) throws IOException, InterruptedException {
        Process icacls = new ProcessBuilder("icacls.exe", root.toString(), "/t", "/c", "/q")
                .redirectErrorStream(true)
                .start();
        String output = new String(icacls.getInputStream().readAllBytes(), Charset.defaultCharset());
        if (!icacls.waitFor(5, TimeUnit.MINUTES)) {
            icacls.destroyForcibly();
            throw new IOException("icacls did not finish on " + root);
        }
        Set<String> entries = new LinkedHashSet<>();
        String path = "";
        for (String line : output.split("\\R")) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                int space = line.indexOf(' ', line.indexOf(':') + 2);
                path = space < 0 ? line : line.substring(0, space);
            }
            Matcher entry = APPCONTAINER_ENTRY.matcher(line);
            while (entry.find()) {
                if (!entry.group(2).equals("I")) entries.add(path + "|" + entry.group(1));
            }
        }
        return entries;
    }

    private static Path jdk() {
        return Path.of(System.getProperty("java.home")).toAbsolutePath().normalize();
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
