package com.minos.orchestration;

/** Politique de reprise d'un run interrompu (ADR 0039 §6). */
public enum IndexingResumePolicy {
    /** Par défaut : rouvrir le run interrompu quand ses points de contrôle sont valides, sinon run complet. */
    RESUME,
    /** Supplanter explicitement le run interrompu et exécuter un run complet. */
    NO_RESUME,
    /** Échouer proprement, sans créer de run, si aucune reprise n'est possible (utile en CI). */
    RESUME_ONLY
}
