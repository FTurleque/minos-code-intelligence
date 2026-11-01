package com.minos.domain.registry;

/**
 * One read-only mount of a project folder of the host under the container project root: {@code path} is relative to
 * that root (one or more safe segments), {@code hostRoot} the absolute host folder mounted there.
 */
public record ProjectMount(String path, String hostRoot) {

    public ProjectMount {
        path = ProjectPathMapping.normalizeRelative(path);
        if (".".equals(path)) throw new IllegalArgumentException("a project mount cannot be the container root");
        hostRoot = ProjectPathMapping.normalizeAbsoluteRoot(hostRoot, "mount hostRoot");
    }
}
