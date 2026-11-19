package com.minos.bootstrap.application;

import com.minos.application.MinosApplication;
import com.minos.program.ProgramGraph;
import com.minos.program.analysis.ProgramGraphProvider;
import com.minos.program.analysis.RelationshipProgramGraphProvider;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The program graph providers of an application, identifiers and order: the production list of the composition
 * root, and the completion of a list given to the builder (Java source analyser only beside the relationship
 * provider, sidecar reader always).
 */
class ProgramGraphProviderCompositionTest {

    private static final String RELATIONSHIPS = "minos-relationships";
    private static final String JAVA_SOURCE = "minos-java-source-v1";
    private static final String SIDECAR = "minos-program-sidecar-v1";

    @Test
    void anOpenedApplicationHasTheProductionProvidersInOrder(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.open(home)) {
            assertEquals(List.of(RELATIONSHIPS, JAVA_SOURCE, SIDECAR),
                    List.copyOf(application.programGraphService().providerIds()));
        }
    }

    @Test
    void theRelationshipProviderAloneIsCompletedWithTheJavaAnalyserAndTheSidecar(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.builder(home)
                .programGraphProviders(List.of(new RelationshipProgramGraphProvider())).build()) {
            assertEquals(List.of(RELATIONSHIPS, JAVA_SOURCE, SIDECAR),
                    List.copyOf(application.programGraphService().providerIds()));
        }
    }

    @Test
    void anotherProviderIsCompletedWithTheSidecarOnly(@TempDir Path home) throws Exception {
        try (MinosApplication application = MinosApplication.builder(home)
                .programGraphProviders(List.of(new OtherProvider())).build()) {
            assertEquals(List.of("other-provider", SIDECAR),
                    List.copyOf(application.programGraphService().providerIds()));
        }
    }

    /** A provider of an identifier the completion does not know. */
    private static final class OtherProvider implements ProgramGraphProvider {
        @Override
        public String id() {
            return "other-provider";
        }

        @Override
        public ProgramGraph analyze(RegisteredProject project, CodeKnowledgeSnapshot snapshot) {
            throw new AssertionError("not analysed");
        }
    }
}
