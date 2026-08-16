package com.minos.git;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JGitCloneDeadlineTest {

    @Test
    void stalledHttpReadIsBoundedByAbsoluteCloneDeadlineWithoutProgressCallbacks(@TempDir Path temp) throws Exception {
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2,
                1024L * 1024L,
                100,
                100,
                200,
                Duration.ofMillis(500));
        JGitRemoteRepositoryMaterializer.CloneBudget budget =
                new JGitRemoteRepositoryMaterializer.CloneBudget(temp.resolve("repository"), policy);

        AtomicInteger connectTimeout = new AtomicInteger();
        AtomicInteger readTimeout = new AtomicInteger();
        HttpConnection raw = (HttpConnection) Proxy.newProxyInstance(
                HttpConnection.class.getClassLoader(),
                new Class<?>[]{HttpConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "setConnectTimeout" -> {
                        connectTimeout.set((Integer) args[0]);
                        yield null;
                    }
                    case "setReadTimeout" -> {
                        readTimeout.set((Integer) args[0]);
                        yield null;
                    }
                    case "getInputStream" -> new InputStream() {
                        @Override
                        public int read() throws IOException {
                            try {
                                Thread.sleep(Math.max(1, readTimeout.get()) + 25L);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException("test read interrupted", interrupted);
                            }
                            throw new SocketTimeoutException("simulated stalled socket");
                        }
                    };
                    case "getURL" -> new URL("https://github.com/acme/demo.git");
                    case "usingProxy" -> false;
                    case "getContentLength", "getResponseCode" -> 0;
                    default -> defaultValue(method.getReturnType());
                });

        HttpConnectionFactory delegate = new HttpConnectionFactory() {
            @Override
            public HttpConnection create(URL url) {
                return raw;
            }

            @Override
            public HttpConnection create(URL url, java.net.Proxy proxy) {
                return raw;
            }
        };

        HttpConnection bounded = JGitCloneDeadline.wrapFactory(delegate, budget)
                .create(new URL("https://github.com/acme/demo.git"));
        bounded.setConnectTimeout(30_000);
        bounded.setReadTimeout(30_000);

        assertTrue(connectTimeout.get() > 0 && connectTimeout.get() <= 500,
                "connect timeout must be clamped to the absolute remaining clone budget");
        assertTrue(readTimeout.get() > 0 && readTimeout.get() <= 500,
                "read timeout must be clamped to the absolute remaining clone budget");

        InputStream input = bounded.getInputStream();
        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class, input::read);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals("remote repository clone exceeds the configured time limit", failure.getMessage());
        assertTrue(elapsedMillis < 2_000L,
                "a stalled transport must not outlive the configured wall-clock budget indefinitely");
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
