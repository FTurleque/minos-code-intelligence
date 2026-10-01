# Suivi du chantier « Sécurité, sévérité moyenne » (S5, S6, S7, S8, S9, S12, S15)

Chantier ouvert le 2026-10-01 sur `docs/audit/AUDIT-2026-09.md`. Sept constats. L'ordre des lots est celui du risque :
S9 et S12 sont les deux seuls qui donnent quelque chose à un attaquant aujourd'hui (exfiltration d'un secret, détection
éteinte), S5/S6 sont la fondation, S8 et S7/S15 viennent ensuite.

> Les constats S12 et S15 ne figurent que dans la version **locale non commitée** de `AUDIT-2026-09.md` (l'audit
> commité sur `develop` s'arrête avant). Ce chantier ne modifie pas ce fichier.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s9-git` | S9 | à ouvrir | en cours |
| 2 | `sec/s12-audit` | S12 | – | – |
| 3 | `sec/s5-s6-primitives` | S5, S6 (ferme aussi R9) | – | – |
| 4 | `sec/s8-gitignore` | S8 | – | – |
| 5 | `sec/s7-s15-windows` | S7, S15 | – | – |

Base : `origin/develop` au 2026-10-01 (b991ffd2). Une branche, un worktree (`minos-wt/sec-lotN`) par lot, rebasés l'un sur l'autre.

## 1. Lot 1 : S9, un secret ne part pas vers un hôte distant

### Relocalisation

| Cité par l'audit | Réalité |
|---|---|
| nom de la variable de credential | `RemoteRepositoryRequest` (`minos-engine`, `com.minos.remote`) ; lue par `JGitRemoteRepositoryMaterializer.resolveSecret`, envoyée par `JGitRemoteGitClient` |
| `readEnvironment()` | `GitIntelligenceService.open` (`minos-integration-git`) |
| `deleteCacheTree` | `JGitRemoteRepositoryMaterializer` ; primitive existante : `com.minos.io.FileTreeOperations.deleteRecursively` (`minos-engine`) |
| URL à slash final | `RemoteRepositoryRequest.canonicalUri` |
| troncature d'historique | `GitIntelligenceService.analyze` |
| clone shallow | `JGitRemoteGitClient.setDepth(1)` |

### Décisions (écrites avant le code)

1. **Le nom de la variable n'est plus libre : liste blanche par hôte, refus à la construction de la requête.**
   Admis : `MINOS_REMOTE_TOKEN` et `MINOS_REMOTE_TOKEN_<SUFFIXE>` (espace de noms dédié à MINOS, celui que
   `docs/user/remote-indexing.md` documentait déjà), plus la variable usuelle **de l'hôte visé** (github.com :
   `MINOS_GITHUB_TOKEN`, `GITHUB_TOKEN`, `GH_TOKEN` ; gitlab.com : `MINOS_GITLAB_TOKEN`, `GITLAB_TOKEN`). `GITHUB_TOKEN`
   ne peut donc pas partir vers gitlab.com. La règle s'écrit une fois, dans `RemoteHost.isAllowedCredentialVariable`.
2. **Refus, pas tolérance.** C'est un credential : un avertissement laisse partir la fuite pendant la période de
   grâce. Le message liste les noms admis et n'écho pas le nom refusé ; le correctif pour l'utilisateur est de
   renommer sa variable. Le refus survient avant tout accès réseau et avant la lecture de la variable.
3. **La destination était déjà épinglée** (github.com / gitlab.com seulement, `JGitCloneEndpointPinTest` refuse toute
   redirection hors hôte). Le défaut réel est donc « n'importe quelle variable d'environnement part vers github/gitlab »,
   pas « vers un hôte arbitraire ». Le test d'attaque exerce le point de passage (résolveur de secret → client Git) avec
   un client enregistreur plutôt qu'un serveur HTTP local : aucun serveur local ne peut être adressé par une requête
   qui n'accepte que ces deux hôtes. Aucune vraie valeur de secret dans les tests.
4. **Environnement Git** : `readEnvironment()` supprimé. `GIT_DIR`, `GIT_WORK_TREE` et `GIT_CEILING_DIRECTORIES` ne
   redirigent plus l'analyse.
5. **Suppression** : `deleteCacheTree` passe par `FileTreeOperations.deleteRecursively` (sans suivi des liens ni des
   jonctions). La primitive apprend à effacer l'attribut lecture seule DOS (ce que la copie locale faisait pour les
   fichiers pack clonés) : une implémentation en moins, pas une de plus.
6. **URL** : la normalisation vit à un seul endroit (`stripRepositorySuffix`) : slashes finaux puis `.git`.
7. **Historique** : un commit plus ancien que `since` est **sauté**, plus un point d'arrêt. La marche ne s'arrête
   que sur une série de 1 000 commits consécutifs plus anciens ou à 200 000 commits visités
   (limitation `HISTORY_SCAN_LIMIT`). C'est une borne, pas une preuve : une horloge faussée de plus de 1 000 commits
   consécutifs reste invisible, et c'est dit.
