# Design

## Contexte

Motivation : voir `proposal.md` (Why). Ce changement est documentaire, avec un auto-test optionnel (D1). Il touche le dossier de capacité, `docs/developer/quality-gates.md` et `.claude/rules/java-production.md`, plus éventuellement un auto-test du garde. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819), le 2026-11-25.

| Fait | Preuve |
|---|---|
| Le dossier écrit `asm["minos-app (assemblage, dépend des 12 autres)"]` | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:47` |
| La politique compte 14 modules ; `allowedDependencies['minos-app']` en liste 13 ; le POM de `minos-app` déclare 13 modules en dépendance, plus le `test-jar` de `minos-application` en portée test ; le garde imprime pour `minos-app` 13 dépendances | `scripts/architecture/module-boundaries.json` (lecture par `json`) ; `minos-app/pom.xml:17-32` ; sortie du garde, ligne `M21 module-boundary minos-app` |
| La liste des règles du dossier décrit A17 comme « tables Java et Python différentes », s'arrête à A18 et dit « Aucune règle ne porte les étiquettes A4 à A6 » | `frontieres-de-modules.md:79-87` |
| Le garde imprime « A17 ArchUnit policy: ModuleArchitectureTest reads the single source module-boundaries.json (14 modules), no policy table is copied », puis des lignes A19 et A20 | sortie de `python scripts/architecture/check-module-boundaries.py` (rc=0) |
| Les sources du garde portent exactement les étiquettes A2, A3, A7 à A20 ; aucune ne porte A1, A4, A5 ou A6 | `grep -ohE "\bA[0-9]{1,2}\b" scripts/architecture/check-module-boundaries.py scripts/architecture/module_boundary_*.py \| sort -u` |
| Le paragraphe sous la vue V1 décrit déjà A17 correctement (« la règle A17 refuse toute table recopiée ») : le dossier se contredit à 13 lignes d'écart | `frontieres-de-modules.md:73` contre `:86` |
| E3 : « Une exigence parle de quatre découvertes de service de production ; le test en affirme cinq », preuve citée dans `test_check_module_boundaries.py` | `frontieres-de-modules.md:209` |
| Le test qui affirme le compte est dans le fichier `_adapters` et affirme 6 ; le garde imprime « 6 production ServiceLoader.load calls » | `scripts/architecture/test_check_module_boundaries_adapters.py:228` ; sortie du garde, ligne A16 |
| La spec exige encore « les quatre découvertes de production passent un chargeur » | `openspec/specs/frontieres-de-modules/spec.md:339-352` (scénario « Dépôt réel ») |
| La question ouverte dit que le libellé de l'étape CI (« A3 packages, A7 reactor ») ne reflète plus le périmètre « A2 à A18 » | `frontieres-de-modules.md:219-220` |
| Les étapes du job `invariants` nomment chacune les règles de leur auto-test (« A3 packages, A7 reactor, A8 cycles, A15 unreferenced types », « A2 sources, A10, A14, A16, A18 to A20, storage isolation », etc.) ; l'étape du garde s'intitule « Maven module-boundary fitness gate » sans liste | `.github/workflows/pr-ci.yml:161-180` |
| `quality-gates.md` nomme les fichiers du garde (table `:63-73`) ; la phrase des auto-tests ne nomme que « les cinq fichiers `test_check_module_boundaries*.py` » | `docs/developer/quality-gates.md:75` |
| `test_gate_runner.py` est lancé par le job `invariants` (« Architecture gate runner self-test ») ; `quality-gates.md` ne le nomme nulle part | `.github/workflows/pr-ci.yml:179-180` ; `grep -c test_gate_runner docs/developer/quality-gates.md` = 0 |
| La liste « applique, sans compilation » de `quality-gates.md` a une puce pour A7, A2, A3, A8, A9, A10, A11 à A14, A17, A18, A15, A16 ; aucune pour A19 ni A20 (ni `ASSEMBLY_CLASSES`, ni `EMBEDDED_ADAPTER_CLASSES`, ni `INFRASTRUCTURE_API_USERS`) | `quality-gates.md:77-98` ; seule mention d'A19 et A20 : la table, `:68` |
| La dernière puce dit seulement que l'auto-test échoue si une `check_*` n'est référencée par aucun test ; rien sur `MainWiringTest` (câblage de `main()`, AUD-TST-13) | `quality-gates.md:99` |
| `.claude/rules/java-production.md` : « règles A2, A3, A7 à A16 » | `.claude/rules/java-production.md:11` |
| `check-capability-dossiers.py` : 22 dossiers cohérents ; il contrôle les titres d'exigence, les statuts et les chemins de preuve, pas la prose ni les listes de règles | `python scripts/docs/check-capability-dossiers.py` (rc=0) ; docstring `:1-27` |
| Gates littéraux sur les fichiers touchés : `check-current-docs.py` mute `quality-gates.md` (ruleset) ; `check-minos-01.py` exige la chaîne `check-minos-01.py` dans `quality-gates.md` ; aucun gate ne lit le dossier de capacité hors `check-capability-dossiers.py` ; aucun gate ne lit `.claude/rules/java-production.md` | `scripts/docs/check-current-docs.py:265` ; `scripts/remediation/check-minos-01.py:188-194` ; `grep -rln "frontieres-de-modules.md\|java-production.md" scripts` |
| Le sprint 10 a écarté tout contrôle automatique (D2 A) ; l'option B (A9 étendue pour exiger que `quality-gates.md` nomme chaque fichier du garde) était renvoyée à un changement distinct, après AUD-TST-13 | `openspec/changes/archive/2026-11-24-decrire-le-garde-de-frontieres-decoupe/design.md:78-86` |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit cite la preuve d'E3 dans `test_check_module_boundaries_adapters.py:228` ; le dossier la situe dans `test_check_module_boundaries.py`. Le fichier est donc faux en plus du compte | `frontieres-de-modules.md:209` | `test_check_module_boundaries_adapters.py:228` | E3 clos ; la spec cesse d'écrire un nombre (exigence modifiée) |
| L'action de l'audit propose « A2 à A20 » pour la question ouverte. Or la question est périmée : les étapes CI nomment déjà leurs règles, A18 à A20 compris. Écrire « A2 à A20 » prolongerait une question sans objet | audit, action | `pr-ci.yml:164-180` | retirer la question (D2) |
| L'audit ne relève pas : A1 absente de « Aucune règle ne porte les étiquettes A4 à A6 » ; l'absence de description d'A19 et d'A20 dans la liste de `quality-gates.md` ; la puce `:99` qui ignore `MainWiringTest` ; `.claude/rules/java-production.md:11` (« A7 à A16 ») | — | état vérifié ci-dessus | corrigés dans ce changement |
| L'audit propose pour `:47` « 13 autres, ou ne plus écrire de nombre ». Le nombre est déjà écrit ailleurs (politique, POM, document généré) : le recopier recrée la dérive au prochain module | audit, action | `.claude/rules/documentation.md` (« un fait chiffré s'écrit une seule fois, puis se référence ») | D3 : aucun nombre dérivé dans le dossier |
| La spec exige « quatre » découvertes : écart entre la spec courante et le code (6), que la seule correction du dossier laisserait | `spec.md:339-352` | garde, ligne A16 | exigence modifiée sans nombre |
| Hors dossier : STATUS et ROADMAP écrivent « 154 cas » pour l'auto-test du garde ; 314 méthodes `test_` au HEAD | `docs/project/governance/STATUS.md:19`, `ROADMAP.md:23` | `grep -cE "^    def test_"` sur les cinq fichiers | signalé au coordinateur (`/minos:docs-sync`), hors périmètre |

## Exigences couvertes

- frontieres-de-modules › La documentation des gates nomme chaque fichier du garde de frontières — exigence modifiée (auto-tests nommés, règles décrites, contrôle automatique selon D1).
- frontieres-de-modules › Aucune découverte de service de production n'omet le chargeur — exigence modifiée (sans nombre).
- frontieres-de-modules › Le dossier de la capacité nomme chaque règle du garde — exigence ajoutée.

## Décisions

### D1 — Contrôler automatiquement les étiquettes de règles et les fichiers nommés [Proposition, recommandation : B]

La même famille de libellés a dérivé deux sprints de suite : AUD-ARC-12 au sprint 10, AUD-ARC-16 au sprint 11. Les commandes `git grep` et `grep -c` de la spec ne sont rejouées qu'à la clôture d'un changement, et seulement par celui qui touche la page.

- **A, statu quo** : corrections à la main ; la récidive reste tenue par la revue, comme au sprint 10 (D2 A). Coût nul ; le constat reviendra à la prochaine règle (le changement n°1 du sprint peut en ajouter).
- **B (recommandée), auto-test léger sur le dépôt réel** dans `scripts/architecture/test_check_module_boundaries_documents.py`, sans nouveau gate, sans étape CI ni nouvelle étiquette de règle. Le test vérifie trois choses :
  1. les étiquettes `A<n>` portées par les sources du garde (`check-module-boundaries.py` et `module_boundary_*.py`) sont exactement celles du paragraphe « Règles du garde » du dossier ;
  2. chacune a une puce dans la liste « applique, sans compilation » de `quality-gates.md` ;
  3. `quality-gates.md` nomme chaque fichier du garde : `check-module-boundaries.py`, `module-boundaries.json`, `gate_runner.py`, `module_boundaries_testing.py`, chaque `module_boundary_*.py`, chaque `test_check_module_boundaries*.py` et `test_gate_runner.py`, découverts par glob et non recopiés.

  Le contrôle est structurel : il porte sur des identifiants (étiquettes, noms de fichiers), pas sur des phrases, ce qu'admet l'ADR 0043. Il est fail-closed : un paragraphe introuvable, un ensemble d'étiquettes vide ou une page absente font échouer le test. Une règle nouvelle, comme un module ou un auto-test nouveau, rougit tant que la documentation ne la nomme pas. C'est l'option B du sprint 10, élargie aux étiquettes ; la condition qu'elle posait (« après AUD-TST-13 ») est remplie.
- **C, ne plus écrire de liste** : le dossier renvoie à `quality-gates.md` pour les règles. Cela supprime une copie, mais `quality-gates.md` reste non gardé, et le dossier perd la lecture « par ce qu'elles refusent ».

Pourquoi B plutôt qu'une règle du garde (extension d'A9) : une règle de `main()` lirait une page de `docs/developer/` à chaque exécution du garde et ajouterait une ligne au rapport et une fonction `check_*`. Le câblage de cette fonction serait alors exigé par `MainWiringTest`. Un auto-test sur le dépôt réel suffit, puisque le job `invariants` lance déjà ce fichier.

Si A est retenu, les scénarios des exigences se vérifient par les commandes `grep` qu'ils citent, rejouées en Clôture, et la tâche 2.1 est retirée.

### D2 — Retirer la question ouverte périmée plutôt que la corriger [Proposition, recommandation]

Les cinq étapes d'auto-test nomment leurs règles et l'étape du garde n'en nomme aucune : aucun libellé ne prétend plus couvrir « A3, A7 » seulement. La question est retirée. Alternative : écrire « A2 à A20 », ce que propose l'audit ; cela garde une question sans objet.

### D3 — Aucun nombre dérivé dans le dossier [Proposition, recommandation]

`minos-app` dépend de « tous les autres modules » ; E3 est clos sans compte ; la spec ne compte plus les découvertes. Les nombres restent là où ils sont calculés : la sortie du garde, `module-boundaries.json` et le document généré `module-dependencies.md`. La règle de documentation le prescrit déjà. La docstring du garde (`check-module-boundaries.py:4-12`, cliquets « A8, A10 to A15, A18 ») est un commentaire de code du fichier que modifie le changement n°1 : elle y est corrigée si n°1 passe avant ; sinon, la tâche 1.4 la corrige ici, en anglais (`check-comment-language.py`).

## Windows et Linux

Changement documentaire et auto-test Python de la bibliothèque standard : chemins par `pathlib`, lecture UTF-8 explicite, aucune branche de plateforme. Le job `invariants` tourne sous Ubuntu ; l'auto-test se rejoue aussi sur le poste Windows.

## Vérification

- Rouge : au HEAD, l'auto-test D1 (écrit avant les corrections) échoue. Il doit nommer A19 et A20 absentes du dossier et de la liste de `quality-gates.md`, et `test_gate_runner.py` non nommé. Les commandes `grep` des scénarios rendent les lignes périmées.
- Correction : l'auto-test passe ; mutations sur copie (A19 retirée du dossier, `module_boundary_extra.py` ajouté sans doc, étiquette `A21` ajoutée à un message du garde) : chacune le fait rougir.
- Gates : voir `proposal.md`, Impact.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve exécutée | Preuve manquante |
|---|---|---|---|---|---|
| AUD-ARC-16 | frontieres-de-modules › Le dossier de la capacité nomme chaque règle du garde | D1, D2, D3 | 1.1-1.3, 2.1-2.3 | 2026-11-25 : relevé rouge au HEAD (1.1) ; `GuardDocumentationTest` (`test_check_module_boundaries_documents.py`) rouge au HEAD en nommant `A19`, `A20` et `test_gate_runner.py`, vert après 1.2-1.3 ; six mutations sur copie `git archive` (A19 retirée du dossier, `module_boundary_x.py` ajouté, mention de `test_gate_runner.py` retirée, puce A20 retirée, `A21` dans un message, amorce réécrite) rendent chacune le code 1 en nommant l'élément ; garde, six auto-tests, `check-ci-wiring.py`, `check-comment-language.py`, `check-capability-dossiers.py` verts | `run_gates.py` complet en CI |
| AUD-ARC-16 | frontieres-de-modules › La documentation des gates nomme chaque fichier du garde de frontières | D1 | 1.1, 1.2, 2.1-2.3 | `test_quality_gates_names_every_file_of_the_guard` et `test_quality_gates_has_a_bullet_for_every_rule_of_the_guard` verts ; boucle `grep -c` à ≥ 1 pour chaque fichier ; `check-current-docs.py` et `check-minos-01.py` verts | aucune |
| AUD-ARC-16 | frontieres-de-modules › Aucune découverte de service de production n'omet le chargeur | D3 | 1.3 | garde vert ; `test_check_module_boundaries_adapters.py` vert (dont `test_the_real_tree_passes_a_loader_at_every_site`) ; E3 clos sans compte dans le dossier | spec courante sans nombre : à l'archivage |
