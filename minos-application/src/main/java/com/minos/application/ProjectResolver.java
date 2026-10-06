package com.minos.application;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.UnreadableRegistryException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Single application-level policy for resolving user-facing project references. */
public final class ProjectResolver {
    public static final int MAX_REFERENCE_UTF8_BYTES = 64 * 1024;

    /** What a public caller reads when the reference it sent must not be echoed (path, secret, connection string). */
    static final String UNKNOWN_REFERENCE_NOT_SHOWN =
            "unknown project (the reference is not shown); pass a registered project name or UUID, see `minos project list`";
    static final String AMBIGUOUS_REFERENCE_NOT_SHOWN =
            "ambiguous project name (the name is not shown); pass the project UUID, see `minos project list`";

    public enum ErrorCode { PROJECT_NOT_FOUND, PROJECT_REFERENCE_AMBIGUOUS, INVALID_PROJECT_REFERENCE }

    public static final class ResolutionException extends IllegalArgumentException {
        private final ErrorCode code;
        private final String reference;
        private final List<UUID> candidateIds;

        private ResolutionException(ErrorCode code, String reference, List<UUID> candidateIds, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
            this.reference = reference;
            this.candidateIds = List.copyOf(Objects.requireNonNull(candidateIds, "candidateIds"));
        }

        public ErrorCode code() { return code; }

        /**
         * The message a public caller (CLI, Java API, MCP) may read, the same on all three surfaces. The internal
         * message copies the reference the caller sent; it is returned as is unless the central redaction policy
         * judges it sensitive (it looks like a path, a credential or a connection string), in which case a fixed
         * text names the cause and the way to fix it without echoing the value. An invalid reference never echoes
         * its value, so its message is already public.
         */
        public String publicMessage() {
            return switch (code) {
                case INVALID_PROJECT_REFERENCE -> getMessage();
                case PROJECT_NOT_FOUND -> PublicErrorMessages.sanitize(getMessage(), UNKNOWN_REFERENCE_NOT_SHOWN);
                case PROJECT_REFERENCE_AMBIGUOUS ->
                        PublicErrorMessages.sanitize(getMessage(), AMBIGUOUS_REFERENCE_NOT_SHOWN);
            };
        }
        public String reference() { return reference; }
        public List<UUID> candidateIds() { return candidateIds; }
    }

    /**
     * A project resolved while some registry entries could not be read: the answer is true for what was read, and
     * {@code unreadable} says what was ignored (never empty-by-accident: it is the registry's own inventory).
     */
    public record Resolution(RegisteredProject project, List<DegradedEntry> unreadable) {
        public Resolution {
            Objects.requireNonNull(project, "project");
            unreadable = List.copyOf(Objects.requireNonNull(unreadable, "unreadable"));
        }
    }

    private final ProjectRegistry registry;

    public ProjectResolver(ProjectRegistry registry) { this.registry = Objects.requireNonNull(registry, "registry"); }

    public RegisteredProject resolve(String reference) throws IOException {
        requireReference(reference);
        UUID projectId = parseUuid(reference);
        return projectId == null ? resolveByName(reference) : resolveById(projectId);
    }

    /**
     * Like {@link #resolve} for the commands that report an incomplete answer instead of failing (Q24): a name found
     * among the readable entries is returned with the entries that could not be read; a name not found while some
     * entries are unreadable is neither found nor absent, and fails with {@link UnreadableRegistryException}. A
     * reference that is a UUID reads only its own entry, so a damaged neighbour never concerns it.
     */
    public Resolution resolveTolerantly(String reference) throws IOException {
        requireReference(reference);
        UUID projectId = parseUuid(reference);
        if (projectId != null) return new Resolution(resolveById(projectId), List.of());
        ProjectRegistry.Inventory inventory = registry.inventory();
        List<RegisteredProject> candidates = inventory.projects().stream()
                .filter(project -> reference.equals(project.displayName())).toList();
        if (candidates.size() > 1) throw ambiguous(reference, candidates);
        if (candidates.isEmpty()) {
            if (inventory.unreadable().isEmpty()) throw notFound(reference);
            throw UnreadableRegistryException.of(inventory.unreadable(), "it cannot be told whether this project exists");
        }
        return new Resolution(candidates.getFirst(), inventory.unreadable());
    }

    public RegisteredProject resolveById(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        return registry.findProject(projectId).orElseThrow(() -> notFound(projectId.toString()));
    }

    public RegisteredProject resolveByName(String displayName) throws IOException {
        requireReference(displayName);
        List<RegisteredProject> candidates = candidatesByName(displayName);
        if (candidates.isEmpty()) throw notFound(displayName);
        if (candidates.size() > 1) throw ambiguous(displayName, candidates);
        return candidates.getFirst();
    }

    public List<RegisteredProject> listCandidates(String reference) throws IOException {
        requireReference(reference);
        UUID projectId = parseUuid(reference);
        if (projectId != null) return registry.findProject(projectId).map(List::of).orElseGet(List::of);
        return candidatesByName(reference);
    }

    private List<RegisteredProject> candidatesByName(String displayName) throws IOException {
        try {
            return registry.listProjects().stream().filter(project -> displayName.equals(project.displayName())).toList();
        } catch (IOException | RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            Optional<UnreadableRegistryException> explained = UnreadableRegistryException.explaining(registry,
                    "the project name cannot be resolved with certainty; use its UUID");
            if (explained.isEmpty()) throw failure;
            explained.get().addSuppressed(failure);
            throw explained.get();
        }
    }

    private static void requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new ResolutionException(ErrorCode.INVALID_PROJECT_REFERENCE, reference, List.of(),
                    "project identifier must not be blank");
        }
        if (reference.getBytes(StandardCharsets.UTF_8).length > MAX_REFERENCE_UTF8_BYTES) {
            throw new ResolutionException(ErrorCode.INVALID_PROJECT_REFERENCE, null, List.of(),
                    "project identifier exceeds UTF-8 byte limit: " + MAX_REFERENCE_UTF8_BYTES);
        }
    }

    private static ResolutionException notFound(String reference) {
        return new ResolutionException(ErrorCode.PROJECT_NOT_FOUND, reference, List.of(), "unknown project: " + reference);
    }

    private static ResolutionException ambiguous(String reference, List<RegisteredProject> candidates) {
        return new ResolutionException(ErrorCode.PROJECT_REFERENCE_AMBIGUOUS, reference,
                candidates.stream().map(RegisteredProject::id).toList(),
                "ambiguous project name, use its UUID: " + reference);
    }

    private static UUID parseUuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException exception) { return null; }
    }
}
