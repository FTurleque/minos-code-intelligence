package com.minos.application.resolution;

import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.registry.UnreadableRegistryException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The resolution policy against an in-memory registry. The same policy is exercised end to end, against the
 * file-backed registry, by {@code ProjectResolverTest} in minos-bootstrap; this one keeps it covered where it lives.
 */
class ProjectResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-10T00:00:00Z");

    private final RegisteredProject alpha = project("alpha");
    private final RegisteredProject beta = project("beta");
    private final RegisteredProject twinOne = project("twin");
    private final RegisteredProject twinTwo = project("twin");

    @Test
    void aUuidReferenceResolvesTheProjectOfThatId() throws Exception {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha, beta)));

        assertEquals(beta, resolver.resolve(beta.id().toString()));
        assertEquals(beta, resolver.resolveById(beta.id()));
    }

    @Test
    void aNameReferenceResolvesTheProjectWithThatName() throws Exception {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha, beta)));

        assertEquals(alpha, resolver.resolve("alpha"));
        assertEquals(alpha, resolver.resolveByName("alpha"));
    }

    @Test
    void anUnknownUuidOrNameIsNotFound() {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha)));

        ProjectResolver.ResolutionException byId = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve(UUID.randomUUID().toString()));
        ProjectResolver.ResolutionException byName = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("missing"));
        assertEquals(ProjectResolver.ErrorCode.PROJECT_NOT_FOUND, byId.code());
        assertEquals(ProjectResolver.ErrorCode.PROJECT_NOT_FOUND, byName.code());
        assertEquals("missing", byName.reference());
        assertTrue(byName.candidateIds().isEmpty());
    }

    @Test
    void aNameSharedByTwoProjectsIsAmbiguousAndNamesBothCandidates() {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(twinOne, twinTwo)));

        ProjectResolver.ResolutionException ambiguous = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("twin"));
        assertEquals(ProjectResolver.ErrorCode.PROJECT_REFERENCE_AMBIGUOUS, ambiguous.code());
        assertEquals(List.of(twinOne.id(), twinTwo.id()), ambiguous.candidateIds());
        assertTrue(ambiguous.getMessage().contains("use its UUID"), ambiguous.getMessage());
    }

    @Test
    void aBlankNullOrOversizedReferenceIsInvalidAndNeverEchoed() {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha)));
        String oversized = "x".repeat(ProjectResolver.MAX_REFERENCE_UTF8_BYTES + 1);

        for (String reference : new String[] {null, "", "   ", oversized}) {
            ProjectResolver.ResolutionException invalid = assertThrows(ProjectResolver.ResolutionException.class,
                    () -> resolver.resolve(reference));
            assertEquals(ProjectResolver.ErrorCode.INVALID_PROJECT_REFERENCE, invalid.code());
            assertEquals(invalid.getMessage(), invalid.publicMessage());
        }
        assertEquals(null, assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve(oversized)).reference());
    }

    @Test
    void thePublicMessageOfAnUnknownProjectDoesNotEchoASensitiveReference() {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha)));

        ProjectResolver.ResolutionException plain = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve("missing"));
        assertEquals("unknown project: missing", plain.publicMessage());

        String secret = "jdbc:postgresql://db.internal/minos?password=hunter2";
        ProjectResolver.ResolutionException sensitive = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve(secret));
        assertEquals(ProjectResolver.UNKNOWN_REFERENCE_NOT_SHOWN, sensitive.publicMessage());
    }

    @Test
    void thePublicMessageOfAnAmbiguousNameDoesNotEchoASensitiveName() {
        RegisteredProject first = project("jdbc:postgresql://db.internal/minos?password=hunter2");
        RegisteredProject second = project("jdbc:postgresql://db.internal/minos?password=hunter2");
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(first, second)));

        ProjectResolver.ResolutionException ambiguous = assertThrows(ProjectResolver.ResolutionException.class,
                () -> resolver.resolve(first.displayName()));
        assertEquals(ProjectResolver.AMBIGUOUS_REFERENCE_NOT_SHOWN, ambiguous.publicMessage());
    }

    @Test
    void listCandidatesAnswersByIdAndByNameWithoutFailingOnAbsence() throws Exception {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha, twinOne, twinTwo)));

        assertEquals(List.of(alpha), resolver.listCandidates(alpha.id().toString()));
        assertEquals(List.of(), resolver.listCandidates(UUID.randomUUID().toString()));
        assertEquals(List.of(twinOne, twinTwo), resolver.listCandidates("twin"));
        assertEquals(List.of(), resolver.listCandidates("missing"));
        assertThrows(ProjectResolver.ResolutionException.class, () -> resolver.listCandidates(" "));
    }

    @Test
    void tolerantResolutionReturnsTheProjectWithWhatCouldNotBeRead() throws Exception {
        DegradedEntry damaged = DegradedEntry.of("damaged-entry", "not a regular file");
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha), List.of(damaged)));

        ProjectResolver.Resolution byName = resolver.resolveTolerantly("alpha");
        assertEquals(alpha, byName.project());
        assertEquals(List.of(damaged), byName.unreadable());

        ProjectResolver.Resolution byId = resolver.resolveTolerantly(alpha.id().toString());
        assertEquals(alpha, byId.project());
        assertEquals(List.of(), byId.unreadable(), "a UUID reads only its own entry: a damaged neighbour is not its concern");
    }

    @Test
    void tolerantResolutionOfAnAbsentNameFailsPlainlyOnlyWhenNothingIsUnreadable() {
        ProjectResolver clean = new ProjectResolver(new FakeRegistry(List.of(alpha)));
        assertEquals(ProjectResolver.ErrorCode.PROJECT_NOT_FOUND,
                assertThrows(ProjectResolver.ResolutionException.class, () -> clean.resolveTolerantly("missing")).code());

        DegradedEntry damaged = DegradedEntry.of("damaged-entry", "not a regular file");
        ProjectResolver degraded = new ProjectResolver(new FakeRegistry(List.of(alpha), List.of(damaged)));
        UnreadableRegistryException undetermined = assertThrows(UnreadableRegistryException.class,
                () -> degraded.resolveTolerantly("missing"));
        assertEquals(List.of(damaged), undetermined.unreadable());
    }

    @Test
    void tolerantResolutionOfAnAmbiguousNameIsAmbiguous() {
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(twinOne, twinTwo)));

        assertEquals(ProjectResolver.ErrorCode.PROJECT_REFERENCE_AMBIGUOUS,
                assertThrows(ProjectResolver.ResolutionException.class, () -> resolver.resolveTolerantly("twin")).code());
    }

    @Test
    void aRegistryThatCannotBeListedFailsAsItIsWhenNothingExplainsIt() {
        IOException failure = new IOException("listing failed");
        ProjectResolver resolver = new ProjectResolver(new FakeRegistry(List.of(alpha)).failingListingWith(failure));

        assertSame(failure, assertThrows(IOException.class, () -> resolver.resolve("alpha")));
    }

    @Test
    void aRegistryThatCannotBeListedIsExplainedByItsUnreadableEntries() {
        IOException failure = new IOException("listing failed");
        DegradedEntry damaged = DegradedEntry.of("damaged-entry", "not a regular file");
        ProjectResolver resolver = new ProjectResolver(
                new FakeRegistry(List.of(alpha), List.of(damaged)).failingListingWith(failure));

        UnreadableRegistryException explained = assertThrows(UnreadableRegistryException.class,
                () -> resolver.resolve("alpha"));
        assertEquals(List.of(damaged), explained.unreadable());
        assertEquals(List.of(failure), List.of(explained.getSuppressed()));
    }

    @Test
    void theRegistryAndTheResolutionAreRequired() {
        assertThrows(NullPointerException.class, () -> new ProjectResolver(null));
        assertThrows(NullPointerException.class, () -> new ProjectResolver.Resolution(null, List.of()));
        assertThrows(NullPointerException.class, () -> new ProjectResolver.Resolution(alpha, null));
        assertThrows(NullPointerException.class,
                () -> new ProjectResolver(new FakeRegistry(List.of())).resolveById(null));
    }

    private static RegisteredProject project(String displayName) {
        return new RegisteredProject(UUID.randomUUID(), Path.of("/projects", displayName.replaceAll("\\W", "-")),
                displayName, Optional.empty(), NOW, NOW);
    }

    /** An in-memory registry: only reads are supported, which is all the resolver does. */
    private static final class FakeRegistry implements ProjectRegistry {
        private final List<RegisteredProject> projects;
        private final List<DegradedEntry> unreadable;
        private IOException listingFailure;

        FakeRegistry(List<RegisteredProject> projects) {
            this(projects, List.of());
        }

        FakeRegistry(List<RegisteredProject> projects, List<DegradedEntry> unreadable) {
            this.projects = projects;
            this.unreadable = unreadable;
        }

        FakeRegistry failingListingWith(IOException failure) {
            this.listingFailure = failure;
            return this;
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return projects.stream().filter(project -> project.id().equals(projectId)).findFirst();
        }

        @Override
        public List<RegisteredProject> listProjects() throws IOException {
            if (listingFailure != null) throw listingFailure;
            return projects;
        }

        @Override
        public Inventory inventory() {
            return new Inventory(projects, unreadable);
        }

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            throw new UnsupportedOperationException("read-only fake");
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("read-only fake");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("read-only fake");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("read-only fake");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }
}
