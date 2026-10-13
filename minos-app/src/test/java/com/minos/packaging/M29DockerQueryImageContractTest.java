package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-DEP-15 — l'image du plan de requête MCP ne contient ni compilateur ni outil de provider, et
 * chaque service Docker utilise l'image de son rôle.
 *
 * <p>Les règles sont des fonctions pures sur le texte d'un Dockerfile ou d'un fichier compose :
 * elles sont exécutées sur les fichiers du dépôt (aucune violation attendue) et sur des copies
 * modifiées (la violation doit nommer la ligne ou le service). La preuve sur le contenu réel de
 * l'image est faite par {@code scripts/ci/qualify-docker-release.sh}.</p>
 */
class M29DockerQueryImageContractTest {

    private static final String QUERY_IMAGE = "MINOS_QUERY_IMAGE";
    private static final String ADMIN_IMAGE = "MINOS_IMAGE";

    /** Classement de la règle D2 : variable d'image attendue par service. */
    private static final Map<String, String> SERVICE_IMAGES = serviceImages();

    /** Services dont l'image est une image tierce épinglée dans le compose, pas une image MINOS. */
    private static final Map<String, String> MANAGED_SERVICE_IMAGES = Map.of(
            "minos-postgres", "MINOS_POSTGRES_IMAGE",
            "minos-ollama", "MINOS_OLLAMA_IMAGE");

    /** Commandes que la qualification doit chercher, et ne pas trouver, dans l'image de requête (D3). */
    private static final List<String> ABSENT_COMMANDS = List.of(
            "gcc", "cc", "cargo", "rustc", "go", "dotnet", "pip3", "npm", "node", "javac");

