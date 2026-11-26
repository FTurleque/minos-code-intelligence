# Tasks

Ordre de fusion conseillé avec les autres changements du sprint 11 qui touchent la capacité `frontieres-de-modules` : n°8 `deriver-les-modules-de-regles-du-garde`, puis n°1 `tenir-le-contrat-de-la-facade-par-le-garde`, puis ce changement (n°4), en dernier. La documentation décrit alors le garde final : une règle ou une table ajoutée par n°1 entre dans le dossier et dans `quality-gates.md`, et l'auto-test 2.1 la vérifie. Si n°3 `aligner-la-prose-de-la-facade-apres-le-sprint-10` modifie la vue V2 du dossier, le second fusionné rebase le dossier ; les deux changements ne touchent pas les mêmes lignes.

Toutes les mutations se font sur une copie du dépôt (scratchpad), jamais sur le dépôt.

## 1. Libellés périmés (AUD-ARC-16)

- [x] 1.1 AUD-ARC-16 — Rouge : relever au HEAD les lignes périmées par `grep -nE "12 autres|A2 à A18|tables Java et Python|en affirme cinq|A4 à A6" docs/architecture/diagrams/capacites/frontieres-de-modules.md`, `grep -c test_gate_runner docs/developer/quality-gates.md` (0) et `grep -c "ASSEMBLY_CLASSES\|EMBEDDED_ADAPTER_CLASSES" docs/developer/quality-gates.md` (0), puis `grep -n "A7 à A16" .claude/rules/java-production.md`. Consigner la sortie dans la PR. Exigences : frontieres-de-modules › Le dossier de la capacité nomme chaque règle du garde ; › La documentation des gates nomme chaque fichier du garde de frontières.
- [x] 1.2 AUD-ARC-16 — Correction du dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` :
  - `:47` : « assemblage, dépend de tous les autres modules », sans nombre (D3) ;
  - `:79-87` : A17 « table de politique recopiée dans `ModuleArchitectureTest` » ; ajouter A19 (classe d'adaptateur embarquée nommée hors de sa famille, API d'infrastructure du JDK nommée par un fichier non listé) et A20 (classe de production de `minos-app` hors liste) ; « Aucune règle ne porte les étiquettes A1 et A4 à A6 » ;
  - E3 (`:209`) : clos, sans compte, preuve `scripts/architecture/test_check_module_boundaries_adapters.py#test_the_real_tree_passes_a_loader_at_every_site` ;
  - « Questions ouvertes » (`:217-220`) : question retirée (D2), section « Aucune » ;
  - en-tête `:4` : commit du relevé mis à jour.

  Vérifier : la commande `grep -nE` de 1.1 (sans `A4 à A6`) ne rend rien ; `python scripts/docs/check-capability-dossiers.py` vert.
- [x] 1.3 AUD-ARC-16 — Correction de `docs/developer/quality-gates.md` et des règles d'agent :
  - `:75` nomme `test_gate_runner.py` à côté des cinq `test_check_module_boundaries*.py` ;
  - deux puces décrivent A19 (`INFRASTRUCTURE_API_USERS`, `EMBEDDED_ADAPTER_CLASSES`) et A20 (`ASSEMBLY_CLASSES`) dans la liste « applique, sans compilation » ;
  - la puce `:99` dit aussi ce que prouve `MainWiringTest` (chaque `check_*` exécutée par `main()`, son erreur rend `main()` rouge) ;
  - `.claude/rules/java-production.md:11` dit « règles A2, A3, A7 à A20 » ou renvoie à `quality-gates.md` sans liste.

  La spec ne contient plus de nombre de découvertes : exigence « Aucune découverte de service de production n'omet le chargeur », appliquée à l'archivage. Vérifier : la boucle `grep -c` du scénario « Chaque fichier du garde est nommé » donne ≥ 1 partout ; `python scripts/docs/check-current-docs.py` et `python scripts/remediation/check-minos-01.py` verts (ils lisent `quality-gates.md`).
