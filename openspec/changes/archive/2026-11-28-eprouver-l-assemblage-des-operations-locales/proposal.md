# Proposal

## Why

Constat **AUD-TST-15** de l'audit `docs/project/audits/2026-11-15/` (sprint 12, sévérité faible, ouvert le 2026-11-27 au commit `4f4cac81`). Le sprint 11 (AUD-ARC-14) fait assembler par `MinosApplication` les collaborateurs des opérations locales (`localProjectOperationsDependencies()`, `MinosApplication.java:309-313`). Deux tests ne prouvent pas cet assemblage :

- `LocalOperationsAssemblyTest.theSymbolQueryOfAnApplicationIsBuiltOverItsQueryService` (`:96-101`) construit `new LocalProjectSymbolQuery(application)` sans aucune assertion ; seul le refus d'une application `null` est vérifié.
- Le constructeur public `new LocalProjectOperations(MinosApplication)` n'est exercé que par un refus d'artefact (`LocalProjectOperationsRetentionTest.aRefusedArtifactAppliesNoRetention`, `:99-110`) : l'`IllegalArgumentException` précède toute lecture d'un port. Les imports réussis passent par `new LocalProjectOperations(dependencies())` (`:168-171`), sans application.

Un collaborateur mal choisi dans `localProjectOperationsDependencies()` (une autre instance du même port, ou la rétention oubliée) ne ferait rougir aucun test de `minos-application`. Aucun défaut n'est observé : le code relu est correct.

## What Changes

- `LocalProjectOperationsRetentionTest` : un cas fait passer un import SCIP publié par `new LocalProjectOperations(fixture.application())` et affirme la même suite d'événements que l'import sur collaborateurs (`publish`, `state`, `retention`).
- `LocalOperationsAssemblyTest.theSymbolQueryOfAnApplicationIsBuiltOverItsQueryService` affirme par un appel observable que la requête lit les ports de l'application : un registre double enregistre ses appels, et une recherche de symboles sur le projet de ce registre y appelle `findProject`.
- Une exigence ajoutée tient ces deux preuves.

Aucun code de production, aucune signature, aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence ajoutée (les points d'entrée publics des opérations locales sont éprouvés sur une application).

## Hors périmètre

- Changer l'assemblage de `MinosApplication` ou les constructeurs publics (`LocalProjectOperations(MinosApplication)`, `LocalProjectSymbolQuery(MinosApplication)`) : conservés par décision du sprint 11.
- `ModuleArchitectureTest.onlyTheFacadeReadsItsPorts` (référence de méthode `application::gitQuery` peut-être invisible, contrôle positif absent) : réserve d'AUD-ARC-14, non retenue par le sprint 12.
- `UseCaseFacadeTest` (identité du décorateur seulement, réserve d'AUD-ARC-13, point 4) : non retenu par le sprint 12.
- `ProviderPlatformService.defaults` et `LocalProjectGitQuery` : déjà éprouvés par des appels observables (`LocalOperationsAssemblyTest.java`, deux premiers cas).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Test sans assertion sur la requête de symboles | [Existant vérifié] | `minos-application/src/test/java/com/minos/application/LocalOperationsAssemblyTest.java:96-101` |
| `LocalProjectSymbolQuery(MinosApplication)` délègue à `application.projectQueryService()` | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/LocalProjectSymbolQuery.java:29-31` |
| Constructeur sur application exercé par un seul cas de refus | [Existant vérifié] | `LocalProjectOperationsRetentionTest.java:99-110` ; `grep -n "new LocalProjectOperations"` (lignes 104, 125, 133, 169) |
| La fixture sait construire une application sur les mêmes ports, sans racine de composition | [Existant vérifié] | `LocalProjectOperationsRetentionTest.java:210-230` |
| L'assemblage : registre, snapshots, état, inspection, importeur, rétention, historique | [Existant vérifié] | `MinosApplication.java:309-313` |
| Les sept types de `Dependencies` sont distincts : une permutation ne compile pas | [Existant vérifié] | `LocalProjectOperationsRetentionTest.java:176-179` |
| Preuve par la suite d'événements et par un registre qui enregistre | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : `minos-application`, tests seulement.
- **Surfaces publiques** : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `./mvnw -B -ntp -pl minos-application -am test`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`. Scopes JaCoCo : ceux qui couvrent `minos-application` (`scripts/quality/check-jacoco.py`), sans plancher modifié.
- **Plateformes** : tests JUnit purs, sans processus ni système de fichiers hors `@TempDir` ; exécutés par `Verify (ubuntu-24.04)` et `Verify (windows-2022)`.
- **Documentation durable** : dossier `frontieres-de-modules.md` (traçabilité à l'archivage).

## Questions ouvertes

Aucune.
