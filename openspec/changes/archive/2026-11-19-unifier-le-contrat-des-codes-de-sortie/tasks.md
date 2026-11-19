# Tasks

Ordre de fusion conseillé : premier changement du sprint 7 (large et mécanique) ; les PR ouvertes sur `minos-cli` ou `minos-app` se rebasent par la même substitution.

## 1. Définition unique (AUD-ARC-06)

- [x] 1.1 AUD-ARC-06 — Rouge : écrire dans `minos-cli` un test qui fige les quatre valeurs de `CliExitCodes` (0, 1, 2, 3) et qui exige, par réflexion, que `FindSymbolCommand` ne déclare aucun champ `SUCCESS`, `EXECUTION_ERROR`, `USAGE_ERROR` ni `PARTIAL_RESULT`. Exigence : surfaces-publiques › Les codes de sortie de la CLI ont une seule définition. Vérifier : le test échoue sur les alias.
- [x] 1.2 AUD-ARC-06 — Correction : remplacer `FindSymbolCommand.<code>` par `CliExitCodes.<code>` dans les 25 fichiers de production de `minos-cli`, les 3 de `minos-app` et les 31 fichiers de test ; supprimer les quatre alias et qualifier le `return SUCCESS;` non qualifié de `FindSymbolCommand.java:58` (`CliExitCodes.SUCCESS`) ; sites de `minos-app` : `ExplorerLaunchRouteProvider.java:101`, `McpBackendRouter.java:78`, `DockerMcpTransport.java:83` ; réécrire en anglais la Javadoc de `CliExitCodes` (contrat ARCH-0016, sans renvoi à une commande) et le commentaire déplacé de `PARTIAL_RESULT`. Vérifier : la recherche `FindSymbolCommand\.(SUCCESS|EXECUTION_ERROR|USAGE_ERROR|PARTIAL_RESULT)` dans `minos-*/src` est vide ; test 1.1 vert ; `python scripts/quality/check-comment-language.py` (abaisser `scripts/quality/comment-language.json` si le maximum de `minos-cli` baisse) ; `python scripts/architecture/check-module-boundaries.py` (A8).
- [x] 1.3 AUD-ARC-06 — Preuve : `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` ; chercher `FindSymbolCommand` dans `docs/user/`, `docs/developer/` et `docs/architecture/` (hors `adr/archive/`) et corriger toute page qui le cite pour les codes de sortie, dont `docs/architecture/arc42/06-vue-execution.md:119` et `docs/architecture/diagrams/seq-erreur-provider.md:35`. Vérifier : code 0 ; `python .claude/scripts/run_gates.py --fast` vert ; `python scripts/docs/check-current-docs.py` et les liens relatifs des pages modifiées.

## 2. Parité de la surface d'exploration (AUD-ARC-06)

- [x] 2.1 AUD-ARC-06 — Rouge puis correction : écrire `ExitCodeContractParityTest` dans `minos-app` — fonction de comparaison (nom → valeur) testée sur une table divergente et sur une table portant un code inconnu (les deux refusés en nommant la constante), puis appliquée aux `public static final int` d'`ExplorerSession` et de `CliExitCodes`. Exigence : surfaces-publiques › Les codes de sortie de la surface d'exploration suivent ceux de la CLI. Vérifier : `./mvnw -B -ntp -pl minos-app -am test "-Dtest=ExitCodeContractParityTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert ; les deux cas de refus passent.
- [x] 2.2 AUD-ARC-06 — Documentation : ajouter les deux exigences au dossier `docs/architecture/diagrams/capacites/surfaces-publiques.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`. — lignes de traçabilité ajoutées à l'archivage, `check-capability-dossiers.py` vert.
  - Report à l'archivage : `check-capability-dossiers.py` refuse une ligne de traçabilité qui nomme une exigence absente de `openspec/specs/surfaces-publiques/spec.md` ; les deux lignes s'ajoutent avec l'archivage du changement (preuves : `CliExitCodesTest`, `ExitCodeContractParityTest`).

## 3. Clôture

- [x] 3.1 AUD-ARC-06 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [x] 3.2 AUD-ARC-06 — Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [x] 3.3 AUD-ARC-06 — Valider les spécifications ; vérifier par `openspec validate --all --strict`.
- [x] 3.4 AUD-ARC-06 — Lister les documents à mettre à jour avant archivage (STATUS et ROADMAP si le constat y figure, `docs/user/cli.md`, suivi de l'audit) ; nommer comme reste la constante du plugin IntelliJ, hors périmètre.
  - Avant archivage : deux lignes du dossier `surfaces-publiques.md` (tâche 2.2), ligne AUD-ARC-06 de `docs/project/audits/2026-11-15/SUIVI.md` ; STATUS, ROADMAP et `docs/user/cli.md` ne citent ni le constat ni `FindSymbolCommand` (rien à modifier). Reste : `PARTIAL_RESULT_EXIT_CODE` du plugin IntelliJ.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive unifier-le-contrat-des-codes-de-sortie -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
