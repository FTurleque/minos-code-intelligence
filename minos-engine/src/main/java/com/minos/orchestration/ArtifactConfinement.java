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
 * <p>La décision est physique, jamais textuelle. Le répertoire d'accueil de l'artefact est résolu
 * (liens et jonctions suivis) puis doit se trouver sous le répertoire de run lui-même résolu : un
 * segment {@code ..}, un répertoire ancêtre lié ou un alias (nom court Windows 8.3) sont tranchés sur
 * l'emplacement réel. Le dernier composant est ensuite ouvert par {@link ConfinedFileOpener}, la
 * primitive de confinement du dépôt (S5), qui refuse un lien symbolique, une jonction, un objet
 * spécial ou un fichier non régulier : un lien dont la cible retomberait dans le répertoire est refusé
 * comme les autres. Aucun message ne porte de chemin.</p>
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
        Path name = normalized.getFileName();
        Path parent = normalized.getParent();
        if (name == null || parent == null) throw new Escape(Reason.OUTSIDE, null);
        final Path realRoot;
        try {
            realRoot = runDirectory.toRealPath();
        } catch (IOException unresolvable) {
            throw new Escape(Reason.UNRESOLVABLE, unresolvable);
        }
        try {
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realRoot)) throw new Escape(Reason.OUTSIDE, null);
            Path relative = realRoot.relativize(realParent.resolve(name));
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
}
