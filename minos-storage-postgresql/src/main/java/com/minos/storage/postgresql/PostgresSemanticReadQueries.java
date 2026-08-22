package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore.IndexMetadata;
import com.minos.semantic.SemanticVectorStore.IndexSnapshot;
import com.minos.semantic.SemanticVectorStore.IndexedDocument;
import com.minos.semantic.SemanticVectorStore.VectorHit;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
