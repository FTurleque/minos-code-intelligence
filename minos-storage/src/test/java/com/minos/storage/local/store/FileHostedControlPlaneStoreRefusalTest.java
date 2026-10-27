package com.minos.storage.local.store;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedProjectBinding;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import com.minos.hosted.SharedWorkspace;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Refusals of the encrypted hosted control-plane store: construction limits, version discipline, and every
 * malformed envelope or authenticated payload a tenant file may contain. Nothing refused is ever returned.
 */
class FileHostedControlPlaneStoreRefusalTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");
    private static final HostedTenantKeyProvider KEYS = DerivedTenantKeys.provider();
    private static final String NAME = "Tenant";
    private static final String KEY_ID = "primary";
    /** Plaintext offsets for a state named {@link #NAME} with key id {@link #KEY_ID}. */
    private static final int NAME_LENGTH_OFFSET = 16;
    private static final int CREATED_AT_OFFSET = 16 + 4 + NAME.length() + 4 + KEY_ID.length() + 8;
    private static final int MEMBER_COUNT_OFFSET = CREATED_AT_OFFSET + 12 + 12 + 12;

    // ---- construction ----

    @Test
    void refusesATenantByteLimitOutsideItsBounds(@TempDir Path root) throws Exception {
        assertLimitRefused(root, 1023L);
        assertLimitRefused(root, Integer.MAX_VALUE + 1L);
        FileHostedControlPlaneStore smallest = new FileHostedControlPlaneStore(root, KEYS, 1024L, new SecureRandom());
        assertEquals(OptionalLong.of(1024L), smallest.tenantByteLimit());
    }

    @Test
    void refusesMissingCollaborators(@TempDir Path root) {
        SecureRandom random = new SecureRandom();
        NullPointerException timeout = assertThrows(NullPointerException.class,
                () -> new FileHostedControlPlaneStore(root, KEYS, 1024L, random, null));
        assertEquals("lockTimeout", timeout.getMessage());
        NullPointerException keys = assertThrows(NullPointerException.class,
                () -> new FileHostedControlPlaneStore(root, null));
        assertEquals("keys", keys.getMessage());
        NullPointerException noRandom = assertThrows(NullPointerException.class,
                () -> new FileHostedControlPlaneStore(root, KEYS, 1024L, null));
        assertEquals("random", noRandom.getMessage());
    }

    @Test
    void normalizesItsRootAndCreatesIt(@TempDir Path temp) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(
                temp.resolve("first").resolve("..").resolve("control-plane"), KEYS);

        assertEquals(temp.resolve("control-plane").toAbsolutePath().normalize(), store.root());
        assertTrue(Files.isDirectory(store.root()));
        assertEquals(OptionalLong.of(FileHostedControlPlaneStore.DEFAULT_MAX_TENANT_BYTES), store.tenantByteLimit());
        assertFalse(Files.exists(temp.resolve("first")), "normalization must not create the discarded segment");
    }

    @Test
    void refusesARootThatIsARegularFile(@TempDir Path temp) throws Exception {
        Path occupied = Files.writeString(temp.resolve("control-plane"), "occupied");

        assertThrows(IOException.class, () -> new FileHostedControlPlaneStore(occupied, KEYS));

        assertEquals("occupied", Files.readString(occupied));
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void refusesASymbolicLinkRoot(@TempDir Path temp) throws Exception {
        Path target = Files.createDirectories(temp.resolve("target"));
        Path link = Files.createSymbolicLink(temp.resolve("control-plane"), target);

        IOException failure = assertThrows(IOException.class, () -> new FileHostedControlPlaneStore(link, KEYS));

        assertEquals("hosted control-plane root must not be a symbolic link", failure.getMessage());
        try (Stream<Path> entries = Files.list(target)) {
            assertEquals(0L, entries.count(), "nothing may be written through the link");
        }
    }

    // ---- create and save discipline ----

    @Test
    void createRefusesANonZeroVersionAndAnExistingTenant(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        HostedTenantState versioned = state(tenant, NAME, 1);

        IllegalArgumentException version = assertThrows(IllegalArgumentException.class, () -> store.create(versioned));
        assertEquals("new tenant state version must be zero", version.getMessage());
        assertFalse(Files.exists(tenantFile(root, tenant)), "a refused creation must not publish a tenant file");

        HostedTenantState original = state(tenant, NAME, 0);
        store.create(original);
        HostedTenantState duplicate = state(tenant, "Usurper", 0);
        IOException exists = assertThrows(IOException.class, () -> store.create(duplicate));
        assertEquals("hosted tenant already exists: " + tenant, exists.getMessage());
        assertEquals(original, store.find(tenant).orElseThrow(), "the existing tenant must be left untouched");
    }

    @Test
    void saveRefusesAVersionThatIsNotTheExpectedSuccessor(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        store.create(state(tenant, NAME, 0));
        HostedTenantState next = state(tenant, "Next", 1);
        HostedTenantState skipping = state(tenant, "Skipping", 2);

        assertSaveRefused(store, next, -1L);
        assertSaveRefused(store, skipping, 0L);
        assertEquals(0L, store.find(tenant).orElseThrow().version());
    }

    @Test
    void saveRefusesAnUnknownTenantWithoutCreatingIt(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        HostedTenantState state = state(tenant, NAME, 1);

        IOException failure = assertThrows(IOException.class, () -> store.save(state, 0L));

        assertEquals("hosted tenant does not exist: " + tenant, failure.getMessage());
        assertFalse(Files.exists(tenantFile(root, tenant)));
        assertTrue(store.find(tenant).isEmpty());
    }

    @Test
    void refusesAnEnvelopeLargerThanTheTenantLimitBeforeWritingAnything(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS, 1024L, new SecureRandom());
        UUID tenant = UUID.randomUUID();
        List<HostedPrincipal> members = new ArrayList<>();
        members.add(new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW));
        for (int index = 0; index < 8; index++) {
            members.add(new HostedPrincipal("member-" + index, "d".repeat(200), HostedRole.VIEWER, NOW));
        }
        HostedTenantState large = new HostedTenantState(tenant, NAME, KEY_ID, 0, NOW, NOW,
                HostedRetentionPolicy.defaults(), members, List.of(), 0, HostedAuditEvent.GENESIS_HASH, List.of());

        IOException failure = assertThrows(IOException.class, () -> store.create(large));

        assertEquals("encoded hosted tenant exceeds byte limit", failure.getMessage());
        assertNoTenantNorTemporaryFile(root);
    }

    @Test
    void encryptionFailureLeavesNoTenantFile(@TempDir Path root) throws Exception {
        HostedTenantKeyProvider unavailable = (tenant, keyId, purpose) -> {
            throw new IllegalStateException("missing hosted tenant key environment variable: MINOS_TEAM_KEY_PRIMARY");
        };
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, unavailable);
        HostedTenantState state = state(UUID.randomUUID(), NAME, 0);

        IOException failure = assertThrows(IOException.class, () -> store.create(state));

        assertEquals("hosted tenant cryptographic operation failed", failure.getMessage());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertNoTenantNorTemporaryFile(root);
    }

    @Test
    void readWithAnUnavailableKeyFailsClosedWithoutTheTagMismatchLabel(@TempDir Path root) throws Exception {
        UUID tenant = UUID.randomUUID();
        new FileHostedControlPlaneStore(root, KEYS).create(state(tenant, NAME, 0));
        FileHostedControlPlaneStore keyless = new FileHostedControlPlaneStore(root, (id, keyId, purpose) -> {
            throw new IllegalStateException("hosted tenant key is not valid base64: MINOS_TEAM_KEY_PRIMARY");
        });

        IOException failure = assertThrows(IOException.class, () -> keyless.find(tenant));

        assertEquals("hosted tenant cryptographic operation failed", failure.getMessage());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    // ---- round trip of the optional structures ----

    @Test
    void roundTripsWorkspacesBindingsAndArchivedWorkspaces(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        SharedWorkspace active = new SharedWorkspace(UUID.randomUUID(), tenant, "Platform",
                SharedWorkspace.Status.ACTIVE, NOW, NOW.plusSeconds(5), null, List.of(
                new HostedProjectBinding(UUID.randomUUID(), "snap-1", NOW.plusSeconds(1), "owner"),
                new HostedProjectBinding(UUID.randomUUID(), "snap-é", NOW.plusSeconds(2), "owner")));
        SharedWorkspace archived = new SharedWorkspace(UUID.randomUUID(), tenant, "Legacy",
                SharedWorkspace.Status.ARCHIVED, NOW, NOW.plusSeconds(9), NOW.plusSeconds(9), List.of());
        HostedTenantState state = new HostedTenantState(tenant, NAME, KEY_ID, 0, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner()), List.of(active, archived), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());

        store.create(state);

        assertEquals(state, new FileHostedControlPlaneStore(root, KEYS).find(tenant).orElseThrow());
    }

    // ---- unauthenticated envelope header ----

    @Test
    void refusesATenantEntryThatIsNotARegularFile(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        Files.createDirectory(tenantFile(root, tenant));

        IOException failure = assertThrows(IOException.class, () -> store.find(tenant));

        assertEquals("hosted tenant entry is not a regular file", failure.getMessage());
    }

    @Test
    void refusesAnEmptyTenantFile(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
        UUID tenant = UUID.randomUUID();
        Files.write(tenantFile(root, tenant), new byte[0]);

        assertFindRefused(store, tenant, "hosted tenant file size is invalid");
    }

    @Test
    void refusesATenantFileLargerThanTheLimitBeforeDecoding(@TempDir Path root) throws Exception {
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS, 1024L, new SecureRandom());
        UUID tenant = UUID.randomUUID();
        Files.write(tenantFile(root, tenant), new byte[2048]);

        IOException failure = assertThrows(IOException.class, () -> store.find(tenant));

        assertFalse(failure.getMessage().contains(root.toString()), "no absolute path in the refusal");
    }

    @Test
    void refusesAnUnknownMagicOrFormatVersion(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);

        fixture.overwrite(fixture.envelope.withHeader(0x12345678, HostedTenantEnvelope.VERSION).encode());
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant magic");

        fixture.overwrite(fixture.envelope.withHeader(HostedTenantEnvelope.MAGIC, 2).encode());
        assertFindRefused(fixture.store, fixture.tenant, "unsupported hosted tenant version");
    }

    @Test
    void refusesAFileWhoseHeaderNamesAnotherTenant(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        UUID other = UUID.randomUUID();
        Files.write(tenantFile(root, other), fixture.original);

        assertFindRefused(fixture.store, other, "hosted tenant filename/header mismatch");
    }

    @Test
    void refusesAnInvalidOrMalformedKeyIdField(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        HostedTenantEnvelope envelope = fixture.envelope;

        fixture.overwrite(envelope.encode(-1, envelope.nonce.length, envelope.ciphertext.length));
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant string length for keyId");

        fixture.overwrite(envelope.encode(1 << 20, envelope.nonce.length, envelope.ciphertext.length));
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant string length for keyId");

        byte[] malformed = new byte[envelope.keyId.length];
        Arrays.fill(malformed, (byte) 0xFF);
        fixture.overwrite(envelope.withKeyId(malformed).encode());
        IOException failure = assertThrows(IOException.class, () -> fixture.store.find(fixture.tenant));
        assertInstanceOf(java.nio.charset.CharacterCodingException.class, failure);
    }

    @Test
    void refusesAnInvalidOrTruncatedNonce(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        HostedTenantEnvelope envelope = fixture.envelope;

        fixture.overwrite(envelope.encode(envelope.keyId.length, 11, envelope.ciphertext.length));
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant nonce length");

        int nonceStart = envelope.nonceLengthOffset() + 4;
        fixture.overwrite(Arrays.copyOf(fixture.original, nonceStart + 5));
        assertFindRefused(fixture.store, fixture.tenant, "truncated hosted tenant nonce");
    }

    @Test
    void refusesACiphertextLengthThatDisagreesWithTheFile(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        HostedTenantEnvelope envelope = fixture.envelope;

        fixture.overwrite(envelope.encode(envelope.keyId.length, envelope.nonce.length, 15));
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant ciphertext length");

        fixture.overwrite(envelope.encode(envelope.keyId.length, envelope.nonce.length, envelope.ciphertext.length + 1));
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant ciphertext length");

        byte[] trailing = Arrays.copyOf(fixture.original, fixture.original.length + 1);
        fixture.overwrite(trailing);
        assertFindRefused(fixture.store, fixture.tenant, "trailing or truncated hosted tenant bytes");
    }

    // ---- authenticated payload ----

    @Test
    void refusesAnAuthenticatedPayloadOfAnotherTenant(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        UUID other = UUID.randomUUID();
        // The payload of `fixture.tenant`, correctly sealed under the header of `other`.
        Files.write(tenantFile(root, other), HostedTenantEnvelope.seal(KEYS, other, KEY_ID, fixture.plaintext()).encode());

        assertFindRefused(fixture.store, other, "hosted tenant authenticated header/payload mismatch");
    }

    @Test
    void refusesTrailingOrTruncatedPlaintext(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        byte[] plaintext = fixture.plaintext();

        fixture.reseal(Arrays.copyOf(plaintext, plaintext.length + 1));
        assertFindRefused(fixture.store, fixture.tenant, "trailing hosted tenant plaintext bytes");

        fixture.reseal(Arrays.copyOf(plaintext, plaintext.length - 1));
        assertFindRefused(fixture.store, fixture.tenant, "truncated hosted tenant plaintext");
    }

    @Test
    void refusesAnOutOfRangeInstant(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        byte[] plaintext = fixture.plaintext();
        ByteBuffer.wrap(plaintext).putLong(CREATED_AT_OFFSET, Long.MAX_VALUE);

        fixture.reseal(plaintext);
        IOException failure = assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant instant");
        assertInstanceOf(RuntimeException.class, failure.getCause());
    }

    @Test
    void refusesAnOutOfRangeCountOrStringLength(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);

        byte[] negativeMembers = fixture.plaintext();
        ByteBuffer.wrap(negativeMembers).putInt(MEMBER_COUNT_OFFSET, -1);
        fixture.reseal(negativeMembers);
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant member count");

        byte[] tooManyMembers = fixture.plaintext();
        ByteBuffer.wrap(tooManyMembers).putInt(MEMBER_COUNT_OFFSET, HostedTenantState.MAX_MEMBERS + 1);
        fixture.reseal(tooManyMembers);
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant member count");

        byte[] negativeName = fixture.plaintext();
        ByteBuffer.wrap(negativeName).putInt(NAME_LENGTH_OFFSET, -1);
        fixture.reseal(negativeName);
        assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant string length for name");
    }

    @Test
    void refusesAPayloadTheDomainRejects(@TempDir Path root) throws Exception {
        Fixture fixture = Fixture.created(root);
        byte[] plaintext = fixture.plaintext();
        replaceOnce(plaintext, "OWNER", "OWNZR");

        fixture.reseal(plaintext);
        IOException failure = assertFindRefused(fixture.store, fixture.tenant, "invalid hosted tenant payload");
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
    }

    // ---- helpers ----

    /** One tenant created through the store, with its original bytes and parsed envelope. */
    private static final class Fixture {
        private final Path root;
        private final FileHostedControlPlaneStore store;
        private final UUID tenant;
        private final byte[] original;
        private final HostedTenantEnvelope envelope;

        private Fixture(Path root, FileHostedControlPlaneStore store, UUID tenant) throws IOException {
            this.root = root;
            this.store = store;
            this.tenant = tenant;
            this.original = Files.readAllBytes(tenantFile(root, tenant));
            this.envelope = HostedTenantEnvelope.parse(original);
        }

        static Fixture created(Path root) throws IOException {
            FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(root, KEYS);
            UUID tenant = UUID.randomUUID();
            store.create(state(tenant, NAME, 0));
            return new Fixture(root, store, tenant);
        }

        byte[] plaintext() throws Exception {
            return envelope.open(KEYS);
        }

        void overwrite(byte[] bytes) throws IOException {
            Files.write(tenantFile(root, tenant), bytes);
        }

        void reseal(byte[] plaintext) throws Exception {
            overwrite(HostedTenantEnvelope.seal(KEYS, tenant, KEY_ID, plaintext).encode());
        }
    }

    private static IOException assertFindRefused(FileHostedControlPlaneStore store, UUID tenant, String message) {
        IOException failure = assertThrows(IOException.class, () -> store.find(tenant));
        assertEquals(message, failure.getMessage());
        return failure;
    }

    private static void assertSaveRefused(FileHostedControlPlaneStore store, HostedTenantState state, long expected) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.save(state, expected));
        assertEquals("hosted tenant save requires version expectedVersion + 1", failure.getMessage());
    }

    private static void assertLimitRefused(Path root, long limit) {
        SecureRandom random = new SecureRandom();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new FileHostedControlPlaneStore(root, KEYS, limit, random));
        assertEquals("invalid tenant byte limit", failure.getMessage());
    }

    private static void assertNoTenantNorTemporaryFile(Path root) throws IOException {
        try (Stream<Path> entries = Files.list(root)) {
            List<String> names = entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".mht") || name.endsWith(".tmp"))
                    .toList();
            assertEquals(List.of(), names, "a refused write must leave neither a tenant nor a temporary file");
        }
    }

    private static void replaceOnce(byte[] bytes, String from, String to) {
        byte[] needle = from.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = to.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index + needle.length <= bytes.length; index++) {
            if (Arrays.equals(bytes, index, index + needle.length, needle, 0, needle.length)) {
                System.arraycopy(replacement, 0, bytes, index, replacement.length);
                return;
            }
        }
        throw new AssertionError("fixture plaintext does not contain " + from);
    }

    private static Path tenantFile(Path root, UUID tenant) {
        return root.resolve(tenant + ".mht");
    }

    private static HostedPrincipal owner() {
        return new HostedPrincipal("owner", "Owner Display", HostedRole.OWNER, NOW);
    }

    private static HostedTenantState state(UUID tenant, String name, long version) {
        return new HostedTenantState(tenant, name, KEY_ID, version, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner()), List.of(), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }
}
