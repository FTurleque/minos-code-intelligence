package com.minos.cli;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Port CLI d'administration des projets et d'import d'un artefact d'index.
 *
 * <p>Les commandes restent ainsi indépendantes du registre, du stockage et du
 * format d'artefact concret.</p>
 */
public interface ProjectOperations {

    ProjectView addProject(Path rootPath, String displayName) throws Exception;

    List<ProjectView> listProjects() throws Exception;

    ProjectView inspectProject(String projectIdentifier) throws Exception;

    IndexImportResult importScip(
            String projectIdentifier,
            Path indexFile,
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) throws Exception;

    record ProjectView(
            String id,
            String name,
            String rootPath,
            boolean rootAvailable,
            List<String> languages,
            List<String> buildSystems,
            int moduleCount,
            String indexState,
            String activeSnapshotId,
            String lastSuccessfulIndexAt,
            String providerId,
            String providerVersion
    ) {
        public ProjectView {
            requireText(id, "id");
            requireText(name, "name");
            requireText(rootPath, "rootPath");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            if (moduleCount < 0) {
                throw new IllegalArgumentException("moduleCount must not be negative");
            }
            requireText(indexState, "indexState");
        }
    }

    record IndexImportResult(
            String projectId,
            String snapshotId,
            String providerId,
            String providerVersion,
            int normalizedSymbolCount,
            int occurrenceCount,
            int relationshipCount,
            int relatedTestRelationshipCount,
            int unresolvedOccurrenceCount,
            int unresolvedRelationshipCount,
            String completedAt
    ) {
        public IndexImportResult {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(completedAt, "completedAt");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
