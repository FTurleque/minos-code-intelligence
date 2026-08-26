package com.minos.git;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JGitCloneEndpointPinTest {

    @TempDir
    Path temporary;

    @Test
    void rejectsCrossHostRedirectBeforeDelegateConnectionCreation() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        HttpConnectionFactory factory = JGitCloneDeadline.wrapFactory(
                delegate(delegateCalls),
                budget(),
                "github.com");

        URL crossHostRedirect = new URL("https://attacker.example/acme/demo.git?service=git-upload-pack");
        IOException failure = assertThrows(
                IOException.class,
                () -> factory.create(crossHostRedirect));

        assertEquals("JGit attempted a connection outside the pinned remote repository HTTPS endpoint",
                failure.getMessage());
        assertEquals(0, delegateCalls.get(), "untrusted redirect must be rejected before opening a socket");
    }

    @Test
    void rejectsProtocolAndPortChangesButAllowsSameHttpsEndpoint() throws Exception {
        AtomicInteger delegateCalls = new AtomicInteger();
        HttpConnectionFactory factory = JGitCloneDeadline.wrapFactory(
                delegate(delegateCalls),
                budget(),
                "gitlab.com");

        URL protocolChange = new URL("http://gitlab.com/acme/demo.git");
        assertThrows(IOException.class, () -> factory.create(protocolChange));
        URL portChange = new URL("https://gitlab.com:8443/acme/demo.git");
        assertThrows(IOException.class, () -> factory.create(portChange));

        HttpConnection allowed = factory.create(
                new URL("https://gitlab.com/acme/demo.git?service=git-upload-pack"));
        assertNotNull(allowed);
        assertEquals(1, delegateCalls.get());
    }

    private JGitRemoteRepositoryMaterializer.CloneBudget budget() {
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2,
                1024L * 1024L,
                100,
                100,
                200,
                Duration.ofSeconds(5));
        return new JGitRemoteRepositoryMaterializer.CloneBudget(
                temporary.resolve("repository"),
                policy);
    }

    private static HttpConnectionFactory delegate(AtomicInteger calls) {
        HttpConnection connection = (HttpConnection) Proxy.newProxyInstance(
                HttpConnection.class.getClassLoader(),
                new Class<?>[]{HttpConnection.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
        return new HttpConnectionFactory() {
            @Override
            public HttpConnection create(URL url) {
                calls.incrementAndGet();
                return connection;
            }

            @Override
            public HttpConnection create(URL url, java.net.Proxy proxy) {
                calls.incrementAndGet();
                return connection;
            }
        };
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        if (type == char.class) return '\0';
        throw new AssertionError("unsupported primitive: " + type);
    }
}
