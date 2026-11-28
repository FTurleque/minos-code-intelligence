# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement de tests seulement, dans `minos-application`. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `4f4cac814` (PR #829), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `localProjectOperationsDependencies()` assemble `stores.projectRegistry()`, `stores.snapshotStore()`, `stores.indexStateStore()`, `queries.projectInspectionService()`, `indexing.scipArtifactImporter()`, `stores.retentionService()`, `indexImportHistoryStore` | `minos-application/src/main/java/com/minos/application/MinosApplication.java:309-313` |
| `LocalProjectSymbolQuery(MinosApplication)` : `application.projectQueryService()` | `LocalProjectSymbolQuery.java:29-31` |
| Requête de symboles : construite, aucune assertion | `LocalOperationsAssemblyTest.java:96-101` |
| Requête Git et plate-forme des fournisseurs : appels observables affirmés | `LocalOperationsAssemblyTest.java:52-92` |
| Imports réussis : `new LocalProjectOperations(dependencies())`, sans application | `LocalProjectOperationsRetentionTest.java:167-171` |
| Seul cas sur application : artefact manquant, `IllegalArgumentException`, aucun événement | `LocalProjectOperationsRetentionTest.java:99-110` |
| `Fixture.application()` construit l'application sur le registre, l'état, la rétention et l'importeur de la fixture ; racine de composition remplacée par un double jamais appelé | `LocalProjectOperationsRetentionTest.java:210-230` |

## Écarts

1. **Impact plus étroit que la fiche.** La fiche cite un « collaborateur permuté entre deux ports de même type » : les sept collaborateurs ont des types distincts, une permutation ne compile pas. Le risque réel est une autre instance du même port (par exemple un magasin d'état qui n'est pas celui de l'application) ou une rétention oubliée. La preuve proposée le couvre : la suite d'événements n'apparaît que si l'état et la rétention de l'application sont ceux que l'opération appelle.
2. **Observable de la requête de symboles.** `LocalProjectSymbolQuery` ne rend pas son service. L'observable retenu est un appel : un registre double qui enregistre `findProject`. `projectQueryService` est construit sur le registre de l'application (`MinosApplication.java:205`).

## Décisions

### D1 — Preuve par appels observables, sans accès au service [Proposition, recommandation]

- (a) **Recommandé.** Import publié par `new LocalProjectOperations(fixture.application())` : la liste `events` vaut `publish`, `state`, `retention` comme le cas sur collaborateurs. Requête de symboles : registre double qui enregistre ses appels ; `findSymbols` sur l'identifiant du projet de la fixture appelle `findProject` du registre de l'application.
- (b) Exposer le service par l'accès de test (`MinosApplicationTestAccess`) et comparer par `assertSame` : prouve l'identité, pas l'usage, et élargit le `test-jar` que le garde limite à la classe d'accès.

## Vérification

- Rouge : le nouveau cas de la requête de symboles est d'abord écrit avec un registre qui n'est pas celui de l'application (mutation locale) et échoue ; idem pour l'import, en donnant à la fixture une rétention qui n'est pas celle de l'application.
- Correction : `./mvnw -B -ntp -pl minos-application -am test "-Dtest=LocalOperationsAssemblyTest,LocalProjectOperationsRetentionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` vert sur le poste Windows ; jobs `Verify` verts en CI.
- Mutation de la production (copie locale, non commitée) : `localProjectOperationsDependencies()` passe `null` puis un double à la place de `stores.retentionService()` ; le nouveau cas échoue.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de l'exigence ajoutée, à l'archivage).

## Traçabilité

| Constat | Exigence | Tâches | Preuve attendue |
|---|---|---|---|
| AUD-TST-15 | frontieres-de-modules › Les points d'entrée publics des opérations locales sont éprouvés sur une application | 1.1 à 1.5 | `LocalProjectOperationsRetentionTest#aPublishedImportThroughTheApplicationAppliesItsRetention` et `LocalOperationsAssemblyTest#theSymbolQueryOfAnApplicationIsBuiltOverItsQueryService` verts ; rouges sur une rétention ou un registre qui n'est pas celui de l'application ; mutation de `localProjectOperationsDependencies()` rouge ; `Verify` vert sur la PR (tâche 2.2) |
