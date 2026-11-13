package com.minos.storage.postgresql;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Privileges of the PostgreSQL inspection role that pgAdmin connects with.
 *
 * <p>The two SQL scripts under test are the ones the Docker lifecycle script sends to the managed PostgreSQL
 * ({@code docker/scripts/mcp-lifecycle.ps1}, here-strings {@code $InspectRoleSql} and
 * {@code $InspectRoleRemovalSql}): they are read from that file, not copied here. The read-only guarantee is
 * asserted on the privileges, with {@code default_transaction_read_only} switched off in the session: that
 * setting is a session default the role itself can change.</p>
 */
class PostgresInspectionRoleTest extends PostgresTestSupport {

    private static final String ROLE = "minos_inspect";
    private static final String SCHEMA = "minos";
    private static final String PERMISSION_DENIED = "42501";
    private static final String LIFECYCLE_SCRIPT = "docker/scripts/mcp-lifecycle.ps1";
    private static final Set<String> READABLE_TABLES = Set.of(
            "semantic_documents", "semantic_index_meta", "knowledge_active", "project_index_state", "schema_version");
    private static final List<String> WITHHELD_TABLES = List.of(
            "knowledge_snapshots", "indexing_runs", "runtime_sessions", "fingerprint_snapshots", "fingerprint_active",
            "workspaces");

    private final String password = randomPassword();

    @AfterEach
    void dropTheRole() throws Exception {
        if (connections == null) {
            return;
        }
        // The role is global to the server: it must not outlive the database of the class.
        executeAsOwner("DROP OWNED BY " + ROLE, true);
        executeAsOwner("DROP ROLE IF EXISTS " + ROLE, false);
    }

