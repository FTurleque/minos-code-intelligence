# État courant — MINOS

Dernière mise à jour : **23 juillet 2026**

Ce document est le tableau de bord opérationnel compact de MINOS. Les preuves détaillées restent dans les documents de jalon, les décisions et les issues GitHub.

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
M7 — Indexation incrémentale        TERMINÉ, VALIDÉ ET LIVRÉ
M8 — Analyse d’impact               IMPLÉMENTÉ — PORTE LOCALE FINALE EN ATTENTE
M9 à M13                            NON DÉMARRÉS
```

GitHub Actions reste volontairement hors de la porte locale courante ; l’anomalie historique est suivie séparément dans #5.

## Portes acquises

```text
M2   86 tests   BUILD SUCCESS
M3  115 tests   BUILD SUCCESS
M4  131 tests   BUILD SUCCESS
M5  140 tests   BUILD SUCCESS
M6  162 tests   BUILD SUCCESS
M7  196 tests   BUILD SUCCESS
```

Décisions :

- `docs/m0/DECISION_M0.md` ;
- `docs/m2/DECISION_M2.md` ;
- `docs/m3/DECISION_M3.md` ;
- `docs/m4/DECISION_M4.md` ;
- `docs/m5/DECISION_M5.md` ;
- `docs/m6/DECISION_M6.md` ;
- `docs/m7/DECISION_M7.md`.

## M7 — Indexation incrémentale — TERMINÉ

Suivi clôturé : issue #22.

Livraisons :

```text
M7.1  PR #23  merge 34b57dfadad962b98c2d5c028957595cee575400  167/167 PASS
M7.2  PR #24  merge 379b5a28a92cb58b340dc8801d66fad1b853e4ce  176/176 PASS
M7.3  PR #25  merge 8f87a8fbb3f62361f88e38c9a8f22c2da2050ca8  184/184 PASS
M7.4  PR #26  merge c66382705880158b9ccac63b5662b81bf2d8d255  196/196 PASS
```

Head final validé avant fusion :

```text
ab9367dd532891ba5d5099a7bc9fa7d0ef5074f7
```

Porte finale :

```text
134 sources main
69 sources test
196/196 tests PASS
BUILD SUCCESS
```

Acquis principaux :

- fingerprints déterministes fichiers/projet/build ;
- `ProjectChangeSet` ;
- snapshots d’empreintes persistants ;
- invalidation `NONE / PARTIAL_CANDIDATE / FULL_REQUIRED` ;
- capacité `INCREMENTAL_INDEXING` ;
- planification `NONE / INCREMENTAL / FULL` ;
- fallback complet projet ;
- lifecycle avec `mode + changedFiles` ;
- baseline fingerprint avancée uniquement sur workspace stable.

Les versions épinglées `scip-java 0.13.1` et `scip-typescript 0.4.0` ne revendiquent pas `INCREMENTAL_INDEXING` faute de preuve M0 ; elles retombent explicitement en `FULL`.

## M8 — Analyse d’impact — IMPLÉMENTÉ

Suivi : issue #27.

Branche :

```text
m8/impact-analysis
```

Acquis :

- `ImpactAnalysisRequest` ;
- `ImpactAnalysisReport` ;
- `ImpactedSymbol` ;
- `ImpactPathStep` ;
- `ImpactAnalysisService` ;
- `ProjectImpactQuery` / `LocalProjectImpactQuery` ;
- impact direct et indirect ;
- traversée inverse des relations de dépendance pertinentes ;
- cycles neutralisés ;
- profondeur bornée `1..32` ;
- résultats bornés `1..10 000` ;
- meilleur chemin déterministe ;
- confiance conservatrice par minimum des arêtes ;
- tests potentiellement impactés via `RELATED_TEST` M5 ;
- chemin de preuve `RELATED_TEST` conservé séparément du meilleur chemin général ;
- limites dynamiques explicites.

Relations propagées :

```text
TYPE_DEFINITION IMPORTS REFERENCES EXTENDS IMPLEMENTS CALLS
RETURNS ACCEPTS READS WRITES INSTANTIATES DEPENDS_ON INJECTS RELATED_TEST
```

Limites structurées :

```text
UNRESOLVED_RELATIONSHIPS_IGNORED
EXTERNAL_TARGETS_NOT_TRAVERSED
GENERATED_SYMBOLS_NOT_TRAVERSED
DYNAMIC_DISPATCH_NOT_PROVEN
REFLECTION_NOT_PROVEN
RUNTIME_CONFIGURATION_NOT_PROVEN
MAX_DEPTH_REACHED
MAX_RESULTS_REACHED
```

Replay réel : `fixtures/typescript/typescript-modules` depuis `GreetingPort.greet`.

Documents :

- `docs/m8/IMPACT_ANALYSIS.md` ;
- `docs/m8/DECISION_M8.md`.

## Porte active — finale M8

```powershell
.\mvnw.cmd clean verify
```

La PR M8 doit rester Draft jusqu’à validation locale de son **head exact**.

Après porte verte et fusion :

- issue #27 → `completed` ;
- M8 → terminé, validé et livré ;
- M9 — CLI stabilisée → prochain jalon.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M8 : issue #27 ;
- décision M7 : `docs/m7/DECISION_M7.md` ;
- conception M8 : `docs/m8/IMPACT_ANALYSIS.md` ;
- décision M8 : `docs/m8/DECISION_M8.md` ;
- modèle de relations : M3 ;
- tests liés : M5.
