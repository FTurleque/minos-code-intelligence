package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
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
 * Point d'entrée système de la CLI locale MINOS.
 */
public final class MinosLauncher {

    public static final String HOME_ENVIRONMENT_VARIABLE = "MINOS_HOME";
    public static final String HOME_SYSTEM_PROPERTY = "minos.home";

    private MinosLauncher() {
    }

    public static void main(String[] arguments) {
        int exitCode;
        try {
            Path home = resolveHome(System.getenv(), System.getProperties());
            exitCode = run(home, arguments, System.out, System.err);
        } catch (Exception exception) {
            System.err.println("error: MINOS bootstrap failed: " + failureMessage(exception));
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        }
        System.exit(exitCode);
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

        Path normalizedHome = home.toAbsolutePath().normalize();
        return new MinosCli(new LazyLocalProjectSymbolQuery(normalizedHome))
                .run(arguments, output, error);
    }

    static Path resolveHome(Map<String, String> environment, Properties properties) {
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
                    "neither minos.home, MINOS_HOME nor user.home defines a storage directory"
            );
        }
        return Path.of(userHome).resolve(".minos");
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message.replace('\r', ' ').replace('\n', ' ');
    }

    private static final class LazyLocalProjectSymbolQuery implements ProjectSymbolQuery {

        private final Path home;

        private LazyLocalProjectSymbolQuery(Path home) {
            this.home = home;
        }

        @Override
        public List<SymbolResult> findSymbols(
                String projectId,
                SymbolSearchCriteria criteria
        ) throws Exception {
            return delegate().findSymbols(projectId, criteria);
        }

        @Override
        public List<SymbolResult> getFileSymbols(
                String projectId,
                String fileId,
                int limit
        ) throws Exception {
            return delegate().getFileSymbols(projectId, fileId, limit);
        }

        @Override
        public List<UsageResult> findUsages(
                String projectId,
                String symbolId,
                int limit
        ) throws Exception {
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
        public CodeSearchResponse searchCode(
                String projectId,
                CodeSearchCriteria criteria
        ) throws Exception {
            return delegate().searchCode(projectId, criteria);
        }

        @Override
        public SourceExcerpt getSource(String projectId, String fileId) throws Exception {
            return delegate().getSource(projectId, fileId);
        }

        private LocalProjectSymbolQuery delegate() throws IOException {
            return new LocalProjectSymbolQuery(
                    new LocalProjectRegistry(home.resolve("registry")),
                    new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
            );
        }
    }
}
