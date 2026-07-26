package com.minos.architecture;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bootstrap local M6 : registre projet + découverte factuelle + snapshot actif.
 */
public final class LocalProjectArchitectureQuery implements ProjectArchitectureQuery {

    private final LocalProjectRegistry projectRegistry;
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
        this(projectRegistry, snapshotStore, new ProjectDiscoveryService());
    }

    /** Composition constructor used by MinosApplication to share discovery state. */
    public LocalProjectArchitectureQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ProjectDiscoveryService discoveryService
    ) {
        this(
                projectRegistry,
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
                projectRegistry,
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
                projectRegistry,
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
            ArchitectureConcentrationService concentrationService,
            ArchitectureCentralityService centralityService,
            ArchitectureTechnologyService technologyService
    ) {
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
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
        return load(projectIdentifier).overview();
    }

    @Override
    public ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException {
        return load(projectIdentifier).dependencies();
    }

    @Override
    public ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier) throws IOException {
        return load(projectIdentifier).concentration();
    }

    @Override
    public ArchitectureCentralityReport getArchitectureCentrality(String projectIdentifier) throws IOException {
        return load(projectIdentifier).centrality();
    }

    @Override
    public ArchitectureTechnologyReport getArchitectureTechnologies(String projectIdentifier) throws IOException {
        return load(projectIdentifier).technologies();
    }

    @Override
    public ArchitectureIntelligenceView getArchitectureIntelligence(String projectIdentifier) throws IOException {
        LoadedArchitecture loaded = load(projectIdentifier);
        return intelligenceService.compose(
                loaded.overview(),
                loaded.dependencies(),
                loaded.concentration(),
                loaded.centrality(),
                loaded.technologies()
        );
    }

    @Override
    public ArchitectureModuleContext getModuleContext(String projectIdentifier, String moduleIdentifier)
            throws IOException {
        LoadedArchitecture loaded = load(projectIdentifier);
        return intelligenceService.moduleContext(
                loaded.overview(),
                loaded.dependencies(),
                loaded.concentration(),
                loaded.centrality(),
                loaded.technologies(),
                moduleIdentifier
        );
    }

    private LoadedArchitecture load(String projectIdentifier) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active code knowledge snapshot: " + project.id()));
        ArchitectureOverview overview = topologyService.analyze(project, discovery, snapshot);
        ArchitectureDependencyGraph dependencies = dependencyService.analyze(overview, snapshot);
        ArchitectureConcentrationReport concentration = concentrationService.analyze(overview, dependencies);
        ArchitectureCentralityReport centrality = centralityService.analyze(overview, dependencies);
        ArchitectureTechnologyReport technologies = technologyService.analyze(overview);
        return new LoadedArchitecture(overview, dependencies, concentration, centrality, technologies);
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("project identifier must not be blank");
        }
        UUID projectId = parseUuid(identifier);
        if (projectId != null) {
            return projectRegistry.findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        }
        List<RegisteredProject> matches = projectRegistry.listProjects().stream()
                .filter(project -> identifier.equals(project.displayName()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown project: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("ambiguous project name, use its UUID: " + identifier);
        }
        return matches.getFirst();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record LoadedArchitecture(
            ArchitectureOverview overview,
            ArchitectureDependencyGraph dependencies,
            ArchitectureConcentrationReport concentration,
            ArchitectureCentralityReport centrality,
            ArchitectureTechnologyReport technologies
    ) {
    }
}
