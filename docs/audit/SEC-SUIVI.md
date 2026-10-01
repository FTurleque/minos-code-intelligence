# Suivi du chantier « Sécurité, sévérité moyenne » (S5, S6, S7, S8, S9, S12, S15)

Chantier ouvert le 2026-10-01 sur `docs/audit/AUDIT-2026-09.md`. Sept constats. L'ordre des lots est celui du risque :
S9 et S12 sont les deux seuls qui donnent quelque chose à un attaquant aujourd'hui (exfiltration d'un secret, détection
éteinte), S5/S6 sont la fondation, S8 et S7/S15 viennent ensuite.

> Les constats S12 et S15 ne figurent que dans la version **locale non commitée** de `AUDIT-2026-09.md` (l'audit
> commité sur `develop` s'arrête avant). Ce chantier ne modifie pas ce fichier.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s9-git` | S9 | à ouvrir | en cours |
| 2 | `sec/s12-audit` | S12 | à ouvrir | **déjà corrigé** (`c380baa3`), preuve par mutation, aucun code |
| 3 | `sec/s5-s6-primitives` | S5, S6 (ferme aussi R9) | à ouvrir | **code terminé**, non poussé ; gate `check-private-io` |
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

## 2. Lot 2 : S12, le constat est périmé

**Le correctif existe déjà dans `develop`.** `c380baa3` (« fix(hosted): S12 », chantier Résidus du sprint 1, décrit dans
`RESIDUS-SPRINT-1-SUIVI.md`) a remplacé la garde `auditEvents().size() < deniedAuditCapacity()` par
`HostedRetentionPolicy.admitsChainedDenial(chainedDenials, chainSize)` : la réserve de refus est comptée sur les
événements `DENIED` retenus (`HostedAuthorizationService.chainedDenials`), jamais sur le total. L'audit local non
commité qui décrit S12 comme ouvert est antérieur à ce correctif. S13 est dans le même cas (`f0f70b6d`, refus non
chaîné marqué `UNCHAINED`, séquence 0, HMAC dans un domaine séparé), et n'est de toute façon pas dans ce chantier.

**Vérification par l'attaque, sans toucher au code** (2026-10-01, `HostedDeniedAuditReserveTest`,
`HostedDenialSaturationTest`, `HostedModelTest` : 19 tests, tous verts sur `develop`).
Mutation témoin : la condition remise à l'ancienne forme (`chainSize < deniedAuditCapacity()`), tests rejoués, **7 échecs sur
19** (puis fichier restauré, `git status` propre) :

- `firstAttackRefusalIsChainedAfterNinetyPercentOfAuthorizedEvents` : attendu `DENIED`, reçu `ALLOWED` (le premier refus d'une
  attaque, après 90 % d'événements autorisés, n'est plus chaîné) ;
- `firstAttackRefusalIsChainedWhileAuthorizedEventsAwaitAnExplicitRetention`, `reserveCountsOnlyChainedRefusals`,
  `explicitRetentionReleasesTheReserveAndKeepsTheChainContiguous`, `chainedDenialAdmissionCountsRefusalsAndKeepsAuthorizedHeadroom` ;
- `refusalsNeverConsumeTheAuthorizedHeadroomBelowTheHardCapacity` et
  `refusalsStopBeingChainedAtTheDeniedCapacityEvenAcrossProcesses` : la borne dans l'autre sens (un attaquant qui ne
  produit que des refus) est elle aussi gardée : 90 refus chaînés au plus, la chaîne ne grossit pas sans fin, la marge
  d'un dixième sous la capacité dure reste aux mutations autorisées.

**Ce qui arrive quand la réserve de refus est épuisée** : le refus est appliqué (`SecurityException`
« hosted permission denied »), livré au puits d'audit comme événement non chaîné, l'état du tenant est intact (pas de
version consommée).

**S13** : un correctif existe ; l'embarquer ici n'a pas de sens, il est fermé. Rien à signaler.

**Conséquence pour le chantier** : lot 2 sans changement de code, PR de documentation seule portant cette preuve.

## 3. Lot 3 : S5 et S6, les primitives d'E/S sont l'unique chemin

### Relocalisation

