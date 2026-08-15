package com.minos.store;

import com.minos.io.BoundedInputStream;
import com.minos.io.DurableAtomicFile;
import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedProjectBinding;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import com.minos.hosted.SharedWorkspace;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** AES-256-GCM, tenant-addressed and optimistic-concurrency local M27 control-plane store. */
public final class FileHostedControlPlaneStore implements HostedControlPlaneStore {
    public static final long DEFAULT_MAX_TENANT_BYTES = 32L * 1024L * 1024L;
    private static final int MAGIC = 0x4D485431;
    private static final int VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_STRING_BYTES = 128 * 1024;
    private static final int JVM_LOCK_STRIPES = 64;
    private static final ReentrantLock[] JVM_LOCKS = locks();

    private final Path root;
    private final HostedTenantKeyProvider keys;
    private final long maxTenantBytes;
    private final SecureRandom random;

    public FileHostedControlPlaneStore(Path root, HostedTenantKeyProvider keys) throws IOException {
        this(root, keys, DEFAULT_MAX_TENANT_BYTES, new SecureRandom());
    }

    FileHostedControlPlaneStore(Path root, HostedTenantKeyProvider keys, long maxTenantBytes, SecureRandom random)
            throws IOException {
        if (maxTenantBytes < 1024 || maxTenantBytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid tenant byte limit");
        }
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.keys = Objects.requireNonNull(keys, "keys");
        this.maxTenantBytes = maxTenantBytes;
        this.random = Objects.requireNonNull(random, "random");
        if (Files.exists(this.root, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(this.root)) {
            throw new IOException("hosted control-plane root must not be a symbolic link");
        }
        DurableAtomicFile.ensureDirectory(this.root, "hosted control-plane root");
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("hosted control-plane root is not a directory");
        }
    }

