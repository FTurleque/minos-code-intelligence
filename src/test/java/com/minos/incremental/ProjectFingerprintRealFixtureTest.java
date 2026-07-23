package com.minos.incremental;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectFingerprintRealFixtureTest {

    @Test
    void fingerprintsVersionedTypeScriptMultiModuleFixtureDeterministically() throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        ProjectFingerprintService service = new ProjectFingerprintService();

        ProjectFingerprint first = service.capture(fixture);
        ProjectFingerprint second = service.capture(fixture);

        assertEquals(first, second);
        assertTrue(first.fileCount() > 0);
        assertTrue(first.files().stream().anyMatch(file -> "package-lock.json".equals(file.relativePath())));
        assertTrue(first.files().stream().anyMatch(file -> file.relativePath().endsWith(".ts")));

        System.out.printf(
                "M7.1 typescript-modules fingerprints: files=%d, project=%s, build=%s%n",
                first.fileCount(),
                first.projectSha256(),
                first.buildSha256()
        );
    }
}
