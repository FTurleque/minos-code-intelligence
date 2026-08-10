from pathlib import Path
import re


def read(path: str) -> str:
    return Path(path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    Path(path).write_text(text, encoding="utf-8", newline="\n")


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one anchor, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# MNC-03 — one shared installer: repository-packaged lockfiles + expected root integrity + npm ci --ignore-scripts.
helper = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/LockedNpmPackage.java"
write(helper, r'''package com.minos.adapter.scip.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Repository-owned npm lockfile preparation for managed SCIP runtimes. */
final class LockedNpmPackage {
    private LockedNpmPackage() {
    }

    static void prepare(
            Class<?> resourceOwner,
            Path installRoot,
            String lockResource,
            String packageName,
            String version,
            String expectedIntegrity
    ) throws IOException {
        Objects.requireNonNull(resourceOwner, "resourceOwner");
        Objects.requireNonNull(installRoot, "installRoot");
        requireText(lockResource, "lockResource");
        requireText(packageName, "packageName");
        requireText(version, "version");
        requireText(expectedIntegrity, "expectedIntegrity");
        Files.createDirectories(installRoot);
        Files.writeString(
                installRoot.resolve("package.json"),
                "{\n  \"private\": true,\n  \"dependencies\": {\n    \"" + packageName
                        + "\": \"" + version + "\"\n  }\n}\n",
                StandardCharsets.UTF_8);
        Path lock = installRoot.resolve("package-lock.json");
        try (InputStream input = resourceOwner.getResourceAsStream(lockResource)) {
            if (input == null) throw new IOException("packaged npm lockfile is missing: " + lockResource);
            Files.copy(input, lock, StandardCopyOption.REPLACE_EXISTING);
        }
        verify(lock, packageName, version, expectedIntegrity);
    }

    static void verify(Path lock, String packageName, String version, String expectedIntegrity) throws IOException {
        long size = Files.size(lock);
        if (size < 1L || size > 4L * 1024L * 1024L) {
            throw new IOException("managed npm lockfile size is invalid");
        }
        String json = Files.readString(lock, StandardCharsets.UTF_8);
        String marker = "\"node_modules/" + packageName + "\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("managed npm lockfile does not contain root package: " + packageName);
        int nextPackage = json.indexOf("\"node_modules/", start + marker.length());
        String rootEntry = nextPackage < 0 ? json.substring(start) : json.substring(start, nextPackage);
        if (!rootEntry.contains("\"version\": \"" + version + "\"")) {
            throw new IOException("managed npm lockfile root version mismatch for " + packageName);
        }
        if (!rootEntry.contains("\"integrity\": \"" + expectedIntegrity + "\"")) {
            throw new IOException("managed npm lockfile root integrity mismatch for " + packageName);
        }
        if (!json.contains("\"lockfileVersion\": 3")) {
            throw new IOException("managed npm lockfile must use lockfileVersion 3");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
''')

python_manager = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipPythonRuntimeManager.java"
text = read(python_manager)
text = text.replace(
    '    private static final String WINDOWS_COMPATIBILITY_PRELOAD = "minos-windows-regexp-compat.cjs";\n',
    '    private static final String WINDOWS_COMPATIBILITY_PRELOAD = "minos-windows-regexp-compat.cjs";\n'
    '    private static final String NPM_LOCK_RESOURCE = "scip-python-package-lock.json";\n'
    '    private static final String NPM_INTEGRITY = "sha512-qoKL1Rggg0o5newAFbCFAKlS0AjWxG5MA+mC28BtgxOv0DhO4zdL8u7151FxEppDpXMVvm7+yXSjXotoVH9cMQ==";\n',
    1,
)
old = '''        try {
            run(CommandLocator.invocation(
                    npm,
                    "install",
                    "--prefix", partial.toString(),
                    "--no-audit", "--no-fund",
                    "@sourcegraph/scip-python@" + VERSION
            ), home, toolsRoot.resolve("scip-python-install.log"), Duration.ofMinutes(10));
'''
new = '''        try {
            LockedNpmPackage.prepare(
                    ManagedScipPythonRuntimeManager.class,
                    partial,
                    NPM_LOCK_RESOURCE,
                    "@sourcegraph/scip-python",
                    VERSION,
                    NPM_INTEGRITY);
            run(CommandLocator.invocation(
                    npm,
                    "ci",
                    "--prefix", partial.toString(),
                    "--no-audit", "--no-fund", "--ignore-scripts"
            ), home, toolsRoot.resolve("scip-python-install.log"), Duration.ofMinutes(10));
'''
if text.count(old) != 1:
    raise SystemExit("ManagedScipPythonRuntimeManager npm install anchor mismatch")
text = text.replace(old, new, 1)
write(python_manager, text)

ts_manager = "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java"
text = read(ts_manager)
marker = '    private static final String WINDOWS_RUNNER_RESOURCE = "scip-java-windows-runner.ps1";\n'
if text.count(marker) != 1:
    raise SystemExit("ManagedScipProviderRuntimeManager constant anchor mismatch")
text = text.replace(
    marker,
    '    private static final String SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE = "scip-typescript-package-lock.json";\n'
    '    private static final String SCIP_TYPESCRIPT_NPM_INTEGRITY = "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==";\n'
    + marker,
    1,
)
old = '''        try {
            run(CommandLocator.invocation(
                    npm, "install", "--prefix", partial.toString(), "--no-audit", "--no-fund", "--ignore-scripts",
                    "@sourcegraph/scip-typescript@" + SCIP_TYPESCRIPT_VERSION),
                    home, toolsRoot.resolve("scip-typescript-install.log"), Duration.ofMinutes(10));
'''
new = '''        try {
            LockedNpmPackage.prepare(
                    ManagedScipProviderRuntimeManager.class,
                    partial,
                    SCIP_TYPESCRIPT_NPM_LOCK_RESOURCE,
                    "@sourcegraph/scip-typescript",
                    SCIP_TYPESCRIPT_VERSION,
                    SCIP_TYPESCRIPT_NPM_INTEGRITY);
            run(CommandLocator.invocation(
                    npm, "ci", "--prefix", partial.toString(), "--no-audit", "--no-fund", "--ignore-scripts"),
                    home, toolsRoot.resolve("scip-typescript-install.log"), Duration.ofMinutes(10));
'''
if text.count(old) != 1:
    raise SystemExit("ManagedScipProviderRuntimeManager npm install anchor mismatch")
text = text.replace(old, new, 1)
write(ts_manager, text)

# MNC-18 — ref-count JVM locks so completed project IDs do not accumulate forever.
lease = "minos-application/src/main/java/com/minos/orchestration/ProjectIndexLease.java"
text = read(lease)
text = text.replace(
    "    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();\n\n"
    "    private final ReentrantLock jvmLock;\n",
    "    private static final Object JVM_LOCK_MONITOR = new Object();\n"
    "    private static final ConcurrentMap<Path, LockState> JVM_LOCKS = new ConcurrentHashMap<>();\n\n"
    "    private final Path lockPath;\n"
    "    private final LockState lockState;\n"
    "    private final ReentrantLock jvmLock;\n",
    1,
)
text = text.replace(
    "    private ProjectIndexLease(ReentrantLock jvmLock, FileChannel channel, FileLock fileLock) {\n"
    "        this.jvmLock = jvmLock;\n"
    "        this.channel = channel;\n"
    "        this.fileLock = fileLock;\n"
    "    }\n",
    "    private ProjectIndexLease(\n"
    "            Path lockPath, LockState lockState, FileChannel channel, FileLock fileLock\n"
    "    ) {\n"
    "        this.lockPath = lockPath;\n"
    "        this.lockState = lockState;\n"
    "        this.jvmLock = lockState.lock;\n"
    "        this.channel = channel;\n"
    "        this.fileLock = fileLock;\n"
    "    }\n",
    1,
)
old_acquire = '''        ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock());
        jvm.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new ProjectIndexLease(jvm, channel, lock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            jvm.unlock();
            throw exception;
        }
'''
new_acquire = '''        LockState state;
        synchronized (JVM_LOCK_MONITOR) {
            state = JVM_LOCKS.computeIfAbsent(lockPath, ignored -> new LockState());
            state.references++;
        }
        state.lock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.lock();
            return new ProjectIndexLease(lockPath, state, channel, lock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            state.lock.unlock();
            releaseState(lockPath, state);
            throw exception;
        }
'''
if text.count(old_acquire) != 1:
    raise SystemExit("ProjectIndexLease acquire anchor mismatch")
text = text.replace(old_acquire, new_acquire, 1)
text = text.replace(
    "        } finally {\n"
    "            jvmLock.unlock();\n"
    "        }\n"
    "        if (failure != null) throw failure;\n"
    "    }\n"
    "}\n",
    "        } finally {\n"
    "            jvmLock.unlock();\n"
    "            releaseState(lockPath, lockState);\n"
    "        }\n"
    "        if (failure != null) throw failure;\n"
    "    }\n\n"
    "    static int retainedJvmLockCount() {\n"
    "        synchronized (JVM_LOCK_MONITOR) {\n"
    "            return JVM_LOCKS.size();\n"
    "        }\n"
    "    }\n\n"
    "    private static void releaseState(Path path, LockState state) {\n"
    "        synchronized (JVM_LOCK_MONITOR) {\n"
    "            state.references--;\n"
    "            if (state.references < 0) throw new IllegalStateException(\"project index JVM lock reference underflow\");\n"
    "            if (state.references == 0) JVM_LOCKS.remove(path, state);\n"
    "        }\n"
    "    }\n\n"
    "    private static final class LockState {\n"
    "        private final ReentrantLock lock = new ReentrantLock();\n"
    "        private int references;\n"
    "    }\n"
    "}\n",
    1,
)
write(lease, text)

# MNC-19 — bound post-parent stream-reader drain in the IntelliJ client.
intellij = "minos-intellij/src/main/java/com/minos/intellij/protocol/MinosCliClient.java"
text = read(intellij)
text = text.replace(
    "            if (!completed) terminate(process);\n"
    "            outReader.join(); errReader.join();\n",
    "            if (!completed) terminate(process);\n"
    "            awaitReaders(process, outReader, errReader);\n",
    1,
)
anchor = "    private static void joinAfterTermination(Thread... readers) {\n"
helper = '''    private static void awaitReaders(Process process, Thread... readers) throws IOException {
        boolean alive = false;
        for (Thread reader : readers) {
            try {
                reader.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("MINOS process output drain was interrupted", interrupted);
            }
            alive |= reader.isAlive();
        }
        if (!alive) return;
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        joinAfterTermination(readers);
        for (Thread reader : readers) {
            if (reader.isAlive()) throw new IOException("MINOS process output drain did not terminate");
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing a process pipe is best-effort after the bounded drain timeout.
        }
    }

'''
if text.count(anchor) != 1:
    raise SystemExit("MinosCliClient join helper anchor mismatch")
text = text.replace(anchor, helper + anchor, 1)
write(intellij, text)

# MNC-19 — Docker probe reader cannot hang forever after the direct child exits.
docker = "minos-app/src/main/java/com/minos/cli/DockerMcpTransport.java"
text = read(docker)
text = text.replace(
    "                    reader.join();\n"
    "                    IOException readFailure = readerFailure.get();\n",
    "                    joinReaderBounded(process, reader);\n"
    "                    IOException readFailure = readerFailure.get();\n",
    1,
)
anchor = "        private static void drainBounded(InputStream source, ByteArrayOutputStream captured) throws IOException {\n"
helper = '''        private static void joinReaderBounded(Process process, Thread reader)
                throws IOException, InterruptedException {
            reader.join(5_000L);
            if (!reader.isAlive()) return;
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // The process may already have closed its direct pipe.
            }
            reader.join(5_000L);
            if (reader.isAlive()) throw new IOException("Docker backend probe output drain did not terminate");
        }

'''
if text.count(anchor) != 1:
    raise SystemExit("DockerMcpTransport drain helper anchor mismatch")
text = text.replace(anchor, helper + anchor, 1)
write(docker, text)

print("MNC supply-chain/lifecycle remediation staged")
