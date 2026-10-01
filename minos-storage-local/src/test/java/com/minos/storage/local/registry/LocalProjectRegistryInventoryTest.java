package com.minos.storage.local.registry;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Q8 : une entrée de registre abîmée dégrade CETTE entrée. Chaque test abîme une entrée d'un registre de trois
 * projets et exige que les deux autres restent listés, que l'entrée abîmée soit nommée (sans chemin absolu) et
 * que rien ne disparaisse sans trace.
 */
class LocalProjectRegistryInventoryTest {

    private static final int PROJECT_COUNT = 3;
    private static final String PROJECTS = "projects";
    private static final String PROPERTIES = ".properties";
    private static final String CREATED_AT = "createdAt=.*";

    @TempDir
    Path temp;

    private Path storage;
    private LocalProjectRegistry registry;
    private List<RegisteredProject> registered;

    private void registerThreeProjects() throws IOException {
        storage = Files.createDirectories(temp.resolve("registry"));
        registry = new LocalProjectRegistry(storage);
        registered = new ArrayList<>();
        for (int index = 0; index < PROJECT_COUNT; index++) {
            Path root = Files.createDirectories(temp.resolve("project-" + index));
            registered.add(registry.registerProject(root, "Project " + index));
        }
    }

    private Path entryOf(RegisteredProject project) {
        return storage.resolve(PROJECTS).resolve(project.id() + PROPERTIES);
    }

    private static void rewrite(Path file, UnaryOperator<String> edit) {
        try {
            Files.writeString(file, edit.apply(Files.readString(file, StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private ProjectRegistry.Inventory assertOnlyTheFirstEntryIsDegraded(Consumer<Path> corruption) throws IOException {
        registerThreeProjects();
        RegisteredProject damaged = registered.getFirst();
        corruption.accept(entryOf(damaged));

        ProjectRegistry.Inventory inventory = new LocalProjectRegistry(storage).inventory();

        assertEquals(PROJECT_COUNT - 1, inventory.projects().size(), "the two healthy projects stay listed");
        assertFalse(inventory.projects().stream().anyMatch(project -> project.id().equals(damaged.id())));
        assertEquals(1, inventory.unreadable().size(), "the damaged entry is counted, not dropped");
        DegradedEntry entry = inventory.unreadable().getFirst();
        assertEquals(damaged.id().toString(), entry.entry(), "the entry names the project it could not read");
        assertPublic(entry);
        return inventory;
    }

    /** A degraded entry reaches the user: no absolute path, neither the registry's nor the temporary directory's. */
    private void assertPublic(DegradedEntry entry) {
        String text = entry.entry() + " " + entry.reason();
        assertFalse(text.contains(temp.toString()), text);
        assertFalse(text.contains(storage.toString()), text);
        assertFalse(PublicErrorMessages.looksSensitive(text), text);
    }

    @Test
    void anInvalidInstantDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file, text -> text.replaceAll(CREATED_AT, "createdAt=not-an-instant")));
    }

    @Test
    void aValueCarryingTerminalControlsNeverReachesTheReason() throws IOException {
        ProjectRegistry.Inventory inventory = assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file,
                text -> text.replaceAll(CREATED_AT, "createdAt=\u001b[2Jevil\u0007")));

        assertTrue(inventory.unreadable().getFirst().reason().chars().noneMatch(Character::isISOControl),
                "a damaged file cannot write escape sequences to the terminal");
    }

