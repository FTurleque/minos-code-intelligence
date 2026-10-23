---
id: ARCH-NNNN
title: "Titre court qui énonce la décision"
domain: architecture
rules: false
---

# Titre court qui énonce la décision

- **Statut** : Proposed | Accepted | Superseded | Rejected
- **Date** : AAAA-MM-JJ
- **Origine** : jalon, audit ou changement OpenSpec
- **Remplace** : ADR-XXXX (si applicable)
- **Remplacé par** : ADR-YYYY (à remplir si cet ADR est remplacé plus tard)

Copier ce fichier en `docs/architecture/adr/{PRÉFIXE}-{NNNN}-titre-en-kebab-case.md` : préfixe du domaine (`architecture` → `ARCH`, `backend` → `BE`, `data` → `DATA`, `general` → `GEN`, `security` → `SEC`) et numéro suivant de l'index. L'en-tête YAML reste en ligne 1 ; `id` reprend le début du nom de fichier (`ARCH-NNNN`), le titre `#` reprend exactement `title`. `rules: true` seulement avec un fichier compagnon `{même nom}.rules.ts` dont la règle a été vue en échec sur une violation ; `files` borne le périmètre d'une règle qui ne regarde qu'une partie du dépôt. Les titres de section restent ceux d'Archgate ; le texte est en français.

## Context

Faits vérifiés (`fichier:ligne`, mesures, gates), contraintes, options considérées.

## Decision

Ce qui est décidé, conditions prouvables (test, gate), ce qui est exclu.

## Do's and Don'ts

### Do

- Ce que la décision impose, en une phrase vérifiable.

### Don't

- Ce que la décision interdit.

## Consequences

### Positives

- …

### Négatives

- …

### Risques

- …

## Compliance and Enforcement

- Contrôle automatique : gate `scripts/…/check-….py` (job `invariants`), règle de `scripts/architecture/check-module-boundaries.py`, test, ou règle Archgate compagnon — ce qu'il vérifie.
- Revue : ce qu'un relecteur doit vérifier.

## Mise en œuvre

(Complété à l'implémentation : écarts à la conception, preuves.)

## References

- ADR liés, changement OpenSpec, sections arc42.