    @Test
    void writesAreRefusedByThePrivilegesOnceTheSessionIsNoLongerReadOnly() throws Exception {
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            statement.execute("SET default_transaction_read_only = off");
            for (String write : List.of(
                    "DELETE FROM semantic_documents",
                    "UPDATE semantic_documents SET content = ''",
                    "INSERT INTO knowledge_active(project_id, snapshot_id) VALUES (gen_random_uuid(), 's')",
                    "TRUNCATE semantic_index_meta",
                    "CREATE TABLE " + SCHEMA + ".inspection_probe (id integer)")) {
                assertDenied(statement, write);
            }
        }
    }

    @Test
    void theRootOfAProjectIsNotReadableWhileItsOtherColumnsAre() throws Exception {
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            assertDenied(statement, "SELECT root_value FROM projects");
            assertDenied(statement, "SELECT * FROM projects");
            assertDoesNotThrow(() -> statement.executeQuery(
                    "SELECT id, display_name, workspace_id, created_at, updated_at FROM projects").close());
        }
    }

    @Test
    void onlyTheClosedListOfTablesIsReadable() throws Exception {
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            for (String table : READABLE_TABLES) {
                assertDoesNotThrow(() -> statement.executeQuery("SELECT count(*) FROM " + table).close(), table);
            }
            for (String table : WITHHELD_TABLES) {
                assertDenied(statement, "SELECT count(*) FROM " + table);
            }
            Set<String> readable = new TreeSet<>();
            Set<String> writable = new TreeSet<>();
            try (ResultSet tables = statement.executeQuery("""
                    SELECT table_name,
                           has_table_privilege(current_user, format('%I.%I', table_schema, table_name), 'SELECT'),
                           has_table_privilege(current_user, format('%I.%I', table_schema, table_name),
                                               'INSERT, UPDATE, DELETE, TRUNCATE')
                    FROM information_schema.tables WHERE table_schema = current_schema()
                    """)) {
                while (tables.next()) {
                    if (tables.getBoolean(2)) readable.add(tables.getString(1));
                    if (tables.getBoolean(3)) writable.add(tables.getString(1));
                }
            }
            assertEquals(new TreeSet<>(READABLE_TABLES), readable);
            assertEquals(Set.of(), writable);
            try (ResultSet attributes = statement.executeQuery("""
                    SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls,
                           has_schema_privilege(current_user, current_schema(), 'CREATE'),
                           has_database_privilege(current_user, current_database(), 'CREATE')
                    FROM pg_roles WHERE rolname = current_user
                    """)) {
                assertTrue(attributes.next());
                assertFalse(attributes.getBoolean(1), "the role holds an administration attribute");
                assertFalse(attributes.getBoolean(2), "the role may create objects in the MINOS schema");
                assertFalse(attributes.getBoolean(3), "the role may create schemas in the database");
            }
        }
    }

    @Test
    void aTableCreatedAfterwardsStaysInvisibleUntilItIsGranted() throws Exception {
        applyRole();
        executeAsOwner("CREATE TABLE future_table (id integer)", false);
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            assertDenied(statement, "SELECT count(*) FROM future_table");
        } finally {
            executeAsOwner("DROP TABLE future_table", false);
        }
    }

    @Test
    void applyingTheScriptAgainRestoresTheClosedList() throws Exception {
        applyRole();
        executeAsOwner("GRANT INSERT, DELETE ON semantic_documents TO " + ROLE, false);
        executeAsOwner("GRANT SELECT ON knowledge_snapshots TO " + ROLE, false);
        executeAsOwner("GRANT SELECT (root_value) ON projects TO " + ROLE, false);
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            statement.execute("SET default_transaction_read_only = off");
            assertDenied(statement, "DELETE FROM semantic_documents");
            assertDenied(statement, "SELECT count(*) FROM knowledge_snapshots");
            assertDenied(statement, "SELECT root_value FROM projects");
        }
    }

    @Test
    void theSessionOfTheRoleIsBoundedByDefault() throws Exception {
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            assertEquals("on", single(statement, "SHOW default_transaction_read_only"));
            assertEquals("30s", single(statement, "SHOW statement_timeout"));
            assertEquals("256MB", single(statement, "SHOW temp_file_limit"));
            // A superuser-only setting: the role cannot lift this bound, unlike the two session defaults above.
            assertDenied(statement, "SET temp_file_limit = -1");
        }
        assertEquals(8L, countAsOwner("SELECT rolconnlimit FROM pg_roles WHERE rolname = '" + ROLE + "'"));
    }

    @Test
    void aRoleThatIsAMemberOfAnotherRoleIsRefusedInsteadOfBeingGranted() throws Exception {
        applyRole();
        // The script cannot take a membership back: pg_read_all_data would read every table, root_value included.
        executeAsOwner("GRANT pg_read_all_data TO " + ROLE, false);
        SQLException refused = assertThrows(SQLException.class, this::applyRole);
        assertTrue(refused.getMessage().contains("member of another role"), refused.getMessage());
        // The removal script takes such a role away, after which the closed list holds again.
        executeAsOwner(script("InspectRoleRemovalSql"), false);
        applyRole();
        try (Connection inspect = openAs(ROLE, password, SCHEMA); Statement statement = inspect.createStatement()) {
            assertDenied(statement, "SELECT root_value FROM projects");
        }
    }

    @Test
    void removingTheRoleLeavesTheDataUntouched() throws Exception {
        executeAsOwner("""
                INSERT INTO semantic_documents(project_id, stable_key, document_id, snapshot_id, kind, source_id,
                                               file_id, start_line, end_line, content, checksum, embedding)
                VALUES (gen_random_uuid(), 'k', 'd', 's', 'symbol', 'src', NULL, 1, 2, 'content', 'c', '[1,2,3]')
                """, false);
        applyRole();
        long before = countAsOwner("SELECT count(*) FROM semantic_documents");

        executeAsOwner(script("InspectRoleRemovalSql"), false);

        assertEquals(0L, countAsOwner("SELECT count(*) FROM pg_roles WHERE rolname = '" + ROLE + "'"));
        assertEquals(1L, before);
        assertEquals(before, countAsOwner("SELECT count(*) FROM semantic_documents"));
        SQLException refused = assertThrows(SQLException.class, this::connectAsTheRole);
        assertTrue(refused.getSQLState().startsWith("28"), refused.getSQLState());
        // Replayable: removing a role that is already gone is not an error.
        executeAsOwner(script("InspectRoleRemovalSql"), false);
    }

    /** Opens then closes a session of the role; the single call an {@code assertThrows} of a refused login needs. */
    private void connectAsTheRole() throws SQLException {
        try (Connection inspect = openAs(ROLE, password, SCHEMA)) {
            assertTrue(inspect.isValid(5), "the session of the role is not usable");
        }
    }

    private void applyRole() throws Exception {
        executeAsOwner(script("InspectRoleSql"), false);
        executeAsOwner("ALTER ROLE " + ROLE + " WITH LOGIN PASSWORD '" + password + "'", false);
    }

    private void executeAsOwner(String sql, boolean ignoreMissingRole) throws SQLException, IOException {
        connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            } catch (SQLException failure) {
                // 42704: undefined object, the role was never created by the test.
                if (!ignoreMissingRole || !"42704".equals(failure.getSQLState())) throw failure;
            }
            return null;
        });
    }

    private long countAsOwner(String sql) throws SQLException, IOException {
        return connections.withConnection(connection -> {
            try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
                result.next();
                return result.getLong(1);
            }
        });
    }

    private static void assertDenied(Statement statement, String sql) {
        SQLException refused = assertThrows(SQLException.class, () -> statement.execute(sql), sql);
        assertEquals(PERMISSION_DENIED, refused.getSQLState(), sql + ": " + refused.getMessage());
    }

    private static String single(Statement statement, String sql) throws SQLException {
        try (ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    /** The body of the here-string {@code $<name> = @' ... '@} of the Docker lifecycle script. */
    private static String script(String name) throws IOException {
        Path file = repositoryRoot().resolve(LIFECYCLE_SCRIPT);
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher body = Pattern.compile("(?s)\\$" + name + " = @'\\r?\\n(.*?)\\r?\\n'@").matcher(text);
        if (!body.find()) {
            fail(LIFECYCLE_SCRIPT + " defines no $" + name + " here-string: the inspection role has no script");
        }
        return body.group(1);
    }

    private static Path repositoryRoot() {
        for (Path current = Path.of("").toAbsolutePath(); current != null; current = current.getParent()) {
            if (Files.isRegularFile(current.resolve(LIFECYCLE_SCRIPT))) {
                return current;
            }
        }
        return fail("the repository root was not found above the working directory");
    }

    private static String randomPassword() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
