package com.minos.adapter.scip;

import com.minos.cli.LocalProjectSymbolQuery;
import com.minos.context.CodeSearchCriteria;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.SymbolResult;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.scip_code.scip.Document;
import org.scip_code.scip.Index;
import org.scip_code.scip.Occurrence;
import org.scip_code.scip.Signature;
import org.scip_code.scip.SingleLineRange;
import org.scip_code.scip.SymbolInformation;
import org.scip_code.scip.SymbolRole;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScipSymbolSnapshotImporterTest {

    private static final String CONVERT_INT =
            "scip-java maven fixture 1.0 com/minos/Converter#convert(+1).";
    private static final String CONVERT_STRING =
            "scip-java maven fixture 1.0 com/minos/Converter#convert(+2).";
    private static final String EXTERNAL_CLOCK =
            "scip-java maven jdk 24 java/time/Clock#";

    @Test
    void importsScipIntoAReopenableProviderNeutralSnapshot(@TempDir Path root)
            throws IOException {
        Path indexFile = root.resolve("index.scip");
        writeIndex(indexFile);
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "converter-fixture");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        project.id(),
                        "snapshot-scip",
                        "main",
                        "scip-java",
                        "0.13.1",
                        "run-scip",
                        Map.of("src/main/java/com/minos/Converter.java", "file-converter")
                ),
                snapshots
        );

        assertEquals(3, report.catalogSymbolCount());
        assertEquals(3, report.normalizedSymbolCount());
        assertEquals(3, report.occurrenceCount());
        assertEquals(1, report.relationshipCount());
        assertEquals(1, report.derivedRelationshipCount());
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(root.resolve("registry")),
                new FileSymbolSnapshotStore(root.resolve("snapshots"))
        );
        List<SymbolResult> overloads = query.findSymbols(
                project.id().toString(),
                new SymbolSearchCriteria(
                        "convert",
                        "com.minos.Converter.convert",
                        SymbolKind.METHOD,
                        "main",
                        10
                )
        );
        assertEquals(List.of("(int)", "(java.lang.String)"),
                overloads.stream().map(SymbolResult::signature).toList());
        SymbolResult integerOverload = overloads.stream()
                .filter(result -> "(int)".equals(result.signature()))
                .findFirst()
                .orElseThrow();
        SymbolResult stringOverload = overloads.stream()
                .filter(result -> "(java.lang.String)".equals(result.signature()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, query.findUsages(
                project.id().toString(),
                integerOverload.id(),
                10
        ).size());
        var relationships = query.findRelationships(
                project.id().toString(),
                RelationshipSearchCriteria.outgoing(
                        new CodeEntityRef(CodeEntityType.SYMBOL, integerOverload.id()),
                        Set.of(RelationshipKind.REFERENCES),
                        10
                )
        );
        assertEquals(1, relationships.size());
        assertEquals(stringOverload.id(), relationships.getFirst().target().id());
        List<SymbolResult> external = query.findSymbols(
                project.displayName(),
                SymbolSearchCriteria.qualifiedName("java.time.Clock", 10)
        );
        assertEquals(1, external.size());
        assertTrue(external.getFirst().external());
        assertNull(external.getFirst().fileId());
    }

    @Test
    void defaultsSafeScipDocumentPathsForM4SourceContext(@TempDir Path root)
            throws IOException {
        Path indexFile = root.resolve("index.scip");
        writeIndex(indexFile);
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Path source = projectRoot.resolve("src/main/java/com/minos/Converter.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, String.join("\n",
                "package com.minos;", "", "class Converter {", "", "", "", "",
                "  int convert(int value) { return value; }", "", "", "",
                "  String convert(String value) { return value; }", "", "", "",
                "  int usage = convert(1);", "}"
        ));
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "converter-source");
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));

        new ScipSymbolSnapshotImporter().importSnapshot(
                indexFile,
                new ScipSymbolSnapshotRequest(
                        project.id(), "snapshot-source", "main", "scip-java",
                        "0.13.1", "run-source", Map.of()),
                snapshots
        );
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(registry, snapshots);
        var response = query.searchCode(project.id().toString(), new CodeSearchCriteria(
                new SymbolSearchCriteria(
                        "convert", "com.minos.Converter.convert", SymbolKind.METHOD,
                        "main", 1),
                0, 0, 0, 1, 512, true));

        assertEquals("src/main/java/com/minos/Converter.java",
                response.contexts().getFirst().symbol().fileId());
        assertTrue(response.contexts().getFirst().source().content().contains("convert"));
    }

    private static void writeIndex(Path indexFile) throws IOException {
        Document document = Document.newBuilder()
                .setLanguage("java")
                .setRelativePath("src/main/java/com/minos/Converter.java")
                .setPositionEncoding(org.scip_code.scip.PositionEncoding.UTF16CodeUnitOffsetFromLineStart)
                .addSymbols(symbol(CONVERT_INT, "convert", "(int)", SymbolInformation.Kind.Method)
                        .toBuilder()
                        .addRelationships(org.scip_code.scip.Relationship.newBuilder()
                                .setSymbol(CONVERT_STRING)
                                .setIsReference(true))
                        .build())
                .addSymbols(symbol(
                        CONVERT_STRING,
                        "convert",
                        "(java.lang.String)",
                        SymbolInformation.Kind.Method
                ))
                .addOccurrences(definition(CONVERT_INT, 7))
                .addOccurrences(definition(CONVERT_STRING, 11))
                .addOccurrences(reference(CONVERT_INT, 15))
                .build();
        Index index = Index.newBuilder()
                .addDocuments(document)
                .addExternalSymbols(symbol(
                        EXTERNAL_CLOCK,
                        "Clock",
                        "class Clock",
                        SymbolInformation.Kind.Class
                ))
                .build();
        try (OutputStream output = Files.newOutputStream(indexFile)) {
            index.writeTo(output);
        }
    }

    private static SymbolInformation symbol(
            String rawSymbol,
            String displayName,
            String signature,
            SymbolInformation.Kind kind
    ) {
        return SymbolInformation.newBuilder()
                .setSymbol(rawSymbol)
                .setDisplayName(displayName)
                .setKind(kind)
                .setSignatureDocumentation(Signature.newBuilder()
                        .setLanguage("java")
                        .setText(signature))
                .build();
    }

    private static Occurrence definition(String rawSymbol, int zeroBasedLine) {
        return Occurrence.newBuilder()
                .setSymbol(rawSymbol)
                .setSymbolRoles(SymbolRole.Definition_VALUE)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(zeroBasedLine)
                        .setStartCharacter(4)
                        .setEndCharacter(11))
                .build();
    }

    private static Occurrence reference(String rawSymbol, int zeroBasedLine) {
        return Occurrence.newBuilder()
                .setSymbol(rawSymbol)
                .setSingleLineRange(SingleLineRange.newBuilder()
                        .setLine(zeroBasedLine)
                        .setStartCharacter(4)
                        .setEndCharacter(11))
                .build();
    }
}
