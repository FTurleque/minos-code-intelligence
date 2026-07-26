package com.minos.cli;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Adaptateur lazy du runtime autonome M14.
 *
 * <p>Le bootstrap CLI peut ainsi construire les commandes sans ouvrir ni créer
 * le MINOS_HOME. Le runtime concret n'est instancié qu'au premier appel qui en
 * a réellement besoin.</p>
 */
final class LazyAutonomousIndexOperations implements AutonomousIndexOperations {

    private final Path home;
    private volatile LocalAutonomousIndexOperations delegate;

    LazyAutonomousIndexOperations(Path home) {
        this.home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return delegate().plan(projectIdentifier, providerOverride, forceFull);
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull)
            throws Exception {
        return delegate().execute(projectIdentifier, providerOverride, forceFull);
    }

    @Override
    public List<ProviderView> providers() {
        try {
            return delegate().providers();
        } catch (IOException exception) {
            throw new IllegalStateException("unable to initialize autonomous index runtime", exception);
        }
    }

    @Override
    public ProviderView installProvider(String providerId) throws Exception {
        return delegate().installProvider(providerId);
    }

    private LocalAutonomousIndexOperations delegate() throws IOException {
        LocalAutonomousIndexOperations current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = delegate;
            if (current == null) {
                current = new LocalAutonomousIndexOperations(home);
                delegate = current;
            }
            return current;
        }
    }
}
