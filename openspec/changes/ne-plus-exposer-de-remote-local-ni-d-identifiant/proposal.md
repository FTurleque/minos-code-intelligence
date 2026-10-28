# Proposal

## Why

Constat **AUD-SEC-10-suite** de l'audit du 10/10/2026 : l'activité Git rendue par la CLI (`minos git-activity --format json`) et par l'API Java (`MinosMultiRepositoryApi.inspectGit`, `analyzeGitActivity`) expose `originRemote`. Une rédaction existe (`GitIntelligenceService.sanitizeRemote`), mais deux formes de remote lui échappent :

- une URL à schéma que `java.net.URI` refuse (caractère illégal dans un mot de passe, par exemple) ressort telle quelle, identifiants compris ;
- un remote local (`/srv/depot.git`, `C:\depots\x`, `file:///…`) ressort tel quel : c'est un chemin absolu de la machine.

Le plugin IntelliJ affiche ce JSON tel quel dans son onglet Git.

## What Changes

- `originRemote` n'est rendu que pour un remote réseau reconnu (URL à schéma et hôte, ou forme SCP `utilisateur@hôte:chemin`), sans identifiants, requête ni fragment. Toute autre valeur (URL non analysable, remote local) est rendue `null`, avec la limitation additive `ORIGIN_REMOTE_NOT_DISCLOSED`.
- L'identité du dépôt (`repositoryId`) reste calculée comme aujourd'hui : elle n'est pas une sortie lisible (empreinte SHA-256) et ne doit pas changer pour un dépôt déjà connu.
- `workTree` reste rendu en chemin absolu par la CLI et l'API Java : décision du propriétaire (2026-10-28), cohérente avec D1 d'AUD-SEC-10 (la CLI locale garde le chemin absolu, le MCP ne rend pas l'activité Git). La documentation le dit.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `analyse-git` : une exigence ajoutée sur la forme rendue d'`originRemote`.

## Hors périmètre

- `workTree` (décision du 2026-10-28 ci-dessus) ; `rootPath` des réponses CLI (D1 d'AUD-SEC-10).
- Le MCP : aucun outil ne rend l'activité Git.
- Toute autre surface ou tout autre constat.

## Impact

- Modules : `minos-integration-git` (`GitIntelligenceService`), tests du module. Aucun autre module : le champ et sa forme `String` ne changent pas.
- Surfaces publiques : CLI `git-activity` (JSON) et API Java `MinosMultiRepositoryApi` — valeur d'`originRemote` plus restrictive dans les deux cas cités, limitation additive. Aucune signature ne change. `cli-git.golden` reste identique (son dépôt n'a pas de remote).
- Plugin IntelliJ : aucun code changé ; l'onglet Git affiche la valeur assainie.
- ADR : aucun nouveau ; DATA-0019 dit déjà « remote assaini ».
