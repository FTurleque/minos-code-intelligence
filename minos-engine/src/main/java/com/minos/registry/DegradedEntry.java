package com.minos.registry;

import static com.minos.domain.Preconditions.requireText;

/**
 * Une entrée d'inventaire qui n'a pas pu être lue, ou pas assemblée en entier : elle est comptée et montrée,
 * jamais écartée en silence (Q8).
 *
 * <p>{@code entry} identifie l'entrée sans chemin (l'identifiant du projet, ou le nom de son fichier de registre
 * réduit à des caractères sûrs) ; {@code reason} est un message public : il a traversé
 * {@link com.minos.diagnostics.PublicErrorMessages} et ne porte donc aucun chemin absolu.</p>
 */
public record DegradedEntry(String entry, String reason) {

    public DegradedEntry {
        requireText(entry, "entry");
        requireText(reason, "reason");
    }
}