- [x] 1.4 AUD-ARC-16 — Seulement si n°1 est fusionné sans l'avoir fait (D3) : la docstring de `scripts/architecture/check-module-boundaries.py:4-12` range A19 et A20 parmi les cliquets et rattache A16 au bon module, en anglais. Vérifier : `python scripts/quality/check-comment-language.py` et `python scripts/architecture/check-module-boundaries.py` verts.

## 2. Auto-test des étiquettes et des fichiers nommés (AUD-ARC-16, D1 option B)

À retirer si le propriétaire retient D1 A (statu quo) : les scénarios se vérifient alors par leurs commandes `grep`, rejouées en 3.3.

- [x] 2.1 AUD-ARC-16 — Rouge : avant 1.2 et 1.3 (ou sur une copie du HEAD), écrire dans `scripts/architecture/test_check_module_boundaries_documents.py` un cas sur le dépôt réel (bibliothèque standard, `pathlib`, UTF-8). Il compare l'ensemble des étiquettes `A<n>` des sources du garde (`check-module-boundaries.py`, `module_boundary_*.py` par glob) à celui du paragraphe « Règles du garde » du dossier, puis à celui des puces de `quality-gates.md`. Il vérifie enfin que `quality-gates.md` nomme chaque fichier du garde découvert par glob (`module_boundary_*.py`, `test_check_module_boundaries*.py`, `test_gate_runner.py`, `gate_runner.py`, `module_boundaries_testing.py`, `module-boundaries.json`). Fail-closed : un paragraphe introuvable ou un ensemble vide échouent. Vérifier : au HEAD, le cas échoue en nommant `A19`, `A20` et `test_gate_runner.py`. Exigences : les deux exigences de 1.1.
- [x] 2.2 AUD-ARC-16 — Correction : après 1.2 et 1.3, le cas passe. Mutations sur copie, une à la fois : `A19` retirée du dossier ; `module_boundary_x.py` ajouté ; mention de `test_gate_runner.py` retirée ; puce A20 retirée de `quality-gates.md` ; étiquette `A21` ajoutée à un message d'un module de règles ; amorce « Règles du garde » réécrite. Vérifier : chacune rend le code 1 en nommant l'élément ; consigner le tableau dans la PR.
- [x] 2.3 AUD-ARC-16 — Gates : `python scripts/architecture/check-module-boundaries.py`, les cinq `test_check_module_boundaries*.py`, `python scripts/architecture/test_gate_runner.py`, `python scripts/quality/check-ci-wiring.py` (aucun fichier `test_*.py` nouveau), `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.

## 3. Clôture

- [x] 3.1 AUD-ARC-16 — Mettre à jour la matrice de traçabilité du design en nommant les preuves exécutées et celles qui manquent ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 3.2 AUD-ARC-16 — À l'archivage, ajouter au dossier la ligne de traçabilité de l'exigence ajoutée « Le dossier de la capacité nomme chaque règle du garde » (preuve : le cas de 2.1). Faire passer à `couverte` les deux exigences modifiées si 2.1 est livré (sinon `partielle`, « commande `grep`, aucun gate »). Vérifier : `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 3.3 AUD-ARC-16 — Valider les spécifications ; vérifier : `openspec validate --all --strict` vert ; rejouer les commandes `grep` des scénarios.
- [ ] 3.4 AUD-ARC-16 — Lister les documents à mettre à jour avant archivage : dossier de capacité (1.2, 3.2), `docs/developer/quality-gates.md` (1.3), `.claude/rules/java-production.md` (1.3), ligne AUD-ARC-16 du suivi de l'audit `docs/project/audits/2026-11-15/` (coordinateur, après fusion). STATUS et ROADMAP ne citent pas le constat ; leur compte « 154 cas » est signalé à part (`/minos:docs-sync`). Vérifier : chacun est fait ou nommé comme reste dans la PR.
