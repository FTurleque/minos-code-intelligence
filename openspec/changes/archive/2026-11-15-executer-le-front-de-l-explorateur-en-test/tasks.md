# Tasks

## 1. Gate et tests du front (AUD-TST-02)

- [x] 1.1 AUD-TST-02 — Rouge : écrire `scripts/quality/check-explorer-front.py` réduit à son `--self-test`, déclaré sous la forme littérale `add_argument("--self-test"` (c'est elle que `check-ci-wiring.py` reconnaît), avec ses copies temporaires : syntaxe cassée, dossier de tests vide, `PATH` sans Node, version sous la minimale ; vérifier que `python scripts/quality/check-explorer-front.py --self-test` échoue tant que le gate n'existe pas, et constater que `python scripts/quality/check-ci-wiring.py` échoue désormais en nommant le script non câblé : ce rouge, comme celui de `run_gates.py`, est attendu jusqu'à la tâche 1.3 (`exploration-semantique` › La page de l'explorateur est exécutée par des tests et contrôlée avant toute fusion)
- [x] 1.2 AUD-TST-02 — Correction : écrire le gate (résolution de `node`, version minimale, `node --check`, `node --test`, refus de zéro test, arguments en liste, environnement réduit, commentaires en anglais) ; vérifier que `--self-test` passe
- [x] 1.3 AUD-TST-02 — Câblage : deux étapes du job `invariants` de `.github/workflows/pr-ci.yml` (`python scripts/quality/check-explorer-front.py` et `--self-test`), précédées de l'affichage de `node --version` ; vérifier que `python scripts/quality/check-ci-wiring.py` repasse au vert ; rejouer `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-workflow-pins.py`, `python scripts/quality/check-single-execution.py`
- [x] 1.4 AUD-TST-02 — Écrire `minos-explorer/src/test/js/harness.mjs` (chargement du vrai `app.js` dans `node:vm`, faux DOM, faux `fetch`, faux canevas, `codeGeneration: { strings: false }`) et un premier test de fumée (`start()` sans jeton affiche le refus) ; vérifier par `python scripts/quality/check-explorer-front.py` (nombre de tests > 0)
- [x] 1.5 AUD-TST-02 — Rouge puis preuve : `sinks.test.mjs` rejoue liste, détail, voisins, recherche, textes et survol de la carte, bandeaux avec des valeurs `<script>alert(1)</script>` ; ajouter à `--self-test` les copies mutées (`insertAdjacentHTML`, nom de propriété calculé, `eval`) et vérifier que le test y échoue (`exploration-semantique` › Aucun contenu indexé n'atteint un puits de balisage, prouvé par exécution)
- [x] 1.6 AUD-TST-02 — `map.test.mjs` : oracle de `pointAt` (graine fixe, égalités, hors rayon, jeu de 100 000 points), `visiblePoints` avec et sans filtre, bornes et point fixe de `zoomMap`, suite des requêtes de `readMapPoints` (trois pages, changement d'état, requête périmée), exécution de `drawMap` sur le faux canevas (points dessinés et marqués enregistrés), compteurs de rafale dans un tableau unique de constantes (au HEAD : K recherches de point et un tracé par image) ; vérifier le rouge en mutant le rayon de capture dans une copie (`exploration-semantique` › La carte et la traduction des états ont un comportement caractérisé par des tests)
- [x] 1.7 AUD-TST-02 — `labels.test.mjs` et tests des fonctions pures (`shortId`, `lines`, `symbolName`, `percent`, `filters`, `emptySearchMessage`, `stateBanner`, `report`) : table unique des constantes du contrat confrontée aux libellés, aucun affichage de constante brute par le badge d'en-tête (table `STATE_BADGE` de `app.js`, écart E6), le bandeau, le message de la carte et le détail, caractérisation de l'affichage d'une erreur d'API ; vérifier le rouge en ajoutant une constante sans libellé

## 2. Preuve et documentation (AUD-TST-02)

- [x] 2.1 AUD-TST-02 — Preuve locale : `python .claude/scripts/run_gates.py` vert ; `./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=com.minos.explorer.**.*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` vert (tests du module ; les modules amont sont compilés, leurs tests ne sont pas lancés : le changement ne touche aucun code Java) ; aucun fichier de `src/test/js/` sous `minos-explorer/target/classes`
  - Preuve : `run_gates.py` 64/64 ; la commande Maven ci-dessus, par le verrou Maven partagé : BUILD SUCCESS, 51 tests du module ; aucun `.mjs` sous `minos-explorer/target`.
- [x] 2.2 AUD-TST-02 — **(manuelle)** Sur la PR : lire dans le journal du job `invariants` la version de Node du runner et le nombre de tests exécutés ; si Node manque ou est sous la minimale, appliquer l'alternative de D4 (`actions/setup-node` épinglée par SHA) après accord du propriétaire
  - Fait : job `Static invariants (single run)` de la PR #741 (run 43740054224, tête a600e904) : `node --version` rend v22.23.4 (minimum 20) et le gate journalise « 26 tests passed » ; l'alternative de D4 est sans objet.
- [x] 2.3 AUD-TST-02 — Documentation : `docs/developer/semantic-explorer.md` (tests du front, comment les lancer), `docs/developer/quality-gates.md` (nouveau gate, ce qu'il ne prouve pas : aucun vrai navigateur), `docs/developer/testing.md` (Node requis pour ce gate) ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`

## 3. Rejet du presse-papiers (AUD-TST-02, décision D6)

- [x] 3.1 AUD-TST-02 — Rouge : `clipboard.test.mjs` avec un presse-papiers simulé qui rejette ; vérifier que le test échoue sur `app.js` du HEAD (rejet non géré, bandeau vide) (`exploration-semantique` › Un échec de copie de la référence est signalé dans la page)
- [x] 3.2 AUD-TST-02 — Correction : traiter le rejet dans `app.js` et afficher le message par `banner` ; vérifier que 3.1 passe, que le cas « copie réussie » passe, et rejouer la commande Maven de la tâche 2.1 (`WebResourcesContentTest`)

## 4. Clôture

- [x] 4.1 Mettre à jour la matrice de traçabilité du design et la section « Ce que les sprints 4 et 8 pourront mesurer » avec les noms réels des tests ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 4.2 Mettre à jour `docs/architecture/diagrams/capacites/exploration-semantique.md` (quatre exigences, écarts) ; vérifier par `python scripts/docs/check-capability-dossiers.py`
  - Reste : `check-capability-dossiers.py` refuse une ligne de traçabilité dont l'exigence n'est pas encore dans `openspec/specs/exploration-semantique/spec.md` (« traceability names unknown requirement ») ; les quatre lignes entrent dans le commit qui archive le changement, avec l'écart E6 du design.
- [x] 4.3 Valider les spécifications par `openspec validate --all --strict`
- [x] 4.4 Lister les documents à mettre à jour avant archivage (`docs/developer/semantic-explorer.md`, `quality-gates.md`, `testing.md`, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste
  - Faits : `docs/developer/semantic-explorer.md`, `docs/developer/quality-gates.md`, `docs/developer/testing.md`, ligne AUD-TST-02 de `docs/project/audits/2026-11-15/SUIVI.md` (statut « implémenté, non fusionné »). Restes nommés : quatre lignes de traçabilité de `docs/architecture/diagrams/capacites/exploration-semantique.md` (tâche 4.2, au commit d'archivage) ; statut « fusionné » de SUIVI après la PR.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive executer-le-front-de-l-explorateur-en-test -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- Débloque AUD-PERF-10 (sprint 4) et AUD-QUA-03 (sprint 8).
