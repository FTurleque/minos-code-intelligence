package com.minos.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Port : sonde des sandboxes de worker disponibles sur l'hôte (ADR 0038, ADR 0041). L'implémentation
 * de production, câblée par minos-bootstrap, interroge les backends réels de l'OS ; seuls les tests
 * substituent un double. Identifiants de backend et codes seulement : jamais de chemin.
 */
public interface WorkerSandboxProbe {

    /**
     * Nom de la cause « écarté par décision » (ADR 0041) tel que le rapporte
     * {@link UntrustedCodeSandbox#cause()} ; l'implémentation de production le garantit égal au nom de
     * l'énumération de sélection des backends (test de minos-bootstrap).
     */
    String CAUSE_REJECTED_BY_DECISION = "REJECTED_BY_DECISION";

    /** Backend le plus fort pour les providers locaux gérés, et s'il honore ce contrat ici. */
    ManagedLocalSandbox managedLocalProvider(Path home);

    /** Sélection stricte pour du code distant non fiable, sur l'hôte réel. */
    UntrustedCodeSandbox untrustedCode(Path home);

    record ManagedLocalSandbox(String backendId, boolean available) {
        public ManagedLocalSandbox {
            Objects.requireNonNull(backendId, "backendId");
        }
    }

    /**
     * @param refusalReport rapport de refus sans chemin, vide quand le code non fiable est supporté
     */
    record UntrustedCodeSandbox(
            String backendId,
            boolean supportsUntrustedCode,
            String cause,
            Optional<String> rejectedBackendId,
            List<String> rejectionReasons,
            String refusalReport
    ) {
        public UntrustedCodeSandbox {
            Objects.requireNonNull(backendId, "backendId");
            Objects.requireNonNull(cause, "cause");
            Objects.requireNonNull(rejectedBackendId, "rejectedBackendId");
            rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
            Objects.requireNonNull(refusalReport, "refusalReport");
        }
    }
}