    private static final Pattern FROM = Pattern.compile(
            "^\\s*FROM\\s+(?:--platform=\\S+\\s+)?(\\S+)(?:\\s+AS\\s+([\\w.-]+))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COPY_FROM = Pattern.compile("--from=([\\w.-]+)");
    /** Un mot qui installe ou télécharge : la cible de requête ne fait ni l'un ni l'autre. */
    private static final Pattern INSTALLER = Pattern.compile(
            "(?<![\\w./-])(apt-get|apt|dpkg|pip|pip3|npm|curl|wget|cargo|rustup|go|dotnet|mvn|cs|gcc|make)(?![\\w.-])");
    private static final Pattern BUILD_PACKAGE = Pattern.compile(
            "(?<![\\w./-])(build-essential|pkg-config|libicu-dev|python3-pip|python3|nodejs|golang|default-jdk)(?![\\w.-])");
    private static final Pattern SERVICE_KEY = Pattern.compile("^ {2}([a-z][a-z0-9-]*):\\s*$");
    private static final Pattern IMAGE_VARIABLE = Pattern.compile("^\\s*image:\\s*\"?\\$\\{([A-Z_]+)[:}]");

    private static Map<String, String> serviceImages() {
        Map<String, String> images = new LinkedHashMap<>();
        images.put("minos-mcp", QUERY_IMAGE);
        images.put("minos-bootstrap", QUERY_IMAGE);
        images.put("minos-data-bootstrap", QUERY_IMAGE);
        images.put("minos-ollama-bootstrap", QUERY_IMAGE);
        images.put("minos-admin", ADMIN_IMAGE);
        images.put("minos-tools-bootstrap", ADMIN_IMAGE);
        images.put("minos-provider-probe", ADMIN_IMAGE);
        return images;
    }

    @Test
    void queryTargetOfTheReleaseDockerfileCarriesNoToolchain() throws Exception {
        String dockerfile = text("docker/Dockerfile.mcp.release");

        assertEquals(List.of(), queryTargetViolations(dockerfile),
                "the query target must be a JRE with the MINOS jar and nothing that compiles or installs");

        List<Stage> stages = stages(dockerfile);
        Stage query = stage(stages, "query");
        Stage admin = stage(stages, "admin");
        assertEquals("admin", stages.get(stages.size() - 1).name(),
                "admin must stay the last stage: a build without --target keeps producing the complete image");
        assertEquals(finalBaseImage(text("docker/Dockerfile.mcp")), query.base(),
                "the query target must use the very JRE reference of docker/Dockerfile.mcp");
        assertTrue(admin.base().contains("-jdk-"), "the administration target keeps the JDK scip-java needs");
        for (String toolchain : new String[]{"rust-toolchain", "go-toolchain", "dotnet-toolchain"}) {
            assertTrue(admin.text().contains("COPY --from=" + toolchain + " "),
                    "the administration target must keep what indexing needs: " + toolchain);
        }
        assertTrue(admin.text().contains("build-essential pkg-config libicu-dev"),
                "the administration target must keep the compilation packages the providers need");
        assertTrue(query.text().contains("io.minos.image.plane=\"query\""));
        assertTrue(admin.text().contains("io.minos.image.plane=\"admin\""));
        assertTrue(query.text().contains("io.minos.providers.prepared=\"false\""),
                "the query image must never be mistaken for a provider-complete image");
        for (Stage image : List.of(query, admin)) {
            assertTrue(image.text().contains("USER 10001:10001"), image.name() + " must run unprivileged");
            assertTrue(image.text().contains(
                            "ENTRYPOINT [\"java\", \"-cp\", \"/opt/minos/minos.jar\", \"com.minos.mcp.MinosMcpServer\"]"),
                    image.name() + " must keep the MCP server entry point");
            assertTrue(image.text().contains("COPY --from=licenses /licenses /opt/minos/licenses"),
                    image.name() + " must ship the notices of the JAR it embeds");
        }
    }

    @Test
    void toolchainReintroducedInTheQueryTargetIsRefusedWithItsLine() throws Exception {
        String dockerfile = text("docker/Dockerfile.mcp.release");
        Stage query = stage(stages(dockerfile), "query");
        int insertionLine = query.firstLine() + 1;

        Map<String, String> mutations = new LinkedHashMap<>();
        mutations.put("COPY --from=rust-toolchain /usr/local/cargo /usr/local/cargo", "rust-toolchain");
        mutations.put("COPY --from=admin /usr/local/go /usr/local/go", "admin");
        mutations.put("RUN apt-get update && apt-get install -y --no-install-recommends build-essential", "apt-get");
        mutations.put("RUN curl -fsSLo /tmp/node.tar.xz https://nodejs.org/dist/node.tar.xz", "curl");
        for (Map.Entry<String, String> mutation : mutations.entrySet()) {
            List<String> violations = queryTargetViolations(insertLine(dockerfile, insertionLine, mutation.getKey()));
            assertFalse(violations.isEmpty(), "accepted in the query target: " + mutation.getKey());
            assertTrue(violations.stream().anyMatch(violation ->
                            violation.startsWith("line " + (insertionLine + 1) + ":") && violation.contains(mutation.getValue())),
                    "the refusal must name the line and the cause: " + violations);
        }

        String onJdk = dockerfile.replace(query.base() + " AS query", stage(stages(dockerfile), "admin").base() + " AS query");
        assertTrue(queryTargetViolations(onJdk).stream().anyMatch(violation ->
                        violation.startsWith("line " + (query.firstLine() + 1) + ":") && violation.contains("JRE")),
                "a query target built on the JDK must be refused");

        String withoutQuery = dockerfile.replace(" AS query", " AS lookup");
        assertTrue(queryTargetViolations(withoutQuery).stream().anyMatch(violation -> violation.contains("no stage named query")));
    }

    @Test
    void everyComposeServiceUsesTheImageOfItsRole() throws Exception {
        for (String name : new String[]{"docker/compose-mcp.prod.yaml", "docker/compose-mcp.connected.yaml"}) {
            String compose = text(name);
            assertEquals(List.of(), composeImageViolations(compose), name);
            assertTrue(serviceBlocks(compose).containsKey("minos-mcp"), name + " must define the query plane");
        }
        assertTrue(serviceBlocks(text("docker/compose-mcp.connected.yaml")).containsKey("minos-ollama-bootstrap"));
    }

    @Test
    void queryServiceOnTheAdministrationImageIsRefusedWithItsName() throws Exception {
        for (String name : new String[]{"docker/compose-mcp.prod.yaml", "docker/compose-mcp.connected.yaml"}) {
            String compose = text(name);
            String block = serviceBlocks(compose).get("minos-mcp");
            String onAdministrationImage = block.replace(
                    "${MINOS_QUERY_IMAGE:?MINOS_QUERY_IMAGE is required}", "${MINOS_IMAGE:?MINOS_IMAGE is required}");
            assertNotEquals(block, onAdministrationImage, name + ": minos-mcp must name the query image");
            List<String> violations = composeImageViolations(compose.replace(block, onAdministrationImage));
            assertTrue(violations.stream().anyMatch(violation ->
                            violation.startsWith("minos-mcp:") && violation.contains(QUERY_IMAGE)),
                    name + ": the refusal must name the service: " + violations);

            String unclassified = compose.replace("  minos-admin:\n", "  minos-extra:\n");
            assertTrue(composeImageViolations(unclassified).stream().anyMatch(violation -> violation.startsWith("minos-extra:")),
                    name + ": a service without a classification must be refused");

            String withoutReason = compose.replace(block, block.replaceFirst("(?m)^ {4}# Query image:.*\\n", ""));
            assertTrue(composeImageViolations(withoutReason).stream().anyMatch(violation ->
                            violation.startsWith("minos-mcp:") && violation.contains("comment")),
                    name + ": a service must say why it uses its image");
        }
    }

    @Test
    void qualificationBuildsBothTargetsAndProvesTheContentOfTheQueryImage() throws Exception {
        String qualification = text("scripts/ci/qualify-docker-release.sh");

        assertTrue(qualification.contains("--target admin"), "qualification must build the administration target");
        assertTrue(qualification.contains("--target query"), "qualification must build the query target");
        Matcher absent = Pattern.compile("(?m)^ABSENT_FROM_QUERY_IMAGE=\\(([^)]*)\\)$").matcher(qualification);
        assertTrue(absent.find(), "qualification must declare the commands that must be absent from the query image");
        assertEquals(ABSENT_COMMANDS, List.of(absent.group(1).trim().split("\\s+")),
                "the commands searched in the query image are those of the design (D3)");
        assertTrue(qualification.contains("command -v \"$name\""),
                "absence is proved inside the built image, not on its Dockerfile");
        assertTrue(qualification.contains("--entrypoint sh \"$QUERY_IMAGE\" -ec \"$COMMAND_PROBE; $PAYLOAD_PROBE\""),
                "the probe must run in the query image");
        assertTrue(qualification.contains("--entrypoint sh \"$IMAGE\" -ec \"$COMMAND_PROBE\""),
                "the probe must first find every command in the administration image, or it proves nothing");
        assertTrue(qualification.contains("io.minos.image.plane"));
        assertTrue(qualification.contains("--entrypoint sleep \"$QUERY_IMAGE\""),
                "the MCP smoke and the symbol query must run on the query image");
        assertTrue(qualification.contains("minos_find_symbols"),
                "qualification must send a real symbol query to the MCP server of the query image");
        int queryBuild = qualification.indexOf("--target query");
        assertTrue(qualification.indexOf("minos_find_symbols") > queryBuild
                        && qualification.indexOf("ABSENT_FROM_QUERY_IMAGE=(") > queryBuild,
                "the query image is examined after it has been built");
    }

    // --- rules -------------------------------------------------------------------------------------

    record Stage(String name, String base, int firstLine, List<String> lines) {
        String text() {
            return String.join("\n", lines);
        }
    }

    static List<Stage> stages(String dockerfile) {
        List<Stage> stages = new ArrayList<>();
        String[] lines = dockerfile.split("\n", -1);
        String name = null;
        String base = null;
        int first = -1;
        List<String> body = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            Matcher from = FROM.matcher(lines[index]);
            if (from.matches()) {
                if (first >= 0) stages.add(new Stage(name, base, first, body));
                name = from.group(2) == null ? "" : from.group(2);
                base = from.group(1);
                first = index;
                body = new ArrayList<>();
            }
            if (first >= 0) body.add(lines[index]);
        }
        if (first >= 0) stages.add(new Stage(name, base, first, body));
        return stages;
    }

