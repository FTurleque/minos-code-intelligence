package com.minos.application.indexing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The indexing use case is built from its collaborators: a missing one is refused by its name. */
class ProjectIndexingDependenciesTest {

    @Test
    void aMissingCollaboratorIsRefusedByName() {
        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> new ProjectIndexingUseCase.Dependencies(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null));

        assertEquals("projectRegistry", refusal.getMessage());
    }

    @Test
    void aMissingDecoratorIsRefusedByName(@TempDir Path temp) throws Exception {
        ProjectIndexingUseCase.Dependencies dependencies = new IndexingFixture(temp).dependencies();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> new ProjectIndexingUseCase(dependencies, null));

        assertEquals("executorDecorator", refusal.getMessage());
    }

    @Test
    void aUseCaseBuiltFromItsCollaboratorsAloneAcceptsTheIdentityDecorator(@TempDir Path temp) throws Exception {
        ProjectIndexingUseCase.Dependencies dependencies = new IndexingFixture(temp).dependencies();

        assertNotNull(new ProjectIndexingUseCase(dependencies, UnaryOperator.identity()));
    }
}
