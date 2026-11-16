# Proposal

## Why

Suite de **AUD-PERF-10** (sprint 4 de l'audit du 2026-11-15) relevée par le superviseur de code : `minos-explorer/src/main/resources/com/minos/explorer/web/app.js` fait **926 lignes** avec la grille de la carte (793 avant), pour une seule portée globale qui mêle deux responsabilités sans lien de code : la page (jeton, liste, recherche, détail, voisins) et la carte 2D (points, grille, tracé, pointeur).

Le fichier est servi tel quel, sans chaîne de build (ARCH-0082) : le découper veut dire servir un second script classique. Ce n'est pas un remaniement interne : la liste fermée des ressources de `WebResources`, l'exception de lecture croisée de `RequestGuard` et `index.html` changent avec lui.

## What Changes

- `app.js` est coupé en deux scripts classiques : `map.js` (section « 2D map » : état de la carte, liste affichée, grille, chargement des points, tracé, survol, zoom, `startMap`) et `app.js` (le reste de la page).
- `index.html` charge `map.js` puis `app.js` ; les deux partagent la portée globale de la page. `map.js` ne contient que des déclarations : il ne s'exécute qu'après `start()` d'`app.js`.
- `WebResources` sert `/map.js` (`text/javascript; charset=utf-8`) ; `ExplorerServer.LINK_OPENED_PATHS` l'ajoute aux lectures de page autorisées sans contrôle `Sec-Fetch-Site`.
- Le harnais de test du front charge les scripts dans l'ordre de leurs balises `<script src>` ; `check-explorer-front.py` contrôle la syntaxe de chacun et son auto-test gagne un cas pour `map.js`.
- Aucun comportement, aucune route d'API, aucun texte de la page ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

Aucune : remaniement à comportement constant, `skip_specs: true`. Les exigences de `exploration-semantique` restent vraies sans modification.

## Hors périmètre

- Un bundler, des modules ES, une dépendance ou une chaîne de build.
- Un découpage plus fin (liste, recherche, détail) : seul le groupe « carte » est séparable sans toucher à la portée partagée.
- `trancher-la-langue-du-contrat-de-l-explorateur` (sprint 8), qui ajoute `ERROR_LABELS` près de `api` et `report` : ces fonctions restent dans `app.js`.
- Le parcours manuel de la carte (tâche 4.2 de `rendre-le-survol-de-la-carte-independant-du-nombre-de-points`).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `app.js` : 926 lignes à la tête de `perf/survol-carte-independant-du-nombre-de-points` (793 au `c388df1b`) | [Existant vérifié] | `wc -l` |
| Les ressources de la page forment une liste fermée de quatre chemins | [Existant vérifié] | `minos-explorer/src/main/java/com/minos/explorer/WebResources.java:36-41` |
| Une seule liste de chemins de page est lisible depuis une autre origine | [Existant vérifié] | `minos-explorer/src/main/java/com/minos/explorer/ExplorerServer.java:57`, `:211` |
| ARCH-0082 nomme les ressources statiques de cette exception | [Existant vérifié] | `docs/architecture/adr/ARCH-0082-surface-http-locale-optionnelle-de-consultation.md:144` |
| Des tests lisent le contenu d'`app.js` et la liste des balises `<script>` | [Existant vérifié] | `minos-explorer/src/test/java/com/minos/explorer/WebResourcesContentTest.java:44-130` |
| Trois autres tests Java et un test de paquetage citent `app.js` | [Existant vérifié] | `ExplorerServerTest`, `ExplorerUnitsTest`, `ExplorerCitedCommandsTest`, `ShadedJarExplorerEntryPointIT` |
| Deux scripts classiques d'une page partagent leurs `const`, `let` et fonctions de premier niveau | [Existant vérifié] | spécification HTML (portée de script globale) ; démontré par les 36 tests du front sous `node:vm` |
| Le nom `map.js` et la coupure à la ligne de la section « 2D map » | [Proposition] | design D1, D2 |

## Impact

- **Modules du reactor** : `minos-explorer` (ressources web, `WebResources`, `ExplorerServer` et leurs tests), `minos-cli` (`ExplorerCitedCommandsTest`), `minos-app` (`ShadedJarExplorerEntryPointIT`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; une ressource statique de la page s'ajoute, sans donnée.
- **ADR** : ARCH-0082 est **clarifié** (la liste des ressources de l'exception de lecture croisée passe de trois à quatre chemins, même décision) ; à faire confirmer par le propriétaire, la liste étant une frontière de sécurité.
- **Gates à rejouer** : `scripts/quality/check-explorer-front.py` et son `--self-test`, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/check-capability-dossiers.py`.
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme ; tests Node identiques sur les deux.
- **Documentation durable** : `docs/developer/semantic-explorer.md`, `docs/developer/quality-gates.md`, `docs/user/semantic-explorer.md`, `docs/project/governance/STATUS.md`, `docs/architecture/diagrams/capacites/exploration-semantique.md` (écart E12).

## Questions ouvertes

Non bloquante : confirmation par le propriétaire de la clarification d'ARCH-0082 (quatre ressources statiques lisibles depuis une autre page).
