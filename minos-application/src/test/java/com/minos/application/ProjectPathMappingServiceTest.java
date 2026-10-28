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

    private static final ProjectPathMapping MAPPING = new ProjectPathMapping("N:/workspace-dev", "/workspace/projects");

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
                .configure(new ProjectPathMapping("N:\\workspace-dev", "/workspace/projects/")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void refusesToReplaceAnotherMappingAndWritesNothing() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.CONFLICT, new ProjectPathMappingService(store)
                .configure(new ProjectPathMapping("D:/other-projects", "/workspace/projects")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void refusesMissingArguments() {
        assertThrows(NullPointerException.class, () -> new ProjectPathMappingService(null));
        assertThrows(NullPointerException.class,
                () -> new ProjectPathMappingService(new RecordingMappings(null)).configure(null));
        assertThrows(NullPointerException.class, () -> ProjectPathMappingService.forHome(null));
    }

    @Test
    void forHomeFailsExplicitlyWithoutTheCompositionRoot() {
        // minos-bootstrap is not on this module's test classpath: the composer lookup fails closed.
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProjectPathMappingService.forHome(Path.of("home")));
        assertEquals(MinosApplicationComposers.MISSING, failure.getMessage());
    }

    private static final class RecordingMappings implements ProjectPathMappings {
        private final ProjectPathMapping existing;
        private final List<ProjectPathMapping> saved = new ArrayList<>();

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
    }
}
