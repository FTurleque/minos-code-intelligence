package com.minos.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Application-level project administration and SCIP import port shared by public surfaces. */
public interface ProjectOperations {

    ProjectView addProject(Path rootPath, String displayName) throws IOException;

    List<ProjectView> listProjects() throws IOException;

    ProjectView inspectProject(String projectIdentifier) throws IOException;

    IndexImportResult importScip(
            String projectIdentifier,
            Path indexFile,
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) throws IOException;

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
            if (moduleCount < 0) throw new IllegalArgumentException("moduleCount must not be negative");
            requireText(indexState, "indexState");
        }
    }

    enum IndexImportCommitStatus {
        COMMITTED,
        COMMITTED_DURABILITY_PENDING,
        COMMITTED_METADATA_PENDING,
        COMMITTED_DURABILITY_AND_METADATA_PENDING
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
            String completedAt,
            IndexImportCommitStatus commitStatus,
            String diagnostic
    ) {
        public IndexImportResult {
            requireText(projectId, "projectId");
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            requireText(completedAt, "completedAt");
            Objects.requireNonNull(commitStatus, "commitStatus");
            diagnostic = diagnostic == null || diagnostic.isBlank() ? null : diagnostic;
        }

        public IndexImportResult(
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
            this(projectId, snapshotId, providerId, providerVersion, normalizedSymbolCount, occurrenceCount,
                    relationshipCount, relatedTestRelationshipCount, unresolvedOccurrenceCount,
                    unresolvedRelationshipCount, completedAt, IndexImportCommitStatus.COMMITTED, null);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
