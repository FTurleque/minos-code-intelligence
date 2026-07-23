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
M7 — Indexation incrémentale        EN COURS — M7.1/M7.2 LIVRÉS, M7.3 EN VALIDATION
M8 à M13                            NON DÉMARRÉS
```

GitHub Actions reste volontairement hors de la porte locale courante ; l’anomalie
historique est suivie séparément dans #5.

## Portes acquises avant M7

### M0

Verdict : **ADOPTER_AVEC_CONTRAINTES**.

- Java 24 + Maven Wrapper qualifiés ;
- SCIP Java / TypeScript mesurés ;
- backend MINOS léger retenu par défaut ;
- Glean optionnel ;
- frontière fournisseur préservée.

Décision : `docs/m0/DECISION_M0.md`.

### M1

- découverte Java / TypeScript ;
- Maven / npm factuels ;
- modules et racines source/test ;
- `.gitignore` / `.minosignore` ;
- registre projets/workspaces ;
- négociation des indexeurs ;
- lifecycle et promotion atomique.

Suivi clôturé : issue #6.

### M2 à M5

```text
M2  86 tests   BUILD SUCCESS
M3 115 tests   BUILD SUCCESS
M4 131 tests   BUILD SUCCESS
M5 140 tests   BUILD SUCCESS
```

Décisions :

- `docs/m2/DECISION_M2.md` ;
- `docs/m3/DECISION_M3.md` ;
- `docs/m4/DECISION_M4.md` ;
- `docs/m5/DECISION_M5.md`.

### M6 — Intelligence d’architecture

M6 a été livré en sept incréments, PR #14 à #20, puis consolidé par PR #21.

Acquis :

- modules et namespaces ;
- dépendances inter-modules ;
- concentration ;
- centralité relative directionnelle ;
- technologies factuelles ;
- vue d’architecture composée ;
- contexte compact de module ;
- distinction faits / dérivations / preuves.

Porte finale :

```text
116 sources main
58 sources test
162/162 tests PASS
BUILD SUCCESS
```

Décision : `docs/m6/DECISION_M6.md`.

## M7 — Indexation incrémentale

Suivi : issue #22.

### M7.1 — Empreintes et ChangeSet — LIVRÉ

PR #23, merge :

```text
34b57dfadad962b98c2d5c028957595cee575400
```

Acquis :

- `FileFingerprint` ;
- `ProjectFingerprint` ;
- `ProjectChangeSet` ;
- `ProjectFingerprintService` ;
- empreintes indépendantes du chemin absolu et des timestamps ;
- fichiers ajoutés/modifiés/supprimés/identiques ;
- empreinte build séparée.

Porte :

```text
120 sources main
60 sources test
167/167 tests PASS
BUILD SUCCESS
```

Replay réel : `files=13` sur `typescript-modules`.

Document : `docs/m7/FINGERPRINTS_AND_CHANGESET.md`.

### M7.2 — Snapshots persistants — LIVRÉ

PR #24, merge :

```text
379b5a28a92cb58b340dc8801d66fad1b853e4ce
```

Acquis :

- snapshot fingerprint associé à `projectId + indexSnapshotId` ;
- historique immuable ;
- publication séparée de la promotion ;
- pointeur actif atomique ;
- checksum et recalcul des agrégats ;
- alignement explicite avec `ProjectIndexState.activeSnapshotId`.

Porte :

```text
124 sources main
63 sources test
176/176 tests PASS
BUILD SUCCESS
```

Document : `docs/m7/FINGERPRINT_SNAPSHOTS.md`.

### M7.3 — Invalidation conservatrice — EN VALIDATION

Branche : `m7/conservative-invalidation`.

Contrats :

- `ProjectInvalidationScope` ;
- `ProjectInvalidationReason` ;
- `ProjectInvalidationAssessment` ;
- `ProjectInvalidationService`.

Portées :

```text
NONE
PARTIAL_CANDIDATE
FULL_REQUIRED
```

Règles principales :

- pas d’index actif → `FULL_REQUIRED` ;
- baseline absente ou désalignée → `FULL_REQUIRED` ;
- build modifié → `FULL_REQUIRED` ;
- `.gitignore` / `.minosignore` modifié → `FULL_REQUIRED` ;
- changement non qualifiable → `FULL_REQUIRED` ;
- uniquement sources/tests reconnus → `PARTIAL_CANDIDATE` ;
- aucun changement → `NONE`.

`PARTIAL_CANDIDATE` ne signifie pas encore qu’un fournisseur sait exécuter une
indexation partielle.

Document : `docs/m7/CONSERVATIVE_INVALIDATION.md`.

## Porte active

```powershell
.\mvnw.cmd clean verify
```

La branche M7.3 doit rester en Draft jusqu’à validation locale de son SHA exact.

## Suite prévue

M7.4 devra ajouter une capacité fournisseur explicite d’indexation incrémentale et
combiner cette qualification avec l’évaluation M7.3 pour produire un plan sûr :

```text
PARTIAL_CANDIDATE + capacité fournisseur prouvée -> incrémental possible
sinon                                         -> indexation complète
```

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M7 : issue #22 ;
- M7.1 : `docs/m7/FINGERPRINTS_AND_CHANGESET.md` ;
- M7.2 : `docs/m7/FINGERPRINT_SNAPSHOTS.md` ;
- M7.3 : `docs/m7/CONSERVATIVE_INVALIDATION.md` ;
- promotion atomique : ADR-0006 ;
- identité registre : ADR-0007 ;
- négociation indexeurs : ADR-0008.
