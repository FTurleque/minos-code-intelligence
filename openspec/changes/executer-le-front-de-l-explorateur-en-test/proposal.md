# Proposal

## Why

Constat **AUD-TST-02** (audit du 2026-11-15, sprint 1, sévérité moyenne). La page de l'explorateur sémantique, `minos-explorer/src/main/resources/com/minos/explorer/web/app.js` (784 lignes, 45 fonctions), n'est exécutée par aucun test : `WebResourcesContentTest` y cherche des sous-chaînes, et aucun workflow n'appelle Node. Une erreur de syntaxe, une régression de la carte 2D ou de la pagination, ou un puits HTML écrit autrement que les douze motifs recherchés passent tous les contrôles, alors que cette page est la seule interface de l'explorateur.

Ce changement est le **prérequis** de deux constats d'autres sprints : AUD-PERF-10 (sprint 4, carte 2D côté navigateur) et AUD-QUA-03 (sprint 8, constantes et codes d'erreur du contrat v1). Il doit donc dire ce que ces sprints pourront affirmer et mesurer (section « Ce que les sprints 4 et 8 pourront mesurer » du design).

## What Changes

- Un gate `scripts/quality/check-explorer-front.py` contrôle la syntaxe de `app.js` (`node --check`) et exécute les tests du front (`node --test`), sans dépendance npm ni `package.json`. Il échoue si Node est absent ou trop ancien ; il a un auto-test (`--self-test`).
- Des tests `node:test` chargent le **vrai** `app.js` dans un contexte `node:vm` muni d'un faux DOM minimal : fonctions pures (libellés, pourcentages, filtres, messages d'état), géométrie et sélection de la carte, pagination des points, traduction des états.
- Un test de non-régression des puits DOM **par exécution** : le faux DOM refuse toute écriture de balisage et le contexte interdit la génération de code depuis une chaîne ; les rendus sont rejoués avec un contenu indexé hostile.
- Le gate et son auto-test sont exécutés par le job `invariants` de `pr-ci.yml`.
- `app.js` : le rejet de la promesse du presse-papiers (« Copier la référence ») est géré et signalé dans la page. C'est la seule modification du produit, de trois lignes (décision D6).

Aucune route, aucun champ du contrat v1, aucun comportement serveur ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `exploration-semantique` : quatre exigences ajoutées (page exécutée par des tests et contrôlée en CI ; puits DOM prouvés par exécution ; comportements de la carte et de la traduction des états caractérisés ; échec du presse-papiers signalé). Aucune exigence existante n'est modifiée.

## Hors périmètre

- Les corrections de performance de la carte (grille spatiale, cache du filtre, survol limité à une image) : AUD-PERF-10, sprint 4. Ce changement fixe le comportement à préserver et les compteurs à comparer, il n'optimise rien.
- Le renommage des constantes du contrat v1 et le `code` stable des erreurs : AUD-QUA-03, sprint 8.
- Les neuf variables globales mutables de `app.js` et l'objet `map` global : relevés par le constat, non restructurés ici (une restructuration sans test serait le défaut que ce changement corrige).
- Un test dans un vrai navigateur, un linter (ESLint) ou un outil de build JavaScript : ils introduiraient une dépendance npm et un verrou.
- La couverture JaCoCo du module Java `minos-explorer` : changement `borner-la-couverture-des-zones-nouvelles`.
- `WebResourcesContentTest` reste en place sans modification.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `app.js` : 784 lignes, 45 déclarations `function` | [Existant vérifié] | décompte au HEAD `89be3fb4` |
| `app.js` est un script classique, sans export, qui appelle `start()` au chargement | [Existant vérifié] | `minos-explorer/src/main/resources/com/minos/explorer/web/app.js:1`, `:758`, `:784` |
| Les tests existants cherchent des sous-chaînes | [Existant vérifié] | `minos-explorer/src/test/java/com/minos/explorer/WebResourcesContentTest.java:25-27`, `:40-46`, `:89-110` |
| Aucun workflow n'appelle Node | [Existant vérifié] | recherche de `node`, `setup-node`, `eslint` dans `.github/workflows/*.yml` : 0 ligne |
| Le rejet de `navigator.clipboard.writeText` n'est pas géré | [Existant vérifié] | `app.js:411-413` |
| Node est présent sur l'image `ubuntu-24.04` des runners | [Question] | non vérifié depuis le poste ; première exécution du job (tâche 2.4) |
| Node du runner, version minimale contrôlée par le gate | [Proposition] | décision D4 du design |
| Corriger le presse-papiers dans ce changement | [Proposition] | décision D6 du design |

## Impact

- **Modules du reactor** : `minos-explorer` (une ressource `app.js`, des fichiers de test JavaScript hors des ressources livrées). Aucun changement de `pom.xml`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune. Page de l'explorateur : un message ajouté en cas d'échec de copie (additif).
- **ADR** : aucun. Node n'entre pas dans la toolchain du produit : il sert un gate, comme Python.
- **Gates à rejouer** : `python scripts/quality/check-explorer-front.py` et `--self-test`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py` ; `./mvnw -B -ntp -pl minos-explorer -am test` (`WebResourcesContentTest` lit `app.js`).
- **Fusion** : quatre changements du sprint 1 ajoutent des étapes au job `invariants` de `.github/workflows/pr-ci.yml` (`executer-le-front-de-l-explorateur-en-test`, `compter-comme-sautes-les-tests-propres-a-une-plateforme`, `doter-chaque-gate-d-un-auto-test`, `epingler-les-fins-de-ligne-des-scripts-posix`), et `borner-la-couverture-des-zones-nouvelles` y modifie l'artefact de validation : les fusionner en série, chaque PR remise à jour sur `main` avant la suivante.
- **Plateformes** : la page est indépendante du système ; le gate s'exécute sous Linux (CI) et sur le poste Windows quand Node y est installé.
- **Documentation durable et diagrammes** : `docs/developer/semantic-explorer.md`, `docs/developer/quality-gates.md`, `docs/developer/testing.md`, `docs/architecture/diagrams/capacites/exploration-semantique.md`.

## Questions ouvertes

Non bloquantes, avec recommandation dans le design : D4 (Node du runner ou `actions/setup-node` épinglée) et D6 (corriger le presse-papiers ici).
