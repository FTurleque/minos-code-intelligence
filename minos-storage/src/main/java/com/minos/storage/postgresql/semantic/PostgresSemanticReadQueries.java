package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore.DocumentPage;
import com.minos.semantic.SemanticVectorStore.IndexChecksums;
import com.minos.semantic.SemanticVectorStore.IndexDocuments;
import com.minos.semantic.SemanticVectorStore.IndexMetadata;
import com.minos.semantic.SemanticVectorStore.IndexSnapshot;
import com.minos.semantic.SemanticVectorStore.IndexedDocument;
import com.minos.semantic.SemanticVectorStore.StoredDocument;
import com.minos.semantic.SemanticVectorStore.StoredVector;
import com.minos.semantic.SemanticVectorStore.VectorHit;
import com.minos.semantic.SemanticVectorStore.VectorPage;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Single-statement, snapshot-consistent read primitives for pgvector semantic state. */
final class PostgresSemanticReadQueries {
    private PostgresSemanticReadQueries() {
    }

    static Optional<String> activeKnowledgeSnapshotId(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT snapshot_id FROM knowledge_active WHERE project_id=?")) {
            statement.setObject(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getString(1)) : Optional.empty();
            }
        }
    }

    static Optional<IndexMetadata> metadata(Connection connection, String projectId, UUID id)
            throws SQLException, IOException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,COUNT(d.stable_key) "
                + "FROM semantic_index_meta m LEFT JOIN semantic_documents d "
                + "ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id WHERE m.project_id=? "
                + "GROUP BY m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                long count = result.getLong(6);
                if (count > Integer.MAX_VALUE) {
                    throw new IOException("semantic document count exceeds supported integer range");
                }
                return Optional.of(new IndexMetadata(projectId, result.getString(1), result.getString(2),
                        result.getString(3), result.getInt(4), result.getLong(5), (int) count));
            }
        }
    }

    /**
     * Metadata and documents of the index in one statement, without the embedding column: the vectors are
     * neither transferred nor parsed.
     */
    static Optional<IndexDocuments> documents(Connection connection, String projectId, UUID id) throws SQLException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                + "d.document_id,d.stable_key,d.snapshot_id,d.kind,d.source_id,d.file_id,d.start_line,d.end_line,"
                + "d.content,d.checksum FROM semantic_index_meta m "
                + "LEFT JOIN semantic_documents d ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id "
                + "WHERE m.project_id=? ORDER BY d.stable_key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String snapshotId = result.getString(1);
                String providerId = result.getString(2);
                String modelId = result.getString(3);
                int dimensions = result.getInt(4);
                long builtAt = result.getLong(5);
                List<SemanticDocument> documents = new ArrayList<>();
                do {
                    if (result.getString(6) == null) continue;
                    documents.add(document(projectId, result, 6));
                } while (result.next());
                return Optional.of(new IndexDocuments(
                        new IndexMetadata(projectId, snapshotId, providerId, modelId, dimensions, builtAt,
                                documents.size()),
                        documents));
            }
        }
    }

    /**
     * Metadata of the index and the stable key and checksum of each of its documents, in one statement that
     * reads neither the contents nor the embedding column.
     */
    static Optional<IndexChecksums> checksums(Connection connection, String projectId, UUID id) throws SQLException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,d.stable_key,d.checksum "
                + "FROM semantic_index_meta m LEFT JOIN semantic_documents d "
                + "ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id WHERE m.project_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String snapshotId = result.getString(1);
                String providerId = result.getString(2);
                String modelId = result.getString(3);
                int dimensions = result.getInt(4);
                long builtAt = result.getLong(5);
                Map<String, String> checksums = new HashMap<>();
                do {
                    if (result.getString(6) != null) checksums.put(result.getString(6), result.getString(7));
                } while (result.next());
                return Optional.of(new IndexChecksums(
                        new IndexMetadata(projectId, snapshotId, providerId, modelId, dimensions, builtAt,
                                checksums.size()),
                        checksums));
            }
        }
    }

    /**
     * One page of the documents of the index, with the metadata of that index and its document count, in one
     * statement that does not read the embedding column. The page asks for one row more than {@code limit} to
     * know whether a document follows; the count is a scalar subquery evaluated in the same statement, so the
     * page, the metadata and the count describe one state of the index.
     */
    static Optional<DocumentPage> documentPage(Connection connection, String projectId, UUID id,
                                               String afterStableKey, int limit) throws SQLException, IOException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                + "(SELECT COUNT(*) FROM semantic_documents c WHERE c.project_id=m.project_id "
                + "AND c.snapshot_id=m.snapshot_id),"
                + "d.document_id,d.stable_key,d.snapshot_id,d.kind,d.source_id,d.file_id,d.start_line,d.end_line,"
                + "d.content,d.checksum FROM semantic_index_meta m "
                + "LEFT JOIN LATERAL (SELECT p.document_id,p.stable_key,p.snapshot_id,p.kind,p.source_id,p.file_id,"
                + "p.start_line,p.end_line,p.content,p.checksum FROM semantic_documents p "
                + "WHERE p.project_id=m.project_id AND p.snapshot_id=m.snapshot_id "
                + "AND (CAST(? AS text) IS NULL OR p.stable_key > ?) ORDER BY p.stable_key LIMIT ?) d ON TRUE "
                + "WHERE m.project_id=? ORDER BY d.stable_key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, afterStableKey);
            statement.setString(2, afterStableKey);
            statement.setInt(3, limit + 1);
            statement.setObject(4, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                long count = result.getLong(6);
                if (count > Integer.MAX_VALUE) {
                    throw new IOException("semantic document count exceeds supported integer range");
                }
                IndexMetadata metadata = new IndexMetadata(projectId, result.getString(1), result.getString(2),
                        result.getString(3), result.getInt(4), result.getLong(5), (int) count);
                List<SemanticDocument> documents = new ArrayList<>();
                boolean hasMore = false;
                do {
                    if (result.getString(7) == null) continue;
                    if (documents.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    documents.add(document(projectId, result, 7));
                } while (result.next());
                return Optional.of(new DocumentPage(metadata, documents, hasMore));
            }
        }
    }

    /**
     * One document, its stored vector and the metadata of its index, in one statement: no row when the
     * project has no index, a row without document when the index holds none under {@code stableKey}.
     */
    static Optional<StoredDocument> storedDocument(Connection connection, String projectId, UUID id,
                                                   String stableKey) throws SQLException, IOException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                + "(SELECT COUNT(*) FROM semantic_documents c WHERE c.project_id=m.project_id "
                + "AND c.snapshot_id=m.snapshot_id),"
                + "d.document_id,d.stable_key,d.snapshot_id,d.kind,d.source_id,d.file_id,d.start_line,d.end_line,"
                + "d.content,d.checksum,d.embedding::text FROM semantic_index_meta m "
                + "LEFT JOIN semantic_documents d ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id "
                + "AND d.stable_key=? WHERE m.project_id=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, stableKey);
            statement.setObject(2, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                long count = result.getLong(6);
                if (count > Integer.MAX_VALUE) {
                    throw new IOException("semantic document count exceeds supported integer range");
                }
                int dimensions = result.getInt(4);
                IndexMetadata metadata = new IndexMetadata(projectId, result.getString(1), result.getString(2),
                        result.getString(3), dimensions, result.getLong(5), (int) count);
                if (result.getString(7) == null) return Optional.of(new StoredDocument(metadata, Optional.empty()));
                SemanticDocument document = document(projectId, result, 7);
                SemanticVector vector = SemanticVector.fromArray(stableKey, parseVector(result.getString(17), dimensions));
                return Optional.of(new StoredDocument(metadata, Optional.of(new IndexedDocument(document, vector))));
            }
        }
    }

    /**
     * One page of the stored vectors of the index, with the kind of each document, the metadata of that index
     * and its document count, in one statement that reads no document content. The page asks for one row more
     * than {@code limit} to know whether a vector follows.
     */
    static Optional<VectorPage> vectorPage(Connection connection, String projectId, UUID id,
                                           String afterStableKey, int limit) throws SQLException, IOException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                + "(SELECT COUNT(*) FROM semantic_documents c WHERE c.project_id=m.project_id "
                + "AND c.snapshot_id=m.snapshot_id),"
                + "d.stable_key,d.kind,d.embedding FROM semantic_index_meta m "
                + "LEFT JOIN LATERAL (SELECT p.stable_key,p.kind,p.embedding::text AS embedding "
                + "FROM semantic_documents p WHERE p.project_id=m.project_id AND p.snapshot_id=m.snapshot_id "
                + "AND (CAST(? AS text) IS NULL OR p.stable_key > ?) ORDER BY p.stable_key LIMIT ?) d ON TRUE "
                + "WHERE m.project_id=? ORDER BY d.stable_key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, afterStableKey);
            statement.setString(2, afterStableKey);
            statement.setInt(3, limit + 1);
            statement.setObject(4, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                long count = result.getLong(6);
                if (count > Integer.MAX_VALUE) {
                    throw new IOException("semantic document count exceeds supported integer range");
                }
                int dimensions = result.getInt(4);
                IndexMetadata metadata = new IndexMetadata(projectId, result.getString(1), result.getString(2),
                        result.getString(3), dimensions, result.getLong(5), (int) count);
                List<StoredVector> vectors = new ArrayList<>();
                boolean hasMore = false;
                do {
                    String stableKey = result.getString(7);
                    if (stableKey == null) continue;
                    if (vectors.size() == limit) {
                        hasMore = true;
                        break;
                    }
                    vectors.add(new StoredVector(SemanticDocumentKind.valueOf(result.getString(8)),
                            SemanticVector.fromArray(stableKey, parseVector(result.getString(9), dimensions))));
                } while (result.next());
                return Optional.of(new VectorPage(metadata, vectors, hasMore));
            }
        }
    }

    static Optional<IndexSnapshot> load(Connection connection, String projectId, UUID id)
            throws SQLException, IOException {
        String sql = "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                + "d.document_id,d.stable_key,d.snapshot_id,d.kind,d.source_id,d.file_id,d.start_line,d.end_line,"
                + "d.content,d.checksum,d.embedding::text FROM semantic_index_meta m "
                + "LEFT JOIN semantic_documents d ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id "
                + "WHERE m.project_id=? ORDER BY d.stable_key";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                String snapshotId = result.getString(1);
                String providerId = result.getString(2);
                String modelId = result.getString(3);
                int dimensions = result.getInt(4);
                long builtAt = result.getLong(5);
                List<IndexedDocument> documents = new ArrayList<>();
                do {
                    if (result.getString(6) == null) continue;
                    SemanticDocument document = document(projectId, result, 6);
                    documents.add(new IndexedDocument(document,
                            SemanticVector.fromArray(document.stableKey(), parseVector(result.getString(16), dimensions))));
                } while (result.next());
                return Optional.of(new IndexSnapshot(
                        projectId, snapshotId, providerId, modelId, dimensions, builtAt, documents));
            }
        }
    }

    static List<VectorHit> search(Connection connection, String projectId, UUID id, SemanticVector query,
                                  int limit, double minimumScore) throws SQLException {
        String vector = PostgresSemanticWriteQueries.vectorLiteral(query);
        String sql = "SELECT d.document_id,d.stable_key,d.snapshot_id,d.kind,d.source_id,d.file_id,"
                + "d.start_line,d.end_line,d.content,d.checksum,"
                + "1.0-(d.embedding <=> CAST(? AS vector)) AS score "
                + "FROM semantic_index_meta m JOIN semantic_documents d "
                + "ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id "
                + "WHERE m.project_id=? AND 1.0-(d.embedding <=> CAST(? AS vector)) >= ? "
                + "ORDER BY d.embedding <=> CAST(? AS vector),d.stable_key LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, vector);
            statement.setObject(2, id);
            statement.setString(3, vector);
            statement.setDouble(4, minimumScore);
            statement.setString(5, vector);
            statement.setInt(6, limit);
            List<VectorHit> hits = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SemanticDocument document = document(projectId, result, 1);
                    double score = Math.max(-1.0, Math.min(1.0, result.getDouble(11)));
                    hits.add(new VectorHit(document, score));
                }
            }
            return List.copyOf(hits);
        }
    }

    static long sizeBytes(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(SUM(octet_length(d.content)+pg_column_size(d.embedding)),0) "
                        + "FROM semantic_index_meta m JOIN semantic_documents d "
                        + "ON d.project_id=m.project_id AND d.snapshot_id=m.snapshot_id WHERE m.project_id=?")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static SemanticDocument document(String projectId, ResultSet result, int start) throws SQLException {
        return new SemanticDocument(result.getString(start), result.getString(start + 1), projectId,
                result.getString(start + 2), SemanticDocumentKind.valueOf(result.getString(start + 3)),
                result.getString(start + 4), result.getString(start + 5), result.getInt(start + 6),
                result.getInt(start + 7), result.getString(start + 8), result.getString(start + 9));
    }

    private static double[] parseVector(String text, int dimensions) throws IOException {
        if (text == null || text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            throw new IOException("invalid pgvector payload");
        }
        String body = text.substring(1, text.length() - 1);
        String[] values = body.isBlank() ? new String[0] : body.split(",");
        if (values.length != dimensions) throw new IOException("pgvector dimensions mismatch");
        double[] parsed = new double[dimensions];
        try {
            for (int index = 0; index < dimensions; index++) parsed[index] = Double.parseDouble(values[index].trim());
        } catch (NumberFormatException exception) {
            throw new IOException("invalid pgvector numeric value", exception);
        }
        return parsed;
    }
}
