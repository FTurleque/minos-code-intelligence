---
id: ADR-0063
title: "Écrire les ADR au format Archgate dans le registre arc42"
domain: general
rules: false
---

# ADR-0063 — Écrire les ADR au format Archgate dans le registre arc42

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : décision du propriétaire du 2026-10-23, après l'audit des 62 ADR et la lecture de la documentation d'Archgate (CLI 0.60.0)
- **Remplace** : —
- **Remplacé par** : —

## Context

Le registre des décisions de MINOS est `docs/architecture/adr/`, à l'emplacement que prévoit arc42 (section 9, `docs/architecture/arc42/09-decisions.md`). Archgate exécute des règles TypeScript rattachées à des ADR. Il lit par défaut `.archgate/adrs/`, mais `.archgate/config.json` accepte `paths.adrs` (chemin relatif à la racine du dépôt). Il exige, en ligne 1 de chaque ADR, un en-tête YAML `id`, `title`, `domain`, `rules`, et ses résumés pour agents (`archgate review-context`) lisent les sections intitulées exactement « Decision » et « Do's and Don'ts ».

Avant cette décision, Archgate ne voyait qu'un fichier pont (`.archgate/adrs/ARCH-044-…`) recopiant l'identité de l'ADR 0044, et les 62 ADR suivaient trois formats d'en-tête de statut différents.

Options considérées :

- **Ponts dans `.archgate/adrs/`** : registre intact, mais deux fichiers par décision outillée et des agents qui ne voient que les ponts.
- **Registre lu directement par Archgate** : un seul fichier par décision, lu par les humains, arc42 et Archgate ; coût : un en-tête YAML et des titres de section Archgate dans chaque ADR.

## Decision

1. `docs/architecture/adr/` reste le registre unique. `.archgate/config.json` y pointe (`"paths": { "adrs": "docs/architecture/adr" }`) ; `.archgate/adrs/` ne contient qu'un `.gitkeep`, exigé par la CLI.
2. Chaque ADR commence par l'en-tête YAML : `id: ADR-NNNN` (les quatre chiffres du nom de fichier, jamais renumérotés), `title`, `domain` (`architecture`, `backend`, `data`, `general`, ou `security`, domaine ajouté avec le préfixe `SEC`), `rules`.
3. Les sections portent les titres d'Archgate : `Context`, `Decision`, `Do's and Don'ts` (`Do`, `Don't`), `Consequences`, `Compliance and Enforcement`, `References` ; les autres sections (Mise en œuvre, Options, Limites…) gardent leur titre. Le texte reste en français.
4. Une règle Archgate vit à côté de son ADR (`NNNN-slug.rules.ts`, `rules: true`) et lit les mêmes sources que le gate Python correspondant, qui reste la source de vérité exécutée par la CI.
5. Le gabarit est `docs/architecture/adr/gabarit/template.md` ; un sous-dossier n'est pas lu par Archgate. `archgate adr create` n'est pas utilisé : il numérote par domaine et écrit un gabarit anglais.
6. Un écart constaté entre une décision et le code s'écrit dans une section « Écarts constatés » datée, sans modifier la décision.

## Do's and Don'ts

### Do

- Créer un ADR en copiant `docs/architecture/adr/gabarit/template.md` et en prenant le numéro suivant de l'index.
- Garder l'en-tête YAML en ligne 1, avec `id: ADR-` suivi des quatre chiffres du fichier.
- Écrire « Do's and Don'ts » et « Compliance and Enforcement » à partir du seul texte de la décision et des contrôles qui existent.
- Placer une règle Archgate à côté de son ADR et la faire lire les sources du gate Python.

### Don't

- Ne pas recréer de registre ou de pont dans `.archgate/adrs/`.
- Ne pas utiliser `archgate adr create` ni renuméroter un ADR.
- Ne pas modifier une décision acceptée pour la faire coïncider avec le code : consigner l'écart.
- Ne pas déclarer `rules: true` sans fichier compagnon dont la règle a été vue en échec.

## Consequences

### Positives

- Un seul fichier par décision, lu par les humains, l'index arc42, Archgate et les agents (`archgate adr list`, `review-context`).
- Format uniforme pour les 63 ADR, statuts compris.

### Négatives

- Les titres de section sont en anglais dans des documents en français.
- `README.md` reste dans le dossier sans en-tête : Archgate le signale comme non lu, ce qui interdit le mode `--strict`.

### Risques

- Une évolution du schéma Archgate impose de reprendre les en-têtes : vérifier `archgate adr list` à chaque montée de version de la CLI.
- `archgate check` écrit `docs/architecture/rules.d.ts` : fichier généré, ignoré par Git.

## Compliance and Enforcement

- `archgate adr list` liste chaque ADR du registre avec son `id`, son `domain` et `rules` ; un ADR absent de la liste a un en-tête invalide.
- `archgate check` exécute les règles compagnons (aujourd'hui `0044-un-package-un-module.rules.ts`, invariant A7) ; il n'est pas lancé par la CI, le gate Python l'est.
- Revue : un nouvel ADR part du gabarit, porte l'en-tête et les sections Archgate, et figure dans `README.md` et `docs/architecture/arc42/09-decisions.md`.

## References

- [ADR 0044](0044-un-package-un-module.md) (règle A7 portée par Archgate), [ADR 0062](0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md).
- Fiche d'usage : `docs/developer/archgate.md`.
- Documentation Archgate : `reference/configuration` (`paths.adrs`), `reference/adr-schema`, `guides/writing-adrs`.
