package com.minos.orchestration;

import com.minos.io.ConfinedFileOpener;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * La seule décision « cet artefact reste dans le répertoire de run » de l'orchestration : l'artefact
 * qu'un exécuteur vient de rendre ({@code validateArtifact}), celui qu'un plan de reprise veut
 * réutiliser et celui que la reprise revérifie juste avant la mise en snapshot passent tous ici.
 *
 * <p>La décision ne repose jamais sur une comparaison de chaînes. Le chemin de l'artefact est normalisé
 * (un segment {@code ..} qui sort du répertoire le sort du préfixe) et doit se lire sous le répertoire de
 * run, tel qu'il est écrit ou tel qu'il se résout (alias Windows 8.3, racine atteinte par un lien).
 * Les composants qui restent sont ensuite parcourus par {@link ConfinedFileOpener}, la primitive de
 * confinement du dépôt (S5) : chaque répertoire est descendu sans suivre de lien et le dernier composant
 * est ouvert sans suivre de lien, si bien qu'un lien symbolique, une jonction ou un objet spécial est refusé
 * <em>à n'importe quel niveau</em> du chemin, même quand sa cible retomberait dans le répertoire de run, et
 * qu'un fichier non régulier est refusé. Aucun message ne porte de chemin.</p>
 */
final class ArtifactConfinement {

    private ArtifactConfinement() { }

    /** Pourquoi l'artefact n'est pas confiné ; {@link #phrase()} complète « artifact … ». */
    enum Reason {
        OUTSIDE("lies outside the run directory"),
        LINKED("is, or lies under, a symbolic link or a special file"),
        MISSING("is missing"),
        UNRESOLVABLE("cannot be resolved");

        private final String phrase;

        Reason(String phrase) {
            this.phrase = phrase;
        }

        String phrase() {
            return phrase;
        }
    }

    /** Refus de confinement ; la cause d'entrée-sortie est chaînée (elle peut être une interruption). */
    static final class Escape extends Exception {
        private static final long serialVersionUID = 1L;

        private final Reason reason;

        private Escape(Reason reason, Throwable cause) {
            super("artifact " + reason.phrase(), cause);
            this.reason = reason;
        }

        Reason reason() {
            return reason;
        }
    }

    /**
     * Exige que {@code artifact} soit un fichier régulier, sans lien sur son chemin, sous
     * {@code runDirectory}.
     *
     * @throws Escape quand ce n'est pas démontrable : il n'y a jamais de « peut-être dedans »
     */
    static void requireInside(Path runDirectory, Path artifact) throws Escape {
        Objects.requireNonNull(runDirectory, "runDirectory");
        Path normalized = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        final Path realRoot;
        try {
            realRoot = runDirectory.toRealPath();
        } catch (IOException unresolvable) {
            throw new Escape(Reason.UNRESOLVABLE, unresolvable);
        }
        Path relative = relativeToTheRunDirectory(normalized, runDirectory.toAbsolutePath().normalize(), realRoot);
        if (relative == null) throw new Escape(Reason.OUTSIDE, null);
        try {
            try (SeekableByteChannel ignored = ConfinedFileOpener.openConfinedRegularFile(realRoot, relative)) {
                // L'ouverture est la preuve : aucun lien sur le chemin, un fichier physique régulier au bout.
            }
        } catch (NoSuchFileException absent) {
            throw new Escape(Reason.MISSING, absent);
        } catch (ConfinedFileOpener.ConfinementException refused) {
            throw new Escape(Reason.LINKED, refused);
        } catch (IOException unresolvable) {
            throw new Escape(Reason.UNRESOLVABLE, unresolvable);
        }
    }

    /**
     * Le chemin de l'artefact relativement au répertoire de run, ou {@code null} quand il n'est pas dessous :
     * d'abord tel que le répertoire est écrit, puis tel qu'il se résout (l'artefact peut être écrit avec le
     * nom long là où le répertoire l'est avec un nom court). Jamais un lien n'est suivi pour y parvenir.
     */
    private static Path relativeToTheRunDirectory(Path artifact, Path lexicalRoot, Path realRoot) {
        if (artifact.startsWith(lexicalRoot)) return lexicalRoot.relativize(artifact);
        if (artifact.startsWith(realRoot)) return realRoot.relativize(artifact);
        return null;
    }
}
