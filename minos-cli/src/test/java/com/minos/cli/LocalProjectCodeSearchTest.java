package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectCodeSearchTest {

    @Test
    void loadsRelevantRangeAndExplicitFullSourceFromRegisteredProject(@TempDir Path root)
            throws IOException {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Path source = projectRoot.resolve("src/GreetingService.java");
        Files.createDirectories(source.getParent());
        String content = String.join("\n",
                "package fixture;", "", "class GreetingService {", "}");
        Files.writeString(source, content, StandardCharsets.UTF_8);
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "fixture");
        new FileSymbolSnapshotStore(root.resolve("snapshots")).publish(
                project.id(), "m4", List.of(symbol(project)));
        LocalProjectSymbolQuery query = new LocalProjectSymbolQuery(
                new LocalProjectRegistry(root.resolve("registry")),
                new FileSymbolSnapshotStore(root.resolve("snapshots")));

        var response = query.searchCode("fixture", new CodeSearchCriteria(
                SymbolSearchCriteria.lexical("GreetingService", 5),
                0, 0, 0, 1, 512, true));
        var full = query.getSource(project.id().toString(), "src/GreetingService.java");

        assertEquals(1, response.count());
        assertTrue(response.contexts().getFirst().source().content()
                .contains("class GreetingService"));
        assertEquals(content, full.content());
        assertTrue(full.fullFile());
        assertThrows(IllegalArgumentException.class,
                () -> query.getSource("fixture", "../outside.java"));
    }

    private static Symbol symbol(RegisteredProject project) {
        SymbolLocation location = new SymbolLocation(
                "src/GreetingService.java", 3, 6, 3, 21,
                PositionEncoding.UTF16_CODE_UNITS);
        return new Symbol(
                "greeting-service", project.id() + "|java|CLASS|fixture.GreetingService",
                SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(),
                "main", location.fileId(), null, SymbolKind.CLASS,
                "GreetingService", "fixture.GreetingService", null, "java", location,
                ResolutionStatus.RESOLVED,
                new Origin("fixture", "TEST", "1", "run", OriginType.OTHER),
                false, false, Set.of());
    }
}
