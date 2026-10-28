## 1. Préalables

- [ ] 1.1 (AUD-ARC-08, SH-06) Relever le SHA de départ sur `develop` et la CI verte qui sert de baseline. Rejouer `python .claude/scripts/run_gates.py` et `./mvnw -B -ntp -pl minos-provider-scip,minos-bootstrap -am test` ; consigner les tests exécutés, sautés et déjà rouges.

## 2. Raison écrite par couple d'A13

- [ ] 2.1 (AUD-ARC-08, D4, Q3) **Rouge.** Dans `scripts/architecture/test_check_module_boundaries.py`, écrire sur des arbres temporaires : un couple listé sans raison, une raison vide, une raison sans couple. Ils doivent échouer contre le garde du HEAD. **Correction** : `ADAPTER_COUPLING_REASONS` et son contrôle dans `check_adapter_types_named_by_adapters`, qui reçoit un paramètre `reasons=` à côté de `listed=` ; les auto-tests A13 existants (`test_check_module_boundaries.py:1355-1367`), qui passent leur propre `listed=`, passent aussi leurs raisons. La raison du couple `minos-provider-scip` → `minos-runtime-local` est écrite dans la table. **Preuve** : l'auto-test et le garde réel passent ; le résumé du garde nomme le nombre de couples justifiés.

## 3. Port et cycle

- [ ] 3.1 (AUD-ARC-08, D1, D2, Q1) **Rouge.** Dans `ScipProjectSnapshotLifecycleTest`, écrire « Répertoires ouverts par le cycle » avec un `StagingSnapshotStores` qui enregistre les répertoires ouverts et délègue à `FileSymbolSnapshotStore::new` : il ne compile pas au HEAD, faute de port. Écrire aussi « Promotion sans snapshot mis en place » (message `staged project snapshot is missing for run`, magasin actif inchangé ; le répertoire `project` vide que crée le magasin n'est pas une preuve d'échec) et « Promotion reprise par un autre processus » s'ils manquent ; ces deux-là passent au HEAD et gardent le comportement. **Correction** : ajouter `com.minos.store.StagingSnapshotStores` (moteur), interface fonctionnelle, Javadoc en anglais. Donner à `ScipProjectSnapshotLifecycle` un constructeur unique `(Path, CodeKnowledgeSnapshotStore, StagingSnapshotStores, List<IndexerDescriptor>)` ; ouvrir les magasins de fournisseur et de mise en place par le port, sous les mêmes répertoires ; retirer l'import de `FileSymbolSnapshotStore`. Garder `cleanupProviderWorkspaces` et `deleteRecursively(stagedRunRoot)`. Passer `FileSymbolSnapshotStore::new` dans `DefaultMinosApplicationComposer.snapshotLifecycle`. Adapter les 7 constructions de test. **Preuve** : les nouveaux tests passent, ainsi que `ScipProjectSnapshotLifecycleTest`, `ScipProjectSnapshotAuthorityTest`, `ScipRuntimeStoragePrivacyTest`, `SemanticDocumentKeysReachabilityTest` et `LocalStorageRetentionPreparedSnapshotTest`, sans changer leurs assertions ; `python scripts/remediation/check-post-mne.py` passe ; A13 refuse encore l'entrée stockage (périmée) jusqu'à la tâche 4.1, donc le garde est rejoué à la fin de 4.1.

## 4. Dépendance de test et garde

- [ ] 4.1 (AUD-ARC-08, D3, Q2) **Rouge.** Écrire l'auto-test « `minos-provider-scip` déclare `minos-storage` sans portée » : le garde du HEAD l'accepte, donc l'auto-test, qui attend un refus, est rouge. **Correction**, dans le même commit que le POM pour que le garde réel reste vert : `TEST_ONLY_DEPENDENCIES` dans `check_hexagonal_boundaries` ; `minos-storage` en `<scope>test</scope>` dans `minos-provider-scip/pom.xml` ; retrait de l'entrée `("minos-provider-scip", "minos-storage")` d'`ADAPTER_TYPES_NAMED_BY_ADAPTER` et de sa raison. **Preuve** : l'auto-test et le garde passent, A13 compte 1 couple et 9 types ; `./mvnw -B -ntp -pl minos-provider-scip,minos-bootstrap,minos-app -am test` passe, `ModuleArchitectureTest` compris.
- [ ] 4.2 (AUD-ARC-08) Rejouer `check-comment-language.py`, `check-private-io.py`, et la base SpotBugs de `minos-provider-scip` et de `minos-engine` (profil `audit-spotbugs`, `audit-report-summary.py spotbugs --baseline-dir quality/spotbugs-baseline`) : 0 alerte nouvelle et 0 disparue.

## 5. Documentation

- [ ] 5.1 (AUD-ARC-08) Mettre à jour :
  - `docs/developer/indexing-and-storage.md` : diagramme de séquence (`:15-16`) et texte du cycle (`:151`), magasins de travail ouverts par le port `StagingSnapshotStores` ;
  - l'arc42 § 5, section `minos-provider-scip` : la ligne « Dépendances » garde `minos-storage`, marqué « tests seulement », car la règle A9 la compare au graphe de toutes les portées (`check-module-boundaries.py:1495-1511`) ; le document généré `module-dependencies.md` ne change pas ;
  - `docs/developer/quality-gates.md:70` : A13 avec une raison par couple, et `TEST_ONLY_DEPENDENCIES` ;
  - l'ADR 0068 (note de mise en œuvre : A13 justifié par couple, port de staging) et l'ADR 0057 (`:76`, « Point 3 » : note de mise en œuvre) ;
  - `docs/project/governance/STATUS.md:126` (compteur A13) et `.claude/rules/java-production.md:15` s'ils décrivent A13.

  L'historique et les archives restent tels quels.
- [ ] 5.2 (AUD-ARC-08) Valider `openspec validate --all --strict`. Cocher les actions de SH-06 qui ont une preuve dans `docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md`.

## 6. Clôture

- [ ] 6.1 (AUD-ARC-08) Lancer `./mvnw -B -ntp verify` complet sous Windows, puis `python .claude/scripts/run_gates.py`, `check-jacoco.py` sur le rapport agrégé et `archgate check`.
- [ ] 6.2 (AUD-ARC-08) Mesurer la couverture JaCoCo de `ScipProjectSnapshotLifecycle` et du module `minos-provider-scip` sur le rapport Linux de la CI (artefact `jacoco-modules-Linux`). Toute classe touchée sous 80 % reçoit des tests dans la PR, avant la fusion.
- [ ] 6.3 (AUD-ARC-08) Mettre à jour la ligne de phase 4 dans `docs/project/audits/2026-10-10/SUIVI.md` (A13 : 1 couple justifié, 9 types) et la ligne SH-06 du plan de stockage hexagonal. Revue d'architecture (`minos-architecture-reviewer`). `ETAT-2026-10-13.md` et `findings.json` ne sont pas modifiés.
