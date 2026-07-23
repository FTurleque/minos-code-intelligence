# État courant — MINOS

Dernière mise à jour : **23 juillet 2026**

Ce document est le tableau de bord opérationnel compact de MINOS. Les preuves
détaillées restent dans les documents de jalon, les décisions et les issues GitHub.

## Synthèse

```text
C0 — Cadrage                         TERMINÉ
M0 — Faisabilité technique          TERMINÉ ET LIVRÉ
M1 — Découverte et orchestration    TERMINÉ ET LIVRÉ
M2 — Intelligence des symboles      TERMINÉ ET LIVRÉ
M3 — Intelligence des relations     TERMINÉ ET LIVRÉ
M4 — Recherche et contexte compact  TERMINÉ ET LIVRÉ
M5 — Tests liés et dérivations      TERMINÉ ET LIVRÉ
M6 — Intelligence d’architecture    TERMINÉ, VALIDÉ ET LIVRÉ
M7 — Indexation incrémentale        FONCTIONNELLEMENT COMPLET — PORTE FINALE EN ATTENTE
M8 — Analyse d’impact               PROCHAIN APRÈS CLÔTURE M7
M9 à M13                            NON DÉMARRÉS
```

GitHub Actions reste volontairement hors de la porte locale courante ; l’anomalie
historique est suivie séparément dans #5.

## Portes acquises avant M7

```text
M2   86 tests   BUILD SUCCESS
M3  115 tests   BUILD SUCCESS
M4  131 tests   BUILD SUCCESS
M5  140 tests   BUILD SUCCESS
M6  162 tests   BUILD SUCCESS
```

Décisions :

- `docs/m0/DECISION_M0.md` ;
- `docs/m2/DECISION_M2.md` ;
- `docs/m3/DECISION_M3.md` ;
- `docs/m4/DECISION_M4.md` ;
- `docs/m5/DECISION_M5.md` ;
- `docs/m6/DECISION_M6.md`.

## M7 — Indexation incrémentale

Suivi : issue #22.

### M7.1 — Empreintes et ChangeSet — LIVRÉ

PR #23, merge :

```text
34b57dfadad962b98c2d5c028957595cee575400
```

Porte :

```text
120 sources main
60 sources test
167/167 tests PASS
BUILD SUCCESS
```

Acquis :

- `FileFingerprint` ;
- `ProjectFingerprint` ;
- `ProjectChangeSet` ;
- `ProjectFingerprintService` ;
- empreintes indépendantes du chemin absolu et des timestamps ;
- classification ajouté/modifié/supprimé/identique ;
- empreinte build séparée.

Document : `docs/m7/FINGERPRINTS_AND_CHANGESET.md`.

### M7.2 — Snapshots persistants — LIVRÉ

PR #24, merge :

```text
379b5a28a92cb58b340dc8801d66fad1b853e4ce
```

Porte :

```text
124 sources main
63 sources test
176/176 tests PASS
BUILD SUCCESS
```

Acquis :

- snapshot fingerprint associé à `projectId + indexSnapshotId` ;
- historique immuable ;
- publication séparée de la promotion ;
- pointeur actif atomique ;
- checksum et recalcul des agrégats ;
- alignement avec `ProjectIndexState.activeSnapshotId`.

Document : `docs/m7/FINGERPRINT_SNAPSHOTS.md`.

### M7.3 — Invalidation conservatrice — LIVRÉ

PR #25, merge :

```text
8f87a8fbb3f62361f88e38c9a8f22c2da2050ca8
```

Head validé :

```text
e41abcf999ca94b0f3cf9accc0ae8b6a22e41ffd
```

Porte :

```text
128 sources main
65 sources test
184/184 tests PASS
BUILD SUCCESS
```

Portées :

```text
NONE
PARTIAL_CANDIDATE
FULL_REQUIRED
```

Document : `docs/m7/CONSERVATIVE_INVALIDATION.md`.

### M7.4 — Planification, fallback et lifecycle — IMPLÉMENTÉ

Branche finale :

```text
m7/finalize-incremental-indexing
```

Acquis :

- `IndexerCapability.INCREMENTAL_INDEXING` ;
- `IncrementalIndexingPlan` et raisons structurées ;
- `IncrementalIndexingPlanner` ;
- `IndexingMode.NONE/FULL/INCREMENTAL` ;
- `IndexingExecutionRequest.mode + changedFiles` ;
- validation de sécurité dans `IndexingLifecycleService` ;
- atomicité projet : toutes les sélections doivent être capables ;
- fallback complet si une seule capacité manque ;
- `IncrementalIndexingCoordinator` ;
- nouvelle baseline fingerprint uniquement sur workspace stable ;
- baseline illisible/désalignée traitée comme absence de preuve.

Les versions épinglées actuelles :

```text
scip-java       0.13.1
scip-typescript 0.4.0
```

ne sont **pas** déclarées `INCREMENTAL_INDEXING`, faute de qualification M0.
Un changement source borné retombe donc actuellement en `FULL` avec ces fournisseurs.

Cette absence de capacité n’est pas un échec M7 : elle démontre précisément le
fallback demandé par la porte de décision, sans inventer de support fournisseur.

Documents :

- `docs/m7/INCREMENTAL_EXECUTION.md` ;
- `docs/m7/DECISION_M7.md`.

## Porte active — finale M7

```powershell
.\mvnw.cmd clean verify
```

La PR finale M7 doit rester Draft jusqu’à validation locale de son **head exact**.

Le replay attendu inclut notamment :

```text
M7.1 typescript-modules fingerprints: ...
M7.2 typescript-modules fingerprint-snapshot: ...
M7.3 typescript-modules invalidation: source-scope=PARTIAL_CANDIDATE, ...
M7.4 typescript-modules planning: initial=FULL, unchanged=NONE, source=FULL, missing-capability=[scip-typescript], baseline=snapshot-2
```

Après porte verte et fusion :

- issue #22 → `completed` ;
- M7 → terminé, validé et livré ;
- M8 — Analyse d’impact → jalon actif.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M7 : issue #22 ;
- M7.1 : `docs/m7/FINGERPRINTS_AND_CHANGESET.md` ;
- M7.2 : `docs/m7/FINGERPRINT_SNAPSHOTS.md` ;
- M7.3 : `docs/m7/CONSERVATIVE_INVALIDATION.md` ;
- M7.4 : `docs/m7/INCREMENTAL_EXECUTION.md` ;
- décision M7 : `docs/m7/DECISION_M7.md` ;
- promotion atomique : ADR-0006 ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.
