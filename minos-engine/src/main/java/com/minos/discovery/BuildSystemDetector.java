package com.minos.discovery;

import com.minos.discovery.ProjectDiscovery.BuildSystem;

import java.nio.file.Path;
import java.util.Optional;

/** Detects one build/workspace ecosystem for a module root. */
@FunctionalInterface
public interface BuildSystemDetector {
    Optional<BuildSystem> detect(Path projectRoot, Path moduleRoot, ProjectIgnorePolicy ignorePolicy);
}
