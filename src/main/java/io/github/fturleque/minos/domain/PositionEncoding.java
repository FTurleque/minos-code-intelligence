package io.github.fturleque.minos.domain;

/**
 * Unité utilisée pour exprimer les colonnes d'un emplacement source.
 *
 * <p>Les lignes MINOS sont normalisées en base 1 pour les consommateurs humains,
 * tandis que les colonnes restent des offsets en base 0 dans l'unité indiquée ici.</p>
 */
public enum PositionEncoding {
    UTF8_CODE_UNITS,
    UTF16_CODE_UNITS,
    UTF32_CODE_UNITS,
    UNKNOWN
}
