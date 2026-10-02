package com.minos.io;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Sha256Test {

    @Test
    void hashesTextAsUtf8IntoLowerCaseHexadecimal() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.hex(""));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc"));
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c", Sha256.hex("é"));
        assertEquals("77710aedc74ecfa33685e33a6c7df5cc83004da1bdcef7fb280f5c2b2e97e0a5",
                Sha256.hex("日本語"));
        assertEquals("f04cdced9736a69da6103f08a4daaf8c485dd481217d218a1b4993c8c3968e13", Sha256.hex("a\u001fb"));
    }

    @Test
    void hashesBytesLikeTheTextOfTheirUtf8Form() {
        assertEquals(Sha256.hex("abc"), Sha256.hex("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("3d1f57c984978ef98a18378c8166c1cb8ede02c03eeb6aee7e2f121dfeee3e56",
                Sha256.hex(new byte[] {0, 1, 2, (byte) 255}));
    }

    @Test
    void everyCallGetsItsOwnDigestInstanceOfTheRightAlgorithm() {
        MessageDigest first = Sha256.newDigest();
        MessageDigest second = Sha256.newDigest();

        assertNotSame(first, second);
        assertEquals("SHA-256", first.getAlgorithm());
        first.update("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(Sha256.hex(""), Sha256.hex(second));
    }

    @Test
    void aDigestFedByPiecesGivesTheHashOfTheWhole() {
        MessageDigest digest = Sha256.newDigest();
        digest.update("a".getBytes(StandardCharsets.UTF_8));
        digest.update("bc".getBytes(StandardCharsets.UTF_8));

        assertEquals(Sha256.hex("abc"), Sha256.hex(digest));
    }

    @Test
    void nullInputIsRefusedByName() {
        assertEquals("value", assertThrows(NullPointerException.class, () -> Sha256.hex((String) null)).getMessage());
        assertEquals("bytes", assertThrows(NullPointerException.class, () -> Sha256.hex((byte[]) null)).getMessage());
        assertEquals("digest",
                assertThrows(NullPointerException.class, () -> Sha256.hex((MessageDigest) null)).getMessage());
    }
}
