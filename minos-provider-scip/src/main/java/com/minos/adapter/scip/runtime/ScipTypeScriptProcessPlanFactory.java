package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plan d'exécution du provider scip-typescript. */
public final class ScipTypeScriptProcessPlanFactory extends AbstractScipProcessPlanFactory {

    private final Path mainScript;

    public ScipTypeScriptProcessPlanFactory(Path executable) {
        this(executable, null);
    }

    /**
     * On Windows, {@code executable} is a MINOS-managed Node.js runtime and {@code mainScript} is
     * scip-typescript's own entry point, invoked directly instead of through its npm-generated
     * {@code .cmd} shim. The shim resolves {@code node} via PATH internally, a location the
     * AppContainer sandbox's static command-line ACL computation can never discover; invoking the
     * managed interpreter directly makes both it and the script ordinary, grantable arguments.
     */
    public ScipTypeScriptProcessPlanFactory(Path executable, Path mainScript) {
        super(executable, "scip-typescript", "scip-typescript incremental execution is not qualified");
        this.mainScript = mainScript;
    }

    @Override
    protected void validateProject(Path root) {
        if (!Files.isRegularFile(root.resolve("tsconfig.json"))
                && !Files.isRegularFile(root.resolve("package.json"))) {
            throw new IllegalArgumentException("scip-typescript requires tsconfig.json or package.json: " + root);
        }
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) throws IOException {
        List<String> arguments = new ArrayList<>();
        if (mainScript != null) {
            // Node's default module resolution calls realpath() on both its entry script and every
            // module it require()s, walking every ancestor directory up to and including the drive
            // root (e.g. C:\). An AppContainer token lacks the "bypass traverse checking" privilege a
            // normal user token has, and granting an ACE on a drive root itself needs WRITE_DAC that
            // only the drive's owner (Administrators/SYSTEM) holds -- exactly the elevation this
            // sandbox must never require. --preserve-symlinks(-main) skips that realpath walk for the
            // main module and every subsequent require(), so the interpreter never needs to touch
            // anything above the paths it was actually granted.
            arguments.add("--preserve-symlinks");
            arguments.add("--preserve-symlinks-main");
            arguments.add(mainScript.toString());
        }
        arguments.add("index");
        arguments.add("--output");
        arguments.add(output.toString());
        if (!Files.isRegularFile(root.resolve("tsconfig.json")) && Files.isRegularFile(root.resolve("package.json"))) {
            arguments.add("--infer-tsconfig");
        }
        return CommandLocator.invocation(executable(), arguments.toArray(String[]::new));
    }
}
