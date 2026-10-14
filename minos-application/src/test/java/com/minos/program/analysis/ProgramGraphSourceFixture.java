package com.minos.program.analysis;

import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Relationship;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectFingerprintSnapshotStore;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Projet Java jetable, magasins en mémoire, horloge manuelle et compteur de lectures de sources pour les tests du
 * graphe de programme. Le compteur observe les lectures réelles de fichiers (événements {@code jdk.FileRead}) : il
 * ne dépend d'aucun point d'injection du code testé.
 */
final class ProgramGraphSourceFixture {

    final Path projectRoot;
    final List<String> fileIds;
    final Registry registry = new Registry();
    final Snapshots snapshots = new Snapshots();
    final Fingerprints fingerprints = new Fingerprints();
    final ManualClock clock = new ManualClock();
    final RegisteredProject project;
    final String snapshotId = "snapshot-source-state";

    ProgramGraphSourceFixture(Path root, int fileCount) throws IOException {
        this.projectRoot = Files.createDirectories(root.resolve("project"));
        List<String> ids = new ArrayList<>(fileCount);
        for (int index = 0; index < fileCount; index++) {
            String simpleName = "Generated" + index;
            String fileId = "src/main/java/demo/" + simpleName + ".java";
            Path source = projectRoot.resolve(fileId);
            Files.createDirectories(source.getParent());
            Files.writeString(source, "package demo;\nfinal class " + simpleName
                    + " {\n    int run(int value) {\n        int copy = value + 1;\n        return copy;\n    }\n}\n",
                    StandardCharsets.UTF_8);
            ids.add(fileId);
        }
        this.fileIds = List.copyOf(ids);
        this.project = registry.registerProject(projectRoot, "source-state");
        snapshots.publish(project.id(), snapshotId, symbols(project, snapshotId, fileIds));
    }

    /** Associe au snapshot l'empreinte exacte de l'arbre tel qu'il est maintenant. */
    void publishExactFingerprint() throws IOException {
        fingerprints.publish(project.id(), snapshotId, new ProjectFingerprintService().capture(projectRoot));
    }

    /** Ajoute une méthode au premier fichier : son contenu, donc l'état des sources, change. */
    void changeFirstSource() throws IOException {
        Path source = projectRoot.resolve(fileIds.getFirst());
        String text = Files.readString(source, StandardCharsets.UTF_8);
        int end = text.lastIndexOf('}');
        Files.writeString(source, text.substring(0, end)
                + "    int added(int value) {\n        return value - 1;\n    }\n}\n", StandardCharsets.UTF_8);
    }

    ProgramGraphService service(List<ProgramGraphProvider> providers) {
        return new ProgramGraphService(registry, snapshots, providers);
    }

    /** Le service tel que la production le compose, avec l'horloge manuelle de ce test. */
    ProgramGraphService productionService() {
        return service(List.of(
                new RelationshipProgramGraphProvider(),
                new FingerprintConstrainedJavaProgramGraphProvider(fingerprints, clock)));
    }

