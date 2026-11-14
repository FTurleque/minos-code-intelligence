package com.minos.storage.local.store;

import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticProjection;
import com.minos.semantic.SemanticProjectionStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projection cache in a file: published in one atomic step, bound to its project, refused when it was cut or
 * altered, and never left half written by a preparation that does not end.
 */
class FileSemanticProjectionStoreTest {

    private static final String PROJECT = "0b8f6f0e-0000-4000-8000-000000000001";

    @TempDir
    Path temp;

    @Test
    void aPublishedProjectionIsReadBackWholeByAnyStoreOfTheSameRoot() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        SemanticProjection projection = projection(PROJECT, 500, 0.25f);

        publish(store, projection);

        assertEquals(Optional.of(projection), store.read(PROJECT));
        assertEquals(Optional.of(projection), new FileSemanticProjectionStore(temp.resolve("projections")).read(PROJECT));
        assertEquals(store.read(PROJECT).orElseThrow().points().getFirst(), projection.points().getFirst());
    }

    @Test
    void twoPublicationsOfTheSameProjectionWriteTheSameBytes() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        Path file = temp.resolve("projections").resolve(PROJECT).resolve(FileSemanticProjectionStore.FILE);

        publish(store, projection(PROJECT, 40, 0.5f));
        byte[] first = Files.readAllBytes(file);
        publish(store, projection(PROJECT, 12, -0.5f));
        publish(store, projection(PROJECT, 40, 0.5f));

        assertArrayEquals(first, Files.readAllBytes(file));
    }

    @Test
    void readingAProjectWithoutProjectionCreatesNothing() throws Exception {
        Path root = temp.resolve("never-created");
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(root);

        assertEquals(Optional.empty(), store.read(PROJECT));
        assertFalse(Files.exists(root));
    }

    @Test
    void aPreparationThatEndsWithoutPublishingLeavesThePublishedProjectionAndNoPartialFile() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        SemanticProjection published = projection(PROJECT, 30, 0.5f);
        publish(store, published);

        try (SemanticProjectionStore.Preparation interrupted = store.prepare(PROJECT)) {
            assertEquals(Optional.of(published), store.read(PROJECT), "the previous projection stays readable");
        }

        assertEquals(Optional.of(published), store.read(PROJECT));
        assertEquals(Set.of(FileSemanticProjectionStore.FILE, FileSemanticProjectionStore.LOCK_FILE), entries());
    }

    @Test
    void aRefusedPublicationLeavesThePublishedProjectionAndNoOtherFile() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        SemanticProjection published = projection(PROJECT, 30, 0.5f);
        publish(store, published);
        SemanticProjection foreign = projection("0b8f6f0e-0000-4000-8000-000000000002", 5, 0.5f);

        try (SemanticProjectionStore.Preparation preparation = store.prepare(PROJECT)) {
            assertThrows(IllegalArgumentException.class, () -> preparation.publish(foreign));
        }

        assertEquals(Optional.of(published), store.read(PROJECT));
        assertEquals(Set.of(FileSemanticProjectionStore.FILE, FileSemanticProjectionStore.LOCK_FILE), entries());
    }

    @Test
    void aPreparationInterruptedBeforeItsFirstPublicationLeavesNoReadableProjection() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));

        store.prepare(PROJECT).close();

        assertEquals(Optional.empty(), store.read(PROJECT));
        assertEquals(Set.of(FileSemanticProjectionStore.LOCK_FILE), entries());
    }

    @Test
    void anAlteredCutOrForeignFileIsRefusedWithoutNamingItsLocation() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        publish(store, projection(PROJECT, 30, 0.5f));
        Path file = temp.resolve("projections").resolve(PROJECT).resolve(FileSemanticProjectionStore.FILE);
        byte[] whole = Files.readAllBytes(file);

        byte[] altered = whole.clone();
        altered[whole.length / 2] ^= 0x01;
        List<byte[]> broken = List.of(altered, Arrays.copyOf(whole, whole.length - 5),
                Arrays.copyOf(whole, whole.length + 3), Arrays.copyOf(whole, 20), new byte[0], unknownVersion(whole));
        for (byte[] content : broken) {
            Files.write(file, content);
            FileSemanticProjectionStore fresh = new FileSemanticProjectionStore(temp.resolve("projections"));
            SemanticProjectionStore.UnreadableProjectionException refused = assertThrows(
                    SemanticProjectionStore.UnreadableProjectionException.class, () -> fresh.read(PROJECT));
            assertEquals("the stored projection is unreadable; prepare it again", refused.getMessage());
            assertFalse(refused.getMessage().contains(temp.toString()));
        }

        String other = "0b8f6f0e-0000-4000-8000-000000000003";
        Files.createDirectories(temp.resolve("projections").resolve(other));
        Files.write(temp.resolve("projections").resolve(other).resolve(FileSemanticProjectionStore.FILE), whole);
        assertThrows(SemanticProjectionStore.UnreadableProjectionException.class, () -> store.read(other));
    }

    @Test
    void aRepairedFileIsReadAgainAndAnUnreadableOneCanBeReplacedByANewPreparation() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        publish(store, projection(PROJECT, 30, 0.5f));
        Path file = temp.resolve("projections").resolve(PROJECT).resolve(FileSemanticProjectionStore.FILE);
        Files.write(file, new byte[]{1, 2, 3});
        assertThrows(SemanticProjectionStore.UnreadableProjectionException.class, () -> store.read(PROJECT));

        SemanticProjection next = projection(PROJECT, 8, -0.25f);
        publish(store, next);

        assertEquals(Optional.of(next), store.read(PROJECT));
    }

    @Test
    void aSecondPreparationOfTheSameProjectIsRefusedWhileOneIsHeld() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        ExecutorService other = Executors.newSingleThreadExecutor();
        try (SemanticProjectionStore.Preparation held = store.prepare(PROJECT)) {
            Throwable refused = other.submit(() -> {
                try {
                    store.prepare(PROJECT).close();
                    return null;
                } catch (IOException failure) {
                    return failure;
                }
            }).get(30, TimeUnit.SECONDS);
            assertInstanceOf(SemanticProjectionStore.PreparationInProgressException.class, refused);
        } finally {
            other.shutdownNow();
        }

        store.prepare(PROJECT).close();
    }

    @Test
    void whatAKilledPreparationLeftBehindIsRemovedByTheNextOne() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        SemanticProjection published = projection(PROJECT, 30, 0.5f);
        publish(store, published);
        Path directory = temp.resolve("projections").resolve(PROJECT);
        Files.write(directory.resolve("projection-v1-1234567890.tmp"), new byte[]{1, 2, 3});
        Files.write(directory.resolve("notes.tmp"), new byte[]{4});

        store.prepare(PROJECT).close();

        assertEquals(Set.of(FileSemanticProjectionStore.FILE, FileSemanticProjectionStore.LOCK_FILE, "notes.tmp"),
                entries());
        assertEquals(Optional.of(published), store.read(PROJECT));
    }

    @Test
    void aLeaseThatCannotBeTakenForAnotherReasonThanABusyOneIsNotReportedAsARunningPreparation() throws Exception {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));
        Files.createDirectories(temp.resolve("projections").resolve(PROJECT)
                .resolve(FileSemanticProjectionStore.LOCK_FILE));

        IOException failure = assertThrows(IOException.class, () -> store.prepare(PROJECT));

        assertFalse(failure instanceof SemanticProjectionStore.PreparationInProgressException,
                "a lock file that is not a file is not a running preparation: " + failure);
    }

    @Test
    void aProjectEntryThatIsALinkToAnotherTreeIsNeverRead() throws Exception {
        FileSemanticProjectionStore elsewhere = new FileSemanticProjectionStore(temp.resolve("elsewhere"));
        publish(elsewhere, projection(PROJECT, 5, 0.5f));
        Path root = Files.createDirectories(temp.resolve("projections"));
        boolean linked;
        try {
            Files.createSymbolicLink(root.resolve(PROJECT), temp.resolve("elsewhere").resolve(PROJECT));
            linked = true;
        } catch (IOException | UnsupportedOperationException | SecurityException refused) {
            linked = false;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(linked, "this account cannot create a symbolic link");

        assertThrows(SemanticProjectionStore.UnreadableProjectionException.class,
                () -> new FileSemanticProjectionStore(root).read(PROJECT));
    }

    @Test
    void aProjectIdentifierThatIsNotAPlainNameNeverReachesTheFileSystem() {
        FileSemanticProjectionStore store = new FileSemanticProjectionStore(temp.resolve("projections"));

        assertThrows(IllegalArgumentException.class, () -> store.read("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.read(".."));
        assertThrows(IllegalArgumentException.class, () -> store.prepare("a/b"));
        assertThrows(IllegalArgumentException.class, () -> store.read(" "));
        assertFalse(Files.exists(temp.resolve("projections")));
    }

    private static void publish(SemanticProjectionStore store, SemanticProjection projection) throws IOException {
        try (SemanticProjectionStore.Preparation preparation = store.prepare(projection.index().projectId())) {
            preparation.publish(projection);
        }
    }

    private Set<String> entries() throws IOException {
        try (Stream<Path> listed = Files.list(temp.resolve("projections").resolve(PROJECT))) {
            return Set.copyOf(listed.map(path -> path.getFileName().toString()).toList());
        }
    }

    private static byte[] unknownVersion(byte[] whole) {
        byte[] changed = whole.clone();
        changed[7] = 9;
        return changed;
    }

    private static SemanticProjection projection(String projectId, int points, float y) {
        List<SemanticProjection.Point> projected = new ArrayList<>(points);
        for (int number = 0; number < points; number++) {
            projected.add(new SemanticProjection.Point(String.format(Locale.ROOT, "chunk:é-%05d", number),
                    number % 2 == 0 ? SemanticDocumentKind.CHUNK : SemanticDocumentKind.SYMBOL,
                    (float) number / points, y));
        }
        return new SemanticProjection(
                new SemanticProjection.IndexIdentity(projectId, "snapshot-1", "local-hash", "hash-v1", 256, "f".repeat(64)),
                new SemanticProjection.Method("pca", 1, "components=2", 42L),
                new SemanticProjection.Selection(Set.of(SemanticDocumentKind.CHUNK, SemanticDocumentKind.SYMBOL), 1_000),
                points * 3, points * 2, List.of(0.6, 0.25), projected);
    }
}
