package com.minos.runtime.local.workspace;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Texte des journaux WARNING d'une récupération de fichiers (répertoires de run, résidus de provider).
 *
 * <p>Un journal est lu par un opérateur et capturé par les clients MCP : il porte le chemin <em>relatif</em> à la
 * racine gérée et la classe de l'exception, jamais un chemin absolu (nom d'utilisateur, structure du disque) ni la
 * cause, dont le message est souvent ce chemin ({@code AccessDeniedException}).</p>
 */
final class RecoveryLogMessages {

    private static final String OUTSIDE_ROOT = "<outside the managed root>";

    private RecoveryLogMessages() {
    }

    /** {@code MINOS could not <action> '<chemin relatif>': <classe>}. */
    static String of(String action, Path root, Path target, Exception failure) {
        return "MINOS could not " + action + " '" + relative(root, target) + "': " + failure.getClass().getSimpleName();
    }

    /** {@code MINOS could not <action>: <classe>}, pour un échec qui ne désigne aucun chemin. */
    static String of(String action, Exception failure) {
        return "MINOS could not " + action + ": " + failure.getClass().getSimpleName();
    }

    private static String relative(Path root, Path target) {
        Path base = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        Path path = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        if (!path.startsWith(base) || path.equals(base)) {
            return OUTSIDE_ROOT;
        }
        StringBuilder shown = new StringBuilder();
        for (Path name : base.relativize(path)) {
            if (!shown.isEmpty()) {
                shown.append('/');
            }
            shown.append(name);
        }
        return shown.toString();
    }
}
