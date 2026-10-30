# Proposal

## Why

**AUD-SEC-10** (faible, confirmé, sprint 7 de l'audit du 10 octobre 2026) regroupe deux expositions volontaires — ce ne sont pas des fuites par message d'erreur, mais des champs de sortie.

- **Racine absolue par le MCP.** `minos_project_structure` rend la fiche projet de `ProjectJson` (`ProjectJson.java:32` : `"rootPath", project.rootPath()`), appelée telle quelle par le backend MCP (`MinosApplicationMcpBackend.java:88`). Le nom du compte local et l'arborescence du poste partent ainsi vers le modèle connecté au serveur MCP.
- **E-mail des auteurs par la CLI et l'API Java.** `GitIntelligenceService.java:159` lit l'adresse de l'auteur de chaque commit ; elle sort en clair par `GitActivityCommand.java:136` (JSON de `git-activity`) et par `LocalMinosMultiRepositoryApi.java:268` (`GitCommitDto.authorEmail`). Le golden `cli-git.golden` en contient **4**. Aucun outil MCP ne l'expose au HEAD.

Ces deux champs appartiennent à des **contrats publics versionnés et additifs** : le plugin IntelliJ lit `rootPath` dans la sortie de la CLI, et `GitCommitDto` est un type de l'API Java (ADR 0018). Les corriger est donc une décision, pas un détail.

## What Changes

Deux lots indépendants.

- **Lot 1 — racine du projet par le MCP** : la réponse MCP ne contient plus de chemin absolu. `ProjectJson` et la CLI ne changent pas. La forme retenue (clé conservée avec une valeur non absolue, ou clé retirée) est une décision en attente (design, D1).
- **Lot 2 — e-mail des auteurs** : le champ et le composant restent ; leur valeur est masquée par défaut et rendue seulement sur demande explicite (option CLI, paramètre de requête de l'API). Le changement de sortie par défaut est une décision en attente (design, D2). L'ADR 0019, qui dit les auteurs exposés comme faits Git, est à amender.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `surfaces-publiques` : une exigence ajoutée (le MCP n'expose pas la racine absolue d'un projet ; la CLI est inchangée).
- `analyse-git` : une exigence ajoutée (l'e-mail d'un auteur n'est rendu que sur demande explicite, sans changer le décompte des auteurs).

## Hors périmètre

- `authorName` : nom d'auteur, fait Git exposé par l'ADR 0019 ; non relevé par l'audit.
- `workTree` (chemin absolu du dépôt) et `originRemote` (URL du dépôt distant, dont la rédaction n'a pas été vérifiée) dans la sortie de `git-activity` (`GitActivityCommand.java:120-121`) et l'API : même nature d'exposition, à ouvrir comme constat distinct ; nommés ici pour ne pas les présenter comme traités.
- `rootPath` dans la sortie de la CLI et dans `ProjectJson` : lu par le plugin IntelliJ (`MinosProjectList.java:47`, `MinosProjectService.java:40`) ; inchangé.
- Les chemins absolus dans les messages d'erreur : changement `ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs`.
- Les autres constats du sprint 7.

## Impact

- **Modules du reactor** : `minos-mcp` (`MinosApplicationMcpBackend`) ; `minos-engine` (`com.minos.git.GitIntelligence`, option de requête) ; `minos-integration-git` (`GitIntelligenceService`) ; `minos-cli` (`GitActivityCommand`) ; `minos-api` (`MinosMultiRepositoryApi`, `LocalMinosMultiRepositoryApi`) ; `minos-app` (goldens).
- **Surfaces publiques** : **MCP** (`minos_project_structure` : valeur ou présence de `rootPath`) ; **CLI** (`git-activity` : valeur par défaut de `authorEmail`, option nouvelle) ; **API Java** (valeur par défaut de `GitCommitDto.authorEmail`, paramètre additif). **IntelliJ** : aucun changement attendu, à prouver par les tests du plugin. **NEXUS** : non relevé.
- **ADR** : **0019 amendé** (e-mail masqué par défaut) ; ADR 0018 respecté (composant conservé) ; ADR 0017 non modifié.
- **Gates à rejouer** : aucun script Python de `scripts/` ne cite `authorEmail` ni `rootPath` (recherche au HEAD) ; goldens `mcp.golden`, `mcp-all-tools.golden` et `cli-git.golden` de `minos-app/src/test/resources/characterization/` ; `python scripts/quality/check-jacoco.py` (scopes `public-api` et `mcp-mapping`) ; `python scripts/docs/check-current-docs.py`.
- **Plateformes** : Windows et Linux ; la forme d'un chemin absolu diffère (lecteur, UNC), les tests couvrent les deux.
