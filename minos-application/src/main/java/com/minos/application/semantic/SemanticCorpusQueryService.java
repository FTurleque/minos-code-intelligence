package com.minos.application.semantic;

import com.minos.application.resolution.ProjectResolver;
import com.minos.context.LocalSourceReader;
import com.minos.domain.PositionEncoding;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolLocation;
import com.minos.io.Sha256;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVectorStore;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.cache.WeightedLruCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Read-only consultation of the semantic documents of a project: paged list, detail of one document and its
 * nearest documents. Nothing here writes, embeds or synchronizes: every answer is read from the stored index and
 * names the index it was read from.
 */
public final class SemanticCorpusQueryService {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_NEIGHBORS = 200;
    public static final int MAX_FILTER_UTF8_BYTES = 1_024;
    public static final int MAX_STABLE_KEY_UTF8_BYTES = 8 * 1_024;
    public static final int MAX_CURSOR_CHARACTERS = 16 * 1_024;

    /** Documents read for one filtered page before the page is returned short, with a cursor to go on. */
    static final int MAX_SCANNED_DOCUMENTS = 2_000;

    static final String INDEX_MISSING = "SEMANTIC_INDEX_MISSING";
    static final String SYMBOLS_UNRESOLVED = "SEMANTIC_DOCUMENT_SYMBOLS_UNRESOLVED_INDEX_NOT_ALIGNED";
    static final String FILTER_SCAN_BOUNDED = "SEMANTIC_DOCUMENT_FILTER_SCAN_BOUNDED";
    static final String SCORE_IS_COSINE = "VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT";

    private static final String SEPARATOR = "\u001f";
    private static final String RECORD_SEPARATOR = "\u001e";
    private static final int SOURCE_CONTEXT_LINES = 2;
    private static final int SOURCE_MAX_TOKENS = 768;

    private final ProjectRegistry registry;
    private final ProjectResolver projects;
    private final CodeKnowledgeSnapshotStore snapshots;
    private final SemanticIndexService indexService;
    private final SemanticSearchService semanticSearch;
    private final HybridSearchService hybridSearch;

    /** Projects whose fingerprint, a few bytes each, is kept; and projects whose symbols, far heavier, are. */
    static final int MAX_FINGERPRINTED_PROJECTS = 64;
    static final int MAX_SYMBOL_LOOKUPS = 4;

    private final Object cacheLock = new Object();
    private final WeightedLruCache<String, FingerprintedIndex> fingerprints =
            new WeightedLruCache<>(MAX_FINGERPRINTED_PROJECTS, MAX_FINGERPRINTED_PROJECTS);
    private final WeightedLruCache<String, SymbolLookup> lookups =
            new WeightedLruCache<>(MAX_SYMBOL_LOOKUPS, MAX_SYMBOL_LOOKUPS);

