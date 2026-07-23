package com.minos.incremental;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.ProjectIndexState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Évalue conservativement l'étendue d'invalidation avant toute négociation avec
 * un fournisseur d'indexation.
 *
 * <p>Le service ne promet jamais qu'une indexation partielle est exécutable.
 * Il peut seulement qualifier un ensemble de changements comme
 * {@link ProjectInvalidationScope#PARTIAL_CANDIDATE} lorsque tous les fichiers
 * modifiés sont des sources/tests reconnus par la découverte M1.</p>
 */
public final class ProjectInvalidationService {

    private static final Set<String> BUILD_DESCRIPTOR_NAMES = Set.of(
            "pom.xml",
            "package.json",
            "package-lock.json"
    );
    private static final Set<String> ROOT_IGNORE_FILES = Set.of(
            ".gitignore",
            ".minosignore"
    );

    private final ProjectFingerprintService fingerprintService;

    public ProjectInvalidationService() {
        this(new ProjectFingerprintService());
    }

    public ProjectInvalidationService(ProjectFingerprintService fingerprintService) {
        this.fingerprintService = Objects.requireNonNull(fingerprintService, "fingerprintService");
    }

    public ProjectInvalidationAssessment assess(
            ProjectIndexState indexState,
            Optional<ProjectFingerprintSnapshot> baselineSnapshot,
            ProjectFingerprint currentFingerprint,
            ProjectDiscovery discovery
    ) {
        Objects.requireNonNull(indexState, "indexState");
        baselineSnapshot = Objects.requireNonNull(baselineSnapshot, "baselineSnapshot");
        Objects.requireNonNull(currentFingerprint, "currentFingerprint");
        Objects.requireNonNull(discovery, "discovery");

        baselineSnapshot.ifPresent(snapshot -> {
            if (!snapshot.projectId().equals(indexState.projectId())) {
                throw new IllegalArgumentException("fingerprint baseline belongs to another project");
            }
        });

        Optional<String> activeIndexId = indexState.activeSnapshotId();
        Optional<String> baselineIndexId = baselineSnapshot.map(ProjectFingerprintSnapshot::indexSnapshotId);

        if (activeIndexId.isEmpty()) {
            return fullWithoutChangeSet(
                    indexState,
                    activeIndexId,
                    baselineIndexId,
                    ProjectInvalidationReason.NO_ACTIVE_INDEX
            );
        }
        if (baselineSnapshot.isEmpty()) {
            return fullWithoutChangeSet(
                    indexState,
                    activeIndexId,
                    baselineIndexId,
                    ProjectInvalidationReason.MISSING_FINGERPRINT_BASELINE
            );
        }
        if (!baselineIndexId.orElseThrow().equals(activeIndexId.orElseThrow())) {
            return fullWithoutChangeSet(
                    indexState,
                    activeIndexId,
                    baselineIndexId,
                    ProjectInvalidationReason.BASELINE_INDEX_MISMATCH
            );
        }

        ProjectChangeSet changeSet = fingerprintService.compare(
                baselineSnapshot.orElseThrow().fingerprint(),
                currentFingerprint
        );
        if (!changeSet.projectChanged()) {
            return new ProjectInvalidationAssessment(
                    indexState.projectId(),
                    activeIndexId,
                    baselineIndexId,
                    ProjectInvalidationScope.NONE,
                    List.of(),
                    Optional.of(changeSet),
                    List.of(),
                    List.of(),
                    List.of()
            );
        }

        List<SourceRootDescriptor> roots = sourceRoots(discovery);
        TreeSet<String> sourceFiles = new TreeSet<>();
        TreeSet<String> testFiles = new TreeSet<>();
        TreeSet<String> unqualifiedFiles = new TreeSet<>();
        EnumSet<ProjectInvalidationReason> reasons = EnumSet.noneOf(ProjectInvalidationReason.class);

        if (changeSet.buildDefinitionChanged()) {
            reasons.add(ProjectInvalidationReason.BUILD_DEFINITION_CHANGED);
        }

        for (String path : changedPaths(changeSet)) {
            if (ROOT_IGNORE_FILES.contains(path)) {
                reasons.add(ProjectInvalidationReason.IGNORE_POLICY_CHANGED);
                continue;
            }
            if (isBuildDescriptor(path)) {
                continue;
            }

            Optional<SourceRootKind> kind = classifySourcePath(path, roots);
            if (kind.isEmpty()) {
                unqualifiedFiles.add(path);
            } else if (kind.orElseThrow() == SourceRootKind.TEST) {
                testFiles.add(path);
            } else {
                sourceFiles.add(path);
            }
        }

        if (!sourceFiles.isEmpty() || !testFiles.isEmpty()) {
            reasons.add(ProjectInvalidationReason.SOURCE_OR_TEST_CHANGED);
        }
        if (!unqualifiedFiles.isEmpty()) {
            reasons.add(ProjectInvalidationReason.UNQUALIFIED_FILE_CHANGE);
        }

        boolean fullRequired = reasons.contains(ProjectInvalidationReason.BUILD_DEFINITION_CHANGED)
                || reasons.contains(ProjectInvalidationReason.IGNORE_POLICY_CHANGED)
                || reasons.contains(ProjectInvalidationReason.UNQUALIFIED_FILE_CHANGE);

        ProjectInvalidationScope scope = fullRequired
                ? ProjectInvalidationScope.FULL_REQUIRED
                : ProjectInvalidationScope.PARTIAL_CANDIDATE;

        return new ProjectInvalidationAssessment(
                indexState.projectId(),
                activeIndexId,
                baselineIndexId,
                scope,
                List.copyOf(reasons),
                Optional.of(changeSet),
                List.copyOf(sourceFiles),
                List.copyOf(testFiles),
                List.copyOf(unqualifiedFiles)
        );
    }

    private static ProjectInvalidationAssessment fullWithoutChangeSet(
            ProjectIndexState indexState,
            Optional<String> activeIndexId,
            Optional<String> baselineIndexId,
            ProjectInvalidationReason reason
    ) {
        return new ProjectInvalidationAssessment(
                indexState.projectId(),
                activeIndexId,
                baselineIndexId,
                ProjectInvalidationScope.FULL_REQUIRED,
                List.of(reason),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of()
        );
    }

    private static List<String> changedPaths(ProjectChangeSet changeSet) {
        TreeSet<String> paths = new TreeSet<>();
        paths.addAll(changeSet.addedFiles());
        paths.addAll(changeSet.modifiedFiles());
        paths.addAll(changeSet.deletedFiles());
        return List.copyOf(paths);
    }

    private static List<SourceRootDescriptor> sourceRoots(ProjectDiscovery discovery) {
        List<SourceRootDescriptor> roots = new ArrayList<>();
        for (ProjectDiscovery.DiscoveredModule module : discovery.modules()) {
            for (ProjectDiscovery.SourceRoot root : module.sourceRoots()) {
                roots.add(new SourceRootDescriptor(
                        portable(root.relativePath()),
                        root.kind(),
                        root.language()
                ));
            }
        }
        roots.sort(Comparator
                .comparingInt((SourceRootDescriptor root) -> root.relativePath().length())
                .reversed()
                .thenComparing(SourceRootDescriptor::relativePath)
                .thenComparing(root -> root.kind().name())
                .thenComparing(root -> root.language().name()));
        return List.copyOf(roots);
    }

    private static Optional<SourceRootKind> classifySourcePath(
            String relativePath,
            List<SourceRootDescriptor> roots
    ) {
        for (SourceRootDescriptor root : roots) {
            if (isInside(relativePath, root.relativePath())
                    && matchesLanguageExtension(relativePath, root.language())) {
                return Optional.of(root.kind());
            }
        }
        return Optional.empty();
    }

    private static boolean isInside(String path, String root) {
        return !root.isEmpty() && path.startsWith(root + "/");
    }

    private static boolean matchesLanguageExtension(String path, Language language) {
        return switch (language) {
            case JAVA -> path.endsWith(".java");
            case TYPESCRIPT -> path.endsWith(".ts") || path.endsWith(".tsx");
        };
    }

    private static boolean isBuildDescriptor(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        String fileName = separator < 0 ? relativePath : relativePath.substring(separator + 1);
        return BUILD_DESCRIPTOR_NAMES.contains(fileName);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private record SourceRootDescriptor(
            String relativePath,
            SourceRootKind kind,
            Language language
    ) {
    }
}
