---
id: ADR-NNNN
title: "Titre court qui énonce la décision"
domain: architecture
rules: false
---

# ADR-NNNN — Titre court qui énonce la décision

- **Statut** : Proposed | Accepted | Superseded | Rejected
- **Date** : AAAA-MM-JJ
- **Origine** : jalon, audit ou changement OpenSpec
- **Remplace** : ADR-XXXX (si applicable)
- **Remplacé par** : ADR-YYYY (à remplir si cet ADR est remplacé plus tard)

Copier ce fichier en `docs/architecture/adr/NNNN-titre-en-kebab-case.md` (numéro suivant de l'index). L'en-tête YAML reste en ligne 1 : Archgate le lit (`id` = `ADR-` + les quatre chiffres du fichier ; `domain` parmi `architecture`, `backend`, `data`, `general`, `security` ; `rules: true` seulement avec un fichier compagnon `NNNN-titre-en-kebab-case.rules.ts` dont la règle a été vue en échec sur une violation). Les titres de section restent ceux d'Archgate ; le texte est en français.

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
