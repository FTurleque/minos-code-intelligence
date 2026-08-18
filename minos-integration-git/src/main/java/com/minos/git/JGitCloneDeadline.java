package com.minos.git;

import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Proxy;
import java.net.URL;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Applies the clone's absolute wall-clock budget and endpoint pin to every HTTP connection created by JGit.
 *
 * <p>JGit's built-in transport timeout is an inactivity timeout expressed in whole seconds. That
 * alone cannot enforce an absolute clone deadline. This adapter clamps every connect/read timeout
 * to the budget remaining at that operation and wraps response/request streams. Reads refresh the
 * socket timeout before each blocking call; response closes and request writes, flushes and closes
 * execute in a virtual thread and are awaited only for the absolute budget remaining. On expiry an
 * in-flight write/flush closes the request stream asynchronously to unblock the transport; an
 * in-flight close is only interrupted because issuing a second close could itself block indefinitely.
 * Every production connection is also revalidated against the original HTTPS host/port before the
 * delegate creates it, so redirects can never silently cross the GitHub/GitLab trust boundary.</p>
 */
final class JGitCloneDeadline {

    private JGitCloneDeadline() {
    }

    static void configure(Transport transport, JGitRemoteRepositoryMaterializer.CloneBudget budget) {
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(budget, "budget");
        if (!(transport instanceof TransportHttp http)) {
            throw new IllegalStateException("remote repository clone requires the JGit HTTPS transport");
        }
        String scheme = http.getURI().getScheme();
        String host = http.getURI().getHost();
        int port = http.getURI().getPort();
        if (!"https".equalsIgnoreCase(scheme)
                || host == null || host.isBlank()
                || (port != -1 && port != 443)) {
            throw new IllegalStateException("remote repository clone requires one pinned HTTPS endpoint on port 443");
        }
        http.setHttpConnectionFactory(wrapFactory(
                http.getHttpConnectionFactory(),
                budget,
                new AllowedEndpoint(host)));
    }

    /** Deadline-only wrapper retained for focused stream tests. Production uses the endpoint-pinned overload. */
    static HttpConnectionFactory wrapFactory(
            HttpConnectionFactory delegate,
            JGitRemoteRepositoryMaterializer.CloneBudget budget
    ) {
        return new DeadlineHttpConnectionFactory(delegate, budget, null);
    }

    static HttpConnectionFactory wrapFactory(
            HttpConnectionFactory delegate,
            JGitRemoteRepositoryMaterializer.CloneBudget budget,
            String expectedHost
    ) {
        return wrapFactory(delegate, budget, new AllowedEndpoint(expectedHost));
    }

    private static HttpConnectionFactory wrapFactory(
            HttpConnectionFactory delegate,
            JGitRemoteRepositoryMaterializer.CloneBudget budget,
            AllowedEndpoint endpoint
    ) {
        return new DeadlineHttpConnectionFactory(delegate, budget, endpoint);
    }

    private static final class DeadlineHttpConnectionFactory implements HttpConnectionFactory {
        private final HttpConnectionFactory delegate;
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;
        private final AllowedEndpoint endpoint;

        private DeadlineHttpConnectionFactory(
                HttpConnectionFactory delegate,
                JGitRemoteRepositoryMaterializer.CloneBudget budget,
                AllowedEndpoint endpoint
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.budget = Objects.requireNonNull(budget, "budget");
            this.endpoint = endpoint;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            budget.enforceTimeout();
            validateEndpoint(url);
            return wrap(delegate.create(url), budget);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            budget.enforceTimeout();
            validateEndpoint(url);
            return wrap(delegate.create(url, proxy), budget);
        }

        private void validateEndpoint(URL url) throws IOException {
            if (endpoint != null) endpoint.validate(url);
        }
    }

    private record AllowedEndpoint(String host) {
        private AllowedEndpoint {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("expected remote repository host must not be blank");
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
        }

