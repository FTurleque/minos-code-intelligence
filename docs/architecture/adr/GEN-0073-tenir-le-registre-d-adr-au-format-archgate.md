---
id: GEN-0073
title: "Tenir le registre d'ADR au format Archgate, à l'emplacement arc42"
domain: general
rules: true
files: ["docs/architecture/adr/**", ".archgate/**"]
---

# Tenir le registre d'ADR au format Archgate, à l'emplacement arc42

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : décisions du propriétaire du 2026-10-23 (format Archgate, tri du registre, convention d'identifiant Archgate)
- **Remplace** : [ADR-0063](archive/0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)
- **Remplacé par** : —

## Context

Le registre des décisions de MINOS est `docs/architecture/adr/`, à l'emplacement que prévoit arc42 (section 9, `docs/architecture/arc42/09-decisions.md`). Archgate lit les ADR et exécute des règles TypeScript qui leur sont rattachées. Par défaut il lit `.archgate/adrs/` ; `.archgate/config.json` accepte `paths.adrs`. Son schéma impose un en-tête YAML en ligne 1 (`id`, `title`, `domain`, `rules`, `files` facultatif) ; sa convention nomme un ADR `{ID}-{slug}.md`, avec un `id` formé du préfixe du domaine et d'un numéro (`ARCH-001`) ; ses résumés pour agents (`archgate review-context`) lisent les sections intitulées « Decision » et « Do's and Don'ts ».

Les numéros des ADR MINOS sont uniques dans tout le registre et cités par des centaines de liens, par le code et par des gates : ils ne changent pas.

Options considérées pour l'identifiant :

- **`ADR-NNNN` pour tous** : numéro conservé, mais `id` et nom de fichier hors de la convention Archgate.
- **Préfixe du domaine et numérotation par domaine** (`ARCH-001`, `BE-001`) : convention Archgate stricte, au prix d'une renumérotation de tout le registre.
- **Préfixe du domaine et numéro unique conservé** (`ARCH-0068`) : convention Archgate `{ID}-{slug}.md`, numéros inchangés.

## Decision

1. `docs/architecture/adr/` est le registre unique. `.archgate/config.json` y pointe (`"paths": { "adrs": "docs/architecture/adr" }`) et déclare le domaine `security` (préfixe `SEC`) ; `.archgate/adrs/` ne contient qu'un `.gitkeep`, exigé par la CLI.
2. Un ADR s'appelle `{ID}-{slug}.md` et son `id` vaut `{PRÉFIXE}-{NNNN}` : préfixe du domaine (`architecture` → `ARCH`, `backend` → `BE`, `data` → `DATA`, `general` → `GEN`, `security` → `SEC`) et numéro à quatre chiffres, unique dans le registre, pris à la suite de l'index et jamais renuméroté. Le titre `#` reprend exactement le `title`.
3. Les sections portent les titres d'Archgate : `Context`, `Decision`, `Do's and Don'ts` (`Do`, `Don't`), `Consequences`, `Compliance and Enforcement`, `References` ; les autres sections (Mise en œuvre, Options, Écarts constatés…) gardent leur titre. Le texte est en français.
4. Une règle Archgate vit à côté de son ADR (`{ID}-{slug}.rules.ts`, `rules: true`), porte un identifiant en kebab-case anglais, lit les mêmes sources que le gate Python correspondant (qui reste celui de la CI) et a été vue en échec sur une mutation. Quand elle ne regarde qu'une partie du dépôt, l'en-tête la borne par `files`.
5. Un ADR ne s'écrit que pour une décision entre options réelles, durable et coûteuse à inverser (section « Quand écrire un ADR » de l'index). Un ADR amendé ne coexiste pas avec son amendement : amendement total, l'ancien passe `Superseded` ; amendement partiel, un ADR consolidé remplace les deux. Un ADR `Superseded`, `Rejected` ou reclassé part dans `archive/` (non lu par Archgate) avec un bandeau qui dit ce qui le remplace ; il n'est jamais supprimé.
6. Un écart entre une décision et le code s'écrit dans une section « Écarts constatés » datée, sans modifier la décision. Le gabarit est `gabarit/template.md` ; `archgate adr create` n'est pas utilisé (il numérote par domaine).

## Do's and Don'ts

### Do

- Copier `gabarit/template.md`, prendre le numéro suivant de l'index, nommer le fichier `{PRÉFIXE}-{NNNN}-{slug}.md` et écrire `id: {PRÉFIXE}-{NNNN}`.
- Garder l'en-tête YAML en ligne 1 et un titre `#` égal au `title`.
- Passer le filtre « Quand écrire un ADR » avant d'écrire ; sinon orienter le contenu vers l'historique, la roadmap, OpenSpec, une étude ou une règle.
- Remplacer par un ADR consolidé tout ADR modifié en partie, et archiver l'ancien avec son bandeau.
- Écrire « Do's and Don'ts » et « Compliance and Enforcement » à partir du seul texte de la décision et des contrôles qui existent.

### Don't

- Ne pas renuméroter un ADR ni réutiliser un numéro.
- Ne pas recréer de registre ou de pont dans `.archgate/adrs/`, ni utiliser `archgate adr create`.
- Ne pas laisser un ADR amendé actif à côté de son amendement.
- Ne pas modifier une décision acceptée pour la faire coïncider avec le code : consigner l'écart.
- Ne pas déclarer `rules: true` sans règle compagnon vue en échec.

## Consequences

### Positives

- Un seul fichier par décision, lu par les humains, l'index arc42, Archgate et les agents, aux noms de la convention Archgate.
- Les numéros historiques et tous leurs renvois restent valides ; le préfixe dit le domaine au premier regard.

### Négatives

- Les titres de section sont en anglais dans des documents en français.
- `README.md` reste dans le dossier sans en-tête : Archgate le signale comme non lu, ce qui interdit le mode `--strict`.
- Une règle bornée par `files` n'est exécutée par `archgate check` que si un fichier de son périmètre a changé (ou sur un arbre propre égal à la base).

### Risques

- Une évolution du schéma Archgate impose de reprendre les en-têtes : vérifier `archgate adr list` à chaque montée de version de la CLI.
- `archgate check` écrit `docs/architecture/rules.d.ts` : fichier généré, ignoré par Git.

## Compliance and Enforcement

- Règle Archgate `GEN-0073-tenir-le-registre-d-adr-au-format-archgate.rules.ts` : `adr-id-matches-file-name` (nom `{PRÉFIXE}-{NNNN}-…`, `id` égal, préfixe conforme au domaine), `adr-number-is-unique`, `adr-listed-in-index`, `adr-heading-matches-title`, `adr-has-archgate-sections` (avertissement), `archgate-adrs-holds-only-gitkeep`. Contrôles propres à Archgate : aucun gate Python ne lit les ADR.
- `archgate adr list` liste chaque ADR actif ; un ADR absent de la liste a un en-tête invalide.
- Revue : un nouvel ADR passe le filtre « Quand écrire un ADR », part du gabarit, figure dans `README.md` et dans `docs/architecture/arc42/09-decisions.md`.

## References

- [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) (règles A3, A7, A14 portées par Archgate).
- Index du registre et critères : [`README.md`](README.md) ; fiche d'usage : `docs/developer/archgate.md`.
- Documentation Archgate : `reference/configuration` (`paths.adrs`), `reference/adr-schema`, `guides/writing-adrs`, `guides/writing-rules`.
