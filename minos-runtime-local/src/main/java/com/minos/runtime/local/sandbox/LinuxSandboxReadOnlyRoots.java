package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.process.IndexerProcessPlan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The sandbox mounts a provider plan declares, as the Linux bubblewrap backend applies them: each read-only root is
 * checked again at the last host-controlled step and bound exactly onto itself, never its parent; each masked file
 * is presented empty by binding {@code /dev/null} over it. The backend calls this after its writable binds, so no
 * writable mount can cover a read-only root and no later mount can expose a masked file again.
 */
final class LinuxSandboxReadOnlyRoots {

    private static final String DEV_NULL = "/dev/null";
    private static final String REFUSED = "the Linux sandbox refuses a read-only root declared by the provider plan: ";
    /** POSIX mode bits, written in decimal: group write (octal 020), others write (octal 002), sticky (octal 1000). */
    private static final int GROUP_WRITE = 16;
    private static final int OTHERS_WRITE = 2;
    private static final int STICKY = 512;
    private static final int ROOT_UID = 0;

    private LinuxSandboxReadOnlyRoots() {
    }

    /**
     * Appends the read-only roots, then the masked files, of {@code plan} to the bubblewrap command.
     *
     * @param writableRoots the workspace, artifact and run directories the command already binds writable
     * @param ownedByCurrentUser a directory MINOS created, whose owner is the account running the provider
     */
    static void addDeclaredMounts(
            List<String> command,
            IndexerProcessPlan plan,
            List<Path> writableRoots,
            Path minosHome,
            Path ownedByCurrentUser
    ) throws IOException {
        List<Path> guarded = minosHome == null
                ? List.copyOf(writableRoots)
                : Stream.concat(writableRoots.stream(), Stream.of(minosHome.toAbsolutePath().normalize())).toList();
        int currentUser = plan.readOnlyRoots().isEmpty() ? ROOT_UID : ownerUid(ownedByCurrentUser.toRealPath());
        for (Path root : plan.readOnlyRoots()) {
            Path real = mountableRoot(root, currentUser, guarded);
            LinuxBubblewrapWorkerSandboxBackend.addDestinationParents(command, real);
            command.add(LinuxBubblewrapWorkerSandboxBackend.RO_BIND);
            command.add(real.toString());
            command.add(real.toString());
        }
        for (Path file : plan.maskedFiles()) {
            addMaskedFile(command, file);
        }
    }

    /**
     * The real directory of one declared root, after checking it: absolute, an existing directory, reached by no
     * link, made of components that only the current user or root can write (a root-owned sticky directory such as
     * /tmp excepted), and disjoint from every guarded root.
     *
     * @throws IllegalStateException without any path when the root cannot be mounted
     */
    static Path mountableRoot(Path root, int currentUser, List<Path> guarded) throws IOException {
        if (root == null || !root.isAbsolute()) throw refused("it is not an absolute path");
        Path normalized = root.normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) throw refused("it is not an existing directory");
        Path real = normalized.toRealPath();
        if (!real.equals(normalized)) throw refused("a component of its path is a symbolic link");
        for (Path component = real; component != null; component = component.getParent()) {
            if (!isTrustedComponent(component, currentUser)) {
                throw refused("a component of its path belongs to another account or is writable by other accounts");
            }
        }
        requireDisjoint(real, guarded);
        return real;
    }

    /** Refuses a root that overlaps a guarded root, or that is or contains the home directory. */
    private static void requireDisjoint(Path real, List<Path> guarded) throws IOException {
        for (Path other : guarded) {
            if (overlaps(real, comparable(other))) {
                throw refused("it overlaps the workspace, the artifact, the run or MINOS_HOME");
            }
        }
        Optional<Path> home = userHome();
        if (home.isPresent() && comparable(home.orElseThrow()).startsWith(real)) {
            throw refused("it is, or contains, the home directory");
        }
    }

    /**
     * Presents one host file as empty inside the sandbox. A file that does not exist is invisible already and needs
     * no mask.
     */
    static void addMaskedFile(List<String> command, Path file) throws IOException {
        if (file == null || !file.isAbsolute()) {
            throw new IllegalStateException("a file the provider plan masks must be named by an absolute path");
        }
        Path normalized = file.normalize();
        if (!Files.exists(normalized)) return;
        Path real = normalized.toRealPath();
        if (!Files.isRegularFile(real)) {
            throw new IllegalStateException("a file the provider plan masks is not a regular file");
        }
        bindDevNull(command, real);
        if (!real.equals(normalized) && !Files.isSymbolicLink(normalized)) bindDevNull(command, normalized);
    }

    private static void bindDevNull(List<String> command, Path destination) {
        command.add(LinuxBubblewrapWorkerSandboxBackend.RO_BIND);
        command.add(DEV_NULL);
        command.add(destination.toString());
    }

    /** Owned by the current user or root, and writable by no other account unless it is a root-owned sticky directory. */
    private static boolean isTrustedComponent(Path component, int currentUser) throws IOException {
        int owner = ownerUid(component);
        if (owner != currentUser && owner != ROOT_UID) return false;
        int mode = (Integer) Files.getAttribute(component, "unix:mode", LinkOption.NOFOLLOW_LINKS);
        boolean writableByOthers = (mode & (GROUP_WRITE | OTHERS_WRITE)) != 0;
        boolean rootSticky = owner == ROOT_UID && (mode & STICKY) != 0;
        return !writableByOthers || rootSticky;
    }

    /** {@code user.home} when it names an absolute path; an unknown home guards nothing more. */
    private static Optional<Path> userHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) return Optional.empty();
        Path home = Path.of(value);
        return home.isAbsolute() ? Optional.of(home.normalize()) : Optional.empty();
    }

    private static Path comparable(Path location) throws IOException {
        return Files.exists(location) ? location.toRealPath() : location.toAbsolutePath().normalize();
    }

    private static boolean overlaps(Path first, Path second) {
        return first.startsWith(second) || second.startsWith(first);
    }

    private static int ownerUid(Path path) throws IOException {
        return (Integer) Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    }

    private static IllegalStateException refused(String reason) {
        return new IllegalStateException(REFUSED + reason);
    }
}
