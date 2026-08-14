package com.minos.packaging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M29DockerAdministrationContractTest {

    @Test
    void composeSeparatesReadOnlyQueryPlaneFromWritableEphemeralAdminPlane() throws Exception {
        String compose = normalizedText(repoRoot().resolve("docker/compose.mcp.prod.yaml"));
        String query = section(compose, "  minos-mcp:", "  minos-admin:");
        String admin = section(compose, "  minos-admin:", "  minos-bootstrap:");
        String bootstrap = section(compose, "  minos-bootstrap:", "  minos-tools-bootstrap:");
        String toolsBootstrap = section(compose, "  minos-tools-bootstrap:", "  minos-provider-probe:");
        String providerProbe = section(compose, "  minos-provider-probe:", "\nvolumes:\n  minos-provider-tools:");

        assertTrue(query.contains("io.minos.runtime-plane: query"));
        assertTrue(query.contains("target: /var/lib/minos\n        read_only: true"));
        assertTrue(query.contains("target: /var/lib/minos/tools\n        read_only: true"));
        assertTrue(query.contains("target: /workspace/projects\n        read_only: true"));
        assertTrue(query.contains("network_mode: none"));
        assertTrue(query.contains("MINOS_SEMANTIC_PROVIDER: \"${MINOS_SEMANTIC_PROVIDER:-disabled}\""),
                "query and admin planes must reopen the same persisted semantic-provider selection");
        assertFalse(query.contains("/run/minos-native"),
                "query-only MCP plane must not expose the provider native-extraction tmpfs");
        assertFalse(query.contains("COURSIER_CACHE:"),
                "query-only MCP plane must not expose provider runtime caches");

        assertTrue(admin.contains("io.minos.runtime-plane: admin"));
        assertTrue(admin.contains("target: /var/lib/minos"));
        assertFalse(admin.contains("target: /var/lib/minos\n        read_only: true"));
        assertTrue(admin.contains("target: /var/lib/minos/tools\n        read_only: true"));
        assertTrue(admin.contains("target: /workspace/projects\n        read_only: true"));
        assertTrue(admin.contains("com.minos.cli.MinosLauncher"));
        assertTrue(admin.contains("MINOS_SEMANTIC_PROVIDER: \"${MINOS_SEMANTIC_PROVIDER:-disabled}\""));
        assertFalse(admin.contains("network_mode: none"),
                "ephemeral indexing must be able to resolve project build dependencies; only query/MCP is networkless");
        assertTrue(admin.contains("HOME: /var/lib/minos/cache/home"));
        assertTrue(admin.contains("COURSIER_CACHE: /var/lib/minos/cache/coursier"),
                "admin/indexing must materialize standalone Coursier resources only under writable MINOS state");
        assertTrue(admin.contains("MAVEN_OPTS: -Dmaven.repo.local=/var/lib/minos/cache/maven/repository"),
                "Maven dependency state must remain under writable MINOS state instead of the project tree/rootfs");
        assertTrue(admin.contains("JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native"),
                "scip-java-generated compiler shims and native extraction must use the bounded executable tmpfs");
        assertTrue(admin.contains("DOTNET_CLI_HOME: /var/lib/minos/cache/dotnet-home"));
        assertTrue(admin.contains("NUGET_PACKAGES: /var/lib/minos/cache/nuget"));
        assertTrue(admin.contains("/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777"));
        assertTrue(admin.contains("/run/minos-native:rw,nosuid,nodev,exec,size=16m,mode=1777"),
                "admin/indexing needs only a bounded exec tmpfs for provider native extraction");

        assertTrue(bootstrap.contains("com.minos.cli.DockerRuntimeBootstrap"));
        assertTrue(bootstrap.contains("configure-project-paths"));
        assertFalse(bootstrap.contains("/run/minos-native"));
        assertFalse(bootstrap.contains("COURSIER_CACHE:"));
        assertTrue(toolsBootstrap.contains("cp -a /opt/minos/provider-tools/. /var/lib/minos/tools/"));
        assertTrue(toolsBootstrap.contains("user: \"0:0\""));
        assertFalse(toolsBootstrap.contains("chown"),
                "tools bootstrap must not require CAP_CHOWN after cap_drop: ALL");
        assertFalse(toolsBootstrap.contains("/run/minos-native"));
        assertFalse(toolsBootstrap.contains("COURSIER_CACHE:"));
        assertTrue(providerProbe.contains("user: \"10001:10001\""));
        assertTrue(providerProbe.contains("io.minos.runtime-plane: provider-probe"));
        assertTrue(providerProbe.contains("MINOS Docker offline provider probe SUCCESS"));
        assertTrue(providerProbe.contains("mvn --version"),
                "offline image probe must prove the Maven runtime required by scip-java");
        assertTrue(providerProbe.contains("scip_java_version=\"$$(scip-java --version 2>&1)\""),
                "probe must preserve scip-java diagnostics instead of hiding failures behind grep");
        assertTrue(providerProbe.contains("scip-java version 0.0.0-SNAPSHOT"),
                "standalone scip-java reports embedded build metadata rather than the Maven artifact version");
        assertFalse(providerProbe.contains("scip-java version 0.13.1"),
                "runtime probe must not claim a version string the standalone executable does not report");
        assertFalse(providerProbe.contains("cs launch org.scip-code:scip-java"),
                "offline provider probe must not require network-time Coursier resolution");
        assertTrue(providerProbe.contains("COURSIER_CACHE: /tmp/minos-coursier-cache"),
                "standalone bootstrap must have a writable ephemeral resource-materialization cache");
        assertTrue(providerProbe.contains("JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native"));
        assertTrue(providerProbe.contains("DOTNET_CLI_HOME: /tmp/minos-dotnet-home"));
        assertTrue(providerProbe.contains("NUGET_PACKAGES: /tmp/minos-nuget"));
        assertTrue(providerProbe.contains("/tmp:rw,nosuid,nodev,noexec,size=128m,mode=1777"));
        assertTrue(providerProbe.contains("/run/minos-native:rw,nosuid,nodev,exec,size=16m,mode=1777"));
        assertTrue(providerProbe.contains("scip-typescript/0.4.0"));
        assertTrue(providerProbe.contains("scip-python/0.6.6"));
        assertTrue(providerProbe.contains("scip-dotnet/0.2.14"));
        assertTrue(providerProbe.contains("scip-go/0.2.7"));
        assertTrue(providerProbe.contains("rust-analyzer 0.3.2989"));
        assertFalse(providerProbe.contains("2026-07-27|12c3381"),
                "runtime probe must verify the executable version, not provenance metadata absent from --version");

        for (String plane : new String[]{query, admin, bootstrap, toolsBootstrap, providerProbe}) {
            assertTrue(plane.contains("read_only: true"));
            assertTrue(plane.contains("cap_drop: [ALL]"));
            assertTrue(plane.contains("security_opt: [no-new-privileges:true]"));
        }
        for (String networklessPlane : new String[]{query, bootstrap, toolsBootstrap, providerProbe}) {
            assertTrue(networklessPlane.contains("network_mode: none"));
        }
        assertTrue(query.contains("MINOS_RUNTIME_LOCATION: docker"));
        assertTrue(admin.contains("MINOS_RUNTIME_LOCATION: docker"));
        assertTrue(bootstrap.contains("MINOS_RUNTIME_LOCATION: docker"));
    }

    @Test
    void packagedWorkflowBootstrapsAndProbesEveryAdvertisedProviderOffline() throws Exception {
        Path root = repoRoot();
        String workflow = normalizedText(root.resolve("docker/scripts/prod-mcp-release.ps1"));
        String dockerfile = normalizedText(root.resolve("docker/Dockerfile.mcp.release"));
        String javaPlan = normalizedText(root.resolve(
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java"));
        String polyglotRuntime = normalizedText(root.resolve(
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager.java"));

        assertTrue(workflow.contains("'minos-tools-bootstrap'"));
        assertTrue(workflow.contains("'minos-provider-probe'"));
        assertTrue(workflow.contains("'tools', 'list', '--format', 'json'"));
        assertTrue(workflow.contains("'tools', 'verify', '--all', '--format', 'json'"));
        assertTrue(workflow.contains("provider-inventory.json"));
        assertTrue(workflow.contains("provider-binary-sha256.txt"));
        assertTrue(workflow.contains("formatVersion = 5"));
        assertTrue(workflow.contains("function Resolve-SemanticProvider"));
        assertTrue(workflow.contains("Allowed: disabled, local-hash"));
        assertTrue(workflow.contains("MINOS_SEMANTIC_PROVIDER=$ResolvedSemanticProvider"));
        assertTrue(workflow.contains("semanticProvider = $ResolvedSemanticProvider"));
        assertTrue(workflow.contains("'--volumes'"));

        assertTrue(dockerfile.matches("(?s).*FROM eclipse-temurin@sha256:[0-9a-f]{64}.*"),
                "runtime base image must be pinned by OCI digest");
        assertTrue(dockerfile.matches("(?s).*FROM rust@sha256:[0-9a-f]{64} AS rust-toolchain.*"));
        assertTrue(dockerfile.matches("(?s).*FROM golang@sha256:[0-9a-f]{64} AS go-toolchain.*"));
        assertTrue(dockerfile.matches("(?s).*FROM mcr\\.microsoft\\.com/dotnet/sdk@sha256:[0-9a-f]{64} AS dotnet-toolchain.*"));
        assertTrue(dockerfile.contains("UBUNTU_APT_SNAPSHOT=20260814T000000Z"));
        assertTrue(dockerfile.contains("snapshot.ubuntu.com/ubuntu/${UBUNTU_APT_SNAPSHOT}"),
                "OS packages must resolve from one dated Ubuntu archive snapshot");
        assertTrue(dockerfile.contains("COURSIER_LAUNCHERS_COMMIT=15f36c167c30be237105f923151adaf177e7ee61"));
        assertTrue(dockerfile.contains("COURSIER_LINUX_SHA256=62b141b186e4dfdef03af64c36bdcdfeaab2df30de14950ca220e6e43e72c26b"));
        assertTrue(dockerfile.contains("SCIP_CLANG_LINUX_SHA256=06fd18c576f979a726c651594644ec4a35db4f471f2160b3f72eb89fa6001784"));
        assertTrue(dockerfile.contains("RUST_ANALYZER_LINUX_GZ_SHA256=ac4f42ddbbd040d75d847e991894776485783e28beb744b9719a660a99abe115"));
        assertTrue(dockerfile.contains("MAVEN_VERSION=3.9.16"));
        assertTrue(dockerfile.contains("MAVEN_ZIP_SHA256=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce"));
        assertTrue(dockerfile.contains("apache-maven-${MAVEN_VERSION}-bin.zip"));
        assertTrue(dockerfile.contains("${MAVEN_ZIP_SHA256}"));
        assertTrue(dockerfile.contains("sha256sum -c -"));
        assertFalse(dockerfile.contains("maven.sha512"),
                "Maven integrity must not depend on a checksum downloaded beside the payload");
        assertTrue(dockerfile.contains("mvn --version | grep -F \"Apache Maven ${MAVEN_VERSION}\""));
        assertTrue(dockerfile.contains("SCIP_TYPESCRIPT_VERSION=0.4.0"));
        assertTrue(dockerfile.contains("SCIP_JAVA_VERSION=0.13.1"));
        assertTrue(dockerfile.contains("SCIP_JAVA_STANDALONE_REPORTED_VERSION=0.0.0-SNAPSHOT"));
        assertTrue(dockerfile.contains("SCIP_PYTHON_VERSION=0.6.6"));
        assertTrue(dockerfile.contains("SCIP_CLANG_VERSION=0.4.0"));
        assertTrue(dockerfile.contains("SCIP_DOTNET_VERSION=0.2.14"));
        assertTrue(dockerfile.contains("SCIP_DOTNET_NUPKG_SHA256=e2d183fe39b9a56cb8bb2ed2d8b96828fb5434c6db084002bf8a5c6009391b52"));
        assertTrue(dockerfile.contains("v3-flatcontainer/scip-dotnet/${SCIP_DOTNET_VERSION}"));
        assertTrue(dockerfile.contains("<packageSources><clear/><add key=\"minos-pinned\""));
        assertTrue(dockerfile.contains("--configfile /tmp/minos-nuget.config --no-cache --ignore-failed-sources"));
        assertTrue(dockerfile.contains("SCIP_GO_VERSION=0.2.7"));
        assertTrue(dockerfile.contains("GOPROXY=\"https://proxy.golang.org\""));
        assertTrue(dockerfile.contains("GOSUMDB=\"sum.golang.org\""));
        assertTrue(dockerfile.contains("GONOSUMDB=\"\""));
        assertTrue(dockerfile.contains("GOPRIVATE=\"\""));
        assertTrue(dockerfile.contains("GONOPROXY=\"\""));
        assertTrue(dockerfile.contains("RUST_ANALYZER_VERSION=0.3.2989"));
        assertTrue(dockerfile.contains("RUST_ANALYZER_RELEASE=2026-07-27"));
        assertTrue(dockerfile.contains("RUST_ANALYZER_COMMIT=12c3381"));
        assertTrue(dockerfile.contains("PATH=/usr/local/bin:/usr/local/cargo/bin:"),
                "standalone rust-analyzer must win PATH resolution over the rustup proxy");
        assertTrue(dockerfile.contains("grep -F \"rust-analyzer ${RUST_ANALYZER_VERSION}\""));
        assertFalse(dockerfile.contains("grep -E \"${RUST_ANALYZER_RELEASE}|${RUST_ANALYZER_COMMIT}\""),
                "rust-analyzer --version does not expose artifact release/commit provenance");
        assertTrue(dockerfile.contains("cs launch \"org.scip-code:scip-java:${SCIP_JAVA_VERSION}\""));
        assertTrue(dockerfile.contains("grep -F \"scip-java version ${SCIP_JAVA_VERSION}\" /tmp/scip-java-coordinate.version"),
                "build must verify the exact Maven coordinate before standalone packaging");
        assertTrue(dockerfile.contains("cs bootstrap \"org.scip-code:scip-java:${SCIP_JAVA_VERSION}\""));
        assertTrue(dockerfile.contains("--standalone"));
        assertTrue(dockerfile.contains("--java-opt -Djna.tmpdir=/run/minos-native"),
                "standalone launcher must isolate JNA extraction from the noexec general temp directory");
        assertTrue(dockerfile.contains("mkdir -p /run/minos-native"));
        assertTrue(dockerfile.contains("-o /usr/local/bin/scip-java"));
        assertTrue(dockerfile.contains("grep -F \"scip-java version ${SCIP_JAVA_STANDALONE_REPORTED_VERSION}\" /tmp/scip-java-standalone.version"));
        assertTrue(dockerfile.contains("/usr/local/bin/scip-java"));
        assertTrue(dockerfile.contains("Coursier standalone bootstrap"));
        assertTrue(dockerfile.contains("\\\"reportedVersion\\\":\\\"${SCIP_JAVA_STANDALONE_REPORTED_VERSION}\\\""));
        assertTrue(dockerfile.contains("artifact provenance is Maven coordinate org.scip-code:scip-java:${SCIP_JAVA_VERSION}"));
        assertTrue(dockerfile.contains("Coursier transitive graph is not repository-lockfile-backed"),
                "provider evidence must remain capability-honest about unresolved transitive-locking limits");
        assertTrue(dockerfile.contains("produced binary hash is retained as build evidence"));
        assertTrue(dockerfile.contains("real Maven indexing executes from a writable MINOS staging tree"));
        assertFalse(dockerfile.contains("COURSIER_CACHE=/opt/minos/coursier-cache"),
                "runtime image must not depend on a packaged mutable Coursier cache for scip-java");
        assertTrue(dockerfile.contains("provider-evidence/provider-inventory.json"));
        assertTrue(dockerfile.contains("\\\"aptSnapshot\\\": \\\"${UBUNTU_APT_SNAPSHOT}\\\""));
        assertTrue(dockerfile.contains("\\\"release\\\": \\\"${RUST_ANALYZER_RELEASE}\\\""));
        assertTrue(dockerfile.contains("\\\"commit\\\": \\\"${RUST_ANALYZER_COMMIT}\\\""));
        assertTrue(dockerfile.contains("chmod -R a+rX /opt/minos/provider-tools"),
                "provider payload must remain readable/executable by uid 10001 without ownership mutation");
        assertTrue(dockerfile.contains("libicu-dev"),
                ".NET must run with ICU installed instead of silently enabling invariant globalization");
        assertFalse(dockerfile.contains("DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"),
                "S4 must not hide missing .NET runtime dependencies behind invariant globalization");

        assertTrue(javaPlan.contains("CommandLocator.find(\"scip-java\")"));
        assertTrue(javaPlan.contains("standaloneCommand"));
        assertTrue(javaPlan.contains("prepareWritableWorkspace"));
        assertTrue(javaPlan.contains("STAGING_EXCLUDED_ROOT_FILES"));
        assertTrue(javaPlan.contains("Set.of(\"mvnw\", \"mvnw.cmd\")"));
        assertTrue(javaPlan.contains("executionRoot"));
        assertTrue(javaPlan.contains("target/scip-targetroot"));
        assertTrue(polyglotRuntime.contains("output.contains(ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION)"));
        assertFalse(polyglotRuntime.contains("!output.contains(ScipIndexerCatalog.RUST_ANALYZER_SCIP_RELEASE)"));
        assertFalse(polyglotRuntime.contains("!output.contains(ScipIndexerCatalog.RUST_ANALYZER_SCIP_COMMIT)"));
        assertTrue(polyglotRuntime.contains("artifact provenance release"));
    }

    @Test
    void providerProcessPlansKeepGeneratedIndexesOutOfProjectRoots() throws Exception {
        Path root = repoRoot();
        for (String relative : new String[]{
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipTypeScriptProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipClangProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipDotnetProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipGoProcessPlanFactory.java",
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/RustAnalyzerScipProcessPlanFactory.java"
        }) {
            String source = normalizedText(root.resolve(relative));
            assertTrue(source.contains("runDirectory"));
            assertFalse(source.contains("root.resolve(\"index.scip\")"));
        }
        String javaPlan = normalizedText(root.resolve(
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java"));
        assertTrue(javaPlan.contains("prepareWritableWorkspace"));
        assertTrue(javaPlan.contains("STAGING_EXCLUDED_DIRECTORIES"));
        assertTrue(javaPlan.contains("STAGING_EXCLUDED_ROOT_FILES"));
        String rust = normalizedText(root.resolve(
                "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/RustAnalyzerScipProcessPlanFactory.java"));
        assertTrue(rust.contains("CARGO_TARGET_DIR"));
    }

    @Test
    void s3AndS4RunnersRemainExactHeadBlockingGates() throws Exception {
        Path root = repoRoot();
        String s3 = normalizedText(root.resolve("scripts/m29/run-s3.ps1"));
        String s4 = normalizedText(root.resolve("scripts/m29/run-s4.ps1"));

        assertTrue(s3.contains("M29-S3 exact-head mismatch"));
        assertTrue(s3.contains("'index', 'm29-s3-fixture'"));
        assertFalse(s3.contains("'index', 'm29-s3-fixture', '--dry-run'"));
        assertTrue(s3.contains("Invoke-McpHandshake"));

        assertTrue(s4.contains("M29-S4 exact-head mismatch"));
        assertTrue(s4.contains("'tools', 'verify', '--all'"));
        assertTrue(s4.contains("provider-inventory.json"));
        assertTrue(s4.contains("provider-binary-sha256.txt"));
        assertTrue(s4.contains("FAIL_OR_BLOCKED"));
    }

    private static String normalizedText(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String section(String content, String start, String end) {
        int from = content.indexOf(start);
        int to = content.indexOf(end, from + start.length());
        assertTrue(from >= 0, "missing section " + start);
        assertTrue(to > from, "missing following section " + end);
        return content.substring(from, to);
    }

    private static Path repoRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath().normalize();
        for (int i = 0; i < 5 && candidate != null; i++, candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("pom.xml"))
                    && Files.isRegularFile(candidate.resolve("docker/compose.mcp.prod.yaml"))) {
                return candidate;
            }
        }
        throw new IOException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
