---
paths:
  - "docs/**/*.md"
  - "README.md"
  - "*.md"
---

# Règles — Documentation et organisation de `docs/`

La documentation s'écrit en **français**, concise et factuelle, sans promesse de capacité non qualifiée
(principe capability-honest). L'arborescence de `docs/` est **figée** depuis le 17/10/2026 : elle a été
réorganisée parce que des pages de pilotage, de qualité et d'historique vivaient à la racine de `docs/` à côté
de deux registres d'ADR. Ne pas recréer ce désordre : ranger chaque nouveau fichier à sa place, du premier coup.

## L'arborescence

```text
docs/
  README.md            aiguillage, rien d'autre
  user/                utiliser MINOS
    release-notes/     <version>.md
  developer/           construire, modifier, étendre MINOS
    TOOLCHAIN_POLICY.md
    quality/           audit de code (SpotBugs, PIT, couverture)
    generated/         documents générés par script, jamais édités à la main
  architecture/        dossier arc42, overview, diagrams/, quality/, risks/
    adr/               registre unique des ADR (format Archgate) + gabarit/template.md
  project/             piloter et auditer le projet
    governance/        STATUS.md, ROADMAP.md
    roadmap/           M<n>_EXECUTION.md, <chantier>-<AAAA-MM>/
    audits/            audit en cours <AAAA-MM-JJ>/, archive/
    research/          études et expériences
    history/           relevés figés (c0, m0, milestones, reconciliations, validation)
```

`docs/README.md` est le **seul** fichier à la racine de `docs/`, et `user/`, `developer/`, `architecture/`,
`project/` les **seuls** dossiers de premier niveau suivis par Git. Un nouveau dossier de premier niveau, ou un
nouveau sous-dossier de `project/`, est une décision de structure : la demander à l'utilisateur, ne pas la
prendre en passant.

## Où ranger un nouveau fichier

Se poser la question du **lecteur**, pas du sujet :

| Le lecteur veut… | Emplacement |
|---|---|
| installer, configurer, lancer une commande, dépanner | `docs/user/` |
| savoir ce qui change dans une version | `docs/user/release-notes/` |
| compiler, tester, qualifier, comprendre un module ou une surface | `docs/developer/` |
| lire un rapport d'analyse statique, de mutation ou de couverture | `docs/developer/quality/` |
| comprendre l'architecture d'ensemble, un scénario qualité, un risque | `docs/architecture/` |
| retrouver ou écrire une décision structurante | `docs/architecture/adr/` |
| connaître l'état livré et la progression | `docs/project/governance/` |
| lire le plan d'un jalon ou d'un chantier | `docs/project/roadmap/` |
| lire un audit | `docs/project/audits/` |
| lire une étude ou une expérience | `docs/project/research/` |

Un même sujet a souvent **deux pages** : `user/` dit comment s'en servir, `developer/` comment c'est construit.
Écrire deux pages plutôt qu'une page mixte.

## État courant et documents générés

- `docs/project/governance/STATUS.md` (livré) et `docs/project/governance/ROADMAP.md` (progression) portent
  l'état courant ; leurs affirmations sont gardées par `scripts/docs/check-current-docs.py` et
  `scripts/docs/product-facts.py --check`. Rejouer les deux scripts après toute modification de docs courantes
- Un fait chiffré (nombre de modules, version, seuil) s'écrit une seule fois, puis se référence
- Une fonctionnalité qui change une sortie, une option ou un diagnostic met à jour la page correspondante de
  `docs/user/` ou `docs/developer/` dans la même PR
- Les diagrammes s'écrivent en Mermaid. `docs/architecture/diagrams/module-dependencies.md` est **généré** par
  `check-module-boundaries.py --write-doc`, et `docs/developer/generated/product-facts.md` par
  `product-facts.py` : ne pas les éditer à la main

## ADR

- Registre unique : `docs/architecture/adr/`, au format et selon la convention Archgate (GEN-0073)
- Partir de `gabarit/template.md` ; nommer le fichier `{PRÉFIXE}-{NNNN}-{slug}.md` (préfixe du domaine, numéro
  unique jamais renuméroté)
- En-tête YAML en ligne 1 (`id: {PRÉFIXE}-{NNNN}`, `title`, `domain`, `rules`) ; titre `#` égal au `title` ;
  sections `Context` / `Decision` / `Do's and Don'ts` / `Consequences` / `Compliance and Enforcement` /
  `References`, texte en français ; statut `Proposed | Accepted | Superseded | Rejected` dans le corps
