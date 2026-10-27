package com.minos.storage.postgresql.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thread ownership of the lifecycle lock, without a database: the marker that lets a write skip the
 * transactional advisory lock is bound to one project and one exact session, and a mismatch fails closed.
 */
class PostgresProjectMutationLockTest {
    private final List<Runnable> cleanups = new ArrayList<>();

    @AfterEach
    void releaseLifecycleMarkers() {
        // The ownership marker is thread-local: a test must never leave one on the JUnit worker thread.
        cleanups.forEach(Runnable::run);
    }

    @Test
    void theKeyIsStableAndDependsOnBothHalvesOfTheProjectId() {
        UUID projectId = new UUID(0x0123456789ABCDEFL, 0x0FEDCBA987654321L);

        assertEquals(0x0123456789ABCDEFL ^ 0x0FEDCBA987654321L, PostgresProjectMutationLock.key(projectId));
        assertEquals(PostgresProjectMutationLock.key(projectId), PostgresProjectMutationLock.key(projectId));
        assertNotEquals(PostgresProjectMutationLock.key(projectId),
                PostgresProjectMutationLock.key(new UUID(0x0123456789ABCDEFL, 0L)));
        assertNotEquals(PostgresProjectMutationLock.key(projectId),
                PostgresProjectMutationLock.key(new UUID(0L, 0x0FEDCBA987654321L)));
    }

    @Test
    void missingArgumentsAreRefused() {
        UUID projectId = UUID.randomUUID();
        Connection connection = connectionRefusingStatements("unused");

        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.key(null));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.acquire(null, projectId));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.acquire(connection, null));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.enterLifecycle(null, connection));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.enterLifecycle(projectId, null));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.exitLifecycle(null, connection));
        assertThrows(NullPointerException.class, () -> PostgresProjectMutationLock.exitLifecycle(projectId, null));
    }

    @Test
    void ownershipIsBoundToOneProjectAndOneExactSession() {
        UUID projectId = UUID.randomUUID();
        Connection session = connectionRefusingStatements("session");
        Connection otherSession = connectionRefusingStatements("other-session");

        assertFalse(PostgresProjectMutationLock.lifecycleOwned(projectId, session), "nothing is owned before entering");
        enter(projectId, session);

        assertTrue(PostgresProjectMutationLock.lifecycleOwned(projectId, session));
        assertFalse(PostgresProjectMutationLock.lifecycleOwned(UUID.randomUUID(), session));
        assertFalse(PostgresProjectMutationLock.lifecycleOwned(projectId, otherSession));

        PostgresProjectMutationLock.exitLifecycle(projectId, session);
        assertFalse(PostgresProjectMutationLock.lifecycleOwned(projectId, session), "ownership ends with the lifecycle");
    }

    @Test
    void aWriteOnTheOwningSessionSkipsTheTransactionalLockButAnotherProjectOrSessionStillTakesIt() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        AtomicBoolean statementRequested = new AtomicBoolean();
        Connection session = connectionRefusingStatements("session", statementRequested);
        Connection otherSession = connectionRefusingStatements("other-session");
        enter(projectId, session);

        PostgresProjectMutationLock.acquire(session, projectId);
        assertFalse(statementRequested.get(), "the owning session must not re-acquire a lock it already holds");

        SQLException otherProjectLock = assertThrows(SQLException.class,
                () -> PostgresProjectMutationLock.acquire(session, otherProject));
        SQLException otherSessionLock = assertThrows(SQLException.class,
                () -> PostgresProjectMutationLock.acquire(otherSession, projectId));
        assertEquals("statement requested on session", otherProjectLock.getMessage());
        assertEquals("statement requested on other-session", otherSessionLock.getMessage());
    }

    @Test
    void reEnteringTheSameLifecycleIsAllowedButAnotherProjectOrSessionIsRefused() {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        Connection session = connectionRefusingStatements("session");
        Connection otherSession = connectionRefusingStatements("other-session");
        enter(projectId, session);

        PostgresProjectMutationLock.enterLifecycle(projectId, session);
        IllegalStateException anotherProject = assertThrows(IllegalStateException.class,
                () -> PostgresProjectMutationLock.enterLifecycle(otherProject, session));
        IllegalStateException anotherSession = assertThrows(IllegalStateException.class,
                () -> PostgresProjectMutationLock.enterLifecycle(projectId, otherSession));

        String expected = "thread already owns a PostgreSQL lifecycle lock for another project or connection: " + projectId;
        assertEquals(expected, anotherProject.getMessage());
        assertEquals(expected, anotherSession.getMessage());
        assertTrue(PostgresProjectMutationLock.lifecycleOwned(projectId, session), "a refused entry must not replace the owner");
        assertFalse(PostgresProjectMutationLock.lifecycleOwned(otherProject, session));
    }

    @Test
    void leavingALifecycleThatIsNotOwnedIsRefusedAndKeepsTheRealOwner() {
        UUID projectId = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        Connection session = connectionRefusingStatements("session");
        Connection otherSession = connectionRefusingStatements("other-session");

        IllegalStateException neverEntered = assertThrows(IllegalStateException.class,
                () -> PostgresProjectMutationLock.exitLifecycle(projectId, session));
        assertEquals("PostgreSQL lifecycle ownership context mismatch: expected=" + projectId + " actual=null",
                neverEntered.getMessage());

        enter(projectId, session);
        IllegalStateException anotherProject = assertThrows(IllegalStateException.class,
                () -> PostgresProjectMutationLock.exitLifecycle(otherProject, session));
        IllegalStateException anotherSession = assertThrows(IllegalStateException.class,
                () -> PostgresProjectMutationLock.exitLifecycle(projectId, otherSession));

        assertEquals("PostgreSQL lifecycle ownership context mismatch: expected=" + otherProject + " actual=" + projectId,
                anotherProject.getMessage());
        assertEquals("PostgreSQL lifecycle ownership context mismatch: expected=" + projectId + " actual=" + projectId,
                anotherSession.getMessage());
        assertTrue(PostgresProjectMutationLock.lifecycleOwned(projectId, session));
    }

    private void enter(UUID projectId, Connection session) {
        PostgresProjectMutationLock.enterLifecycle(projectId, session);
        cleanups.add(() -> {
            if (PostgresProjectMutationLock.lifecycleOwned(projectId, session)) {
                PostgresProjectMutationLock.exitLifecycle(projectId, session);
            }
        });
    }

    private static Connection connectionRefusingStatements(String name) {
        return connectionRefusingStatements(name, new AtomicBoolean());
    }

    /** A session stand-in: preparing a statement on it is recorded and refused, so a skipped lock is observable. */
    private static Connection connectionRefusingStatements(String name, AtomicBoolean statementRequested) {
        return (Connection) Proxy.newProxyInstance(
                PostgresProjectMutationLockTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "prepareStatement" -> {
                        statementRequested.set(true);
                        throw new SQLException("statement requested on " + name);
                    }
                    case "toString" -> name;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
