package com.minos.discovery.spi;

import com.minos.discovery.ProjectIgnorePolicy;

import java.nio.file.Path;

/** Detects whether a visible directory is a logical project/module root. */
@FunctionalInterface
public interface ProjectDetector {
    boolean isModuleRoot(Path projectRoot, Path directory, ProjectIgnorePolicy ignorePolicy);
}
