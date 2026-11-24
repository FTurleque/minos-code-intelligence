package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationTestAccess;
import com.minos.application.diagnostics.InternalFailureReporter;
import com.minos.io.InternalFailureJournal;
import com.minos.testsupport.LogCapture;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.dynamic.CorrelatedRuntimeObservation;
import com.minos.dynamic.CorrelatedRuntimeSession;
import com.minos.dynamic.RuntimeObservation;
import com.minos.dynamic.RuntimeObservationCompleteness;
import com.minos.dynamic.RuntimeObservationSession;
import com.minos.dynamic.RuntimeObservationType;
import com.minos.dynamic.RuntimeResolutionStatus;
import com.minos.dynamic.RuntimeSymbolReference;
import com.minos.dynamic.RuntimeSymbolResolution;
import com.minos.registry.RegisteredProject;
import com.minos.testsupport.WriteProtectedTree;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR 0017 : le plan de requête MCP est en lecture seule. Les fichiers Compose montent donc le répertoire de données
 * en lecture seule pour le service {@code minos-mcp} : aucun outil ne doit avoir besoin d'y écrire pour répondre.
 */
class MinosMcpWriteProtectedHomeTest {
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);
    private static final String INTERNAL_FAILURE = "MINOS tool execution failed";

    @TempDir
    Path temp;

    @Test
    void aSymbolQueryAnswersOnAWriteProtectedHomeAndLeavesItUntouched() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            seed(application);
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            try (WriteProtectedTree protectedHome = WriteProtectedTree.protect(home)) {
                Map<String, String> before = protectedHome.state();

                var result = McpToolCalls.call(tools, "minos_find_symbols",
                        Map.of("project", "fixture", "query", "Greeting"));

                assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
                assertTrue(McpToolCalls.text(result).contains("com.acme.GreetingService"), McpToolCalls.text(result));
                assertEquals(before, protectedHome.state(), "a query must not open, harden or create anything");
            }
        }
    }

    /** Un refus fonctionnel (symbole inconnu, mode équipe désactivé) est une réponse ; l'échec interne opaque non. */
    @Test
    void noToolFailsInternallyOnAWriteProtectedHome() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            seed(application);
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            try (WriteProtectedTree protectedHome = WriteProtectedTree.protect(home)) {
                Map<String, String> before = protectedHome.state();
                List<String> failed = new ArrayList<>();

                for (SyncToolSpecification specification : tools.specifications()) {
                    Map<String, Object> arguments = new HashMap<>();
                    Map<String, Object> schema = specification.tool().inputSchema();
                    if (schema.get("required") instanceof List<?> required) {
                        required.forEach(name -> arguments.put(name.toString(), "GreetingService"));
                    }
                    if (schema.get("properties") instanceof Map<?, ?> properties && properties.containsKey("project")) {
                        arguments.put("project", "fixture");
                    }
                    var result = McpToolCalls.call(tools, specification.tool().name(), arguments);
                    if (McpToolCalls.text(result).contains(INTERNAL_FAILURE)) failed.add(specification.tool().name());
                }

                assertEquals(List.of(), failed, "tools that fail internally when MINOS_HOME cannot be written");
                assertEquals(before, protectedHome.state(), "no tool may open, harden or create anything");
            }
        }
    }

    private void seed(MinosApplication application) throws Exception {
        RegisteredProject project = MinosApplicationTestAccess.projectRegistry(application).registerProject(
                Files.createDirectories(temp.resolve("project")), "fixture");
        MinosApplicationTestAccess.snapshotStore(application).publish(project.id(), "snapshot-read-only",
                List.of(new Symbol("GreetingService", "key:GreetingService", SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                        project.id().toString(), null, "src/GreetingService.java", null, SymbolKind.CLASS,
                        "GreetingService", "com.acme.GreetingService", null, "java", null,
                        ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of())),
                List.of(), List.of());
        // A project that has a runtime session reads it under its own lease: without one, the runtime tools
        // return before taking any lock and would prove nothing.
        RuntimeSymbolReference reference = new RuntimeSymbolReference("key:GreetingService", null, null, null);
        RuntimeObservation observation = new RuntimeObservation(
                RuntimeObservationType.SYMBOL_EXECUTION, reference, null, 3, 100);
        MinosApplicationTestAccess.runtimeObservationStore(application).save(new CorrelatedRuntimeSession(
                new RuntimeObservationSession(RuntimeObservationSession.FORMAT, "run-1", project.id(),
                        "snapshot-read-only", Instant.parse("2026-07-29T06:00:00Z"),
                        Instant.parse("2026-07-29T06:01:00Z"), "fixture", "1", "test",
                        RuntimeObservationCompleteness.PARTIAL, List.of(observation)),
                Instant.parse("2026-07-29T07:00:00Z"), "a".repeat(64),
                List.of(new CorrelatedRuntimeObservation(observation,
                        new RuntimeSymbolResolution(RuntimeResolutionStatus.RESOLVED, reference, "GreetingService",
                                "key:GreetingService", "com.acme.GreetingService", List.of(), false), null))));
    }

    @Test
    void theRuntimeSessionsAreListedOnAWriteProtectedHome() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            seed(application);
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            try (WriteProtectedTree protectedHome = WriteProtectedTree.protect(home)) {
                Map<String, String> before = protectedHome.state();

                var result = McpToolCalls.call(tools, "minos_runtime_sessions", Map.of("project", "fixture"));

                assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
                assertTrue(McpToolCalls.text(result).contains("run-1"), McpToolCalls.text(result));
                assertEquals(before, protectedHome.state(), "listing the sessions must not write anything");
            }
        }
    }

    /**
     * With the private diagnostic journal switched on, a failing tool still answers the opaque error, the directory
     * is left as found and the unavailability is said once: the journal is never a state of a read-only home.
     */
    @Test
    void anEnabledJournalOnAWriteProtectedHomeLeavesTheAnswersAndTheDirectoryUnchanged() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            seed(application);
            // A file where the journal directory would be: refused on every platform, as a read-only mount refuses it.
            Files.writeString(home.resolve("logs"), "not a directory");
            MinosMcpTools tools = new MinosMcpTools(
                    failingBackend(new IllegalStateException("boom")),
                    new InternalFailureReporter("mcp", home,
                            Map.of(InternalFailureJournal.ENVIRONMENT_VARIABLE, "enabled"), new Properties()));

            try (WriteProtectedTree protectedHome = WriteProtectedTree.protect(home);
                 LogCapture journal = new LogCapture(InternalFailureJournal.class)) {
                Map<String, String> before = protectedHome.state();

                for (int call = 0; call < 2; call++) {
                    var result = McpToolCalls.call(tools, "minos_index_status", Map.of("project", "fixture"));
                    McpToolCalls.assertGenericToolError(McpToolCalls.text(result));
                }

                assertEquals(1, journal.warnings().size(), journal.warnings().toString());
                assertEquals(before, protectedHome.state(), "the journal must not open, harden or create anything");
            }
        }
    }

    private static MinosMcpBackend failingBackend(Throwable failure) {
        return (MinosMcpBackend) java.lang.reflect.Proxy.newProxyInstance(
                MinosMcpBackend.class.getClassLoader(),
                new Class<?>[]{MinosMcpBackend.class},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "failing-mcp-backend";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> null;
                        };
                    }
                    throw failure;
                });
    }
}
