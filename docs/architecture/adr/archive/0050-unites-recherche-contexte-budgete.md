---
id: ADR-0050
title: "Assembler un contexte cohérent, compact et traçable"
domain: backend
rules: false
---

# 0050 — Assembler un contexte cohérent, compact et traçable

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans [`docs/project/research/minos-evolution-2026-10/`](../../../project/research/minos-evolution-2026-10/README.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Status: **Proposed** (2026-10-04). Aucune implémentation ni qualification acquise.
Programme : [étude MINOS](../../../project/research/minos-evolution-2026-10/README.md).
Complète les ADR 0011, 0029 ; ne les remplace pas à ce stade.

## Context

SemanticDocumentFactory produit SYMBOL/FILE/CHUNK ; HybridContextBuilder sélectionne sous budget de contenu. Les recouvrements et l'enveloppe sérialisée doivent être mesurés.

## Decision

Découper d'abord avec les frontières symboliques disponibles ; isoler documents/config des faits SCIP ; dédupliquer par plage et génération ; conserver preuves et source. Définir le budget complet rendu avec compteurs estimés explicitement nommés et tokenizer de référence pour le banc. Résumé puis détail par référence versionnée.

## Do's and Don'ts

### Do

- Tant que l'ADR est Proposed, ces points ne sont pas normatifs.
- Découper d'abord avec les frontières symboliques disponibles.
- Isoler documents et configuration des faits SCIP ; dédupliquer par plage et génération ; conserver preuves et source.
- Définir le budget complet rendu avec des compteurs estimés explicitement nommés.

### Don't

- Aucun chunk documentaire ne devient un symbole.
- Ne pas imposer Tree-sitter à tous les langages sans justifier le double parsing.

## Alternatives considérées

A : fichiers entiers — coûteux. B : Tree-sitter imposé à tous les langages — double parsing à justifier. C : symboles existants puis fallback syntaxique/textuel explicitement limité — proposée.

## Consequences

Plus de métadonnées et règles de déduplication. Aucun chunk documentaire ne devient un symbole. Changer le découpage invalide le cache mais ne migre pas le snapshot. Les imports/config utiles restent accessibles.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : un changement de découpage conserve l'ancien découpage et le rendu v1 en repli, sans perte de preuves.
- À réévaluer : aucune mise en œuvre au 2026-10-23.

## Validation et tâches

U2 et U3 ; G2/G3 et tests générations. Voir [protocole](../../../project/research/minos-evolution-2026-10/EVALUATION.md) et [roadmap](../../../project/research/minos-evolution-2026-10/ROADMAP.md). Les mesures propres aux candidats appartiennent aux rapports d'évaluation, pas à cet ADR.

## Acceptation, déploiement et retour arrière

Accepter après comparaison à classement fixe et absence de perte de preuves ; fallback ancien découpage et rendu v1 conservés.

## Provenance

Analyse du code MINOS et des approches Semble/Serena décrites dans [SOURCES.md](../../../project/research/minos-evolution-2026-10/SOURCES.md). Aucune réutilisation de code tiers décidée.
