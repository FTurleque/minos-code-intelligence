# Tasks

## 1. Preuve inter-processus du verrou de synchronisation (AUD-TST-05)

- [x] 1.1 AUD-TST-05 — Rouge : ajouter à `FileSemanticSyncConsistencyTest` un test qui lance le détenteur avec une classe principale inexistante et attend une erreur d'assertion portant le code de sortie et un extrait de la sortie d'erreur ; vérifier par `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=FileSemanticSyncConsistencyTest" "-Dsurefire.failIfNoSpecifiedTests=false"` qu'il échoue au HEAD (une hypothèse de saut est levée à la place) (`recherche-semantique` › La preuve inter-processus du verrou de synchronisation échoue quand son détenteur ne démarre pas)
- [x] 1.2 AUD-TST-05 — Correction : remplacer les trois `assumeTrue` par des assertions ; vider la sortie d'erreur du fils dans un tampon borné par un fil démon ; attendre la fin du fils avec une échéance pour lire son code de sortie ; vider son environnement (décision D3) ; ajouter le cas « sortie d'erreur plus longue que la borne » ; vérifier que 1.1 passe et que le test inter-processus s'exécute (aucun test sauté dans le rapport Surefire de la classe)
- [x] 1.3 AUD-TST-05 — Preuve : rejouer la classe 50 fois sur le poste Windows ; rejouer `python scripts/quality/check-comment-language.py` ; scope JaCoCo `semantic-vector-store` comparé en CI ; sur la PR, la classe ne compte aucun test sauté dans les deux jobs `Verify`
  - Fait en CI : PR #743 (run 43749092436, tête 1c87e79f) : `FileSemanticSyncConsistencyTest` compte 9 tests, 0 sauté, 0 échec dans les deux jobs `Verify` ; `JaCoCo semantic-vector-store: PASS` (0,951 / 0,727) sur les deux systèmes.

## 2. Bail tenu après une fermeture étrangère (AUD-TST-07)

- [x] 2.1 AUD-TST-07 — Rouge **(manuelle pour la copie mutée)** : écrire les affirmations de la décision D4 dans `ProjectLeaseOwnershipTest` (trois baux) et `SnapshotProjectLeaseTest` ; sur une copie locale non commitée où `close()` marque le bail fermé avant de contrôler le fil propriétaire (`InMemoryIndexStateStore`, `ProjectIndexLease`, `BoundedFileLease` qui porte la fermeture de `SnapshotProjectLease`), vérifier que ces tests échouent et que le test du HEAD sur le bail en mémoire y passe ; consigner dans la section Vérification du design le résultat des tests du HEAD sur les deux baux de fichier (`persistance-snapshots` › Une fermeture refusée depuis un autre fil laisse le bail tenu)
- [x] 2.2 AUD-TST-07 — Correction des tests : concurrent sur un autre fil (échéance courte pour les baux de fichier, attente observée par `ParkedThreads.awaitParkedIn` pour le bail en mémoire et celui du magasin fichier), puis acquisition après la fermeture par le propriétaire ; fils démons joints avec une échéance ; vérifier par `./mvnw -B -ntp -pl minos-storage -am test "-Dtest=ProjectLeaseOwnershipTest,SnapshotProjectLeaseTest" "-Dsurefire.failIfNoSpecifiedTests=false"` rejoué 50 fois
- [x] 2.3 AUD-TST-07 — Remplacer les six `assertTrue(true)` (décision D5) ; vérifier que `grep -rn "assertTrue(true" --include=*.java minos-*/src/test` ne retourne aucune ligne et que les imports inutilisés sont retirés
- [x] 2.4 AUD-TST-05, AUD-TST-07 — Preuve d'ensemble : `./mvnw -B -ntp -pl minos-storage -am test` vert sur le poste ; scopes JaCoCo `storage-codec-and-state` et `semantic-vector-store` comparés en CI ; `python .claude/scripts/run_gates.py --fast`
  - Fait en CI : PR #743 : `JaCoCo storage-codec-and-state: PASS` (0,924 / 0,798) et `JaCoCo semantic-vector-store: PASS` (0,951 / 0,727) dans les deux jobs `Verify` ; aucun test des classes de bail (`SnapshotProjectLease*`, `ProjectIndexLease*`, `FileIndexStateStore*`) n'est sauté.
- [x] 2.5 AUD-TST-05, AUD-TST-07 — Documentation : `docs/developer/testing.md` (un test de processus fils échoue quand son fils ne démarre pas et garde sa sortie d'erreur ; une affirmation constante n'est pas une preuve) ; vérifier par `python scripts/docs/check-current-docs.py`

## 3. Clôture

- [x] 3.1 Mettre à jour la matrice de traçabilité du design avec les noms réels des tests ; nommer comme preuve manuelle la copie locale mutée de 2.1 ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [ ] 3.2 Mettre à jour `docs/architecture/diagrams/capacites/recherche-semantique.md` et `docs/architecture/diagrams/capacites/persistance-snapshots.md` (une exigence chacun) ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [x] 3.3 Valider les spécifications par `openspec validate --all --strict`
- [x] 3.4 Lister les documents à mettre à jour avant archivage (`docs/developer/testing.md`, `SUIVI.md` de l'audit du 2026-11-15) ; vérifier que chacun est fait ou nommé comme reste
  - Fait : `docs/developer/testing.md` ; `SUIVI.md` de l'audit du 2026-11-15 mis à jour à l'archivage.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive faire-echouer-les-preuves-de-verrou-muettes -y`), puis vérifier que les deux specs de référence gardent leur section `## Documentation liée`.
