# Design

## Context

AUD-SEC-10 a masqué l'e-mail des auteurs et réduit `rootPath` au nom du répertoire dans le MCP. Il a laissé `workTree` et `originRemote` de l'activité Git, relevés comme « AUD-SEC-10-suite ».

## État vérifié au HEAD

Relevé du 2026-10-28 sur `develop` (`30ce51ba`) :

- `minos-domain/.../git/RepositoryView.java:8-9` : `workTree` et `originRemote` sont des `String`.
- `minos-integration-git/.../GitIntelligenceService.java:332-336` : `workTree` est le chemin réel du dépôt ; `originRemote` vient de `remote.origin.url`, passé par `sanitizeRemote` (`:533-561`). Une URL à schéma et hôte est reconstruite sans `userinfo`, requête ni fragment ; sinon la forme SCP perd ce qui précède `@`, sinon la valeur ressort brute.
- `:366` : `repositoryId = Sha256("remote:" + remote)` (ou `"path:" + workTree` sans remote).
- Surfaces : `GitActivityCommand.java:129-130` (JSON, section `repository`) ; `MinosMultiRepositoryApi.java:172-182` (`GitRepositoryDto`) ; aucun outil MCP. Le plugin affiche le JSON de `git-activity` (`MinosToolWindowPanel.java:227-233`) sans lire ces champs.
- Tests : `GitIntelligenceServiceTest.java:86-99` couvre une URL HTTPS à identifiants ; ni `ssh://`, ni `file://`, ni chemin local, ni URL non analysable. `cli-git.golden` fige `"originRemote":null` (dépôt sans remote).

**Trous confirmés par lecture** : `https://user:p ss@host/x` (espace refusé par `URI`) tombe dans la branche SCP sans `:` après le premier `@` et ressort brut ; `/srv/x.git`, `C:\x` et `file:///x` ressortent tels quels.

## Goals / Non-Goals

**Goals** : aucun identifiant ni chemin local dans `originRemote` rendu ; identité des dépôts inchangée ; limitation déclarée quand le remote est tu.

**Non-Goals** : `workTree` (décision D1 ci-dessous) ; le MCP.

## Decisions

### D1. `workTree` inchangé — **décision du propriétaire (2026-10-28)**

Option retenue « remote seul » : la CLI et l'API Java sont locales ; elles gardent le chemin absolu, comme `rootPath` (D1 d'AUD-SEC-10). La documentation utilisateur le dit.

### D2. Une liste d'autorisation de formes, pas une liste de refus

`originRemote` rendu = forme reconnue, sinon `null` :

1. URL analysable avec schéma **et** hôte, schéma différent de `file` : reconstruite sans `userinfo`, requête ni fragment (règle existante).
2. Forme SCP : `[utilisateur@]hôte:chemin`, où l'hôte a au moins deux caractères et ne contient ni `/` ni `\` (une lettre de lecteur `C:` n'est pas un hôte), et la valeur ne contient pas `://` : rendue `hôte:chemin`.
3. Tout le reste (URL à schéma non analysable, `file:`, chemin POSIX ou Windows, chemin UNC) : `null`.

Fail-closed : une forme inconnue est tue, pas devinée.

### D3. L'identité ne change pas

`repositoryId` est calculé sur la valeur assainie actuelle (`sanitizeRemote`, inchangée) : une empreinte SHA-256 ne rend ni identifiant ni chemin, et la changer casserait l'identité inter-dépôts des dépôts déjà connus (DATA-0019). La forme rendue est calculée à part (`disclosedRemote`).

### D4. Limitation additive

Remote présent mais tu : `ORIGIN_REMOTE_NOT_DISCLOSED` (et non `NO_ORIGIN_REMOTE`, qui reste réservé à l'absence de remote). Ajout à une liste de chaînes : surface additive.

## Direction des dépendances

Aucun changement : la correction reste dans `minos-integration-git`.

## Qualification des capacités

Rédaction du remote : **qualifiée** pour les formes listées en D2, par tests unitaires ; toute autre forme est tue.

## Windows et Linux

Les chemins Windows (`C:\…`, `\\serveur\partage`) et POSIX sont tous deux reconnus comme locaux ; la logique ne dépend pas de l'OS hôte (analyse de chaîne).

## Risks / Trade-offs

- Un remote réseau exotique non reconnu (schéma sans hôte) devient `null` : c'est le choix fail-closed, et la limitation le signale.
