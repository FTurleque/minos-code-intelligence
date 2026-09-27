package com.minos.bootstrap;

import com.minos.runtime.CommandLocator;
import com.minos.runtime.HostCommandLocator;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Implémentation de production du port : délègue telle quelle à {@link CommandLocator} (hôte réel). */
final class LocalHostCommandLocator implements HostCommandLocator {

    @Override
    public Optional<Path> find(String command) {
        return CommandLocator.find(command);
    }

    @Override
    public List<String> invocation(Path executable, String... arguments) {
        return CommandLocator.invocation(executable, arguments);
    }
}
