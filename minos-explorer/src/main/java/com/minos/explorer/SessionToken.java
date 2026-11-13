package com.minos.explorer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The random secret of one exploration session: 256 bits drawn when the surface starts, never stored, never
 * logged. It lives as long as the process and is compared in constant time.
 */
final class SessionToken {

    private static final int BYTES = 32;
    private static final String BEARER = "Bearer ";

    private final String value;
    private final byte[] bytes;

    private SessionToken(String value) {
        this.value = value;
        this.bytes = value.getBytes(StandardCharsets.UTF_8);
    }

    /** A new token from the platform's secure random source. */
    static SessionToken generate() {
        byte[] random = new byte[BYTES];
        new SecureRandom().nextBytes(random);
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
        return MessageDigest.isEqual(bytes, presented);
    }

    @Override
    public String toString() {
        return "SessionToken[redacted]";
    }
}
