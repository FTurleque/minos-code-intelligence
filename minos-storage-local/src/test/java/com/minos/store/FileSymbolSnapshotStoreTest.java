package com.minos.store;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ProviderReference;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileSymbolSnapshotStoreTest {

    @Test
    void roundTripsOverloadsExternalAndUnresolvedSymbols(@TempDir Path root) throws IOException {
        UUID projectId = UUID.randomUUID();
        List<Symbol> source = symbols(projectId);
        FileSymbolSnapshotStore writer = new FileSymbolSnapshotStore(root);

        writer.publish(projectId, "snapshot-1", source);

        SymbolSnapshot loaded = new FileSymbolSnapshotStore(root)
                .loadActive(projectId)
                .orElseThrow();
        assertEquals("snapshot-1", loaded.snapshotId());
        assertEquals(4, loaded.symbols().size());
        assertEquals(
                List.of("external-clock", "method-int", "method-string", "unresolved-client"),
                loaded.symbols().stream().map(Symbol::id).toList()
        );

        Symbol overload = loaded.symbols().stream()
                .filter(symbol -> "method-string".equals(symbol.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("(java.lang.String)", overload.signature());
        assertEquals(12, overload.location().startLine());
        assertEquals(
                Set.of(new ProviderReference("fixture-provider", "opaque-method-\uD800string")),
                overload.providerReferences()
        );

        Symbol external = loaded.symbols().stream()
                .filter(Symbol::external)
                .findFirst()
                .orElseThrow();
        assertEquals(SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK, external.identityQuality());
        assertNull(external.location());

        Symbol unresolved = loaded.symbols().stream()
                .filter(symbol -> symbol.resolutionStatus() == ResolutionStatus.UNRESOLVED)
                .findFirst()
                .orElseThrow();
        assertEquals("MissingClient", unresolved.name());
    }

    @Test
    void atomicallyMovesTheActivePointerWithoutDeletingOlderSnapshots(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", List.of(symbols(projectId).get(0)));
        store.publish(projectId, "snapshot-2", List.of(symbols(projectId).get(1)));

        SymbolSnapshot active = new FileSymbolSnapshotStore(root)
                .loadActive(projectId)
                .orElseThrow();

        assertEquals("snapshot-2", active.snapshotId());
        assertEquals(List.of("method-string"), active.symbols().stream().map(Symbol::id).toList());
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            assertEquals(2, files.filter(path -> path.toString().endsWith(".symbols")).count());
        }
    }

    @Test
    void republishesTheSameLogicalIdWithoutOverwritingThePreviouslyPublishedFile(
            @TempDir Path root
    ) throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-stable", List.of(symbols(projectId).get(0)));
        store.publish(projectId, "snapshot-stable", List.of(symbols(projectId).get(1)));

        SymbolSnapshot active = store.loadActive(projectId).orElseThrow();

        assertEquals("snapshot-stable", active.snapshotId());
        assertEquals(List.of("method-string"), active.symbols().stream().map(Symbol::id).toList());
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            assertEquals(2, files.filter(path -> path.toString().endsWith(".symbols")).count());
        }
    }

    @Test
    void writesDeterministicSnapshotBytes(@TempDir Path root) throws IOException {
        UUID projectId = UUID.randomUUID();
        Path firstRoot = root.resolve("first");
        Path secondRoot = root.resolve("second");
        List<Symbol> symbols = symbols(projectId);
        new FileSymbolSnapshotStore(firstRoot).publish(projectId, "snapshot-1", symbols);
        new FileSymbolSnapshotStore(secondRoot).publish(
                projectId,
                "snapshot-1",
                symbols.reversed()
        );

        assertArrayEquals(
                snapshotBytes(firstRoot, projectId),
                snapshotBytes(secondRoot, projectId)
        );
    }

    @Test
    void detectsCorruptionBeforeDeserializingTheActiveSnapshot(@TempDir Path root)
            throws IOException {
        UUID projectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        store.publish(projectId, "snapshot-1", symbols(projectId));
        Path snapshotFile;
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            snapshotFile = files
                    .filter(path -> path.toString().endsWith(".symbols"))
                    .findFirst()
                    .orElseThrow();
        }
        Files.write(snapshotFile, new byte[]{0x01}, StandardOpenOption.APPEND);

        IOException exception = assertThrows(IOException.class, () -> store.loadActive(projectId));

        assertTrue(exception.getMessage().contains("checksum mismatch"));
    }

    @Test
    void rejectsCrossProjectAndDuplicateSymbols(@TempDir Path root) throws IOException {
        UUID projectId = UUID.randomUUID();
        UUID otherProjectId = UUID.randomUUID();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        Symbol foreign = symbols(otherProjectId).getFirst();

        assertThrows(
                IllegalArgumentException.class,
                () -> store.publish(projectId, "foreign", List.of(foreign))
        );
        Symbol duplicate = symbols(projectId).getFirst();
        assertThrows(
                IllegalArgumentException.class,
                () -> store.publish(projectId, "duplicates", List.of(duplicate, duplicate))
        );
    }

    static List<Symbol> symbols(UUID projectId) {
        return List.of(
                symbol(
                        projectId,
                        "method-int",
                        SymbolKind.METHOD,
                        "convert",
                        "com.minos.Converter.convert",
                        "(int)",
                        8,
                        ResolutionStatus.RESOLVED,
                        false,
                        "opaque-method-int"
                ),
                symbol(
                        projectId,
                        "method-string",
                        SymbolKind.METHOD,
                        "convert",
                        "com.minos.Converter.convert",
                        "(java.lang.String)",
                        12,
                        ResolutionStatus.RESOLVED,
                        false,
                        "opaque-method-\uD800string"
                ),
                symbol(
                        projectId,
                        "external-clock",
                        SymbolKind.CLASS,
                        "Clock",
                        "java.time.Clock",
                        null,
                        0,
                        ResolutionStatus.RESOLVED,
                        true,
                        "opaque-external-clock"
                ),
                symbol(
                        projectId,
                        "unresolved-client",
                        SymbolKind.CLASS,
                        "MissingClient",
                        "com.minos.MissingClient",
                        null,
                        0,
                        ResolutionStatus.UNRESOLVED,
                        true,
                        "opaque-missing-client"
                )
        );
    }

    private static Symbol symbol(
            UUID projectId,
            String id,
            SymbolKind kind,
            String name,
            String qualifiedName,
            String signature,
            int line,
            ResolutionStatus resolutionStatus,
            boolean external,
            String externalId
    ) {
        SymbolLocation location = line == 0
                ? null
                : new SymbolLocation(
                        "file-converter",
                        line,
                        4,
                        line,
                        20,
                        PositionEncoding.UTF16_CODE_UNITS
                );
        return new Symbol(
                id,
                projectId + "|java|" + kind + "|" + qualifiedName + "|" + id,
                external
                        ? SymbolIdentityQuality.PROVIDER_SCOPED_FALLBACK
                        : SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                projectId.toString(),
                external ? null : "main",
                external ? null : "file-converter",
                null,
                kind,
                name,
                qualifiedName,
                signature,
                "java",
                location,
                resolutionStatus,
                new Origin(
                        "fixture-provider",
                        "TEST",
                        "1.0",
                        "run-1",
                        OriginType.OTHER
                ),
                external,
                false,
                Set.of(new ProviderReference("fixture-provider", externalId))
        );
    }

    private static byte[] snapshotBytes(Path root, UUID projectId) throws IOException {
        try (var files = Files.list(root.resolve(projectId.toString()))) {
            Path snapshot = files
                    .filter(path -> path.toString().endsWith(".symbols"))
                    .findFirst()
                    .orElseThrow();
            return Files.readAllBytes(snapshot);
        }
    }
}
