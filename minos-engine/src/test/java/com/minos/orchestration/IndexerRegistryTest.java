package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.EvaluationStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerRegistryTest {

    @Test
    void rejectsDuplicateIndexerIds() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(descriptor("java-a", 10, IndexerQualification.QUALIFIED, Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES)));

        assertThrows(IllegalArgumentException.class, () -> registry.register(
                descriptor("java-a", 20, IndexerQualification.QUALIFIED, Set.of(BuildSystem.MAVEN),
                        Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES))
        ));
    }

    @Test
    void selectsHighestPriorityCompatibleIndexerDeterministically() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(descriptor("java-low", 10, IndexerQualification.QUALIFIED, Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES)));
        registry.register(descriptor("java-high", 20, IndexerQualification.QUALIFIED_WITH_CONSTRAINTS,
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES)));

        IndexerNegotiationResult result = registry.negotiate(javaMavenProject(), IndexingRequirements.baseline());

        assertTrue(result.complete());
        assertEquals(List.of("java-high"), result.selections().stream()
                .map(selection -> selection.indexer().id())
                .toList());
        assertTrue(result.evaluations().stream().anyMatch(evaluation ->
                evaluation.indexerId().equals("java-low")
                        && evaluation.status() == EvaluationStatus.NOT_SELECTED_LOWER_PRIORITY));
    }

    @Test
    void explainsBuildAndCapabilityRejections() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(descriptor("wrong-build", 20, IndexerQualification.QUALIFIED, Set.of(BuildSystem.NPM),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES)));
        registry.register(descriptor("missing-reference", 10, IndexerQualification.QUALIFIED,
                Set.of(BuildSystem.MAVEN), Set.of(IndexerCapability.SYMBOLS)));

        IndexerNegotiationResult result = registry.negotiate(javaMavenProject(), IndexingRequirements.baseline());

        assertFalse(result.complete());
        assertEquals(Set.of(Language.JAVA), result.uncoveredLanguages());
        assertTrue(result.evaluations().stream().anyMatch(evaluation ->
                evaluation.indexerId().equals("wrong-build")
                        && evaluation.status() == EvaluationStatus.REJECTED_BUILD_SYSTEM));
        assertTrue(result.evaluations().stream().anyMatch(evaluation ->
                evaluation.indexerId().equals("missing-reference")
                        && evaluation.status() == EvaluationStatus.REJECTED_MISSING_CAPABILITIES
                        && evaluation.missingCapabilities().equals(Set.of(IndexerCapability.REFERENCES))));
    }

    @Test
    void excludesExperimentalIndexersUnlessExplicitlyAllowed() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(descriptor("experimental", 100, IndexerQualification.EXPERIMENTAL, Set.of(BuildSystem.MAVEN),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES)));

        IndexerNegotiationResult rejected = registry.negotiate(javaMavenProject(), IndexingRequirements.baseline());
        IndexerNegotiationResult accepted = registry.negotiate(
                javaMavenProject(),
                new IndexingRequirements(Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES), true)
        );

        assertFalse(rejected.complete());
        assertTrue(rejected.evaluations().stream().anyMatch(evaluation ->
                evaluation.status() == EvaluationStatus.REJECTED_EXPERIMENTAL));
        assertTrue(accepted.complete());
        assertEquals("experimental", accepted.selections().getFirst().indexer().id());
    }

    @Test
    void executesAMultiLanguageIndexerOnlyOnceWhileCoveringEveryLanguage() {
        IndexerRegistry registry = new IndexerRegistry();
        registry.register(new IndexerDescriptor(
                "multi-language",
                "1.0.0",
                "multi-language",
                Set.of(Language.C, Language.CPP),
                Set.of(BuildSystem.CMAKE),
                Set.of(IndexerCapability.SYMBOLS, IndexerCapability.REFERENCES),
                IndexerQualification.QUALIFIED,
                100,
                List.of()
        ));
        ProjectDiscovery project = new ProjectDiscovery(
                Path.of("multi-language-project"),
                "multi-language-project",
                Set.of(Language.C, Language.CPP),
                Set.of(BuildSystem.CMAKE),
                List.of()
        );

        IndexerNegotiationResult result = registry.negotiate(project, IndexingRequirements.baseline());

        assertTrue(result.complete());
        assertEquals(List.of("multi-language"), result.selections().stream()
                .map(selection -> selection.indexer().id())
                .toList());
        assertEquals(Set.of(Language.C, Language.CPP), result.evaluations().stream()
                .filter(evaluation -> evaluation.status() == EvaluationStatus.SELECTED)
                .map(IndexerNegotiationResult.IndexerEvaluation::language)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static IndexerDescriptor descriptor(
            String id,
            int priority,
            IndexerQualification qualification,
            Set<BuildSystem> buildSystems,
            Set<IndexerCapability> capabilities
    ) {
        return new IndexerDescriptor(
                id,
                "1.0.0",
                id,
                Set.of(Language.JAVA),
                buildSystems,
                capabilities,
                qualification,
                priority,
                List.of()
        );
    }

    private static ProjectDiscovery javaMavenProject() {
        return new ProjectDiscovery(
                Path.of("java-project"),
                "java-project",
                Set.of(Language.JAVA),
                Set.of(BuildSystem.MAVEN),
                List.of()
        );
    }
}
