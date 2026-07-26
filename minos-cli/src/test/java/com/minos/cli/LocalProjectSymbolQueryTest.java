package com.minos.cli;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.SymbolResult;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectSymbolQueryTest {

    @Test
    void reloadsTheActiveSnapshotAndPreservesOverloads(@TempDir Path root) throws IOException {
        Path registryRoot = root.resolve("registry");
        Path snapshotRoot = root.resolve("snapshots");
        RegisteredProject project = registerProject(registryRoot, root.resolve("project"), "converter");
        new FileSymbolSnapshotStore(snapshotRoot).publish(
                project.id(),
                "snapshot-1",
                overloads(project.id())
        );
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(registryRoot),
                new FileSymbolSnapshotStore(snapshotRoot)
        );

        List<SymbolResult> results = query.findSymbols(
                "converter",
                new SymbolSearchCriteria(
                        "convert",
                        "com.minos.Converter.convert",
                        SymbolKind.METHOD,
                        "main",
                        10
                )
        );

        assertEquals(2, results.size());
        assertEquals(List.of("(int)", "(java.lang.String)"),
                results.stream().map(SymbolResult::signature).toList());
        assertTrue(results.stream().allMatch(result -> project.id().toString().equals(result.projectId())));
        assertEquals(
                List.of("method-int", "method-string"),
                query.getFileSymbols("converter", "file-converter", 10)
                        .stream()
                        .map(SymbolResult::id)
                        .toList()
        );
    }

    @Test
    void resolvesAProjectByUuidAndRejectsMissingSnapshots(@TempDir Path root) throws IOException {
        Path registryRoot = root.resolve("registry");
        Path snapshotRoot = root.resolve("snapshots");
        RegisteredProject indexed = registerProject(
                registryRoot,
                root.resolve("indexed"),
                "indexed"
        );
        RegisteredProject empty = registerProject(registryRoot, root.resolve("empty"), "empty");
        new FileSymbolSnapshotStore(snapshotRoot).publish(
                indexed.id(),
                "snapshot-1",
                overloads(indexed.id())
        );
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(registryRoot),
                new FileSymbolSnapshotStore(snapshotRoot)
        );

        assertEquals(2, query.findSymbols(
                indexed.id().toString(),
                SymbolSearchCriteria.qualifiedName("com.minos.Converter.convert", 10)
        ).size());
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> query.findSymbols(
                        empty.id().toString(),
                        SymbolSearchCriteria.lexical("anything", 10)
                )
        );
        assertTrue(exception.getMessage().contains("no active symbol snapshot"));
    }

    @Test
    void rejectsUnknownAndAmbiguousDisplayNames(@TempDir Path root) throws IOException {
        Path registryRoot = root.resolve("registry");
        registerProject(registryRoot, root.resolve("first"), "duplicate");
        registerProject(registryRoot, root.resolve("second"), "duplicate");
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(registryRoot),
                new FileSymbolSnapshotStore(root.resolve("snapshots"))
        );

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> query.findSymbols("unknown", SymbolSearchCriteria.lexical("x", 10))
        );
        assertTrue(unknown.getMessage().contains("unknown project"));
        IllegalArgumentException ambiguous = assertThrows(
                IllegalArgumentException.class,
                () -> query.findSymbols("duplicate", SymbolSearchCriteria.lexical("x", 10))
        );
        assertTrue(ambiguous.getMessage().contains("ambiguous project name"));
    }

    private static RegisteredProject registerProject(
            Path registryRoot,
            Path projectRoot,
            String displayName
    ) throws IOException {
        Files.createDirectories(projectRoot);
        return new LocalProjectRegistry(registryRoot).registerProject(projectRoot, displayName);
    }

    private static List<Symbol> overloads(UUID projectId) {
        return List.of(
                method(projectId, "method-int", "(int)", "opaque-int"),
                method(projectId, "method-string", "(java.lang.String)", "opaque-string")
        );
    }

    private static Symbol method(
            UUID projectId,
            String id,
            String signature,
            String externalId
    ) {
        return new Symbol(
                id,
                projectId + "|java|METHOD|com.minos.Converter.convert|" + signature,
                SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                "main",
                "file-converter",
                null,
                SymbolKind.METHOD,
                "convert",
                "com.minos.Converter.convert",
                signature,
                "java",
                null,
                ResolutionStatus.RESOLVED,
                new Origin("fixture-provider", "TEST", "1.0", "run-1", OriginType.OTHER),
                false,
                false,
                Set.of(new ProviderReference("fixture-provider", externalId))
        );
    }
}
