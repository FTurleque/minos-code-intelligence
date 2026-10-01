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
        return new DegradedEntry(safeEntry(rawEntry), requireText(what, "what") + " (" + printable(detail) + ")");
    }

    /**
     * Le message d'une exception recopie parfois une valeur lue dans un fichier : {@code sanitize} aplatit les sauts
     * de ligne mais laisse passer les séquences de contrôle (ESC, BEL, CSI sur 8 bits, inversion bidirectionnelle),
     * que l'affichage en terminal exécuterait. Elles sont remplacées ici, une seule fois, par {@code _}.
     */
    public static String printable(String text) {
        StringBuilder printable = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> printable.appendCodePoint(isUnsafeForTerminal(codePoint) ? '_' : codePoint));
        return printable.toString();
    }

    private static boolean isUnsafeForTerminal(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint) || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
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
