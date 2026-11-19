# Proposal

## Why

Constat **AUD-QUA-04** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité faible ; déjà relevé par AUD-QUA-12 du 2026-10-10) : la CLI recopie champ à champ, dans des enregistrements miroirs, des résumés que `minos-application` expose déjà et qu'elle importe. Chaque champ ajouté à une preuve d'artefact ou au plan d'indexation doit l'être à trois endroits (résumé applicatif, vue de la CLI, conversion) ; en cas d'oubli, la donnée disparaît en silence de la sortie de la CLI.

## What Changes

- Les deux miroirs stricts de la CLI sont supprimés : `RemoteIndexOperations.RemoteMaterializationView` et `RemoteIndexOperations.ArtifactEvidence`. Le port `RemoteIndexOperations` et la commande `remote` utilisent directement `RemoteMaterializationSummary` et `ArtifactEvidenceSummary` de `minos-application`.
- Les vues qui ne sont pas des miroirs stricts sont conservées, parce qu'elles assainissent des textes ou changent la forme d'un champ : `ProviderView`, `IndexPlanView`, `ResumeView`, `IndexExecutionView`, `RemoteIndexView`. Leur raison d'être est écrite dans leur Javadoc.
- Un test de parité par réflexion exige que toute composante d'un résumé applicatif ait sa composante dans la vue correspondante, ou figure dans une table d'exclusions justifiées : un champ ajouté au résumé sans suite dans la vue fait échouer le test.

Aucune sortie texte ni JSON de la CLI ne change. Aucune rupture.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `surfaces-publiques` : exigences ajoutées sur l'absence de miroir strict et sur la parité des vues conservées.

## Hors périmètre

- La copie interne à `minos-application` entre `ProjectInspectionService.ProjectView` et la vue de `LocalProjectOperations` (`LocalProjectOperations.java:207-211`) : même motif, non relevé par le constat, à traiter à part si le propriétaire le veut.
- Les DTO publics de `minos-api` : contrat d'API publié, séparation voulue.
- Tout champ ajouté ou retiré d'une sortie.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `ArtifactEvidence` (CLI) et `ArtifactEvidenceSummary` (application) ont les mêmes 12 composantes et les mêmes contrôles | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/operations/RemoteIndexOperations.java:47-73` ; `minos-application/src/main/java/com/minos/application/remote/ArtifactEvidenceSummary.java:10-36` |
| `RemoteMaterializationView` et `RemoteMaterializationSummary` ont les mêmes 9 composantes | [Existant vérifié] | `RemoteIndexOperations.java:24-45` ; `RemoteMaterializationSummary.java:8-29` |
| `ProviderView`, `ResumeView` et `IndexExecutionView` assainissent des diagnostics par `CliCommandSupport` | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/operations/AutonomousIndexOperations.java:62-135` |
| La CLI importe déjà les résumés applicatifs | [Existant vérifié] | `minos-cli/src/main/java/com/minos/cli/operations/LocalRemoteIndexOperations.java:4-7` |
| Seuls les miroirs stricts sont supprimés | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : `minos-cli` (`RemoteIndexOperations`, `LocalRemoteIndexOperations`, `RemoteIndexCommand`, tests).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune sortie ne change ; le protocole du plugin IntelliJ n'est pas touché.
- **ADR** : aucun.
- **Gates à rejouer** : `check-remote-distributed-consistency.py` (lit `LocalRemoteIndexOperationsIntegrationTest.java` et `RemoteIndexCommandTest.java`, où il exige `SecretFree`, `RequiresExplicitWorkerNetworkPolicy`, `token@` et `rendersProjectRelativeRootInJsonAndTextOutput`, `:207-209`), `check-module-boundaries.py` (A11 : aucun type du moteur ajouté à la CLI), `check-comment-language.py` ; scope JaCoCo citant `LocalRemoteIndexOperations` (`check-jacoco.py:152`).
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/surfaces-publiques.md`.

## Questions ouvertes

Aucune.
