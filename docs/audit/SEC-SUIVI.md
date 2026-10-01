# Suivi du chantier « Sécurité, sévérité moyenne » (S5, S6, S7, S8, S9, S12, S15)

Chantier ouvert le 2026-10-01 sur `docs/audit/AUDIT-2026-09.md`. Sept constats. L'ordre des lots est celui du risque :
S9 et S12 sont les deux seuls qui donnent quelque chose à un attaquant aujourd'hui (exfiltration d'un secret, détection
éteinte), S5/S6 sont la fondation, S8 et S7/S15 viennent ensuite.

> Les constats S12 et S15 ne figurent que dans la version **locale non commitée** de `AUDIT-2026-09.md` (l'audit
> commité sur `develop` s'arrête avant). Ce chantier ne modifie pas ce fichier.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s9-git` | S9 | #317 | brouillon |
| 2 | `sec/s12-audit` | S12 | #318 | **déjà corrigé** (`c380baa3`), preuve par mutation, aucun code |
| 3 | `sec/s5-s6-primitives` | S5, S6 (ferme aussi R9) | #319 | brouillon |
| 4 | `sec/s8-gitignore` | S8 | à ouvrir | code terminé |
| 5 | `sec/s7-s15-windows` | S7, S15 | à ouvrir | **code terminé** (S15 et S7 a, b, c corrigés ; S7 d documenté), non poussé |

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

### Gates qui exigent du texte littéral (CI, run 36917741879)

`check-post-mne.py` exigeait `Files.walkFileTree` dans `JGitRemoteRepositoryMaterializer` ; l'effaceur local ayant disparu au profit de la primitive, l'exigence devient `FileTreeOperations.deleteRecursively` (la primitive reste gardée par la ligne `FileTreeOperations.java` du même script : `Files.walkFileTree`, `NOFOLLOW_LINKS`, `postVisitDirectory`). `check-remote-distributed-consistency.py` exigeait le nom de test `MISSING_REMOTE_TOKEN`, renommé `MINOS_REMOTE_TOKEN_MISSING` (liste blanche) ; l'énumération `GITHUB("github.com")` / `GITLAB("gitlab.com")` est conservée à l'identique. Aucun contrôle n'a été retiré. Tous les `check-*.py` de `remediation/` et `quality/` rejoués : verts (hors `check-jacoco.py`, qui a besoin des rapports JaCoCo).

### Résultats de fin de lot (2026-10-01, Windows 10, JDK 24)

- `./mvnw -B clean verify` : **BUILD SUCCESS**, 15 modules, 1 876 tests, 0 échec, 54 ignorés (déjà ignorés avant ce lot ; aucun ajouté par le lot).
- Gates : `check-module-boundaries.py` SUCCESS (modules=14, sources=508) ; `check-milestone-artifact-references.py` SUCCESS (95 scripts) ; `check-workflow-pins.py` SUCCESS (70 `uses`).
- Windows : tout a été exécuté sous Windows. Linux : non exécuté localement (la CI le fait).

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

`scripts/architecture/check-private-io.py` interdit, dans `src/main/java` de tous les modules sauf `minos-intellij`, hors des **quatre**
primitives qui doivent toucher l'API brute (`ConfinedFileOpener`, `DurableAtomicFile`, `BoundedFileLease`, `BoundedProperties`) :
`Files.createDirectories`, `Files.write`, `Files.writeString`, `Files.newInputStream` (appel **ou référence de méthode**
`Files::write`), `import static java.nio.file.Files.*`, `FileChannel.open`, `AsynchronousFileChannel.open`, et tout usage d'un
canal (`FileChannel`, `AsynchronousFileChannel`, `FileLock`, `RandomAccessFile`, `getChannel()`, `lock`/`tryLock` sur un récepteur
`*channel*`) : un canal ne vient que des primitives, le détenir ailleurs est le constat, quel que soit le nom de la variable.
`PrivateLocalStorage`, `FileTreeOperations` (0 occurrence) et `SharedCacheLeaseRegistry` (plafonné par une entrée) n'ont plus
d'exemption. Revue verif-sec G1/G2 : l'import joker, les références de méthode, `raf.getChannel().lock()`, un récepteur nommé
autrement, `AsynchronousFileChannel.open` et l'échappement unicode (décodé comme le fait le compilateur) contournaient la
première version.
**Les tests (`src/test`) et les ressources (`src/main/resources`, scripts embarqués) sont exclus par construction** : la règle
gouverne le code que MINOS livre, pas ses fixtures. Commentaires et littéraux ignorés ; import statique, nom qualifié, appel coupé sur plusieurs lignes et échappement unicode détectés.

La liste blanche `scripts/architecture/private-io-allowlist.json` est nominative (fichier + méthode + maximum + justification
écrite) ; jamais un répertoire, jamais un joker (refusés à la lecture) ; **cliquet** : une occurrence hors liste, ou une de plus
dans un fichier listé, échoue ; une entrée dont le maximum dépasse le compte réel échoue aussi, la liste ne peut que rétrécir.
Branché dans `.github/workflows/pr-ci.yml` (gate + auto-test, juste après `check-module-boundaries`) et dans `run-final.sh` /
`run-final.ps1`. Auto-test `test_check_private_io.py` : 30 cas, dont **une mutation témoin par interdiction** (8) et une par contournement connu
(import joker, référence de méthode, ligne coupée, unicode, `getChannel().lock()`, canal issu d'un flux) et par échappatoire
(répertoire, joker, justification vide, maximum périmé, occurrence en trop, doublon, fichier absent, primitive, ancienne primitive).

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
| `SharedCacheLeaseRegistry` (engine) | `FileChannel.lock` | 18 | registre à compte de références : un canal et un verrou par clé (types `FileChannel`/`FileLock`) ; ouverture par `BoundedFileLease.openPrivateLockChannel` |

12 entrées, 37 occurrences, sur 508 sources. Limite du gate, dite : il lit le texte, pas les types. Un verrou de fichier se
reconnaît à la présence d'un type de canal ou de verrou, de `getChannel()` ou d'un récepteur `*channel*` ; ce qui y échappe serait un
`Channel` générique (`Files.newByteChannel`) dont on ne ferait que lire et écrire, ce qui n'est pas un verrou. `Files.newOutputStream`, `Files.createTempFile`, `Files.createDirectory` et `Files.copy`
ne sont pas dans la liste demandée et ne sont pas contrôlés (voir « À traiter plus tard »).

### Journal

| Commit | Contenu |
|---|---|
| 1 | primitives (`openRegularFileNoFollow`, `writePrivateFile`, `createPrivateTempDirectory`, `openPrivateLockChannel`) ; `SharedCacheLeaseRegistry` privé |
| 2 | S6 : verrous du magasin hébergé et de la compaction |
| 3 | S5/S6 : verrou de matérialisation Git, `pin`, `writeProperties` |
| 4 | S5 : TOCTOU du codec d'observations |
| 5 | S5 : entrée propriétaire héritable, refus d'un fichier occupant un répertoire |
| 6 | S5 : secret absolu (puis M1 : lien feuille toléré pour ce seul secret) |
| 7-9 | S5 : `minos-runtime-local` ; `minos-storage-local` + application + PostgreSQL ; `minos-provider-scip` + `minos-cli` |
| 10 | gates de remédiation alignés sur les primitives |
| 11 | le gate `check-private-io` (puis G1/G2 : contournements fermés, quatre primitives) |
| 12 | S6a : `compactAfterSuccess` dans `LocalAutonomousIndexOperations` |

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
| secret absolu | `AbsoluteSecretFileTest` (6) | **4 échecs sur 5** (chemin absolu dans le message : fichier absent, vide, lien, répertoire) ; après M1 : le test de lien feuille vers fichier régulier rouge avant, vert après | vert |
| baux d'indexation distante | `RemoteIndexLeaseTest` (+1) | **pas rouge** : `BoundedFileLease` durcissait déjà le répertoire en créant le fichier de verrou ; le changement est un nettoyage (un `createDirectories` en moins), la garde est une non-régression | vert |

Tests ignorés par ce lot : **0** de mes tests ne sont ignorés sur cette machine (les liens symboliques se créent ; mes tests de
lien s'appuient sur `assumeTrue` seulement quand la création échoue, ce qui n'est pas le cas ici). `PrivateLocalStorageTest` a 5
tests POSIX ignorés sous Windows, **antérieurs au lot**. Un test de plus est propre à Windows (`@EnabledOnOs`) : ignoré sous Linux.

### Constats de la vérification

- Les gates de remédiation `check-mnd`, `check-audit-remediation-v2` et `check-post-mne` exigeaient le texte brut que les
  primitives remplacent (`jvmLock.lock()`, `Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)`, `Files.walkFileTree` dans JGit).
  Chaque littéral est remplacé par celui de la primitive plus stricte, aucune exigence n'est supprimée.
- `check-post-mne` exigeait aussi `Files.walkFileTree` dans JGit (effaceur local disparu avec S9) : le littéral est celui de
  `FileTreeOperations.deleteRecursively`. Après la fusion de `sec/s12-audit` et des gates `quality` de l'orchestrateur, `check-post-mne`
  et `check-remote-distributed-consistency` sont verts.
- **Revue verif-sec, S6a** : la compaction de rétention est bornée à 10 s, donc elle peut expirer ; deux appels de
  `LocalAutonomousIndexOperations` (`NO_CHANGES` et succès) la laissaient remonter après un index réussi, alors que les deux autres sont
  protégés (chemins d'échec). Ils passent par `compactAfterSuccess` : `IOException` capturée, diagnostic « storage retention did not run »
  sans cause ni chemin. Preuve : `RetentionFailureAfterIndexTest` rouge (l'`IOException` remontait) puis vert ; le chemin succès
  d'une exécution réelle partage la même méthode et n'a pas de test propre. `check-post-mne` compte désormais les appels directs et
  `compactAfterSuccess` pour son invariant « rétention sur chaque chemin terminal ».
- **Revue verif-sec, M1, décision** : `readAbsoluteSecret` refusait un lien feuille, ce qui casse les volumes de secrets Kubernetes
  (`key` → `..data/key`) alors que la Javadoc de classe parle de « mounted secret stores ». **Exception limitée au seul secret ABSOLU**,
  que l'opérateur désigne lui-même : le chemin est résolu une fois (`toRealPath`), doit aboutir à un **fichier régulier**, est ouvert
  `NOFOLLOW` et lu sur ce seul flux ; répertoire, lien pendant ou autre objet : refus, sans chemin. Tout ce qui est sous MINOS_HOME, secret
  relatif compris, reste strictement `NOFOLLOW`. Preuve : `AbsoluteSecretFileTest` (6) : lien feuille via `..data` lu ; lien vers répertoire
  et lien pendant refusés ; rouge avant (« must be a regular non-symlink file »), vert après. Le test de refus d'un lien feuille du
  lot 3 d'origine est remplacé par ces deux-là : c'est une décision écrite, pas un affaiblissement silencieux.
- Compromis de S6, dit : une compaction qui en attend une autre sur le même projet échoue au bout de 10 s au lieu de lever tout
  de suite (même JVM) ou d'attendre sans fin (autre processus). Une compaction plus longue que 10 s sur un gros projet, relancée
  en parallèle, échouerait donc ; ce n'est pas un chemin chaud (la rétention suit la fin d'une indexation).

### Commandes lancées et résultats

Machine : Windows 10, JDK 24 (liens symboliques disponibles). Aucun `clean`, aucun golden régénéré : les 12 golden de
`characterization/` sont inchangés (`git diff 8c8deca4 HEAD` vide sur ce répertoire).

| Commande | Résultat |
|---|---|
| `./mvnw -fae test` (réacteur entier, après le dernier commit de code) | **BUILD SUCCESS**, 14 modules, 1 901 tests, 0 échec, 0 erreur, 54 ignorés (tous antérieurs au lot : tests POSIX/Linux sous Windows) |
| `check-private-io.py` | SUCCESS : 508 sources, 37 occurrences listées (12 entrées), 8 interdictions, 4 primitives |
| `test_check_private_io.py` | 30 tests OK |
| `test_check_module_boundaries.py`, `check-module-boundaries.py` | OK ; SUCCESS (14 modules, 508 sources) |
| `check-workflow-pins.py` | SUCCESS (70 usages externes) |
| `product-facts.py --check`, `check-current-docs.py`, `check-current-docs-vertical-extension.py` | SUCCESS |
| `check-milestone-artifact-references.py` | SUCCESS (98 scripts) |
| `check-mnd`, `check-mne`, `check-p0-p2`, `check-post228-hardening`, `check-audit-remediation-v2`, `check-minos-01`, `check-vertical-decomposition-consistency` | SUCCESS |
| `check-post-mne`, `check-remote-distributed-consistency` et tous les gates `quality/` | SUCCESS (après fusion) |

Non vérifié : `clean verify` complet et JaCoCo (rouge m24 sous Windows, antérieur) ; l'exécution Linux (verrous POSIX, droits 0700/0600,
`SecureDirectoryStream`) : les tests de droits POSIX sont ignorés ici et ne tournent qu'en CI ; `minos-intellij` (Gradle) non touché
et non construit ; les sandbox `bubblewrap`/cgroup (Linux seulement) n'ont tourné nulle part.

### Gates à texte littéral du dossier `quality/` (orchestrateur, après fusion des lots 1-2)

Trois scripts de `scripts/quality/` que `impl-sec` n'avait pas rejoués exigeaient du texte que les primitives remplacent : `FileLock` dans `FileHostedControlPlaneStore` et `JGitRemoteRepositoryMaterializer` devient `BoundedFileLease` ; `Files.isSymbolicLink` dans `RuntimeObservationEnvelopeCodec` devient `ConfinedFileOpener.openRegularFileNoFollow`. L'exigence reste du même ordre (le verrou fichier et le non-suivi de lien sont toujours gardés, par la primitive nommée). Tous les `check-*.py` de `remediation/`, `quality/` et `architecture/` sont verts après ce changement, hors `check-jacoco.py` (rapports JaCoCo requis).

### Résultat de fin de lot 3 (orchestrateur, 2026-10-01, Windows 10, JDK 24)

- `./mvnw -B clean verify` après les corrections de `verif-sec` : **BUILD SUCCESS**, 15 modules, 1 910 tests, 0 échec, 54 ignorés (tous antérieurs ; ceux ajoutés par le lot : 0 hors `@EnabledOnOs(WINDOWS)`).
- Revue `verif-sec` : aucun bloquant ; G1 (contournements du gate), G2 (exemptions trop larges), S6a (`compact` non protégé après un run réussi) et M1 (secret absolu par lien feuille) corrigés, voir ci-dessus ; G3/G4/W1/R1/R2/S6b/A1/E1/T1 consignés comme remarques ou en « à traiter plus tard ».
- Tous les `check-*.py` rejoués verts (hors `check-jacoco.py`) ; golden `characterization/` inchangés.

## 4. Lot 4 : S8, un `.gitignore` hostile ne casse pas le chargement

### Relocalisation

`ProjectIgnoreRules` : `minos-engine`, `com.minos.source` (utilisé par la découverte, `ProjectIgnorePolicy`, et trois appelants provider/runtime). Un seul compilateur de motifs d'exclusion, avant comme après.

### Décisions (écrites avant le code)

1. **Un motif qui ne compile pas est écarté et compté, jamais fatal.** `parseRuleOrDiscard` attrape `IllegalArgumentException` (dont `PatternSyntaxException`) : la règle est ignorée, `discardedRuleCount()` l'indique, un seul WARNING par chargement donne le nombre (jamais le texte du motif).
2. **Les classes de caractères sont une entrée, pas de la syntaxe regex.** Chaque membre est échappé ; un intervalle inversé (`[z-a]`) ou une classe vide (`[!]`) rend la règle inutilisable ; `[[]` et `[a&&b]` (intersection regex Java) deviennent des littéraux, comme dans Git.
3. **Le coût est borné par un mécanisme, pas par une horloge** :
   - à la compilation : `MAX_RULE_CHARS` = 1 024 caractères, `MAX_WILDCARDS_PER_RULE` = 8 groupes d'étoiles, les `**/` consécutifs sont repliés en un seul (sémantiquement identique, et c'est le vecteur de répétition `(?:.*/)?(?:.*/)?…`) ;
   - à l'évaluation : le chemin est lu par un `CharSequence` à budget (`MATCH_STEP_BUDGET` = 100 000 lectures par règle et par chemin). Au-delà, la règle est **désactivée** (échec fermé sur la règle, pas sur le chargement), comptée par `exhaustedRuleCount()`, et plus jamais évaluée.
4. **`.gitignore` imbriqués et `.git/info/exclude` : écart assumé et documenté** (Javadoc de `load`). Les lire est un élargissement fonctionnel qui exige la sémantique complète de Git (précédence entre fichiers, négation, portée du répertoire) ; une demi-sémantique serait pire que l'écart. À traiter plus tard si demandé.
5. Les règles `**` non suivies de `/` gardent leur sémantique historique (`.*`, franchit les `/`) : la changer modifierait le périmètre d'indexation, hors périmètre.

### Preuve rouge (2026-10-01, `src/main` d'avant)

Test jetable hors dépôt rejoué : `[z-a]x` et `[[]open` font échouer `ProjectIgnoreRules.load` (`PatternSyntaxException`, CONFIRMÉ) ; `*a*a*a*a*a*a*a*a*a*b` contre 200 `a` **ne termine pas en 20 s** (`assertTimeoutPreemptively` dans ce seul essai de preuve ; le test commité n'a aucune assertion de durée) : **le ReDoS est reproduit**, la part PLAUSIBLE de S8 est donc CONFIRMÉE. Tests commités (`ProjectIgnoreRulesHostileInputTest`, 5 tests) : vérifient les compteurs et les résultats, pas la durée.

### Effet sur le périmètre d'indexation (mesuré)

Ancienne et nouvelle implémentation comparées, mêmes entrées :

| Jeu | Évaluations | Ignorées avant | Gagnées | Perdues | Écartées |
|---|---|---|---|---|---|
| `.gitignore` de ce dépôt × 1 657 fichiers suivis | 1 657 | – | 0 | 0 | 0 |
| corpus de 26 motifs courants (classes `[Bb]in/`, `*.py[cod]`, `[^a-m]z.txt`, `[!0-9]data`, `**/target/`, négations…) × 1 682 chemins (fichier et répertoire) | 3 364 | 524 | **0** | **0** | 0 |

Aucun fichier gagné ni perdu. Les `.gitignore` de deux dépôts publics n'ont **pas** été comparés (pas de téléchargement sans demande explicite) : à faire par `verif-sec` sur les dépôts qu'il a déjà en local, ou à la demande.

### Windows / Linux

Tout exécuté sous Windows (logique pure, sans dépendance de plateforme). Linux : par la CI. Tests ignorés ajoutés : 0.

## 5. Lot 5 : S15 et S7, Windows (ACL, script du bac à sable, environnement des lanceurs)

### Relocalisation

| Cité par l'audit | Réalité |
|---|---|
| `PrivateLocalStorage.harden` : `acl.setAcl(List.of(ownerEntry))` | `minos-engine`, `com.minos.io.PrivateLocalStorage.harden` (le seul endroit qui écrit une ACL sous `MINOS_HOME`) |
| script `sandbox/windows-appcontainer-sandbox-v4.ps1` matérialisé par `doctor` | `WindowsAppContainerWorkerSandboxBackend.installLauncher` (constructeur, donc à chaque `discover`) ; même défaut pour `WindowsJobObjectProcessOwnership.installLauncher` (`windows-job-object-owner-v1.ps1`). Le contenu est assemblé depuis les gabarits du jar par `WindowsContainmentScript.assemble` |
| héritage de l'environnement complet | `ProcessIndexerExecutor.startProvider` (branche `trustedLauncherRequiresParentEnvironment()`), activée par les deux lanceurs |
| `requireInheritableOwnerAccess` / `user.name` | `WindowsAppContainerWorkerSandboxBackend` |
| `forceDirectory` inerte | `DurableAtomicFile.forceDirectory` (`minos-engine`) |

### Décisions (écrites avant le code)

1. **MINOS ne retire jamais une restriction posée par un administrateur.** Les ACE de REFUS (DENY) d'un chemin que MINOS
   durcit sont conservées, placées avant l'ACE ALLOW du propriétaire. Cela vaut aussi pour un refus *hérité* (il devient
   explicite dans la DACL protégée de la décision 3 : le retirer serait le retirer). Si MINOS ne peut pas écrire là où il
   doit écrire à cause d'un refus, il **échoue en le disant** (`private storage is write-protected by an explicit deny entry;
   MINOS does not remove it`, sans chemin), jamais d'élévation silencieuse.
   - **Les ALLOW d'un autre principal restent retirés** (invariant « propriétaire seul » de `verifyPrivacy`/`foreignAclEntry`,
     inchangé). Un grant est un droit accordé, pas une restriction posée : c'est la nature du stockage privé de ne le
     réserver qu'au propriétaire. **Je n'exempte pas SYSTEM et Administrateurs** (position de l'orchestrateur laissée
     ouverte) : (a) ils gardent `SeBackupPrivilege`, `SeRestorePrivilege` et la prise de possession, donc le retrait de
     l'ACE ne leur ôte aucun pouvoir, il ôte seulement l'accès ambiant ; (b) les exempter obligerait `verifyPrivacy` à
     reconnaître deux SID bien connus, soit un affaiblissement de l'invariant vérifié et une surface de plus à garder ;
     (c) c'est le comportement de tous les lots précédents, sur lequel les 3 tests `AppContainer` réels reposent.
   - **Le durcissement n'écrit plus quand l'état est déjà le bon.** Si la DACL est déjà celle attendue, aucun `setAcl`
     n'est émis : un répertoire en lecture seule n'est pas modifié par une simple vérification. Conséquence pour **R12**
     (une commande de lecture ne devrait pas avoir besoin d'écrire dans un `MINOS_HOME` en lecture seule) : sous Windows,
     R12 devient **observable** pour la première fois (avant, la DACL de refus disparaissait au premier lancement, donc il
     n'y avait rien à observer). Je **ne cherche pas à le fermer** ici.
2. **Un script exécutable n'a pas sa place dans un répertoire de données.** Le script du bac à sable est un artefact du
   produit (le jar : il est assemblé depuis ses ressources, ADR 0040, zip auto-portant). `-EncodedCommand` est exclu : le
   script assemblé dépasse la limite de 32 767 caractères de ligne de commande de `CreateProcess`. Il est donc matérialisé
   **hors de `MINOS_HOME`**, dans un répertoire privé propre à l'utilisateur sous le répertoire temporaire de la JVM, à un
   **nom dérivé de son empreinte SHA-256** (jamais remplacé, jamais réécrit sur place), en **lecture seule**, et son
   empreinte est **revérifiée avant chaque lancement** (`sandboxPlan` et plan du Job Object) : un écart échoue fermé, sans
   repli. Le contenu des `.ps1` n'est pas modifié. Limite dite : entre la vérification et la lecture par PowerShell,
   un processus du même utilisateur peut encore substituer le fichier (c'est le périmètre de confiance « même compte »,
   hors de ce que `-File` permet de fermer sans natif).
3. **DACL protégée : seulement si l'expérience la justifie.** Voir « Preuves ». Le moyen retenu est le plus petit :
   `icacls <chemin> /inheritance:d` après `setAcl`, sans shell, sur un chemin déjà validé. **`:d`, pas `:r`** (écart avec la
   formulation de départ, mesuré) : sur un objet qui n'a que des ACE héritées, `/inheritance:r` laisse une DACL **vide** (un fichier
   tout juste créé dans un répertoire privé n'a qu'une ACE héritée, que Java lit comme égale à l'attendue) ; `:d` la rend explicite.
4. **Environnement des lanceurs : liste blanche.** Le lanceur PowerShell (de confiance, mais qui n'a aucune raison de voir un
   secret de l'environnement MINOS) ne reçoit plus l'environnement parent complet. Il reçoit la liste blanche déjà
   commune aux fournisseurs (`ProviderProcessEnvironment`), réduite empiriquement au minimum dont PowerShell et le bac à
   sable ont besoin.
5. **Identité de l'ACE héritable : le jeton du processus, pas `user.name`.** `user.name` est une propriété JVM que la ligne de
   commande (`-Duser.name=…`, `JAVA_TOOL_OPTIONS`) modifie : elle est passée telle quelle à `icacls`, qui accepte aussi les
   SID (`*S-1-1-0`). L'identité vient de `whoami /user` (SID du jeton, y compris élevé), lue une fois par JVM.
6. **`forceDirectory` : limite documentée, pas simulée.** Voir « Preuves ».

### Preuves

**S15 : confirmé.** Un répertoire avec un refus d'écriture posé par un administrateur (`icacls <dir> /deny <utilisateur>:(WD,AD)`)
puis `PrivateLocalStorage.ensurePrivateDirectory` : avant, `DESKTOP-…\fturl:(DENY)(S,WD,AD)` et six entrées héritées ; après, une
seule ligne, `fturl:(OI)(CI)(F)`. Le refus a disparu sans un mot, au premier lancement.

**S7 (b) : reproduit, avec une nuance dite.** `icacls <MINOS_HOME> /grant *S-1-1-0:(OI)(CI)R` (une ACE héritable d'un administrateur)
après durcissement : **tous** les enfants durcis par MINOS passent `EXPOSED` (sous-répertoire, fichier créé par
`createPrivateFile`, fichier de `writePrivateFile`, fichier de `hardenExistingFile`). `Get-Acl` donne `AreAccessRulesProtected = False`
pour chacun : Java écrit une DACL (`SetFileSecurity`, `DACL_SECURITY_INFORMATION` seul, vérifié dans les sources du JDK 24) mais ne
sait pas la marquer protégée. **Nuance** : un grant ajouté au *parent* ou au *grand-parent* d'un répertoire durci n'a, dans trois
montages, pas atteint ce répertoire ; il a atteint son sous-répertoire et tout enfant d'un répertoire dont la DACL a changé. Je n'ai
pas pu établir la règle du moteur d'héritage de Windows ; le défaut est réel mais sa portée dépend de l'histoire de l'objet, ce que la
DACL protégée supprime.

**S7 (a) : reproduit.** Une variable sentinelle posée dans l'environnement de la JVM de test (configuration surefire du module) est
lue par un vrai PowerShell lancé comme lanceur de confiance.

**S7 (c) : reproduit, et plus grave que décrit.** `-Duser.name=*S-1-1-0` : `icacls` accepte la forme SID, la racine d'écriture
reçoit un contrôle total héritable pour **Tout le monde** (mesuré). Un `user.name` divergent d'un vrai nom (`minos-no-such-user`) fait
échouer la préparation du bac à sable, avec le chemin dans le message.

**S7 (d) : non corrigé, documenté.** `FileChannel.open` d'un répertoire échoue en `AccessDeniedException` sous Windows, en lecture
comme en écriture (mesuré, JDK 24). Rien de propre sans natif. La Javadoc de `DurableAtomicFile.forceDirectory` dit ce que l'inertie
signifie : la donnée est forcée avant le renommage (`forceFile`) ; NTFS journalise ses métadonnées, donc après un crash le volume est
cohérent et le renommage est appliqué ou non, jamais déchiré ; ce qui n'est pas garanti est qu'un renommage tout juste terminé survive
à une coupure de courant qui le suit de près (dernière écriture perdue, pas corruption). Le `MoveFileEx` du JDK ne demande pas non plus
le write-through.

#### Rouge → vert (2026-10-01, machine Windows 10, JDK 24)

Les tests ont été commités **avant** les correctifs (`git log` : `bb899906`, `abe2751b`, puis les correctifs) et rejoués contre le
`src/main` d'avant (seule une couture de test sans effet, `forgetProtectedLocationsForTesting`, existait).

| Cible | Test | Sans correctif | Avec |
|---|---|---|---|
| refus conservé (explicite) | `anExplicitDenyEntryPlacedByAnAdministratorSurvivesHardening` | rouge | vert |
| refus hérité conservé | `aDenyEntryInheritedFromTheParentSurvivesHardeningAsAnExplicitEntry` | rouge | vert |
| échec dit, sans chemin | `aWriteProtectedLocationFailsClosedAndSaysSoWithoutNamingThePath` | rouge (aucune exception : l'écriture réussissait) | vert |
| grant ultérieur du parent | `anInheritableGrantAddedLaterToTheParentDoesNotReachHardenedChildren` | rouge (`EXPOSED`) | vert |
| DACL protégée | `aHardenedDaclIsProtectedAgainstInheritanceForDirectoriesAndFiles` | rouge (bit absent, lu par `icacls /save`) | vert |
| pas d'écriture si déjà bon | `hardeningAnAlreadyRightLocationDoesNotRewriteItsDacl` | rouge (2 `setAcl`) | vert (0) |
| lecture d'un emplacement protégé | `aWriteProtectedLocationStaysReadableAndVerifiableWithoutAnyWrite` | vert (non-régression, voulu) | vert |
| sentinelle | `aSecretOfTheParentEnvironmentNeverReachesATrustedLauncher` | rouge (la valeur factice est lue) | vert |
| minimum du lanceur | `theLauncherKeepsTheNonSecretMetadataPowerShellNeedsToStart` | vert (non-régression) | vert |
| script hors `MINOS_HOME` (2 lanceurs), lecture seule, empreinte, falsification refusée | `WindowsLauncherScriptPlacementTest` (4) | **4 échecs sur 4** | vert |
| copie héritée supprimée | `aCopyLeftInMinosHomeByAnEarlierVersionIsRemoved` | non rejoué en rouge (ajouté après le correctif) | vert |
| identité de l'ACE héritable | `WindowsSandboxWriteRootIdentityTest` (2) | **2 échecs sur 2** (Tout le monde reçoit le contrôle total ; `IllegalStateException`) | vert |

Total des tests de ce lot : **16 propres à Windows** (`@EnabledOnOs(WINDOWS)`, **ignorés sous Linux : 16**, comptés ici et à ne pas
confondre avec les 54 déjà ignorés) et 1 test multiplateforme (`ProviderProcessEnvironmentTest.aTrustedLauncherKeeps…`). Aucun
`@Disabled`, aucun `assumeTrue` ajouté.

**Deux scénarios que j'ai écartés après essai** : un refus `WRITE_DAC` posé au propriétaire (`/deny <moi>:(WDAC)`) n'empêche pas
le propriétaire de réécrire la DACL (droit implicite du propriétaire, mesuré) : il ne modélise donc pas un verrou de DACL, et le test
« pas d'écriture si déjà bon » passe par un compteur d'appels `setAcl` sur la couture `CapabilityProbe` à la place. Un verrou réel
demande une ACE `OWNER RIGHTS` ; non traité (voir « À traiter plus tard »).

#### Environnement du lanceur : mesure du minimum

Par essai direct des deux vrais lanceurs (AppContainer et Job Object, `exit 0` dans le bac à sable) avec l'environnement vidé puis
rempli : seuls `PATH` et `PATHEXT` sont indispensables individuellement ; ensemble, `PATH`, `PATHEXT`, `SystemRoot`, `TEMP`, `TMP`
suffisent aux deux lanceurs (sans `TEMP`, `Add-Type` tente d'écrire dans `C:\Windows`). La liste retenue a quinze noms (ces cinq plus
`windir`, `SystemDrive`, `ComSpec`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `ProgramData`, `USERNAME`, `USERDOMAIN`,
`COMPUTERNAME`) : une marge pour une configuration de profil différente de celle mesurée, sans variable qui ressemble à un secret.
**Ce n'est pas le minimum strict**, c'est le minimum mesuré plus une marge nommée. Le fournisseur, lui, reçoit toujours l'environnement
du plan (liste blanche commune `ProviderProcessEnvironment`, inchangée).

### Changements observables

- Le premier lancement ne supprime plus un refus posé par un administrateur. Si ce refus empêche MINOS d'écrire, l'opération échoue
  avec `private storage is write-protected by an explicit deny entry; MINOS does not remove it`.
- **R12 devient observable sous Windows** : un emplacement protégé en écriture n'est plus silencieusement rouvert ; une commande qui
  l'ouvre en écriture échoue maintenant. Une commande de lecture qui n'a pas besoin d'écrire n'écrit plus quand la DACL est déjà la
  bonne. R12 n'est pas fermé (une commande de lecture qui crée encore un répertoire échouera), c'est volontaire.
- Les scripts des lanceurs ne sont plus dans `MINOS_HOME\sandbox`, mais dans `%TEMP%\minos-launchers\<sha256>\` ; les copies d'un
  MINOS précédent sont supprimées au démarrage du bac à sable.
- Chaque objet que `PrivateLocalStorage` durcit pour la première fois dans un processus coûte un `icacls` (environ 13 ms mesurés).
  Sur les 207 tests de `minos-storage-local`, 43 s deviennent 74 s (environ 14 s pour les répertoires, 17 s pour les fichiers) : un test
  crée des milliers de petits objets, une commande réelle en crée quelques dizaines.
- La DACL de chaque objet durci est protégée (bit « P » du SDDL) ; `verifyPrivacy` ne change pas (un DENY n'est pas un grant à un autre
  principal).

### Résultats de fin de lot (Windows 10, JDK 24)

- `./mvnw -fae test -Djacoco.skip=true` (réacteur entier, **sans `clean`**, après le dernier commit de code) : 14 modules sur 15 **SUCCESS**,
  0 échec dans chacun ; 522 tests dans `minos-engine`, 207 dans `minos-storage-local`, 270 dans `minos-cli`, etc. Le 15e (`MINOS
  Code Intelligence`, module `minos-app`) a **1 échec, hérité du lot 4, non introduit ici** :
  `JsonEscapeGuardTest.everyListedExceptionStillExistsAndStillContainsTheSignature` (« exception inutile, plus aucune signature dans
  `minos-engine/.../source/ProjectIgnoreRules.java` ») : la liste d'exceptions de ce test de caractérisation nomme encore une signature
  que la réécriture de `ProjectIgnoreRules` (S8) a retirée. `git diff 25920ed4 HEAD -- minos-engine/src/main/java/com/minos/source`
  est vide : ce lot n'y touche pas. À corriger dans le lot 4 (retirer l'entrée, c'est le test qui l'exige).
- **Tests réels du bac à sable, rejoués sur cette machine après chaque changement du lot** (surefire, 71 tests, 0 échec, 2 ignorés
  antérieurs au lot, POSIX) : `WindowsAppContainerWorkerSandboxBackendTest` (11, dont `realWindowsSandboxUsesAppContainerJobLimits…`,
  `realWindowsSandboxRunsAManagedBatchFileProvider…`, `allowPolicyKeepsAppContainer…`, la qualification
  `qualificationOnlyPermitsSandboxClaimOnWindows` qui exécute la vraie sonde AppContainer/Job Object), `WindowsJobObjectContainmentTest`
  (3), `WindowsNonElevatedIndexingTest` (2), `WindowsStrongProcessOwnershipContainmentTest` (2), `WindowsExecutionPathIdentityProviderTest`
  (2), `WindowsContainmentScriptTest` (6), `WorkerSandboxBackendsTest` (9), `WorkerSandboxQualificationTest` (4),
  `ProviderSandboxSecurityRegressionTest` (2), `StrongProcessOwnership*`/`StrongOwnershipRemoteSandboxCompositionTest` (5),
  `ProcessIndexerExecutor*Test` (15), plus les 8 tests neufs du lot. Aucun repli silencieux : un lanceur dont l'empreinte ne correspond
  plus lève `IOException`, le backend ne se rabat sur rien.
- **`minos doctor` réel** (jar ombré construit par `package`, `MINOS_HOME` neuf) : le backend `windows-appcontainer-job-v3` est
  découvert et qualifié (la ligne d'avertissement sur le code non fiable est l'ADR 0041, antérieure : le backend est rejeté pour du code
  distant non fiable, pas pour un échec de démarrage) ; **aucun `.ps1` sous `MINOS_HOME`** ; les lanceurs sont sous
  `%TEMP%\minos-launchers\<sha256>\`.
- **R12, observé de bout en bout** : `MINOS_HOME` avec `icacls /deny <moi>:(WD,AD)`, puis `minos project list` (une commande de
  lecture) : `error: MINOS bootstrap failed: private storage is write-protected by an explicit deny entry; MINOS does not remove it`,
  code de sortie 1, et la DACL montre le refus toujours là (avant le lot, la commande réussissait en effaçant le refus).
- Gates : tous les `check-*.py` de `scripts/remediation/`, `scripts/quality/` (hors `check-jacoco.py`) et `scripts/architecture/` verts
  (20 scripts, dont `check-private-io.py` et `check-module-boundaries.py`) ; `test_check_private_io.py` (21) et
  `test_check_module_boundaries.py` (13) OK. Aucun gate n'a eu à être aligné. Les 12 golden de `characterization/` sont inchangés
  (`git diff 25920ed4 HEAD` ne les touche pas).
- Non vérifié : l'exécution sous Linux (les 16 tests Windows s'y ignorent ; la CI le dit) ; `clean verify` et JaCoCo (orchestrateur) ;
  un poste avec profil itinérant ou de domaine (la marge de l'environnement du lanceur est là pour lui, non mesurée) ; une session
  élevée (la lecture du SID par `whoami` est censée suivre le jeton, non essayé).


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
- **Lot 5, FFM** : un appel natif (`SetNamedSecurityInfo` avec `PROTECTED_DACL_SECURITY_INFORMATION`, `GetSecurityDescriptorControl`
  pour lire le bit) supprimerait le processus `icacls` par objet et permettrait de vérifier la protection au lieu de la supposer ; le
  même mécanisme (`CreateFile` avec `FILE_FLAG_BACKUP_SEMANTICS` puis `FlushFileBuffers`) rendrait `forceDirectory` réel. Coût : le
  drapeau `--enable-native-access` (ou l'attribut `Enable-Native-Access` du manifeste) dans la distribution.
- **Lot 5, `OWNER RIGHTS`** : une ACE `ALLOW` pour `S-1-3-4` limite les droits implicites du propriétaire (il ne peut plus écrire la
  DACL). `PrivateLocalStorage` la verrait comme un grant étranger à retirer, ne le pourrait pas, et échouerait avec le message brut du
  JDK (qui nomme le chemin). Non traité.
- **Lot 5, répertoires de lanceurs** : `%TEMP%\minos-launchers\<sha256>` accumule un répertoire par version du script ; aucun ménage.
- **Lot 5, autres écritures d'ACL** : `requireAclGrantable` / `isAclGrantable` / `runIcacls` de `WindowsAppContainerWorkerSandboxBackend`
  écrivent encore des ACE (grants de lecture AppContainer sur les racines d'outils) hors de `PrivateLocalStorage` ; antérieur au lot,
  non touché, et le message de `requireAclGrantable` nomme encore un chemin.
