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
M8 — Analyse d’impact               TERMINÉ, VALIDÉ ET LIVRÉ
M9 — CLI stabilisée                 FONCTIONNELLEMENT COMPLET — PORTE FINALE EN ATTENTE
M10 à M13                           NON DÉMARRÉS
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
M8  203 tests   BUILD SUCCESS
```

## M7 — Indexation incrémentale — LIVRÉ

Issue #22 clôturée. Merge final :

```text
c66382705880158b9ccac63b5662b81bf2d8d255
```

Head validé : `ab9367dd532891ba5d5099a7bc9fa7d0ef5074f7`.

Porte : **134 sources main / 69 test / 196 PASS**.

Décision : `docs/m7/DECISION_M7.md`.

## M8 — Analyse d’impact — LIVRÉ

Issue #27 clôturée `completed`.

PR finale : #28.

Head exact validé :

```text
08bbdeab18873a2209f02b58bc8d7e547443ea0f
```

Merge final :

```text
8147db5c246c7bad92c9b6ab21be81084dc64f59
```

Porte finale :

```text
143 sources main
72 sources test
203/203 tests PASS
BUILD SUCCESS
```

Replay réel :

```text
M8 typescript-modules impact: root=GreetingPort, impacts=2, tests=1, max-depth=2, limitations=[DYNAMIC_DISPATCH_NOT_PROVEN, REFLECTION_NOT_PROVEN, RUNTIME_CONFIGURATION_NOT_PROVEN]
```

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, bornes, cycles, tests potentiellement impactés et limites runtime explicites.

Décision : `docs/m8/DECISION_M8.md`.

## M9 — CLI stabilisée — IMPLÉMENTÉ

Suivi : issue #29.

Branche :

```text
m9/stable-cli
```

Surface stabilisée :

```text
minos project add
minos project list
minos project inspect
minos inspect
minos index
minos index-status
minos search
minos find-symbol
minos get-source
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
```

Acquis M9 :

- formats `text` et `json` ;
- codes de sortie `0 / 1 / 2` ;
- erreurs sur stderr ;
- aide globale et par commande, sans création du home ;
- administration du registre projet ;
- inspection factuelle découverte + snapshot actif ;
- import explicite d’un artefact SCIP via `minos index` ;
- `index-status` basé sur un snapshot réellement relisible ;
- architecture M6 exposée en vue projet/module ;
- impact M8 exposé avec chemins, confiance, tests et limitations ;
- replay end-to-end sur `fixtures/typescript/typescript-modules` ;
- encodeur JSON déterministe partagé.

### Frontière d’indexation

Le dépôt ne possède toujours pas de runner de production implémentant `IndexerExecutor` pour lancer automatiquement `scip-java` ou `scip-typescript`.

M9 n’invente pas cette capacité :

```text
artefact SCIP existant
  -> ScipSymbolSnapshotImporter
  -> normalisation MINOS
  -> FileSymbolSnapshotStore
```

Les métadonnées de dernier import ne sont retournées que lorsqu’elles sont réellement enregistrées et alignées sur le snapshot actif.

Documents :

- `docs/m9/CLI.md` ;
- `docs/m9/DECISION_M9.md`.

## Porte active — finale M9

```powershell
.\mvnw.cmd clean verify
```

La PR M9 doit rester Draft jusqu’à validation locale du **head exact**.

Après porte verte et fusion :

- issue #29 → `completed` ;
- M9 → terminé, validé et livré ;
- M10 — Serveur MCP → prochain jalon.

## Sources de vérité

- roadmap : `docs/ROADMAP.md` ;
- état opérationnel : `docs/STATUS.md` ;
- suivi M9 : issue #29 ;
- M8 : `docs/m8/IMPACT_ANALYSIS.md`, `docs/m8/DECISION_M8.md` ;
- M9 : `docs/m9/CLI.md`, `docs/m9/DECISION_M9.md`.
