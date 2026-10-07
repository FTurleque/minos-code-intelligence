package com.minos.storage.local.store;

import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Les magasins écrits par le code de {@code d9ae1005} (V1, V2, V2 avec surrogate isolé) restent lisibles après
 * l'introduction du format V3, octet pour octet intacts ; un nouvel import est promu en V3 (ADR 0046).
 *
 * <p>Les fixtures gardent les octets écrits par la base, sous un nom court ({@code snapshot.symbols},
 * {@code snapshot.knowledge}) : le nom d'origine, trop long pour Git sous Windows, est reconstitué à la copie,
 * tel que la base le formait ({@code snapshot-<logicalIdHash>-<sha256 du contenu><extension>}).</p>
 */
class SnapshotFormatCompatibilityTest {

    private static final String FIXTURES = "/snapshot-formats/base-d9ae1005/";

    @TempDir
    Path home;

    @Test
    void v1StoreWrittenByTheBaseStillOpensUntouched() throws Exception {
        Path root = copyFixture("v1-symbols");
        Map<Path, byte[]> before = snapshotBytes(root);

        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        CodeKnowledgeSnapshot loaded = store.loadActiveKnowledge(LegacySnapshotContent.V1_PROJECT).orElseThrow();

        assertEquals(new CodeKnowledgeSnapshot(LegacySnapshotContent.V1_PROJECT, LegacySnapshotContent.V1_SNAPSHOT,
                LegacySnapshotContent.v1Symbols(), List.of(), List.of()), loaded);
        assertEquals(1, store.loadActiveQueryView(LegacySnapshotContent.V1_PROJECT).orElseThrow().descriptor().formatVersion());
        assertUnchanged(before, root);
    }

    @Test
    void v2StoresWrittenByTheBaseStillOpenUntouchedWithEveryTrapString() throws Exception {
        assertLegacyV2Opens("v2-well-formed", LegacySnapshotContent.v2());
        assertLegacyV2Opens("v2-lone-surrogate", LegacySnapshotContent.v2WithLoneSurrogate());
    }

    @Test
    void existingV2HomeOpensThenANewImportIsPromotedInV3() throws Exception {
        Path root = copyFixture("v2-well-formed");
        UUID project = LegacySnapshotContent.V2_PROJECT;
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);
        assertEquals(LegacySnapshotContent.v2(), store.loadActiveKnowledge(project).orElseThrow());
        CodeKnowledgeSnapshot next = LegacySnapshotContent.knowledge(project, "next-import", "après V3");

        store.publish(project, next.snapshotId(), next.symbols(), next.occurrences(), next.relationships());

