package com.minos.discovery.spi;

import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectIgnorePolicy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Detects source/test roots for one module without central language branching. */
@FunctionalInterface
public interface SourceRootDetector {
    List<SourceRoot> detect(Path projectRoot, Path moduleRoot, ProjectIgnorePolicy ignorePolicy) throws IOException;
}
