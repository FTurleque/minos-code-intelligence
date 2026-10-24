package com.minos.io;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Lifetime bound of the working leftovers of a dead run (MINOS-AUD-A02).
 *
 * <p>A working directory or a clone temporary is deleted only by its owner, at the end of its run. A killed run
 * (abrupt stop, crash, power cut) never reaches that end: its copy stays, without bound. This sweep, called when
 * MINOS creates a new leftover of the same kind, deletes the direct children of a root whose last modification is
 * <strong>strictly</strong> older than the lifetime.</p>
 *
 * <p>Guarantees: it deletes only direct children of the given root, without following a symbolic link or a
 * junction (the link itself is deleted, never its target); it keeps the protected paths, the recent leftovers and
 * those whose date is in the future (clock jump); its cost per call is bounded (entries examined, candidates
 * processed); it never throws, because a maintenance task must not make the operation that triggers it fail: a
 * leftover that cannot be deleted is reported by a warning that carries no absolute path, and left in place. An
 * unreadable date is never a proof of age.</p>
 */
public final class StaleScratchReclamation {

    /**
     * Single lifetime of the leftovers. It strictly exceeds the maximum duration of a provider run (30 minutes by
     * default) and of a remote clone (2 hours), and equals the resume window of an interrupted run.
     */
    public static final Duration DEFAULT_LIFETIME = Duration.ofHours(24);
    /** Maximum number of entries examined per call. */
    public static final int DEFAULT_MAX_EXAMINED = 1_024;
    /** Maximum number of candidates deleted (or attempted) per call; the next ones are handled by the next triggers. */
    public static final int DEFAULT_MAX_RECLAIMED = 16;

    private static final System.Logger LOGGER = System.getLogger(StaleScratchReclamation.class.getName());

    private StaleScratchReclamation() {
    }

    /**
     * Lifetime, current instant and bounds of a sweep.
     *
     * @param lifetime     a child is old if its last modification is strictly older than
     *                     {@code now - lifetime}
     * @param now          current instant, injectable
     * @param maxExamined  maximum number of entries examined, at least 1
     * @param maxReclaimed maximum number of candidates processed, at least 1
     */
    public record Policy(Duration lifetime, Instant now, int maxExamined, int maxReclaimed) {
        public Policy {
            Objects.requireNonNull(lifetime, "lifetime");
            Objects.requireNonNull(now, "now");
            if (lifetime.isNegative() || lifetime.isZero()) {
                throw new IllegalArgumentException("lifetime must be positive");
            }
            if (maxExamined < 1 || maxReclaimed < 1) {
                throw new IllegalArgumentException("bounds must be at least 1");
            }
        }

        /** The default lifetime and bounds, at the given instant. */
        public static Policy defaults(Instant now) {
            return new Policy(DEFAULT_LIFETIME, now, DEFAULT_MAX_EXAMINED, DEFAULT_MAX_RECLAIMED);
        }
    }

    /** What a sweep did. {@code budgetExhausted}: candidates may remain for a later call. */
    public record Outcome(int examined, int reclaimed, int failed, boolean budgetExhausted) {
    }

    /** Sweep with the default policy, the current clock and a logged warning. */
    public static Outcome reclaim(Path root, Predicate<String> childName, Set<Path> protectedChildren) {
        return reclaim(root, childName, protectedChildren, Instant.now());
    }

    /** Like {@link #reclaim(Path, Predicate, Set)} with the caller's current instant (injected clock). */
    public static Outcome reclaim(Path root, Predicate<String> childName, Set<Path> protectedChildren, Instant now) {
        return reclaim(root, childName, protectedChildren, Policy.defaults(now), StaleScratchReclamation::logWarning);
    }

    /**
     * @param root              root whose direct children only are candidates; missing, not a directory or a
     *                          link: nothing is done
     * @param childName         predicate on the name of a direct child
     * @param protectedChildren direct children never to delete (the current run)
     * @param policy            lifetime, current instant and bounds
     * @param warnings          receives a message without absolute path for each leftover that cannot be deleted
     */
    public static Outcome reclaim(
            Path root,
            Predicate<String> childName,
            Set<Path> protectedChildren,
            Policy policy,
            Consumer<String> warnings
    ) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(childName, "childName");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(warnings, "warnings");
        Set<Path> protectedPaths = Set.copyOf(Objects.requireNonNull(protectedChildren, "protectedChildren").stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList());
        return new Sweep(root.toAbsolutePath().normalize(), childName, protectedPaths, policy, warnings).run();
    }

    /** One sweep: the root, what makes a child a candidate, and the counters of what has been done. */
    private static final class Sweep {
        private final Path root;
        private final Predicate<String> childName;
        private final Set<Path> protectedPaths;
        private final Policy policy;
        private final Instant threshold;
        private final Consumer<String> warnings;
        private int examined;
        private int reclaimed;
        private int failed;
        private boolean exhausted;

        Sweep(Path root, Predicate<String> childName, Set<Path> protectedPaths, Policy policy,
              Consumer<String> warnings) {
            this.root = root;
            this.childName = childName;
            this.protectedPaths = protectedPaths;
            this.policy = policy;
            this.threshold = policy.now().minus(policy.lifetime());
            this.warnings = warnings;
        }

        Outcome run() {
            try {
                if (isPlainDirectory(root)) {
                    sweepChildren();
                }
            } catch (IOException | RuntimeException failure) {
                warnings.accept("MINOS could not sweep an expired scratch root: " + failure.getClass().getSimpleName());
            }
            return new Outcome(examined, reclaimed, failed, exhausted);
        }

        private void sweepChildren() throws IOException {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    if (examined >= policy.maxExamined() || reclaimed + failed >= policy.maxReclaimed()) {
                        exhausted = true;
                        return;
                    }
                    examined++;
                    Path candidate = child.toAbsolutePath().normalize();
                    if (isCandidate(candidate)) {
                        reclaim(candidate);
                    }
                }
            }
        }

        private boolean isCandidate(Path candidate) {
            return root.equals(candidate.getParent())
                    && childName.test(candidate.getFileName().toString())
                    && !protectedPaths.contains(candidate)
                    && isExpired(candidate);
        }

        /** The entry itself, never what a link points at; a date in the future, or an unreadable one, is never old. */
        private boolean isExpired(Path candidate) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return attributes.lastModifiedTime().toInstant().isBefore(threshold);
            } catch (IOException exception) {
                return false;
            }
        }

        private void reclaim(Path candidate) {
            try {
                FileTreeOperations.deleteRecursively(candidate);
                reclaimed++;
            } catch (IOException | RuntimeException failure) {
                failed++;
                warnings.accept("MINOS could not remove an expired scratch residue (" + candidate.getFileName()
                        + "): " + failure.getClass().getSimpleName());
            }
        }

        private static boolean isPlainDirectory(Path path) {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return FileTreeOperations.isRecursableDirectory(attributes);
            } catch (IOException exception) {
                return false;
            }
        }
    }

    private static void logWarning(String message) {
        LOGGER.log(System.Logger.Level.WARNING, message);
    }
}
