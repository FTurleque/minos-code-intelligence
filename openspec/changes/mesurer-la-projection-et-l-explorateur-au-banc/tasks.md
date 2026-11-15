# Tasks

## 1. Harnais et test de fumée (AUD-PERF-13)

- [ ] 1.1 AUD-PERF-13 — Rouge : écrire le test de fumée du banc sémantique dans `minos-explorer/src/test/java/com/minos/explorer/` (index local minuscule, liste exacte des mesures attendues de la préparation, des routes et du tas ; égalité entre routes mesurées et routes de `ExplorerApi`) ; vérifier par `./mvnw -B -ntp -pl minos-explorer -am test` qu'il échoue faute de harnais (`exploration-semantique` › Le harnais du banc sémantique est gardé par un test du build)
- [ ] 1.2 AUD-PERF-13 — Correction : écrire le harnais (`main` hors des motifs de Surefire, nom sans numéro de jalon, commentaires en anglais) : jeu synthétique à graine fixe écrit directement par le port du magasin sémantique (`semanticVectorStore().replace`, hors synchronisation, mention `beyond-build-budget` au-delà du budget de construction), PostgreSQL joint par la configuration du produit sur une base dédiée et vide (décision D8), préparation de la projection, serveur réel sur port éphémère, mesures de la décision D3, sortie `METRIC section jeu mesure valeur` ; vérifier que 1.1 passe et que la classe `main` n'apparaît pas dans le rapport Surefire ; rejouer `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-comment-language.py`, `python scripts/quality/check-milestone-identifiers.py` (`exploration-semantique` › Le banc de scalabilité produit une référence pour la projection et les routes de l'explorateur)
- [ ] 1.3 AUD-PERF-13 — Rouge puis correction : cas du test de fumée « PostgreSQL demandé sans URL », « préparation refusée par un budget trop petit », « refus attendu observé : code nul » et « refus attendu absent : code non nul » ; le harnais écrit la ligne `REFUSED`, poursuit et rend un code non nul ; vérifier qu'aucune raison ne contient de chemin absolu ni de mot de passe (`exploration-semantique` › Une mesure impossible est nommée dans le résultat)

## 2. Configuration, script et documentation (AUD-PERF-13)

- [ ] 2.1 AUD-PERF-13 — Écrire `benchmarks/scalability/semantic.json` (dimension 768 ; jeux `local` de 25 000 et 32 768 documents, jeu `local` de 100 000 documents à refus attendu sur liste, détail et voisins ; jeux `postgresql` de 25 000 et 100 000 ; graine, échauffement, itérations, arguments de JVM) et `benchmarks/scalability/run-semantic-benchmark.ps1` sur le modèle de `run-scalability-benchmark.ps1` (compatible Windows PowerShell 5.1, refus d'un dossier de sortie situé dans le dépôt, `$LASTEXITCODE` testé, `environment.txt` sans secret) ; rejouer `python scripts/quality/check-twin-script-functions.py` si une fonction est recopiée du script existant
- [ ] 2.2 AUD-PERF-13 — Preuve : lancer le script avec un dossier de sortie dans le dépôt et constater le refus avant toute mesure ; lancer une exécution réduite (une taille, peu d'itérations) vers un dossier hors du dépôt et constater `git status --porcelain` inchangé
- [ ] 2.3 AUD-PERF-13 — Régler itérations et échauffement pour une exécution locale complète de moins d'une heure ; écrire les valeurs retenues dans `semantic.json` et la durée observée dans le README
- [ ] 2.4 AUD-PERF-13 — Documentation : `benchmarks/scalability/README.md` (section du banc sémantique, table des mesures, provenance synthétique des jeux et budget de construction du produit, renvoi à `SemanticProjectionMeasurementTest` et à ce qu'il mesure, prérequis PostgreSQL par `scripts/dev/start-test-postgres.ps1`, correction de la mention du JDK) et `docs/developer/semantic-explorer.md` (renvoi au banc) ; vérifier par `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`

## 3. Référence avant le sprint 4 (AUD-PERF-13)

- [ ] 3.1 AUD-PERF-13 — **(manuelle)** Sur la machine de référence, au commit de `main` qui précède le sprint 4 : démarrer le PostgreSQL de test (`scripts/dev/start-test-postgres.ps1`), exécuter le banc complet (tous les jeux de `semantic.json`), vérifier que le jeu local de 100 000 documents constate le refus de la liste, du détail et des voisins, noter si son fichier tient sous le plafond de 384 Mio, puis une seconde fois pour vérifier que documents, passes et vecteurs lus sont identiques
- [ ] 3.2 AUD-PERF-13 — **(manuelle)** Consigner dans `docs/project/audits/2026-11-15/SUIVI.md` l'extrait de référence : commit, machine, JVM, et par stockage et par taille la durée et les passes de préparation, p50 / p95 et octets alloués des routes, tas retenu ; nommer toute section refusée et sa raison ; conserver les fichiers de résultat hors du dépôt
- [ ] 3.3 AUD-PERF-13 — Compléter dans le design la table « Ce que le sprint 4 pourra mesurer » avec le nom exact des lignes de `result.tsv` à comparer pour AUD-PERF-01, AUD-PERF-02 et AUD-PERF-03

## 4. Clôture

- [ ] 4.1 Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes (valeurs PostgreSQL si le serveur n'a pas pu être démarré) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 4.2 Mettre à jour `docs/architecture/diagrams/capacites/exploration-semantique.md` (trois exigences, écarts) ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [ ] 4.3 Valider les spécifications par `openspec validate --all --strict`
- [ ] 4.4 Lister les documents à mettre à jour avant archivage (`benchmarks/scalability/README.md`, `docs/developer/semantic-explorer.md`, `SUIVI.md` de l'audit) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive mesurer-la-projection-et-l-explorateur-au-banc -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
- Débloque AUD-PERF-01, AUD-PERF-02 et AUD-PERF-03 (sprint 4) ; la tâche 3.1 doit être faite avant la première PR de ce sprint.
