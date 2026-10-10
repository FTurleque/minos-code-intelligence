package com.minos.application;

import java.util.List;

/**
 * Ce que toute vue d'un projet expose : le contrat commun de {@link ProjectOperations.ProjectView} et de
 * {@link ProjectInspectionService.ProjectView}, deux enregistrements de même forme conservés parce que chacun
 * est un type public observé par un autre module (voir {@code docs/audit/archive/2026-09/CODE-SUIVI.md}, § 21.3). Les
 * projections partagées par la CLI et le MCP ({@code com.minos.output.ProjectJson}) s'écrivent contre cette
 * interface, une seule fois, au lieu d'une copie par enregistrement.
 */
public interface ProjectSummary {

    /**
     * L'état d'une ligne d'inventaire dont le projet n'a pas pu être lu ou assemblé (Q8) : ce n'est pas une
     * disponibilité d'index ({@code ProjectIndexState.Availability}), c'est l'état de la vue.
     */
    String UNREADABLE_STATE = "UNREADABLE";

    String id();

    String name();

    String rootPath();

    boolean rootAvailable();

    List<String> languages();

    List<String> buildSystems();

    int moduleCount();

    String indexState();

    String activeSnapshotId();

    String lastSuccessfulIndexAt();

    String providerId();

    String providerVersion();
}
