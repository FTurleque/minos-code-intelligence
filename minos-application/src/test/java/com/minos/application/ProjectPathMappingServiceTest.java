package com.minos.application;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathMappingServiceTest {

    private static final ProjectPathMapping MAPPING = ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects");

    @Test
    void createsTheMappingWhenNoneExists() throws Exception {
        RecordingMappings store = new RecordingMappings(null);

        assertEquals(ProjectPathMappingService.Outcome.CREATED, new ProjectPathMappingService(store).configure(MAPPING));
        assertEquals(List.of(MAPPING), store.saved);
    }

    @Test
    void acceptsTheSameMappingWithoutWriting() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, new ProjectPathMappingService(store)
                .configure(ProjectPathMapping.singleRoot("N:\\workspace-dev", "/workspace/projects/")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void refusesToReplaceAnotherMappingAndWritesNothing() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.CONFLICT, new ProjectPathMappingService(store)
                .configure(ProjectPathMapping.singleRoot("D:/other-projects", "/workspace/projects")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void replaceCreatesTheMappingWhenNoneExists() throws Exception {
        RecordingMappings store = new RecordingMappings(null);

        ProjectPathMappingService.Result result = new ProjectPathMappingService(store).replace(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.CREATED, result.outcome());
        assertEquals(new ProjectPathMappings.Replacement(0, 0, 0), result.replacement());
        assertEquals(List.of(MAPPING), store.saved);
        assertTrue(store.replaced.isEmpty());
    }

    @Test
    void replaceReplacesAnotherMappingAndReportsTheProjects() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);
        ProjectPathMapping other = ProjectPathMapping.singleRoot("D:/other-projects", "/workspace/projects");

        ProjectPathMappingService.Result result = new ProjectPathMappingService(store).replace(other);

        assertEquals(ProjectPathMappingService.Outcome.REPLACED, result.outcome());
        assertEquals(RecordingMappings.REPORT, result.replacement());
        assertEquals(List.of(other), store.replaced);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void replaceWithTheSameMappingIsUnchangedAndStillReattaches() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        ProjectPathMappingService.Result result = new ProjectPathMappingService(store).replace(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, result.outcome());
        assertEquals(List.of(MAPPING), store.replaced);
    }

    @Test
    void refusesMissingArguments() {
        assertThrows(NullPointerException.class, () -> new ProjectPathMappingService(null));
        ProjectPathMappingService service = new ProjectPathMappingService(new RecordingMappings(null));
        assertThrows(NullPointerException.class, () -> service.configure(null));
        assertThrows(NullPointerException.class, () -> service.replace(null));
        assertThrows(NullPointerException.class, () -> ProjectPathMappingService.forHome(null));
    }

    @Test
    void forHomeFailsExplicitlyWithoutTheCompositionRoot() {
        // minos-bootstrap is not on this module's test classpath: the composer lookup fails closed.
        Path home = Path.of("home");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProjectPathMappingService.forHome(home));
        assertEquals(MinosApplicationComposers.MISSING, failure.getMessage());
    }

    private static final class RecordingMappings implements ProjectPathMappings {
        private final ProjectPathMapping existing;
        private static final Replacement REPORT = new Replacement(1, 2, 3);
        private final List<ProjectPathMapping> saved = new ArrayList<>();
        private final List<ProjectPathMapping> replaced = new ArrayList<>();

        private RecordingMappings(ProjectPathMapping existing) {
            this.existing = existing;
        }

        @Override
        public Optional<ProjectPathMapping> loadOptional() {
            return Optional.ofNullable(existing);
        }

        @Override
        public void save(ProjectPathMapping mapping) {
            saved.add(mapping);
        }

        @Override
        public Replacement replace(ProjectPathMapping desired) {
            replaced.add(desired);
            return REPORT;
        }
    }
}
