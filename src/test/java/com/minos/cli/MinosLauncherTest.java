package com.minos.cli;

import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.EvidenceType;
import com.minos.domain.InformationNature;
import com.minos.domain.OccurrenceRole;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.RelationshipKind;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosLauncherTest {

    @Test
    void runsFindSymbolAgainstAReopenedLocalHome(@TempDir Path root) throws IOException {
        RegisteredProject project = prepareHome(root);
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = MinosLauncher.run(root, new String[]{
                "find-symbol",
                project.displayName(),
                "GreetingService",
                "--format", "json"
        }, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertTrue(output.toString().contains("\"qualifiedName\":\"com.minos.GreetingService\""));
        assertEquals("", error.toString());
    }

    @Test
    void reopenedFullSnapshotServesUsagesAndRelationships(@TempDir Path root) throws Exception {
        RegisteredProject project = prepareKnowledgeHome(root);
        StringBuilder usageOutput = new StringBuilder();
        StringBuilder relationshipOutput = new StringBuilder();

        int usageExit = MinosLauncher.run(root, new String[]{
                "find-usages", project.id().toString(), "symbol-greeting-port", "--format", "json"
        }, usageOutput, new StringBuilder());
        int relationshipExit = MinosLauncher.run(root, new String[]{
                "find-implementations", project.id().toString(), "symbol-greeting-port",
                "--format", "json"
        }, relationshipOutput, new StringBuilder());

        assertEquals(0, usageExit);
        assertTrue(usageOutput.toString().contains("\"count\":1"), usageOutput.toString());
        assertTrue(usageOutput.toString().contains("\"id\":\"occ-port-usage\""));
        assertEquals(0, relationshipExit);
        assertTrue(relationshipOutput.toString().contains("\"count\":1"),
                relationshipOutput.toString());
        assertTrue(relationshipOutput.toString().contains(
                "\"id\":\"symbol-greeting-service\""));
    }

    @Test
    void aNewJavaProcessReadsM3RelationshipsFromTheSameActiveSnapshot(
            @TempDir Path root
    ) throws Exception {
        RegisteredProject project = prepareKnowledgeHome(root);
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                Path.of("target", "classes").toAbsolutePath().normalize().toString(),
                MinosLauncher.class.getName(),
                "find-implementations",
                project.id().toString(),
                "symbol-greeting-port",
                "--format",
                "json"
        );
        builder.environment().put(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, root.toString());
        Process process = builder.start();
        boolean completed = process.waitFor(20, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "MINOS child process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), error);
        assertTrue(output.contains("\"count\":1"), output);
        assertTrue(output.contains("\"kind\":\"IMPLEMENTS\""), output);
        assertTrue(output.contains("\"id\":\"symbol-greeting-service\""), output);
        assertEquals("", error);
    }

    @Test
    void aNewJavaProcessBuildsM4ContextFromTheRegisteredSource(@TempDir Path root)
            throws Exception {
        RegisteredProject project = prepareContextHome(root);
        Process process = javaProcess(root,
                "search", project.id().toString(), "GreetingService",
                "--depth", "0", "--max-tokens", "512", "--format", "json");
        boolean completed = process.waitFor(20, TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
        }
        assertTrue(completed, "MINOS M4 child process timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), error);
        assertTrue(output.contains("\"count\":1"), output);
        assertTrue(output.contains("class GreetingService"), output);
        assertTrue(output.contains("\"tokenBudget\":512"), output);
        assertEquals("", error);
    }

    @Test
    void resolvesHomeFromPropertyThenEnvironment(@TempDir Path root) {
        Properties properties = new Properties();
        properties.setProperty("user.home", root.resolve("fallback").toString());
        properties.setProperty(MinosLauncher.HOME_SYSTEM_PROPERTY, root.resolve("property").toString());

        assertEquals(
                root.resolve("property"),
                MinosLauncher.resolveHome(
                        Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, root.resolve("env").toString()),
                        properties
                )
        );

        properties.remove(MinosLauncher.HOME_SYSTEM_PROPERTY);
        assertEquals(
                root.resolve("env"),
                MinosLauncher.resolveHome(
                        Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, root.resolve("env").toString()),
                        properties
                )
        );
    }

    @Test
    void helpDoesNotCreateTheLocalHome(@TempDir Path root) throws IOException {
        Path absentHome = root.resolve("absent").resolve("home");
        StringBuilder output = new StringBuilder();

        int exitCode = MinosLauncher.run(
                absentHome,
                new String[]{"--help"},
                output,
                new StringBuilder()
        );

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertTrue(output.toString().contains("Usage: minos <command>"));
        assertFalse(Files.exists(absentHome));
    }

    private static RegisteredProject prepareHome(Path home) throws IOException {
        Path projectRoot = Files.createDirectories(home.resolve("fixture-project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "fixture-project");
        new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")).publish(
                project.id(),
                "snapshot-fixture",
                List.of(greetingService(project))
        );
        return project;
    }

    private static RegisteredProject prepareKnowledgeHome(Path home) throws IOException {
        Path projectRoot = Files.createDirectories(home.resolve("fixture-project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "fixture-project");
        Symbol service = greetingService(project);
        Symbol port = greetingPort(project);
        SymbolLocation location = new SymbolLocation(
                "file-greeting-service", 7, 4, 7, 16, PositionEncoding.UTF16_CODE_UNITS);
        CodeEntityRef serviceRef = new CodeEntityRef(CodeEntityType.SYMBOL, service.id());
        CodeEntityRef portRef = new CodeEntityRef(CodeEntityType.SYMBOL, port.id());
        Relationship implementation = new Relationship(
                "rel-implementation", project.id().toString(), serviceRef, portRef, null,
                RelationshipKind.IMPLEMENTS, location, ResolutionStatus.RESOLVED,
                InformationNature.FACTUAL, null, service.origin(),
                List.of(new Evidence(
                        EvidenceType.TYPE_RELATIONSHIP, "fixture implementation",
                        serviceRef, portRef, location, 1.0))
        );
        SymbolOccurrence usage = new SymbolOccurrence(
                "occ-port-usage", project.id().toString(),
                new ResolvedSymbolReference(port.id()), location,
                Set.of(OccurrenceRole.REFERENCE), ResolutionStatus.RESOLVED,
                service.origin(), Set.of()
        );
        new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")).publish(
                project.id(), "snapshot-m3", List.of(service, port),
                List.of(usage), List.of(implementation)
        );
        return project;
    }

    private static RegisteredProject prepareContextHome(Path home) throws IOException {
        Path projectRoot = Files.createDirectories(home.resolve("context-project"));
        Path source = projectRoot.resolve("src/GreetingService.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "package fixture;", "", "class GreetingService {", "}"),
                StandardCharsets.UTF_8);
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "context-project");
        SymbolLocation location = new SymbolLocation(
                "src/GreetingService.java", 3, 6, 3, 21,
                PositionEncoding.UTF16_CODE_UNITS);
        Symbol symbol = new Symbol(
                "symbol-context-service",
                project.id() + "|java|CLASS|fixture.GreetingService",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(), "main", location.fileId(), null,
                SymbolKind.CLASS, "GreetingService", "fixture.GreetingService",
                null, "java", location, ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false, false, Set.of());
        new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")).publish(
                project.id(), "snapshot-context", List.of(symbol));
        return project;
    }

    private static Process javaProcess(Path home, String... arguments) throws IOException {
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        );
        List<String> command = new java.util.ArrayList<>(List.of(
                javaExecutable.toString(),
                "-cp",
                Path.of("target", "classes").toAbsolutePath().normalize().toString(),
                MinosLauncher.class.getName()
        ));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString());
        return builder.start();
    }

    private static Symbol greetingService(RegisteredProject project) {
        return new Symbol(
                "symbol-greeting-service",
                project.id() + "|java|CLASS|com.minos.GreetingService",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                "main",
                "file-greeting-service",
                null,
                SymbolKind.CLASS,
                "GreetingService",
                "com.minos.GreetingService",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }

    private static Symbol greetingPort(RegisteredProject project) {
        return new Symbol(
                "symbol-greeting-port",
                project.id() + "|java|INTERFACE|com.minos.GreetingPort",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(),
                "main",
                "file-greeting-port",
                null,
                SymbolKind.INTERFACE,
                "GreetingPort",
                "com.minos.GreetingPort",
                null,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false,
                false,
                Set.of()
        );
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