    @Override
    public void create(HostedTenantState state) throws IOException {
        Objects.requireNonNull(state, "state");
        if (state.version() != 0) throw new IllegalArgumentException("new tenant state version must be zero");
        try (TenantLock ignored = lock(state.tenantId())) {
            Path target = tenantFile(state.tenantId());
            rejectUnsafeEntry(target);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("hosted tenant already exists: " + state.tenantId());
            }
            writeAtomically(target, state, false);
        }
    }

    @Override
    public Optional<HostedTenantState> find(UUID tenantId) throws IOException {
        Objects.requireNonNull(tenantId, "tenantId");
        try (TenantLock ignored = lock(tenantId)) {
            Path target = tenantFile(tenantId);
            rejectUnsafeEntry(target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(read(target, tenantId));
        }
    }

    @Override
    public void save(HostedTenantState state, long expectedVersion) throws IOException {
        Objects.requireNonNull(state, "state");
        if (expectedVersion < 0 || state.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("hosted tenant save requires version expectedVersion + 1");
        }
        try (TenantLock ignored = lock(state.tenantId())) {
            Path target = tenantFile(state.tenantId());
            rejectUnsafeEntry(target);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("hosted tenant does not exist: " + state.tenantId());
            }
            HostedTenantState current = read(target, state.tenantId());
            if (current.version() != expectedVersion) {
                throw new IOException("hosted tenant concurrent modification: expected version "
                        + expectedVersion + " but found " + current.version());
            }
            if (!current.tenantId().equals(state.tenantId())) throw new IOException("hosted tenant identity mutation");
            writeAtomically(target, state, true);
        }
    }

    public Path root() {
        return root;
    }

    private HostedTenantState read(Path file, UUID expectedTenant) throws IOException {
        requireRegularFile(file);
        byte[] bytes;
        try (BoundedInputStream input = new BoundedInputStream(
                Files.newInputStream(file), maxTenantBytes, "hosted tenant file")) {
            bytes = input.readAllBytes();
        }
        if (bytes.length < 1) throw new IOException("hosted tenant file size is invalid");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) throw new IOException("invalid hosted tenant magic");
            if (input.readInt() != VERSION) throw new IOException("unsupported hosted tenant version");
            UUID tenantId = new UUID(input.readLong(), input.readLong());
            if (!expectedTenant.equals(tenantId)) throw new IOException("hosted tenant filename/header mismatch");
            String keyId = readString(input, "keyId");
            int nonceLength = input.readInt();
            if (nonceLength != NONCE_BYTES) throw new IOException("invalid hosted tenant nonce length");
            byte[] nonce = input.readNBytes(nonceLength);
            if (nonce.length != nonceLength) throw new EOFException("truncated hosted tenant nonce");
            int cipherLength = input.readInt();
            if (cipherLength < 16 || cipherLength > maxTenantBytes || cipherLength > input.available()) {
                throw new IOException("invalid hosted tenant ciphertext length");
            }
            byte[] ciphertext = input.readNBytes(cipherLength);
            if (ciphertext.length != cipherLength || input.available() != 0) {
                throw new IOException("trailing or truncated hosted tenant bytes");
            }
            byte[] plaintext = decrypt(tenantId, keyId, nonce, ciphertext);
            try {
                HostedTenantState state = decodePlaintext(plaintext);
                if (!tenantId.equals(state.tenantId()) || !keyId.equals(state.keyId())) {
                    throw new IOException("hosted tenant authenticated header/payload mismatch");
                }
                return state;
            } finally {
                java.util.Arrays.fill(plaintext, (byte) 0);
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("invalid hosted tenant payload", exception);
        }
    }

    private void writeAtomically(Path target, HostedTenantState state, boolean replaceExisting) throws IOException {
        byte[] plaintext = encodePlaintext(state);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte[] ciphertext;
        try {
            ciphertext = encrypt(state.tenantId(), state.keyId(), nonce, plaintext);
        } finally {
            java.util.Arrays.fill(plaintext, (byte) 0);
        }
        byte[] envelope;
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(state.tenantId().getMostSignificantBits());
            output.writeLong(state.tenantId().getLeastSignificantBits());
            writeString(output, state.keyId());
            output.writeInt(nonce.length);
            output.write(nonce);
            output.writeInt(ciphertext.length);
            output.write(ciphertext);
            output.flush();
            envelope = buffer.toByteArray();
        }
        if (envelope.length > maxTenantBytes) throw new IOException("encoded hosted tenant exceeds byte limit");
        Path temporary = Files.createTempFile(root, ".hosted-tenant-", ".tmp");
        try {
            Files.write(temporary, envelope, StandardOpenOption.TRUNCATE_EXISTING);
            if (replaceExisting) {
                DurableAtomicFile.replace(temporary, target, "hosted tenant state replacement");
            } else {
                DurableAtomicFile.publish(temporary, target, "hosted tenant state publication");
            }
        } finally {
            Files.deleteIfExists(temporary);
            java.util.Arrays.fill(envelope, (byte) 0);
            java.util.Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private byte[] encrypt(UUID tenantId, String keyId, byte[] nonce, byte[] plaintext) throws IOException {
        return crypt(Cipher.ENCRYPT_MODE, tenantId, keyId, nonce, plaintext);
    }

    private byte[] decrypt(UUID tenantId, String keyId, byte[] nonce, byte[] ciphertext) throws IOException {
        try {
            return crypt(Cipher.DECRYPT_MODE, tenantId, keyId, nonce, ciphertext);
        } catch (IOException exception) {
            if (exception.getCause() instanceof AEADBadTagException) {
                throw new IOException("hosted tenant authentication tag mismatch", exception);
            }
            throw exception;
        }
    }

    private byte[] crypt(int mode, UUID tenantId, String keyId, byte[] nonce, byte[] input) throws IOException {
        try {
            SecretKey key = keys.resolve(tenantId, keyId, HostedTenantKeyProvider.Purpose.ENCRYPTION);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(tenantId, keyId));
            return cipher.doFinal(input);
        } catch (GeneralSecurityException | IllegalStateException exception) {
            throw new IOException("hosted tenant cryptographic operation failed", exception);
        }
    }

    private static byte[] aad(UUID tenantId, String keyId) {
        return ("MHT1\0" + VERSION + "\0" + tenantId + "\0" + keyId).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] encodePlaintext(HostedTenantState state) throws IOException {
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeLong(state.tenantId().getMostSignificantBits());
            output.writeLong(state.tenantId().getLeastSignificantBits());
            writeString(output, state.name());
            writeString(output, state.keyId());
            output.writeLong(state.version());
            writeInstant(output, state.createdAt());
            writeInstant(output, state.updatedAt());
            output.writeInt(state.retentionPolicy().maxAuditEvents());
            output.writeInt(state.retentionPolicy().auditRetentionDays());
            output.writeInt(state.retentionPolicy().archivedWorkspaceRetentionDays());
            output.writeInt(state.members().size());
            for (HostedPrincipal member : state.members()) {
                writeString(output, member.principalId());
                writeString(output, member.displayName());
                writeString(output, member.role().name());
                writeInstant(output, member.createdAt());
            }
            output.writeInt(state.workspaces().size());
            for (SharedWorkspace workspace : state.workspaces()) {
                writeUuid(output, workspace.workspaceId());
                writeUuid(output, workspace.tenantId());
                writeString(output, workspace.name());
                writeString(output, workspace.status().name());
                writeInstant(output, workspace.createdAt());
                writeInstant(output, workspace.updatedAt());
                output.writeBoolean(workspace.archivedAt() != null);
                if (workspace.archivedAt() != null) writeInstant(output, workspace.archivedAt());
                output.writeInt(workspace.bindings().size());
                for (HostedProjectBinding binding : workspace.bindings()) {
                    writeUuid(output, binding.projectId());
                    writeString(output, binding.snapshotId());
                    writeInstant(output, binding.boundAt());
                    writeString(output, binding.boundBy());
                }
            }
            output.writeLong(state.auditSequence());
            writeString(output, state.auditAnchorHash());
            output.writeInt(state.auditEvents().size());
            for (HostedAuditEvent event : state.auditEvents()) {
                output.writeLong(event.sequence());
                writeUuid(output, event.tenantId());
                writeInstant(output, event.occurredAt());
                writeString(output, event.principalId());
                writeString(output, event.action());
                writeString(output, event.resourceType());
                writeString(output, event.resourceId());
                writeString(output, event.outcome().name());
                writeString(output, event.requestId());
                writeString(output, event.keyId());
                writeString(output, event.previousHash());
                writeString(output, event.hash());
            }
            output.flush();
            return buffer.toByteArray();
        }
    }

    private HostedTenantState decodePlaintext(byte[] plaintext) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            UUID tenantId = readUuid(input);
            String name = readString(input, "name");
            String keyId = readString(input, "keyId");
            long version = input.readLong();
            Instant createdAt = readInstant(input);
            Instant updatedAt = readInstant(input);
            HostedRetentionPolicy retention = new HostedRetentionPolicy(input.readInt(), input.readInt(), input.readInt());
            int memberCount = boundedCount(input.readInt(), HostedTenantState.MAX_MEMBERS, "member count");
            List<HostedPrincipal> members = new ArrayList<>(memberCount);
            for (int index = 0; index < memberCount; index++) {
                members.add(new HostedPrincipal(readString(input, "principalId"), readString(input, "displayName"),
                        HostedRole.valueOf(readString(input, "role")), readInstant(input)));
            }
            int workspaceCount = boundedCount(input.readInt(), HostedTenantState.MAX_WORKSPACES, "workspace count");
            List<SharedWorkspace> workspaces = new ArrayList<>(workspaceCount);
            for (int index = 0; index < workspaceCount; index++) {
                UUID workspaceId = readUuid(input);
                UUID workspaceTenant = readUuid(input);
                String workspaceName = readString(input, "workspace name");
                SharedWorkspace.Status status = SharedWorkspace.Status.valueOf(readString(input, "workspace status"));
                Instant workspaceCreated = readInstant(input);
                Instant workspaceUpdated = readInstant(input);
                Instant archivedAt = input.readBoolean() ? readInstant(input) : null;
                int bindingCount = boundedCount(input.readInt(), SharedWorkspace.MAX_BINDINGS, "binding count");
                List<HostedProjectBinding> bindings = new ArrayList<>(bindingCount);
                for (int binding = 0; binding < bindingCount; binding++) {
                    bindings.add(new HostedProjectBinding(readUuid(input), readString(input, "snapshotId"),
                            readInstant(input), readString(input, "boundBy")));
                }
                workspaces.add(new SharedWorkspace(workspaceId, workspaceTenant, workspaceName, status,
                        workspaceCreated, workspaceUpdated, archivedAt, bindings));
            }
            long auditSequence = input.readLong();
            String auditAnchor = readString(input, "audit anchor");
            int auditCount = boundedCount(input.readInt(), HostedRetentionPolicy.MAX_AUDIT_EVENTS, "audit count");
            List<HostedAuditEvent> audit = new ArrayList<>(auditCount);
            for (int index = 0; index < auditCount; index++) {
                audit.add(new HostedAuditEvent(input.readLong(), readUuid(input), readInstant(input),
                        readString(input, "principalId"), readString(input, "action"),
                        readString(input, "resourceType"), readString(input, "resourceId"),
                        HostedAuditEvent.Outcome.valueOf(readString(input, "outcome")),
                        readString(input, "requestId"), readString(input, "keyId"),
                        readString(input, "previousHash"),
                        readString(input, "hash")));
            }
            if (input.available() != 0) throw new IOException("trailing hosted tenant plaintext bytes");
            return new HostedTenantState(tenantId, name, keyId, version, createdAt, updatedAt, retention,
                    members, workspaces, auditSequence, auditAnchor, audit);
        } catch (EOFException exception) {
            throw new IOException("truncated hosted tenant plaintext", exception);
        }
    }

    private TenantLock lock(UUID tenantId) throws IOException {
        Path lockPath = root.resolve(tenantId + ".lock");
        rejectUnsafeEntry(lockPath);
        ReentrantLock jvmLock = JVM_LOCKS[Math.floorMod(lockPath.hashCode(), JVM_LOCKS.length)];
        jvmLock.lock();
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock fileLock = channel.lock();
            return new TenantLock(jvmLock, channel, fileLock);
        } catch (IOException | RuntimeException exception) {
            if (channel != null) channel.close();
            jvmLock.unlock();
            throw exception;
        }
    }

    private Path tenantFile(UUID tenantId) {
        Path file = root.resolve(tenantId + ".mht").normalize();
        if (!file.getParent().equals(root)) throw new IllegalStateException("tenant path escaped root");
        return file;
    }

    private static void rejectUnsafeEntry(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
            throw new IOException("hosted control-plane entry must not be a symbolic link: " + path.getFileName());
        }
    }

    private static void requireRegularFile(Path path) throws IOException {
        rejectUnsafeEntry(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("hosted tenant entry is not a regular file");
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeInstant(DataOutputStream output, Instant value) throws IOException {
        output.writeLong(value.getEpochSecond());
        output.writeInt(value.getNano());
    }

    private static Instant readInstant(DataInputStream input) throws IOException {
        try {
            return Instant.ofEpochSecond(input.readLong(), input.readInt());
        } catch (RuntimeException exception) {
            throw new IOException("invalid hosted tenant instant", exception);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("hosted tenant string exceeds byte limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, String field) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES || length > input.available()) {
            throw new IOException("invalid hosted tenant string length for " + field);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("truncated hosted tenant string: " + field);
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("non-canonical UTF-8 hosted tenant string: " + field);
        }
        return value;
    }

    private static int boundedCount(int value, int maximum, String field) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("invalid hosted tenant " + field);
        return value;
    }

    private static ReentrantLock[] locks() {
        ReentrantLock[] locks = new ReentrantLock[JVM_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) locks[index] = new ReentrantLock();
        return locks;
    }

    private record TenantLock(ReentrantLock jvmLock, FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try { lock.close(); } catch (IOException exception) { failure = exception; }
            try { channel.close(); } catch (IOException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            } finally {
                jvmLock.unlock();
            }
            if (failure != null) throw failure;
        }
    }
}
