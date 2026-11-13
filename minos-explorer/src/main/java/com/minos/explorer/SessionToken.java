package com.minos.explorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The random secret of one exploration session: 256 bits drawn when the surface starts and never stored by
 * MINOS. It is printed once, inside the access address the command writes on standard error, and appears in
 * no other line; it lives as long as the process and is compared in constant time.
 */
final class SessionToken {

    private static final int TOKEN_LENGTH_BYTES = 32;
    private static final String BEARER = "Bearer ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String value;
    private final byte[] encoded;

    private SessionToken(String value) {
        this.value = value;
        this.encoded = value.getBytes(StandardCharsets.UTF_8);
    }

    /** A new token from the platform's secure random source. */
    static SessionToken generate() {
        byte[] random = new byte[TOKEN_LENGTH_BYTES];
        RANDOM.nextBytes(random);
        return new SessionToken(HexFormat.of().formatHex(random));
    }

    /** The token as the browser sends it back; handed out once, in the fragment of the access URL. */
    String value() {
        return value;
    }

    /**
     * Whether {@code authorization} is the {@code Authorization} header of this session. The comparison does
     * not stop at the first differing byte: its duration tells nothing about the token. The length of a token
     * is public, so a value of another length is told apart without harm.
     */
    boolean matches(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER)) return false;
        byte[] presented = authorization.substring(BEARER.length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(encoded, presented);
    }

    @Override
    public String toString() {
        return "SessionToken[redacted]";
    }
}
