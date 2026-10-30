package com.minos.storage.postgresql.semantic;

import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticVector;
import com.minos.semantic.SemanticVectorStore.IndexSnapshot;
import com.minos.semantic.SemanticVectorStore.IndexedDocument;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Transaction-local write primitives for the pgvector semantic index.
 *
 * <p>An index is written as a differential against the rows in place: the rows whose key left the index are
 * deleted, the new or changed documents are written with their vector, and a document marked as reusing its
 * stored vector only receives its new snapshot, identifier, source and position: its vector is neither sent
 * nor parsed again. The rows left are those a full replacement of the same index would leave.</p>
 */
final class PostgresSemanticWriteQueries {
    private static final int BATCH_SIZE = 500;

    private PostgresSemanticWriteQueries() {
    }

    static void replace(Connection connection, UUID projectId, IndexSnapshot snapshot)
            throws SQLException, IOException {
        Map<String, String> stored = storedChecksums(connection, projectId);
        List<IndexedDocument> embedded = new ArrayList<>();
        List<IndexedDocument> reused = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (IndexedDocument indexed : snapshot.documents()) {
            keys.add(indexed.document().stableKey());
            if (indexed.reusesStoredVector()) reused.add(indexed);
            else embedded.add(indexed);
        }
        if (!reused.isEmpty()) requireReusable(connection, projectId, snapshot, stored, reused);
        List<String> removed = new ArrayList<>();
        for (String key : stored.keySet()) {
            if (!keys.contains(key)) removed.add(key);
        }
        deleteDocuments(connection, projectId, removed);
        upsertDocuments(connection, projectId, embedded);
        attachReusedDocuments(connection, projectId, reused);
        upsertMetadata(connection, projectId, snapshot);
    }

    static void delete(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM semantic_documents WHERE project_id=?")) {
            statement.setObject(1, projectId);
            statement.executeUpdate();
        }
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

    /** Stable key and checksum of every row of the project, whatever snapshot it is attached to. */
    private static Map<String, String> storedChecksums(Connection connection, UUID projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT stable_key,checksum FROM semantic_documents WHERE project_id=?")) {
            statement.setObject(1, projectId);
            Map<String, String> checksums = new HashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) checksums.put(result.getString(1), result.getString(2));
            }
            return checksums;
        }
    }

    /**
     * A reused document keeps the vector in place only if that vector was computed by the same model for the
     * same content: otherwise the whole write is refused and the transaction leaves the previous index.
     */
    private static void requireReusable(Connection connection, UUID projectId, IndexSnapshot snapshot,
                                        Map<String, String> stored, List<IndexedDocument> reused)
            throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT provider_id,model_id,dimensions FROM semantic_index_meta WHERE project_id=?")) {
            statement.setObject(1, projectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IOException("semantic documents reuse stored vectors but the index holds none");
                }
                if (!snapshot.providerId().equals(result.getString(1))
                        || !snapshot.modelId().equals(result.getString(2))
                        || snapshot.dimensions() != result.getInt(3)) {
                    throw new IOException("semantic documents reuse vectors of another embedding model");
                }
            }
        }
        for (IndexedDocument indexed : reused) {
            SemanticDocument document = indexed.document();
            if (!document.checksum().equals(stored.get(document.stableKey()))) {
                throw new IOException("semantic document reuses a stored vector that does not exist: "
                        + document.stableKey());
            }
        }
    }

    private static void deleteDocuments(Connection connection, UUID projectId, List<String> keys)
            throws SQLException {
        if (keys.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM semantic_documents WHERE project_id=? AND stable_key=?")) {
            int pending = 0;
            for (String key : keys) {
                statement.setObject(1, projectId);
                statement.setString(2, key);
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    statement.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) statement.executeBatch();
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

    /** New or changed documents, written with their vector over any row holding the same key. */
    private static void upsertDocuments(Connection connection, UUID projectId, List<IndexedDocument> documents)
            throws SQLException {
        if (documents.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO semantic_documents(project_id,stable_key,document_id,snapshot_id,kind,source_id,"
                        + "file_id,start_line,end_line,content,checksum,embedding) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,CAST(? AS vector)) "
                        + "ON CONFLICT(project_id,stable_key) DO UPDATE SET document_id=EXCLUDED.document_id,"
                        + "snapshot_id=EXCLUDED.snapshot_id,kind=EXCLUDED.kind,source_id=EXCLUDED.source_id,"
                        + "file_id=EXCLUDED.file_id,start_line=EXCLUDED.start_line,end_line=EXCLUDED.end_line,"
                        + "content=EXCLUDED.content,checksum=EXCLUDED.checksum,embedding=EXCLUDED.embedding")) {
            int pending = 0;
            for (IndexedDocument indexed : documents) {
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

    /**
     * Attaches the reused rows to the new snapshot. Their content, kind and checksum are those in place (the
     * checksum covers them) but the identifier, the source and the position may have changed: they are
     * rewritten, and the embedding column is left out of the statement.
     */
    private static void attachReusedDocuments(Connection connection, UUID projectId, List<IndexedDocument> documents)
            throws SQLException {
        if (documents.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE semantic_documents SET document_id=?,snapshot_id=?,source_id=?,file_id=?,start_line=?,"
                        + "end_line=? WHERE project_id=? AND stable_key=? AND checksum=?")) {
            int pending = 0;
            for (IndexedDocument indexed : documents) {
                SemanticDocument document = indexed.document();
                statement.setString(1, document.id());
                statement.setString(2, document.snapshotId());
                statement.setString(3, document.sourceId());
                statement.setString(4, document.fileId());
                statement.setInt(5, document.startLine());
                statement.setInt(6, document.endLine());
                statement.setObject(7, projectId);
                statement.setString(8, document.stableKey());
                statement.setString(9, document.checksum());
                statement.addBatch();
                if (++pending == BATCH_SIZE) {
                    requireEveryRowAttached(statement.executeBatch());
                    pending = 0;
                }
            }
            if (pending > 0) requireEveryRowAttached(statement.executeBatch());
        }
    }

    private static void requireEveryRowAttached(int[] counts) throws SQLException {
        for (int count : counts) {
            if (count != 1 && count != Statement.SUCCESS_NO_INFO) {
                throw new SQLException("a reused semantic document was not found in place");
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
}
