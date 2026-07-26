package com.minos.cli;

import com.minos.architecture.ArchitectureCentralityReport;
import com.minos.architecture.ArchitectureConcentrationReport;
import com.minos.architecture.ArchitectureDependencyGraph;
import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ArchitectureModuleContext;
import com.minos.architecture.ArchitectureOverview;
import com.minos.architecture.ArchitectureTechnologyReport;
import com.minos.architecture.LocalProjectArchitectureQuery;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.LocalProjectImpactQuery;
import com.minos.impact.ProjectImpactQuery;
import com.minos.integration.nexus.NexusExportService;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import com.minos.registry.LocalProjectRegistry;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Reusable local CLI execution surface, separated from the process/system launcher.
 *
 * <p>M15-S2 keeps MCP -> CLI temporarily for compatibility. M15-S4 will replace
 * that dependency with direct application services.</p>
 */
public final class MinosCliRunner {

    public static final String HOME_ENVIRONMENT_VARIABLE = "MINOS_HOME";
    public static final String HOME_SYSTEM_PROPERTY = "minos.home";

    private MinosCliRunner() {
    }

    public static int run(
            Path home,
            String[] arguments,
            Appendable output,
            Appendable error
    ) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (isHelp(arguments)) {
            output.append(MinosCli.usage()).append('\n');
            return FindSymbolCommand.SUCCESS;
        }

        Path normalizedHome = home.toAbsolutePath().normalize();
        NexusExportCommand nexusExportCommand = new NexusExportCommand(projectRoot ->
                new NexusExportService(registry(normalizedHome), snapshots(normalizedHome)).export(projectRoot));
        AutonomousIndexOperations autonomousOperations = new LazyAutonomousIndexOperations(normalizedHome);
        return new MinosCli(
                new LazyLocalProjectSymbolQuery(normalizedHome),
                new LazyProjectOperations(normalizedHome),
                new LazyProjectArchitectureQuery(normalizedHome),
                new LazyProjectImpactQuery(normalizedHome),
                nexusExportCommand,
                autonomousOperations,
                normalizedHome
        ).run(arguments, output, error);
    }

    public static Path resolveHome(Map<String, String> environment, Properties properties) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(properties, "properties");

        String property = properties.getProperty(HOME_SYSTEM_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property);
        }
        String environmentValue = environment.get(HOME_ENVIRONMENT_VARIABLE);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Path.of(environmentValue);
        }
        String userHome = properties.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException(
                    "neither minos.home, MINOS_HOME nor user.home defines a storage directory");
        }
        return Path.of(userHome).resolve(".minos");
    }

    private static boolean isHelp(String[] arguments) {
        return arguments.length == 1 && ("--help".equals(arguments[0]) || "-h".equals(arguments[0]));
    }

    private static LocalProjectRegistry registry(Path home) throws IOException {
        return new LocalProjectRegistry(home.resolve("registry"));
    }

    private static FileSymbolSnapshotStore snapshots(Path home) throws IOException {
        return new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
    }

    private static final class LazyLocalProjectSymbolQuery implements ProjectSymbolQuery {
        private final Path home;

        private LazyLocalProjectSymbolQuery(Path home) {
            this.home = home;
        }

        @Override
        public List<SymbolResult> findSymbols(String projectId, SymbolSearchCriteria criteria) throws Exception {
            return delegate().findSymbols(projectId, criteria);
        }

        @Override
        public List<SymbolResult> getFileSymbols(String projectId, String fileId, int limit) throws Exception {
            return delegate().getFileSymbols(projectId, fileId, limit);
        }

        @Override
        public List<UsageResult> findUsages(String projectId, String symbolId, int limit) throws Exception {
            return delegate().findUsages(projectId, symbolId, limit);
        }

        @Override
        public List<RelationshipResult> findRelationships(
                String projectId,
                RelationshipSearchCriteria criteria
        ) throws Exception {
            return delegate().findRelationships(projectId, criteria);
        }

        @Override
        public CodeSearchResponse searchCode(String projectId, CodeSearchCriteria criteria) throws Exception {
            return delegate().searchCode(projectId, criteria);
        }

        @Override
        public SourceExcerpt getSource(String projectId, String fileId) throws Exception {
            return delegate().getSource(projectId, fileId);
        }

        private LocalProjectSymbolQuery delegate() throws IOException {
            return new LocalProjectSymbolQuery(registry(home), snapshots(home));
        }
    }

    private static final class LazyProjectOperations implements ProjectOperations {
        private final Path home;

        private LazyProjectOperations(Path home) {
            this.home = home;
        }

        @Override
        public ProjectView addProject(Path rootPath, String displayName) throws Exception {
            return delegate().addProject(rootPath, displayName);
        }

        @Override
        public List<ProjectView> listProjects() throws Exception {
            return delegate().listProjects();
        }

        @Override
        public ProjectView inspectProject(String projectIdentifier) throws Exception {
            return delegate().inspectProject(projectIdentifier);
        }

        @Override
        public IndexImportResult importScip(
                String projectIdentifier,
                Path indexFile,
                String providerId,
                String providerVersion,
                String moduleId,
                String snapshotId
        ) throws Exception {
            return delegate().importScip(
                    projectIdentifier, indexFile, providerId, providerVersion, moduleId, snapshotId);
        }

        private LocalProjectOperations delegate() throws IOException {
            return new LocalProjectOperations(home);
        }
    }

    private static final class LazyProjectArchitectureQuery implements ProjectArchitectureQuery {
        private final Path home;

        private LazyProjectArchitectureQuery(Path home) {
            this.home = home;
        }

        @Override
        public ArchitectureOverview getArchitectureOverview(String projectIdentifier) throws IOException {
            return delegate().getArchitectureOverview(projectIdentifier);
        }

        @Override
        public ArchitectureDependencyGraph getModuleDependencies(String projectIdentifier) throws IOException {
            return delegate().getModuleDependencies(projectIdentifier);
        }

        @Override
        public ArchitectureConcentrationReport getArchitectureConcentration(String projectIdentifier)
                throws IOException {
            return delegate().getArchitectureConcentration(projectIdentifier);
        }

        @Override
        public ArchitectureCentralityReport getArchitectureCentrality(String projectIdentifier)
                throws IOException {
            return delegate().getArchitectureCentrality(projectIdentifier);
        }

        @Override
        public ArchitectureTechnologyReport getArchitectureTechnologies(String projectIdentifier)
                throws IOException {
            return delegate().getArchitectureTechnologies(projectIdentifier);
        }

        @Override
        public ArchitectureIntelligenceView getArchitectureIntelligence(String projectIdentifier)
                throws IOException {
            return delegate().getArchitectureIntelligence(projectIdentifier);
        }

        @Override
        public ArchitectureModuleContext getModuleContext(String projectIdentifier, String moduleIdentifier)
                throws IOException {
            return delegate().getModuleContext(projectIdentifier, moduleIdentifier);
        }

        private LocalProjectArchitectureQuery delegate() throws IOException {
            return new LocalProjectArchitectureQuery(registry(home), snapshots(home));
        }
    }

    private static final class LazyProjectImpactQuery implements ProjectImpactQuery {
        private final Path home;

        private LazyProjectImpactQuery(Path home) {
            this.home = home;
        }

        @Override
        public ImpactAnalysisReport analyzeImpact(String projectIdentifier, ImpactAnalysisRequest request)
                throws IOException {
            return new LocalProjectImpactQuery(registry(home), snapshots(home))
                    .analyzeImpact(projectIdentifier, request);
        }
    }
}
