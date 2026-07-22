package io.github.fturleque.minos.domain;

/**
 * Types de preuves pouvant justifier un fait ou une dérivation MINOS.
 */
public enum EvidenceType {
    DIRECT_REFERENCE,
    DIRECT_CALL,
    IMPORT,
    TYPE_RELATIONSHIP,
    NAMING_CONVENTION,
    PACKAGE_PROXIMITY,
    TEST_LOCATION,
    DERIVATION_PATH,
    PROVIDER_FACT,
    OTHER
}
