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
 * Borne de vie des résidus de travail d'un run mort (MINOS-AUD-A02).
 *
 * <p>Un répertoire de travail ou un temporaire de clonage n'est supprimé que par son propriétaire, à la fin de
 * son run. Un run tué (arrêt brutal, plantage, coupure) ne passe jamais par cette fin : sa copie reste, sans
 * borne. Ce balayage, appelé au moment où MINOS crée un nouveau résidu du même genre, supprime les enfants directs
 * d'une racine dont la dernière modification est <strong>strictement</strong> antérieure à la durée de vie.</p>
 *
 * <p>Garanties : il ne supprime que des enfants directs de la racine donnée, sans suivre un lien symbolique ni une
 * jonction (le lien lui-même est supprimé, jamais sa cible) ; il conserve les chemins protégés, les résidus
 * récents et ceux dont la date est dans le futur (saut d'horloge) ; son coût par appel est borné (entrées
 * examinées, candidats traités) ; il ne lève jamais d'exception, car une maintenance ne doit pas faire échouer
 * l'opération qui la déclenche : un résidu qui ne peut pas être supprimé est signalé par un avertissement qui
 * ne porte aucun chemin absolu, et laissé en place. Une date illisible n'est jamais une preuve d'ancienneté.</p>
 */
public final class StaleScratchReclamation {

    /**
     * Durée de vie unique des résidus. Elle excède strictement la durée maximale d'un run de provider (30 minutes
     * par défaut) et d'un clonage distant (2 heures), et vaut la durée de reprise d'un run interrompu.
     */
    public static final Duration DEFAULT_LIFETIME = Duration.ofHours(24);
    /** Nombre maximal d'entrées examinées par appel. */
    public static final int DEFAULT_MAX_EXAMINED = 1_024;
    /** Nombre maximal de candidats supprimés (ou tentés) par appel ; les suivants le seront aux déclenchements suivants. */
    public static final int DEFAULT_MAX_RECLAIMED = 16;

    private static final System.Logger LOGGER = System.getLogger(StaleScratchReclamation.class.getName());

    private StaleScratchReclamation() {
    }

    /**
     * Durée de vie, instant courant et bornes d'un balayage.
     *
     * @param lifetime     un enfant est ancien si sa dernière modification est strictement antérieure à
     *                     {@code now - lifetime}
     * @param now          instant courant, injectable
     * @param maxExamined  nombre maximal d'entrées examinées, au moins 1
     * @param maxReclaimed nombre maximal de candidats traités, au moins 1
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

        /** La durée de vie et les bornes par défaut, à l'instant donné. */
        public static Policy defaults(Instant now) {
            return new Policy(DEFAULT_LIFETIME, now, DEFAULT_MAX_EXAMINED, DEFAULT_MAX_RECLAIMED);
        }
    }

    /** Ce qu'un balayage a fait. {@code budgetExhausted} : des candidats peuvent rester pour un prochain appel. */
    public record Outcome(int examined, int reclaimed, int failed, boolean budgetExhausted) {
    }

    /** Balayage avec la politique par défaut, l'horloge courante et un avertissement journalisé. */
    public static Outcome reclaim(Path root, Predicate<String> childName, Set<Path> protectedChildren) {
        return reclaim(root, childName, protectedChildren, Instant.now());
    }

    /** Comme {@link #reclaim(Path, Predicate, Set)} avec l'instant courant de l'appelant (horloge injectée). */
    public static Outcome reclaim(Path root, Predicate<String> childName, Set<Path> protectedChildren, Instant now) {
        return reclaim(root, childName, protectedChildren, Policy.defaults(now), StaleScratchReclamation::logWarning);
    }

    /**
     * @param root              racine dont seuls les enfants directs sont candidats ; absente, non répertoire ou
     *                          lien : rien n'est fait
     * @param childName         prédicat sur le nom d'un enfant direct
     * @param protectedChildren enfants directs à ne jamais supprimer (le run courant)
     * @param policy            durée de vie, instant courant et bornes
     * @param warnings          reçoit un message sans chemin absolu à chaque résidu non supprimable
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