| Cité par l'audit | Réalité |
|---|---|
| magasin de paquets d'artefacts distribués | `DistributedArtifactBundleStore` (`minos-runtime-local`, `com.minos.runtime.local`) |
| worker isolé, préparation du répertoire de run, sortie bornée, rétention des runs | `LocalIsolatedIndexWorker`, `ProcessIndexerExecutor.prepareRunDirectory`, `BoundedProcessOutput.openTarget`, `RunDirectoryRetention` (même module) |
| registre de baux de cache partagé | `SharedCacheLeaseRegistry` (`minos-engine`, `com.minos.io`) |
| magasin du plan de contrôle hébergé, compaction de rétention | `FileHostedControlPlaneStore`, `LocalStorageRetentionService.compact` (`minos-storage-local`, `com.minos.storage.local.*`) |
| verrou de matérialisation Git, `pin()`, `writeProperties` | `JGitRemoteRepositoryMaterializer` (`minos-integration-git`) |
| registre de projets | `LocalProjectRegistry`, `ProjectPathMappingStore` (`minos-storage-local`, `...local.registry`) |
| lecture d'un secret absolu | `MinosRuntimeSettings.readAbsoluteSecret` (`minos-engine`, `com.minos.storage`) |
| TOCTOU du codec d'observations | `RuntimeObservationEnvelopeCodec.read` (`minos-application`, `com.minos.application.dynamic`) |

### Décisions (écrites avant le code)

