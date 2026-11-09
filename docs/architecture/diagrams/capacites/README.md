# Dossiers durables des capacités

Un dossier par capacité OpenSpec : vues Mermaid du comportement, traçabilité exigence → preuve, écarts constatés
entre spec, code et tests. Les exigences elles-mêmes vivent dans `openspec/specs/<capacité>/spec.md`, qui renvoie
ici par sa section `## Documentation liée`. Format, conventions et commandes :
[guide du schéma detailed-uml](../../../developer/openspec-detailed-uml.md). Cohérence contrôlée par
`scripts/docs/check-capability-dossiers.py` (job `invariants`).

Un dossier décrit la spec **de référence** en vigueur. Un changement OpenSpec actif qui la modifie est signalé
dans le dossier, sans que ses exigences proposées y soient présentées comme livrées.

| Dossier | Capacité | Vues |
|---|---|---|
| [analyse-git.md](analyse-git.md) | `analyse-git` | Séquence d'ouverture d'un dépôt analysé |
| [audit-qualite-code.md](audit-qualite-code.md) | `audit-qualite-code` | Organigramme de l'audit outillé à la demande et de ses refus |
| [chaine-de-livraison.md](chaine-de-livraison.md) | `chaine-de-livraison` | Organigramme du chemin d'une release et de ses refus |
| [client-intellij.md](client-intellij.md) | `client-intellij` | Séquence d'une action de l'IDE jusqu'au processus CLI |
| [confinement-code-non-fiable.md](confinement-code-non-fiable.md) | `confinement-code-non-fiable` | Organigramme du confinement et de ses refus ; séquence du lanceur AppContainer |
| [controle-tenant-heberge.md](controle-tenant-heberge.md) | `controle-tenant-heberge` | Séquence d'une mutation authentifiée et auditée ; modèle d'un tenant |
| [controles-de-fusion.md](controles-de-fusion.md) | `controles-de-fusion` | Organigramme du jugement d'une pull request |
| [decouverte-et-negociation.md](decouverte-et-negociation.md) | `decouverte-et-negociation` | Séquence capture, découverte, négociation, promotion ; états de disponibilité d'un projet |
| [distribution-docker.md](distribution-docker.md) | `distribution-docker` | Séquence de démarrage du plan de requête ; composants images et services |
| [frontieres-de-modules.md](frontieres-de-modules.md) | `frontieres-de-modules` | Vue de composants des modules et de leurs dépendances autorisées |
| [indexation-java-via-wsl.md](indexation-java-via-wsl.md) | `indexation-java-via-wsl` | Composants Windows et distribution ; séquence de `minos index --via-wsl` ; états d'un run |
| [installation-des-providers.md](installation-des-providers.md) | `installation-des-providers` | Séquence d'installation vérifiée d'un outil ; états de scip-java |
| [installeur-windows.md](installeur-windows.md) | `installeur-windows` | Organigramme de post-installation ; séquence de désinstallation Docker |
| [journalisation.md](journalisation.md) | `journalisation` | Séquence d'une erreur de bibliothèque vers stderr ; organigramme d'un journal sans fuite |
| [persistance-snapshots.md](persistance-snapshots.md) | `persistance-snapshots` | Séquence de publication d'un snapshot ; modèle entité-relation PostgreSQL |
| [program-graph-et-architecture.md](program-graph-et-architecture.md) | `program-graph-et-architecture` | Séquence du graphe de programme et de son cache ; classes d'une vue d'architecture |
| [recherche-semantique.md](recherche-semantique.md) | `recherche-semantique` | Séquence d'une recherche hybride ; états de l'index sémantique |
| [socle-d-execution.md](socle-d-execution.md) | `socle-d-execution` | Organigramme du contrôle de provenance Node et toolchains |
| [stockage-prive-et-secrets.md](stockage-prive-et-secrets.md) | `stockage-prive-et-secrets` | Organigramme des primitives d'écriture privée ; séquence de lecture d'un secret PostgreSQL |
| [surfaces-publiques.md](surfaces-publiques.md) | `surfaces-publiques` | Vue de composants des trois surfaces ; séquence des erreurs d'un outil MCP |
| [tests-lies-et-impact.md](tests-lies-et-impact.md) | `tests-lies-et-impact` | Séquence d'une requête d'impact ; modèle du rapport d'impact |

L'avancement et les décisions attendues sont suivis dans
[le suivi du chantier](../../../project/roadmap/openspec-detailed-uml-2026-11/README.md).
