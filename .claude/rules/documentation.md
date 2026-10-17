---
paths:
  - "docs/**/*.md"
  - "README.md"
  - "*.md"
---

# Documentation

- **Français**, concise, factuelle, sans promesse de capacité non qualifiée (principe capability-honest).
- **État courant** : `docs/project/governance/STATUS.md` (livré) et `docs/project/governance/ROADMAP.md` (progression) ; leurs affirmations sont gardées par `scripts/docs/check-current-docs.py` et `scripts/docs/product-facts.py --check`. Un fait chiffré (nombre de modules, version, seuil) s'écrit une seule fois puis se référence. Rejouer les deux scripts après toute modification de docs courantes.
- **Historique figé** : `docs/project/history/`, `docs/project/audits/archive/`, `openspec/changes/archive/` conservent l'état d'époque (anciens SHA, « à valider », anciens nombres). Ne pas les « mettre à jour » ; un lien cassé ou une erreur factuelle se corrige, avec confirmation.
- **ADR** (`docs/architecture/adr/`) : partir de `template.md`, Contexte / Décision / Conséquences, statut `Proposed | Accepted | Superseded | Rejected`, ajouté à l'index de `docs/architecture/adr/README.md` et à `docs/architecture/arc42/09-decisions.md`. Une décision Accepted ne se réécrit pas : compléter « Mise en œuvre » ou écrire l'ADR qui la remplace (commande `/minos:adr`).
- **Audits** : seul l'audit en cours reste à la racine de `docs/project/audits/` ; à la clôture, déplacement sous `archive/<date>/` par `git mv`, liens relatifs corrigés (commande `/minos:audit-archive`). Aucun lien d'un document suivi vers un dossier non suivi.
- **Liens** : relatifs, vérifiés (aucun lien cassé dans les fichiers modifiés). Références de code sous la forme `chemin:ligne`, relues au HEAD avant d'être citées.
- **Documentation utilisateur** (`docs/user/`) et **développeur** (`docs/developer/`) : une fonctionnalité qui change une sortie, une option ou un diagnostic met à jour la page correspondante dans la même PR.
- Mermaid pour les diagrammes ; `docs/architecture/diagrams/module-dependencies.md` est **généré** par `check-module-boundaries.py --write-doc` et `docs/developer/generated/product-facts.md` par `product-facts.py` (ne pas les éditer à la main).

## Organisation de `docs/` (figée depuis le 2026-10-17)

L'arborescence a été réorganisée parce que des pages de pilotage, de qualité et d'historique vivaient à la racine de `docs/` à côté de deux registres d'ADR. Ne pas recréer ce désordre : ranger chaque nouveau fichier à sa place, du premier coup.

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
    adr/               registre unique des ADR + template.md
  project/             piloter et auditer le projet
    governance/        STATUS.md, ROADMAP.md
    roadmap/           M<n>_EXECUTION.md, <chantier>-<AAAA-MM>/
    audits/            audit en cours <AAAA-MM-JJ>/, archive/
    research/          études et expériences
    history/           relevés figés (c0, m0, milestones, reconciliations, validation)
```

`docs/README.md` est le **seul** fichier à la racine de `docs/`, et `user/`, `developer/`, `architecture/`, `project/` les **seuls** dossiers de premier niveau suivis par Git. Un nouveau dossier de premier niveau, ou un nouveau sous-dossier de `project/`, est une décision de structure : la demander à l'utilisateur, ne pas la prendre en passant.

### Où ranger un nouveau fichier

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

Un même sujet a souvent **deux pages** : `user/` dit comment s'en servir, `developer/` comment c'est construit. Écrire deux pages plutôt qu'une page mixte.

### TOUJOURS

- Ranger une page dans **un seul** emplacement ; ne jamais la dupliquer.
- Ajouter la page à l'index de son espace dans le même changement : `README.md` du dossier, et `docs/README.md` si elle répond à un besoin de premier niveau.
- Écrire les liens **relatifs** entre pages de `docs/` ; citer un chemin depuis la racine du dépôt (`docs/developer/testing.md`) partout ailleurs — scripts, tests, workflows, `.claude/`, OpenSpec.
- Garder une page de `docs/user/` lisible sans le dépôt : elle parle du produit installé, de ses commandes et de sa configuration.

### JAMAIS

- Jamais de fichier à la racine de `docs/` autre que `README.md` (`STATUS.md`, `ROADMAP.md`, `TOOLCHAIN_POLICY.md` n'y reviennent pas).
- Jamais de retour d'un ancien dossier de premier niveau : `docs/adr/`, `docs/audit/`, `docs/history/`, `docs/roadmap/`, `docs/research/`, `docs/quality/`, `docs/releases/`, `docs/generated/`, `docs/m0/`.
- Jamais de second registre d'ADR, ni de second dossier d'audits.
- Jamais de contenu de pilotage (état d'un jalon, priorités, preuve datée) dans `docs/user/` ou `docs/developer/`.
- Jamais de document versionné sous `docs/rapport-supervisor/` : c'est un dossier local du superviseur de code, hors de cette arborescence.

### Déplacer ou renommer un fichier de `docs/`

Un chemin de documentation est **épinglé** : des gates `scripts/**/check-*.py` l'affirment littéralement (parfois construit `ROOT / "docs" / "x"`), des hooks `.claude/hooks/*.py`, des skills, des workflows et des tests le citent.

1. Déplacer avec `git mv`, jamais par suppression puis recréation.
2. Chercher l'ancien chemin dans **tout** le dépôt sous ses écritures `docs/x/`, `docs\x\` et `"docs" / "x"` (skill `minos-literal-gates`).
3. Recalculer les liens relatifs des pages déplacées **et** de celles qui pointent vers elles, relevés figés compris.
4. Rejouer `python .claude/scripts/run_gates.py --fast`, `check-current-docs.py` et `product-facts.py --check`.
5. Compléter la table de correspondance de `docs/project/README.md`.

Dans un relevé figé, les **liens** sont recalculés, la **prose** n'est pas réécrite : elle cite les chemins de sa date.
