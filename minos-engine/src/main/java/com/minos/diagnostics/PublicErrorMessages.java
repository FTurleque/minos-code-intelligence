package com.minos.diagnostics;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Central place that decides whether a caught exception's message is safe to surface across a
 * public boundary -- the {@code MinosApi} Java contract, MCP tool errors, CLI diagnostics.
 *
 * <p>An internal exception message can legitimately carry an absolute filesystem path (leaking a
 * username or MINOS_HOME layout), a JDBC connection string, a bearer token, or credentials embedded
 * in a URL. All of that is fine to log internally for diagnosis, but none of it is safe to hand to
 * a caller across a published API surface. Every layer that turns an internal exception into a
 * public one should route through here rather than inventing its own ad hoc check, so the policy
 * cannot drift between the API, MCP and CLI.</p>
 */
public final class PublicErrorMessages {

    private static final int MAX_LENGTH = 500;

    /** {@code scheme://user:pass@host}, independent of whether the credential itself contains a keyword below. */
    private static final Pattern URL_CREDENTIALS = Pattern.compile("://[^/\\s@]+:[^/\\s@]+@");

    /**
     * {@code token":} / {@code token:} -- the JSON/HTTP-header shape (e.g. an OAuth error body like
     * {@code {"access_token":"eyJ..."}}), which {@code token=} alone does not cover. Deliberately
     * scoped to "token", not the bare word "key": "key" alone is far too common in this codebase's
     * own vocabulary (cache key, symbol key, map key, primary key) to trigger on safely -- API keys
     * are instead covered narrowly by {@link #API_KEY_MENTION}.
     */
    private static final Pattern TOKEN_ASSIGNMENT = Pattern.compile("token\\s*[\"']?\\s*:");

    /** {@code api key} / {@code api-key} / {@code api_key} / {@code apikey}, however punctuated. */
    private static final Pattern API_KEY_MENTION = Pattern.compile("api[-_ ]?key");

    private PublicErrorMessages() {
    }

    /**
     * Returns a single-line, length-bounded version of {@code message} safe to surface publicly,
     * or {@code fallback} if the message is missing, blank, or looks like it carries internal
     * detail. {@code fallback} itself is never checked -- callers must supply something already
     * known to be safe, such as an exception's simple class name or a fixed generic string.
     */
    public static String sanitize(String message, String fallback) {
        if (message == null || message.isBlank()) return fallback;
        String flattened = message.replace('\r', ' ').replace('\n', ' ').trim();
        if (flattened.length() > MAX_LENGTH) flattened = flattened.substring(0, MAX_LENGTH);
        return looksSensitive(flattened) ? fallback : flattened;
    }

    /** Whether {@code detail} looks like it carries a secret, credential, or private local path. */
    public static boolean looksSensitive(String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("jdbc:") || lower.contains("password") || lower.contains("secret")
                || lower.contains("bearer") || lower.contains("authorization") || lower.contains("token=")
                || lower.contains("apikey") || lower.contains("api_key") || lower.contains("private_key")
                || lower.contains("key=") || lower.contains("pwd=") || lower.contains("uid=")) {
            return true;
        }
        if (TOKEN_ASSIGNMENT.matcher(lower).find()) return true;
        if (API_KEY_MENTION.matcher(lower).find()) return true;
        if (URL_CREDENTIALS.matcher(detail).find()) return true;
        return containsAbsolutePath(detail) || containsUncPath(detail);
    }

    /**
     * A {@code /...} or {@code C:\...} path, wherever it appears in the message -- not only when it
     * opens the whole string or a whitespace-delimited word. Internal exception messages routinely
     * quote or parenthesize the offending path (e.g. {@code Cannot open '/etc/minos/secrets.json'}
     * or {@code (C:\Users\fabrice\.minos\token)}), so the character immediately before the path only
     * needs to not itself be part of an identifier -- not specifically whitespace.
     */
    private static boolean containsAbsolutePath(String detail) {
        for (int index = 0; index < detail.length(); index++) {
            if (index > 0 && isPathIdentifierChar(detail.charAt(index - 1))) continue;
            char first = detail.charAt(index);
            if (first == '/') return true;
            if (index + 2 < detail.length()
                    && Character.isLetter(first)
                    && detail.charAt(index + 1) == ':'
                    && (detail.charAt(index + 2) == '\\' || detail.charAt(index + 2) == '/')) {
                return true;
            }
        }
        return false;
    }

    /** A Windows UNC path such as {@code \\server\share\file}. */
    private static boolean containsUncPath(String detail) {
        for (int index = 0; index < detail.length() - 2; index++) {
            if (index > 0 && isPathIdentifierChar(detail.charAt(index - 1))) continue;
            if (detail.charAt(index) == '\\' && detail.charAt(index + 1) == '\\'
                    && (Character.isLetterOrDigit(detail.charAt(index + 2)))) {
                return true;
            }
        }
        return false;
    }

    /** A character that could plausibly be part of the same word/token as what follows it. */
    private static boolean isPathIdentifierChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '.' || value == '-';
    }
}
