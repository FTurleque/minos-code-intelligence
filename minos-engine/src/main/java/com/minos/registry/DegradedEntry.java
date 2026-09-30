package com.minos.registry;

import com.minos.diagnostics.PublicErrorMessages;

import static com.minos.domain.Preconditions.requireText;

/**
 * Une entrée d'inventaire qui n'a pas pu être lue, ou pas assemblée en entier : elle est comptée et montrée,
 * jamais écartée en silence (Q8).
 *
 * <p>{@code entry} identifie l'entrée sans chemin (l'identifiant du projet, ou le nom de son fichier de registre
 * réduit à des caractères sûrs) ; {@code reason} est un message public : il a traversé
 * {@link PublicErrorMessages} et ne porte donc aucun chemin absolu. Les deux fabriques sont l'unique endroit qui
 * décide de ces deux formes, pour le registre comme pour l'assemblage de la vue d'un projet.</p>
 */
public record DegradedEntry(String entry, String reason) {

    private static final int MAX_ENTRY_LENGTH = 64;
    private static final String UNNAMED = "unnamed";

    public DegradedEntry {
        requireText(entry, "entry");
        requireText(reason, "reason");
    }

    /** Une entrée illisible pour une raison qui n'est pas une exception (par exemple : pas un fichier régulier). */
    public static DegradedEntry of(String rawEntry, String what) {
        return new DegradedEntry(safeEntry(rawEntry), requireText(what, "what"));
    }

    /** Une entrée dont la lecture a échoué : {@code what} dit ce qui n'a pas pu être fait, sans chemin. */
    public static DegradedEntry of(String rawEntry, String what, Throwable failure) {
        String detail = PublicErrorMessages.sanitize(failure.getMessage(), failure.getClass().getSimpleName());
        return new DegradedEntry(safeEntry(rawEntry), requireText(what, "what") + " (" + detail + ")");
    }

    private static String safeEntry(String rawEntry) {
        if (rawEntry == null || rawEntry.isBlank()) return UNNAMED;
        StringBuilder safe = new StringBuilder(Math.min(rawEntry.length(), MAX_ENTRY_LENGTH));
        for (int index = 0; index < rawEntry.length() && safe.length() < MAX_ENTRY_LENGTH; index++) {
            char value = rawEntry.charAt(index);
            boolean plain = value < 128 && (Character.isLetterOrDigit(value) || value == '-' || value == '_' || value == '.');
            safe.append(plain ? value : '_');
        }
        return safe.toString();
    }
}
