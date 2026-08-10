package com.minos.adapter.scip.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

class LockedNpmPackageTest {

    @TempDir
    Path temporary;

    @Test
    void verifiesPackagedPythonLockIntegrity() throws Exception {
        verifyResource(
                "scip-python-package-lock.json",
                "@sourcegraph/scip-python",
                "0.6.6",
                "sha512-qoKL1Rggg0o5newAFbCFAKlS0AjWxG5MA+mC28BtgxOv0DhO4zdL8u7151FxEppDpXMVvm7+yXSjXotoVH9cMQ==");
    }

    @Test
    void verifiesPackagedTypeScriptLockIntegrity() throws Exception {
        verifyResource(
                "scip-typescript-package-lock.json",
                "@sourcegraph/scip-typescript",
                "0.4.0",
                "sha512-k+AtsrqmS41Sd5qjkZlHcmvoSQIvBOonRj4jpgp0KNFM6aqvMGpdSuPUqrUcg8ENTKjUbfaUVszgQwq3bCOvwA==");
    }

    private void verifyResource(String resource, String packageName, String version, String integrity) throws Exception {
        Path lock = temporary.resolve(resource);
        try (InputStream input = LockedNpmPackageTest.class.getResourceAsStream(resource)) {
            if (input == null) throw new AssertionError("missing test resource " + resource);
            Files.copy(input, lock);
        }
        LockedNpmPackage.verify(lock, packageName, version, integrity);
    }
}
