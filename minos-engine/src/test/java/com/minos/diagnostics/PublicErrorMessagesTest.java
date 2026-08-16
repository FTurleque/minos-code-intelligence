package com.minos.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicErrorMessagesTest {

    @Test
    void safeMessagesPassThroughUnchanged() {
        assertEquals("project not found: demo",
                PublicErrorMessages.sanitize("project not found: demo", "fallback"));
    }

    @Test
    void blankOrMissingMessageFallsBack() {
        assertEquals("fallback", PublicErrorMessages.sanitize(null, "fallback"));
        assertEquals("fallback", PublicErrorMessages.sanitize("   ", "fallback"));
    }

    @Test
    void multiLineMessagesAreFlattenedToOneLine() {
        assertEquals("first line second line",
                PublicErrorMessages.sanitize("first line\nsecond line", "fallback"));
        assertEquals("first line  second line",
                PublicErrorMessages.sanitize("first line\r\nsecond line", "fallback"));
    }

    @Test
    void longMessagesAreBounded() {
        String huge = "x".repeat(10_000);
        String sanitized = PublicErrorMessages.sanitize(huge, "fallback");
        assertTrue(sanitized.length() <= 500, "sanitized message must be bounded, was " + sanitized.length());
    }

    @Test
    void windowsAbsolutePathIsRejected() {
        assertTrue(PublicErrorMessages.looksSensitive("failed to open C:\\Users\\secret-user\\.minos\\data"));
        assertEquals("fallback",
                PublicErrorMessages.sanitize("failed to open C:\\Users\\secret-user\\.minos\\data", "fallback"));
    }

    @Test
    void posixAbsolutePathIsRejected() {
        assertTrue(PublicErrorMessages.looksSensitive("failed to open /home/private-user/.minos/index"));
        assertEquals("fallback",
                PublicErrorMessages.sanitize("failed to open /home/private-user/.minos/index", "fallback"));
    }

    @Test
    void jdbcUrlWithCredentialsIsRejected() {
        String detail = "connection refused: jdbc:postgresql://user:password@database.example/minos";
        assertTrue(PublicErrorMessages.looksSensitive(detail));
        assertEquals("fallback", PublicErrorMessages.sanitize(detail, "fallback"));
    }

    @Test
    void httpUrlWithUserinfoCredentialsIsRejected() {
        String detail = "GET https://user:secret@example.org/private failed";
        assertTrue(PublicErrorMessages.looksSensitive(detail));
        assertEquals("fallback", PublicErrorMessages.sanitize(detail, "fallback"));
    }

    @Test
    void urlWithNonKeywordCredentialsIsStillRejected() {
        // No "password"/"secret"/etc keyword in the credential itself -- only the generic
        // scheme://user:pass@ shape should be enough to flag it.
        String detail = "GET https://alice:x7Kj9mP2Q@example.org/repo.git failed";
        assertTrue(PublicErrorMessages.looksSensitive(detail));
    }

    @Test
    void bearerTokenIsRejected() {
        assertTrue(PublicErrorMessages.looksSensitive("Authorization: Bearer abcdef123456"));
    }

    @Test
    void tokenAssignmentIsRejected() {
        assertTrue(PublicErrorMessages.looksSensitive("failed to authenticate: token=super-secret-value"));
        assertEquals("fallback",
                PublicErrorMessages.sanitize("failed to authenticate: token=super-secret-value", "fallback"));
    }

    @Test
    void ordinaryMessageMentioningAWordLikeTokenWithoutAssignmentIsNotFlagged() {
        assertFalse(PublicErrorMessages.looksSensitive("query token limit exceeded"));
    }
}
