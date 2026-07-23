package com.minos.adapter.scip;

import com.minos.cli.LocalProjectSymbolQuery;
import com.minos.cli.MinosCli;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipRelatedTestSnapshotIntegrationTest {

    private static final String PRODUCTION =
            "scip-java maven fixture 1.0 com/acme/Widget#";
    private static final String TEST =
            "scip-java maven fixture 1.0 com/acme/WidgetTest#";

    @Test
    void reloadsRelatedTestAndExplainsItThroughProductCli(@TempDir Path root)
            throws Exception {
        String productionPath = "src/main/java/com/acme/Widget.java";
        String testPath = "src/test/java/com/acme/WidgetTest.java";
        Path indexFile = root.resolve("index.scip");
        write(indexFile, productionPath, testPath);
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "related-test-fixture");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        project.id(), "snapshot-m5", "main", "scip-java", "0.13.1",
                        "run-m5", Map.of()),
                snapshots
        );
        LocalProjectSymbolQuery reopened = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(root.resolve("registry")),
                new FileSymbolSnapshotStore(root.resolve("snapshots"))
        );
        String productionId = reopened.findSymbols(
                project.id().toString(), SymbolSearchCriteria.lexical("Widget", 10))
                .stream()
                .filter(symbol -> "Widget".equals(symbol.name()))
                .findFirst()
                .orElseThrow()
                .id();
        StringBuilder output = new StringBuilder();

        int exitCode = new MinosCli(reopened).run(
                new String[]{
                        "related-tests", project.id().toString(), productionId,
                        "--format", "json"
                },
                output,
                new StringBuilder()
        );

        assertEquals(1, report.relatedTestRelationshipCount());
        assertEquals(0, exitCode);
        assertTrue(output.toString().contains("\"kind\":\"RELATED_TEST\""));
        assertTrue(output.toString().contains("\"type\":\"DIRECT_REFERENCE\""));
        assertTrue(output.toString().contains("\"type\":\"NAMING_CONVENTION\""));
        assertTrue(output.toString().contains("\"confidence\":0.887"));
    }

    private static void write(
            Path indexFile,
            String productionPath,
            String testPath
    ) throws IOException {
        Document production = document(
                productionPath, symbol(PRODUCTION, "Widget"), occurrence(PRODUCTION, 2, true));
        Document test = Document.newBuilder(document(
                        testPath, symbol(TEST, "WidgetTest"), occurrence(TEST, 4, true)))
                .addOccurrences(occurrence(PRODUCTION, 8, false))
                .build();
        try (OutputStream output = Files.newOutputStream(indexFile)) {
            Index.newBuilder().addDocuments(production).addDocuments(test).build()
                    .writeTo(output);
        }
    }

    private static Document document(
            String path,
            SymbolInformation symbol,
            Occurrence definition
    ) {
        return Document.newBuilder()
                .setLanguage("java")
                .setRelativePath(path)
                .setPositionEncoding(
                        org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(symbol)
                .addOccurrences(definition)
                .build();
    }

    private static SymbolInformation symbol(String raw, String name) {
        return SymbolInformation.newBuilder()
                .setSymbol(raw)
                .setDisplayName(name)
                .setKind(SymbolInformation.Kind.Class)
                .build();
    }

    private static Occurrence occurrence(String raw, int line, boolean definition) {
        return Occurrence.newBuilder()
                .setSymbol(raw)
                .setSymbolRoles(definition ? SymbolRole.Definition_VALUE : 0)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(line)
                        .setStartCharacter(0)
                        .setEndCharacter(6))
                .build();
    }
}
