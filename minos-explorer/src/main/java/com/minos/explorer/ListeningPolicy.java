package com.minos.explorer;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Decides which addresses the surface listens on. There is one answer for a person at a workstation: the
 * loopback, and no option widens it. A port published by a container engine reaches the process through the
 * interface of the container, never through its loopback, so the Docker runtime sets an internal variable that
 * makes the surface listen on that interface; it is refused wherever the process is not in a container.
 */
final class ListeningPolicy {

    /** Internal variable set by the Compose service of the Docker runtime; never a command-line option. */
    static final String CONTAINER_LISTEN_ENV = "MINOS_EXPLORER_CONTAINER_LISTEN";
    static final String RUNTIME_LOCATION_ENV = "MINOS_RUNTIME_LOCATION";

    static final String REFUSED_OUTSIDE_CONTAINER =
            CONTAINER_LISTEN_ENV + " is reserved to the MINOS Docker runtime and this process does not run in a"
                    + " container: unset it; the exploration surface listens on the loopback only";
    static final String INVALID_VALUE =
            CONTAINER_LISTEN_ENV + " accepts no other value than enabled; unset it to listen on the loopback";

    private static final String ENABLED = "enabled";
    private static final List<Path> CONTAINER_MARKERS = List.of(Path.of("/.dockerenv"), Path.of("/run/.containerenv"));

    private ListeningPolicy() {
    }

    /** Whether the surface listens on the loopback or on the interface of its container. */
    enum Scope { LOOPBACK, CONTAINER_INTERFACE }

    /** The scope for this process, read from its real environment and its real file system. */
    static Scope resolve(Map<String, String> environment) {
        return resolve(environment, ListeningPolicy::runsInContainer);
    }

    /**
     * The scope for {@code environment}. Without the internal variable it is the loopback. With it, the
     * container interface is granted only when the runtime location says Docker and {@code inContainer}
     * confirms it; anything else is refused, never downgraded in silence.
     *
     * @throws IllegalArgumentException when the variable is set outside a container, or to an unknown value
     */
    static Scope resolve(Map<String, String> environment, BooleanSupplier inContainer) {
        String requested = environment.get(CONTAINER_LISTEN_ENV);
        if (requested == null || requested.isBlank()) return Scope.LOOPBACK;
        if (!ENABLED.equals(requested.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(INVALID_VALUE);
        }
        String location = environment.get(RUNTIME_LOCATION_ENV);
        boolean docker = location != null && "docker".equals(location.trim().toLowerCase(Locale.ROOT));
        if (!docker || !inContainer.getAsBoolean()) {
            throw new IllegalArgumentException(REFUSED_OUTSIDE_CONTAINER);
        }
        return Scope.CONTAINER_INTERFACE;
    }

    /** The addresses to bind for {@code scope}; the IPv6 loopback is optional, the IPv4 one is not. */
    static List<InetAddress> addresses(Scope scope) {
        try {
            if (scope == Scope.CONTAINER_INTERFACE) {
                return List.of(InetAddress.getByAddress(new byte[4]));
            }
            byte[] ipv6Loopback = new byte[16];
            ipv6Loopback[15] = 1;
            return List.of(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), InetAddress.getByAddress(ipv6Loopback));
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("a literal address always resolves", impossible);
        }
    }

    private static boolean runsInContainer() {
        return runsInContainer(CONTAINER_MARKERS, System.getProperty("os.name", ""));
    }

    /** A Linux process whose root holds a marker file written by the container engine. */
    static boolean runsInContainer(List<Path> markers, String osName) {
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) return false;
        for (Path marker : markers) {
            if (Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return true;
        }
        return false;
    }
}
