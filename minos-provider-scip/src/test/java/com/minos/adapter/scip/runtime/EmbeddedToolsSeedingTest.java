package com.minos.adapter.scip.runtime;

import com.minos.io.Sha256;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * First use on an empty MINOS_HOME: the tools shipped with the installation are verified and extracted,
 * with the network forbidden throughout, idempotently and under concurrency, and an altered payload is
 * refused.
 */
class EmbeddedToolsSeedingTest {

    private static final String MODULES_ID = "scip-typescript-modules";
    private static final String MODULES_ARCHIVE = "assembled/scip-typescript-modules-0.4.0-windows-x64.zip";

    @TempDir
    Path directory;

    private Path home;
    private Path payload;
    private final List<String[]> restored = new ArrayList<>();

    @BeforeEach
    void isolate() {
        home = directory.resolve("home");
        payload = directory.resolve("install").resolve("tools");
        set("minos.embedded.tools", payload.toString());
        set("minos.tools.platform", EmbeddedToolsCatalog.PLATFORM_WINDOWS_X64);
        set(ToolsNetworkPolicy.SYSTEM_PROPERTY, "1");
    }

    @AfterEach
    void restoreProperties() {
        for (String[] saved : restored) {
            if (saved[1] == null) System.clearProperty(saved[0]);
            else System.setProperty(saved[0], saved[1]);
        }
    }

    private void set(String key, String value) {
        restored.add(new String[] {key, System.getProperty(key)});
        System.setProperty(key, value);
    }

