package com.minos.cli.support;

/**
 * Codes de sortie du CLI, définis une seule fois. Les constantes de même nom de {@link FindSymbolCommand},
 * antérieures à cette classe, y renvoient.
 */
public final class CliExitCodes {

    public static final int SUCCESS = 0;
    public static final int EXECUTION_ERROR = 1;
    public static final int USAGE_ERROR = 2;
    /** Résultat partiel : la sortie est valide pour ce qui a pu être lu ; voir {@link FindSymbolCommand#PARTIAL_RESULT}. */
    public static final int PARTIAL_RESULT = 3;

    private CliExitCodes() {
    }
}