        private void validate(URL url) throws IOException {
            Objects.requireNonNull(url, "url");
            int port = url.getPort();
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !host.equalsIgnoreCase(url.getHost())
                    || (port != -1 && port != 443)
                    || url.getUserInfo() != null) {
                throw new IOException("JGit attempted a connection outside the pinned remote repository HTTPS endpoint");
            }
        }
    }

    private static HttpConnection wrap(
            HttpConnection connection,
            JGitRemoteRepositoryMaterializer.CloneBudget budget
    ) {
        Objects.requireNonNull(connection, "connection");
        return (HttpConnection) java.lang.reflect.Proxy.newProxyInstance(
                HttpConnection.class.getClassLoader(),
                new Class<?>[]{HttpConnection.class},
                (proxy, method, args) -> invoke(connection, budget, method, args));
    }

    private static Object invoke(
            HttpConnection connection,
            JGitRemoteRepositoryMaterializer.CloneBudget budget,
            Method method,
            Object[] args
    ) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return invokeDelegate(connection, method, args);
        }

        budget.enforceTimeoutUnchecked();
        Object[] effectiveArgs = args;
        String methodName = method.getName();
        if (("setConnectTimeout".equals(methodName) || "setReadTimeout".equals(methodName))
                && args != null && args.length == 1 && args[0] instanceof Integer configured) {
            effectiveArgs = new Object[]{budget.clampTimeoutMillis(configured)};
        }

        Object result;
        try {
            result = invokeDelegate(connection, method, effectiveArgs);
        } catch (Throwable failure) {
            // If the underlying connection failed because the clamped timeout expired, make the
            // absolute clone deadline the observable cause. Otherwise preserve the transport error.
            budget.enforceTimeoutUnchecked();
            throw failure;
        }
        budget.enforceTimeoutUnchecked();
        if (result instanceof InputStream input) {
            return new DeadlineInputStream(input, connection, budget);
        }
        if (result instanceof OutputStream output) {
            return new DeadlineOutputStream(output, budget);
        }
        return result;
    }

    private static Object invokeDelegate(HttpConnection connection, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException invocation) {
            throw invocation.getCause();
        }
    }

    private static final class DeadlineInputStream extends FilterInputStream {
        private final HttpConnection connection;
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DeadlineInputStream(
                InputStream delegate,
                HttpConnection connection,
                JGitRemoteRepositoryMaterializer.CloneBudget budget
        ) {
            super(delegate);
            this.connection = connection;
            this.budget = budget;
        }

        @Override
        public int read() throws IOException {
            prepareRead();
            try {
                int value = super.read();
                budget.enforceTimeout();
                return value;
            } catch (IOException failure) {
                budget.enforceTimeout();
                throw failure;
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            prepareRead();
            try {
                int count = super.read(bytes, offset, length);
                budget.enforceTimeout();
                return count;
            } catch (IOException failure) {
                budget.enforceTimeout();
                throw failure;
            }
        }

        @Override
        public long skip(long count) throws IOException {
            prepareRead();
            try {
                long skipped = super.skip(count);
                budget.enforceTimeout();
                return skipped;
            } catch (IOException failure) {
                budget.enforceTimeout();
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            runBoundedClose();
        }

        private void prepareRead() throws IOException {
            if (closed.get()) {
                throw new IOException("JGit HTTP response stream is closed");
            }
            connection.setReadTimeout(budget.remainingTimeoutMillis());
            budget.enforceTimeout();
        }

        private void runBoundedClose() throws IOException {
            try {
                budget.enforceTimeout();
            } catch (IOException timeout) {
                closeDelegateAsync();
                throw timeout;
            }

            FutureTask<Void> closeTask = new FutureTask<>(() -> {
                in.close();
                return null;
            });
            Thread worker = Thread.ofVirtual()
                    .name("minos-jgit-http-input-close")
                    .start(closeTask);

            try {
                awaitCloseTask(closeTask);
            } catch (InterruptedException interrupted) {
                worker.interrupt();
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while enforcing remote repository clone deadline", interrupted);
            } catch (ExecutionException execution) {
                budget.enforceTimeout();
                rethrowInputFailure(execution.getCause());
            } catch (IOException timeout) {
                // Do not issue a second close: the first close is precisely the operation that may
                // be stuck. Interrupt the worker and let the bounded caller return at the deadline.
                worker.interrupt();
                throw timeout;
            }

            // A close that completed just after the deadline still fails as a deadline breach.
            budget.enforceTimeout();
        }

        private void awaitCloseTask(FutureTask<Void> closeTask)
                throws InterruptedException, ExecutionException, IOException {
            while (!closeTask.isDone()) {
                int remainingMillis = budget.remainingTimeoutMillis();
                try {
                    closeTask.get(remainingMillis, TimeUnit.MILLISECONDS);
                } catch (TimeoutException timeout) {
                    // Millisecond rounding may wake just before the monotonic deadline. Re-check
                    // and loop if there is still budget; once expired this throws the stable error.
                    budget.enforceTimeout();
                }
            }
            // Surface delegate failures, including Errors captured by FutureTask, without catching
            // Throwable in MINOS code. A successful task returns null immediately here.
            closeTask.get();
        }

        private void closeDelegateAsync() {
            Thread.ofVirtual()
                    .name("minos-jgit-http-input-close")
                    .start(() -> {
                        try {
                            in.close();
                        } catch (IOException ignored) {
                            // The deadline failure remains authoritative; close is best-effort cleanup.
                        }
                    });
        }

        private static void rethrowInputFailure(Throwable failure) throws IOException {
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IOException("unexpected JGit HTTP input failure", failure);
        }
    }

    private static final class DeadlineOutputStream extends FilterOutputStream {
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;
        private final AtomicBoolean closed = new AtomicBoolean();

        private DeadlineOutputStream(
                OutputStream delegate,
                JGitRemoteRepositoryMaterializer.CloneBudget budget
        ) {
            super(delegate);
            this.budget = budget;
        }

        @Override
        public void write(int value) throws IOException {
            requireOpen();
            runBounded(() -> out.write(value), true);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireOpen();
            runBounded(() -> out.write(bytes, offset, length), true);
        }

        @Override
        public void flush() throws IOException {
            requireOpen();
            runBounded(out::flush, true);
        }

        @Override
        public void close() throws IOException {
            if (!closed.compareAndSet(false, true)) return;
            runBounded(out::close, false);
        }

        private void requireOpen() throws IOException {
            if (closed.get()) {
                throw new IOException("JGit HTTP request stream is closed");
            }
        }

        private void runBounded(IoOperation operation, boolean closeDelegateOnAbort) throws IOException {
            try {
                budget.enforceTimeout();
            } catch (IOException timeout) {
                if (closeDelegateOnAbort) closed.set(true);
                closeDelegateAsync();
                throw timeout;
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            Thread worker = Thread.ofVirtual()
                    .name("minos-jgit-http-output")
                    .start(() -> {
                        try {
                            operation.run();
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            completed.countDown();
                        }
                    });

            try {
                while (completed.getCount() != 0L) {
                    int remainingMillis = budget.remainingTimeoutMillis();
                    if (completed.await(remainingMillis, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                    // Millisecond rounding may wake just before the monotonic deadline. Re-check
                    // and loop if there is still budget; once expired this throws the stable error.
                    budget.enforceTimeout();
                }
            } catch (InterruptedException interrupted) {
                abort(worker, closeDelegateOnAbort);
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while enforcing remote repository clone deadline", interrupted);
            } catch (IOException timeout) {
                abort(worker, closeDelegateOnAbort);
                throw timeout;
            }

            // An operation that completed just after the deadline still fails as a deadline breach.
            try {
                budget.enforceTimeout();
            } catch (IOException timeout) {
                if (closeDelegateOnAbort) {
                    closed.set(true);
                    closeDelegateAsync();
                }
                throw timeout;
            }
            rethrow(failure.get());
        }

        private void abort(Thread worker, boolean closeDelegateOnAbort) {
            worker.interrupt();
            if (!closeDelegateOnAbort) return;
            closed.set(true);
            closeDelegateAsync();
        }

        private void closeDelegateAsync() {
            Thread.ofVirtual()
                    .name("minos-jgit-http-output-close")
                    .start(() -> {
                        try {
                            out.close();
                        } catch (IOException ignored) {
                            // The deadline failure remains authoritative; close is best-effort cleanup.
                        }
                    });
        }

        private static void rethrow(Throwable failure) throws IOException {
            if (failure == null) return;
            if (failure instanceof IOException io) throw io;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IOException("unexpected JGit HTTP output failure", failure);
        }

        @FunctionalInterface
        private interface IoOperation {
            void run() throws IOException;
        }
    }
}