        SnapshotDescriptor pointer = new ActiveSnapshotRepository(new SnapshotRepository(root)).read(project).orElseThrow();
        assertEquals(3, pointer.formatVersion());
        Path v3File = root.resolve(project.toString()).resolve(pointer.fileName());
        assertEquals(3, KnowledgeSnapshotCodecs.formatVersionOf(v3File));
        assertEquals(new SnapshotIntegrityService().checksum(v3File), pointer.sha256());
        assertEquals(next, new FileSymbolSnapshotStore(root).loadActiveKnowledge(project).orElseThrow());
        assertEquals(2, new SnapshotRepository(root).listSnapshotFiles(project).size(),
                "the legacy V2 file and the new V3 file are both recognized");
        SnapshotRetentionService.RetentionResult retention = new SnapshotCompactionService(root)
                .compact(project, new SnapshotRetentionPolicy(1));
        assertEquals(pointer.fileName(), retention.activeFileName());
        assertEquals(1, retention.retainedHistoricalFiles().size(), "the V2 file is a valid historical snapshot");
    }

    @Test
    void reimportingTheSameSnapshotAfterTheFormatChangeSucceeds() throws Exception {
        Path root = copyFixture("v2-well-formed");
        UUID project = LegacySnapshotContent.V2_PROJECT;
        CodeKnowledgeSnapshot same = LegacySnapshotContent.v2();
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);

        store.publish(project, same.snapshotId(), same.symbols(), same.occurrences(), same.relationships());

        SnapshotDescriptor pointer = new ActiveSnapshotRepository(new SnapshotRepository(root)).read(project).orElseThrow();
        assertEquals(3, pointer.formatVersion());
        assertEquals(same, new FileSymbolSnapshotStore(root).loadActiveKnowledge(project).orElseThrow());
        String legacyName = legacyFileName("v2-well-formed");
        String sameIdPrefix = "snapshot-" + new SnapshotIntegrityService().logicalIdHash(same.snapshotId()) + "-";
        assertTrue(legacyName.startsWith(sameIdPrefix) && pointer.fileName().startsWith(sameIdPrefix),
                "file names still derive from the unchanged logical id hash: " + legacyName + " / " + pointer.fileName());
    }

    @Test
    void aSnapshotWithAnUnpairedSurrogateIsRepublishedInV2AndKeptExactly() throws Exception {
        Path root = copyFixture("v2-lone-surrogate");
        UUID project = LegacySnapshotContent.V2_LONE_SURROGATE_PROJECT;
        CodeKnowledgeSnapshot orphan = LegacySnapshotContent.knowledge(project, "orphan-again",
                LegacySnapshotContent.LONE_SURROGATE);
        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);

        store.publish(project, orphan.snapshotId(), orphan.symbols(), orphan.occurrences(), orphan.relationships());

        SnapshotDescriptor pointer = new ActiveSnapshotRepository(new SnapshotRepository(root)).read(project).orElseThrow();
        assertEquals(2, pointer.formatVersion());
        CodeKnowledgeSnapshot loaded = new FileSymbolSnapshotStore(root).loadActiveKnowledge(project).orElseThrow();
        assertEquals(orphan, loaded);
        assertTrue(loaded.occurrences().get(1).symbolRef().toString().contains("\uD800"),
                "the unpaired surrogate survives, never degraded to '?'");
    }

    private void assertLegacyV2Opens(String fixture, CodeKnowledgeSnapshot expected) throws Exception {
        Path root = copyFixture(fixture);
        Map<Path, byte[]> before = snapshotBytes(root);

        FileSymbolSnapshotStore store = new FileSymbolSnapshotStore(root);

        assertEquals(expected, store.loadActiveKnowledge(expected.projectId()).orElseThrow());
        assertEquals(2, store.loadActiveQueryView(expected.projectId()).orElseThrow().descriptor().formatVersion());
        assertUnchanged(before, root);
    }

    private Path copyFixture(String name) throws IOException, URISyntaxException {
        Path source = Path.of(SnapshotFormatCompatibilityTest.class.getResource(FIXTURES + name).toURI());
        Path target = home.resolve(name);
        try (Stream<Path> files = Files.walk(source)) {
            for (Path file : files.sorted(Comparator.naturalOrder()).toList()) {
                Path copy = target.resolve(source.relativize(file).toString());
                if (Files.isDirectory(file)) Files.createDirectories(copy);
                else Files.copy(file, copy.resolveSibling(storedName(file)));
            }
        }
        return target;
    }

    /** Nom sous lequel la base avait publié ce fichier ; le pointeur actif le désigne. */
    private static String storedName(Path fixture) throws IOException {
        String name = fixture.getFileName().toString();
        if (!name.startsWith("snapshot.")) return name;
        String extension = name.substring("snapshot".length());
        String snapshotId = switch (fixture.getParent().getParent().getFileName().toString()) {
            case "v1-symbols" -> LegacySnapshotContent.V1_SNAPSHOT;
            case "v2-well-formed" -> LegacySnapshotContent.V2_SNAPSHOT;
            case "v2-lone-surrogate" -> LegacySnapshotContent.V2_LONE_SURROGATE_SNAPSHOT;
            default -> throw new IllegalStateException("unknown fixture " + fixture);
        };
        SnapshotIntegrityService integrity = new SnapshotIntegrityService();
        return "snapshot-" + integrity.logicalIdHash(snapshotId) + "-" + integrity.checksum(fixture) + extension;
    }

    private static String legacyFileName(String fixture) throws IOException, URISyntaxException {
        Path source = Path.of(SnapshotFormatCompatibilityTest.class.getResource(FIXTURES + fixture).toURI());
        try (Stream<Path> files = Files.walk(source)) {
            return storedName(files.filter(path -> path.getFileName().toString().equals("snapshot.knowledge"))
                    .findFirst().orElseThrow());
        }
    }

    private static Map<Path, byte[]> snapshotBytes(Path root) throws IOException {
        Map<Path, byte[]> bytes = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> regular = new ArrayList<>(files.filter(Files::isRegularFile).sorted().toList());
            for (Path file : regular) {
                try (InputStream input = Files.newInputStream(file)) {
                    bytes.put(root.relativize(file), input.readAllBytes());
                }
            }
        }
        return bytes;
    }

    private static void assertUnchanged(Map<Path, byte[]> before, Path root) throws IOException {
        Map<Path, byte[]> after = snapshotBytes(root);
        assertEquals(before.keySet(), after.keySet());
        for (Map.Entry<Path, byte[]> entry : before.entrySet()) {
            assertArrayEquals(entry.getValue(), after.get(entry.getKey()), "rewritten: " + entry.getKey());
        }
    }
}
