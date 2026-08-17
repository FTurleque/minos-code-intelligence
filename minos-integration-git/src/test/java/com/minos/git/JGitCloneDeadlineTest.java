package com.minos.git;

import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
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

    @Test
    void stalledHttpWriteIsBoundedByAbsoluteCloneDeadline(@TempDir Path temp) throws Exception {
        assertOutputOperationIsBounded(temp, BlockOn.WRITE, output -> output.write(7));
    }

    @Test
    void stalledHttpFlushIsBoundedByAbsoluteCloneDeadline(@TempDir Path temp) throws Exception {
        assertOutputOperationIsBounded(temp, BlockOn.FLUSH, OutputStream::flush);
    }

    @Test
    void stalledHttpCloseIsBoundedByAbsoluteCloneDeadlineAndRemainsIdempotent(@TempDir Path temp) throws Exception {
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2,
                1024L * 1024L,
                100,
                100,
                200,
                Duration.ofMillis(300));
        JGitRemoteRepositoryMaterializer.CloneBudget budget =
                new JGitRemoteRepositoryMaterializer.CloneBudget(temp.resolve("repository"), policy);
        BlockingCloseOutputStream rawOutput = new BlockingCloseOutputStream();
        HttpConnection raw = outputConnection(rawOutput);
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
        OutputStream output = bounded.getOutputStream();
        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class, output::close);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals("remote repository clone exceeds the configured time limit", failure.getMessage());
        assertTrue(elapsedMillis < 2_000L,
                "a blocked request body close must not outlive the absolute clone deadline");
        assertTrue(rawOutput.entered.await(1, TimeUnit.SECONDS),
                "the fixture must prove the delegate close actually blocked");
        assertEquals(1, rawOutput.closeCalls.get(),
                "deadline expiry must not recursively invoke another potentially blocking close");

        // The wrapper is logically closed as soon as close starts, even if the delegate remains stuck.
        output.close();
        assertEquals(1, rawOutput.closeCalls.get(), "repeated close must remain idempotent");

        rawOutput.release.countDown();
        assertTrue(rawOutput.completed.await(1, TimeUnit.SECONDS),
                "the fixture close worker must be releasable after the bounded caller returns");
    }

    private static void assertOutputOperationIsBounded(
            Path temp,
            BlockOn blockOn,
            OutputOperation operation
    ) throws Exception {
        RemoteRepositoryCachePolicy policy = new RemoteRepositoryCachePolicy(
                2,
                1024L * 1024L,
                100,
                100,
                200,
                Duration.ofMillis(300));
        JGitRemoteRepositoryMaterializer.CloneBudget budget =
                new JGitRemoteRepositoryMaterializer.CloneBudget(temp.resolve("repository"), policy);
        BlockingOutputStream rawOutput = new BlockingOutputStream(blockOn);
        HttpConnection raw = outputConnection(rawOutput);
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
        OutputStream output = bounded.getOutputStream();
        long started = System.nanoTime();
        IOException failure = assertThrows(IOException.class, () -> operation.run(output));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertEquals("remote repository clone exceeds the configured time limit", failure.getMessage());
        assertTrue(elapsedMillis < 2_000L,
                "a blocked request body operation must not outlive the absolute clone deadline");
        assertTrue(rawOutput.entered.await(1, TimeUnit.SECONDS),
                "the fixture must prove the delegate operation actually blocked");
        assertTrue(rawOutput.closed.await(1, TimeUnit.SECONDS),
                "deadline expiry must close the request stream to unblock the transport");
    }

    private static HttpConnection outputConnection(OutputStream output) {
        return (HttpConnection) Proxy.newProxyInstance(
                HttpConnection.class.getClassLoader(),
                new Class<?>[]{HttpConnection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOutputStream" -> output;
                    case "getURL" -> new URL("https://github.com/acme/demo.git");
                    case "usingProxy" -> false;
                    case "getContentLength", "getResponseCode" -> 0;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private enum BlockOn { WRITE, FLUSH }

    private static final class BlockingOutputStream extends OutputStream {
        private final BlockOn blockOn;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        private BlockingOutputStream(BlockOn blockOn) {
            this.blockOn = blockOn;
        }

        @Override
        public void write(int value) throws IOException {
            if (blockOn == BlockOn.WRITE) blockUntilClosed();
        }

        @Override
        public void flush() throws IOException {
            if (blockOn == BlockOn.FLUSH) blockUntilClosed();
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private void blockUntilClosed() throws IOException {
            entered.countDown();
            while (closed.getCount() != 0L) {
                try {
                    closed.await();
                } catch (InterruptedException ignored) {
                    // Deliberately ignore interruption: only closing the delegate releases this fixture.
                }
            }
        }
    }

    private static final class BlockingCloseOutputStream extends OutputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            entered.countDown();
            try {
                while (release.getCount() != 0L) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // Deliberately ignore interruption: the fixture models an uncooperative close.
                    }
                }
            } finally {
                completed.countDown();
            }
        }
    }

    @FunctionalInterface
    private interface OutputOperation {
        void run(OutputStream output) throws IOException;
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
