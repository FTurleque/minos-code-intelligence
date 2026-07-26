package com.minos.application;

import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Single application-level policy for resolving user-facing project references.
 *
 * <p>A generic reference is interpreted as a UUID when it is syntactically valid;
 * otherwise it is matched exactly against the persisted display name. Duplicate
 * display names are intentionally reported as ambiguous because the local registry
 * does not require display-name uniqueness.</p>
 */
public final class ProjectResolver {

    public enum ErrorCode {
        PROJECT_NOT_FOUND,
        PROJECT_REFERENCE_AMBIGUOUS,
        INVALID_PROJECT_REFERENCE
    }

    public static final class ResolutionException extends IllegalArgumentException {
        private final ErrorCode code;
        private final String reference;
        private final List<UUID> candidateIds;

        private ResolutionException(
                ErrorCode code,
                String reference,
                List<UUID> candidateIds,
                String message
        ) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.reference = reference;
            this.candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "candidateIds"));
        }

        public ErrorCode code() {
            return code;
        }

        public String reference() {
            return reference;
        }

        public List<UUID> candidateIds() {
            return candidateIds;
        }
    }

    private final LocalProjectRegistry registry;

    public ProjectResolver(LocalProjectRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /** Resolves either an exact UUID or an exact persisted display name. */
    public RegisteredProject resolve(String reference) throws IOException {
        requireReference(reference);
        UUID projectId = parseUuid(reference);
        return projectId == null ? resolveByName(reference) : resolveById(projectId);
    }

    public RegisteredProject resolveById(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        return registry.findProject(projectId)
                .orElseThrow(() -> notFound(projectId.toString()));
    }

    public RegisteredProject resolveByName(String displayName) throws IOException {
        requireReference(displayName);
        List<RegisteredProject> candidates = candidatesByName(displayName);
        if (candidates.isEmpty()) {
            throw notFound(displayName);
        }
        if (candidates.size() > 1) {
            throw ambiguous(displayName, candidates);
        }
        return candidates.getFirst();
    }

    /** Returns the deterministic exact-match candidates represented by a user reference. */
    public List<RegisteredProject> listCandidates(String reference) throws IOException {
        requireReference(reference);
        UUID projectId = parseUuid(reference);
        if (projectId != null) {
            return registry.findProject(projectId).map(List::of).orElseGet(List::of);
        }
        return candidatesByName(reference);
    }

    private List<RegisteredProject> candidatesByName(String displayName) throws IOException {
        return registry.listProjects().stream()
                .filter(project -> displayName.equals(project.displayName()))
                .toList();
    }

    private static void requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ResolutionException(
                    ErrorCode.INVALID_PROJECT_REFERENCE,
                    reference,
                    List.of(),
                    "project identifier must not be blank"
            );
        }
    }

    private static ResolutionException notFound(String reference) {
        return new ResolutionException(
                ErrorCode.PROJECT_NOT_FOUND,
                reference,
                List.of(),
                "unknown project: " + reference
        );
    }

    private static ResolutionException ambiguous(String reference, List<RegisteredProject> candidates) {
        return new ResolutionException(
                ErrorCode.PROJECT_REFERENCE_AMBIGUOUS,
                reference,
                candidates.stream().map(RegisteredProject::id).toList(),
                "ambiguous project name, use its UUID: " + reference
        );
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
