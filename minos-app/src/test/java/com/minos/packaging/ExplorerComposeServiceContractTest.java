package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract of the optional {@code minos-explorer} service of the Docker runtime (ADR 0082): it is off unless its
 * profile is asked for, it mounts the data read-only, its port is published on the loopback of the host only,
 * and it is the one service allowed to listen on the interface of its container.
 */
class ExplorerComposeServiceContractTest {

    private static final String COMPOSE = "docker/compose-mcp.connected.yaml";
    private static final String SERVICE = "minos-explorer";
    private static final Pattern SERVICE_KEY = Pattern.compile("^ {2}([a-z][a-z0-9-]*):\\s*$");
    private static final Pattern PORT_ENTRY = Pattern.compile("^\\s+- \"?([^\"#]+)\"?\\s*$");

    @Test
    void theExplorerIsAnOptionalReadOnlyServiceOnTheQueryImage() throws IOException {
        String block = services(text(COMPOSE)).get(SERVICE);

        assertTrue(block != null, "docker/compose-mcp.connected.yaml declares no " + SERVICE + " service");
        assertTrue(block.contains("    profiles: [explorer]\n"), "the service starts with its profile only");
        assertTrue(block.contains("    <<: *limits-query\n"), "the service carries the ceilings of the query plane");
        assertTrue(block.contains("    image: \"${MINOS_QUERY_IMAGE:?MINOS_QUERY_IMAGE is required}\"\n"));
        assertTrue(block.contains("    entrypoint: [java, -cp, /opt/minos/minos.jar, com.minos.cli.MinosLauncher]\n"));
        assertTrue(block.contains("    command: [explorer, serve, --port, \"${MINOS_EXPLORER_PORT:-8765}\"]\n"));
        assertTrue(block.contains("    read_only: true\n"), "the root file system is read-only");
        assertTrue(block.contains("    cap_drop: [ALL]\n"));
        assertTrue(block.contains("    security_opt: [no-new-privileges:true]\n"));
        assertTrue(block.contains("    networks: [minos-runtime, minos-explorer-ingress]\n"));
        assertFalse(block.contains("privileged"), "no privilege");
        assertFalse(block.contains("docker.sock"), "no access to the Docker daemon");
        assertFalse(block.contains("MINOS_TEAM"), "no team secret reaches the surface");
        assertFalse(block.contains("MINOS_POSTGRES_PASSWORD"), "no database secret in the environment");
        List<String> mounts = new ArrayList<>();
        Matcher mount = Pattern.compile("(?m)^ {6}- type: (bind|volume)\\n(?: {8}.*\\n)*").matcher(block);
        while (mount.find()) mounts.add(mount.group());
        assertEquals(2, mounts.size(), "the data and the provider tools, nothing else");
        for (String entry : mounts) {
            assertTrue(entry.contains("        read_only: true\n"), "every mount is read-only: " + entry);
        }
    }

    @Test
    void thePortOfTheExplorerIsPublishedOnTheLoopbackOfTheHostOnly() throws IOException {
        String block = services(text(COMPOSE)).get(SERVICE);

        assertTrue(block != null, "docker/compose-mcp.connected.yaml declares no " + SERVICE + " service");
        assertEquals(List.of("127.0.0.1:${MINOS_EXPLORER_PORT:-8765}:${MINOS_EXPLORER_PORT:-8765}"), ports(block));
        assertFalse(block.contains("network_mode: host"), "the host network would bypass the loopback publication");
    }

    @Test
    void noComposeFilePublishesAPortElsewhereThanOnTheLoopback() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("docker"))) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("compose.*\\.ya?ml")).toList()) {
                String content = Files.readString(file).replace("\r\n", "\n");
                for (Map.Entry<String, String> service : services(content).entrySet()) {
                    for (String port : ports(service.getValue())) {
                        if (!port.startsWith("127.0.0.1:")) {
                            violations.add(file.getFileName() + " " + service.getKey() + ": " + port);
                        }
                    }
                    if (service.getValue().contains("network_mode: host")) {
                        violations.add(file.getFileName() + " " + service.getKey() + ": network_mode host");
                    }
                }
            }
        }
        assertEquals(List.of(), violations);
    }

    @Test
    void onlyTheExplorerServiceMayListenOnTheInterfaceOfItsContainer() throws IOException {
        List<String> requesting = new ArrayList<>();
        try (Stream<Path> files = Files.list(repoRoot().resolve("docker"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String content = Files.readString(file).replace("\r\n", "\n");
                if (!file.getFileName().toString().matches("compose.*\\.ya?ml")) {
                    if (content.contains("MINOS_EXPLORER_CONTAINER_LISTEN")) requesting.add(file.getFileName().toString());
                    continue;
                }
                for (Map.Entry<String, String> service : services(content).entrySet()) {
                    if (service.getValue().contains("MINOS_EXPLORER_CONTAINER_LISTEN")) {
                        requesting.add(file.getFileName() + ":" + service.getKey());
                    }
                }
            }
        }

        assertEquals(List.of("compose-mcp.connected.yaml:" + SERVICE), requesting);
        String block = services(text(COMPOSE)).get(SERVICE);
        assertTrue(block.contains("      MINOS_EXPLORER_CONTAINER_LISTEN: enabled\n"));
        assertTrue(block.contains("      MINOS_RUNTIME_LOCATION: docker\n"));
        String networks = text(COMPOSE).substring(text(COMPOSE).indexOf("\nnetworks:\n"));
        assertTrue(networks.contains("  minos-explorer-ingress:\n"), "the ingress network of the explorer is declared");
        // The ingress network is not internal: the label of the service must say so, never promise a denied egress.
        assertTrue(block.contains("      io.minos.network-policy: loopback-published-outbound-not-denied\n"),
                "the network policy label of the explorer states that its outbound route is not denied");
        assertTrue(networks.contains("  minos-runtime:\n    internal: true\n"), "the runtime network stays internal");
    }

    /** The text of each service of a Compose file, by name. */
    private static Map<String, String> services(String compose) {
        Map<String, String> blocks = new LinkedHashMap<>();
        String name = null;
        StringBuilder block = new StringBuilder();
        boolean inServices = false;
        for (String line : compose.split("\n", -1)) {
            if (!line.startsWith(" ") && !line.isBlank() && !line.startsWith("#")) {
                if (name != null) blocks.put(name, block.toString());
                name = null;
                inServices = line.equals("services:");
                continue;
            }
            if (!inServices) continue;
            Matcher key = SERVICE_KEY.matcher(line);
            if (key.matches()) {
                if (name != null) blocks.put(name, block.toString());
                name = key.group(1);
                block = new StringBuilder();
                continue;
            }
            if (name != null) block.append(line).append('\n');
        }
        if (name != null) blocks.put(name, block.toString());
        return blocks;
    }

    /** The entries of the {@code ports:} list of one service. */
    private static List<String> ports(String block) {
        List<String> ports = new ArrayList<>();
        boolean inPorts = false;
        for (String line : block.split("\n")) {
            if (line.equals("    ports:")) {
                inPorts = true;
            } else if (inPorts && line.startsWith("      - ")) {
                Matcher entry = PORT_ENTRY.matcher(line);
                ports.add(entry.matches() ? entry.group(1).trim() : line.trim());
            } else if (inPorts && !line.isBlank() && !line.startsWith("      ")) {
                inPorts = false;
            }
        }
        return ports;
    }

    private static String text(String relative) throws IOException {
        return Files.readString(repoRoot().resolve(relative)).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/Dockerfile.mcp.release"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