8. **Point (6), clone shallow : non traité, nommé en « à traiter plus tard ».** Ce n'est pas un défaut de sécurité mais
   une limite fonctionnelle. L'indexer exigerait de récupérer un commit arbitraire (fetch par SHA, dépendant de
   `uploadpack.allowReachableSHA1InWant` côté serveur) ou d'approfondir le clone, donc de changer le budget réseau, la clé
   de cache et la validation `expectedCommit == HEAD`. C'est une refonte du clone.

### Changements observables

- Une configuration qui nomme une autre variable que celles ci-dessus est désormais refusée au démarrage de
  `remote materialize` / `remote index` (exit 2, message sans valeur). Les tests, le script `scripts/m25/run-remote-e2e.py`
  (`MINOS_M25_E2E_GITHUB_TOKEN` devenu `MINOS_REMOTE_TOKEN_E2E`) et l'aide `--credential-env` ont suivi.
- L'historique d'activité Git compte maintenant des commits récents situés derrière un commit à date faussée.

### Journal

| Commit | Contenu | Preuve |
|---|---|---|
| 1 | S9 complet | tests rouges sans correctif (ci-dessous), verts avec |
| 2 | revue `verif-sec` : V1, V3, V4 | `GitIntelligenceServiceTest`, `LocalProviderWorkspace*` verts |

### Constats de `verif-sec` (lot 1)

| Id | Constat | Sévérité | Résolution |
|---|---|---|---|
| V1 | au plafond de 200 000 commits visités, `HISTORY_SCAN_LIMIT` sans `historyTruncated` : le rapport se contredit | à corriger | corrigé : `historyTruncated = true` et `HISTORY_TRUNCATED` ajoutés |
| V2 | l'arrêt sur 1 000 commits consécutifs plus anciens n'émet aucune limitation | à corriger | **assumé, non signalé** : c'est la coupure `since` normale (`git --since` fait pareil avec sa marge) ; la signaler polluerait tout rapport. Borne documentée en décision 7 |
| V3 | `HISTORY_SCAN_LIMIT` absent de `multi-repo-git.md` | à corriger | corrigé |
| V4 | second effaceur d'arborescence (`ProviderWorkspaceFiles.deleteTree`, `clearReadOnly` copié) | à corriger | corrigé : `deleteTree` ne garde que le contrôle de racine puis appelle `FileTreeOperations.deleteRecursively` ; une copie de `clearReadOnly` en moins |
| V5, V6 | variante bornée de `RunDirectoryRetention` (budget, sémantique propre) ; `trimGitSuffix` d'affichage du remote | remarque | laissés : lot 3 (S5) revoit `RunDirectoryRetention` ; l'affichage n'est pas un point de décision |
| V7 | pas de test de non-suivi de lien sur la voie du cache | remarque | couvert par `FileTreeOperationsTest` (lien symbolique, jonction Windows) ; `deleteCacheTree` n'est plus qu'un appel à cette primitive |
| V8 | `MINOS_REMOTE_TOKEN*` valable pour les deux hôtes | remarque | décision 1 assumée : secret dédié à MINOS, pas un secret ambiant |

### Preuve rouge → vert (2026-10-01)

Correctifs de production retirés (`git stash` de `src/main`), tests rejoués :

- `RemoteRepositoryRequestTest.anUnrelatedEnvironmentVariableCanNeverBeNamedAsTheCredential` : `AWS_SECRET_ACCESS_KEY`
  acceptée (« Expected IllegalArgumentException to be thrown, but nothing was thrown ») ;
- `RemoteRepositoryRequestTest.aTrailingSlashDoesNotProduceADotGitSegment` : `…/demo/` → `https://github.com/acme/demo/.git` ;
- `FileTreeOperationsTest.deletesReadOnlyEntriesSuchAsClonedPackFiles` : `AccessDeniedException` sur le fichier lecture seule ;
- `GitIntelligenceServiceTest.anOldCommitterDateDoesNotHideMoreRecentAncestors` : 1 commit compté au lieu de 2 ;
- `GitIntelligenceServiceTest.theGitEnvironmentCannotRedirectTheAnalysedRepository` : JVM fille avec `GIT_DIR` posée,
  l'analyse ouvrait l'autre dépôt.

Avec les correctifs, les mêmes tests passent (12 + 8 tests ciblés, plus `CliValidInvocationsTest`,
`ExecutionCommandsArgumentRulesTest`, `RemoteIndexCommandTest`).

### Résultats de fin de lot (2026-10-01, Windows 10, JDK 24)

- `./mvnw -B clean verify` : **BUILD SUCCESS**, 15 modules, 1 876 tests, 0 échec, 54 ignorés (déjà ignorés avant ce lot ; aucun ajouté par le lot).
- Gates : `check-module-boundaries.py` SUCCESS (modules=14, sources=508) ; `check-milestone-artifact-references.py` SUCCESS (95 scripts) ; `check-workflow-pins.py` SUCCESS (70 `uses`).
- Windows : tout a été exécuté sous Windows. Linux : non exécuté localement (la CI le fait).

## À traiter plus tard

- **Clone shallow à profondeur 1 (S9 point 6)** : voir décision 8.