    @Test
    void anImpossibleUpdatedAtDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file, text -> text.replaceAll("updatedAt=.*", "updatedAt=2026-13-99")));
    }

    @Test
    void anInvalidUuidInTheContentDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file, text -> text.replaceAll("\nid=.*", "\nid=not-a-uuid")));
    }

    @Test
    void anInvalidWorkspaceIdDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file, text -> text.replaceAll("workspaceId=.*", "workspaceId=zzz")));
    }

    @Test
    void anIdentityThatDiffersFromTheFileNameDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file,
                text -> text.replaceAll("\nid=.*", "\nid=" + UUID.randomUUID())));
    }

    @Test
    void aMissingRequiredPropertyDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> rewrite(file, text -> text.replaceAll("displayName=.*", "")));
    }

    @Test
    void anEmptyFileDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> {
            try {
                Files.write(file, new byte[0]);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @Test
    void anOversizedFileDegradesOnlyItsEntry() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> {
            try {
                Files.write(file, new byte[200 * 1024]);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @Test
    void aDirectoryWhereAnEntryShouldBeIsCountedInsteadOfVanishing() throws IOException {
        assertOnlyTheFirstEntryIsDegraded(file -> {
            try {
                Files.delete(file);
                Files.createDirectory(file);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
    }

    @Test
    void aDanglingLinkWhereAnEntryShouldBeIsCountedInsteadOfVanishing() throws IOException {
        registerThreeProjects();
        RegisteredProject damaged = registered.getFirst();
        Path file = entryOf(damaged);
        Files.delete(file);
        try {
            Files.createSymbolicLink(file, temp.resolve("nowhere"));
        } catch (IOException | UnsupportedOperationException failure) {
            abort("symbolic links are not available on this machine");
        }

        ProjectRegistry.Inventory inventory = new LocalProjectRegistry(storage).inventory();

        assertEquals(PROJECT_COUNT - 1, inventory.projects().size());
        assertEquals(List.of(damaged.id().toString()),
                inventory.unreadable().stream().map(DegradedEntry::entry).toList());
    }

    @Test
    void aFileNameThatIsNotAnIdentityIsCountedUnderASafeName() throws IOException {
        registerThreeProjects();
        Files.writeString(storage.resolve(PROJECTS).resolve("not-a-uuid" + PROPERTIES), "id=x\n");

        ProjectRegistry.Inventory inventory = new LocalProjectRegistry(storage).inventory();

        assertEquals(PROJECT_COUNT, inventory.projects().size(), "the three real projects stay listed");
        assertEquals(1, inventory.unreadable().size());
        assertEquals("not-a-uuid", inventory.unreadable().getFirst().entry());
        assertPublic(inventory.unreadable().getFirst());
    }

    @Test
    void severalDamagedEntriesAreAllCountedAndTheHealthyOneSurvives() throws IOException {
        registerThreeProjects();
        rewrite(entryOf(registered.get(0)), text -> text.replaceAll(CREATED_AT, "createdAt=not-an-instant"));
        rewrite(entryOf(registered.get(1)), text -> text.replaceAll("\nid=.*", "\nid=not-a-uuid"));

        ProjectRegistry.Inventory inventory = new LocalProjectRegistry(storage).inventory();

        assertEquals(List.of(registered.get(2).id()), inventory.projects().stream().map(RegisteredProject::id).toList());
        assertEquals(List.of(registered.get(0).id().toString(), registered.get(1).id().toString()).stream().sorted().toList(),
                inventory.unreadable().stream().map(DegradedEntry::entry).sorted().toList());
        inventory.unreadable().forEach(this::assertPublic);
    }

    @Test
    void everyEntryDamagedStillReportsAnInventoryInsteadOfFailing() throws IOException {
        registerThreeProjects();
        for (RegisteredProject project : registered) {
            rewrite(entryOf(project), text -> text.replaceAll(CREATED_AT, "createdAt=x"));
        }

        ProjectRegistry.Inventory inventory = new LocalProjectRegistry(storage).inventory();

        assertTrue(inventory.projects().isEmpty());
        assertEquals(PROJECT_COUNT, inventory.unreadable().size());
    }

    @Test
    void aHealthyRegistryReportsNothingDegradedAndTheSameProjectsAsTheStrictListing() throws IOException {
        registerThreeProjects();

        ProjectRegistry.Inventory inventory = registry.inventory();

        assertTrue(inventory.unreadable().isEmpty(), "no degraded entry, no stray counter");
        assertEquals(registry.listProjects(), inventory.projects());
    }

    @Test
    void theStrictListingStillFailsOnADamagedEntrySoMutationsKeepFailingClosed() throws IOException {
        registerThreeProjects();
        rewrite(entryOf(registered.getFirst()), text -> text.replaceAll(CREATED_AT, "createdAt=not-an-instant"));

        LocalProjectRegistry reopened = new LocalProjectRegistry(storage);

        assertThrows(RuntimeException.class, reopened::listProjects);
    }

    @Test
    void aRegistryDirectoryThatCannotBeListedFailsAsAWholeBecauseThereIsNothingToReportByEntry() throws IOException {
        registerThreeProjects();
        Path projects = storage.resolve(PROJECTS);
        for (RegisteredProject project : registered) Files.delete(entryOf(project));
        Files.delete(projects);
        Files.writeString(projects, "not a directory");

        assertThrows(IOException.class, registry::inventory);
    }

    @Test
    void aPendingInterruptionDegradesNoEntryAndIsLeftPendingForTheCaller() throws IOException {
        registerThreeProjects();
        Thread.currentThread().interrupt();
        try {
            ProjectRegistry.Inventory inventory = registry.inventory();

            assertEquals(PROJECT_COUNT, inventory.projects().size());
            assertTrue(inventory.unreadable().isEmpty(), "an interruption is not a damaged entry");
            assertTrue(Thread.currentThread().isInterrupted(), "the interruption is still pending for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theInterProcessRegistryExposesTheSameInventoryUnderItsLease() throws IOException {
        registerThreeProjects();
        rewrite(entryOf(registered.getFirst()), text -> text.replaceAll(CREATED_AT, "createdAt=not-an-instant"));

        ProjectRegistry.Inventory inventory = new InterProcessLocalProjectRegistry(storage).inventory();

        assertEquals(PROJECT_COUNT - 1, inventory.projects().size());
        assertEquals(1, inventory.unreadable().size());
    }
}
