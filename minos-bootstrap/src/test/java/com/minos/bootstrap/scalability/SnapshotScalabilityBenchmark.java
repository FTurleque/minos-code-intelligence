package com.minos.bootstrap.scalability;

import com.minos.adapter.scip.ScipIndexReader;
import com.minos.adapter.scip.ScipIngestionLimits;
import com.minos.application.MinosApplication;
import com.minos.application.ProjectResolver;
import com.minos.application.semantic.HybridSearchService;
import com.minos.application.semantic.SemanticDocumentFactory;
import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.Origin;
import com.minos.domain.ProviderReference;
import com.minos.domain.Relationship;
import com.minos.domain.ResolvedSymbolReference;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolOccurrence;
import com.minos.domain.SymbolReference;
import com.minos.domain.UnresolvedSymbolReference;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ImpactAnalysisService;
import com.minos.orchestration.IndexArtifactLimits;
import com.minos.orchestration.ScipSymbolSnapshotRequest;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.storage.local.store.SnapshotCodec;
import com.minos.storage.local.store.SnapshotCodecV2;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;
import com.minos.store.SnapshotDescriptor;
import com.minos.store.SnapshotQueryView;
import com.minos.store.SymbolSnapshot;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Banc de mesure du lot A6 (mémoire et scalabilité) : taille et composition des snapshots persistés,
 * ratio SCIP vers snapshot, empreinte en tas, recherche hybride, analyse d'impact profonde et part de la
 * découverte dans {@code LocalProjectArchitectureQuery}.
 *
 * <p>Classe {@code main} des sources de test, comme {@code InMemoryBackendBenchmark} et
 * {@code CodeSearchBenchmark} : son nom ne correspond à aucun motif d'inclusion de Surefire, elle ne
 * tourne donc jamais dans {@code mvn verify}. Elle est lancée par
 * {@code benchmarks/scalability/run-scalability-benchmark.ps1}, qui documente la procédure. Chaque mesure
 * sort sur une ligne {@code METRIC\tsection\tdataset\tname\tvalue}.</p>
 *
 * <p>Deux familles de jeux, toutes deux tirées du corpus réel pour en garder les distributions (longueurs de
 * chaînes, occurrences et relations par fichier) : des tranches stables de fichiers ({@code file-f*}),
 * publiées dans le magasin fichier et mesurées par le chemin produit ; le corpus entier répliqué {@code k}
 * fois ({@code mem-k*} : identifiants, clés et noms qualifiés préfixés par {@code r<i>~}, graphe recopié à
 * l'intérieur de chaque réplique), servi par un magasin en mémoire, hors persistance.</p>
 */
public final class SnapshotScalabilityBenchmark {

    private static final String SNAPSHOT_STORE_DIRECTORY = "symbol-snapshots";
    private static final int HYBRID_LIMIT = 20;
    private static final int IMPACT_ROOT_CANDIDATES = 25;
    private static final int IMPACT_ROOTS = 3;

    private SnapshotScalabilityBenchmark() {
    }

    public static void main(String[] arguments) throws Exception {
        Options options = Options.parse(arguments);
        Files.createDirectories(options.out());
        environment(options);

        MinosApplication application = MinosApplication.open(options.home());
        RegisteredProject mainProject = application.projectRegistry()
                .registerProject(options.sourceRoot(), "scalability-main");
        long persistedLimit = persistedSnapshotLimit();
        metric("limits", "all", "persistedSnapshotLimitBytes", persistedLimit);
        metric("limits", "all", "scipArtifactLimitBytes", IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES);
        metric("limits", "all", "queryCacheMaximumWeightBytes", FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES);
        metric("limits", "all", "corpusCacheMaximumWeightBytes", HybridSearchService.DEFAULT_MAX_CORPUS_CACHE_WEIGHT_BYTES);

        CodeKnowledgeSnapshot base = null;
        for (ScipInput input : options.scipInputs()) {
            RegisteredProject project = input.main() ? mainProject : application.projectRegistry()
                    .registerProject(Files.createDirectories(options.out().resolve("ratio-roots").resolve(input.label())),
                            "scalability-" + input.label());
            CodeKnowledgeSnapshot imported = ratio(application, project, input, options, persistedLimit);
            if (input.main()) base = imported;
        }
        if (base == null) throw new IllegalStateException("main SCIP input is required");
        CodeKnowledgeSnapshot full = base;

        // Niveau 1 - chemin produit (magasin fichier, cache de vues) : tranches de fichiers du corpus réel.
        for (double fraction : options.fractions()) {
            String dataset = String.format(Locale.ROOT, "file-f%.2f", fraction);
            CodeKnowledgeSnapshot slice = fraction >= 1.0 ? full : slice(full, fraction);
            metric("size", dataset, "fraction", fraction);
            metric("size", dataset, "symbols", slice.symbols().size());
            metric("size", dataset, "occurrences", slice.occurrences().size());
            metric("size", dataset, "relationships", slice.relationships().size());
            Census census = Census.of(slice);
            census.emit("size", dataset, slice);
            if (census.v2Bytes() > persistedLimit) {
                overLimitPublish(application, mainProject, slice, dataset);
                continue;
            }
            publish(application, mainProject, slice, "scalability-" + dataset, dataset);
            slice = null;
            if (options.enabled("memory")) guarded("memory", dataset, () -> memory(options, mainProject, dataset));
            CodeKnowledgeSnapshot active = application.snapshotStore().loadActiveKnowledge(mainProject.id()).orElseThrow();
            runQuerySections(application, application.hybridSearchService(), application.architectureQuery(),
                    application.snapshotStore(), mainProject, active, options, dataset);
        }

        // Niveau 2 - en mémoire, sans persistance : le corpus réel entier (non persistable en V2) et ses
        // répliques. HybridSearchService interroge SemanticIndexService.status, qui charge le snapshot actif
        // du magasin fichier de l'application même fournisseur sémantique désactivé : un snapshot actif vide
        // rend ce chargement négligeable, et le niveau 2 mesure la recherche elle-même.
        application.snapshotStore().publish(mainProject.id(), "scalability-reset", List.of(), List.of(), List.of());
        InMemorySnapshotStore memoryStore = new InMemorySnapshotStore();
        HybridSearchService memoryHybrid = new HybridSearchService(new ProjectResolver(application.projectRegistry()),
                memoryStore, application.semanticIndexService(), application.semanticSearchService());
        ProjectArchitectureQuery memoryArchitecture = LocalProjectArchitectureQuery.defaults(
                application.projectRegistry(), memoryStore, application.discoveryService());
        for (int k : options.replicas()) {
            String dataset = "mem-k" + k;
            CodeKnowledgeSnapshot scaled = k == 1 ? full : replicate(full, k, mainProject.id());
            metric("size", dataset, "symbols", scaled.symbols().size());
            metric("size", dataset, "occurrences", scaled.occurrences().size());
            metric("size", dataset, "relationships", scaled.relationships().size());
            if (k > 1) Census.of(scaled).emit("size", dataset, scaled);
            if (options.enabled("memory")) guarded("memory", dataset, () -> indexHeap(scaled, dataset));
            memoryStore.put(scaled);
            runQuerySections(application, memoryHybrid, memoryArchitecture, memoryStore, mainProject, scaled,
                    options, dataset);
        }
    }

    private static void runQuerySections(MinosApplication application, HybridSearchService hybrid,
                                         ProjectArchitectureQuery architecture, CodeKnowledgeSnapshotStore store,
                                         RegisteredProject project, CodeKnowledgeSnapshot active, Options options,
                                         String dataset) {
        if (options.enabled("hybrid")) guarded("hybrid", dataset, () -> hybrid(hybrid, project, active, options, dataset));
        if (options.enabled("impact")) guarded("impact", dataset, () -> impact(active, options, dataset));
        if (options.enabled("architecture") && options.architectureDatasets().contains(dataset)) {
            guarded("architecture", dataset, () -> architecture(application.discoveryService(), architecture, store,
                    project, options, dataset));
        }
    }

    /** Une section en échec est consignée comme une mesure et n'arrête pas les autres. */
    private static void guarded(String section, String dataset, CheckedRunnable action) {
        try {
            action.run();
        } catch (IOException | RuntimeException failure) {
            metric(section, dataset, "error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    // ---------------------------------------------------------------- environment

    private static void environment(Options options) {
        Runtime runtime = Runtime.getRuntime();
        metric("env", "jvm", "java.version", System.getProperty("java.version"));
        metric("env", "jvm", "java.vm.name", System.getProperty("java.vm.name"));
        metric("env", "jvm", "java.vm.version", System.getProperty("java.vm.version"));
        metric("env", "jvm", "gc", String.join("+", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList()));
        metric("env", "jvm", "maxHeapBytes", runtime.maxMemory());
        metric("env", "jvm", "availableProcessors", runtime.availableProcessors());
        metric("env", "jvm", "inputArguments", String.join(" ",
                ManagementFactory.getRuntimeMXBean().getInputArguments()));
        metric("env", "os", "name", System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch"));
        metric("env", "run", "warmup", options.warmup());
        metric("env", "run", "iterations", options.iterations());
        metric("env", "run", "ratioIterations", options.ratioIterations());
        metric("env", "run", "loadIterations", options.loadIterations());
        metric("env", "run", "architectureWarmup", options.architectureWarmup());
        metric("env", "run", "architectureIterations", options.architectureIterations());
        metric("env", "run", "profileMillis", options.profileMillis());
    }

    // ---------------------------------------------------------------- ratio SCIP -> snapshot

    private static CodeKnowledgeSnapshot ratio(MinosApplication application, RegisteredProject project, ScipInput input,
                                               Options options, long persistedLimit) throws IOException {
        String dataset = input.label();
        long scipBytes = Files.size(input.path());
        metric("ratio", dataset, "provider", input.provider());
        metric("ratio", dataset, "scipBytes", scipBytes);
        metric("ratio", dataset, "scipArtifactLimitBytes", IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES);

        ScipIngestionLimits.PreflightMetrics preflight = null;
        long[] preflightNanos = new long[options.ratioIterations()];
        for (int index = 0; index < preflightNanos.length; index++) {
            long started = System.nanoTime();
            try (InputStream stream = new BufferedInputStream(Files.newInputStream(input.path()))) {
                preflight = ScipIngestionLimits.DEFAULT.preflight(stream);
            }
            preflightNanos[index] = System.nanoTime() - started;
        }
        metric("ratio", dataset, "preflightDocuments", preflight.documents());
        metric("ratio", dataset, "preflightSymbols", preflight.symbols());
        metric("ratio", dataset, "preflightOccurrences", preflight.occurrences());
        metric("ratio", dataset, "preflightRelationshipFacts", preflight.relationshipFacts());
        Stats.of(preflightNanos).emit("ratio", dataset, "preflight");

        long[] decodeNanos = new long[options.ratioIterations()];
        for (int index = 0; index < decodeNanos.length; index++) {
            long started = System.nanoTime();
            Reference.reachabilityFence(new ScipIndexReader().read(input.path()));
            decodeNanos[index] = System.nanoTime() - started;
        }
        Stats.of(decodeNanos).emit("ratio", dataset, "decode");

        // Import dans un magasin de capture en mémoire : tout l'import produit (gel + sha256, décodage,
        // ingestion) sans l'étape de persistance, quelle que soit la taille du snapshot.
        long heapBefore = settledHeap();
        CodeKnowledgeSnapshot snapshot = null;
        long[] captureNanos = new long[options.ratioIterations()];
        for (int index = 0; index < captureNanos.length; index++) {
            InMemorySnapshotStore capture = new InMemorySnapshotStore();
            snapshot = null;
            long started = System.nanoTime();
            application.scipArtifactImporter().importSnapshot(input.path(),
                    importRequest(project, dataset, "capture-" + index, input), capture);
            captureNanos[index] = System.nanoTime() - started;
            snapshot = capture.loadActiveKnowledge(project.id()).orElseThrow();
        }
        long heapWithSnapshot = settledHeap();
        Stats captureStats = Stats.of(captureNanos);
        captureStats.emit("ratio", dataset, "importWithoutPersistence");
        metric("ratio", dataset, "heapImportedSnapshotBytes", heapWithSnapshot - heapBefore);
        metric("ratio", dataset, "snapshotSymbols", snapshot.symbols().size());
        metric("ratio", dataset, "snapshotOccurrences", snapshot.occurrences().size());
        metric("ratio", dataset, "snapshotRelationships", snapshot.relationships().size());
        metric("ratio", dataset, "localSymbols", snapshot.symbols().stream().filter(symbol -> !symbol.external()).count());

        Census census = Census.of(snapshot);
        census.emit("ratio", dataset, snapshot);
        metric("ratio", dataset, "persistable", census.v2Bytes() <= persistedLimit);
        metric("ratio", dataset, "ratioV2OverScip", ratio(census.v2Bytes(), scipBytes));
        metric("ratio", dataset, "ratioUtf8OverScip", ratio(census.utf8Bytes(), scipBytes));
        metric("ratio", dataset, "ratioStringTableOverScip", ratio(census.stringTableBytes(), scipBytes));
        metric("ratio", dataset, "v2BytesPerPreflightOccurrence", ratio(census.v2Bytes(), preflight.occurrences()));
        metric("ratio", dataset, "v2BytesPerPreflightSymbol", ratio(census.v2Bytes(), preflight.symbols()));
        metric("ratio", dataset, "snapshotOverPreflightOccurrences",
                ratio(snapshot.occurrences().size(), preflight.occurrences()));
        metric("ratio", dataset, "snapshotOverPreflightSymbols", ratio(snapshot.symbols().size(), preflight.symbols()));
        metric("ratio", dataset, "heapOverV2", ratio(heapWithSnapshot - heapBefore, census.v2Bytes()));

        // Import produit dans le vrai magasin fichier : il réussit, ou échoue à la publication.
        long[] importNanos = new long[options.ratioIterations()];
        String outcome = "PUBLISHED";
        for (int index = 0; index < importNanos.length; index++) {
            long started = System.nanoTime();
            try {
                application.scipArtifactImporter().importSnapshot(input.path(),
                        importRequest(project, dataset, "import-" + index, input), application.snapshotStore());
            } catch (IOException refused) {
                outcome = "REFUSED " + refused.getClass().getSimpleName() + ": " + refused.getMessage();
            }
            importNanos[index] = System.nanoTime() - started;
        }
        Stats importStats = Stats.of(importNanos);
        importStats.emit("ratio", dataset, "import");
        metric("ratio", dataset, "importOutcome", outcome);
        metric("ratio", dataset, "persistenceShareOfImportMedian",
                ratio(importStats.median() - captureStats.median(), importStats.median()));
        if (outcome.equals("PUBLISHED")) {
            metric("ratio", dataset, "activeSnapshotFileBytes", activeSnapshotFileBytes(options.home(), project.id()));
        }
        return snapshot;
    }

    /**
     * Même forme que {@code minos import-scip} : l'{@code Origin} recopiée sur chaque entité porte
     * {@code indexRunId = "application-" + snapshotId} (41 caractères pour un identifiant dérivé du SCIP) et la
     * version du fournisseur ; elle pèse sur la taille. Seul le suffixe distingue les imports successifs.
     */
    private static ScipSymbolSnapshotRequest importRequest(RegisteredProject project, String dataset, String suffix,
                                                           ScipInput input) {
        String snapshotId = "scip-" + suffix + "-" + Integer.toHexString(dataset.hashCode());
        snapshotId = snapshotId + "0".repeat(Math.max(0, 29 - snapshotId.length()));
        return new ScipSymbolSnapshotRequest(project.id(), snapshotId, null, input.provider(), input.providerVersion(),
                "application-" + snapshotId, Map.of(), "");
    }

    /** Tas des index de requête construits sur un snapshot déjà en mémoire. */
    private static void indexHeap(CodeKnowledgeSnapshot snapshot, String dataset) {
        long before = settledHeap();
        long started = System.nanoTime();
        InMemoryCodeKnowledgeStore indexes = new InMemoryCodeKnowledgeStore(snapshot);
        long buildNanos = System.nanoTime() - started;
        long after = settledHeap();
        Reference.reachabilityFence(indexes);
        metric("memory", dataset, "indexBuildMs", millis(buildNanos));
        metric("memory", dataset, "heapIndexesBytes", after - before);
    }

    private static long activeSnapshotFileBytes(Path home, UUID projectId) throws IOException {
        Path projectDirectory = home.resolve(SNAPSHOT_STORE_DIRECTORY).resolve(projectId.toString());
        if (!Files.isDirectory(projectDirectory)) return -1L;
        try (var files = Files.list(projectDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".knowledge"))
                    .max(Comparator.comparingLong(SnapshotScalabilityBenchmark::lastModified))
                    .map(SnapshotScalabilityBenchmark::size)
                    .orElse(-1L);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MIN_VALUE;
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            return -1L;
        }
    }

    private static long persistedSnapshotLimit() {
        return SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES;
    }

    // ---------------------------------------------------------------- publication

    private static void publish(MinosApplication application, RegisteredProject project, CodeKnowledgeSnapshot snapshot,
                                String snapshotId, String dataset) throws IOException {
        long started = System.nanoTime();
        application.snapshotStore().publish(project.id(), snapshotId,
                snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        metric("size", dataset, "publishMs", millis(System.nanoTime() - started));
    }

    private static void overLimitPublish(MinosApplication application, RegisteredProject project,
                                         CodeKnowledgeSnapshot snapshot, String dataset) {
        long started = System.nanoTime();
        try {
            application.snapshotStore().publish(project.id(), "scalability-" + dataset + "-over-limit",
                    snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
            metric("size", dataset, "overLimitPublish", "UNEXPECTED_SUCCESS");
        } catch (IOException | RuntimeException failure) {
            metric("size", dataset, "overLimitPublish", "REFUSED " + failure.getClass().getSimpleName());
            metric("size", dataset, "overLimitMessage", String.valueOf(failure.getMessage()));
        }
        metric("size", dataset, "overLimitPublishMs", millis(System.nanoTime() - started));
    }

    // ---------------------------------------------------------------- memory

    private static void memory(Options options, RegisteredProject project, String dataset) throws IOException {
        Path storeRoot = options.home().resolve(SNAPSHOT_STORE_DIRECTORY);
        long[] loadNanos = new long[options.loadIterations()];
        long[] buildNanos = new long[options.loadIterations()];
        FileSymbolSnapshotStore.CacheStats stats = null;
        for (int index = 0; index < loadNanos.length; index++) {
            FileSymbolSnapshotStore cold = new FileSymbolSnapshotStore(storeRoot);
            long started = System.nanoTime();
            SnapshotQueryView view = cold.loadActiveQueryView(project.id()).orElseThrow();
            loadNanos[index] = System.nanoTime() - started;
            buildNanos[index] = view.buildNanos();
            stats = cold.cacheStats();
        }
        Stats.of(loadNanos).emit("memory", dataset, "coldLoadQueryView");
        Stats.of(buildNanos).emit("memory", dataset, "indexBuild");
        metric("memory", dataset, "queryCacheEntriesAfterLoad", stats.entries());
        metric("memory", dataset, "queryCacheWeightBytes", stats.weightBytes());
        metric("memory", dataset, "queryCacheMaximumWeightBytes", stats.maximumWeightBytes());

        long before = settledHeap();
        FileSymbolSnapshotStore cold = new FileSymbolSnapshotStore(storeRoot);
        SnapshotQueryView view = cold.loadActiveQueryView(project.id()).orElseThrow();
        long withView = settledHeap();
        CodeKnowledgeSnapshot snapshotOnly = view.snapshot();
        Reference.reachabilityFence(view);
        Reference.reachabilityFence(cold);
        view = null;
        cold = null;
        long withSnapshotOnly = settledHeap();
        Reference.reachabilityFence(snapshotOnly);
        metric("memory", dataset, "heapQueryViewBytes", withView - before);
        metric("memory", dataset, "heapSnapshotOnlyBytes", withSnapshotOnly - before);
        metric("memory", dataset, "heapIndexesBytes", withView - withSnapshotOnly);
    }

    private static long settledHeap() {
        for (int round = 0; round < 3; round++) {
            System.gc();
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    // ---------------------------------------------------------------- hybrid search

    private static void hybrid(HybridSearchService hybrid, RegisteredProject project, CodeKnowledgeSnapshot active,
                               Options options, String dataset) throws IOException {
        String reference = project.id().toString();
        long buildStarted = System.nanoTime();
        int documents = new SemanticDocumentFactory().build(project, active).size();
        metric("hybrid", dataset, "corpusBuildMs", millis(System.nanoTime() - buildStarted));
        metric("hybrid", dataset, "corpusDocuments", documents);

        long before = settledHeap();
        long started = System.nanoTime();
        hybrid.search(reference, new HybridSearchService.HybridRequest(options.queries().getFirst(), HYBRID_LIMIT, 0.0));
        metric("hybrid", dataset, "firstSearchMs", millis(System.nanoTime() - started));
        long after = settledHeap();
        HybridSearchService.CorpusCacheStats corpus = hybrid.corpusCacheStats();
        metric("hybrid", dataset, "corpusCacheEntries", corpus.entries());
        metric("hybrid", dataset, "corpusCacheWeightBytes", corpus.weightBytes());
        metric("hybrid", dataset, "corpusCacheMaximumWeightBytes", corpus.maximumWeightBytes());
        metric("hybrid", dataset, "heapAfterFirstSearchDeltaBytes", after - before);

        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        for (String query : options.queries()) {
            HybridSearchService.HybridRequest request = new HybridSearchService.HybridRequest(query, HYBRID_LIMIT, 0.0);
            for (int index = 0; index < options.warmup(); index++) hybrid.search(reference, request);
            long[] nanos = new long[options.iterations()];
            long[] allocated = new long[options.iterations()];
            HybridSearchService.HybridResponse response = null;
            for (int index = 0; index < nanos.length; index++) {
                long allocatedBefore = threads.getCurrentThreadAllocatedBytes();
                long queryStarted = System.nanoTime();
                response = hybrid.search(reference, request);
                nanos[index] = System.nanoTime() - queryStarted;
                allocated[index] = threads.getCurrentThreadAllocatedBytes() - allocatedBefore;
            }
            String name = "query[" + query + "]";
            Stats.of(nanos).emit("hybrid", dataset, name);
            metric("hybrid", dataset, name + ".allocatedBytesMedian", Stats.of(allocated).median());
            metric("hybrid", dataset, name + ".hits", response.hits().size());
            metric("hybrid", dataset, name + ".topScore", response.hits().isEmpty() ? 0.0 : response.hits().getFirst().score());
        }

        Profile profile = Profile.record(options, () -> {
            for (String query : options.queries()) {
                hybrid.search(reference, new HybridSearchService.HybridRequest(query, HYBRID_LIMIT, 0.0));
            }
        }, List.of(
                Category.method("normalize", "com.minos.application.semantic.HybridSearchService", "normalize"),
                Category.method("containsTerm", "com.minos.application.semantic.HybridSearchService", "containsTerm"),
                Category.classPrefix("snapshotLoad", "com.minos.storage.local.store."),
                Category.classPrefix("snapshotLoad", "com.minos.store.InMemoryCodeKnowledgeStore"),
                Category.classPrefix("scoreOther", "com.minos.application.semantic.HybridSearchService$LexicalQuery"),
                Category.classPrefix("searchOther", "com.minos.application.semantic.HybridSearchService")));
        profile.emit("hybrid", dataset);
    }

    // ---------------------------------------------------------------- impact analysis

    private static List<String> impactRoots(CodeKnowledgeSnapshot snapshot) {
        Map<String, Integer> incoming = new HashMap<>();
        for (Relationship relationship : snapshot.relationships()) {
            if (relationship.target() != null && relationship.target().type() == CodeEntityType.SYMBOL
                    && relationship.source().type() == CodeEntityType.SYMBOL) {
                incoming.merge(relationship.target().id(), 1, Integer::sum);
            }
        }
        ImpactAnalysisService service = new ImpactAnalysisService();
        List<Symbol> candidates = snapshot.symbols().stream()
                .filter(symbol -> !symbol.external() && !symbol.generated())
                .sorted(Comparator.comparingInt((Symbol symbol) -> incoming.getOrDefault(symbol.id(), 0)).reversed()
                        .thenComparing(Symbol::id))
                .limit(IMPACT_ROOT_CANDIDATES)
                .toList();
        Map<String, Integer> reach = new LinkedHashMap<>();
        for (Symbol candidate : candidates) {
            reach.put(candidate.id(), service.analyze(snapshot,
                    new ImpactAnalysisRequest(candidate.id(), 32, 10_000)).impacts().size());
        }
        return reach.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(IMPACT_ROOTS)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String leafRoot(CodeKnowledgeSnapshot snapshot) {
        Set<String> targets = new HashSet<>();
        for (Relationship relationship : snapshot.relationships()) {
            if (relationship.target() != null) targets.add(relationship.target().id());
        }
        return snapshot.symbols().stream()
                .filter(symbol -> !symbol.external() && !symbol.generated() && !targets.contains(symbol.id()))
                .map(Symbol::id)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static void impact(CodeKnowledgeSnapshot snapshot, Options options, String dataset) throws IOException {
        if (snapshot.symbols().stream().allMatch(symbol -> symbol.external() || symbol.generated())) {
            metric("impact", dataset, "skipped", "no local symbol");
            return;
        }
        List<String> roots = impactRoots(snapshot);
        String leafRoot = leafRoot(snapshot);
        ImpactAnalysisService service = new ImpactAnalysisService();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        metric("impact", dataset, "relationships", snapshot.relationships().size());

        // Coût fixe : une racine sans arête entrante exécute la préparation de chaque appel (table des
        // symboles, index des arêtes entrantes, limitations de base, tri final) avec une traversée vide.
        if (leafRoot != null) {
            measureImpact(service, snapshot, new ImpactAnalysisRequest(leafRoot, 1, 1), options, threads, dataset, "setupOnly");
        }

        for (int index = 0; index < roots.size(); index++) {
            String root = roots.get(index);
            String prefix = "root" + index;
            Symbol symbol = snapshot.symbols().stream().filter(candidate -> candidate.id().equals(root)).findFirst().orElseThrow();
            metric("impact", dataset, prefix + ".name", symbol.qualifiedName() == null ? symbol.name() : symbol.qualifiedName());
            measureImpact(service, snapshot, ImpactAnalysisRequest.defaults(root), options, threads, dataset, prefix + ".default");
            measureImpact(service, snapshot, new ImpactAnalysisRequest(root, 32, 10_000), options, threads, dataset, prefix + ".deepFull");
            measureImpact(service, snapshot, new ImpactAnalysisRequest(root, 32, 10), options, threads, dataset, prefix + ".deepMax10");
        }

        if (roots.isEmpty()) return;
        ImpactAnalysisRequest deep = new ImpactAnalysisRequest(roots.getFirst(), 32, 10);
        Profile profile = Profile.record(options, () -> service.analyze(snapshot, deep), List.of(
                Category.methodContains("incomingIndex", "com.minos.impact.ImpactAnalysisService", "incomingRelationships"),
                Category.methodContains("baselineLimitations", "com.minos.impact.ImpactAnalysisService", "baselineLimitations"),
                Category.methodContains("pathBuilding", "com.minos.impact.ImpactAnalysisService", "append"),
                Category.methodContains("pathBuilding", "com.minos.impact.ImpactAnalysisService", "pathSignature"),
                Category.classPrefix("analyzeBody", "com.minos.impact.ImpactAnalysisService")));
        profile.emit("impact", dataset + ".root0.deepMax10");
    }

    private static void measureImpact(ImpactAnalysisService service, CodeKnowledgeSnapshot snapshot,
                                      ImpactAnalysisRequest request, Options options,
                                      com.sun.management.ThreadMXBean threads, String dataset, String name) {
        for (int index = 0; index < options.warmup(); index++) service.analyze(snapshot, request);
        long[] nanos = new long[options.iterations()];
        long[] allocated = new long[options.iterations()];
        ImpactAnalysisReport report = null;
        for (int index = 0; index < nanos.length; index++) {
            long allocatedBefore = threads.getCurrentThreadAllocatedBytes();
            long started = System.nanoTime();
            report = service.analyze(snapshot, request);
            nanos[index] = System.nanoTime() - started;
            allocated[index] = threads.getCurrentThreadAllocatedBytes() - allocatedBefore;
        }
        Stats.of(nanos).emit("impact", dataset, name);
        metric("impact", dataset, name + ".allocatedBytesMedian", Stats.of(allocated).median());
        metric("impact", dataset, name + ".impacts", report.impacts().size());
        metric("impact", dataset, name + ".tests", report.potentiallyImpactedTests().size());
        metric("impact", dataset, name + ".limitations", report.limitations().toString());
    }

    // ---------------------------------------------------------------- architecture

    private static void architecture(ProjectDiscoveryService discovery, ProjectArchitectureQuery architecture,
                                     CodeKnowledgeSnapshotStore store, RegisteredProject project, Options options,
                                     String dataset) throws IOException {
        String reference = project.id().toString();
        Path root = project.rootPath();

        long[] discoverNanos = timed(options.architectureWarmup(), options.architectureIterations(), () -> Reference.reachabilityFence(discovery.discover(root)));
        long[] loadNanos = timed(options.architectureWarmup(), options.architectureIterations(), () -> Reference.reachabilityFence(store.loadActiveKnowledge(project.id())));
        long[] overviewNanos = timed(options.architectureWarmup(), options.architectureIterations(), () -> Reference.reachabilityFence(architecture.getArchitectureOverview(reference)));
        long[] intelligenceNanos = timed(options.architectureWarmup(), options.architectureIterations(), () -> Reference.reachabilityFence(
                architecture.getArchitectureIntelligence(reference)));
        Stats discover = Stats.of(discoverNanos);
        Stats intelligence = Stats.of(intelligenceNanos);
        discover.emit("architecture", dataset, "discovery");
        Stats.of(loadNanos).emit("architecture", dataset, "snapshotLoadActive");
        Stats.of(overviewNanos).emit("architecture", dataset, "overview");
        intelligence.emit("architecture", dataset, "intelligence");
        metric("architecture", dataset, "discoveryShareOfIntelligenceMedian", ratio(discover.median(), intelligence.median()));

        for (Path extraRoot : options.extraDiscoveryRoots()) {
            Stats.of(timed(options.architectureWarmup(), options.architectureIterations(), () -> Reference.reachabilityFence(discovery.discover(extraRoot))))
                    .emit("architecture", dataset, "discovery[" + extraRoot + "]");
        }

        Profile profile = Profile.record(options, () -> architecture.getArchitectureIntelligence(reference), List.of(
                Category.methodContains("discovery.moduleRoots", "com.minos.discovery.ProjectDiscoveryService",
                        "discoverModuleRoots"),
                Category.methodContains("discovery.sourceRoots", "com.minos.discovery.ProjectDiscoveryService",
                        "discoverSourceRoots"),
                Category.classPrefix("discovery.other", "com.minos.discovery."),
                Category.classPrefix("snapshotLoad", "com.minos.storage.local.store."),
                Category.classPrefix("snapshotLoad", "com.minos.store.InMemoryCodeKnowledgeStore"),
                Category.classPrefix("analyses", "com.minos.architecture.Architecture"),
                Category.classPrefix("queryOther", "com.minos.architecture.LocalProjectArchitectureQuery")));
        profile.emit("architecture", dataset + ".intelligence");
    }

    private static long[] timed(int warmup, int iterations, CheckedRunnable action) throws IOException {
        for (int index = 0; index < warmup; index++) action.run();
        long[] nanos = new long[iterations];
        for (int index = 0; index < nanos.length; index++) {
            long started = System.nanoTime();
            action.run();
            nanos[index] = System.nanoTime() - started;
        }
        return nanos;
    }

    // ---------------------------------------------------------------- replication

    static CodeKnowledgeSnapshot replicate(CodeKnowledgeSnapshot base, int replicas, UUID projectId) {
        String project = projectId.toString();
        List<Symbol> symbols = new ArrayList<>(base.symbols().size() * replicas);
        List<SymbolOccurrence> occurrences = new ArrayList<>(base.occurrences().size() * replicas);
        List<Relationship> relationships = new ArrayList<>(base.relationships().size() * replicas);
        for (int replica = 0; replica < replicas; replica++) {
            String prefix = replica == 0 ? "" : "r" + replica + "~";
            for (Symbol symbol : base.symbols()) {
                symbols.add(new Symbol(prefix + symbol.id(), prefix + symbol.symbolKey(), symbol.identityQuality(),
                        project, symbol.moduleId(), symbol.fileId(), prefixed(prefix, symbol.parentSymbolId()),
                        symbol.kind(), symbol.name(), prefixed(prefix, symbol.qualifiedName()), symbol.signature(),
                        symbol.language(), symbol.location(), symbol.resolutionStatus(), symbol.origin(),
                        symbol.external(), symbol.generated(), prefixed(prefix, symbol.providerReferences())));
            }
            for (SymbolOccurrence occurrence : base.occurrences()) {
                occurrences.add(new SymbolOccurrence(prefix + occurrence.id(), project,
                        prefixed(prefix, occurrence.symbolRef()), occurrence.location(), occurrence.roles(),
                        occurrence.resolutionStatus(), occurrence.origin(),
                        prefixed(prefix, occurrence.providerReferences())));
            }
            for (Relationship relationship : base.relationships()) {
                relationships.add(new Relationship(prefix + relationship.id(), project,
                        prefixed(prefix, relationship.source()), prefixed(prefix, relationship.target()),
                        relationship.unresolvedTarget(), relationship.kind(), relationship.location(),
                        relationship.resolutionStatus(), relationship.nature(), relationship.confidence(),
                        relationship.origin(), relationship.evidence().stream()
                        .map(evidence -> new Evidence(evidence.type(), evidence.description(),
                                prefixed(prefix, evidence.source()), prefixed(prefix, evidence.target()),
                                evidence.location(), evidence.weight()))
                        .toList()));
            }
        }
        return new CodeKnowledgeSnapshot(projectId, base.snapshotId() + "-x" + replicas, symbols, occurrences, relationships);
    }

    private static String prefixed(String prefix, String value) {
        return value == null || prefix.isEmpty() ? value : prefix + value;
    }

    private static Set<ProviderReference> prefixed(String prefix, Set<ProviderReference> references) {
        if (prefix.isEmpty()) return references;
        Set<ProviderReference> result = new LinkedHashSet<>();
        for (ProviderReference reference : references) {
            result.add(new ProviderReference(reference.providerId(), prefix + reference.externalId()));
        }
        return result;
    }

    private static SymbolReference prefixed(String prefix, SymbolReference reference) {
        return reference instanceof ResolvedSymbolReference resolved && !prefix.isEmpty()
                ? new ResolvedSymbolReference(prefix + resolved.symbolId()) : reference;
    }

    private static CodeEntityRef prefixed(String prefix, CodeEntityRef reference) {
        return reference == null || prefix.isEmpty() || reference.type() != CodeEntityType.SYMBOL
                ? reference : new CodeEntityRef(reference.type(), prefix + reference.id());
    }

    // ---------------------------------------------------------------- output helpers

    static void metric(String section, String dataset, String name, Object value) {
        System.out.println("METRIC\t" + section + "\t" + dataset + "\t" + name + "\t" + value);
    }

    private static String millis(long nanoseconds) {
        return String.format(Locale.ROOT, "%.3f", nanoseconds / 1_000_000.0);
    }

    private static String ratio(double numerator, double denominator) {
        return denominator == 0.0 ? "NaN" : String.format(Locale.ROOT, "%.4f", numerator / denominator);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws IOException;
    }

    /** Médiane et p95 (rang le plus proche) d'un échantillon. */
    record Stats(long median, long p95, long min, long max, int count) {
        static Stats of(long[] samples) {
            long[] sorted = samples.clone();
            Arrays.sort(sorted);
            return new Stats(percentile(sorted, 0.50), percentile(sorted, 0.95), sorted[0], sorted[sorted.length - 1],
                    sorted.length);
        }

        private static long percentile(long[] sorted, double percentile) {
            int index = Math.max(0, (int) Math.ceil(sorted.length * percentile) - 1);
            return sorted[Math.min(index, sorted.length - 1)];
        }

        void emit(String section, String dataset, String name) {
            metric(section, dataset, name + ".medianMs", millis(median));
            metric(section, dataset, name + ".p95Ms", millis(p95));
            metric(section, dataset, name + ".minMs", millis(min));
            metric(section, dataset, name + ".maxMs", millis(max));
            metric(section, dataset, name + ".n", count);
        }
    }

    // ---------------------------------------------------------------- persisted-size census

    /**
     * Parcourt un snapshot dans l'ordre des champs du codec V2 ({@code SnapshotBinaryCodecSupport}) et compte
     * les octets fixes et les champs chaîne, pour en tirer la composition (part des chaînes, catégories) et
     * des projections (UTF-8, table de chaînes). La taille V2 qu'il prédit est comparée à celle du vrai
     * encodeur ({@link SnapshotCodecV2#encodedSize}) sur chaque jeu ; les tailles des autres formats ne sont
     * rapportées que depuis leurs vrais encodeurs, jamais depuis ce recensement.
     */
    static final class Census {
        private long fixed;
        private long slots;
        private long chars;
        private long utf8;
        private long nonAsciiChars;
        private long loneSurrogates;
        private long distinctUtf8;
        private final Set<String> distinct = new HashSet<>();
        private final Map<String, long[]> categories = new TreeMap<>();

        static Census of(CodeKnowledgeSnapshot snapshot) {
            Census census = new Census();
            census.fixed(4 + 4 + 16);
            census.string("misc", snapshot.snapshotId());
            census.fixed(4);
            snapshot.symbols().forEach(census::symbol);
            census.fixed(4);
            snapshot.occurrences().forEach(census::occurrence);
            census.fixed(4);
            snapshot.relationships().forEach(census::relationship);
            return census;
        }

        long v2Bytes() {
            return fixed + 4L * slots + 2L * chars;
        }

        long utf8Bytes() {
            return fixed + 4L * slots + utf8;
        }

        /** Même cadrage avec une table de chaînes par snapshot : chaque champ chaîne devient un index de 4 octets. */
        long stringTableBytes() {
            return fixed + 4L * slots + 4L * distinct.size() + distinctUtf8;
        }

        void emit(String section, String dataset, CodeKnowledgeSnapshot snapshot) {
            metric(section, dataset, "v2Bytes", v2Bytes());
            metric(section, dataset, "fixedBytes", fixed);
            metric(section, dataset, "stringSlots", slots);
            metric(section, dataset, "stringChars", chars);
            metric(section, dataset, "stringBytesV2", 4L * slots + 2L * chars);
            metric(section, dataset, "stringShareOfV2", ratio(4L * slots + 2L * chars, v2Bytes()));
            metric(section, dataset, "nonAsciiChars", nonAsciiChars);
            metric(section, dataset, "loneSurrogates", loneSurrogates);
            metric(section, dataset, "utf8Bytes", utf8Bytes());
            metric(section, dataset, "utf8OverV2", ratio(utf8Bytes(), v2Bytes()));
            metric(section, dataset, "distinctStrings", distinct.size());
            metric(section, dataset, "stringTableBytes", stringTableBytes());
            metric(section, dataset, "stringTableOverV2", ratio(stringTableBytes(), v2Bytes()));
            for (Map.Entry<String, long[]> entry : categories.entrySet()) {
                long bytes = 4L * entry.getValue()[0] + 2L * entry.getValue()[1];
                metric(section, dataset, "category." + entry.getKey() + ".v2Bytes", bytes);
                metric(section, dataset, "category." + entry.getKey() + ".shareOfV2", ratio(bytes, v2Bytes()));
            }
            try {
                long encoded = new SnapshotCodecV2().encodedSize(snapshot);
                metric(section, dataset, "encodedV2Bytes", encoded);
                metric(section, dataset, "censusMatchesEncoder", encoded == v2Bytes());
            } catch (IOException refused) {
                metric(section, dataset, "encodedV2Bytes", "REFUSED " + refused.getMessage());
            }
        }

        private void fixed(long bytes) {
            fixed += bytes;
        }

        private void string(String category, String value) {
            slots++;
            long[] counters = categories.computeIfAbsent(category, ignored -> new long[2]);
            counters[0]++;
            if (value == null) return;
            chars += value.length();
            counters[1] += value.length();
            long encoded = utf8Length(value);
            utf8 += encoded;
            if (distinct.add(value)) distinctUtf8 += encoded;
        }

        private long utf8Length(String value) {
            long bytes = 0L;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current < 0x80) {
                    bytes += 1;
                    continue;
                }
                nonAsciiChars++;
                if (current < 0x800) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(current) && index + 1 < value.length()
                        && Character.isLowSurrogate(value.charAt(index + 1))) {
                    bytes += 4;
                    index++;
                    nonAsciiChars++;
                } else {
                    if (Character.isSurrogate(current)) loneSurrogates++;
                    bytes += 3;
                }
            }
            return bytes;
        }

        private void symbol(Symbol symbol) {
            string("entityId", symbol.id());
            string("symbolText", symbol.symbolKey());
            string("enum", symbol.identityQuality().name());
            string("projectId", symbol.projectId());
            string("misc", symbol.moduleId());
            string("fileId", symbol.fileId());
            string("entityId", symbol.parentSymbolId());
            string("enum", symbol.kind().name());
            string("symbolText", symbol.name());
            string("symbolText", symbol.qualifiedName());
            string("symbolText", symbol.signature());
            string("misc", symbol.language());
            location(symbol.location());
            string("enum", symbol.resolutionStatus().name());
            origin(symbol.origin());
            fixed(2);
            providerReferences(symbol.providerReferences());
        }

        private void occurrence(SymbolOccurrence occurrence) {
            string("entityId", occurrence.id());
            string("projectId", occurrence.projectId());
            fixed(1);
            if (occurrence.symbolRef() instanceof ResolvedSymbolReference resolved) {
                string("entityId", resolved.symbolId());
            } else if (occurrence.symbolRef() instanceof UnresolvedSymbolReference unresolved) {
                string("symbolText", unresolved.displayName());
                string("symbolText", unresolved.qualifiedNameCandidate());
                string("misc", unresolved.language());
                string("misc", unresolved.reason());
                providerReferences(unresolved.providerReferences());
            } else {
                throw new IllegalStateException("unsupported symbol reference: " + occurrence.symbolRef());
            }
            location(occurrence.location());
            fixed(4);
            occurrence.roles().forEach(role -> string("enum", role.name()));
            string("enum", occurrence.resolutionStatus().name());
            origin(occurrence.origin());
            providerReferences(occurrence.providerReferences());
        }

        private void relationship(Relationship relationship) {
            string("entityId", relationship.id());
            string("projectId", relationship.projectId());
            entityReference(relationship.source());
            optionalEntityReference(relationship.target());
            string("misc", relationship.unresolvedTarget());
            string("enum", relationship.kind().name());
            location(relationship.location());
            string("enum", relationship.resolutionStatus().name());
            string("enum", relationship.nature().name());
            fixed(relationship.confidence() == null ? 1 : 9);
            origin(relationship.origin());
            fixed(4);
            for (Evidence evidence : relationship.evidence()) {
                string("enum", evidence.type().name());
                string("misc", evidence.description());
                optionalEntityReference(evidence.source());
                optionalEntityReference(evidence.target());
                location(evidence.location());
                fixed(evidence.weight() == null ? 1 : 9);
            }
        }

        private void entityReference(CodeEntityRef reference) {
            string("enum", reference.type().name());
            string("entityId", reference.id());
        }

        private void optionalEntityReference(CodeEntityRef reference) {
            fixed(1);
            if (reference != null) entityReference(reference);
        }

        private void location(SymbolLocation location) {
            fixed(1);
            if (location == null) return;
            string("fileId", location.fileId());
            fixed(16);
            string("enum", location.positionEncoding().name());
        }

        private void origin(Origin origin) {
            string("origin", origin.providerId());
            string("origin", origin.providerType());
            string("origin", origin.providerVersion());
            string("origin", origin.indexRunId());
            string("enum", origin.sourceType().name());
        }

        private void providerReferences(Set<ProviderReference> references) {
            fixed(4);
            for (ProviderReference reference : references) {
                string("providerRef", reference.providerId());
                string("providerRef", reference.externalId());
            }
        }
    }

    // ---------------------------------------------------------------- sampling profile (JFR)

    /**
     * Catégorie de pile. Les catégories sont essayées par priorité : un échantillon appartient à la première
     * qui reconnaît l'une des trames de sa pile ; une méthode englobante (par exemple {@code analyze}) passe
     * donc en dernier.
     */
    record Category(String name, Predicate<RecordedFrame> matcher) {
        static Category method(String name, String type, String method) {
            return new Category(name, frame -> type.equals(frame.getMethod().getType().getName())
                    && method.equals(frame.getMethod().getName()));
        }

        static Category methodContains(String name, String type, String fragment) {
            return new Category(name, frame -> type.equals(frame.getMethod().getType().getName())
                    && frame.getMethod().getName().contains(fragment));
        }

        static Category classPrefix(String name, String prefix) {
            return new Category(name, frame -> frame.getMethod().getType().getName().startsWith(prefix));
        }
    }

    /**
     * Échantillons Java ({@code jdk.ExecutionSample}) et natifs ({@code jdk.NativeMethodSample} : fil bloqué
     * en code natif, entrées-sorties par exemple) du seul fil de mesure, par catégorie. Le travail est répété
     * jusqu'à remplir la fenêtre d'échantillonnage, pour que le nombre d'échantillons ait un sens.
     */
    record Profile(long javaSamples, Map<String, Long> javaByCategory, long nativeSamples,
                   Map<String, Long> nativeByCategory, long wallNanos, int runs) {
        private static final String JAVA_SAMPLE = "jdk.ExecutionSample";
        private static final String NATIVE_SAMPLE = "jdk.NativeMethodSample";

        static Profile record(Options options, CheckedRunnable work, List<Category> categories) throws IOException {
            long threadId = Thread.currentThread().threadId();
            Path dump = Files.createTempFile(options.out(), "profile-", ".jfr");
            long wall;
            int runs = 0;
            try (Recording recording = new Recording()) {
                recording.enable(JAVA_SAMPLE).withPeriod(Duration.ofMillis(1)).withStackTrace();
                recording.enable(NATIVE_SAMPLE).withPeriod(Duration.ofMillis(10)).withStackTrace();
                recording.setToDisk(true);
                recording.start();
                long started = System.nanoTime();
                long deadline = started + Duration.ofMillis(options.profileMillis()).toNanos();
                do {
                    work.run();
                    runs++;
                } while (System.nanoTime() < deadline);
                wall = System.nanoTime() - started;
                recording.stop();
                recording.dump(dump);
            }
            Map<String, Long> javaCounts = emptyCounts(categories);
            Map<String, Long> nativeCounts = emptyCounts(categories);
            long javaTotal = 0L;
            long nativeTotal = 0L;
            try {
                for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
                    String type = event.getEventType().getName();
                    boolean javaSample = JAVA_SAMPLE.equals(type);
                    if (!javaSample && !NATIVE_SAMPLE.equals(type)) continue;
                    RecordedThread thread = event.getThread("sampledThread");
                    if (thread == null || thread.getJavaThreadId() != threadId) continue;
                    String category = classify(event.getStackTrace(), categories);
                    if (javaSample) {
                        javaTotal++;
                        javaCounts.merge(category, 1L, Long::sum);
                    } else {
                        nativeTotal++;
                        nativeCounts.merge(category, 1L, Long::sum);
                    }
                }
            } finally {
                Files.deleteIfExists(dump);
            }
            return new Profile(javaTotal, javaCounts, nativeTotal, nativeCounts, wall, runs);
        }

        private static Map<String, Long> emptyCounts(List<Category> categories) {
            Map<String, Long> counts = new LinkedHashMap<>();
            categories.forEach(category -> counts.putIfAbsent(category.name(), 0L));
            counts.put("other", 0L);
            return counts;
        }

        private static String classify(RecordedStackTrace stack, List<Category> categories) {
            if (stack == null) return "other";
            List<RecordedFrame> frames = stack.getFrames().stream().filter(RecordedFrame::isJavaFrame).toList();
            for (Category category : categories) {
                if (frames.stream().anyMatch(category.matcher())) return category.name();
            }
            return "other";
        }

        void emit(String section, String dataset) {
            metric(section, dataset, "profile.runs", runs);
            metric(section, dataset, "profile.wallMs", millis(wallNanos));
            metric(section, dataset, "profile.javaSamples", javaSamples);
            metric(section, dataset, "profile.nativeSamples", nativeSamples);
            for (Map.Entry<String, Long> entry : javaByCategory.entrySet()) {
                metric(section, dataset, "profile.java." + entry.getKey() + ".share", ratio(entry.getValue(), javaSamples));
            }
            for (Map.Entry<String, Long> entry : nativeByCategory.entrySet()) {
                if (entry.getValue() == 0L) continue;
                metric(section, dataset, "profile.native." + entry.getKey() + ".share",
                        ratio(entry.getValue(), nativeSamples));
            }
        }
    }

    // ---------------------------------------------------------------- options

    record ScipInput(String label, String provider, Path path, String providerVersion, boolean main) {
    }

    record Options(Path home, Path sourceRoot, Path out, List<ScipInput> scipInputs, List<Double> fractions,
                   List<Integer> replicas, Set<String> architectureDatasets, List<Path> extraDiscoveryRoots,
                   int warmup, int iterations, int ratioIterations, int loadIterations, long profileMillis,
                   int architectureWarmup, int architectureIterations,
                   List<String> queries, Set<String> sections) {

        static Options parse(String[] arguments) {
            Map<String, List<String>> values = new HashMap<>();
            for (String argument : arguments) {
                if (!argument.startsWith("--") || !argument.contains("=")) {
                    throw new IllegalArgumentException("expected --name=value, got: " + argument);
                }
                int separator = argument.indexOf('=');
                values.computeIfAbsent(argument.substring(2, separator), ignored -> new ArrayList<>())
                        .add(argument.substring(separator + 1));
            }
            List<ScipInput> inputs = new ArrayList<>();
            inputs.add(scipInput(required(values, "main-scip"), true));
            for (String extra : values.getOrDefault("ratio-scip", List.of())) inputs.add(scipInput(extra, false));
            return new Options(
                    Path.of(required(values, "home")).toAbsolutePath().normalize(),
                    Path.of(required(values, "source-root")).toAbsolutePath().normalize(),
                    Path.of(required(values, "out")).toAbsolutePath().normalize(),
                    List.copyOf(inputs),
                    Arrays.stream(optional(values, "fractions", "0.25,0.5,1.0").split(","))
                            .map(String::trim).map(Double::parseDouble).toList(),
                    Arrays.stream(optional(values, "replicas", "1,2,3").split(","))
                            .map(String::trim).map(Integer::parseInt).toList(),
                    Set.of(optional(values, "architecture-datasets", "mem-k1").split(",")),
                    values.getOrDefault("extra-discovery-root", List.of()).stream()
                            .map(value -> Path.of(value).toAbsolutePath().normalize()).toList(),
                    Integer.parseInt(optional(values, "warmup", "3")),
                    Integer.parseInt(optional(values, "iterations", "15")),
                    Integer.parseInt(optional(values, "ratio-iterations", "3")),
                    Integer.parseInt(optional(values, "load-iterations", "5")),
                    Long.parseLong(optional(values, "profile-millis", "5000")),
                    Integer.parseInt(optional(values, "architecture-warmup", "1")),
                    Integer.parseInt(optional(values, "architecture-iterations", "5")),
                    List.of(optional(values, "queries", "snapshot store").split(";")),
                    Set.of(optional(values, "sections", "memory,hybrid,impact,architecture").split(",")));
        }

        /** {@code libellé|fournisseur|chemin[|version du fournisseur]}. */
        private static ScipInput scipInput(String value, boolean main) {
            String[] parts = value.split("\\|", 4);
            if (parts.length < 3) throw new IllegalArgumentException("expected label|provider|path[|version], got: " + value);
            return new ScipInput(parts[0], parts[1], Path.of(parts[2]).toAbsolutePath().normalize(),
                    parts.length == 4 ? parts[3] : null, main);
        }

        private static String required(Map<String, List<String>> values, String name) {
            List<String> found = values.get(name);
            if (found == null || found.isEmpty()) throw new IllegalArgumentException("missing --" + name);
            return found.getLast();
        }

        private static String optional(Map<String, List<String>> values, String name, String fallback) {
            List<String> found = values.get(name);
            return found == null || found.isEmpty() ? fallback : found.getLast();
        }

        boolean enabled(String section) {
            return sections.contains(section);
        }
    }

    // ---------------------------------------------------------------- slicing and in-memory store

    /**
     * Garde une fraction stable des fichiers source, choisie par hachage : leurs symboles, leurs occurrences et
     * les relations dont le symbole source est gardé. Échantillonner des fichiers entiers garde les
     * distributions par fichier du corpus réel.
     */
    static CodeKnowledgeSnapshot slice(CodeKnowledgeSnapshot base, double fraction) {
        int threshold = (int) Math.round(fraction * 1_000);
        Predicate<String> keptFile = fileId -> fileId != null && Math.floorMod(fileId.hashCode(), 1_000) < threshold;
        List<Symbol> symbols = base.symbols().stream()
                .filter(symbol -> symbol.external()
                        || keptFile.test(symbol.location() == null ? symbol.fileId() : symbol.location().fileId()))
                .toList();
        Set<String> keptSymbols = new HashSet<>();
        symbols.forEach(symbol -> keptSymbols.add(symbol.id()));
        List<SymbolOccurrence> occurrences = base.occurrences().stream()
                .filter(occurrence -> keptFile.test(occurrence.location().fileId()))
                .toList();
        List<Relationship> relationships = base.relationships().stream()
                .filter(relationship -> relationship.source().type() == CodeEntityType.SYMBOL
                        ? keptSymbols.contains(relationship.source().id())
                        : relationship.location() != null && keptFile.test(relationship.location().fileId()))
                .toList();
        return new CodeKnowledgeSnapshot(base.projectId(),
                base.snapshotId() + String.format(Locale.ROOT, "-f%.2f", fraction), symbols, occurrences, relationships);
    }

    /** Un snapshot actif par projet, tenu en mémoire : la persistance est hors du chemin mesuré. */
    static final class InMemorySnapshotStore implements CodeKnowledgeSnapshotStore {
        private static final String NO_FILE_SHA256 = "0".repeat(64);
        private final Map<UUID, CodeKnowledgeSnapshot> active = new HashMap<>();

        void put(CodeKnowledgeSnapshot snapshot) {
            active.put(snapshot.projectId(), snapshot);
        }

        @Override
        public SymbolSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols) {
            put(new CodeKnowledgeSnapshot(projectId, snapshotId, List.copyOf(symbols), List.of(), List.of()));
            return new SymbolSnapshot(projectId, snapshotId, List.copyOf(symbols));
        }

        @Override
        public CodeKnowledgeSnapshot publish(UUID projectId, String snapshotId, Collection<Symbol> symbols,
                                             Collection<SymbolOccurrence> occurrences,
                                             Collection<Relationship> relationships) {
            CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(projectId, snapshotId, List.copyOf(symbols),
                    List.copyOf(occurrences), List.copyOf(relationships));
            put(snapshot);
            return snapshot;
        }

        @Override
        public Optional<SymbolSnapshot> loadActive(UUID projectId) {
            return loadActiveKnowledge(projectId).map(snapshot ->
                    new SymbolSnapshot(snapshot.projectId(), snapshot.snapshotId(), snapshot.symbols()));
        }

        @Override
        public Optional<CodeKnowledgeSnapshot> loadActiveKnowledge(UUID projectId) {
            return Optional.ofNullable(active.get(projectId));
        }

        @Override
        public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) {
            return loadActiveKnowledge(projectId).map(snapshot -> {
                long started = System.nanoTime();
                InMemoryCodeKnowledgeStore indexes = new InMemoryCodeKnowledgeStore(snapshot);
                return new SnapshotQueryView(new SnapshotDescriptor(2, snapshot.snapshotId(), "in-memory",
                        NO_FILE_SHA256, snapshot.symbols().size(), snapshot.occurrences().size(),
                        snapshot.relationships().size()), snapshot, indexes, System.nanoTime() - started);
            });
        }
    }
}
