#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def save(path: str, value: str) -> None:
    (ROOT / path).write_text(value, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    value = load(path)
    count = value.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one anchor, found {count}: {old[:100]!r}")
    save(path, value.replace(old, new, 1))


def replace_between(path: str, start: str, end: str, replacement: str) -> None:
    value = load(path)
    left = value.find(start)
    if left < 0:
        raise RuntimeError(f"{path}: start anchor missing: {start!r}")
    right = value.find(end, left)
    if right < 0:
        raise RuntimeError(f"{path}: end anchor missing: {end!r}")
    save(path, value[:left] + replacement + value[right:])


# MNE-01: derive the protected active snapshot while holding the same project lease used by retention.
retention = "minos-storage-local/src/main/java/com/minos/store/SnapshotRetentionService.java"
replace_once(retention,
             "    private RetentionResult applyPolicyLocked(\n",
             "    RetentionResult applyPolicyLocked(\n")

compaction = "minos-storage-local/src/main/java/com/minos/store/SnapshotCompactionService.java"
save(compaction, '''package com.minos.store;\n\nimport java.io.IOException;\nimport java.nio.file.Path;\nimport java.util.Objects;\nimport java.util.UUID;\n\n/** Applies snapshot retention while deriving and protecting the current active snapshot. */\npublic final class SnapshotCompactionService {\n\n    private final Path storageRoot;\n    private final ActiveSnapshotRepository activeSnapshots;\n    private final SnapshotRetentionService retention;\n\n    public SnapshotCompactionService(Path storageRoot) throws IOException {\n        SnapshotRepository repository = new SnapshotRepository(\n                Objects.requireNonNull(storageRoot, "storageRoot"));\n        this.storageRoot = repository.storageRoot();\n        this.activeSnapshots = new ActiveSnapshotRepository(repository);\n        this.retention = new SnapshotRetentionService(repository);\n    }\n\n    public SnapshotRetentionService.RetentionResult compact(\n            UUID projectId,\n            SnapshotRetentionPolicy policy\n    ) throws IOException {\n        Objects.requireNonNull(projectId, "projectId");\n        Objects.requireNonNull(policy, "policy");\n        try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storageRoot, projectId)) {\n            SnapshotDescriptor active = activeSnapshots.read(projectId)\n                    .orElseThrow(() -> new IllegalStateException(\n                            "project has no active snapshot to protect during compaction: " + projectId));\n            return retention.applyPolicyLocked(projectId, active.fileName(), policy);\n        }\n    }\n}\n''')

# MNE-02/MNE-07: bounded PostgreSQL active-row retry, guaranteed temp cleanup and cache weight tied to payload bytes.
postgres = "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresCodeKnowledgeSnapshotStore.java"
replace_once(postgres,
             "    private static final long MAX_PERSISTED_SNAPSHOT_BYTES = 3L * 1024L * 1024L * 1024L;\n",
             "    private static final long MAX_PERSISTED_SNAPSHOT_BYTES = 256L * 1024L * 1024L;\n"
             "    private static final int MAX_ACTIVE_QUERY_RETRIES = 4;\n"
             "    private static final long QUERY_VIEW_PERSISTED_AMPLIFICATION = 8L;\n")
replace_between(postgres,
                "    @Override\n    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {\n",
                "    QueryCacheStats queryCacheStats() {\n",
'''    @Override\n    public Optional<SnapshotQueryView> loadActiveQueryView(UUID projectId) throws IOException {\n        Objects.requireNonNull(projectId, "projectId");\n        for (int attempt = 1; attempt <= MAX_ACTIVE_QUERY_RETRIES; attempt++) {\n            Optional<ActiveMetadata> metadataOptional = activeMetadata(projectId);\n            if (metadataOptional.isEmpty()) return Optional.empty();\n            ActiveMetadata metadata = metadataOptional.orElseThrow();\n            CacheKey key = new CacheKey(projectId, metadata.snapshotId(), metadata.sha256());\n            Optional<SnapshotQueryView> cached = cached(key);\n            if (cached.isPresent()) return cached;\n\n            ReentrantLock buildLock = buildLocks[Math.floorMod(projectId.hashCode(), buildLocks.length)];\n            boolean retry = false;\n            buildLock.lock();\n            try {\n                cached = cached(key);\n                if (cached.isPresent()) return cached;\n                synchronized (cacheLock) { cacheMisses++; }\n\n                Optional<Row> row = activeRow(projectId);\n                if (row.isEmpty()) return Optional.empty();\n                Row value = row.orElseThrow();\n                if (!metadata.snapshotId().equals(value.snapshotId()) || !metadata.sha256().equals(value.sha256())) {\n                    Files.deleteIfExists(value.payload());\n                    retry = true;\n                } else {\n                    long payloadBytes = Files.size(value.payload());\n                    CodeKnowledgeSnapshot snapshot = decodeVerified(projectId, value);\n                    long started = System.nanoTime();\n                    InMemoryCodeKnowledgeStore queryStore = new InMemoryCodeKnowledgeStore(snapshot);\n                    long buildNanos = System.nanoTime() - started;\n                    SnapshotDescriptor descriptor = new SnapshotDescriptor(\n                            2,\n                            snapshot.snapshotId(),\n                            "postgresql:" + snapshot.snapshotId(),\n                            metadata.sha256(),\n                            metadata.symbolCount(),\n                            metadata.occurrenceCount(),\n                            metadata.relationshipCount());\n                    SnapshotQueryView view = new SnapshotQueryView(descriptor, snapshot, queryStore, buildNanos);\n                    cache(projectId, key, view, estimateWeight(metadata, payloadBytes));\n                    return Optional.of(view);\n                }\n            } finally {\n                buildLock.unlock();\n            }\n            if (!retry) throw new IOException("PostgreSQL active snapshot resolution ended without a result");\n        }\n        throw new IOException("PostgreSQL active snapshot changed repeatedly; retry limit exceeded for project "\n                + projectId + " after " + MAX_ACTIVE_QUERY_RETRIES + " attempts");\n    }\n\n''')
replace_once(postgres,
'''    private static long estimateWeight(ActiveMetadata metadata) {\n        long weight = 64L * 1024L;\n        weight = safeAdd(weight, (long) metadata.symbolCount() * 1024L);\n        weight = safeAdd(weight, (long) metadata.occurrenceCount() * 640L);\n        weight = safeAdd(weight, (long) metadata.relationshipCount() * 1024L);\n        return weight;\n    }\n\n    private static long safeAdd(long left, long right) {\n        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;\n    }\n''',
'''    private static long estimateWeight(ActiveMetadata metadata, long persistedBytes) {\n        long countWeight = 64L * 1024L;\n        countWeight = safeAdd(countWeight, (long) metadata.symbolCount() * 1024L);\n        countWeight = safeAdd(countWeight, (long) metadata.occurrenceCount() * 640L);\n        countWeight = safeAdd(countWeight, (long) metadata.relationshipCount() * 1024L);\n        long persistedWeight = safeMultiply(Math.max(0L, persistedBytes), QUERY_VIEW_PERSISTED_AMPLIFICATION);\n        return Math.max(countWeight, persistedWeight);\n    }\n\n    private static long safeAdd(long left, long right) {\n        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;\n    }\n\n    private static long safeMultiply(long left, long right) {\n        try {\n            return Math.multiplyExact(left, right);\n        } catch (ArithmeticException exception) {\n            return Long.MAX_VALUE;\n        }\n    }\n''')

# MNE-06: encoded snapshot bytes are a hard decode boundary, not just object-count protocol maxima.
codec = "minos-storage-local/src/main/java/com/minos/store/SnapshotBinaryCodecSupport.java"
replace_once(codec,
             "    private static final int MAX_STRING_CHARS = 8 * 1024 * 1024;\n",
             "    private static final int MAX_STRING_CHARS = 8 * 1024 * 1024;\n"
             "    static final long MAX_PERSISTED_SNAPSHOT_BYTES = 256L * 1024L * 1024L;\n")
replace_once(codec,
'''    static SymbolSnapshot readSymbolSnapshotV1(Path file) throws IOException {\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''',
'''    static SymbolSnapshot readSymbolSnapshotV1(Path file) throws IOException {\n        requireSnapshotFileSize(file);\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''')
replace_once(codec,
'''    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2(Path file) throws IOException {\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''',
'''    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2(Path file) throws IOException {\n        requireSnapshotFileSize(file);\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''')
replace_once(codec,
'''    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2FromBytes(byte[] payload) throws IOException {\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''',
'''    static CodeKnowledgeSnapshot readKnowledgeSnapshotV2FromBytes(byte[] payload) throws IOException {\n        if (payload.length > MAX_PERSISTED_SNAPSHOT_BYTES) {\n            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);\n        }\n        try (DataInputStream input = new DataInputStream(new BufferedInputStream(\n''')
replace_once(codec,
'''        return HEX.formatHex(digest.digest());\n    }\n\n    static SymbolSnapshot readSymbolSnapshotV1''',
'''        requireSnapshotFileSize(file);\n        return HEX.formatHex(digest.digest());\n    }\n\n    static SymbolSnapshot readSymbolSnapshotV1''')
# The second writer has the same return sequence; anchor it relative to the V2 method tail.
replace_once(codec,
'''        return HEX.formatHex(digest.digest());\n    }\n\n    static byte[] writeKnowledgeSnapshotV2ToBytes''',
'''        requireSnapshotFileSize(file);\n        return HEX.formatHex(digest.digest());\n    }\n\n    static byte[] writeKnowledgeSnapshotV2ToBytes''')
replace_once(codec,
'''    static byte[] writeKnowledgeSnapshotV2ToBytes(CodeKnowledgeSnapshot snapshot) throws IOException {\n        ByteArrayOutputStream baos = new ByteArrayOutputStream();\n        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(baos))) {\n            writeKnowledgeSnapshotV2Body(output, snapshot);\n        }\n        return baos.toByteArray();\n    }\n''',
'''    static byte[] writeKnowledgeSnapshotV2ToBytes(CodeKnowledgeSnapshot snapshot) throws IOException {\n        ByteArrayOutputStream baos = new ByteArrayOutputStream();\n        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(baos))) {\n            writeKnowledgeSnapshotV2Body(output, snapshot);\n        }\n        byte[] payload = baos.toByteArray();\n        if (payload.length > MAX_PERSISTED_SNAPSHOT_BYTES) {\n            throw new IOException("knowledge snapshot payload exceeds persisted byte limit: " + payload.length);\n        }\n        return payload;\n    }\n''')
# Install shared file-size guard before primitive writers.
anchor = "    private static void writeSymbol(DataOutputStream output, Symbol symbol) throws IOException {\n"
value = load(codec)
if value.count(anchor) != 1:
    raise RuntimeError("SnapshotBinaryCodecSupport: writeSymbol anchor mismatch")
guard = '''    private static void requireSnapshotFileSize(Path file) throws IOException {\n        long size = Files.size(file);\n        if (size < 1L || size > MAX_PERSISTED_SNAPSHOT_BYTES) {\n            throw new IOException("snapshot payload exceeds persisted byte limit: " + size\n                    + "/" + MAX_PERSISTED_SNAPSHOT_BYTES);\n        }\n    }\n\n'''
save(codec, value.replace(anchor, guard + anchor, 1))

# MNE-07: local query cache weight includes encoded payload amplification.
local_store = "minos-storage-local/src/main/java/com/minos/store/FileSymbolSnapshotStore.java"
replace_once(local_store,
             "    private static final int BUILD_LOCK_STRIPES = 64;\n",
             "    private static final int BUILD_LOCK_STRIPES = 64;\n"
             "    private static final long QUERY_VIEW_PERSISTED_AMPLIFICATION = 8L;\n")
replace_once(local_store,
             "        long weight = estimateQueryViewWeight(descriptor);\n",
             "        long weight = estimateQueryViewWeight(projectId, descriptor);\n")
replace_once(local_store,
             "    private QueryResolution publishBuiltView(\n            UUID projectId,\n            CacheKey key,\n            SnapshotDescriptor descriptor,\n            SnapshotQueryView built\n    ) {\n",
             "    private QueryResolution publishBuiltView(\n            UUID projectId,\n            CacheKey key,\n            SnapshotDescriptor descriptor,\n            SnapshotQueryView built\n    ) throws IOException {\n")
replace_once(local_store,
'''    private static long estimateQueryViewWeight(SnapshotDescriptor descriptor) {\n        long weight = 64L * 1024L;\n        weight = safeAdd(weight, (long) descriptor.symbolCount() * 1024L);\n        weight = safeAdd(weight, (long) descriptor.occurrenceCount() * 640L);\n        weight = safeAdd(weight, (long) descriptor.relationshipCount() * 1024L);\n        return weight;\n    }\n\n    private static long safeAdd(long left, long right) {\n        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;\n    }\n''',
'''    private long estimateQueryViewWeight(UUID projectId, SnapshotDescriptor descriptor) throws IOException {\n        long countWeight = 64L * 1024L;\n        countWeight = safeAdd(countWeight, (long) descriptor.symbolCount() * 1024L);\n        countWeight = safeAdd(countWeight, (long) descriptor.occurrenceCount() * 640L);\n        countWeight = safeAdd(countWeight, (long) descriptor.relationshipCount() * 1024L);\n        Path snapshot = snapshotRepository.resolveSnapshotFile(projectId, descriptor.fileName());\n        long persistedWeight = safeMultiply(Files.size(snapshot), QUERY_VIEW_PERSISTED_AMPLIFICATION);\n        return Math.max(countWeight, persistedWeight);\n    }\n\n    private static long safeAdd(long left, long right) {\n        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;\n    }\n\n    private static long safeMultiply(long left, long right) {\n        try {\n            return Math.multiplyExact(left, right);\n        } catch (ArithmeticException exception) {\n            return Long.MAX_VALUE;\n        }\n    }\n''')

print("MNE snapshot/storage transformations applied")
