package com.minos.runtime.local.workspace;

import com.minos.orchestration.ExecutionPathIdentityProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Failure text for a test whose execution roots could not be canonically authorized.
 *
 * <p>The production journal names only the exception class. A test that hits the refusal asks
 * every identity provider again and reports its answer, then counts the processes visible on the
 * host: on Windows the helper has been seen to exit at once with no output, which points at a host
 * resource rather than at the roots, and the census is what tells the two apart.</p>
 */
final class ExecutionPathIdentityDiagnostics {

    private ExecutionPathIdentityDiagnostics() {
    }

    static String describe(Path projectRoot) {
        return providerAnswers(projectRoot) + "; " + processCensus();
    }

    private static String providerAnswers(Path projectRoot) {
        List<String> answers = new ArrayList<>();
        for (ExecutionPathIdentityProvider provider : ServiceLoader.load(
                ExecutionPathIdentityProvider.class, ExecutionPathIdentityProvider.class.getClassLoader())) {
            String name = provider.getClass().getSimpleName();
            try {
                Path real = projectRoot.toRealPath();
                answers.add(name + (provider.capture(real, real).isPresent()
                        ? " captured both roots when asked again"
                        : " declined this platform"));
            } catch (IOException | RuntimeException failure) {
                answers.add(name + " failed again: " + failure.getClass().getSimpleName()
                        + ": " + failure.getMessage());
            }
        }
        return answers.isEmpty() ? "no identity provider is registered" : String.join("; ", answers);
    }

    private static String processCensus() {
        int visible = 0;
        int powershell = 0;
        int conhost = 0;
        for (ProcessHandle process : ProcessHandle.allProcesses().toList()) {
            visible++;
            String command = process.info().command().orElse("").toLowerCase(Locale.ROOT);
            if (command.endsWith("powershell.exe")) powershell++;
            if (command.endsWith("conhost.exe")) conhost++;
        }
        long descendants = ProcessHandle.current().descendants().count();
        return "host processes visible: " + visible + " (powershell.exe " + powershell
                + ", conhost.exe " + conhost + "), live descendants of this JVM: " + descendants;
    }
}
