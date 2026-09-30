package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.git.GitIntelligence;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ProjectImpactQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Caractérisation (Q11) : les invocations valides d'aujourd'hui, avec les arguments exacts que
 * chaque commande transmet à son service. Écrit AVANT la migration vers {@link CliOptions} : une
 * invocation valide ne doit jamais devenir refusée, ni changer de sens.
 *
 * <p>Chaque service est un enregistreur qui échoue au premier appel : la commande sort alors en 1
 * (« failed: reached ») et la liste des appels dit ce que l'analyse a compris.</p>
 */
class CliValidInvocationsTest {

    @TempDir Path home;

    private final List<String> calls = new ArrayList<>();

    private <T> T recorder(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return method.getName().equals("toString") ? type.getSimpleName() : null;
            }
            calls.add(method.getName() + Arrays.deepToString(arguments == null ? new Object[0] : arguments));
            throw new IllegalStateException("reached");
        }));
    }

    private MinosCli cli() {
        ProjectOperations projects = new ProjectOperations() {
            @Override public ProjectView addProject(Path rootPath, String displayName) {
                calls.add("addProject[" + rootPath + ", " + displayName + "]");
                throw new IllegalStateException("reached");
            }
            @Override public List<ProjectView> listProjects() {
                calls.add("listProjects[]");
                throw new IllegalStateException("reached");
            }
            @Override public ProjectView inspectProject(String projectIdentifier) {
                calls.add("inspectProject[" + projectIdentifier + "]");
                throw new IllegalStateException("reached");
            }
            @Override public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                                          String providerVersion, String moduleId, String snapshotId) {
                calls.add("importScip[" + projectIdentifier + ", " + indexFile + ", " + providerId + ", "
                        + providerVersion + ", " + moduleId + ", " + snapshotId + "]");
                throw new IllegalStateException("reached");
            }
        };
        return MinosCli.builder(recorder(ProjectSymbolQuery.class))
                .projectOperations(projects)
                .architectureQuery(recorder(ProjectArchitectureQuery.class))
                .impactQuery(recorder(ProjectImpactQuery.class))
                .autonomousOperations(recorder(AutonomousIndexOperations.class))
                .remoteIndexOperations(recorder(RemoteIndexOperations.class))
                .nexusExportCommand(new NexusExportCommand(root -> {
                    calls.add("export[" + root + "]");
                    throw new IllegalStateException("reached");
                }))
                .build();
    }

    private void assertReaches(String invocation, String... expectedCalls) throws Exception {
        calls.clear();
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int code = cli().run(invocation.split(" "), output, error);
        assertEquals(List.of(expectedCalls), calls, invocation + " -> " + error);
        assertEquals(1, code, invocation + " -> " + error);
        assertFalse(error.toString().contains("Usage:"), invocation + " -> " + error);
    }

    @Test
    void architecture() throws Exception {
        assertReaches("architecture p", "getArchitectureIntelligence[p]");
        assertReaches("architecture p --module core", "getModuleContext[p, core]");
        assertReaches("architecture p --format JSON", "getArchitectureIntelligence[p]");
        assertReaches("architecture p --format mermaid --module core", "getArchitectureIntelligence[p]");
        assertReaches("architecture p --module core --format dot", "getArchitectureIntelligence[p]");
    }

    @Test
    void impact() throws Exception {
        assertReaches("impact p S", "analyzeImpact[p, " + new ImpactAnalysisRequest("S", 4, 200) + "]");
        assertReaches("impact p S --depth 2 --limit 5 --format json",
                "analyzeImpact[p, " + new ImpactAnalysisRequest("S", 2, 5) + "]");
        assertReaches("impact p S --limit 10000 --depth 32",
                "analyzeImpact[p, " + new ImpactAnalysisRequest("S", 32, 10_000) + "]");
        assertReaches("impact p S --format Text", "analyzeImpact[p, " + new ImpactAnalysisRequest("S", 4, 200) + "]");
    }

    @Test
    void project() throws Exception {
        assertReaches("project add work", "addProject[work, work]");
        assertReaches("project add work --name Demo --format json", "addProject[work, Demo]");
        assertReaches("project add work --format JSON --name -odd", "addProject[work, -odd]");
        assertReaches("project list", "listProjects[]");
        assertReaches("project list --format JSON", "listProjects[]");
        assertReaches("project inspect p", "inspectProject[p]");
        assertReaches("project inspect p --format json", "inspectProject[p]");
        assertReaches("inspect p --format text", "inspectProject[p]");
        assertReaches("index-status p --format json", "inspectProject[p]");
    }

    @Test
    void importScip() throws Exception {
        assertReaches("import-scip p --file f.scip --provider x", "importScip[p, f.scip, x, null, null, null]");
        assertReaches("import-scip p --provider x --file f.scip --snapshot s --module m --provider-version 1 --format json",
                "importScip[p, f.scip, x, 1, m, s]");
        assertReaches("index p --scip f.scip --provider x --provider-version 1 --module m --snapshot s",
                "importScip[p, f.scip, x, 1, m, s]");
    }

    @Test
    void index() throws Exception {
        assertReaches("index p", "execute[p, null, false, RESUME]");
        assertReaches("index p --provider scip-java --force-full --format json",
                "execute[p, scip-java, true, RESUME]");
        assertReaches("index p --no-resume", "execute[p, null, false, NO_RESUME]");
        assertReaches("index p --resume-only", "execute[p, null, false, RESUME_ONLY]");
        assertReaches("index p --dry-run", "plan[p, null, false]");
        assertReaches("index p --force-full --dry-run --format json", "plan[p, null, true]");
        assertReaches("index p --dry-run --provider scip-go", "plan[p, scip-go, false]");
    }

    @Test
    void tools() throws Exception {
        assertReaches("tools list", "providers[]");
        assertReaches("tools list --format JSON", "providers[]");
        assertReaches("tools verify", "providers[]");
        assertReaches("tools verify --all --format json", "providers[]");
        assertReaches("tools install scip-java", "installProvider[scip-java]");
        assertReaches("tools install scip-java --format json", "installProvider[scip-java]");
    }

    @Test
    void remote() throws Exception {
        String commit = "0123456789012345678901234567890123456789";
        calls.clear();
        assertEquals(1, cli().run(("remote materialize https://github.com/a/b --ref main --commit " + commit
                + " --subdir s --credential-env TOK --format json").split(" "), new StringBuilder(), new StringBuilder()));
        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().startsWith("materialize["), calls.toString());
        assertTrue(calls.getFirst().contains("main") && calls.getFirst().contains(commit), calls.toString());

        calls.clear();
        assertEquals(1, cli().run(("remote index https://github.com/a/b --ref main --commit " + commit
                + " --name Demo --worker-network DENY --worker w1 --provider scip-java").split(" "),
                new StringBuilder(), new StringBuilder()));
        assertEquals(1, calls.size());
        assertTrue(calls.getFirst().endsWith(", Demo, scip-java, w1, DENY]"), calls.toString());
    }

    @Test
    void nexusExport() throws Exception {
        assertReaches("nexus-export --root /work/demo", "export[" + Path.of("/work/demo") + "]");
    }

    @Test
    void gitActivityTransmitsItsBoundsToTheService() throws Exception {
        List<GitIntelligence.ActivityQuery> queries = new ArrayList<>();
        ProjectOperations projects = new ProjectOperations() {
            @Override public ProjectView addProject(Path rootPath, String displayName) { throw new UnsupportedOperationException(); }
            @Override public List<ProjectView> listProjects() { throw new UnsupportedOperationException(); }
            @Override public ProjectView inspectProject(String projectIdentifier) {
                return new ProjectView("id-1", "demo", home.toString(), true, List.of(), List.of(), 0, "READY",
                        null, null, null, null);
            }
            @Override public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                                          String providerVersion, String moduleId, String snapshotId) {
                throw new UnsupportedOperationException();
            }
        };
        GitIntelligence git = new GitIntelligence() {
            @Override public RepositoryView inspect(Path projectRoot) { throw new UnsupportedOperationException(); }
            @Override public ActivityReport analyze(Path projectRoot, ActivityQuery query) {
                queries.add(query);
                throw new IllegalStateException("reached");
            }
        };
        MinosCli cli = MinosCli.builder(recorder(ProjectSymbolQuery.class))
                .projectOperations(projects).gitIntelligence(git).build();

        assertEquals(1, cli.run(("git-activity p --days 5 --max-commits 10 --max-files 20 --zone-depth 3 --format json")
                .split(" "), new StringBuilder(), new StringBuilder()));
        assertEquals(1, cli.run("git-activity p".split(" "), new StringBuilder(), new StringBuilder()));
        assertEquals(1, cli.run("git-activity p --days 3650 --max-commits 10000 --max-files 10000 --zone-depth 8"
                .split(" "), new StringBuilder(), new StringBuilder()));

        assertEquals(3, queries.size());
        assertEquals(10, queries.get(0).maxCommits());
        assertEquals(20, queries.get(0).maxFiles());
        assertEquals(3, queries.get(0).zoneDepth());
        assertTrue(queries.get(0).since().isBefore(Instant.now().minus(Duration.ofDays(5)).plusSeconds(5)));
        assertEquals(500, queries.get(1).maxCommits());
        assertEquals(2, queries.get(1).zoneDepth());
        assertEquals(10_000, queries.get(2).maxCommits());
        assertEquals(8, queries.get(2).zoneDepth());
    }

    /**
     * Commandes dont les services sont des classes finales : l'analyse passe si le code n'est pas 2,
     * et, pour {@code ide} (dont un échec de service sortait 2 avant ce lot), si la sortie d'erreur ne
     * réclame pas l'usage.
     */
    @Test
    void invocationsOfTheCommandsBackedByFinalServicesAreAccepted() throws Exception {
        MinosApplication application = MinosApplication.builder(home.resolve("home")).build();
        List<String> accepted = List.of(
                "runtime sessions p --limit 5 --format json",
                "runtime report p --session s --limit 10 --format JSON",
                "runtime symbol p --symbol x --session s --limit 3",
                "runtime import p --file f.tsv --format json",
                "providers",
                "providers scip-java",
                "providers --format json",
                "providers scip-java --format JSON",
                "providers --format text scip-java",
                "semantic status p --format json",
                "hybrid status p",
                "ide handshake",
                "ide handshake --format text",
                "ide program-graph p --max-nodes 10 --max-edges 20 --format json",
                "ide impact-v2 p S --max-depth 3 --max-results 5",
                "ide security-paths p --source-node n --max-depth 2 --max-results 3",
                "ide semantic-index-status p",
                "ide semantic-index-sync p --format JSON",
                "ide semantic-search p -leading-dash --limit 3 --minimum-score 0.5 --format json",
                "ide hybrid-search p query --limit 3",
                "ide hybrid-context p query --max-documents 2 --max-tokens 200 --max-tokens-per-document 50");
        for (String invocation : accepted) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            int code = MinosCliRunner.run(application, invocation.split(" "), output, error);
            String context = invocation + " -> " + code + " " + error;
            if (invocation.startsWith("ide ") && !invocation.startsWith("ide handshake")) {
                assertFalse(error.toString().contains("Usage:"), context);
                assertFalse(error.toString().startsWith("error: unsupported option")
                        || error.toString().startsWith("error: missing value")
                        || error.toString().contains("must be an integer"), context);
            } else {
                assertNotEquals(2, code, context);
            }
        }
    }
}