- Ajouter l'ADR à l'index de `docs/architecture/adr/README.md` et à `docs/architecture/arc42/09-decisions.md`
- Une décision `Accepted` ne se réécrit pas : compléter « Mise en œuvre », consigner un écart dans « Écarts
  constatés », ou écrire l'ADR qui la remplace (commande `/minos:adr`)
- Un ADR amendé ne coexiste pas avec son amendement : amendement total → l'ancien passe `Superseded` ;
  amendement partiel → un ADR consolidé remplace les deux
- Les ADR `Superseded`, `Rejected` ou reclassés vivent dans `docs/architecture/adr/archive/` (non lu par
  Archgate), jamais supprimés

### Quand écrire un ADR

Voir la section « Quand écrire un ADR » de `docs/architecture/adr/README.md`. Un ADR se justifie seulement pour
un choix entre options réelles qui contraint durablement plusieurs modules, une surface publique, un format
persisté, un modèle de confiance ou la toolchain, et dont l'inversion coûte cher.

Jamais pour un état de jalon, une preuve datée, un plan, une spécification de comportement, une étude, une
convention locale ou un correctif : ils vont respectivement dans `docs/project/history/`,
`docs/project/roadmap/`, OpenSpec, `docs/project/research/`, `.claude/rules/` ou la PR.

## Audits

- Seul l'audit en cours reste à la racine de `docs/project/audits/`
- À la clôture, il passe sous `archive/<date>/` par `git mv`, liens relatifs corrigés (commande
  `/minos:audit-archive`)
- Aucun lien d'un document suivi vers un dossier non suivi

## TOUJOURS

- Ranger une page dans **un seul** emplacement ; ne jamais la dupliquer
- Ajouter la page à l'index de son espace dans le même changement : `README.md` du dossier, et
  `docs/README.md` si elle répond à un besoin de premier niveau
- Écrire les liens **relatifs** entre pages de `docs/`, et les vérifier (aucun lien cassé dans les fichiers
  modifiés) ; citer un chemin depuis la racine du dépôt (`docs/developer/testing.md`) partout ailleurs —
  scripts, tests, workflows, `.claude/`, OpenSpec
- Citer le code sous la forme `chemin:ligne`, relu au HEAD avant d'être cité
- Garder une page de `docs/user/` **lisible sans le dépôt** : elle parle du produit installé, de ses commandes
  et de sa configuration

## JAMAIS

- Jamais de fichier à la racine de `docs/` autre que `README.md` (`STATUS.md`, `ROADMAP.md`,
  `TOOLCHAIN_POLICY.md` n'y reviennent pas)
- Jamais de retour d'un ancien dossier de premier niveau : `docs/adr/`, `docs/audit/`, `docs/history/`,
  `docs/roadmap/`, `docs/research/`, `docs/quality/`, `docs/releases/`, `docs/generated/`, `docs/m0/`
- Jamais de second registre d'ADR, ni de second dossier d'audits ; jamais `archgate adr create`, jamais de
  registre ni de pont dans `.archgate/adrs/`
- Jamais de contenu de pilotage (état d'un jalon, priorités, preuve datée) dans `docs/user/` ou
  `docs/developer/`
- Jamais de document versionné sous `docs/project/rapport-supervisor/` : c'est le dossier local du superviseur
  de code (rapports et état de session, auto-ignoré), fixé par `log_subdir` dans
  `.claude/supervisor.config.json`

## Déplacer ou renommer un fichier de `docs/`

Un chemin de documentation est **épinglé** : des gates `scripts/**/check-*.py` l'affirment littéralement
(parfois construit `ROOT / "docs" / "x"`), des hooks `.claude/hooks/*.py`, des skills, des workflows et des
tests le citent.

1. Déplacer avec `git mv`, jamais par suppression puis recréation.
2. Chercher l'ancien chemin dans **tout** le dépôt sous ses écritures `docs/x/`, `docs\x\` et
   `"docs" / "x"` (skill `minos-literal-gates`).
3. Recalculer les liens relatifs des pages déplacées **et** de celles qui pointent vers elles, relevés figés
   compris.
4. Rejouer `python .claude/scripts/run_gates.py --fast`, `check-current-docs.py` et
   `product-facts.py --check`.
5. Compléter la table de correspondance de `docs/project/README.md`.

## Relevés figés — liens oui, prose non

`docs/project/history/`, `docs/project/audits/archive/` et `openspec/changes/archive/` conservent l'état de
leur époque (anciens SHA, « à valider », anciens nombres). Ne pas les « mettre à jour » : un lien cassé ou une
erreur factuelle se corrige, avec confirmation.

Lors d'un déplacement, leurs **liens** sont recalculés, leur **prose** n'est pas réécrite : elle cite les
chemins de sa date.
