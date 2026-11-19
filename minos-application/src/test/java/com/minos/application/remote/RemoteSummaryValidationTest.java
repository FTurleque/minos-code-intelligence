package com.minos.application.remote;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The surfaces render these summaries directly, so each one refuses an invalid field by naming it. */
class RemoteSummaryValidationTest {

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void artifactEvidenceWithABlankDigestIsRefusedByName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> artifact(" ", ""));

        assertEquals("artifactSha256 must not be blank", failure.getMessage());
    }

    @Test
    void artifactEvidenceAcceptsTheRootScopeButNotAMissingOne() {
        String digest = "a".repeat(64);
        assertEquals("", artifact(digest, "").projectRelativeRoot());

        NullPointerException failure = assertThrows(NullPointerException.class, () -> artifact(digest, null));
        assertEquals("projectRelativeRoot", failure.getMessage());
    }

    @Test
    void materializationWithABlankCommitIsRefusedByName() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> materialization(""));

        assertEquals("commit must not be blank", failure.getMessage());
        assertEquals(COMMIT, materialization(COMMIT).commit());
    }

    private static ArtifactEvidenceSummary artifact(String artifactSha256, String projectRelativeRoot) {
        return new ArtifactEvidenceSummary("scip-java", "0.13.1", "JAVA", "worker-one",
                "PROCESS_EPHEMERAL_WORKSPACE", "ALLOW", false, artifactSha256, "b".repeat(64), "c".repeat(64),
                false, projectRelativeRoot);
    }

    private static RemoteMaterializationSummary materialization(String commit) {
        return new RemoteMaterializationSummary("GITHUB", "https://github.com/acme/demo.git", "refs/heads/main",
                commit, "cache/repository", "cache/repository/fixtures", "c".repeat(64), true,
                "2026-07-29T00:00:00Z");
    }
}
