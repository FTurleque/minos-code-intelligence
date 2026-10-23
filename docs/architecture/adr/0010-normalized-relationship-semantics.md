---
id: ADR-0010
title: "Normaliser les relations avec provenance, preuve et confiance explicites"
domain: data
rules: false
---

# ADR-0010 — Normaliser les relations avec provenance, preuve et confiance explicites

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M3 — Intelligence des relations

## Context

Les indexeurs ne publient pas tous les mêmes relations ni avec la même sémantique. Transformer une référence, une implémentation ou une absence de relation en information plus précise créerait de faux faits.

## Decision

MINOS représente les relations dans un modèle fournisseur-neutre en conservant leur origine et leur niveau de certitude.

- les relations fournisseur sont normalisées uniquement lorsqu’une équivalence explicite existe ;
- les dépendances dérivées restent identifiées comme dérivations explicables ;
- provenance, nature, confiance et preuves sont conservées ;
- les cibles non résolues restent non résolues ;
- une capacité absente, telle qu’un `CALLS` non émis, n’est jamais synthétisée comme fait certain.

## Do's and Don'ts

### Do

- Représenter les relations dans un modèle fournisseur-neutre en conservant leur origine et leur niveau de certitude.
- Conserver provenance, nature, confiance et preuves.
- Identifier les dépendances dérivées comme des dérivations explicables.

### Don't

- Ne normaliser une relation fournisseur que s'il existe une équivalence explicite.
- Ne pas résoudre une cible non résolue.
- Ne jamais synthétiser une capacité absente, telle qu'un `CALLS` non émis, comme fait certain.

## Consequences

Les requêtes directionnelles peuvent être uniformes entre fournisseurs sans masquer leurs limitations. Les consommateurs peuvent expliquer pourquoi une relation existe et distinguer fait observé et dérivation.

## Compliance and Enforcement

- Aucun contrôle automatique : vérification en revue de code.
- Revue : relations normalisées seulement sur équivalence explicite, provenance et confiance conservées, cibles non résolues laissées non résolues.

## Preuves

Voir [`../../project/history/milestones/m3/DECISION_M3.md`](../../project/history/milestones/m3/DECISION_M3.md) et les rapports M3 associés.
