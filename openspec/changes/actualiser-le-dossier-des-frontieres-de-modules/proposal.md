# Proposal

## Why

Constat **AUD-ARC-16** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouvert le 2026-11-25). Après la PR #810 (changement `decrire-le-garde-de-frontieres-decoupe`, sprint 10), le dossier durable de la capacité `frontieres-de-modules` et la page `docs/developer/quality-gates.md` décrivent encore un garde plus ancien que celui du HEAD :

- le dossier écrit que `minos-app` « dépend des 12 autres » modules, alors que la politique et le POM en comptent 13 ;
- il décrit A17 comme une comparaison de « tables Java et Python différentes », alors qu'A17 refuse toute table recopiée dans `ModuleArchitectureTest` ;
- sa liste des règles s'arrête à A18 alors que le garde porte A19 et A20 ;
- son écart E3 parle de « cinq » découvertes de service affirmées par le test, alors que le test en affirme six, et la spec en exige encore « quatre » ;
- sa question ouverte cite un libellé d'étape CI qui n'existe plus ;
- `quality-gates.md` ne nomme pas `test_gate_runner.py` et ne décrit ni A19 ni A20.

La revérification au HEAD ajoute trois libellés du même genre que l'audit n'a pas relevés (design, « Écarts »). Le sprint 10 avait choisi de ne poser aucun contrôle automatique (décision D2 A de `decrire-le-garde-de-frontieres-decoupe`) : la récidive, un sprint plus tard, montre que la relecture ne suffit pas pour des listes de règles qui évoluent à chaque sprint.

## What Changes

- Le dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` est corrigé : `minos-app` dépend de « tous les autres modules », sans nombre ; A17 est décrite comme le refus d'une table recopiée ; A19 et A20 sont ajoutées à la liste des règles ; A1 rejoint les étiquettes absentes. L'écart E3 n'écrit plus de compte et est clos. La question ouverte périmée sur le libellé de l'étape CI est retirée.
- `docs/developer/quality-gates.md` nomme `test_gate_runner.py` parmi les auto-tests. Il décrit A19 (adaptateurs embarqués et API d'infrastructure du JDK) et A20 (classes de production de `minos-app`), et dit ce que prouve `MainWiringTest`.
- `.claude/rules/java-production.md` cesse d'arrêter la liste des règles du garde à A16.
- Selon la recommandation D1 du design, un auto-test sur le dépôt réel est ajouté à `test_check_module_boundaries_documents.py`. Il confronte les étiquettes de règles citées par le dossier et par `quality-gates.md` à celles que portent les sources du garde, et vérifie que `quality-gates.md` nomme chaque fichier du garde, auto-tests compris. L'exigence de la spec « La documentation des gates nomme chaque fichier du garde de frontières » est modifiée en conséquence.
- L'exigence « Aucune découverte de service de production n'omet le chargeur » n'écrit plus de nombre de découvertes : ce nombre change à chaque ajout de service.

Aucune règle du garde, aucun code Java, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences modifiées (la documentation des gates nomme chaque fichier du garde ; aucune découverte de service n'omet le chargeur) et une exigence ajoutée (le dossier de la capacité nomme chaque règle du garde).

## Hors périmètre

- Les règles du garde et leur sortie : A11, le `test-jar` (changement n°1 du sprint, `tenir-le-contrat-de-la-facade-par-le-garde`) et la liste des modules de règles de l'auto-test (n°8, `deriver-les-modules-de-regles-du-garde`).
- La vue V2 du dossier (façade `MinosApplication`) et la prose de la façade : changement n°3, `aligner-la-prose-de-la-facade-apres-le-sprint-10`.
- Les écarts E1, E2, E4 à E8 du dossier : ils portent sur des exigences de la spec et restent ouverts.
- Le compte « 154 cas » de `docs/project/governance/STATUS.md:19` et de `ROADMAP.md:23` (314 méthodes `test_` dans les cinq auto-tests au HEAD) : il relève de la synchronisation de STATUS et de ROADMAP (`/minos:docs-sync`), signalé au coordinateur.
- Les ADR : ARCH-0068 (§ « Compliance and Enforcement ») ne liste pas A17 à A20, qui relèvent d'ARCH-0058 et d'ARCH-0087 ; aucun ADR n'est amendé.
- La docstring de `scripts/architecture/check-module-boundaries.py` (:4-12), qui range les cliquets « A8, A10 to A15, A18 » sans A19 ni A20 : c'est un commentaire de code du garde, laissé au changement n°1 qui modifie ce fichier (design, D3).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le dossier écrit « dépend des 12 autres » ; la politique compte 14 modules, `allowedDependencies['minos-app']` en liste 13, le POM de `minos-app` en déclare 13, plus le `test-jar` en portée test | [Existant vérifié] | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:47` ; `scripts/architecture/module-boundaries.json` ; `minos-app/pom.xml:17-32` |
| A17 décrite comme « tables Java et Python différentes » ; la liste s'arrête à A18 ; le garde imprime A19 et A20 | [Existant vérifié] | `frontieres-de-modules.md:86-87` ; sortie de `python scripts/architecture/check-module-boundaries.py` (lignes « A17 ArchUnit policy … no policy table is copied », « A19 embedded adapters », « A20 assembly classes ») |
| E3 : « le test en affirme cinq » ; le test affirme 6 ; la spec exige « quatre » | [Existant vérifié] | `frontieres-de-modules.md:209` ; `scripts/architecture/test_check_module_boundaries_adapters.py:228` ; `openspec/specs/frontieres-de-modules/spec.md`, exigence « Aucune découverte de service de production n'omet le chargeur », scénario « Dépôt réel » |
| La question ouverte cite un libellé « A3 packages, A7 reactor » qui « ne reflète plus » le périmètre ; les étapes actuelles nomment leurs règles, A18 à A20 compris | [Existant vérifié] | `frontieres-de-modules.md:219-220` ; `.github/workflows/pr-ci.yml:164-180` |
| `quality-gates.md` ne nomme pas `test_gate_runner.py`, lancé par le job `invariants` | [Existant vérifié] | `docs/developer/quality-gates.md:75` ; `.github/workflows/pr-ci.yml:179-180` |
| `quality-gates.md` ne décrit ni A19 ni A20 dans la liste des règles appliquées | [Existant vérifié] | `quality-gates.md:77-98` (seule mention : la table, `:68`) |
| `.claude/rules/java-production.md` cite « règles A2, A3, A7 à A16 » | [Existant vérifié] | `.claude/rules/java-production.md:11` |
| Étiquettes portées par les sources du garde : A2, A3, A7 à A20 | [Existant vérifié] | `grep -ohE "\bA[0-9]{1,2}\b" scripts/architecture/check-module-boundaries.py scripts/architecture/module_boundary_*.py \| sort -u` |
| Auto-test des étiquettes et des fichiers nommés | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé.
- **Workflow** : aucune étape ajoutée (l'auto-test vit dans `test_check_module_boundaries_documents.py`, déjà lancé par le job `invariants`).
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries_documents.py` et les quatre autres `test_check_module_boundaries*.py`, `scripts/docs/check-capability-dossiers.py`, `scripts/docs/check-current-docs.py` (lit `quality-gates.md`), `scripts/remediation/check-minos-01.py` (affirme une chaîne de `quality-gates.md`), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux ; Python de la bibliothèque standard, lecture UTF-8 explicite, sans branche de plateforme.
- **Documentation durable** : le dossier de la capacité, `docs/developer/quality-gates.md`, `.claude/rules/java-production.md`.

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.
