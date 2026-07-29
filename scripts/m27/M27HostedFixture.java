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

/** Creates one authoritative active snapshot for the cross-platform M27 hosted e2e. */
public final class M27HostedFixture {
    private M27HostedFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: M27HostedFixture <home> <project-root>");
        Path home = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Service.java"), "class Service {}\n");
        RegisteredProject project = new LocalProjectRegistry(home.resolve("registry"))
                .registerProject(root, "m27-hosted-e2e");
        Origin origin = new Origin("m27-fixture", "TEST", "1", "m27-e2e", OriginType.OTHER);
        Symbol symbol = new Symbol("service", "key:service", SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(), "main", "src/Service.java", null, SymbolKind.CLASS, "Service",
                "com.acme.Service", null, "java",
                new SymbolLocation("src/Service.java", 1, 0, 1, 16, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, origin, false, false, Set.of());
        new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
                .publish(project.id(), "snapshot-m27-e2e", List.of(symbol));
        System.out.println(project.id());
    }
}
