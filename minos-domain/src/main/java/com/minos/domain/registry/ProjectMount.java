package com.minos.domain.registry;

/**
 * One read-only mount of a project folder of the host under the container project root: {@code path} is relative to
 * that root (one or more safe segments), {@code hostRoot} the absolute host folder mounted there.
 */
public record ProjectMount(String path, String hostRoot) {

    public ProjectMount {
        // Compose interpolates '$' in a mount source or target, and a control character has no place in a path.
        if (unsafeCharacter(path) || unsafeCharacter(hostRoot)) {
            throw new IllegalArgumentException("a project mount path holds a dollar sign or a control character");
        }
        path = ProjectPathMapping.normalizeRelative(path);
        if (".".equals(path)) throw new IllegalArgumentException("a project mount cannot be the container root");
        try {
            hostRoot = ProjectPathMapping.normalizeAbsoluteRoot(hostRoot, "mount hostRoot");
        } catch (IllegalArgumentException exception) {
            // The folder is the user's: the message names the rule, never the value.
            throw new IllegalArgumentException("a project mount folder must be an absolute host path", exception);
        }
        for (String segment : hostRoot.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("a project mount folder must not hold a . or .. segment");
            }
        }
    }

    private static boolean unsafeCharacter(String value) {
        if (value == null) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '$' || Character.isISOControl(character)) return true;
        }
        return false;
    }
}
