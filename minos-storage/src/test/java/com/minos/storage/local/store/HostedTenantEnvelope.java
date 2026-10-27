package com.minos.storage.local.store;

import com.minos.hosted.HostedTenantKeyProvider;

import javax.crypto.Cipher;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Independent reader and writer of the on-disk hosted tenant envelope, used to forge files that the store
 * must refuse. It mirrors the documented layout: magic, version, tenant id, key id, nonce, AES-GCM ciphertext
 * whose additional authenticated data binds the format version, the tenant id and the key id.
 */
final class HostedTenantEnvelope {
    static final int MAGIC = 0x4D485431;
    static final int VERSION = 1;

    final int magic;
    final int version;
    final UUID tenantId;
    final byte[] keyId;
    final byte[] nonce;
    final byte[] ciphertext;

    private HostedTenantEnvelope(int magic, int version, UUID tenantId, byte[] keyId, byte[] nonce, byte[] ciphertext) {
        this.magic = magic;
        this.version = version;
        this.tenantId = tenantId;
        this.keyId = keyId;
        this.nonce = nonce;
        this.ciphertext = ciphertext;
    }

    static HostedTenantEnvelope parse(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int magic = input.readInt();
            int version = input.readInt();
            UUID tenant = new UUID(input.readLong(), input.readLong());
            byte[] keyId = input.readNBytes(input.readInt());
            byte[] nonce = input.readNBytes(input.readInt());
            byte[] ciphertext = input.readNBytes(input.readInt());
            return new HostedTenantEnvelope(magic, version, tenant, keyId, nonce, ciphertext);
        }
    }

    /** Envelope that authenticates {@code plaintext} for {@code tenantId} and {@code keyId}. */
    static HostedTenantEnvelope seal(HostedTenantKeyProvider keys, UUID tenantId, String keyId, byte[] plaintext)
            throws GeneralSecurityException {
        byte[] nonce = new byte[12];
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, keys, tenantId, keyId, nonce);
        return new HostedTenantEnvelope(MAGIC, VERSION, tenantId, keyId.getBytes(StandardCharsets.UTF_8), nonce,
                cipher.doFinal(plaintext));
    }

    byte[] open(HostedTenantKeyProvider keys) throws GeneralSecurityException {
        String key = new String(keyId, StandardCharsets.UTF_8);
        return cipher(Cipher.DECRYPT_MODE, keys, tenantId, key, nonce).doFinal(ciphertext);
    }

    HostedTenantEnvelope withHeader(int newMagic, int newVersion) {
        return new HostedTenantEnvelope(newMagic, newVersion, tenantId, keyId, nonce, ciphertext);
    }

    HostedTenantEnvelope withKeyId(byte[] newKeyId) {
        return new HostedTenantEnvelope(magic, version, tenantId, newKeyId, nonce, ciphertext);
    }

    byte[] encode() throws IOException {
        return encode(keyId.length, nonce.length, ciphertext.length);
    }

    /** Encodes the envelope with the given declared lengths, which may disagree with the actual content. */
    byte[] encode(int keyIdLength, int nonceLength, int ciphertextLength) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(magic);
            output.writeInt(version);
            output.writeLong(tenantId.getMostSignificantBits());
            output.writeLong(tenantId.getLeastSignificantBits());
            output.writeInt(keyIdLength);
            output.write(keyId);
            output.writeInt(nonceLength);
            output.write(nonce);
            output.writeInt(ciphertextLength);
            output.write(ciphertext);
        }
        return buffer.toByteArray();
    }

    /** Offset of the nonce length field, right after the key id. */
    int nonceLengthOffset() {
        return 4 + 4 + 16 + 4 + keyId.length;
    }

    private static Cipher cipher(int mode, HostedTenantKeyProvider keys, UUID tenantId, String keyId, byte[] nonce)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, keys.resolve(tenantId, keyId, HostedTenantKeyProvider.Purpose.ENCRYPTION),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("MHT1\0" + VERSION + "\0" + tenantId + "\0" + keyId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
