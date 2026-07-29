package com.minos.remote;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Immutable, secret-free description of one remote source revision.
 *
 * <p>M25 intentionally supports the public GitHub and GitLab HTTPS endpoints only. A complete
 * commit id is mandatory even when a branch or tag is supplied, so a moving ref can never change
 * the indexed source silently.</p>
 */
public record RemoteRepositoryRequest(
        RemoteHost host,
        URI repositoryUri,
        String reference,
        String expectedCommit,
        Path projectSubdirectory,
        Optional<String> credentialEnvironmentVariable,
        FetchNetworkPolicy fetchNetworkPolicy
) {

    private static final Pattern SHA1 = Pattern.compile("[0-9a-fA-F]{40}");
    private static final Pattern ENVIRONMENT_VARIABLE = Pattern.compile("[A-Z_][A-Z0-9_]{0,127}");
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}");

    public RemoteRepositoryRequest {
        Objects.requireNonNull(host, "host");
        repositoryUri = canonicalUri(Objects.requireNonNull(repositoryUri, "repositoryUri"), host);
        reference = canonicalReference(reference);
        if (expectedCommit == null || !SHA1.matcher(expectedCommit).matches()) {
            throw new IllegalArgumentException("expectedCommit must be a complete 40-character Git SHA-1");
        }
        expectedCommit = expectedCommit.toLowerCase(Locale.ROOT);
        projectSubdirectory = canonicalSubdirectory(projectSubdirectory);
        credentialEnvironmentVariable = Objects.requireNonNull(
                credentialEnvironmentVariable, "credentialEnvironmentVariable");
        credentialEnvironmentVariable.ifPresent(value -> {
            if (!ENVIRONMENT_VARIABLE.matcher(value).matches()) {
                throw new IllegalArgumentException("credential environment variable has an invalid name");
            }
        });
        if (fetchNetworkPolicy != FetchNetworkPolicy.FETCH_ONLY) {
            throw new IllegalArgumentException("remote materialization requires the explicit FETCH_ONLY policy");
        }
    }

    public static RemoteRepositoryRequest of(
            String repositoryUri,
            String reference,
            String expectedCommit,
            String projectSubdirectory,
            String credentialEnvironmentVariable
    ) {
        URI parsed;
        try {
            parsed = new URI(requireText(repositoryUri, "repositoryUri"));
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("repositoryUri must be a valid URI", exception);
        }
        RemoteHost host = RemoteHost.fromHostname(parsed.getHost());
        Path subdirectory = projectSubdirectory == null || projectSubdirectory.isBlank()
                ? Path.of("") : Path.of(projectSubdirectory);
        Optional<String> credential = credentialEnvironmentVariable == null
                || credentialEnvironmentVariable.isBlank()
                ? Optional.empty() : Optional.of(credentialEnvironmentVariable);
        return new RemoteRepositoryRequest(
                host,
                parsed,
                reference,
                expectedCommit,
                subdirectory,
                credential,
                FetchNetworkPolicy.FETCH_ONLY
        );
    }

    public String canonicalRepositoryUri() {
        return repositoryUri.toASCIIString();
    }

    private static URI canonicalUri(URI uri, RemoteHost host) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("remote repositories require HTTPS");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("repositoryUri must not contain credentials");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("repositoryUri must not contain a query or fragment");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("repositoryUri must use the default HTTPS port");
        }
        String hostname = uri.getHost();
        if (hostname == null || !host.hostname().equalsIgnoreCase(hostname)) {
            throw new IllegalArgumentException("repositoryUri host does not match " + host);
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || path.equals("/")) {
            throw new IllegalArgumentException("repositoryUri must identify a repository path");
        }
        String normalizedPath = path.endsWith(".git") ? path.substring(0, path.length() - 4) : path;
        String[] segments = normalizedPath.substring(1).split("/");
        if (segments.length < 2) {
            throw new IllegalArgumentException("repositoryUri must include an owner/group and repository");
        }
        for (String segment : segments) {
            if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("repositoryUri contains an unsafe path segment");
            }
        }
        try {
            return new URI("https", null, host.hostname(), -1, normalizedPath + ".git", null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("repositoryUri cannot be canonicalized", exception);
        }
    }

    private static String canonicalReference(String value) {
        String reference = requireText(value, "reference");
        if (!reference.startsWith("refs/heads/") && !reference.startsWith("refs/tags/")) {
            reference = "refs/heads/" + reference;
        }
        if (!REFERENCE.matcher(reference).matches()
                || reference.contains("..")
                || reference.contains("//")
                || reference.contains("@{")
                || reference.endsWith("/")
                || reference.endsWith(".")
                || reference.endsWith(".lock")) {
            throw new IllegalArgumentException("reference is not a safe branch or tag name");
        }
        return reference;
    }

    private static Path canonicalSubdirectory(Path value) {
        Path path = Objects.requireNonNull(value, "projectSubdirectory");
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("projectSubdirectory must be relative");
        }
        Path normalized = path.normalize();
        if (normalized.startsWith("..")) {
            throw new IllegalArgumentException("projectSubdirectory must stay inside the repository");
        }
        return normalized;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public enum RemoteHost {
        GITHUB("github.com"),
        GITLAB("gitlab.com");

        private final String hostname;

        RemoteHost(String hostname) {
            this.hostname = hostname;
        }

        public String hostname() {
            return hostname;
        }

        public static RemoteHost fromHostname(String hostname) {
            for (RemoteHost value : values()) {
                if (value.hostname.equalsIgnoreCase(hostname)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("only github.com and gitlab.com remote repositories are supported");
        }
    }

    public enum FetchNetworkPolicy {
        FETCH_ONLY
    }
}
