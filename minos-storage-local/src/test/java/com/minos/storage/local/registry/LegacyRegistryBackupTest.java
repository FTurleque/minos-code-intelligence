package com.minos.storage.local.registry;

import com.minos.io.PrivateLocalStorage;
import com.minos.registry.ProjectPathMapping;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rollback copy of a legacy registry entry (AUD-SEC-03): published exclusively from a private temporary, never
 * replacing a copy that exists, private, complete under a race between two migrations, and without a leftover.
 */
class LegacyRegistryBackupTest {

    private record Fixture(Path record, Path backup, LocalProjectRegistry migrated, RegisteredProject original) { }

    private static Fixture legacyEntry(Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path hostRoot = Files.createDirectories(temp.resolve("host-projects")).toRealPath();
        Path dockerRoot = Files.createDirectories(temp.resolve("docker-projects")).toRealPath();
        Path projectRoot = Files.createDirectories(hostRoot.resolve("legacy"));
        LocalProjectRegistry legacy = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject original = legacy.registerProject(projectRoot, "Legacy");
        Path record = home.resolve("registry/projects/" + original.id() + ".properties");
        ProjectPathMapping mapping = new ProjectPathMapping(hostRoot.toString(), dockerRoot.toString());
        new ProjectPathMappingStore(home).save(mapping);
        LocalProjectRegistry migrated = new LocalProjectRegistry(
                home.resolve("registry"), mapping, ProjectPathMapping.RuntimeLocation.NATIVE);
        return new Fixture(record, record.resolveSibling(record.getFileName() + ".m29-v1.bak"), migrated, original);
    }

    @Test
    void theBackupIsPrivateAndHoldsTheLegacyContent(@TempDir Path temp) throws Exception {
        Fixture fixture = legacyEntry(temp);
        String legacyContent = Files.readString(fixture.record());

        fixture.migrated().findProject(fixture.original().id()).orElseThrow();

        assertEquals(legacyContent, Files.readString(fixture.backup()), "the backup is the entry as it was");
        assertEquals(PrivateLocalStorage.Privacy.ENFORCED, PrivateLocalStorage.privacyOf(fixture.backup()));
        assertFalse(Files.readString(fixture.record()).contains("rootPath="));
    }

    @Test
    void aBackupWhichAlreadyExistsIsNeitherModifiedNorReplaced(@TempDir Path temp) throws Exception {
        Fixture fixture = legacyEntry(temp);
        Files.writeString(fixture.backup(), "sentinel: kept as it is", StandardCharsets.UTF_8);

        fixture.migrated().findProject(fixture.original().id()).orElseThrow();

        assertEquals("sentinel: kept as it is", Files.readString(fixture.backup()));
    }

    @Test
    void noTemporaryOfTheCopyIsLeftBehind(@TempDir Path temp) throws Exception {
        Fixture fixture = legacyEntry(temp);

        fixture.migrated().findProject(fixture.original().id()).orElseThrow();

        try (Stream<Path> siblings = Files.list(fixture.record().getParent())) {
            List<String> names = siblings.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().noneMatch(name -> name.endsWith(".tmp")), names.toString());
        }
    }

    @Test
    void twoMigrationsAtTheSameTimeLeaveOneCompleteBackup(@TempDir Path temp) throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            Path round = Files.createDirectories(temp.resolve("round-" + attempt));
            Fixture fixture = legacyEntry(round);
            String legacyContent = Files.readString(fixture.record());
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Callable<Object>> tasks = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    tasks.add(() -> fixture.migrated().findProject(fixture.original().id()));
                }
                for (Future<Object> done : pool.invokeAll(tasks)) {
                    try {
                        done.get(30, TimeUnit.SECONDS);
                    } catch (java.util.concurrent.ExecutionException failure) {
                        // The entry itself is rewritten by both: only the backup is judged here.
                        assertFalse(failure.getCause() instanceof java.nio.file.FileAlreadyExistsException,
                                "a lost race for the backup is not an error: " + failure.getCause());
                    }
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(legacyContent, Files.readString(fixture.backup()), "round " + attempt);
        }
    }

    @Test
    void aBackupRefusesALinkInPlaceOfTheEntry(@TempDir Path temp) throws Exception {
        Fixture fixture = legacyEntry(temp);
        Path outside = Files.writeString(temp.resolve("outside.properties"),
                Files.readString(fixture.record()), StandardCharsets.UTF_8);
        Files.delete(fixture.record());
        try {
            Files.createSymbolicLink(fixture.record(), outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "this platform/account cannot create symbolic links");
        }

        // A linked entry is not read as a registry entry: nothing is copied from the link target.
        try {
            fixture.migrated().findProject(fixture.original().id());
        } catch (IOException | RuntimeException refused) {
            // Refusing the linked entry is acceptable: what matters is that nothing was copied from it.
        }

        assertFalse(Files.exists(fixture.backup()), "no backup is made from a link");
    }

    @Test
    void aLegacyEntryBeyondTheByteLimitIsNotCopied(@TempDir Path temp) throws Exception {
        Fixture fixture = legacyEntry(temp);
        String padding = "#" + "x".repeat(1024 * 1024 + 16) + System.lineSeparator();
        Files.writeString(fixture.record(), padding + Files.readString(fixture.record()), StandardCharsets.UTF_8);

        try {
            fixture.migrated().findProject(fixture.original().id());
        } catch (IOException | RuntimeException refused) {
            // The entry is refused as a whole: what matters is that no oversized backup is made.
        }

        assertFalse(Files.exists(fixture.backup()), "an entry beyond the limit is never copied");
    }
}
