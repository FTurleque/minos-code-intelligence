# Tasks

Ordre de fusion sur `MinosApplicationComposer` et `MinosApplicationComposersTest.FirstComposer` : avant `cabler-les-adaptateurs-embarques-par-la-racine-de-composition` et `injecter-les-collaborateurs-des-cas-d-usage` (design, D6).

## 1. Port et implémentation fichier (AUD-ARC-03)

- [ ] 1.1 AUD-ARC-03 — Rouge : écrire `FileIndexImportHistoryStoreTest` dans `minos-storage` — fichier au format du HEAD relu (version de provider vide → absente), projet sans historique (vide, aucun dossier créé), fichier hors bornes refusé, clé obligatoire manquante refusée en nommant la clé et la feuille (cas porté depuis `ProjectInspectionServiceRefusalMessageTest.java:38-45`), et les quatre cas de `LocalProjectOperationsHistoryTest` : écriture ASCII en `.properties` à droits privés, version de provider non ASCII échappée en Latin-1, version absente stockée vide, seconde écriture qui remplace la première sans temporaire restant. Exigences : frontieres-de-modules › Le format de l'historique d'import reste lisible ; › Un historique d'import illisible est refusé sans fuite. Vérifier : le test échoue faute de classe.
- [ ] 1.2 AUD-ARC-03 — Correction : créer le port `IndexImportHistoryStore` et l'enregistrement `IndexImportHistory` dans `minos-engine` (`com.minos.orchestration`), puis `FileIndexImportHistoryStore` dans `minos-storage` (`com.minos.storage.home`), en reprenant les primitives et les bornes de `LocalProjectOperations.writeHistory` et `ProjectInspectionService.readHistory`. Commentaires en anglais. Vérifier : `./mvnw -B -ntp -pl minos-storage -am test` ; `python scripts/architecture/check-private-io.py` (aucune entrée de tolérance ajoutée) ; `python scripts/architecture/check-module-boundaries.py` (A3, A8, A14, règle « home -> backends ») ; `python scripts/quality/check-comment-language.py`.

## 2. Câblage et retrait des doublons (AUD-ARC-03)

- [ ] 2.1 AUD-ARC-03 — Rouge : dans `minos-bootstrap`, cas de `DefaultMinosApplicationComposerTest` qui exige que la racine fournisse le magasin d'historique du home, et test d'inspection après import (application ouverte par la racine de production, import SCIP de provider et de version connus, vue qui rend instantané, provider, version et date) ; dans `minos-application`, test de `ProjectInspectionService` avec un port substitué. Exigence : frontieres-de-modules › L'historique d'import est lu et écrit par un port. Vérifier : échec faute de méthode.
- [ ] 2.2 AUD-ARC-03 — Correction : ajouter `indexImportHistoryStore(Path home)` à `MinosApplicationComposer` et à `DefaultMinosApplicationComposer` ; injecter le port dans `LocalProjectOperations` et `ProjectInspectionService` ; garder le port dans `MinosApplication` sans accesseur public ; supprimer `writeHistory`, `readHistory`, les deux `IndexHistory` et les deux littéraux ; supprimer `LocalProjectOperationsHistoryTest` (ses cas vivent en 1.1) ; adapter `ProjectInspectionServiceRefusalMessageTest` à un port substitué qui lève l'erreur de clé manquante ; adapter `MinosApplicationComposersTest.FirstComposer` (`:90`) et les tests qui construisent ces deux classes (`minos-application`, `minos-bootstrap`, `minos-cli`). Vérifier : la recherche `cli-index-history` dans `minos-*/src/main` ne rend qu'une classe de `minos-storage` ; `ProjectInventoryToleranceTest` et `ImportHistoryFailureTest` verts sans changement d'attente ; `python scripts/architecture/check-module-boundaries.py` (A11 : aucune entrée ajoutée) ; `./mvnw -B -ntp -pl minos-cli -am test` et `./mvnw -B -ntp -pl minos-bootstrap -am test`.
- [ ] 2.3 AUD-ARC-03 — **(manuelle, propriétaire)** confirmer D2 du design (l'historique reste un fichier de `MINOS_HOME` avec PostgreSQL). Vérifier : choix consigné dans la PR.
- [ ] 2.4 AUD-ARC-03 — Documentation : page des backends de stockage de `docs/user/` (l'historique d'import est un fichier de `MINOS_HOME` pour tout backend), `docs/developer/indexing-and-storage.md` (port et implémentation), `docs/user/java-api.md` (méthode ajoutée au port du composeur), dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`. Vérifier : `python scripts/docs/check-current-docs.py`, `python scripts/docs/check-capability-dossiers.py`, liens relatifs.

## 3. Clôture

- [ ] 3.1 AUD-ARC-03 — Mettre à jour la matrice de traçabilité du design en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 3.2 AUD-ARC-03 — Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 3.3 AUD-ARC-03 — Valider les spécifications ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 3.4 AUD-ARC-03 — Lister les documents à mettre à jour avant archivage (STATUS et ROADMAP si le constat y figure, `docs/user`, `docs/developer`, suivi de l'audit) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive porter-l-historique-d-import-derriere-un-port -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
