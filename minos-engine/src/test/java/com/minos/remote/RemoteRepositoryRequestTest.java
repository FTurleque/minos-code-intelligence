package com.minos.remote;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteRepositoryRequestTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void canonicalizesSupportedHostsRefAndRepositorySuffixWithoutPersistingASecret() {
        RemoteRepositoryRequest github = RemoteRepositoryRequest.of(
                "https://github.com/acme/demo",
                "main",
                COMMIT.toUpperCase(),
                "fixtures/java",
                "MINOS_REMOTE_TOKEN"
        );
        RemoteRepositoryRequest gitlab = RemoteRepositoryRequest.of(
                "https://gitlab.com/acme/group/demo.git",
                "refs/tags/v1.0.0",
                COMMIT,
                null,
                null
        );

        assertEquals(RemoteRepositoryRequest.RemoteHost.GITHUB, github.host());
        assertEquals("https://github.com/acme/demo.git", github.canonicalRepositoryUri());
        assertEquals("refs/heads/main", github.reference());
        assertEquals(COMMIT, github.expectedCommit());
        assertEquals(Path.of("fixtures/java"), github.projectSubdirectory());
        assertEquals(Optional.of("MINOS_REMOTE_TOKEN"), github.credentialEnvironmentVariable());
        assertEquals(RemoteRepositoryRequest.RemoteHost.GITLAB, gitlab.host());
        assertEquals("refs/tags/v1.0.0", gitlab.reference());
        assertTrue(gitlab.projectSubdirectory().toString().isEmpty());
    }

    @Test
    void rejectsUnsafeHostsUrlsRefsPinsSubdirectoriesAndCredentialNames() {
        assertInvalid("http://github.com/acme/demo", "main", COMMIT, null, null);
        assertInvalid("https://token@github.com/acme/demo", "main", COMMIT, null, null);
        assertInvalid("https://github.com/acme/demo?token=secret", "main", COMMIT, null, null);
        assertInvalid("https://github.com/acme/demo#fragment", "main", COMMIT, null, null);
        assertInvalid("https://github.example/acme/demo", "main", COMMIT, null, null);
        assertInvalid("https://github.com/demo", "main", COMMIT, null, null);
        assertInvalid("https://github.com/acme/../demo", "main", COMMIT, null, null);
        assertInvalid("https://github.com/acme/demo", "feature..unsafe", COMMIT, null, null);
        assertInvalid("https://github.com/acme/demo", "main", "abc123", null, null);
        assertInvalid("https://github.com/acme/demo", "main", COMMIT, "../escape", null);
        assertInvalid("https://github.com/acme/demo", "main", COMMIT, null, "bad-name");
    }

    @Test
    void recordConstructorRejectsHostMismatchAndNonFetchPolicyCannotBeInvented() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new RemoteRepositoryRequest(
                RemoteRepositoryRequest.RemoteHost.GITLAB,
                new URI("https://github.com/acme/demo.git"),
                "main",
                COMMIT,
                Path.of(""),
                Optional.empty(),
                RemoteRepositoryRequest.FetchNetworkPolicy.FETCH_ONLY
        ));
    }

    @Test
    void anUnrelatedEnvironmentVariableCanNeverBeNamedAsTheCredential() {
        for (String name : new String[]{"AWS_SECRET_ACCESS_KEY", "OPENAI_API_KEY", "PATH", "MINOS_REMOTE_TOKENX", "TOKEN"}) {
            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> RemoteRepositoryRequest.of("https://github.com/acme/demo", "main", COMMIT, null, name),
                    name);
            assertTrue(refusal.getMessage().contains("MINOS_REMOTE_TOKEN"), refusal.getMessage());
            if (name.endsWith("_KEY")) {
                assertFalse(refusal.getMessage().contains(name), "the refusal must not echo an arbitrary variable name");
            }
        }
        // A host's own token variable is not another host's: GITHUB_TOKEN must not travel to gitlab.com.
        assertThrows(IllegalArgumentException.class, () -> RemoteRepositoryRequest.of(
                "https://gitlab.com/acme/demo", "main", COMMIT, null, "GITHUB_TOKEN"));
        assertEquals(Optional.of("GITHUB_TOKEN"), RemoteRepositoryRequest.of(
                "https://github.com/acme/demo", "main", COMMIT, null, "GITHUB_TOKEN").credentialEnvironmentVariable());
        assertEquals(Optional.of("MINOS_REMOTE_TOKEN_CI"), RemoteRepositoryRequest.of(
                "https://gitlab.com/acme/demo", "main", COMMIT, null, "MINOS_REMOTE_TOKEN_CI")
                .credentialEnvironmentVariable());
    }

    @Test
    void aTrailingSlashDoesNotProduceADotGitSegment() {
        for (String url : new String[]{
                "https://github.com/acme/demo/", "https://github.com/acme/demo.git/", "https://github.com/acme/demo//"}) {
            assertEquals("https://github.com/acme/demo.git",
                    RemoteRepositoryRequest.of(url, "main", COMMIT, null, null).canonicalRepositoryUri(), url);
        }
    }

    private static void assertInvalid(
            String uri,
            String ref,
            String commit,
            String subdirectory,
            String credential
    ) {
        assertThrows(IllegalArgumentException.class, () ->
                RemoteRepositoryRequest.of(uri, ref, commit, subdirectory, credential));
    }
}
