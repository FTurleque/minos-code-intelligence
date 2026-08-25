package com.minos.adapter.scip.runtime;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;

/**
 * Build-time entry point for {@code docker/Dockerfile.mcp.release}.
 *
 * <p>The Docker image installs scip-dotnet/scip-go directly via {@code dotnet tool install}/
 * {@code go install} rather than through {@link ManagedPolyglotScipRuntimeManager}'s own
 * install path -- {@code minos.jar} is not even copied into the image until after those
 * (expensive, cacheable) provider layers, so the manager's Java code cannot run at that point.
 * Without the {@code .minos-install-source}/{@code .minos-integrity.sha256} markers {@link
 * ManagedPolyglotScipRuntimeManager#writeManagedMarkers} would have written, {@code inspect()}
 * reports these providers {@code INVALID} on every Docker install, forever -- not because the
 * install is untrusted, but because nothing ever stamped it as MINOS-managed.
 *
 * <p>This runs as its own {@code RUN java -cp minos.jar ...} step once the jar is available,
 * reusing the exact same marker-writing/digest logic the native install path uses -- not a
 * reimplementation in shell, which would risk a subtly different digest.
 */
public final class StampManagedProviderMarkers {

    private static final Logger LOG = System.getLogger(StampManagedProviderMarkers.class.getName());

    private StampManagedProviderMarkers() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3) {
            LOG.log(Level.ERROR, "usage: StampManagedProviderMarkers <directory> <version> <sourceId>");
            System.exit(2);
            return;
        }
        ManagedPolyglotScipRuntimeManager.writeManagedMarkers(Path.of(arguments[0]), arguments[1], arguments[2]);
    }
}
