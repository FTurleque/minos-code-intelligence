package com.minos.characterization;

import com.minos.api.LocalMinosApi;
import com.minos.api.MinosTeamApi;
import com.minos.application.MinosApplication;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.api.LocalProviderPlatformApi;
import com.minos.api.MinosApi;
import com.minos.cli.MinosLauncher;
import com.minos.mcp.MinosMcpTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.spec.SecretKeySpec;
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

    /** V38 — les 31 outils MCP, sur la fixture indexée et une session d'observations runtime importée. */
    @Test
    void mcpEveryToolOutputIsUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript setup = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        setup.cli(home, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", "json");
        pinProjectIdentity(home);
        setup.cli(home, "index", PROJECT, "--scip", SCIP.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");
        setup.cli(home, "runtime", "import", PROJECT, "--file", runtimeEnvelope(temp).toString(), "--format", "json");
        String port = symbolId(home, "GreetingPort");
        String implementation = symbolId(home, "DefaultGreetingPort");

        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        try (MinosMcpTools tools = new MinosMcpTools(home)) {
            List<SyncToolSpecification> specifications = tools.specifications();
            transcript.text.append("tools: ").append(specifications.stream().map(spec -> spec.tool().name()).toList()).append('\n');
            Map<String, Object> project = Map.of("project", PROJECT);
            transcript.mcp(specifications, "minos_project_structure", project);
            transcript.mcp(specifications, "minos_index_status", project);
            transcript.mcp(specifications, "minos_search_code", Map.of("project", PROJECT, "query", "Greeting",
                    "includeSource", true, "contextLines", 2));
            transcript.mcp(specifications, "minos_find_symbols", Map.of("project", PROJECT, "query", "greet", "limit", 50));
            for (String tool : List.of("minos_find_usages", "minos_find_implementations", "minos_find_callers",
                    "minos_find_callees", "minos_dependencies", "minos_dependents", "minos_related_tests")) {
                transcript.mcp(specifications, tool, Map.of("project", PROJECT, "symbolId", port, "limit", 50));
                transcript.mcp(specifications, tool, Map.of("project", PROJECT, "symbolId", implementation, "limit", 50));
            }
            transcript.mcp(specifications, "minos_symbol_context", Map.of("project", PROJECT, "query", "GreetingPort",
                    "includeSource", true));
            transcript.mcp(specifications, "minos_module_context", Map.of("project", PROJECT, "module", "packages/api"));
            transcript.mcp(specifications, "minos_architecture", project);
            for (String format : List.of("json", "mermaid", "dot")) {
                transcript.mcp(specifications, "minos_architecture_graph", Map.of("project", PROJECT, "format", format));
            }
            transcript.mcp(specifications, "minos_architecture_graph",
                    Map.of("project", PROJECT, "module", "packages/app", "format", "mermaid"));
            transcript.mcp(specifications, "minos_impact", Map.of("project", PROJECT, "symbolId", port));
            transcript.mcp(specifications, "minos_program_graph", project);
            transcript.mcp(specifications, "minos_impact_v2", Map.of("project", PROJECT, "symbolId", port));
            transcript.mcp(specifications, "minos_security_paths", project);
            transcript.mcp(specifications, "minos_semantic_index_status", project);
            transcript.mcp(specifications, "minos_semantic_search", Map.of("project", PROJECT, "query", "greeting"));
            transcript.mcp(specifications, "minos_hybrid_search", Map.of("project", PROJECT, "query", "greeting"));
            transcript.mcp(specifications, "minos_hybrid_context", Map.of("project", PROJECT, "query", "greeting"));
            transcript.mcp(specifications, "minos_runtime_sessions", project);
            transcript.mcp(specifications, "minos_runtime_report", project);
            transcript.mcp(specifications, "minos_runtime_symbol", Map.of("project", PROJECT, "symbolId", port));
            transcript.mcp(specifications, "minos_team_tenant", Map.of());
            transcript.mcp(specifications, "minos_team_workspaces", Map.of());
            transcript.mcp(specifications, "minos_team_workspace",
                    Map.of("workspaceId", "10000000-0000-0000-0000-000000000001"));
            transcript.mcp(specifications, "minos_team_members", Map.of());
            transcript.mcp(specifications, "minos_team_audit", Map.of("limit", 10));
        }
        Golden.assertMatches("mcp-all-tools.golden", transcript.text());
    }

    /**
     * V38 — graphe de programme sur un projet Java : les fournisseurs de production (relations et
     * source Java contrainte par les empreintes) contribuent tous deux, leur ordre est donc observable.
     */
    @Test
    void javaProgramGraphOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path projectRoot = Files.createDirectories(temp.resolve("java-project"));
        String fileId = "src/main/java/demo/A2Fixture.java";
        write(projectRoot.resolve(fileId), """
                package demo;
                final class A2Fixture {
                    String source() { return "raw"; }
                    String sanitize(String value) { return value; }
                    void sink(String value) { }
                    String callee(String value) { return value; }
                    String run() {
                        String raw = source();
                        String clean = sanitize(raw);
                        sink(clean);
                        String copy = callee(clean);
                        if (copy.isEmpty()) {
                            return raw;
                        }
                        return copy;
                    }
                }
                """);
        write(projectRoot.resolve(".minos/java-advanced-provider.properties"),
                "sources=source\nsanitizers=sanitize\nsinks=sink\n");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        transcript.cli(home, "project", "add", projectRoot.toString(), "--name", "a2-java", "--format", "json");
        pinProjectIdentity(home);
        java.util.UUID projectId = java.util.UUID.fromString(PINNED_PROJECT_ID);
        String snapshotId = "a2-java-snapshot";
        try (MinosApplication application = MinosApplication.open(home)) {
            com.minos.domain.Symbol symbol = new com.minos.domain.Symbol(
                    "a2-java-symbol", "demo/A2Fixture#", com.minos.domain.SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                    PINNED_PROJECT_ID, null, fileId, null, com.minos.domain.SymbolKind.CLASS, "A2Fixture",
                    "demo.A2Fixture", null, "java",
                    new com.minos.domain.SymbolLocation(fileId, 1, 0, 1, 1, com.minos.domain.PositionEncoding.UTF16_CODE_UNITS),
                    com.minos.domain.ResolutionStatus.RESOLVED,
                    new com.minos.domain.Origin("a2-characterization", "FIXTURE", "1", snapshotId,
                            com.minos.domain.OriginType.OTHER),
                    false, false, Set.of());
            application.snapshotStore().publish(projectId, snapshotId, List.of(symbol), List.of(), List.of());
            application.fingerprintStore().publish(projectId, snapshotId,
                    application.fingerprintService().capture(projectRoot));
            application.fingerprintStore().promote(projectId, snapshotId);
        }
        try (MinosMcpTools tools = new MinosMcpTools(home)) {
            List<SyncToolSpecification> specifications = tools.specifications();
            transcript.mcp(specifications, "minos_program_graph", Map.of("project", "a2-java"));
            transcript.mcp(specifications, "minos_impact_v2", Map.of("project", "a2-java", "symbolId", "a2-java-symbol"));
            transcript.mcp(specifications, "minos_security_paths", Map.of("project", "a2-java"));
        }
        // Source modifiée après le snapshot : seul le fournisseur Java contraint par les empreintes
        // refuse de la lire, ce qui rend observable le câblage de ce fournisseur par la composition.
        write(projectRoot.resolve(fileId), Files.readString(projectRoot.resolve(fileId), StandardCharsets.UTF_8)
                .replace("String copy = callee(clean);", "String copy = callee(raw);"));
        try (MinosMcpTools tools = new MinosMcpTools(home)) {
            List<SyncToolSpecification> specifications = tools.specifications();
            transcript.mcp(specifications, "minos_program_graph", Map.of("project", "a2-java"));
            transcript.mcp(specifications, "minos_security_paths", Map.of("project", "a2-java"));
        }
        Golden.assertMatches("java-program-graph.golden", transcript.text());
    }

    /** V38 — observations runtime (magasin runtime-observations et sa sérialisation). */
    @Test
    void cliRuntimeObservationOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        transcript.cli(home, "project", "add", FIXTURE.toString(), "--name", PROJECT, "--format", "json");
        pinProjectIdentity(home);
        transcript.cli(home, "index", PROJECT, "--scip", SCIP.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");
        Path envelope = runtimeEnvelope(temp);
        String port = symbolId(home, "GreetingPort");
        for (String format : List.of("text", "json")) {
            transcript.cli(home, "runtime", "import", PROJECT, "--file", envelope.toString(), "--format", format);
            transcript.cli(home, "runtime", "sessions", PROJECT, "--format", format);
            transcript.cli(home, "runtime", "report", PROJECT, "--format", format);
            transcript.cli(home, "runtime", "report", PROJECT, "--session", "a2-session", "--format", format);
            transcript.cli(home, "runtime", "symbol", PROJECT, "--symbol", port, "--format", format);
        }
        Golden.assertMatches("cli-runtime.golden", transcript.text());
    }

    /** V38 — intelligence Git (GitIntelligenceService) sur un dépôt JGit à auteurs et dates figés. */
    @Test
    void cliGitActivityOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Path repository = Files.createDirectories(temp.resolve("git-project"));
        write(repository.resolve("package.json"), "{\"name\":\"a2-git\",\"private\":true}\n");
        write(repository.resolve("src/greeting.ts"), "export const greeting = 'hello';\n");
        write(repository.resolve("src/service/service.ts"), "export function serve(): string { return 'ok'; }\n");
        PersonIdent author = new PersonIdent("A2 Characterization", "a2@example.invalid",
                java.util.Date.from(java.time.Instant.parse("2026-09-01T08:00:00Z")), java.util.TimeZone.getTimeZone("UTC"));
        try (Git git = Git.init().setDirectory(repository.toFile()).setInitialBranch("main").call()) {
            var config = git.getRepository().getConfig();
            config.setString("core", null, "autocrlf", "false");
            config.save();
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial").setAuthor(author).setCommitter(author).setSign(false).call();
            write(repository.resolve("src/greeting.ts"), "export const greeting = 'hello, world';\nexport const bye = 'bye';\n");
            PersonIdent later = new PersonIdent(author, java.util.Date.from(java.time.Instant.parse("2026-09-02T08:00:00Z")),
                    java.util.TimeZone.getTimeZone("UTC"));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("change greeting").setAuthor(later).setCommitter(later).setSign(false).call();
        }
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        transcript.cli(home, "project", "add", repository.toString(), "--name", "a2-git", "--format", "json");
        for (String format : List.of("text", "json")) {
            transcript.cli(home, "git-activity", "a2-git", "--days", "3650", "--format", format);
            transcript.cli(home, "git-activity", "a2-git", "--days", "3650", "--zone-depth", "1", "--max-files", "1",
                    "--format", format);
        }
        Golden.assertMatches("cli-git.golden", transcript.text());
    }

    /** V38 — plan de contrôle hébergé (FileHostedControlPlaneStore) : API team, CLI team, MCP team. */
    @Test
    void teamControlPlaneOutputsAreUnchanged(@TempDir Path temp) throws Exception {
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        Path home = temp.resolve("home");
        java.time.Clock clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-15T10:00:00Z"), java.time.ZoneOffset.UTC);
        try (MinosApplication application = MinosApplication.builder(home)
                .hostedTenantKeyProvider(fixedKeys())
                .hostedClock(clock)
                .build();
             LocalMinosApi api = new LocalMinosApi(application)) {
            MinosTeamApi team = api.team();
            java.util.UUID tenant = java.util.UUID.fromString("7e7e7e7e-0000-4000-8000-00000000a2a2");
            MinosTeamApi.BootstrapDto bootstrap = team.bootstrap(new MinosTeamApi.BootstrapRequest(tenant, "Acme",
                    "key-a", "owner", "Owner", java.time.Duration.ofHours(1), "a2-bootstrap"));
            transcript.api("bootstrap", () -> bootstrap);
            String token = bootstrap.bearerToken();
            transcript.api("tenant", () -> team.tenant(token));
            MinosTeamApi.WorkspaceDto workspace = team.createWorkspace(token, "a2-workspace", "Platform");
            transcript.api("createWorkspace", () -> workspace);
            transcript.api("listWorkspaces", () -> team.listWorkspaces(token));
            transcript.api("getWorkspace", () -> team.getWorkspace(token, workspace.workspaceId()));
            transcript.api("grantMember", () -> team.grantMember(token, "a2-grant",
                    new MinosTeamApi.MemberGrantRequest("reader", "Reader", "VIEWER")));
            transcript.api("listMembers", () -> team.listMembers(token));
            transcript.api("retentionPlan", () -> team.retentionPlan(token));
            transcript.api("audit", () -> team.audit(token, 50));
            transcript.api("tenant(invalid token)", () -> team.tenant("invalid-token"));
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int exit = MinosLauncher.run(application, new String[]{"team", "tenant"}, output, error);
            transcript.text.append("$ minos team tenant (application hébergée, sans MINOS_TEAM_TOKEN)\nexit: ").append(exit)
                    .append("\n--- stdout\n").append(transcript.normalizer.normalize(output.toString()))
                    .append("\n--- stderr\n").append(transcript.normalizer.normalize(error.toString())).append('\n');
        }
        Path local = temp.resolve("home-local");
        transcript.cli(local, "team", "tenant", "--format", "json");
        Path configured = temp.resolve("home-hosted");
        transcript.cli(configured, "project", "list", "--format", "json");
        configure(configured, "minos.hosted.mode=enabled\n");
        transcript.cli(configured, "team", "bootstrap", "--tenant", "7e7e7e7e-0000-4000-8000-00000000a2a2",
                "--name", "Acme", "--key-id", "a2-missing-key", "--owner", "owner", "--owner-name", "Owner");
        transcript.cli(configured, "team", "tenant");
        Golden.assertMatches("team.golden", transcript.text());
    }

    /** V38 — effet observable de la rétention persistante du backend local. */
    @Test
    void retentionEffectIsUnchanged(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        Transcript transcript = new Transcript(new CharacterizationNormalizer(temp, REPOSITORY));
        try (LocalMinosApi api = new LocalMinosApi(home)) {
            transcript.api("addProject", () -> api.addProject(FIXTURE, PROJECT));
        }
        pinProjectIdentity(home);
        try (LocalMinosApi api = new LocalMinosApi(home)) {
            for (String snapshot : List.of("a2-snapshot-1", "a2-snapshot-2", "a2-snapshot-3", "a2-snapshot-4")) {
                transcript.api("importScip " + snapshot, () -> api.importScip(PROJECT, SCIP,
                        new MinosApi.IndexImportRequest("scip-typescript", "0.4.0", null, snapshot)));
            }
        }
        transcript.text.append("before: ").append(transcript.normalizer.normalize(listing(home))).append('\n');
        java.util.UUID projectId = java.util.UUID.fromString(PINNED_PROJECT_ID);
        try (MinosApplication application = MinosApplication.open(home)) {
            transcript.api("compact(default)", () -> application.retentionService().compact(projectId));
            transcript.text.append("after compact(default): ").append(transcript.normalizer.normalize(listing(home))).append('\n');
            transcript.api("compact(0,1,1)", () -> application.retentionService().compact(projectId,
                    new com.minos.storage.PersistentRetentionPolicy(0, 1, 1)));
            transcript.text.append("after compact(0,1,1): ").append(transcript.normalizer.normalize(listing(home))).append('\n');
            transcript.api("activeSnapshot", () -> application.snapshotStore().loadActive(projectId)
                    .map(snapshot -> snapshot.snapshotId()));
            transcript.api("runs", () -> application.indexStateStore().listRuns(projectId).stream()
                    .map(run -> run.status()).toList());
        }
        transcript.cli(home, "index-status", PROJECT, "--format", "json");
        Golden.assertMatches("retention.golden", transcript.text());
    }

    /** Arborescence (noms seuls, triés) des espaces que la rétention peut toucher. */
    private static String listing(Path home) throws IOException {
        List<String> names = new java.util.ArrayList<>();
        for (String namespace : List.of("symbol-snapshots", "index-state", "fingerprint-snapshots")) {
            Path root = home.resolve(namespace);
            try (Stream<Path> paths = Files.walk(root)) {
                paths.filter(Files::isRegularFile)
                        .map(path -> namespace + "/" + root.relativize(path).toString().replace('\\', '/'))
                        .sorted()
                        .forEach(names::add);
            }
        }
        return names.toString();
    }

    private static Path runtimeEnvelope(Path temp) throws IOException {
        Path envelope = temp.resolve("a2-runtime.tsv");
        write(envelope, String.join("\n",
                "minos-runtime-observation-v1",
                "session\ta2-session",
                "project\t" + PINNED_PROJECT_ID,
                "snapshot\tscip-7f41649a3cdad442a3235c0a",
                "started\t2026-09-10T06:00:00Z",
                "ended\t2026-09-10T06:05:00Z",
                "collector\ta2-fixture\t1.0.0",
                "environment\ttest",
                "completeness\tPARTIAL",
                "symbol\t\tGreetingPort\tpackages/api/src/greeting-contract.ts\t1\t5\t500",
                "symbol\t\tDefaultGreetingPort.greet\tpackages/app/src/default-greeting-port.ts\t4\t3\t300",
                "call\t\tDefaultGreetingPort.greet\tpackages/app/src/default-greeting-port.ts\t4\t\tGreetingPort"
                        + "\tpackages/api/src/greeting-contract.ts\t1\t3\t200",
                "line\tpackages/app/src/greeting-service.ts\t7\t4",
                "symbol\t\ta2.Missing\t\t\t1\t0",
                ""));
        return envelope;
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static HostedTenantKeyProvider fixedKeys() {
        return (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            java.util.Arrays.fill(bytes, (byte) (keyId.length() + purpose.ordinal() + 1));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
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
                    Object component = accessor.invoke(record);
                    String name = components[index].getName();
                    // Un jeton porteur n'est jamais écrit dans une référence ; sa seule présence est comparée.
                    String rendered = name.endsWith("Token") && component != null ? "<secret>" : render(component);
                    builder.append(name).append('=').append(rendered);
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
