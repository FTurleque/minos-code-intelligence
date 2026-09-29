package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscovery.BuildSystem;
import com.minos.discovery.ProjectDiscovery.DiscoveredModule;
import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscovery.SourceRootKind;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexerExecutionScopeResolverTest {

    private final IndexerExecutionScopeResolver resolver = new IndexerExecutionScopeResolver();

    @Test
    void routesManifestLessPolyglotProjectRootToNestedTypescriptModules() {
        ProjectDiscovery discovery = discovery();
        IndexerSelection selection = new IndexerSelection(
                Language.TYPESCRIPT,
                descriptor("scip-typescript", Language.TYPESCRIPT, Set.of(), true)
        );
        IndexerNegotiationResult negotiation = new IndexerNegotiationResult(
                List.of(selection), Set.of(), List.of()
        );

        assertEquals(
                List.of(Path.of("ui/app"), Path.of("ui/lib")),
                resolver.resolve(discovery, negotiation, selection)
        );
    }

    @Test
    void keepsQualifiedMultiModuleBuildAtRegisteredRoot() {
        ProjectDiscovery discovery = discovery();
        IndexerSelection selection = new IndexerSelection(
                Language.JAVA,
                descriptor("scip-java", Language.JAVA, Set.of(BuildSystem.MAVEN), true)
        );
        IndexerNegotiationResult negotiation = new IndexerNegotiationResult(
                List.of(selection), Set.of(), List.of()
        );

        assertEquals(List.of(Path.of("")), resolver.resolve(discovery, negotiation, selection));
    }

    private static ProjectDiscovery discovery() {
        Path root = Path.of("project").toAbsolutePath().normalize();
        return new ProjectDiscovery(
                root,
                "project",
                EnumSet.of(Language.JAVA, Language.TYPESCRIPT),
                EnumSet.of(BuildSystem.MAVEN, BuildSystem.NPM),
                List.of(
                        new DiscoveredModule(
                                Path.of(""),
                                "project",
                                EnumSet.of(BuildSystem.MAVEN),
                                List.of(new SourceRoot(
                                        Path.of("src/main/java"),
                                        SourceRootKind.SOURCE,
                                        Language.JAVA
                                ))
                        ),
                        new DiscoveredModule(
                                Path.of("ui/app"),
                                "app",
                                EnumSet.of(BuildSystem.NPM),
                                List.of(new SourceRoot(
                                        Path.of("ui/app/src"),
                                        SourceRootKind.SOURCE,
                                        Language.TYPESCRIPT
                                ))
                        ),
                        new DiscoveredModule(
                                Path.of("ui/lib"),
                                "lib",
                                EnumSet.of(BuildSystem.NPM),
                                List.of(new SourceRoot(
                                        Path.of("ui/lib/src"),
                                        SourceRootKind.SOURCE,
                                        Language.TYPESCRIPT
                                ))
                        )
                )
        );
    }

    private static IndexerDescriptor descriptor(
            String id,
            Language language,
            Set<BuildSystem> buildSystems,
            boolean multiModule
    ) {
        EnumSet<IndexerCapability> capabilities = EnumSet.of(
                IndexerCapability.SYMBOLS,
                IndexerCapability.REFERENCES
        );
        if (multiModule) {
            capabilities.add(IndexerCapability.MULTI_MODULE);
        }
        return new IndexerDescriptor(
                id,
                "1.0",
                id,
                Set.of(language),
                buildSystems,
                capabilities,
                IndexerQualification.QUALIFIED,
                100,
                List.of()
        );
    }
}
