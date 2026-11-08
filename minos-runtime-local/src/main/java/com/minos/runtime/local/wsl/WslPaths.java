package com.minos.runtime.local.wsl;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a Windows drive path to the path WSL mounts it at ({@code /mnt/<drive>/…}, the default automount root).
 *
 * <p>Only a local drive path ({@code X:\…}) is accepted: a UNC, device or relative path has no mount in the
 * distribution and is refused. Paths are handled as text so that the mapping is the same on every host.</p>
 */
final class WslPaths {

    /** Default automount root of WSL; the prerequisite probe of the route is qualified against it. */
    static final String MOUNT_ROOT = "/mnt/";

    private static final Pattern DRIVE_PATH = Pattern.compile("([A-Za-z]):[\\\\/](.*)");
    private static final Pattern UNSAFE_SEGMENT = Pattern.compile("(^|/)\\.{1,2}(/|$)");

    private WslPaths() {
    }

    /**
     * Returns the distribution path of an absolute Windows drive path.
     *
     * @throws IllegalArgumentException with a message that names no path
     */
    static String toDistribution(String windowsPath) {
        if (windowsPath == null || windowsPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("the WSL route needs a local drive path");
        }
        Matcher matcher = DRIVE_PATH.matcher(windowsPath);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("the WSL route indexes only projects on a local drive (X:\\...)");
        }
        String rest = matcher.group(2).replace('\\', '/');
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.contains("//") || UNSAFE_SEGMENT.matcher(rest).find()) {
            throw new IllegalArgumentException("the WSL route needs a normalized local drive path");
        }
        String drive = matcher.group(1).toLowerCase(Locale.ROOT);
        return rest.isEmpty() ? MOUNT_ROOT + drive : MOUNT_ROOT + drive + "/" + rest;
    }

    /** Validates an absolute, normalized distribution path. */
    static String requireDistributionPath(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) != '/' || value.indexOf('\0') >= 0
                || value.indexOf('\\') >= 0 || value.contains("//") || UNSAFE_SEGMENT.matcher(value).find()
                || value.length() > 4_096) {
            throw new IllegalArgumentException("projectRoot must be an absolute normalized distribution path");
        }
        return value;
    }

    /** Validates a project scope: empty for the root, otherwise relative with {@code '/'} separators. */
    static String requireRelativeScope(String value) {
        if (value == null || value.isEmpty() || ".".equals(value)) {
            return "";
        }
        if (value.charAt(0) == '/' || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0
                || value.contains("//") || UNSAFE_SEGMENT.matcher(value).find() || value.length() > 1_024) {
            throw new IllegalArgumentException("projectRelativeRoot must stay relative to the project root");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
