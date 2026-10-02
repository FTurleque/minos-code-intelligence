package com.minos.incremental;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** R1 lot 1 : empreinte de scope restreinte au sous-arbre, sous la politique d'ignore du projet (ADR 0039 §1). */
class ProjectFingerprintScopeCaptureTest {

    private final ProjectFingerprintService service = new ProjectFingerprintService();

    @Test
    void scopeCaptureCoversTheSubtreePlusAncestorDescriptorsUnderTheProjectRootIgnorePolicy(@TempDir Path root)
            throws Exception {
        Files.writeString(root.resolve(".gitignore"), "*.log\nbuild/\n");
        Files.createDirectories(root.resolve("services/mod/src"));
        Files.createDirectories(root.resolve("services/mod/build"));
        Files.createDirectories(root.resolve("other"));
        Files.writeString(root.resolve("services/mod/src/A.java"), "class A {}");
        Files.writeString(root.resolve("services/mod/pom.xml"), "<project>mod</project>");
        Files.writeString(root.resolve("services/mod/trace.log"), "ignored by the root policy");
        Files.writeString(root.resolve("services/mod/build/out.class"), "ignored directory");
        Files.writeString(root.resolve("services/pom.xml"), "<project>services</project>");
        Files.writeString(root.resolve("services/README.md"), "not a build descriptor");
        Files.writeString(root.resolve("other/B.java"), "class B {}");
        Files.writeString(root.resolve("pom.xml"), "<project>root</project>");
        Files.writeString(root.resolve("README.md"), "not a build descriptor");

        ProjectFingerprint scoped = service.captureScope(root, Path.of("services/mod"));

        assertEquals(
                List.of(".gitignore", "pom.xml", "services/mod/pom.xml", "services/mod/src/A.java", "services/pom.xml"),
                scoped.files().stream().map(FileFingerprint::relativePath).toList(),
                "subtree files plus root control files and ancestor build descriptors, relative to the project root,"
                        + " under the root ignore rules");
        assertNotEquals(service.capture(root).projectSha256(), scoped.projectSha256());
        assertEquals(service.capture(root).buildSha256().length(), scoped.buildSha256().length());
    }

    @Test
    void ancestorBuildDescriptorChangeMovesTheScopeFingerprint(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("mod/src"));
        Files.writeString(root.resolve("mod/src/A.java"), "class A {}");
        Files.writeString(root.resolve("pom.xml"), "<project>v1</project>");
        ProjectFingerprint before = service.captureScope(root, Path.of("mod"));

        Files.writeString(root.resolve("pom.xml"), "<project>v2</project>");
        ProjectFingerprint afterRootDescriptor = service.captureScope(root, Path.of("mod"));
        Files.writeString(root.resolve("README.md"), "unrelated root file");
        ProjectFingerprint afterUnrelatedRootFile = service.captureScope(root, Path.of("mod"));

        assertNotEquals(before.projectSha256(), afterRootDescriptor.projectSha256());
        assertEquals(afterRootDescriptor, afterUnrelatedRootFile);
    }

    @Test
    void emptyScopeIsTheWholeProjectAndTheCaptureIsDeterministic(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("mod"));
        Files.writeString(root.resolve("mod/A.java"), "class A {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        assertEquals(service.capture(root), service.captureScope(root, Path.of("")));
        assertEquals(service.captureScope(root, Path.of("mod")), service.captureScope(root, Path.of("mod")));
    }

    @Test
    void ancestorToolingDescriptorsBeyondTheBuildPolicyAreCovered(@TempDir Path root) throws Exception {
        // V8: tsconfig*.json, .npmrc and .mvn/** of an ancestor shape what a provider sees.
        Files.createDirectories(root.resolve("packages/app/src"));
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve("packages/app/src/index.ts"), "export const a = 1;");
        Files.writeString(root.resolve("tsconfig.base.json"), "{}");
        Files.writeString(root.resolve(".npmrc"), "strict-peer-dependencies=true");
        Files.writeString(root.resolve(".mvn/jvm.config"), "-Xmx2g");
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=x");
        Files.writeString(root.resolve("notes.txt"), "irrelevant");

        ProjectFingerprint scoped = service.captureScope(root, Path.of("packages/app"));

        assertEquals(List.of(".mvn/jvm.config", ".mvn/wrapper/maven-wrapper.properties", ".npmrc",
                        "packages/app/src/index.ts", "tsconfig.base.json"),
                scoped.files().stream().map(FileFingerprint::relativePath).toList());
    }

    @Test
    void symbolicLinkScopeIsRejectedSoTheFingerprintStaysBoundToRealSources(@TempDir Path root) throws Exception {
        // V7: a scope that is itself a link would otherwise fingerprint an empty subtree.
        Files.createDirectories(root.resolve("real/src"));
        Files.writeString(root.resolve("real/src/A.java"), "class A {}");
        Path link = root.resolve("linked");
        try {
            Files.createSymbolicLink(link, root.resolve("real"));
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links are not creatable on this host");
        }

        assertThrows(IllegalArgumentException.class, () -> service.captureScope(root, Path.of("linked")));
        assertThrows(IllegalArgumentException.class, () -> service.captureScope(root, Path.of("linked/src")));
    }

    @Test
    void scopeOutsideTheProjectOrMissingIsRejected(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");

        assertThrows(IllegalArgumentException.class, () -> service.captureScope(root, Path.of("../escape")));
        assertThrows(IllegalArgumentException.class, () -> service.captureScope(root, root.toAbsolutePath()));
        assertThrows(IllegalArgumentException.class, () -> service.captureScope(root, Path.of("missing")));
    }
}