    /** Nombre de fichiers {@code .java} distincts réellement lus pendant {@code action}. */
    static int javaSourcesRead(IoAction action) throws IOException {
        Path recordingFile = Files.createTempFile("minos-source-reads-", ".jfr");
        try {
            try (Recording recording = new Recording()) {
                recording.enable("jdk.FileRead").withThreshold(Duration.ZERO).withoutStackTrace();
                recording.start();
                action.run();
                recording.stop();
                recording.dump(recordingFile);
            }
            Set<String> javaFiles = new HashSet<>();
            try (RecordingFile events = new RecordingFile(recordingFile)) {
                while (events.hasMoreEvents()) {
                    RecordedEvent event = events.readEvent();
                    String path = "jdk.FileRead".equals(event.getEventType().getName()) ? event.getString("path") : null;
                    if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".java")) javaFiles.add(path);
                }
            }
            return javaFiles.size();
        } finally {
            Files.deleteIfExists(recordingFile);
        }
    }

    static List<Symbol> symbols(RegisteredProject project, String snapshotId, List<String> fileIds) {
        Origin origin = new Origin("source-state-fixture", "GENERATED_FIXTURE", "1", snapshotId, OriginType.OTHER);
        return fileIds.stream().map(fileId -> {
            String simpleName = Path.of(fileId).getFileName().toString().replace(".java", "");
            return new Symbol(
                    "source-state-" + simpleName, "demo/" + simpleName + "#", SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                    project.id().toString(), null, fileId, null, SymbolKind.CLASS, simpleName, "demo." + simpleName,
                    null, "java", new SymbolLocation(fileId, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                    ResolutionStatus.RESOLVED, origin, false, false, Set.of());
        }).toList();
    }

    @FunctionalInterface
    interface IoAction {
        void run() throws IOException;
    }

    /** Horloge monotone que le test avance lui-même : aucune attente réelle. */
    static final class ManualClock implements LongSupplier {
        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }

    /** Registre en mémoire : seuls l'enregistrement et la résolution d'un projet sont utilisés. */
    static final class Registry implements ProjectRegistry {
        private final Map<UUID, RegisteredProject> projects = new HashMap<>();

        @Override
        public RegisteredProject registerProject(Path rootPath, String displayName) {
            Instant now = Instant.parse("2026-10-13T00:00:00Z");
            RegisteredProject registered = new RegisteredProject(
                    UUID.randomUUID(), rootPath, displayName, Optional.empty(), now, now);
            projects.put(registered.id(), registered);
            return registered;
        }

        @Override
        public Optional<RegisteredProject> findProject(UUID projectId) {
            return Optional.ofNullable(projects.get(projectId));
        }

        @Override
        public List<RegisteredProject> listProjects() {
            return List.copyOf(projects.values());
        }

        @Override
        public RegisteredWorkspace createWorkspace(String name) {
            throw new UnsupportedOperationException("workspaces are not used by these tests");
        }

        @Override
        public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) {
            throw new UnsupportedOperationException("workspaces are not used by these tests");
        }

        @Override
        public RegisteredProject removeProjectFromWorkspace(UUID projectId) {
            throw new UnsupportedOperationException("workspaces are not used by these tests");
        }

        @Override
        public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) {
            return Optional.empty();
        }

        @Override
        public List<RegisteredWorkspace> listWorkspaces() {
            return List.of();
        }
    }

    /** Snapshots en mémoire : le dernier publié devient actif ; sa vue de requête est construite à la demande. */
    static final class Snapshots implements CodeKnowledgeSnapshotStore {
        private final Map<UUID, CodeKnowledgeSnapshot> active = new HashMap<>();

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            List<Symbol> ordered = symbols.stream().sorted(Comparator.comparing(Symbol::id)).toList();
            active.put(projectId, new CodeKnowledgeSnapshot(projectId, snapshotId, ordered, List.of(), List.of()));
            return new SymbolSnapshot(projectId, snapshotId, ordered);
        }

        @Override
        public CodeKnowledgeSnapshot publish(
                UUID projectId, String snapshotId, Collection<Symbol> symbols,
                Collection<SymbolOccurrence> occurrences, Collection<Relationship> relationships) {
            throw new UnsupportedOperationException("occurrences and relationships are not used by these tests");
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            return loadActiveKnowledge(projectId).map(snapshot -> new SymbolSnapshot(
                    snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols()));
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.ofNullable(active.get(projectId));
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return loadActiveKnowledge(projectId).map(snapshot -> new SnapshotQueryView(
                    new SnapshotDescriptor(3, snapshot.snapshotId(), snapshot.snapshotId() + ".bin", "0".repeat(64),
                            snapshot.symbols().size(), snapshot.occurrences().size(),
                            snapshot.relationships().size()),
                    snapshot, new InMemoryCodeKnowledgeStore(snapshot), 0L));
        }
    }

    /** Empreintes en mémoire, par projet et snapshot d'index. */
    static final class Fingerprints implements ProjectFingerprintSnapshotStore {
        private final Map<String, ProjectFingerprintSnapshot> stored = new HashMap<>();

        @Override
        public ProjectFingerprintSnapshot publish(UUID projectId, String indexSnapshotId, ProjectFingerprint fingerprint) {
            ProjectFingerprintSnapshot snapshot = new ProjectFingerprintSnapshot(projectId, indexSnapshotId, fingerprint);
            stored.put(projectId + "/" + indexSnapshotId, snapshot);
            return snapshot;
        }

        @Override
        public void promote(UUID projectId, String indexSnapshotId) {
            // Rien à promouvoir : load() répond par snapshot d'index.
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> load(UUID projectId, String indexSnapshotId) {
            return Optional.ofNullable(stored.get(projectId + "/" + indexSnapshotId));
        }

        @Override
        public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) {
            return Optional.empty();
        }

        @Override
        public List<String> listIndexSnapshotIds(UUID projectId) {
            return List.of();
        }
    }
}
