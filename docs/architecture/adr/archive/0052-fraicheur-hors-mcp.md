---
id: ADR-0052
title: "Actualiser les index hors du chemin MCP en lecture seule"
domain: architecture
rules: false
---

# 0052 — Actualiser les index hors du chemin MCP en lecture seule

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0014, 0017, 0039, 0041 ; ne les remplace pas à ce stade.

## Context

Semble actualise son cache à la demande ; reproduire cela directement violerait l'ADR-0017 de MINOS. Une actualisation peut aussi exécuter un build via un provider.

## Decision

Maintenir lecture MCP sans effet de bord ; ajouter une coordination locale opt-in hors MCP réutilisant lifecycle, leases et reprise existants. Diagnostiquer fraîcheur par génération/empreinte ; réconcilier overflow/branches ; publication atomique. Incrémental seulement sur capability qualifiée ; sinon reconstruction complète annoncée.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Maintenir la lecture MCP sans effet de bord.
- Coordonner l'actualisation hors MCP, en opt-in, en réutilisant lifecycle, leases et reprise existants.
- N'utiliser l'incrémental que sur capability qualifiée ; sinon, annoncer une reconstruction complète.
- Garder le mode watch désactivé par défaut.

### Don't

- Ne pas indexer depuis une recherche MCP.
- Aucun assouplissement du confinement ni du remote index.

## Alternatives considérées

A : indexer depuis search MCP — rejetée. B : freshness invisible — rejetée. C : coordinateur indépendant + état observable — proposée.

## Consequences

Nouveau lifecycle à superviser ; arrêt, crash et quotas ; aucun assouplissement du confinement ou remote index. Détection rapide ne garantit pas indexation rapide. Les buffers IDE sont une source distincte.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : aucune actualisation n'est déclenchée depuis un outil MCP ; concurrence et crash validés avant activation.
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U5-01 à U5-04 ; G4/G5. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Valider concurrence et crash avant activation ; mode watch désactivé par défaut ; arrêt du coordinateur et synchronisation manuelle comme rollback.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