    private static Stage stage(List<Stage> stages, String name) {
        return stages.stream().filter(candidate -> candidate.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no stage named " + name));
    }

    private static String finalBaseImage(String dockerfile) {
        List<Stage> stages = stages(dockerfile);
        return stages.get(stages.size() - 1).base();
    }

    /** Violations de la cible {@code query}, chacune préfixée par {@code line <n>:} (numérotée à partir de 1). */
    static List<String> queryTargetViolations(String dockerfile) {
        List<String> violations = new ArrayList<>();
        Stage query = stages(dockerfile).stream().filter(candidate -> candidate.name().equals("query")).findFirst().orElse(null);
        if (query == null) {
            violations.add("line 1: no stage named query in the release Dockerfile");
            return violations;
        }
        if (!query.base().toLowerCase(Locale.ROOT).contains("-jre-")) {
            violations.add("line " + (query.firstLine() + 1) + ": the query target must be built on a JRE image, not " + query.base());
        }
        boolean continued = false;
        String instruction = "";
        for (int index = 1; index < query.lines().size(); index++) {
            String line = query.lines().get(index);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (!continued) instruction = trimmed.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
            continued = trimmed.endsWith("\\");
            int number = query.firstLine() + index + 1;
            if (instruction.equals("COPY") || instruction.equals("ADD") || instruction.equals("RUN")) {
                Matcher copy = COPY_FROM.matcher(line);
                while (copy.find()) {
                    if (!copy.group(1).equals("licenses")) {
                        violations.add("line " + number + ": the query target copies from stage " + copy.group(1));
                    }
                }
            }
            if (instruction.equals("RUN")) {
                Matcher installer = INSTALLER.matcher(line);
                if (installer.find()) {
                    violations.add("line " + number + ": the query target runs " + installer.group(1));
                }
                Matcher buildPackage = BUILD_PACKAGE.matcher(line);
                if (buildPackage.find()) {
                    violations.add("line " + number + ": the query target installs " + buildPackage.group(1));
                }
            }
        }
        return violations;
    }

    /** Blocs de services d'un fichier compose, dans l'ordre du fichier. */
    static Map<String, String> serviceBlocks(String compose) {
        Map<String, String> blocks = new LinkedHashMap<>();
        int servicesAt = compose.indexOf("\nservices:\n");
        if (servicesAt < 0) return blocks;
        String name = null;
        StringBuilder block = new StringBuilder();
        for (String line : compose.substring(servicesAt + "\nservices:\n".length()).split("\n", -1)) {
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) break;
            Matcher key = SERVICE_KEY.matcher(line);
            if (key.matches()) {
                if (name != null) blocks.put(name, block.toString());
                name = key.group(1);
                block = new StringBuilder();
            }
            if (name != null) block.append(line).append('\n');
        }
        if (name != null) blocks.put(name, block.toString());
        return blocks;
    }

