package com.minos.architecture;

import com.minos.application.ProjectResolver;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.Objects;

/**
 * Bootstrap local M6 : résolution projet + découverte factuelle + snapshot actif.
 */
public final class LocalProjectArchitectureQuery implements ProjectArchitectureQuery {

    private final ProjectResolver projectResolver;
    private final FileSymbolSnapshotStore snapshotStore;
    private final ProjectDiscoveryService discoveryService;
    private final ArchitectureTopologyService topologyService;
    private final ArchitectureDependencyService dependencyService;
    private final ArchitectureConcentrationService concentrationService;
    private final ArchitectureCentralityService centralityService;
    private final ArchitectureTechnologyService technologyService;
    private final ArchitectureIntelligenceService intelligenceService = new ArchitectureIntelligenceService();

    public LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                new ProjectDiscoveryService(),
                new ArchitectureTopologyService(),
                new ArchitectureDependencyService(),
                new ArchitectureConcentrationService(),
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    /** Composition constructor used by MinosApplication to share discovery state. */
    public LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                new ArchitectureTopologyService(),
                new ArchitectureDependencyService(),
                new ArchitectureConcentrationService(),
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    /** Composition constructor used when a shared application resolver is available. */
    public LocalProjectArchitectureQuery(
            ProjectResolver projectResolver,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService
    ) {
        this(
                projectResolver,
                snapshotStore,
                discoveryService,
                new ArchitectureTopologyService(),
                new ArchitectureDependencyService(),
                new ArchitectureConcentrationService(),
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                topologyService,
                new ArchitectureDependencyService(),
                new ArchitectureConcentrationService(),
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService,
            ArchitectureDependencyService dependencyService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                topologyService,
                dependencyService,
                new ArchitectureConcentrationService(),
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService,
            ArchitectureDependencyService dependencyService,
            ArchitectureConcentrationService concentrationService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                topologyService,
                dependencyService,
                concentrationService,
                new ArchitectureCentralityService(),
                new ArchitectureTechnologyService()
        );
    }

    LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService,
            ArchitectureDependencyService dependencyService,
            ArchitectureConcentrationService concentrationService,
            ArchitectureCentralityService centralityService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                topologyService,
                dependencyService,
                concentrationService,
                centralityService,
                new ArchitectureTechnologyService()
        );
    }

    LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService,
            ArchitectureDependencyService dependencyService,
            ArchitectureConcentrationService concentrationService,
            ArchitectureCentralityService centralityService,
            ArchitectureTechnologyService technologyService
    ) {
        this(
                new ProjectResolver(projectRegistry),
                snapshotStore,
                discoveryService,
                topologyService,
                dependencyService,
                concentrationService,
                centralityService,
                technologyService
        );
    }

    private LocalProjectArchitectureQuery(
            ProjectResolver projectResolver,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService,
            ArchitectureTopologyService topologyService,
            ArchitectureDependencyService dependencyService,
            ArchitectureConcentrationService concentrationService,
            ArchitectureCentralityService centralityService,
            ArchitectureTechnologyService technologyService
    ) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
        this.topologyService = Objects.requireNonNull(topologyService, "topologyService");
        this.dependencyService = Objects.requireNonNull(dependencyService, "dependencyService");
        this.concentrationService = Objects.requireNonNull(concentrationService, "concentrationService");
        this.centralityService = Objects.requireNonNull(centralityService, "centralityService");
        this.technologyService = Objects.requireNonNull(technologyService, "technologyService");
    }

    @Override
    public ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return topologyService.build(context.discovery(), context.snapshot());
    }

    @Override
    public ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return dependencyService.build(context.discovery(), context.snapshot());
    }

    @Override
    public ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return concentration(context);
    }

    @Override
    public ArchitectureCentralityReport getArchitectureCentrality(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return centralityService.rank(concentration(context));
    }

    @Override
    public ArchitectureTechnologyReport getArchitectureTechnologies(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        ArchitectureOverview overview = topologyService.build(context.discovery(), context.snapshot());
        return technologyService.detect(context.discovery(), overview);
    }

    @Override
    public ArchitectureIntelligenceView getArchitectureIntelligence(String projectIdentifier) throws IOException {
        return intelligence(loadContext(projectIdentifier));
    }

    @Override
    public ArchitectureModuleContext getModuleContext(
            String projectIdentifier,
            String moduleIdentifier
    ) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return intelligenceService.moduleContext(intelligence(context), moduleIdentifier);
    }

    private ArchitectureIntelligenceView intelligence(ProjectContext context) {
        ArchitectureOverview overview = topologyService.build(context.discovery(), context.snapshot());
        ArchitectureDependencyGraph dependencies = dependencyService.build(context.discovery(), context.snapshot());
        ArchitectureConcentrationReport concentration = concentrationService.analyze(overview, dependencies);
        ArchitectureCentralityReport centrality = centralityService.rank(concentration);
        ArchitectureTechnologyReport technologies = technologyService.detect(context.discovery(), overview);
        return intelligenceService.compose(overview, dependencies, concentration, centrality, technologies);
    }

    private ArchitectureConcentrationReport concentration(ProjectContext context) {
        ArchitectureOverview overview = topologyService.build(context.discovery(), context.snapshot());
        ArchitectureDependencyGraph graph = dependencyService.build(context.discovery(), context.snapshot());
        return concentrationService.analyze(overview, graph);
    }

    private ProjectContext loadContext(String projectIdentifier) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active code knowledge snapshot: " + project.id()
                ));
        ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
        return new ProjectContext(discovery, snapshot);
    }

    private record ProjectContext(ProjectDiscovery discovery, CodeKnowledgeSnapshot snapshot) {
        private ProjectContext {
            Objects.requireNonNull(discovery, "discovery");
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
