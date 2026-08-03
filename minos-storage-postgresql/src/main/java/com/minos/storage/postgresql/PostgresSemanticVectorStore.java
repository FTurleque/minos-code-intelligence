package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticDocumentKind;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class PostgresSemanticVectorStore implements SemanticVectorStore {
    private static final int BATCH_SIZE = 500;
    private final PostgresConnectionFactory connections;

    PostgresSemanticVectorStore(PostgresConnectionFactory connections) { this.connections = connections; }

    @Override public String searchEngine() { return "pgvector-exact-cosine"; }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try (Connection c = connections.open()) {
            String snapshotId; String providerId; String modelId; int dimensions; long builtAt;
            try (PreparedStatement meta = c.prepareStatement(
                    "SELECT snapshot_id,provider_id,model_id,dimensions,built_at FROM semantic_index_meta WHERE project_id=?")) {
                meta.setObject(1, id);
                try (ResultSet r = meta.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    snapshotId = r.getString(1); providerId = r.getString(2); modelId = r.getString(3);
                    dimensions = r.getInt(4); builtAt = r.getLong(5);
                }
            }
            List<IndexedDocument> documents = new ArrayList<>();
            try (PreparedStatement docs = c.prepareStatement(
                    "SELECT document_id,stable_key,snapshot_id,kind,source_id,file_id,start_line,end_line,content,checksum,embedding::text " +
                            "FROM semantic_documents WHERE project_id=? ORDER BY stable_key")) {
                docs.setObject(1, id);
                try (ResultSet r = docs.executeQuery()) {
                    while (r.next()) {
                        SemanticDocument document = document(projectId, r);
                        double[] values = parseVector(r.getString(11), dimensions);
                        documents.add(new IndexedDocument(document, SemanticVector.fromArray(document.stableKey(), values)));
                    }
                }
            }
            return Optional.of(new IndexSnapshot(projectId, snapshotId, providerId, modelId, dimensions, builtAt, documents));
        } catch (SQLException e) { throw new IOException("unable to load pgvector semantic index", e); }
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        UUID projectId = uuid(snapshot.projectId());
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement delete = c.prepareStatement("DELETE FROM semantic_documents WHERE project_id=?")) {
                    delete.setObject(1, projectId); delete.executeUpdate();
                }
                try (PreparedStatement meta = c.prepareStatement(
                        "INSERT INTO semantic_index_meta(project_id,snapshot_id,provider_id,model_id,dimensions,built_at) VALUES (?,?,?,?,?,?) " +
                                "ON CONFLICT(project_id) DO UPDATE SET snapshot_id=EXCLUDED.snapshot_id,provider_id=EXCLUDED.provider_id,model_id=EXCLUDED.model_id,dimensions=EXCLUDED.dimensions,built_at=EXCLUDED.built_at")) {
                    meta.setObject(1, projectId); meta.setString(2, snapshot.snapshotId()); meta.setString(3, snapshot.providerId());
                    meta.setString(4, snapshot.modelId()); meta.setInt(5, snapshot.dimensions()); meta.setLong(6, snapshot.builtAtEpochMilli()); meta.executeUpdate();
                }
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO semantic_documents(project_id,stable_key,document_id,snapshot_id,kind,source_id,file_id,start_line,end_line,content,checksum,embedding) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector))")) {
                    int pending = 0;
                    for (IndexedDocument indexed : snapshot.documents()) {
                        SemanticDocument d = indexed.document();
                        insert.setObject(1, projectId); insert.setString(2, d.stableKey()); insert.setString(3, d.id());
                        insert.setString(4, d.snapshotId()); insert.setString(5, d.kind().name()); insert.setString(6, d.sourceId());
                        insert.setString(7, d.fileId()); insert.setInt(8, d.startLine()); insert.setInt(9, d.endLine());
                        insert.setString(10, d.content()); insert.setString(11, d.checksum()); insert.setString(12, vectorLiteral(indexed.vector()));
                        insert.addBatch(); pending++;
                        if (pending == BATCH_SIZE) { insert.executeBatch(); pending = 0; }
                    }
                    if (pending > 0) insert.executeBatch();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw new IOException("unable to replace pgvector semantic index", e);
            }
        } catch (SQLException e) { throw new IOException("unable to replace pgvector semantic index", e); }
    }

    @Override
    public void delete(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement docs = c.prepareStatement("DELETE FROM semantic_documents WHERE project_id=?");
                 PreparedStatement meta = c.prepareStatement("DELETE FROM semantic_index_meta WHERE project_id=?")) {
                docs.setObject(1, id); docs.executeUpdate(); meta.setObject(1, id); meta.executeUpdate(); c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
        } catch (SQLException e) { throw new IOException("unable to delete pgvector semantic index", e); }
    }

    @Override
    public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore) throws IOException {
        UUID id = uuid(projectId);
        String vector = vectorLiteral(query);
        List<VectorHit> hits = new ArrayList<>();
        String sql = "SELECT document_id,stable_key,snapshot_id,kind,source_id,file_id,start_line,end_line,content,checksum," +
                "1.0-(embedding <=> CAST(? AS vector)) AS score FROM semantic_documents WHERE project_id=? " +
                "AND 1.0-(embedding <=> CAST(? AS vector)) >= ? ORDER BY embedding <=> CAST(? AS vector), stable_key LIMIT ?";
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, vector); s.setObject(2, id); s.setString(3, vector); s.setDouble(4, minimumScore); s.setString(5, vector); s.setInt(6, limit);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    SemanticDocument document = document(projectId, r);
                    double score = Math.max(-1.0, Math.min(1.0, r.getDouble(11)));
                    hits.add(new VectorHit(document, score));
                }
            }
            return List.copyOf(hits);
        } catch (SQLException e) { throw new IOException("unable to search pgvector semantic index", e); }
    }

    @Override
    public long sizeBytes(String projectId) throws IOException {
        try (Connection c = connections.open(); PreparedStatement s = c.prepareStatement(
                "SELECT COALESCE(SUM(octet_length(content)+pg_column_size(embedding)),0) FROM semantic_documents WHERE project_id=?")) {
            s.setObject(1, uuid(projectId)); try (ResultSet r = s.executeQuery()) { r.next(); return r.getLong(1); }
        } catch (SQLException e) { throw new IOException("unable to measure pgvector semantic index", e); }
    }

    private static SemanticDocument document(String projectId, ResultSet r) throws SQLException {
        return new SemanticDocument(r.getString(1), r.getString(2), projectId, r.getString(3),
                SemanticDocumentKind.valueOf(r.getString(4)), r.getString(5), r.getString(6), r.getInt(7), r.getInt(8),
                r.getString(9), r.getString(10));
    }

    private static UUID uuid(String projectId) throws IOException {
        try { return UUID.fromString(projectId); }
        catch (IllegalArgumentException e) { throw new IOException("semantic project id must be a UUID", e); }
    }

    private static String vectorLiteral(SemanticVector vector) {
        StringBuilder b = new StringBuilder(vector.dimensions() * 12).append('[');
        for (int i = 0; i < vector.dimensions(); i++) {
            if (i > 0) b.append(',');
            b.append(Float.toString((float) vector.valueAt(i)));
        }
        return b.append(']').toString();
    }

    private static double[] parseVector(String text, int dimensions) throws IOException {
        if (text == null || text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            throw new IOException("invalid pgvector payload");
        }
        String body = text.substring(1, text.length() - 1);
        String[] values = body.isBlank() ? new String[0] : body.split(",");
        if (values.length != dimensions) throw new IOException("pgvector dimensions mismatch");
        double[] result = new double[dimensions];
        try {
            for (int i = 0; i < dimensions; i++) result[i] = Double.parseDouble(values[i].trim());
        } catch (NumberFormatException e) { throw new IOException("invalid pgvector numeric value", e); }
        return result;
    }
}