    public SemanticCorpusQueryService(ProjectRegistry registry, ProjectResolver projects,
                                      CodeKnowledgeSnapshotStore snapshots, SemanticIndexService indexService,
                                      SemanticSearchService semanticSearch, HybridSearchService hybridSearch) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.indexService = Objects.requireNonNull(indexService, "indexService");
        this.semanticSearch = Objects.requireNonNull(semanticSearch, "semanticSearch");
        this.hybridSearch = Objects.requireNonNull(hybridSearch, "hybridSearch");
    }

    /** The registered projects, by name, with the state of their semantic index. No root path is returned. */
    public List<ProjectSummary> projects() throws IOException {
        List<RegisteredProject> registered = new ArrayList<>(registry.listProjects());
        registered.sort(Comparator.comparing(RegisteredProject::displayName)
                .thenComparing(project -> project.id().toString()));
        List<ProjectSummary> summaries = new ArrayList<>(registered.size());
        for (RegisteredProject project : registered) {
            summaries.add(ProjectSummary.of(indexService.statusWithoutSize(project.id().toString())));
        }
        return List.copyOf(summaries);
    }

    /**
     * One page of the documents of the index, in stable-key order. A filter removes documents from the page; it
     * never reorders them. A cursor is valid for the index it was issued for only.
     *
     * @throws IndexChangedException when the cursor was issued for another index, or the index was replaced
     *                               while the page was read
     */
    public DocumentsPage documents(String projectReference, DocumentsRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        RegisteredProject project = projects.resolve(projectReference);
        String projectId = project.id().toString();
        SemanticIndexService.Status status = indexService.statusWithoutSize(projectId);
        List<String> limitations = new ArrayList<>(status.limitations());
        Cursor cursor = request.cursor() == null ? null : Cursor.decode(request.cursor());
        DocumentWalk walk = new DocumentWalk(project, request, cursor, limitations);
        SemanticVectorStore store = indexService.vectorStore();
        boolean goOn = true;
        while (goOn) {
            goOn = walk.hasMoreAfterReading(store);
        }
        return walk.page();
    }

    /**
     * The reading of one answer page: pages of the store are read one after the other, on one index, until
     * the answer is full, the index is exhausted or the scan bound is reached.
     */
    private final class DocumentWalk {
        private final RegisteredProject project;
        private final String projectId;
        private final DocumentsRequest request;
        private final Cursor cursor;
        private final List<String> limitations;
        private final List<DocumentSummary> selected;
        private final int batch;
        private String after;
        private IndexIdentity identity;
        private SemanticVectorStore.IndexMetadata metadata;
        private SymbolLookup lookup;
        private String nextCursor;
        private int scanned;

        DocumentWalk(RegisteredProject project, DocumentsRequest request, Cursor cursor, List<String> limitations) {
            this.project = project;
            this.projectId = project.id().toString();
            this.request = request;
            this.cursor = cursor;
            this.limitations = limitations;
            this.selected = new ArrayList<>(request.limit());
            this.batch = request.filtered() ? MAX_PAGE_SIZE : request.limit();
            this.after = cursor == null ? null : cursor.lastStableKey();
        }

        /** Reads the next page of the store; false when the answer needs no other page. */
        boolean hasMoreAfterReading(SemanticVectorStore store) throws IOException {
            Optional<SemanticVectorStore.DocumentPage> read = store.documentPage(projectId, after, batch);
            if (read.isEmpty()) {
                // An index that was there for the cursor or for a previous page is gone: it was replaced.
                if (cursor != null || metadata != null) throw new IndexChangedException();
                if (!limitations.contains(INDEX_MISSING)) limitations.add(INDEX_MISSING);
                return false;
            }
            SemanticVectorStore.DocumentPage page = read.orElseThrow();
            bindToIndex(store, page.metadata());
            int consumed = select(page.documents());
            if (!page.hasMore() && consumed == page.documents().size()) return false;
            boolean full = selected.size() == request.limit();
            if (!full && scanned < MAX_SCANNED_DOCUMENTS) return true;
            nextCursor = new Cursor(identity.token(), after).encode();
            if (!full) limitations.add(FILTER_SCAN_BOUNDED);
            return false;
        }

        /** The first page names the index of the walk; every other page must come from the same one. */
        private void bindToIndex(SemanticVectorStore store, SemanticVectorStore.IndexMetadata read) throws IOException {
            if (metadata != null) {
                if (!metadata.equals(read)) throw new IndexChangedException();
                return;
            }
            metadata = read;
            identity = identityOf(store, read);
            if (cursor != null && !cursor.identityToken().equals(identity.token())) {
                throw new IndexChangedException();
            }
            lookup = lookup(project, read.snapshotId());
            if (!lookup.resolved()) limitations.add(SYMBOLS_UNRESOLVED);
        }

        /** Keeps the documents the request asks for, up to its limit; returns how many were looked at. */
        private int select(List<SemanticDocument> documents) {
            int consumed = 0;
            for (SemanticDocument document : documents) {
                consumed++;
                scanned++;
                after = document.stableKey();
                DocumentSummary summary = lookup.summarize(document);
                if (!request.matches(summary)) continue;
                selected.add(summary);
                if (selected.size() == request.limit()) break;
            }
            return consumed;
        }

        DocumentsPage page() {
            if (metadata == null) return new DocumentsPage(projectId, Optional.empty(), List.of(), null, 0, limitations);
            return new DocumentsPage(projectId, Optional.of(identity), selected, nextCursor,
                    metadata.documentCount(), limitations);
        }
    }

    /**
     * One document: its indexed content, the excerpt its source file holds now, and whether they differ. The
     * source is read through the reader confined to the project root, from the file the index names: no path
     * comes from the caller, and no absolute path is returned.
     *
     * @throws DocumentNotFoundException when the index holds no document under {@code stableKey}
     */
    public Detail detail(String projectReference, String stableKey) throws IOException {
        requireStableKey(stableKey);
        RegisteredProject project = projects.resolve(projectReference);
        String projectId = project.id().toString();
        SemanticIndexService.Status status = indexService.statusWithoutSize(projectId);
        SemanticVectorStore store = indexService.vectorStore();
        SemanticVectorStore.StoredDocument stored = store.storedDocument(projectId, stableKey)
                .orElseThrow(DocumentNotFoundException::new);
        SemanticDocument document = stored.document().orElseThrow(DocumentNotFoundException::new).document();
        IndexIdentity identity = identityOf(store, stored.metadata());
        List<String> limitations = new ArrayList<>(status.limitations());
        SymbolLookup lookup = lookup(project, stored.metadata().snapshotId());
        if (!lookup.resolved()) limitations.add(SYMBOLS_UNRESOLVED);
        SourceComparison comparison = compareWithSource(project, document);
        return new Detail(projectId, identity, lookup.summarize(document), document.content(),
                comparison.source(), comparison.divergence(), limitations);
    }

    /**
     * The nearest documents of one document, ranked by exact cosine similarity with its stored vector, in the
     * same index. The embedding provider is never called and the document itself is left out.
     *
     * @throws DocumentNotFoundException when the index holds no document under {@code stableKey}
     * @throws IndexChangedException     when the index was replaced while its neighbours were ranked
     */
    public Neighbors neighbors(String projectReference, String stableKey, int limit) throws IOException {
        requireStableKey(stableKey);
        if (limit < 1 || limit > MAX_NEIGHBORS) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_NEIGHBORS);
        }
        RegisteredProject project = projects.resolve(projectReference);
        String projectId = project.id().toString();
        SemanticIndexService.Status status = indexService.statusWithoutSize(projectId);
        SemanticVectorStore store = indexService.vectorStore();
        SemanticVectorStore.StoredDocument stored = store.storedDocument(projectId, stableKey)
                .orElseThrow(DocumentNotFoundException::new);
        SemanticVectorStore.IndexedDocument origin = stored.document().orElseThrow(DocumentNotFoundException::new);
        IndexIdentity identity = identityOf(store, stored.metadata());
        List<SemanticVectorStore.VectorHit> ranked = store.search(projectId, origin.vector(), limit + 1, -1.0);
        boolean sameIndex = store.metadata(projectId).map(stored.metadata()::equals).orElse(false)
                && ranked.stream().allMatch(hit -> stored.metadata().snapshotId().equals(hit.document().snapshotId()));
        if (!sameIndex) throw new IndexChangedException();

        List<String> limitations = new ArrayList<>(status.limitations());
        SymbolLookup lookup = lookup(project, stored.metadata().snapshotId());
        if (!lookup.resolved()) limitations.add(SYMBOLS_UNRESOLVED);
        limitations.add(SCORE_IS_COSINE);
        List<Neighbor> neighbors = new ArrayList<>(limit);
        for (SemanticVectorStore.VectorHit hit : ranked) {
            if (stableKey.equals(hit.document().stableKey())) continue;
            if (neighbors.size() == limit) break;
            neighbors.add(new Neighbor(lookup.summarize(hit.document()), hit.score()));
        }
        return new Neighbors(projectId, identity, stableKey, neighbors, limitations);
    }

    /**
     * A search ranked by the semantic or the hybrid search service, unchanged: the ranks and the scores are
     * theirs, with their bounds and their limitations. A filter removes hits from the answer; a hit that stays
     * keeps the rank and the score the service gave it. Every score is named by {@code scoreKind}.
     */
    public SearchResult search(String projectReference, SearchRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        RegisteredProject project = projects.resolve(projectReference);
        String projectId = project.id().toString();
        List<RankedDocument> ranked = new ArrayList<>();
        String snapshotId;
        List<String> limitations;
        long latencyMillis;
        if (request.mode() == SearchMode.SEMANTIC) {
            SemanticSearchService.SearchResponse response = semanticSearch.search(projectId,
                    new SemanticSearchService.SearchRequest(request.query(), request.limit(), request.minimumScore()));
            for (SemanticSearchService.SearchHit hit : response.hits()) {
                ranked.add(new RankedDocument(hit.document(), hit.score(), ScoreKind.COSINE_SIMILARITY,
                        List.of(new ScoreSignal("SEMANTIC", hit.score(), hit.nature().name()))));
            }
            snapshotId = response.snapshotId();
            limitations = new ArrayList<>(response.limitations());
            latencyMillis = response.latencyMillis();
        } else {
            HybridSearchService.HybridResponse response = hybridSearch.search(projectId,
                    new HybridSearchService.HybridRequest(request.query(), request.limit(), request.minimumScore()));
            for (HybridSearchService.HybridHit hit : response.hits()) {
                ranked.add(new RankedDocument(hit.document(), hit.score(), ScoreKind.HYBRID_SCORE,
                        hit.signals().stream()
                                .map(signal -> new ScoreSignal(signal.type(), signal.score(), signal.nature().name()))
                                .toList()));
            }
            snapshotId = response.snapshotId();
            limitations = new ArrayList<>(response.limitations());
            latencyMillis = response.latencyMillis();
        }
        SymbolLookup lookup = ranked.isEmpty() ? SymbolLookup.unresolved()
                : lookup(project, ranked.getFirst().document().snapshotId());
        if (!ranked.isEmpty() && !lookup.resolved()) limitations.add(SYMBOLS_UNRESOLVED);
        List<SearchHit> hits = new ArrayList<>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            RankedDocument value = ranked.get(index);
            DocumentSummary summary = lookup.summarize(value.document());
            if (!request.matches(summary)) continue;
            hits.add(new SearchHit(index + 1, summary, value.score(), value.scoreKind().name(), value.signals()));
        }
        SemanticVectorStore store = indexService.vectorStore();
        Optional<SemanticVectorStore.IndexMetadata> metadata = store.metadata(projectId)
                .filter(value -> value.snapshotId().equals(snapshotId));
        Optional<IndexIdentity> identity = metadata.isEmpty() ? Optional.empty()
                : Optional.of(identityOf(store, metadata.orElseThrow()));
        return new SearchResult(projectId, snapshotId, request.mode().name(), request.query(), hits, ranked.size(),
                identity, limitations, latencyMillis);
    }

    private SourceComparison compareWithSource(RegisteredProject project, SemanticDocument document) {
        if (document.fileId() == null) return SourceComparison.none(Divergence.NON_COMPARABLE);
        Optional<LocalSourceReader.SourceFile> file;
        try {
            file = new LocalSourceReader(project.rootPath()).read(document.fileId());
        } catch (IOException | IllegalArgumentException unreadable) {
            // The root is gone, the file cannot be read or the stored identifier is not a confined project
            // file: nothing is read and nothing about the cause is returned.
            return SourceComparison.none(Divergence.SOURCE_INTROUVABLE);
        }
        if (file.isEmpty()) return SourceComparison.none(Divergence.SOURCE_INTROUVABLE);
        if (document.kind() == SemanticDocumentKind.FILE || document.startLine() < 1) {
            return SourceComparison.none(Divergence.NON_COMPARABLE);
        }
        SymbolLocation location = new SymbolLocation(document.fileId(), document.startLine(), 0,
                document.endLine(), 0, PositionEncoding.UTF16_CODE_UNITS);
        Optional<CurrentSource> source = file.orElseThrow()
                .excerpt(location, SOURCE_CONTEXT_LINES, SOURCE_MAX_TOKENS)
                .map(excerpt -> new CurrentSource(document.fileId(), excerpt.startLine(), excerpt.endLine(),
                        excerpt.content(), excerpt.truncated()));
        if (document.kind() != SemanticDocumentKind.CHUNK) {
            return new SourceComparison(source, Divergence.NON_COMPARABLE);
        }
        Optional<String> current = SemanticDocumentFactory.chunkContent(file.orElseThrow(), location);
        boolean identical = current.isPresent() && current.orElseThrow().equals(document.content());
        return new SourceComparison(source, identical ? Divergence.IDENTIQUE : Divergence.DIFFERENTE);
    }

    private IndexIdentity identityOf(SemanticVectorStore store, SemanticVectorStore.IndexMetadata metadata)
            throws IOException {
        synchronized (cacheLock) {
            FingerprintedIndex known = fingerprints.get(metadata.projectId());
            if (known != null && metadata.equals(known.metadata())) {
                return IndexIdentity.of(metadata, known.fingerprint());
            }
        }
        SemanticVectorStore.IndexChecksums checksums = store.checksums(metadata.projectId())
                .orElseThrow(IndexChangedException::new);
        if (!metadata.equals(checksums.metadata())) throw new IndexChangedException();
        String fingerprint = fingerprint(checksums.checksumsByStableKey());
        synchronized (cacheLock) {
            fingerprints.put(metadata.projectId(), new FingerprintedIndex(metadata, fingerprint), 1L);
        }
        return IndexIdentity.of(metadata, fingerprint);
    }

    /** The kind named by {@code value}, whatever its case, or {@code null} for no kind. */
    public static SemanticDocumentKind parseKind(String value) {
        if (value == null) return null;
        try {
            return SemanticDocumentKind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("kind must be SYMBOL, CHUNK or FILE");
        }
    }

    static String fingerprint(Map<String, String> checksumsByStableKey) {
        MessageDigest digest = Sha256.newDigest();
        for (Map.Entry<String, String> entry : new TreeMap<>(checksumsByStableKey).entrySet()) {
            digest.update((entry.getKey() + SEPARATOR + entry.getValue() + RECORD_SEPARATOR)
                    .getBytes(StandardCharsets.UTF_8));
        }
        return Sha256.hex(digest);
    }

    private SymbolLookup lookup(RegisteredProject project, String indexedSnapshotId) throws IOException {
        String projectId = project.id().toString();
        Optional<String> active = snapshots.activeSnapshotId(project.id());
        if (active.isEmpty() || !active.orElseThrow().equals(indexedSnapshotId)) {
            return SymbolLookup.unresolved();
        }
        synchronized (cacheLock) {
            SymbolLookup known = lookups.get(projectId);
            if (known != null && known.describes(projectId, indexedSnapshotId)) return known;
        }
        Optional<CodeKnowledgeSnapshot> loaded = snapshots.loadActiveKnowledge(project.id());
        if (loaded.isEmpty() || !indexedSnapshotId.equals(loaded.orElseThrow().snapshotId())) {
            return SymbolLookup.unresolved();
        }
        SymbolLookup built = SymbolLookup.of(projectId, loaded.orElseThrow());
        synchronized (cacheLock) {
            lookups.put(projectId, built, 1L);
        }
        return built;
    }

    private static void requireStableKey(String stableKey) {
        if (stableKey == null || stableKey.isBlank()) throw new IllegalArgumentException("stableKey must not be blank");
        if (stableKey.getBytes(StandardCharsets.UTF_8).length > MAX_STABLE_KEY_UTF8_BYTES) {
            throw new IllegalArgumentException("stableKey exceeds " + MAX_STABLE_KEY_UTF8_BYTES + " UTF-8 bytes");
        }
    }

    private static String requireFilter(String value, String name) {
        if (value == null) return null;
        if (value.isBlank()) throw new IllegalArgumentException(name + " must be absent or non-blank");
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_FILTER_UTF8_BYTES) {
            throw new IllegalArgumentException(name + " exceeds " + MAX_FILTER_UTF8_BYTES + " UTF-8 bytes");
        }
        return value;
    }

    /** The index was replaced: the walk or the ranking in progress must be started again. */
    public static final class IndexChangedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public IndexChangedException() {
            super("the semantic index changed; start the reading again from its first page");
        }
    }

    /** The index holds no document under the requested stable key. */
    public static final class DocumentNotFoundException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public DocumentNotFoundException() {
            super("no semantic document under this stable key in the index of the project");
        }
    }

    /** Whether the indexed content of a document and the excerpt its source holds now are the same. */
    public enum Divergence { IDENTIQUE, DIFFERENTE, SOURCE_INTROUVABLE, NON_COMPARABLE }

    /**
     * The index a reading was made on. Two readings that carry the same identity read the same index;
     * {@code documentsFingerprint} is the SHA-256 of its sorted stable keys and checksums.
     */
    public record IndexIdentity(String projectId, String snapshotId, String providerId, String modelId,
                                int dimensions, String documentsFingerprint) {
        public IndexIdentity {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(documentsFingerprint, "documentsFingerprint");
        }

        static IndexIdentity of(SemanticVectorStore.IndexMetadata metadata, String fingerprint) {
            return new IndexIdentity(metadata.projectId(), metadata.snapshotId(), metadata.providerId(),
                    metadata.modelId(), metadata.dimensions(), fingerprint);
        }

        /** One opaque value for the whole identity, compared by a cursor. */
        public String token() {
            return Sha256.hex(String.join(SEPARATOR, projectId, snapshotId, providerId, modelId,
                    Integer.toString(dimensions), documentsFingerprint));
        }
    }

    /** A registered project and the state of its semantic index, without its root path. */
    public record ProjectSummary(String projectId, String name, String state, String providerId, String modelId,
                                 int dimensions, String activeSnapshotId, String indexedSnapshotId,
                                 int documentCount, List<String> limitations) {
        public ProjectSummary {
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }

        static ProjectSummary of(SemanticIndexService.Status status) {
            return new ProjectSummary(status.projectId(), status.projectName(), status.state().name(),
                    status.providerId(), status.modelId(), status.dimensions(), status.activeSnapshotId(),
                    status.indexedSnapshotId(), status.documentCount(), status.limitations());
        }
    }

    /**
     * What a page is asked for. {@code cursor} is the cursor of the previous page or {@code null}; each filter
     * is {@code null} when it does not apply.
     */
    public record DocumentsRequest(String cursor, int limit, SemanticDocumentKind kind, String module,
                                   String language, String filePrefix) {
        public DocumentsRequest {
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
            }
            if (cursor != null && (cursor.isBlank() || cursor.length() > MAX_CURSOR_CHARACTERS)) {
                throw new IllegalArgumentException("cursor is not valid");
            }
            module = requireFilter(module, "module");
            language = requireFilter(language, "language");
            filePrefix = requireFilter(filePrefix, "filePrefix");
        }

        /** The first page of {@value #DEFAULT_PAGE_SIZE} documents, without filter. */
        public static DocumentsRequest firstPage() {
            return new DocumentsRequest(null, DEFAULT_PAGE_SIZE, null, null, null, null);
        }

        boolean filtered() {
            return kind != null || module != null || language != null || filePrefix != null;
        }

        boolean matches(DocumentSummary document) {
            return (kind == null || kind.name().equals(document.kind()))
                    && (module == null || module.equals(document.moduleId()))
                    && (language == null || language.equals(document.language()))
                    && (filePrefix == null
                            || (document.fileId() != null && document.fileId().startsWith(filePrefix)));
        }
    }

    /**
     * One document as a list shows it: its identity in the index and, when the index describes the active
     * snapshot, the symbol it was built from. {@code symbolResolved} is false when that symbol was not looked
     * up or not found; the symbol fields are then {@code null}.
     */
    public record DocumentSummary(String stableKey, String kind, String sourceId, String fileId, int startLine,
                                  int endLine, String checksum, int contentLength, boolean symbolResolved,
                                  String symbolName, String qualifiedName, String symbolKind, String language,
                                  String moduleId) {
    }

    /** One page of documents; {@code nextCursor} is {@code null} on the last page. */
    public record DocumentsPage(String projectId, Optional<IndexIdentity> identity, List<DocumentSummary> documents,
                                String nextCursor, int totalDocuments, List<String> limitations) {
        public DocumentsPage {
            Objects.requireNonNull(identity, "identity");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }

    /** The excerpt a source file holds now, named by its project-relative identifier. */
    public record CurrentSource(String fileId, int startLine, int endLine, String content, boolean truncated) {
    }

    /** One document, its indexed content and what its source holds now. */
    public record Detail(String projectId, IndexIdentity identity, DocumentSummary document, String indexedContent,
                         Optional<CurrentSource> source, Divergence divergence, List<String> limitations) {
        public Detail {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(indexedContent, "indexedContent");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(divergence, "divergence");
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }

    /** Which service ranks a search. */
    public enum SearchMode {
        SEMANTIC, HYBRID;

        /** The mode named by {@code value}, whatever its case; {@link #SEMANTIC} when none is named. */
        public static SearchMode parse(String value) {
            if (value == null) return SEMANTIC;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                throw new IllegalArgumentException("mode must be semantic or hybrid");
            }
        }
    }

    private record FingerprintedIndex(SemanticVectorStore.IndexMetadata metadata, String fingerprint) {
    }

    /** What a score is: an exact cosine similarity, or the hybrid score built from the signals listed with it. */
    public enum ScoreKind { COSINE_SIMILARITY, HYBRID_SCORE }

    /**
     * A search and the filters applied to its answer. {@code limit} and {@code minimumScore} are those of the
     * service that ranks: 1 to 1 000 and -1 to 1 for {@link SearchMode#SEMANTIC}, 1 to 500 and 0 to 1 for
     * {@link SearchMode#HYBRID}; the query is at most 64 KiB of UTF-8.
     */
    public record SearchRequest(String query, SearchMode mode, int limit, double minimumScore,
                                SemanticDocumentKind kind, String module, String language, String filePrefix) {
        public SearchRequest {
            Objects.requireNonNull(mode, "mode");
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            if (query.getBytes(StandardCharsets.UTF_8).length > SemanticSearchService.MAX_QUERY_UTF8_BYTES) {
                throw new IllegalArgumentException(
                        "query exceeds " + SemanticSearchService.MAX_QUERY_UTF8_BYTES + " UTF-8 bytes");
            }
            int maximum = mode == SearchMode.SEMANTIC ? SemanticSearchService.MAX_RESULTS : HybridSearchService.MAX_RESULTS;
            if (limit < 1 || limit > maximum) {
                throw new IllegalArgumentException("limit must be between 1 and " + maximum);
            }
            double lowest = mode == SearchMode.SEMANTIC ? -1.0 : 0.0;
            if (!Double.isFinite(minimumScore) || minimumScore < lowest || minimumScore > 1.0) {
                throw new IllegalArgumentException(
                        "minimumScore must be between " + (mode == SearchMode.SEMANTIC ? "-1" : "0") + " and 1");
            }
            module = requireFilter(module, "module");
            language = requireFilter(language, "language");
            filePrefix = requireFilter(filePrefix, "filePrefix");
        }

        boolean matches(DocumentSummary document) {
            return new DocumentsRequest(null, 1, kind, module, language, filePrefix).matches(document);
        }
    }

    /** One signal of a score, as the ranking service reports it. */
    public record ScoreSignal(String type, double score, String nature) {
    }

    /** One hit: {@code rank} is its 1-based rank in the ranking of the service, before any filter. */
    public record SearchHit(int rank, DocumentSummary document, double score, String scoreKind,
                            List<ScoreSignal> signals) {
        public SearchHit {
            Objects.requireNonNull(document, "document");
            signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        }
    }

    /**
     * The hits kept by the filters, in rank order, out of the {@code rankedCount} hits the service ranked;
     * {@code identity} is empty when no index answers the search.
     */
    public record SearchResult(String projectId, String snapshotId, String mode, String query, List<SearchHit> hits,
                               int rankedCount, Optional<IndexIdentity> identity, List<String> limitations,
                               long latencyMillis) {
        public SearchResult {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            Objects.requireNonNull(identity, "identity");
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }

    private record RankedDocument(SemanticDocument document, double score, ScoreKind scoreKind,
                                  List<ScoreSignal> signals) {
    }

    /** One neighbour and its cosine similarity with the document asked for. */
    public record Neighbor(DocumentSummary document, double similarity) {
    }

    /** The nearest documents of {@code stableKey}, best first. */
    public record Neighbors(String projectId, IndexIdentity identity, String stableKey, List<Neighbor> neighbors,
                            List<String> limitations) {
        public Neighbors {
            Objects.requireNonNull(identity, "identity");
            neighbors = List.copyOf(Objects.requireNonNull(neighbors, "neighbors"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }

    private record SourceComparison(Optional<CurrentSource> source, Divergence divergence) {
        static SourceComparison none(Divergence divergence) {
            return new SourceComparison(Optional.empty(), divergence);
        }
    }

    /** The position a walk stopped at, bound to the identity of the index it walks. */
    private record Cursor(String identityToken, String lastStableKey) {
        private static final int TOKEN_LENGTH = 64;

        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((identityToken + lastStableKey).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            String decoded;
            try {
                decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("cursor is not valid");
            }
            if (decoded.length() <= TOKEN_LENGTH || decoded.substring(TOKEN_LENGTH).isBlank()) {
                throw new IllegalArgumentException("cursor is not valid");
            }
            return new Cursor(decoded.substring(0, TOKEN_LENGTH), decoded.substring(TOKEN_LENGTH));
        }
    }

    /** The symbols of one snapshot by identifier and the first symbol of each file, to describe documents. */
    private static final class SymbolLookup {
        private static final SymbolLookup UNRESOLVED = new SymbolLookup(null, null, Map.of(), Map.of());

        private final String projectId;
        private final String snapshotId;
        private final Map<String, Symbol> byId;
        private final Map<String, Symbol> firstByFile;

        private SymbolLookup(String projectId, String snapshotId, Map<String, Symbol> byId,
                             Map<String, Symbol> firstByFile) {
            this.projectId = projectId;
            this.snapshotId = snapshotId;
            this.byId = byId;
            this.firstByFile = firstByFile;
        }

        static SymbolLookup unresolved() {
            return UNRESOLVED;
        }

        static SymbolLookup of(String projectId, CodeKnowledgeSnapshot snapshot) {
            Map<String, Symbol> byId = new HashMap<>();
            Map<String, Symbol> firstByFile = new HashMap<>();
            for (Symbol symbol : snapshot.symbols()) {
                byId.put(symbol.id(), symbol);
                String fileId = symbol.location() == null ? symbol.fileId() : symbol.location().fileId();
                if (fileId == null) continue;
                firstByFile.merge(fileId, symbol,
                        (kept, candidate) -> candidate.symbolKey().compareTo(kept.symbolKey()) < 0 ? candidate : kept);
            }
            return new SymbolLookup(projectId, snapshot.snapshotId(), byId, firstByFile);
        }

        boolean resolved() {
            return snapshotId != null;
        }

        boolean describes(String otherProjectId, String otherSnapshotId) {
            return resolved() && projectId.equals(otherProjectId) && snapshotId.equals(otherSnapshotId);
        }

        DocumentSummary summarize(SemanticDocument document) {
            Symbol symbol = document.kind() == SemanticDocumentKind.FILE ? null : byId.get(document.sourceId());
            Symbol described = symbol != null ? symbol
                    : document.fileId() == null ? null : firstByFile.get(document.fileId());
            return new DocumentSummary(document.stableKey(), document.kind().name(), document.sourceId(),
                    document.fileId(), document.startLine(), document.endLine(), document.checksum(),
                    document.content().length(), symbol != null,
                    symbol == null ? null : symbol.name(),
                    symbol == null ? null : symbol.qualifiedName(),
                    symbol == null ? null : symbol.kind().name(),
                    described == null ? null : described.language(),
                    described == null ? null : described.moduleId());
        }
    }
}