    /** Violations du classement des services, chacune préfixée par {@code <service>:}. */
    static List<String> composeImageViolations(String compose) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> service : serviceBlocks(compose).entrySet()) {
            String name = service.getKey();
            String[] lines = service.getValue().split("\n");
            String variable = null;
            String previous = "";
            String comment = "";
            for (String line : lines) {
                Matcher image = IMAGE_VARIABLE.matcher(line);
                if (image.find()) {
                    variable = image.group(1);
                    comment = previous.trim();
                    break;
                }
                previous = line;
            }
            if (variable == null) {
                violations.add(name + ": no image taken from a variable");
                continue;
            }
            if (MANAGED_SERVICE_IMAGES.containsKey(name)) {
                if (!MANAGED_SERVICE_IMAGES.get(name).equals(variable)) {
                    violations.add(name + ": expected the pinned image of " + MANAGED_SERVICE_IMAGES.get(name) + ", found " + variable);
                }
                continue;
            }
            String expected = SERVICE_IMAGES.get(name);
            if (expected == null) {
                violations.add(name + ": service without a classification (query image or administration image)");
                continue;
            }
            if (!expected.equals(variable)) {
                violations.add(name + ": must use " + expected + ", found " + variable);
            }
            String reason = expected.equals(QUERY_IMAGE) ? "# Query image:" : "# Administration image:";
            if (!comment.startsWith(reason)) {
                violations.add(name + ": the line above image: must be a comment starting with '" + reason + "'");
            }
        }
        return violations;
    }

    private static String insertLine(String content, int afterLineIndex, String inserted) {
        List<String> lines = new ArrayList<>(List.of(content.split("\n", -1)));
        lines.add(afterLineIndex, inserted);
        return String.join("\n", lines);
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
