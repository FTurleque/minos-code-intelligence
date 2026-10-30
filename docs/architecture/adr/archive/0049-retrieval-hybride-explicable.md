---
id: ADR-0049
title: "Faire évoluer le classement hybride par profils mesurés"
domain: backend
rules: false
---

# 0049 — Faire évoluer le classement hybride par profils mesurés

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0029, 0031 ; ne les remplace pas à ce stade.

## Context

HybridSearchService utilise recouvrement de termes, bonus phrase et coefficients fixes. Les échelles lexicales, graphe et vectorielles ne sont pas naturellement comparables.

## Decision

Conserver le profil legacy ; proposer BM25 avec segmentation d'identifiants, fusion RRF et routage déterministe borné. Traiter le graphe comme signal dérivé. Versionner profil, tokenizer et corpus. Préserver scores v1/minimumScore ; si leur sens change, exposer un contrat explicite plutôt que recycler les valeurs.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Conserver le profil legacy.
- Versionner profil, tokenizer et corpus.
- Préserver les scores v1/minimumScore ; si leur sens change, exposer un contrat explicite plutôt que recycler les valeurs.
- Ne promouvoir un profil qu'après un holdout concluant.

### Don't

- Ne pas retoucher les pondérations fixes sans banc.
- Aucun score de ranking ne devient une relation de programme.

## Alternatives considérées

A : pondérations fixes retouchées sans banc — rejetée. B : remplacement global par moteur tiers — non retenue sans besoin prouvé. C : stratégies internes comparables — proposée.

## Consequences

Complexité de configuration et caches lexicaux ; top-k borné et poids mémoire comptabilisé. Aucun score de ranking ne devient une relation de programme. Les tests/legacy ne sont pas systématiquement déclassés.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : un nouveau profil de classement est versionné, garde le profil legacy en retour arrière et cite son holdout.
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U1-01 à U1-05 ; G1/G3, ablations. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Promouvoir uniquement après holdout concluant ; rollback par profil legacy et reconstruction des caches dérivés.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
