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

    PostgresSemanticVectorStore(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public String searchEngine() {
        return "pgvector-exact-cosine";
    }

    @Override
    public Optional<IndexMetadata> metadata(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at,"
                                + "COUNT(d.stable_key) "
                                + "FROM semantic_index_meta m LEFT JOIN semantic_documents d "
                                + "ON d.project_id=m.project_id WHERE m.project_id=? "
                                + "GROUP BY m.snapshot_id,m.provider_id,m.model_id,m.dimensions,m.built_at")) {
                    statement.setObject(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        long count = result.getLong(6);
                        if (count > Integer.MAX_VALUE) {
                            throw new IOException("semantic document count exceeds supported integer range");
                        }
                        return Optional.of(new IndexMetadata(
                                projectId,
                                result.getString(1),
                                result.getString(2),
                                result.getString(3),
                                result.getInt(4),
                                result.getLong(5),
                                (int) count));
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load pgvector semantic metadata", exception);
        }
    }

    @Override
    public Optional<IndexSnapshot> load(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            return connections.withConnection(connection -> {
                String snapshotId;
                String providerId;
                String modelId;
                int dimensions;
                long builtAt;
                try (PreparedStatement metadata = connection.prepareStatement(
                        "SELECT snapshot_id,provider_id,model_id,dimensions,built_at "
                                + "FROM semantic_index_meta WHERE project_id=?")) {
                    metadata.setObject(1, id);
                    try (ResultSet result = metadata.executeQuery()) {
                        if (!result.next()) {
                            return Optional.empty();
                        }
                        snapshotId = result.getString(1);
                        providerId = result.getString(2);
                        modelId = result.getString(3);
                        dimensions = result.getInt(4);
                        builtAt = result.getLong(5);
                    }
                }
                List<IndexedDocument> documents = loadDocuments(connection, projectId, id, dimensions);
                return Optional.of(new IndexSnapshot(
                        projectId, snapshotId, providerId, modelId, dimensions, builtAt, documents));
            });
        } catch (SQLException exception) {
            throw new IOException("unable to load pgvector semantic index", exception);
        }
    }

    private static List<IndexedDocument> loadDocuments(
            Connection connection,
            String projectId,
            UUID id,
            int dimensions
    ) throws SQLException, IOException {
        List<IndexedDocument> documents = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT document_id,stable_key,snapshot_id,kind,source_id,file_id,start_line,end_line,"
                        + "content,checksum,embedding::text "
                        + "FROM semantic_documents WHERE project_id=? ORDER BY stable_key")) {
            statement.setObject(1, id);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SemanticDocument document = document(projectId, result);
                    double[] values = parseVector(result.getString(11), dimensions);
                    documents.add(new IndexedDocument(
                            document,
                            SemanticVector.fromArray(document.stableKey(), values)));
                }
            }
        }
        return documents;
    }

    @Override
    public void replace(IndexSnapshot snapshot) throws IOException {
        UUID projectId = uuid(snapshot.projectId());
        try {
            connections.inTransaction(connection -> {
                deleteDocuments(connection, projectId);
                upsertMetadata(connection, projectId, snapshot);
                insertDocuments(connection, projectId, snapshot);
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to replace pgvector semantic index", exception);
        }
    }

    private static void deleteDocuments(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM semantic_documents WHERE project_id=?")) {
            statement.setObject(1, projectId);
            statement.executeUpdate();
        }
    }

    private static void upsertMetadata(Connection connection, UUID projectId, IndexSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_index_meta(project_id,snapshot_id,provider_id,model_id,dimensions,built_at) "
                        + "VALUES (?,?,?,?,?,?) ON CONFLICT(project_id) DO UPDATE SET "
                        + "snapshot_id=EXCLUDED.snapshot_id,provider_id=EXCLUDED.provider_id,"
                        + "model_id=EXCLUDED.model_id,dimensions=EXCLUDED.dimensions,"
                        + "built_at=EXCLUDED.built_at")) {
            statement.setObject(1, projectId);
            statement.setString(2, snapshot.snapshotId());
            statement.setString(3, snapshot.providerId());
            statement.setString(4, snapshot.modelId());
            statement.setInt(5, snapshot.dimensions());
            statement.setLong(6, snapshot.builtAtEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void insertDocuments(Connection connection, UUID projectId, IndexSnapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_documents(project_id,stable_key,document_id,snapshot_id,kind,source_id,"
                        + "file_id,start_line,end_line,content,checksum,embedding) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector))")) {
            int pending = 0;
            for (IndexedDocument indexed : snapshot.documents()) {
                bindDocument(statement, projectId, indexed);
                statement.addBatch();
                pending++;
                if (pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                statement.executeBatch();
            }
        }
    }

    private static void bindDocument(PreparedStatement statement, UUID projectId, IndexedDocument indexed)
            throws SQLException {
        SemanticDocument document = indexed.document();
        statement.setObject(1, projectId);
        statement.setString(2, document.stableKey());
        statement.setString(3, document.id());
        statement.setString(4, document.snapshotId());
        statement.setString(5, document.kind().name());
        statement.setString(6, document.sourceId());
        statement.setString(7, document.fileId());
        statement.setInt(8, document.startLine());
        statement.setInt(9, document.endLine());
        statement.setString(10, document.content());
        statement.setString(11, document.checksum());
        statement.setString(12, vectorLiteral(indexed.vector()));
    }

    @Override
    public void delete(String projectId) throws IOException {
        UUID id = uuid(projectId);
        try {
            connections.inTransaction(connection -> {
                deleteDocuments(connection, id);
                try (PreparedStatement metadata = connection.prepareStatement(
                        "DELETE FROM semantic_index_meta WHERE project_id=?")) {
                    metadata.setObject(1, id);
                    metadata.executeUpdate();
                }
                return null;
            });
        } catch (SQLException exception) {
            throw new IOException("unable to delete pgvector semantic index", exception);
        }
    }

    @Override
    public List<VectorHit> search(String projectId, SemanticVector query, int limit, double minimumScore)
            throws IOException {
        UUID id = uuid(projectId);
        String vector = vectorLiteral(query);
        String sql = "SELECT document_id,stable_key,snapshot_id,kind,source_id,file_id,start_line,end_line,"
                + "content,checksum,1.0-(embedding <=> CAST(? AS vector)) AS score "
                + "FROM semantic_documents WHERE project_id=? "
                + "AND 1.0-(embedding <=> CAST(? AS vector)) >= ? "
                + "ORDER BY embedding <=> CAST(? AS vector), stable_key LIMIT ?";
        try {
            return connections.withConnection(connection -> {
                List<VectorHit> hits = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, vector);
                    statement.setObject(2, id);
                    statement.setString(3, vector);
                    statement.setDouble(4, minimumScore);
                    statement.setString(5, vector);
                    statement.setInt(6, limit);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            SemanticDocument document = document(projectId, result);
                            double score = Math.max(-1.0, Math.min(1.0, result.getDouble(11)));
                            hits.add(new VectorHit(document, score));
                        }
                    }
                }
                return List.copyOf(hits);
            });
        } catch (SQLException exception) {
            throw new IOException("unable to search pgvector semantic index", exception);
        }
    }

    @Override
    public long sizeBytes(String projectId) throws IOException {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT COALESCE(SUM(octet_length(content)+pg_column_size(embedding)),0) "
                                + "FROM semantic_documents WHERE project_id=?")) {
                    statement.setObject(1, uuid(projectId));
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        return result.getLong(1);
                    }
                }
            });
        } catch (SQLException exception) {
            throw new IOException("unable to measure pgvector semantic index", exception);
        }
    }

    private static SemanticDocument document(String projectId, ResultSet result) throws SQLException {
        return new SemanticDocument(
                result.getString(1),
                result.getString(2),
                projectId,
                result.getString(3),
                SemanticDocumentKind.valueOf(result.getString(4)),
                result.getString(5),
                result.getString(6),
                result.getInt(7),
                result.getInt(8),
                result.getString(9),
                result.getString(10));
    }

    private static UUID uuid(String projectId) throws IOException {
        try {
            return UUID.fromString(projectId);
        } catch (IllegalArgumentException exception) {
            throw new IOException("semantic project id must be a UUID", exception);
        }
    }

    private static String vectorLiteral(SemanticVector vector) {
        StringBuilder builder = new StringBuilder(vector.dimensions() * 12).append('[');
        for (int index = 0; index < vector.dimensions(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(Float.toString((float) vector.valueAt(index)));
        }
        return builder.append(']').toString();
    }

    private static double[] parseVector(String text, int dimensions) throws IOException {
        if (text == null || text.length() < 2 || text.charAt(0) != '[' || text.charAt(text.length() - 1) != ']') {
            throw new IOException("invalid pgvector payload");
        }
        String body = text.substring(1, text.length() - 1);
        String[] values = body.isBlank() ? new String[0] : body.split(",");
        if (values.length != dimensions) {
            throw new IOException("pgvector dimensions mismatch");
        }
        double[] result = new double[dimensions];
        try {
            for (int index = 0; index < dimensions; index++) {
                result[index] = Double.parseDouble(values[index].trim());
            }
        } catch (NumberFormatException exception) {
            throw new IOException("invalid pgvector numeric value", exception);
        }
        return result;
    }
}
