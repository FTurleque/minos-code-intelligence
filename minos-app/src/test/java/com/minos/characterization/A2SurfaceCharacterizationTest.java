package com.minos.characterization;

import com.minos.api.LocalMinosApi;
import com.minos.api.LocalProviderPlatformApi;
import com.minos.api.MinosApi;
import com.minos.cli.MinosLauncher;
import com.minos.mcp.MinosMcpTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A2 — caractérisation des surfaces CLI, MCP et API Java avant le déplacement hexagonal.
 *
 * <p>Chaque test exécute de vraies commandes sur la fixture {@code fixtures/typescript/typescript-modules}
 * (import de son artefact SCIP versionné, donc sans provider externe) dans un MINOS_HOME temporaire
 * neuf, et compare la transcription normalisée ({@link CharacterizationNormalizer}) à un fichier de
 * référence. Ces références doivent rester identiques du début à la fin d'A2 : une différence est
 * une régression de comportement, pas une mise à jour à accepter.</p>
 */
class A2SurfaceCharacterizationTest {

    private static final Path REPOSITORY = Path.of("").toAbsolutePath().normalize();
    private static final Path FIXTURE = Path.of("fixtures", "typescript", "typescript-modules");
    private static final Path SCIP = FIXTURE.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
    private static final String PROJECT = "a2-char";
    private static final Pattern FIRST_ID = Pattern.compile("\"id\":\"([^\"]+)\"");
    /**
     * Identité de projet figée. Les identifiants de symbole, d'occurrence et de relation hachent
     * l'identifiant de projet, et l'ordre de plusieurs listes suit ces identifiants ; or le registre
     * local tire un UUID aléatoire. Juste après l'enregistrement (dont la sortie est capturée telle
     * quelle), le fichier du registre est renommé et réécrit avec cet UUID fixe : tout le reste de
     * la transcription devient ainsi déterministe sans normaliser les empreintes.
     */
    private static final String PINNED_PROJECT_ID = "a2a2a2a2-0000-4000-8000-00000000c0de";

    /** Variables lues par la configuration runtime : une valeur héritée de l'hôte changerait les sorties. */
    private static final List<String> SETTINGS_ENVIRONMENT = List.of(
            "MINOS_STORAGE_BACKEND", "MINOS_SEMANTIC_PROVIDER", "MINOS_SEMANTIC_MODEL",
            "MINOS_SEMANTIC_DIMENSIONS", "MINOS_SEMANTIC_ENDPOINT", "MINOS_SEMANTIC_TIMEOUT_SECONDS",
            "MINOS_HOSTED_MODE", "MINOS_TEAM_TOKEN", "MINOS_POSTGRES_URL");

    @BeforeAll
    static void hostDoesNotOverrideDefaults() {
        for (String variable : SETTINGS_ENVIRONMENT) {
            assertNull(System.getenv(variable),
                    "A2 characterization expects default settings; unset " + variable + " to run it");
        }
    }

