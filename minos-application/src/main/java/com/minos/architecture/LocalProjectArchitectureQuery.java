package com.minos.architecture;

import com.minos.application.resolution.ProjectResolver;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.Objects;

/** Bootstrap local M6 : résolution projet + découverte factuelle + snapshot actif. */
public final class LocalProjectArchitectureQuery implements ProjectArchitectureQuery {

    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final ProjectDiscoveryService discoveryService;
    private final ArchitectureTopologyService topologyService = new ArchitectureTopologyService();
    private final ArchitectureDependencyService dependencyService = new ArchitectureDependencyService();
    private final ArchitectureConcentrationService concentrationService = new ArchitectureConcentrationService();
    private final ArchitectureCentralityService centralityService = new ArchitectureCentralityService();
    private final ArchitectureTechnologyService technologyService = new ArchitectureTechnologyService();
    private final ArchitectureIntelligenceService intelligenceService = new ArchitectureIntelligenceService();

    /**
     * Point d'entrée unique (ADR 0045) : résolution par le registre, analyseurs par défaut (sans état).
     * La découverte est celle de l'appelant ; la racine de composition passe la sienne.
     */
    public static LocalProjectArchitectureQuery defaults(ProjectRegistry projectRegistry, CodeKnowledgeSnapshotStore snapshotStore,
                                                         ProjectDiscoveryService discoveryService) {
        return new LocalProjectArchitectureQuery(new ProjectResolver(projectRegistry), snapshotStore, discoveryService);
    }

    private LocalProjectArchitectureQuery(ProjectResolver projectResolver, CodeKnowledgeSnapshotStore snapshotStore,
                                          ProjectDiscoveryService discoveryService) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.discoveryService = Objects.requireNonNull(discoveryService, "discoveryService");
    }

    @Override public ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier); return topologyService.build(context.discovery(), context.snapshot());
    }
    @Override public ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier); return dependencyService.build(context.discovery(), context.snapshot());
    }
    @Override public ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier) throws IOException {
        return concentration(loadContext(projectIdentifier));
    }
    @Override public ArchitectureCentralityReport getArchitectureCentrality(String projectIdentifier) throws IOException {
        return centralityService.rank(concentration(loadContext(projectIdentifier)));
    }
    @Override public ArchitectureTechnologyReport getArchitectureTechnologies(String projectIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier);
        return technologyService.detect(context.discovery(), topologyService.build(context.discovery(), context.snapshot()));
    }
    @Override public ArchitectureIntelligenceView getArchitectureIntelligence(String projectIdentifier) throws IOException {
        return intelligence(loadContext(projectIdentifier));
    }
    @Override public ArchitectureModuleContext getModuleContext(String projectIdentifier, String moduleIdentifier) throws IOException {
        ProjectContext context = loadContext(projectIdentifier); return intelligenceService.moduleContext(intelligence(context), moduleIdentifier);
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
                .orElseThrow(() -> new IllegalStateException("project has no active code knowledge snapshot: " + project.id()));
        return new ProjectContext(discoveryService.discover(project.rootPath()), snapshot);
    }

    private record ProjectContext(ProjectDiscovery discovery, CodeKnowledgeSnapshot snapshot) {
        private ProjectContext { Objects.requireNonNull(discovery, "discovery"); Objects.requireNonNull(snapshot, "snapshot"); }
    }
}
