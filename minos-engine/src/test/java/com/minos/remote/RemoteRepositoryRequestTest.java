package com.minos.remote;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
