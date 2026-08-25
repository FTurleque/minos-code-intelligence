package com.minos.storage.postgresql;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore.IndexSnapshot;
import com.minos.semantic.SemanticVectorStore.IndexedDocument;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** Transaction-local write primitives for the pgvector semantic index. */
final class PostgresSemanticWriteQueries {
    private static final int BATCH_SIZE = 500;

    private PostgresSemanticWriteQueries() {
    }

    static void replace(Connection connection, UUID projectId, IndexSnapshot snapshot) throws SQLException {
        deleteDocuments(connection, projectId);
        upsertMetadata(connection, projectId, snapshot);
        insertDocuments(connection, projectId, snapshot);
    }

    static void delete(Connection connection, UUID projectId) throws SQLException {
        deleteDocuments(connection, projectId);
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM semantic_index_meta WHERE project_id=?")) {
            statement.setObject(1, projectId);
            statement.executeUpdate();
        }
    }

    static String vectorLiteral(SemanticVector vector) {
        StringBuilder builder = new StringBuilder(vector.dimensions() * 12).append('[');
        for (int index = 0; index < vector.dimensions(); index++) {
            if (index > 0) builder.append(',');
            builder.append(Float.toString((float) vector.valueAt(index)));
        }
        return builder.append(']').toString();
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
                        + "model_id=EXCLUDED.model_id,dimensions=EXCLUDED.dimensions,built_at=EXCLUDED.built_at")) {
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
                if (++pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) statement.executeBatch();
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
}