    @Test
    void cliTextOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Golden.assertMatches("cli-text.golden", cliTranscript(temp, "text"));
    }

    @Test
    void cliJsonOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Golden.assertMatches("cli-json.golden", cliTranscript(temp, "json"));
    }

    @Test
    void cliDoctorOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        transcript.cli(home, "doctor", "--format", "text");
        transcript.cli(home, "doctor", "--format", "json");
        Golden.assertMatches("cli-doctor.golden", CharacterizationNormalizer.normalizeHostFacts(transcript.text()));
    }

    @Test
    void cliSemanticProviderLimitationsAreUnchanged(@TempDir Path temp) throws Exception {
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));

        Path localHash = temp.resolve("home-local-hash");
        transcript.cli(localHash, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", "json");
        pinProjectIdentity(localHash);
        configure(localHash, "minos.semantic.provider=local-hash\n");
        transcript.cli(localHash, "semantic", "status", PROJECT, "--format", "json");
        transcript.cli(localHash, "index", PROJECT, "--scip", SCIP.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");
        transcript.cli(localHash, "semantic", "status", PROJECT, "--format", "json");
        transcript.cli(localHash, "semantic", "status", PROJECT, "--format", "text");
        transcript.cli(localHash, "hybrid", "status", PROJECT, "--format", "json");

        Path ollama = temp.resolve("home-ollama");
        transcript.cli(ollama, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", "json");
        pinProjectIdentity(ollama);
        // Point de terminaison volontairement injoignable : le statut ne doit jamais l'appeler.
        configure(ollama, """
                minos.semantic.provider=ollama
                minos.semantic.model=a2-characterization-model
                minos.semantic.dimensions=32
                minos.semantic.endpoint=http://127.0.0.1:9/
                """);
        transcript.cli(ollama, "semantic", "status", PROJECT, "--format", "json");
        transcript.cli(ollama, "semantic", "status", PROJECT, "--format", "text");

        Path invalid = temp.resolve("home-invalid-ollama");
        transcript.cli(invalid, "project", "list", "--format", "json");
        configure(invalid, """
                minos.semantic.provider=ollama
                minos.semantic.model=a2-characterization-model
                minos.semantic.dimensions=8
                """);
        transcript.cli(invalid, "project", "list", "--format", "json");

        Golden.assertMatches("cli-semantic.golden", transcript.text());
    }

    @Test
    void mcpToolOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript setup = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        setup.cli(home, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", "json");
        pinProjectIdentity(home);
        setup.cli(home, "index", PROJECT, "--scip", SCIP.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");
        String symbolId = symbolId(home, "GreetingPort");

        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        try (MinosMcpTools tools = new MinosMcpTools(home)) {
            List<SyncToolSpecification> specifications = tools.specifications();
            transcript.mcp(specifications, "minos_project_structure", Map.of("project", PROJECT));
            transcript.mcp(specifications, "minos_index_status", Map.of("project", PROJECT));
            transcript.mcp(specifications, "minos_find_symbols", Map.of("project", PROJECT, "query", "Greeting", "limit", 20));
            transcript.mcp(specifications, "minos_search_code", Map.of("project", PROJECT, "query", "GreetingPort"));
            transcript.mcp(specifications, "minos_find_usages", Map.of("project", PROJECT, "symbolId", symbolId, "limit", 20));
            transcript.mcp(specifications, "minos_architecture", Map.of("project", PROJECT));
            transcript.mcp(specifications, "minos_impact", Map.of("project", PROJECT, "symbolId", symbolId));
            transcript.mcp(specifications, "minos_semantic_index_status", Map.of("project", PROJECT));
            transcript.mcp(specifications, "minos_index_status", Map.of("project", "a2-missing-project"));
        }
        Golden.assertMatches("mcp.golden", transcript.text());
    }

    @Test
    void javaApiOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        try (LocalMinosApi api = new LocalMinosApi(home)) {
            transcript.api("addProject", () -> api.addProject(FIXTURE, PROJECT));
        }
        pinProjectIdentity(home);
        try (LocalMinosApi api = new LocalMinosApi(home)) {
            transcript.api("listProjects", api::listProjects);
            transcript.api("getProject", () -> api.getProject(PROJECT));
            transcript.api("importScip", () -> api.importScip(PROJECT, SCIP,
                    new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, null)));
            transcript.api("getProject(after index)", () -> api.getProject(PROJECT));
            transcript.api("findSymbols", () -> api.findSymbols(PROJECT, MinosApi.SymbolQuery.lexical("Greeting", 20)));
            String symbolId = api.findSymbols(PROJECT,
                    new MinosApi.SymbolQuery(null, "GreetingPort", null, null, 1)).getFirst().id();
            transcript.api("findUsages", () -> api.findUsages(PROJECT, symbolId, 20));
            transcript.api("findRelationships", () -> api.findRelationships(PROJECT,
                    MinosApi.RelationshipQuery.incomingSymbol(symbolId, Set.of(), 20)));
            transcript.api("getArchitecture", () -> api.getArchitecture(PROJECT));
            transcript.api("analyzeImpact", () -> api.analyzeImpact(PROJECT, MinosApi.ImpactQuery.defaults(symbolId)));
            transcript.api("getProject(missing)", () -> api.getProject("a2-missing-project"));
        }
        try (LocalProviderPlatformApi providers = new LocalProviderPlatformApi(home)) {
            transcript.api("listProviders", providers::listProviders);
            transcript.api("getProvider", () -> providers.getProvider("scip-typescript"));
        }
        Golden.assertMatches("api.golden", transcript.text());
    }

    private static String cliTranscript(Path temp, String format) throws Exception {
        Path home = temp.resolve("home");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        transcript.cli(home, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", format);
        pinProjectIdentity(home);
        transcript.cli(home, "project", "list", "--format", format);
        transcript.cli(home, "project", "inspect", PROJECT, "--format", format);
        transcript.cli(home, "index-status", PROJECT, "--format", format);
        transcript.cli(home, "index", PROJECT, "--scip", SCIP.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", format);
        transcript.cli(home, "index-status", PROJECT, "--format", format);
        transcript.cli(home, "inspect", PROJECT, "--format", format);
        transcript.cli(home, "find-symbol", PROJECT, "Greeting", "--format", format);
        transcript.cli(home, "search", PROJECT, "GreetingPort", "--format", format);
        String symbolId = symbolId(home, "GreetingPort");
        transcript.cli(home, "find-usages", PROJECT, symbolId, "--format", format);
        transcript.cli(home, "dependencies", PROJECT, symbolId, "--format", format);
        transcript.cli(home, "impact", PROJECT, symbolId, "--format", format);
        transcript.cli(home, "architecture", PROJECT, "--format", format);
        transcript.cli(home, "providers", "--format", format);
        transcript.cli(home, "providers", "scip-typescript", "--format", format);
        transcript.cli(home, "semantic", "status", PROJECT, "--format", format);
        transcript.cli(home, "hybrid", "status", PROJECT, "--format", format);
        transcript.cli(home, "find-symbol", "a2-missing-project", "Greeting", "--format", format);
        return transcript.text();
    }

    private static String symbolId(Path home, String qualifiedName) throws IOException {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exit = MinosLauncher.run(home,
                new String[]{"find-symbol", PROJECT, qualifiedName, "--format", "json"}, output, error);
        Matcher matcher = FIRST_ID.matcher(output);
        if (exit != 0 || !matcher.find()) fail("unable to resolve " + qualifiedName + ": " + output + error);
        return matcher.group(1);
    }

    private static void pinProjectIdentity(Path home) throws IOException {
        Path projects = home.resolve("registry").resolve("projects");
        List<Path> files;
        try (Stream<Path> entries = Files.list(projects)) {
            files = entries.filter(path -> path.getFileName().toString().endsWith(".properties")).toList();
        }
        if (files.size() != 1) fail("expected exactly one registered project, found " + files);
        Path generated = files.getFirst();
        String generatedId = generated.getFileName().toString().replace(".properties", "");
        // Properties.store écrit en ISO-8859-1 ; le renommage conserve les permissions privées du fichier.
        String content = Files.readString(generated, StandardCharsets.ISO_8859_1);
        Path pinned = Files.move(generated, projects.resolve(PINNED_PROJECT_ID + ".properties"));
        Files.writeString(pinned, content.replace(generatedId, PINNED_PROJECT_ID), StandardCharsets.ISO_8859_1);
    }

    private static void configure(Path home, String properties) throws IOException {
        Path configuration = home.resolve("config");
        Files.createDirectories(configuration);
        Files.writeString(configuration.resolve("minos.properties"), properties, StandardCharsets.UTF_8);
    }

    @FunctionalInterface
    private interface ApiCall {
        Object call() throws Exception;
    }

    /** Transcription ordonnée : commande (sous forme symbolique), code de sortie, stdout, stderr. */
    private static final class Transcript {
        private final CharacterizationNormalizer normalizer;
        private final StringBuilder text = new StringBuilder();

        private Transcript(CharacterizationNormalizer normalizer) {
            this.normalizer = normalizer;
        }

        void cli(Path home, String... arguments) throws IOException {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            String exit;
            try {
                exit = String.valueOf(MinosLauncher.run(home, arguments, output, error));
            } catch (RuntimeException exception) {
                // Une configuration invalide sort aujourd'hui de MinosLauncher.run par une exception non contrôlée.
                exit = "thrown " + exception.getClass().getName() + ": " + exception.getMessage();
            }
            text.append("$ minos ").append(normalizer.normalize(String.join(" ", symbolic(arguments)))).append('\n')
                    .append("exit: ").append(normalizer.normalize(exit)).append('\n')
                    .append("--- stdout\n").append(normalizer.normalize(output.toString())).append('\n')
                    .append("--- stderr\n").append(normalizer.normalize(error.toString())).append('\n');
        }

        void mcp(List<SyncToolSpecification> specifications, String tool, Map<String, Object> arguments) {
            SyncToolSpecification specification = specifications.stream()
                    .filter(candidate -> tool.equals(candidate.tool().name()))
                    .findFirst()
                    .orElseThrow();
            CallToolResult result = specification.callHandler()
                    .apply(null, CallToolRequest.builder(tool).arguments(arguments).build());
            StringBuilder content = new StringBuilder();
            for (var item : result.content()) {
                content.append(item instanceof TextContent textContent ? textContent.text() : item.toString());
            }
            text.append("@ ").append(tool).append(' ').append(normalizer.normalize(render(new TreeMap<>(arguments)))).append('\n')
                    .append("isError: ").append(Boolean.TRUE.equals(result.isError())).append('\n')
                    .append("--- content\n").append(normalizer.normalize(content.toString())).append('\n');
        }

        void api(String operation, ApiCall call) {
            String value;
            try {
                value = "ok: " + render(call.call());
            } catch (MinosApi.MinosApiException exception) {
                value = "MinosApiException[" + exception.code() + "]: " + exception.getMessage();
            } catch (Exception exception) {
                value = exception.getClass().getName() + ": " + exception.getMessage();
            }
            text.append("# ").append(operation).append('\n').append(normalizer.normalize(value)).append('\n');
        }

        String text() {
            return text.toString();
        }

        private List<String> symbolic(String[] arguments) {
            List<String> result = new ArrayList<>();
            for (String argument : arguments) {
                if (argument.equals(FIXTURE.toString())) result.add("<FIXTURE>");
                else if (argument.equals(SCIP.toString())) result.add("<SCIP>");
                else result.add(argument);
            }
            return result;
        }
    }

    /**
     * Rendu déterministe d'une valeur de l'API : les records composant par composant, les listes
     * dans leur ordre, les ensembles et les maps triés (leur ordre d'itération n'est pas un contrat
     * et varie d'une JVM à l'autre pour {@code Set.copyOf}/{@code Map.copyOf}).
     */
    static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof Record record) {
            StringBuilder builder = new StringBuilder(record.getClass().getSimpleName()).append('[');
            RecordComponent[] components = record.getClass().getRecordComponents();
            for (int index = 0; index < components.length; index++) {
                if (index > 0) builder.append(", ");
                try {
                    var accessor = components[index].getAccessor();
                    accessor.setAccessible(true);
                    builder.append(components[index].getName()).append('=').append(render(accessor.invoke(record)));
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return builder.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, String> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(render(key), render(entry)));
            return sorted.toString();
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(A2SurfaceCharacterizationTest::render).sorted().toList().toString();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(A2SurfaceCharacterizationTest::render).toList().toString();
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(inner -> "Optional[" + render(inner) + "]").orElse("Optional.empty");
        }
        return String.valueOf(value);
    }
}