1. **Aucune quatrième primitive.** `PrivateLocalStorage`, `ConfinedFileOpener`, `DurableAtomicFile` (et `BoundedFileLease`,
   `SharedCacheLeaseRegistry`, `BoundedProperties`, `FileTreeOperations`) restent l'unique implémentation. Ajouts, tous dans
   l'existant : `ConfinedFileOpener.openRegularFileNoFollow` (une ouverture NOFOLLOW, flux rendu), `PrivateLocalStorage.writePrivateFile`
   et `createPrivateTempDirectory`, `BoundedFileLease.openPrivateLockChannel` (fichier de verrou privé, NOFOLLOW, message sans
   chemin ; `SharedCacheLeaseRegistry` s'en sert).
2. **S6 réutilise la mécanique, ne la réécrit pas.** `FileHostedControlPlaneStore.lock` et `LocalStorageRetentionService.compact`
   passent par `BoundedFileLease.acquire` (verrou JVM par bande + verrou fichier, une échéance de 10 s, `IOException`
   « timed out waiting for … », aucun chemin). `compact` gagne ainsi le verrou JVM qui lui manquait : deux threads qui compactent
   se sérialisent au lieu de lever `OverlappingFileLockException` (ferme R9). La copie de la boucle d'attente de
   `JGitRemoteRepositoryMaterializer.acquireFileLock` est **supprimée** : la matérialisation passe par `BoundedFileLease` avec son
   délai propre de 2 minutes (le verrou est tenu pendant un clone ; ce n'est pas un chemin à 10 s).
3. **Le TOCTOU se ferme en n'ouvrant qu'une fois.** `RuntimeObservationEnvelopeCodec.read` n'a plus de contrôle de chemin : il
   ouvre par `openRegularFileNoFollow` et ne lit que ce flux. Le seul regard avant l'ouverture est un `lstat` de type, qui sert à ne
   pas se bloquer sur un FIFO ; il ne décide jamais *quel* fichier est lu (l'ouverture est NOFOLLOW). Limite dite : un FIFO posé
   entre ce regard et l'ouverture, par quelqu'un qui écrit déjà dans le répertoire, peut encore bloquer l'ouverture (déni de
   service, pas de lecture détournée) ; Java n'expose pas de `fstat` sur le descripteur ouvert.
4. **L'entrée propriétaire d'un répertoire privé est héritable (ACL Windows).** Sans cela, ce qu'un processus du propriétaire crée
   plus tard dans le répertoire (l'artefact qu'un provider sandboxé écrit dans le répertoire de run, une extraction d'outil)
   prenait l'ACL par défaut de son créateur : les tests `AppContainer` réels ont échoué (`AccessDenied` sur l'artefact) tant que
   cette entrée n'était pas héritable. Toujours aucun autre principal : `verifyPrivacy` est inchangé.
5. **Un fichier qui occupe le nom d'un répertoire est refusé en `FileAlreadyExistsException`** (la famille de `Files.createDirectories`,
   qu'un test existant de `LocalRemoteIndexOperations` attendait déjà), sans nommer le chemin.
6. **Aucun chemin absolu** dans les messages que ce lot touche : verrous (`BoundedFileLease`), secret absolu, refus de la primitive.
7. **Le gate est une règle de source Python**, pas ArchUnit (le dépôt n'a pas ArchUnit) : `scripts/architecture/check-private-io.py`,
   à côté de `check-module-boundaries.py`, avec auto-test. Voir « Le gate ».

### Sites migrés

| Famille | Sites |
|---|---|
| verrous (S6) | `FileHostedControlPlaneStore.lock`, `LocalStorageRetentionService.compact` (+ verrou JVM), `JGitRemoteRepositoryMaterializer.materialize`, `SharedCacheLeaseRegistry` (fichiers `.lease` privés, NOFOLLOW) |
| lectures NOFOLLOW uniques | 15 fichiers (`ExecutionCheckpoints`, `FileProgramGraphProvider`, `LockedNpmPackage`, `ManagedScip*`, `DistributedArtifactBundleStore`, `ProcessIndexerExecutor`, magasins de `minos-storage-local`, `PostgresCodeKnowledgeSnapshotStore`) + le codec (TOCTOU) + le secret absolu |
| répertoires privés | magasin de paquets, worker isolé, répertoire de run, sortie bornée, rétention des runs, racines de `ScipProjectSnapshotLifecycle`, installations d'outils (`ManagedScip*`, `LockedNpmPackage`), baux d'indexation distante, sandbox Windows (récupération, lanceurs) |
| fichiers privés | pin et métadonnées du cache Git, manifeste et artefact du cache de paquets, marqueur de reprise, empreinte d'artefact, journaux des providers, plan des sandbox Windows, registre de projets, mapping de chemins, état d'index, empreintes, snapshots, historique CLI (copies temporaires passées de `Files.createTempFile` à `createPrivateTempFile`) |

### Le gate

`scripts/architecture/check-private-io.py` interdit, dans `src/main/java` de tous les modules sauf `minos-intellij`, hors des sept
primitives nommées (`PrivateLocalStorage`, `ConfinedFileOpener`, `DurableAtomicFile`, `FileTreeOperations`, `BoundedFileLease`,
`BoundedProperties`, `SharedCacheLeaseRegistry`) : `Files.createDirectories`, `Files.write`, `Files.writeString`,
`Files.newInputStream`, `FileChannel.open`, `FileChannel.lock`/`tryLock` (récepteur nommé `*channel*` ou tout `FileLock`).
**Les tests (`src/test`) et les ressources (`src/main/resources`, scripts embarqués) sont exclus par construction** : la règle
gouverne le code que MINOS livre, pas ses fixtures. Commentaires et littéraux ignorés ; import statique et nom qualifié détectés.

La liste blanche `scripts/architecture/private-io-allowlist.json` est nominative (fichier + méthode + maximum + justification
écrite) ; jamais un répertoire, jamais un joker (refusés à la lecture) ; **cliquet** : une occurrence hors liste, ou une de plus
dans un fichier listé, échoue ; une entrée dont le maximum dépasse le compte réel échoue aussi, la liste ne peut que rétrécir.
Branché dans `.github/workflows/pr-ci.yml` (gate + auto-test, juste après `check-module-boundaries`) et dans `run-final.sh` /
`run-final.ps1`. Auto-test `test_check_private_io.py` : 21 cas, dont **une mutation témoin par interdiction** (6) et une par
échappatoire (répertoire, joker, justification vide, maximum périmé, occurrence en trop, doublon, fichier absent, primitive).

| Fichier (module) | Méthode | Max | Pourquoi |
|---|---|---|---|
| `FingerprintConstrainedJavaProgramGraphProvider` (application) | `Files.newInputStream` | 1 | source du projet utilisateur ; **à migrer**, hors S5 nommé |
| `JavaSourceWorkspace` (application) | `Files.newInputStream` | 1 | idem |
| `MinosMcpHandshakeProbe` (mcp) | `Files.createDirectories` | 1 | crée MINOS_HOME lui-même, chemin explicite en argument |
| `ManagedScipProviderRuntimeManager` (provider-scip) | `Files.createDirectories` | 2 | sous-répertoires d'extraction d'une archive d'outil, sous une racine déjà privée |
| `RustAnalyzerScipProcessPlanFactory` (provider-scip) | `Files.createDirectories` | 1 | répertoire d'écriture du provider sandboxé (accès par héritage d'ACL) |
| `ScipJavaProcessPlanFactory` (provider-scip) | `Files.createDirectories` | 4 | sortie et arbre de travail du provider sandboxé |
| `DistributedArtifactBundleStore` (runtime-local) | `Files.createDirectories` | 1 | `createBundle` écrit vers la sortie explicite de l'appelant |
| `LinuxBubblewrapWorkerSandboxBackend` (runtime-local) | `Files.createDirectories` | 1 | parent de l'artefact dans l'arbre de travail sandboxé ; **à revoir**, hors S5 nommé |
| `WindowsAppContainerWorkerSandboxBackend` (runtime-local) | `Files.createDirectories` | 1 | idem |
| `LinuxCgroupJob` (runtime-local) | `Files.writeString` | 4 | interface noyau cgroup v2, pas des fichiers MINOS |
| `ProviderWorkspaceFiles` (runtime-local) | `Files.createDirectories` | 2 | copie du projet dans l'arbre de travail sandboxé (accès conteneur par héritage d'ACL) |

11 entrées, 19 occurrences, sur 508 sources. Limite du gate, dite : il lit le texte, pas les types. Un verrou de fichier se
reconnaît à un récepteur nommé `*channel*` ou au type `FileLock` ; un `FileChannel` obtenu d'ailleurs, nommé autrement et dont le
verrou n'est jamais stocké, passerait. `Files.newOutputStream`, `Files.createTempFile`, `Files.createDirectory` et `Files.copy`
ne sont pas dans la liste demandée et ne sont pas contrôlés (voir « À traiter plus tard »).

### Journal

| Commit | Contenu |
|---|---|
| 1 | primitives (`openRegularFileNoFollow`, `writePrivateFile`, `createPrivateTempDirectory`, `openPrivateLockChannel`) ; `SharedCacheLeaseRegistry` privé |
| 2 | S6 : verrous du magasin hébergé et de la compaction |
| 3 | S5/S6 : verrou de matérialisation Git, `pin`, `writeProperties` |
| 4 | S5 : TOCTOU du codec d'observations |
| 5 | S5 : entrée propriétaire héritable, refus d'un fichier occupant un répertoire |
| 6 | S5 : secret absolu |
| 7-9 | S5 : `minos-runtime-local` ; `minos-storage-local` + application + PostgreSQL ; `minos-provider-scip` + `minos-cli` |
| 10 | gates de remédiation alignés sur les primitives |
| 11 | le gate `check-private-io` |

### Preuve rouge → vert (2026-10-01)

Pour chaque correctif, le test d'attaque a été écrit avant, rejoué contre le `src/main` d'avant (`git stash` des fichiers de
production concernés, ou la version `HEAD` d'un seul fichier), puis contre le correctif. Les nombres sont ceux observés.

| Cible | Test | Sans correctif | Avec |
|---|---|---|---|
| `SharedCacheLeaseRegistry` | `SharedCacheLeaseRegistryTest` (+3) | **3 échecs sur 14** : fichier `.lease` suivi à travers un lien (acquire et éviction), droits `EXPOSED` | vert |
| compaction | `LocalStorageRetentionLockTest` (5) | **5 échecs sur 5** : `OverlappingFileLockException` (deux threads, et verrou tenu ailleurs), attente non bornée, lien suivi, droits `EXPOSED` | vert |
| magasin hébergé | `FileHostedControlPlaneStoreLockTest` (4) | **3 échecs sur 4** : `OverlappingFileLockException`, aucune attente, fichiers `EXPOSED` ; le lien sur le verrou était déjà refusé (`rejectUnsafeEntry`), gardé en non-régression | vert |
| matérialisation Git | `JGitRemoteRepositoryMaterializerLockTest` (5) | **3 échecs sur 3** rejoués (lien sur le verrou suivi, verrou `EXPOSED`, `pin` écrit à travers un lien et `EXPOSED`) ; les deux tests de délai ne sont pas rejoués en rouge (l'ancien code attendait 2 minutes) | vert |
| TOCTOU du codec | `RuntimeObservationEnvelopeCodecLinkSwapTest` | **6 rejouées sur 6 rouges** (le codec a lu l'enveloppe étrangère après environ 120 lectures) ; une première version du test, dont le thread de bascule mourait à la première erreur, n'était rouge que 3 fois sur 7 : corrigée | 4 rejouées sur 4 vertes |
| primitive d'ouverture | `OpenRegularFileNoFollowTest` | test déterministe (la feuille est échangée par la couture entre le regard et l'ouverture) : la primitive est neuve, son rouge est l'ancien « regarder puis rouvrir », couvert par la ligne précédente | vert |
| `minos-runtime-local` | `RuntimeStoragePrivacyTest` (6) + `LocalIsolatedIndexWorkerTest` (+1) | **5 échecs sur 6** et **1 sur 1** (journaux, répertoire de run, empreinte, marqueur, racine des runs, cache de paquets, répertoires du worker : `EXPOSED`) ; le lien sur le journal était déjà refusé, gardé | vert |
| `minos-storage-local` | `LocalStorageTreesArePrivateTest` (2) | **2 échecs sur 2** (registre, mapping, empreintes, snapshots, état : fichiers `EXPOSED`) | vert |
| `minos-provider-scip` | `ScipRuntimeStoragePrivacyTest` (2) | **1 échec sur 2** (racine d'installation npm `EXPOSED`) | vert |
| ACL héritable | `aFileCreatedLaterInAPrivateDirectoryInheritsOwnerOnlyAccess` | rouge (`EXPOSED`) ; en production : 3 tests `AppContainer` réels en `AccessDenied` | vert, et les 3 tests sandbox verts |
| fichier occupant un répertoire | `aFileOccupyingTheDirectoryNameIsRefused…` | rouge (`IOException` au lieu de `FileAlreadyExistsException`) ; en production : `releasesMaterializationWhenRemoteIndexLeaseAcquisitionFails` | vert |
| secret absolu | `AbsoluteSecretFileTest` (5) | **4 échecs sur 5** (chemin absolu dans le message : fichier absent, vide, lien, répertoire) | vert |
| baux d'indexation distante | `RemoteIndexLeaseTest` (+1) | **pas rouge** : `BoundedFileLease` durcissait déjà le répertoire en créant le fichier de verrou ; le changement est un nettoyage (un `createDirectories` en moins), la garde est une non-régression | vert |

Tests ignorés par ce lot : **0** de mes tests ne sont ignorés sur cette machine (les liens symboliques se créent ; mes tests de
lien s'appuient sur `assumeTrue` seulement quand la création échoue, ce qui n'est pas le cas ici). `PrivateLocalStorageTest` a 5
tests POSIX ignorés sous Windows, **antérieurs au lot**. Un test de plus est propre à Windows (`@EnabledOnOs`) : ignoré sous Linux.

### Constats de la vérification

- Les gates de remédiation `check-mnd`, `check-audit-remediation-v2` et `check-post-mne` exigeaient le texte brut que les
  primitives remplacent (`jvmLock.lock()`, `Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)`, `Files.walkFileTree` dans JGit).
  Chaque littéral est remplacé par celui de la primitive plus stricte, aucune exigence n'est supprimée.
- **Hérité du lot 1, non corrigé ici** : `check-post-mne.py` échoue encore, par cascade, sur `check-remote-distributed-consistency.py`
  (`RemoteRepositoryRequest.java: missing semantic facts: GITHUB("github.com"), GITLAB("gitlab.com")`) : S9 a déplacé ces faits
  dans `RemoteHost`. Le littéral `Files.walkFileTree` de JGit, cassé par le même S9, est en revanche corrigé ici car ce lot
  réécrit ce fichier.
- Compromis de S6, dit : une compaction qui en attend une autre sur le même projet échoue au bout de 10 s au lieu de lever tout
  de suite (même JVM) ou d'attendre sans fin (autre processus). Une compaction plus longue que 10 s sur un gros projet, relancée
  en parallèle, échouerait donc ; ce n'est pas un chemin chaud (la rétention suit la fin d'une indexation).

### Commandes lancées et résultats

Machine : Windows 10, JDK 24 (liens symboliques disponibles). Aucun `clean`, aucun golden régénéré : les 12 golden de
`characterization/` sont inchangés (`git diff 8c8deca4 HEAD` vide sur ce répertoire).

| Commande | Résultat |
|---|---|
| `./mvnw -fae test` (réacteur entier, après le dernier commit de code) | **BUILD SUCCESS**, 14 modules, 1 901 tests, 0 échec, 0 erreur, 54 ignorés (tous antérieurs au lot : tests POSIX/Linux sous Windows) |
| `check-private-io.py` | SUCCESS : 508 sources, 19 occurrences listées, 6 interdictions, 7 primitives |
| `test_check_private_io.py` | 21 tests OK |
| `test_check_module_boundaries.py`, `check-module-boundaries.py` | OK ; SUCCESS (14 modules, 508 sources) |
| `check-workflow-pins.py` | SUCCESS (70 usages externes) |
| `product-facts.py --check`, `check-current-docs.py`, `check-current-docs-vertical-extension.py` | SUCCESS |
| `check-milestone-artifact-references.py` | SUCCESS (98 scripts) |
| `check-mnd`, `check-mne`, `check-p0-p2`, `check-post228-hardening`, `check-audit-remediation-v2`, `check-minos-01`, `check-vertical-decomposition-consistency` | SUCCESS |
| `check-post-mne.py`, `check-remote-distributed-consistency.py` | **échec hérité du lot 1** (voir ci-dessus), pas introduit ici |

Non vérifié : `clean verify` complet et JaCoCo (rouge m24 sous Windows, antérieur) ; l'exécution Linux (verrous POSIX, droits 0700/0600,
`SecureDirectoryStream`) : les tests de droits POSIX sont ignorés ici et ne tournent qu'en CI ; `minos-intellij` (Gradle) non touché
et non construit ; les sandbox `bubblewrap`/cgroup (Linux seulement) n'ont tourné nulle part.

## À traiter plus tard

- **Clone shallow à profondeur 1 (S9 point 6)** : voir décision 8.
- **S10, S11, S13, S14** : hors du périmètre de ce chantier (S13 est de toute façon déjà fermé, voir section 2).
- **Lot 3, liste blanche « à migrer »** : les deux lectures de sources du projet dans `minos-application`
  (`FingerprintConstrainedJavaProgramGraphProvider`, `JavaSourceWorkspace`) vers `ConfinedFileOpener`, une fois confirmé que la
  découverte exclut les liens ; les `createDirectories` du parent de l'artefact dans les deux backends sandbox (`LinuxBubblewrap…`,
  `WindowsAppContainer…`). Chaque migration retire une entrée de `private-io-allowlist.json` (le cliquet l'exige).
- **Lot 3, ce que le gate ne contrôle pas** : `Files.newOutputStream`, `Files.createTempFile`, `Files.createDirectory`,
  `Files.copy`, `Files.newBufferedWriter` ne sont pas dans la liste demandée. Les copies temporaires des magasins de `MINOS_HOME`
  passent désormais par `createPrivateTempFile`, mais rien n'empêche d'en réécrire une brute. Les ajouter au gate demande de
  migrer d'abord les écritures de flux (`SnapshotBinaryCodecSupport`, `ProviderWorkspaceFiles.copyBounded`, journaux, archives
  téléchargées des gestionnaires d'outils).
- **Lot 3, chemins absolus dans les messages des primitives** : `PrivateLocalStorage` (`harden`, `verifyPrivacy`, `requireType`),
  `BoundedProperties.requireRegularFile` et plusieurs messages de `ProcessIndexerExecutor` / `ManagedScip*` nomment encore un chemin.
  Ce lot n'a assaini que ceux qu'il touche (verrous, secret absolu, refus de la primitive de lecture) ; le reste relève d'un
  passage par `PublicErrorMessages` à la frontière.
- **Lot 3, `MinosRuntimeSettings.load`** : `Files.isRegularFile(configuration)` suit encore un lien avant `BoundedProperties.load`
  (qui le refuse ensuite, avec un chemin dans le message). Même famille que le secret absolu, hors S5 nommé.
- **Hérité du lot 1** : `check-post-mne.py` (donc le gate M25 `check-remote-distributed-consistency.py`) échoue sur
  `RemoteRepositoryRequest.java` : les faits `GITHUB("github.com")` / `GITLAB("gitlab.com")` ont déménagé dans `RemoteHost` avec S9.
