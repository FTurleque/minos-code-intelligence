package com.minos.domain;

/**
 * Validations d'arguments partagées par tous les modules MINOS (Q13).
 *
 * <p>Ce module est le seul que voient tous les appelants sans dépendance interdite par
 * {@code check-module-boundaries.py} (il ne dépend d'aucun autre module MINOS) : une validation
 * écrite une seule fois ici remplace les copies privées de chaque record et de chaque service.</p>
 */
public final class Preconditions {

    private Preconditions() {
    }

    /**
     * Refuse un texte absent ou blanc.
     *
     * @param value le texte à contrôler
     * @param name le nom du champ, repris tel quel dans le message d'erreur
     * @return {@code value}, inchangé (jamais rogné)
     * @throws IllegalArgumentException si {@code value} est {@code null} ou blanc, avec le message
     *         {@code "<name> must not be blank"}
     */
    public static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
