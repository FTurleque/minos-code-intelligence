package com.minos.integration.nexus;

import com.minos.cli.MinosLauncher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Minimal process bridge used by NEXUS M13.
 *
 * <p>The project root is read from standard input instead of being placed on the
 * operating-system command line. The regular MINOS launcher remains responsible
 * for home resolution, CLI error codes and JSON/stdout discipline.</p>
 */
public final class NexusExportBridgeMain {

    private NexusExportBridgeMain() {
    }

    public static void main(String[] arguments) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String projectRoot = reader.readLine();
            if (projectRoot == null || projectRoot.isBlank()) {
                System.err.println("error: project root is required on stdin");
                System.exit(2);
                return;
            }
            MinosLauncher.main(new String[]{"nexus-export", "--root", projectRoot});
        }
    }
}
