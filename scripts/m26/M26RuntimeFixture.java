import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.FileSymbolSnapshotStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/** Deterministic static-snapshot setup used only by the cross-platform M26 CLI e2e. */
public final class M26RuntimeFixture {
    private static final String SNAPSHOT_ID = "snapshot-m26-e2e";
    private static final Origin ORIGIN = new Origin("m26-fixture", "TEST", "1", "m26-e2e", OriginType.OTHER);

    private M26RuntimeFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: M26RuntimeFixture <minos-home> <project-root>");
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Path projectRoot = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/Service.java"), "class Service {}\n");
        Files.writeString(projectRoot.resolve("src/Helper.java"), "class Helper {}\n");

        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "m26-runtime-e2e");
        List<Symbol> symbols = List.of(
                symbol(project, "service", "key:service", "com.acme.Service", "src/Service.java", 1, 20),
                symbol(project, "helper", "key:helper", "com.acme.Helper", "src/Helper.java", 1, 20),
                symbol(project, "duplicate-a", "key:duplicate-a", "com.acme.Duplicate", "src/A.java", 1, 5),
                symbol(project, "duplicate-b", "key:duplicate-b", "com.acme.Duplicate", "src/B.java", 1, 5));
        new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
                .publish(project.id(), SNAPSHOT_ID, symbols);
        System.out.println(project.id());
    }

    private static Symbol symbol(
            RegisteredProject project, String id, String key, String qualifiedName,
            String file, int startLine, int endLine
    ) {
        return new Symbol(
                id, key, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), "main", file,
                null, SymbolKind.CLASS, id, qualifiedName, null, "java",
                new SymbolLocation(file, startLine, 0, endLine, 1, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}
