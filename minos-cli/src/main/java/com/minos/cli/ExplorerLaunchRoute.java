package com.minos.cli;

import java.nio.file.Path;

/**
 * Entry point of {@code minos explorer serve}, provided by the final assembly ({@code minos-app}) and discovered
 * by {@code ServiceLoader}.
 *
 * <p>{@link MinosLauncher} lives in {@code minos-cli}, which depends on no other surface: the exploration
 * surface ({@code minos-explorer}) is reached through this interface, registered in
 * {@code META-INF/services/com.minos.cli.ExplorerLaunchRoute}. The launcher calls it before any
 * {@code MinosApplication} is opened, with the MINOS home resolved but not opened.</p>
 */
public interface ExplorerLaunchRoute {

    /**
     * Serves the exploration surface of this home until the process is asked to stop.
     *
     * @param home  resolved MINOS home, not opened yet
     * @param port  port to listen on, or 0 for a free port chosen by the system
     * @param error where the address of the surface and its diagnostics are written
     * @return the exit code of the process
     * @throws Exception any failure, reported by the launcher without a path
     */
    int run(Path home, int port, Appendable error) throws Exception;
}
