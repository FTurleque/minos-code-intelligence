# Proposal

## Why

Constat **AUD-TST-03** (Faible, Tests) de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`,
sprint 6) : quatre classes de la frontière de confiance des providers portent des planchers JaCoCo de classe très
bas dans `scripts/quality/check-jacoco.py` :

| Classe | Plancher ligne / branche | Ligne |
|---|---|---|
| `ProviderProcessEnvironment` (filtre d'environnement des processus, règle de sécurité n° 3) | 0,70 / 0,20 | `:182` |
| `StrongProcessOwnershipIndexerExecutor` | 0,35 / 0,18 | `:181` |
| `ScipJavaProcessPlanFactory` (plan d'exécution de scip-java) | 0,35 / 0,15 | `:143` |
| `ManagedPolyglotScipRuntimeManager` | 0,28 / 0,20 | `:141` |

Le filtre d'environnement peut perdre l'essentiel de ses branches couvertes sans faire échouer le gate. Le critère de
sortie du sprint demande un plancher de branches d'au moins 0,50 sur ces classes. Le changement ne touche que le gate
et des tests : il se fusionne seul, après le changement du sprint 1 qui édite le même fichier.

## What Changes

- Les quatre planchers de classe sont relevés à la plus basse des mesures de la CI (Ubuntu 24.04 et Windows Server
  2022) moins environ 5 points, règle des planchers de classe du dépôt, avec un minimum de 0,50 en branches ; 0,80 en
  branches pour `ProviderProcessEnvironment`.
- Là où la mesure est sous la cible, des tests sont ajoutés **avant** de relever le plancher.
- Un plancher de classe reste un seul nombre par compteur, valable sur les deux systèmes.
- L'auto-test du gate refuse qu'une de ces quatre classes porte un plancher de branches inférieur à son minimum.

Aucun code de production n'est modifié, sauf extraction rendue nécessaire par un test (alors nommée dans la PR).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : une exigence **ajoutée** (plancher de branches minimal des classes de la frontière de
  confiance des providers). Les exigences existantes sur les scopes et planchers de classe sont inchangées.

## Hors périmètre

- La création des scopes des zones nouvelles (`explorer-surface`, `wsl-lane`, `semantic-projection`) : AUD-TST-01,
  changement `borner-la-couverture-des-zones-nouvelles` (sprint 1).
- Le refus d'un nom de secret hérité (AUD-SEC-05, changement `refuser-un-nom-de-secret-herite-et-une-garde-illisible`,
  sprint 2) : il ajoute un test de `ProviderProcessEnvironment` dont ce changement bénéficie ; aucune exigence de
  comportement du filtre n'est écrite ici.
- Les autres planchers de classe bas des mêmes scopes (`BoundedProviderSourceProbe` 0,55 / 0,35, `JGitCloneDeadline`
  0,48 / 0,42, `RemoteIndexingUseCase` 0,65 / 0,35) : non relevés par le constat, cités comme écart.
- Les planchers de scope (`m24-polyglot-provider-platform` 0,50 / 0,28, `provider-execution-trust-boundary`
  0,68 / 0,48).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les quatre planchers cités | [Existant vérifié] | `scripts/quality/check-jacoco.py:141`, `:143`, `:181`, `:182` |
| Un plancher de **scope** peut être écrit par système ; un plancher de **classe** est lu comme un nombre (`float(minimums["line"])`), un dictionnaire y lèverait `TypeError` | [Existant vérifié] | `scripts/quality/check-jacoco.py:327-331`, `:656-657` |
| Règle des planchers de classe : « mesure moins environ 5 points » | [Existant vérifié] | `docs/developer/quality-gates.md:225` |
| Une clé de `prefixMinimums` absente de `prefixes` fait échouer le gate | [Existant vérifié] | `scripts/quality/check-jacoco.py:647-652` |
| `ProviderProcessEnvironment` a trois classes de test dédiées | [Existant vérifié] | `minos-runtime-local/src/test/java/com/minos/runtime/local/process/ProviderProcessEnvironmentTest.java`, `…AllowlistTest.java`, `…DeclaredSecretsTest.java` |
| Le motif de refus est une fonction pure | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:133-140` |
| Mesure actuelle des quatre classes sur la CI | [Question] bloquante pour les valeurs, pas pour le découpage | non lue : aucun build ni artefact consulté ; tâche 1.1 |
| 0,50 en branches est atteignable sur les deux systèmes pour `StrongProcessOwnershipIndexerExecutor` et les deux classes SCIP | [Question] | dépend de la mesure ; `design.md`, D2 |

## Impact

- **Modules du reactor** : `minos-runtime-local` et `minos-provider-scip` (tests) ; `scripts/quality/check-jacoco.py`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py --self-test`, `python scripts/quality/check-jacoco.py`
  (scopes `provider-execution-trust-boundary` et `m24-polyglot-provider-platform`),
  `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`.
- **Plateformes** : Windows et Linux ; le scope `m24-polyglot-provider-platform` est rouge sur poste Windows hors
  régression : les valeurs se lisent sur les artefacts `jacoco-modules-Linux` et `jacoco-modules-Windows` de la CI.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

- Valeurs exactes des planchers : fixées par la tâche 1.1 (lecture des artefacts de la CI), non inventées ici.
- D2 : que faire si une classe ne peut pas atteindre 0,50 sur le système où elle mesure le moins (décision du
  propriétaire, recommandation dans `design.md`).
