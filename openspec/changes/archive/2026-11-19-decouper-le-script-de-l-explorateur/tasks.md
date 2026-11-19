# Tasks

Remaniement à comportement constant (suite de **AUD-PERF-10**, retour du superviseur de code) : la caractérisation tient lieu de rouge (design D1 du précédent) — les 36 tests `node:test` du front passent avant et après sans attente modifiée — ; les tests qui encodent le fichier unique ont un vrai rouge, noté avant l'édition. Tout `-pl` s'accompagne de `-am` ; jamais deux builds Maven en parallèle sur le poste. Prérequis : `rendre-le-survol-de-la-carte-independant-du-nombre-de-points` fusionné (même fichier) ; avant `trancher-la-langue-du-contrat-de-l-explorateur`.

## 1. Rouge

- [x] 1.1 [AUD-PERF-10] Mesurer le point de départ : `wc -l` d'`app.js` (926), liste des tests et gates qui nomment `app.js` (`grep -rn "app\.js"` hors `openspec/changes/archive`), et vérifier que les 36 tests du front sont verts avant toute édition. Vérifier : `python scripts/quality/check-explorer-front.py` vert.
- [ ] 1.2 [AUD-PERF-10] Rouge : écrire d'abord les attentes du fichier coupé — `WebResourcesContentTest` (ordre exact des balises `map.js`, `app.js` ; chaînes de la carte lues dans `map.js` ; contrôles de sécurité sur les deux scripts), `ExplorerUnitsTest` (cinq chemins), `ExplorerServerTest` (`/map.js` lu depuis une autre origine, refus de `/map.js/` et de `/map.js.map`). Vérifier : `./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=WebResourcesContentTest,ExplorerServerTest,ExplorerUnitsTest" "-Dsurefire.failIfNoSpecifiedTests=false"` échoue pour la bonne raison (ressource `/map.js` absente), sortie notée. **Non faite** : les attentes ont été écrites en même temps que la coupure, sans exécution rouge préalable ; la preuve est le vert de 3.2 et le diff des attentes. — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.

## 2. Coupure

- [x] 2.1 [AUD-PERF-10] Couper `app.js` : la section « 2D map » (de `MAP_PAGE` à `startMap`, 432 lignes) devient `map.js` ; `index.html` charge `map.js` puis `app.js` ; aucun autre octet de code ne change. Vérifier : `node --check` des deux fichiers ; `python scripts/quality/check-explorer-front.py` (36 tests verts sans attente modifiée).
- [x] 2.2 [AUD-PERF-10] Le harnais charge les scripts dans l'ordre des balises `<script src>` de `index.html`. Vérifier : mêmes 36 tests verts ; la redéclaration d'un nom entre les deux fichiers ferait échouer le chargement.
- [x] 2.3 [AUD-PERF-10] `check-explorer-front.py` : syntaxe de chaque script, cas d'auto-test « broken syntax in the map script names the file », ancre « rayon de capture » déplacée dans `map.js`. Vérifier : `python scripts/quality/check-explorer-front.py --self-test` (12 cas) ; `python scripts/quality/check-ci-wiring.py`.

## 3. Surface et contrôles Java

- [x] 3.1 [AUD-PERF-10] `WebResources` sert `/map.js` ; `ExplorerServer.LINK_OPENED_PATHS` l'ajoute (design D3). Vérifier : `ExplorerServerTest`, `ExplorerUnitsTest`.
- [x] 3.2 [AUD-PERF-10] Adapter les contrôles qui nomment `app.js` : `WebResourcesContentTest`, `ExplorerCitedCommandsTest` (liste des sources), `ShadedJarExplorerEntryPointIT` (entrée du JAR ombré). Vérifier : `./mvnw -B -ntp -pl minos-explorer,minos-cli -am test "-Dtest=WebResourcesContentTest,ExplorerServerTest,ExplorerUnitsTest,ExplorerCitedCommandsTest" "-Dsurefire.failIfNoSpecifiedTests=false"`. `ShadedJarExplorerEntryPointIT` n'est pas exécuté ici (paquetage complet) : à rejouer en CI.

## 4. Documentation

- [x] 4.1 [AUD-PERF-10] Mettre à jour `docs/developer/semantic-explorer.md`, `docs/developer/quality-gates.md`, `docs/user/semantic-explorer.md`, `docs/project/governance/STATUS.md`, le dossier de capacité (écart E12) et clarifier ARCH-0082 (quatre ressources statiques). Vérifier : `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/docs/check-capability-dossiers.py`.
- [x] 4.2 [AUD-PERF-10] **(manuelle, propriétaire)** Confirmer D3 (`/map.js` dans l'exception de lecture croisée) et la clarification d'ARCH-0082. Vérifier : relecture du propriétaire. — décision du propriétaire du 2026-11-19 : recommandation du design retenue (D3 : `/map.js` dans l'exception de lecture croisée).
- [ ] 4.3 [AUD-PERF-10] **(manuelle)** Ouvrir la page dans un navigateur et vérifier que la carte se charge (deux scripts servis). Non fait : aucun navigateur n'a chargé la page servie par le vrai serveur ; seuls le faux DOM sous Node et les tests HTTP ont été exécutés. — **Reste au 2026-11-19** : archivé sans cette tâche, voir `docs/project/audits/2026-11-15/SUIVI.md`.

## 5. Clôture

- [x] 5.1 [AUD-PERF-10] Matrice de traçabilité du design : preuves nommées, aucune ligne sans preuve. Vérifier : relecture.
- [x] 5.2 [AUD-PERF-10] Valider les spécifications : `openspec validate --all --strict` ; `python .claude/scripts/run_gates.py`.
- [x] 5.3 [AUD-PERF-10] Lister les documents à mettre à jour avant archivage : STATUS, `docs/user`, `docs/developer`, ARCH-0082 faits en 4.1 ; ROADMAP, arc42 sans objet. Vérifier : liste relue.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive decouper-le-script-de-l-explorateur -y`) ; comme il n'a pas de delta (`skip_specs: true`), l'archivage ne modifie aucune spécification courante.
