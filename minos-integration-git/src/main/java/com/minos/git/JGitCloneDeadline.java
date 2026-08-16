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

/**
 * Applies the clone's absolute wall-clock budget to every HTTP connection created by JGit.
 *
 * <p>JGit's built-in transport timeout is an inactivity timeout expressed in whole seconds. That
 * alone cannot enforce an absolute clone deadline. This adapter clamps every connect/read timeout
 * to the budget remaining at that operation and wraps response/request streams so the remaining
 * timeout is refreshed before each blocking I/O call. GitHub/GitLab remote materialization only
 * permits HTTPS, so an unexpected non-HTTP transport is rejected fail-closed.</p>
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
        http.setHttpConnectionFactory(wrapFactory(http.getHttpConnectionFactory(), budget));
    }

    static HttpConnectionFactory wrapFactory(
            HttpConnectionFactory delegate,
            JGitRemoteRepositoryMaterializer.CloneBudget budget
    ) {
        return new DeadlineHttpConnectionFactory(delegate, budget);
    }

    private static final class DeadlineHttpConnectionFactory implements HttpConnectionFactory {
        private final HttpConnectionFactory delegate;
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;

        private DeadlineHttpConnectionFactory(
                HttpConnectionFactory delegate,
                JGitRemoteRepositoryMaterializer.CloneBudget budget
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.budget = Objects.requireNonNull(budget, "budget");
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            budget.enforceTimeout();
            return wrap(delegate.create(url), budget);
        }

        @Override
        public HttpConnection create(URL url, Proxy proxy) throws IOException {
            budget.enforceTimeout();
            return wrap(delegate.create(url, proxy), budget);
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

        Object result = invokeDelegate(connection, method, effectiveArgs);
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
            int value = super.read();
            budget.enforceTimeout();
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            prepareRead();
            int count = super.read(bytes, offset, length);
            budget.enforceTimeout();
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            prepareRead();
            long skipped = super.skip(count);
            budget.enforceTimeout();
            return skipped;
        }

        private void prepareRead() throws IOException {
            connection.setReadTimeout(budget.remainingTimeoutMillis());
            budget.enforceTimeout();
        }
    }

    private static final class DeadlineOutputStream extends FilterOutputStream {
        private final JGitRemoteRepositoryMaterializer.CloneBudget budget;

        private DeadlineOutputStream(
                OutputStream delegate,
                JGitRemoteRepositoryMaterializer.CloneBudget budget
        ) {
            super(delegate);
            this.budget = budget;
        }

        @Override
        public void write(int value) throws IOException {
            budget.enforceTimeout();
            out.write(value);
            budget.enforceTimeout();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            budget.enforceTimeout();
            out.write(bytes, offset, length);
            budget.enforceTimeout();
        }

        @Override
        public void flush() throws IOException {
            budget.enforceTimeout();
            out.flush();
            budget.enforceTimeout();
        }
    }
}
