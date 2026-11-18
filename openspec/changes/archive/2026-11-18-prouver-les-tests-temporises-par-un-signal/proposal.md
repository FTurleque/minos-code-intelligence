# Proposal

## Why

Constat **AUD-TST-06** (Faible, Tests) de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`,
sprint 6), réserve résiduelle d'AUD-TST-10 de l'audit du 2026-10-10 (tests de verrou traités, pas ceux-ci). Quatre
tests reposent sur un délai fixe ou une fenêtre d'horloge réelle :

- `ProviderWriteQuotaSupervisorTest.java:64-65` : `Thread.sleep(200L)` puis affirmation d'**absence** de
  dépassement — verte même si le superviseur n'a pris aucun échantillon ; `:79-81` : affirmation positive après le
  même délai, fausse rouge si aucun échantillon n'a eu le temps d'être pris.
- `ProcessOwnershipTrackerCadenceTest.java:30-38` et son jumeau du plugin
  `MinosProcessSupervisorCadenceTest.java:33-36` : la fenêtre de deux secondes est mesurée par le `Thread.sleep` du
  test ; un test endormi plus longtemps sur un runner chargé compte plus de 30 énumérations.
- `QueryViewWeightTest.java:97-103` : tas « stabilisé » par quatre `System.gc()` suivis de 150 ms d'attente.

La règle du dépôt est le signal positif borné par une échéance qui fait échouer
(`docs/developer/testing.md:73-87`). Le changement est une hygiène de tests : il se fusionne seul.

## What Changes

- Le superviseur de quota d'écriture expose à ses tests le nombre d'échantillons pris ; les deux tests attendent un
  nombre d'échantillons, avec échéance, avant d'affirmer.
- Les tests de cadence du suivi de propriété (runtime et plugin) gardent un suivi réel, ouvrent leur fenêtre après
  une première énumération observée et jugent le nombre d'énumérations sur la durée mesurée par l'horloge monotone
  (au plus 15 par seconde mesurée), au lieu de supposer que le test a dormi exactement deux secondes.
- La mesure du tas retenu par une vue ne dépend plus d'un délai fixe.
- Les noms des méthodes de test affirmés par `scripts/remediation/check-minos-01.py:44-48` sont conservés.

Aucun comportement de production ne change : périodes, bornes et nettoyage du suivi sont identiques ; le code de
production du suivi de propriété et du plugin n'est pas modifié.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `confinement-code-non-fiable` : deux exigences **ajoutées** (preuve de l'absence de dépassement de quota par des
  échantillons observés ; cadence du suivi de propriété jugée sur le temps réellement mesuré). L'exigence « Le suivi
  de propriété par sondage est espacé sans rien céder au nettoyage » est inchangée.
- `client-intellij` : une exigence **ajoutée** (même preuve pour le superviseur du plugin). L'exigence « Le
  superviseur de processus du plugin sonde à cadence bornée » est inchangée.
- `persistance-snapshots` : une exigence **ajoutée** (mesure du tas retenu sans délai fixe).

## Hors périmètre

- Les tests de verrou (AUD-TST-10 du 2026-10-10, clos) et les preuves de verrou muettes (AUD-TST-07, sprint 1,
  changement `faire-echouer-les-preuves-de-verrou-muettes`).
- Les autres `Thread.sleep` des tests (23 fichiers en contiennent) : seuls les quatre sites du constat sont traités ;
  les boucles d'attente bornées avec affirmation positive (`awaitBreach`, `ProviderWriteQuotaSupervisorTest.java:114-117`)
  sont conformes à la règle.
- Toute modification des périodes de sondage ou des bornes de cadence.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Affirmation négative après délai fixe | [Existant vérifié] | `minos-runtime-local/src/test/java/com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisorTest.java:64-65` |
| Affirmation positive après le même délai | [Existant vérifié] | même fichier, `:79-81` |
| Le superviseur ne compte pas ses échantillons | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisor.java:57-60`, `:151-175` |
| Fenêtres de cadence mesurées par le sommeil du test | [Existant vérifié] | `minos-runtime-local/src/test/java/com/minos/runtime/local/process/ProcessOwnershipTrackerCadenceTest.java:30-38` ; `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosProcessSupervisorCadenceTest.java:33-36` |
| La cadence est décidée par `System.nanoTime()` et `Thread.sleep` dans la boucle de suivi | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProcessOwnershipTracker.java:101-116` ; `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosProcessSupervisor.java:221-234` |
| `System.gc()` puis 150 ms, quatre fois | [Existant vérifié] | `minos-storage/src/test/java/com/minos/storage/local/store/QueryViewWeightTest.java:97-103` |
| Aucun test du dépôt ne porte d'étiquette JUnit (`@Tag`) | [Existant vérifié] | recherche de `@Tag(` dans `minos-*/src/test` : aucune occurrence |
| Les scénarios de cadence en vigueur portent sur un suivi réel attaché à un processus factice | [Exigence] | `openspec/specs/confinement-code-non-fiable/spec.md:248-251` ; `openspec/specs/client-intellij/spec.md:295-298` |
| Isoler la mesure de tas dans un test « étiqueté mesure » | [Proposition] écartée | `design.md`, D3 (décision du propriétaire attendue) |

## Impact

- **Modules du reactor** : `minos-runtime-local` (une couture package-private, `sampleCount()` ; tests),
  `minos-storage` (test seul). Hors reactor : `minos-intellij` (test seul).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `scripts/remediation/check-minos-01.py` (noms des méthodes de
  `ProviderWriteQuotaSupervisorTest`), `scripts/architecture/check-module-boundaries.py` (A10),
  `scripts/quality/check-comment-language.py` ; scopes JaCoCo `provider-write-quota-supervisor` et
  `provider-execution-trust-boundary` ; plancher JaCoCo du plugin (`minos-intellij/build.gradle.kts:147-155`).
- **Plateformes** : Windows et Linux, mêmes tests ; le plugin est jugé par le job `IntelliJ plugin (gate)`.
- **Documentation durable et diagrammes** : `docs/developer/testing.md` (un paragraphe : échantillons observés,
  cadence jugée sur la durée mesurée), dossiers de capacité `confinement-code-non-fiable.md`, `client-intellij.md`,
  `persistance-snapshots.md`.

## Questions ouvertes

Aucune bloquante. D3 (mesure de tas) attend la décision du propriétaire, avec recommandation.
