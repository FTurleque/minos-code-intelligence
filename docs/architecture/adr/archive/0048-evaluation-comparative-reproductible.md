---
id: ADR-0048
title: "Évaluer les changements sur un protocole commun"
domain: general
rules: false
---

# 0048 — Évaluer les changements sur un protocole commun

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](../0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0025, 0031 ; ne les remplace pas à ce stade.

## Context

Les gains publiés par un concurrent ne prouvent ni la qualité sur les projets MINOS ni une économie de crédits. Les bancs snapshot existants ne couvrent pas toutes les tâches agents.

## Decision

Geler corpus, partitions, versions et machine avant réglage ; comparer des capacités communes ; mesurer qualité, ressources, tokens sérialisés et réussite des tâches. Distinguer FAILED, NOT_RUN et NOT_SUPPORTED. Publier les ablations et les résultats négatifs.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Geler corpus, partitions, versions et machine avant tout réglage.
- Comparer des capacités communes ; mesurer qualité, ressources, tokens sérialisés et réussite des tâches.
- Distinguer FAILED, NOT_RUN et NOT_SUPPORTED.
- Publier les ablations et les résultats négatifs.

### Don't

- Ne pas adopter les chiffres publiés en amont : le protocole est différent.
- Ne pas mesurer uniquement la latence.
- Aucune collecte distante automatique.

## Alternatives considérées

A : adopter les chiffres amont — rejetée, protocole différent. B : mesurer uniquement latence — rejetée, permet une réponse rapide mais fausse. C : banc commun contrôlé — proposée.

## Consequences

Coût d'annotation et d'exécution ; budget LLM à prévoir. Données privées locales uniquement. Aucune collecte distante automatique.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : un changement de ranking cite le protocole commun et ses résultats, négatifs compris, validés avec le propriétaire (corpus, budget, seuils).
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U0-01 à U0-04 ; G0 puis G1–G7 dans EVALUATION. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Valider corpus, budget et seuils avec le propriétaire avant le premier changement de ranking. En cas de mesure inconclusive, conserver le profil courant.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