    private static byte[] modulesZip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String name : List.of(
                    "package.json",
                    "node_modules/@sourcegraph/scip-typescript/dist/src/main.js",
                    "node_modules/.bin/scip-typescript.cmd")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(("content of " + name).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private void writePayload(byte[] archive, String declaredSha256) throws IOException {
        Files.createDirectories(payload.resolve("assembled"));
        Files.write(payload.resolve(MODULES_ARCHIVE), archive);
        Files.writeString(payload.resolve(EmbeddedToolsPayload.MANIFEST_FILE), "{\"formatVersion\":1,"
                + "\"platform\":\"windows-x64\",\"files\":[{\"id\":\"" + MODULES_ID + "\",\"kind\":\"assembled\","
                + "\"version\":\"0.4.0\",\"path\":\"" + MODULES_ARCHIVE + "\",\"sha256\":\"" + declaredSha256
                + "\",\"sizeBytes\":" + archive.length + "}]}");
    }

    private Path modulesRoot() {
        return home.resolve("tools").resolve("scip-typescript").resolve("0.4.0");
    }

    private ProviderRuntimeStatus inspect() {
        return new ManagedScipProviderRuntimeManager(home).inspect("scip-typescript");
    }

    @Test
    void anEmptyHomeIsSeededFromThePayloadWithoutAnyNetwork() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));

        ProviderRuntimeStatus status = inspect();

        assertTrue(Files.isRegularFile(modulesRoot().resolve("node_modules/@sourcegraph/scip-typescript/dist/src/main.js")));
        assertEquals("embedded-tools-manifest-sha256",
                Files.readString(modulesRoot().resolve(".minos-install-source")).trim());
        assertTrue(Files.isRegularFile(modulesRoot().resolve(".minos-integrity.sha256")));
        assertTrue(status.diagnostics().stream().anyMatch(line -> line.contains("tools origin: " + MODULES_ID + "=embedded")),
                String.valueOf(status.diagnostics()));
        assertFalse(Files.exists(home.resolve("tools").resolve(".seed-" + MODULES_ID + ".partial.zip")));
    }

    @Test
    void aSecondInspectionChangesNothing() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        inspect();
        Path marker = modulesRoot().resolve(".minos-integrity.sha256");
        Path script = modulesRoot().resolve("node_modules/@sourcegraph/scip-typescript/dist/src/main.js");
        var markerTime = Files.getLastModifiedTime(marker);
        var scriptTime = Files.getLastModifiedTime(script);

        ProviderRuntimeStatus second = inspect();

        assertEquals(markerTime, Files.getLastModifiedTime(marker));
        assertEquals(scriptTime, Files.getLastModifiedTime(script));
        assertFalse(second.state() == ProviderRuntimeStatus.State.INVALID);
    }

    @Test
    void anAlteredArchiveIsRefusedAndNothingIsExtracted() throws Exception {
        byte[] archive = modulesZip();
        String declared = Sha256.hex(archive);
        archive[archive.length / 2] ^= 0x10;
        writePayload(archive, declared);

        ProviderRuntimeStatus status = inspect();

        assertEquals(ProviderRuntimeStatus.State.INVALID, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(line -> line.contains("refused")), String.valueOf(status.diagnostics()));
        assertFalse(Files.exists(modulesRoot()), "nothing from an altered archive may be extracted");
    }

    @Test
    void aTreeAlteredAfterSeedingIsRefusedOnTheNextInspection() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        inspect();
        Files.writeString(modulesRoot().resolve("node_modules/@sourcegraph/scip-typescript/dist/src/main.js"),
                "process.exit(0) // injected");

        ProviderRuntimeStatus status = inspect();

        assertEquals(ProviderRuntimeStatus.State.INVALID, status.state());
        assertTrue(status.diagnostics().stream().anyMatch(line -> line.contains("integrity manifest")),
                String.valueOf(status.diagnostics()));
    }

    private ProviderRuntimeStatus seedThenDamage(java.util.function.Consumer<Path> damage) throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        inspect();
        damage.accept(modulesRoot());
        return inspect();
    }

    private void assertRefusedWithRepairInstruction(ProviderRuntimeStatus status) {
        assertEquals(ProviderRuntimeStatus.State.INVALID, status.state(), String.valueOf(status.diagnostics()));
        assertTrue(status.diagnostics().stream().anyMatch(line -> line.contains("reinstall the MINOS package")),
                String.valueOf(status.diagnostics()));
    }

    @Test
    void aFileRemovedFromTheSeededTreeIsRefused() throws Exception {
        assertRefusedWithRepairInstruction(seedThenDamage(root -> {
            try {
                Files.delete(root.resolve("node_modules/@sourcegraph/scip-typescript/dist/src/main.js"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }));
    }

    @Test
    void aJunctionOrLinkAddedToTheSeededTreeIsRefusedNotMistakenForReady() throws Exception {
        assertRefusedWithRepairInstruction(seedThenDamage(root -> {
            try {
                TestLinks.directoryLink(root.resolve("node_modules").resolve("injected"), directory.resolve("outside"));
            } catch (IOException | InterruptedException failure) {
                throw new IllegalStateException(failure);
            }
        }));
    }

    @Test
    void theEmbeddedMarkerRemovedFromTheSeededTreeIsRefused() throws Exception {
        assertRefusedWithRepairInstruction(seedThenDamage(root -> {
            try {
                Files.delete(root.resolve(".minos-install-source"));
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }));
    }

    @Test
    void anArchiveListedByTheManifestThatWentMissingIsRefusedWithARepairInstruction() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        Files.delete(payload.resolve(MODULES_ARCHIVE));

        ProviderRuntimeStatus status = inspect();

        assertRefusedWithRepairInstruction(status);
        assertFalse(Files.exists(modulesRoot()));
    }

    @Test
    void anArchiveReplacedByALinkIsRefusedWithARepairInstruction() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        Path file = payload.resolve(MODULES_ARCHIVE);
        Files.delete(file);
        TestLinks.directoryLink(file, directory.resolve("elsewhere"));

        assertRefusedWithRepairInstruction(inspect());
    }

    @Test
    void concurrentFirstUsesSeedOnceAndAllEndValid() throws Exception {
        byte[] archive = modulesZip();
        writePayload(archive, Sha256.hex(archive));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<ProviderRuntimeStatus>> results = new ArrayList<>();
            for (int index = 0; index < 4; index++) results.add(pool.submit(this::inspect));
            for (Future<ProviderRuntimeStatus> result : results) {
                assertFalse(result.get().state() == ProviderRuntimeStatus.State.INVALID,
                        String.valueOf(result.get().diagnostics()));
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(Files.isRegularFile(modulesRoot().resolve("node_modules/@sourcegraph/scip-typescript/dist/src/main.js")));
        assertFalse(Files.exists(modulesRoot().resolveSibling("0.4.0.partial")));
    }

    @Test
    void withoutAPayloadNothingIsCreatedUnderTools() {
        System.setProperty("minos.embedded.tools", directory.resolve("absent").toString());

        inspect();

        assertFalse(Files.exists(home.resolve("tools")));
    }
}
