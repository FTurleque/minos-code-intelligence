# Design

## Context

Voir `proposal.md` (Pourquoi, constats). État du code au HEAD, relu pendant la rédaction :

- **A01.** `windows-appcontainer-sandbox-v4.ps1.template` : `Recover-Stale` parcourt tous les `*.json` du répertoire de reprise, appelle `Remove-AppContainerPath` (`icacls /remove:g`) sur chaque chemin listé, puis `DeleteProfile` et supprime le journal ; il est appelé sans condition avant chaque lancement (`Recover-Stale $recoveryDirectory`). `Write-Recovery` n'écrit que `profile`, `sid`, `paths` (écriture `.tmp` puis `Move-Item`). Aucun verrou, PID, mutex ni instant. Le répertoire est `MINOS_HOME/sandbox/appcontainer-recovery`, calculé dans `WindowsAppContainerWorkerSandboxBackend.sandboxPlan` et transmis au script par la ligne `recovery=` du plan. La sonde de capacité (`probeOsIsolation`, mise en cache par JVM dans `discover`) lance ce même lanceur réel.
- **Pas de test existant** sur la reprise : aucune classe de test Java ne mentionne `Recover-Stale` ou `appcontainer-recovery` (seul le golden en ressource contient le texte de la fonction). De plus, `WindowsContainmentScriptTest` compare le script assemblé à un golden *pré-remédiation* (`src/test/resources/com/minos/runtime/local/golden/windows-appcontainer-sandbox-v4.ps1`) plus des dérives approuvées épinglées par SHA-256 (fragments `appcontainer-private-storage*.ps1frag`). Toute modification du gabarit hors dérive approuvée rend ce test rouge sur **tous** les OS (il est indépendant de la plateforme).
- **Mécanisme Linux de référence.** `CgroupJobOwnership` : la marque (`.own-<pid>-t<ticks>-n<ns>-<token>`) est dans le nom du cgroup, la décision « tuer » exige une preuve positive de mort (table des processus fiable, ticks de démarrage comparables), sinon `LEAVE`. Ses défauts passés : R2 (instant dérivé de l'horloge murale, saut d'horloge), R11 (les marques d'un build antérieur ne sont jamais récupérées).
- **A02.** `LocalProviderWorkspace.create` copie le projet (jusqu'à `SourceBudgetPolicy.DEFAULT`, 100 000 fichiers, 2 Gio) sous `MINOS_HOME/local-provider-workspaces/<runId>/<provider>/workspace` ; seuls `close()` et l'échec de `create` suppriment, et `create` ne nettoie que le même `runId`. `JGitRemoteRepositoryMaterializer.materialize` crée `.entry-<uuid>.tmp` (clone), supprimé seulement dans son `finally` ; `evict` ignore les entrées commençant par `.`. `DistributedArtifactBundleStore` (`.accept-*`) et `LocalIsolatedIndexWorker` (`distributed-workers/`, `.bundle-*.zip`) sont dormants (ADR 0041). `RunDirectoryRetention` ne parcourt que `runs/`. La reprise ADR 0039 s'appuie sur `runs/<runId>`, pas sur la copie de travail : une copie orpheline n'a aucun usage de reprise (le `create` d'une reprise la recrée).

## Goals / Non-Goals

**Goals:**

- A01 : un sandbox vivant n'est jamais touché par un autre lanceur ; un sandbox mort est récupéré ; en cas de doute on ne récupère pas (même asymétrie que `CgroupJobOwnership`).
- A02 : une borne de vie unique, partagée, sur les résidus de travail de runs morts, sans échec du chemin appelant.
- Preuves explicites de ce qui n'est établi que sous Windows.

**Non-Goals:**

- Supprimer, scinder ou rendre passive la sonde de qualification (A07, voir Questions ouvertes).
- Rapprocher le mécanisme Windows de celui de Linux au-delà du principe (les deux preuves de mort diffèrent volontairement, voir Décision 1).
- Nettoyer les journaux hérités d'un lanceur antérieur (Questions ouvertes).
- Rétention de `runs/`, `staged-snapshots/` (R8), `fingerprint-snapshots/` et résidus de sonde `sandbox/appcontainer-probe-*` (constaté au passage : `probeOsIsolation` les supprime dans son `finally`, un arrêt brutal les laisse ; non traité ici, hors fiche A02).

## Qualification des capacités (règle capability-honesty)

| Capacité | Qualification | Justification |
|---|---|---|
| Non-interférence entre lanceurs AppContainer concurrents (A01) | **qualifiée sous Windows seulement, après livraison** ; **non supportée** avant | prouvée uniquement par des tests `@EnabledOnOs(OS.WINDOWS)` ; aucune preuve Linux (voir Plateformes) |
| Récupération d'un sandbox AppContainer mort (A01) | **qualifiée sous Windows seulement** | idem ; la mort du propriétaire est prouvée par la libération de la marque par le noyau |
| Récupération des journaux d'un lanceur antérieur (A01) | **non supportée** (choix délibéré, voir Décision 4) | aucune preuve de propriété possible |
| Borne de vie des résidus de run (A02), `local-provider-workspaces/` et `.entry-*.tmp` | **qualifiée** (Windows et Linux) après livraison | test unitaire du balayage + test `LocalProviderWorkspaceTest` ; chemins vivants |
| Borne de vie des résidus `.accept-*` et `distributed-workers/` (A02) | **partielle** | code dormant (ADR 0041) : testé unitairement, jamais exercé en production |
| Disposition `SCRATCH_RECLAMATION` après arrêt brutal (ADR 0038) | **non qualifiée** par ce changement | aucune disposition annoncée n'est modifiée (Question ouverte 4) |

## Décisions

### 1. A01 — la marque de propriété est un verrou exclusif du système d'exploitation, tenu par le lanceur pendant toute la vie du sandbox

Le lanceur crée, **avant** `CreateProfile`, un fichier de verrou `Minos.Worker.<guid>.lock` dans le répertoire de reprise, ouvert en `CreateNew` / `ReadWrite` / `FileShare.None` et conservé ouvert jusqu'à la toute dernière étape de son `finally`. Windows libère ce verrou à la mort du processus, quelle qu'en soit la cause (arrêt forcé, plantage, déconnexion) : la mort du propriétaire est donc établie par « le verrou est acquérable », sans horloge, sans PID, sans instant de démarrage. Conséquences :

- **R2 évité** : aucune horloge n'entre dans la décision (spécification : « ne dépend ni d'une horloge ni d'un identifiant réutilisable »).
- **PID réutilisé évité** : un processus sans rapport qui reprend le PID ne tient pas le verrou.
- **Pas de cache obsolète** : la preuve est lue au moment de la décision, atomiquement, par l'ouverture exclusive elle-même (pas de fenêtre vérifier-puis-agir sur une table de processus).

Séquence du lanceur : (a) nom de profil ; (b) `CreateNew` du verrou, échec ⇒ `throw` avant tout profil (exigence fail-closed) ; (c) `CreateProfile`, journal, octrois ; (d) sandbox ; (e) `finally` : retrait des ACE, `DeleteProfile`, suppression du journal, **puis** fermeture et suppression du verrou. Le journal n'existe donc jamais sans verrou associé.

Séquence du balayage `Recover-Stale` : pour chaque `*.json`, tenter l'ouverture exclusive du `.lock` frère. Verrou introuvable ⇒ journal non prouvable ⇒ laissé, avertissement. Violation de partage ⇒ propriétaire vivant ⇒ ignoré sans bruit. Succès ⇒ propriétaire mort ⇒ **garder le verrou ouvert** pendant la récupération (retrait des ACE, `DeleteProfile`, suppression du journal), puis supprimer le journal et le verrou. Toute autre erreur d'ouverture (accès refusé, E/S) ⇒ non prouvable ⇒ laissé, avertissement. Un `.lock` orphelin (sans journal) est supprimé seulement si son ouverture exclusive réussit.

Le handle du verrou ne doit **pas** être héritable : `CreateProcessW` est appelé avec `bInheritHandles = true` dans le fragment `createprocess-call.ps1frag` ; un `FileStream` ouvert sans `FileShare.Inheritable` n'est pas hérité. Si le provider héritait du verrou, un sandbox mort resterait « vivant » tant que l'enfant survit.

**Alternatives écartées :**

- *PID + instant de démarrage dans le journal (proposition alternative de la fiche)* : reproduit les risques R2 (instants dérivés de l'horloge) et la réutilisation de PID ; demande une table des processus fiable côté PowerShell. Rejeté.
- *Mutex nommé* : n'est pas libéré de façon observable depuis un autre processus après une mort brutale sans `WaitForSingleObject`/`AbandonedMutexException` ; moins direct qu'un verrou de fichier, et ne laisse pas de trace pour le diagnostic. Rejeté.
- *Verrou sur le journal lui-même (variante de la fiche)* : le journal est réécrit à chaque octroi par `.tmp` puis `Move-Item`, ce qui est incompatible avec un fichier ouvert en exclusivité ; d'où le fichier de verrou distinct (la fiche l'anticipait).

### 2. A01 — le code de la reprise vit dans un fragment épinglé, pas dans le corps du gabarit

`Write-Recovery` et `Recover-Stale` (et la prise de verrou) passent dans un fragment `windows-fragments/appcontainer-recovery-ownership.ps1frag`, inclus par `#minos-include:appcontainer-recovery-ownership`, sur le modèle exact de `appcontainer-private-storage`. `WindowsContainmentScriptTest` est étendu **dans la même tâche** : une dérive approuvée supplémentaire, le fragment vérifié par SHA-256, retirée avant comparaison au golden, plus les remplacements `replaceExactlyOnce` des points d'appel. Le golden pré-remédiation n'est **pas** régénéré (il reste la référence qualifiée). Le nom de fonction `Recover-Stale` et `Write-Recovery` est **conservé** : `scripts/remediation/check-mnd.py` exige les littéraux `Recover-Stale`, `Write-Recovery`, `Grant-AppContainerFile`, `readFile` dans le gabarit assemblé et `lines.add("recovery="` dans le backend (les gates lisent le script **assemblé** via `scripts/remediation/windows_launcher.py`, qui résout les inclusions de façon générique : les littéraux placés dans le fragment restent vus ; le nom du fragment doit respecter `[a-z0-9-]+`).

**Pas de renommage du gabarit** (`-v4`). Le script est placé sous `%LOCALAPPDATA%\minos-launchers\<sha256>\` à un nom dérivé de son empreinte : un contenu nouveau est donc un nouveau répertoire sans renommer. Un renommage obligerait à mettre à jour la constante `LAUNCHER_SCRIPT_NAME`, le golden, quatre scripts de gates (`check-minos-01.py`, `check-mnd.py`, `check-post-mne.py`, `check-post228-hardening.py`) et trois tests (`ProviderSandboxSecurityRegressionTest`, `WindowsContainmentScriptTest`, `WindowsLauncherScriptPlacementTest`) pour aucun gain de sûreté ; il accentuerait aussi R13 (les répertoires de lanceurs s'accumulent). La fiche proposait « mettre à jour le nom » : décision contraire documentée ici.

### 3. A01 — un emplacement de reprise distinct pour le nouveau format

Le nouveau lanceur écrit ses journaux et verrous sous `MINOS_HOME/sandbox/appcontainer-recovery-v2` (valeur à confirmer à l'implémentation ; le point est la séparation). Raison : l'ancien lanceur (un MINOS d'une version antérieure encore actif sur le même `MINOS_HOME`) continue de balayer **tout** son répertoire ; sans séparation il détruirait les sandbox du nouveau format. Le changement tient en une ligne côté Java (`sandboxPlan`) ; la ligne `recovery=` du plan est inchangée dans sa forme.

### 4. A01 — un journal sans marque n'est jamais récupéré (R11 assumé)

Même asymétrie que `CgroupJobOwnership` : « en cas de doute, ne pas récupérer ». Un journal de l'ancien emplacement ou un journal du nouvel emplacement dont le verrou a disparu est signalé, pas récupéré. Coût assumé : les résidus (profil AppContainer, ACE) laissés par un ancien build ne sont pas nettoyés automatiquement. Ils sont peu nombreux (l'ancien lanceur nettoyait tout à chaque démarrage) ; la documentation (`docs/user/troubleshooting.md`, section Windows) dit comment les supprimer à la main. Une politique de drainage automatique est la Question ouverte 1.

### 5. A02 — une primitive partagée de balayage dans `minos-engine`, paquet `com.minos.io`

`minos-runtime-local` et `minos-integration-git` ne dépendent que de `minos-engine` (aucune dépendance entre eux) : le point d'ancrage de la primitive est donc `com.minos.io`, à côté de `FileTreeOperations` et `PrivateLocalStorage`, dans le sens `engine → adaptateurs` d'ADR-0022. Aucun cycle, aucun nouveau module. Nom proposé : `StaleScratchReclamation` (à fixer). Contrat :

- entrée : racine, prédicat sur le nom des enfants directs, durée de vie, ensemble de chemins protégés, instant courant injectable (comme `RunDirectoryRetention.prune(…, Instant now)`), bornes (entrées listées, candidats supprimés par appel) ;
- critère : dernière modification de l'enfant lui-même (sans suivre les liens) **strictement antérieure** à `now − lifetime` ; une date dans le futur n'est jamais « ancienne » ; un chemin protégé est ignoré ;
- suppression par `FileTreeOperations.deleteRecursively` (jamais de lien suivi, retrait de l'attribut lecture seule Windows, les jonctions sont supprimées sans être parcourues) ;
- échec d'un candidat : avertissement sans chemin absolu, on continue ; aucune exception ne sort (le balayage est une maintenance, jamais une cause d'échec du run ou du clonage) ;
- durée de vie initiale proposée : **24 h**, plus que le timeout provider par défaut (30 min) et que le plafond de clonage (2 h, `RemoteRepositoryCachePolicy`), égale à `RunDirectoryRetention.DEFAULT_RESUME_TTL` ; elle est portée par une constante de la primitive pour qu'il n'y en ait pas une troisième copie dispersée (il en existe déjà deux, `RunDirectoryRetention` et `IndexingResumePlanner`).

Points de déclenchement : `LocalProviderWorkspace.create` (avant de créer sa racine ; protégé = répertoire du `runId` courant) ; `JGitRemoteRepositoryMaterializer.materialize` (avant `.entry-<uuid>.tmp`, prédicat `.entry-*.tmp`) ; `DistributedArtifactBundleStore` (prédicat `.accept-*`) et `LocalIsolatedIndexWorker` (`distributed-workers/`, enfants et `.bundle-*`), ces deux derniers dormants.

**Alternatives écartées :**

- *Bail OS (verrou de fichier) par répertoire de travail, comme A01* : plus fort que l'âge mais exige que chaque propriétaire tienne un verrou pendant toute sa vie (copie de 2 Gio incluse) ; ajoute une E/S privée sous les gates `check-private-io.py`. L'âge avec une durée de vie très supérieure à la durée maximale d'un run est le choix déjà fait pour `runs/`. Cohérence plutôt que meilleure garantie ; le risque résiduel (saut d'horloge en avant supérieur à 24 h) est la Question ouverte 3.
- *Étendre `RunDirectoryRetention`* : elle est privée au paquet de `minos-runtime-local`, donc inaccessible à `minos-integration-git`, et attachée à `runs/` (quarantaine, marqueurs de reprise).
- *Hook d'arrêt de la JVM* : ne couvre pas SIGKILL, plantage, coupure — précisément le cas du constat.

## Plateformes (Windows et Linux)

| Sujet | Windows | Linux |
|---|---|---|
| A01 | Cœur du changement (script PowerShell, verrou `FileShare.None`). Tests `@EnabledOnOs(OS.WINDOWS)`, jamais exécutés ailleurs. | Aucun code modifié : `CgroupJobOwnership` est déjà le correctif S3. Seuls tournent ici : `WindowsContainmentScriptTest` (assemblage, indépendant de l'OS) et les gates littéraux de scripts. |
| A02 | Suppression des jonctions sans les parcourir, retrait de l'attribut lecture seule, échec toléré si un antivirus tient un handle. Dates NTFS : résolution fine, aucune hypothèse. | Liens symboliques jamais suivis. Même logique. |

**Ce qui n'est prouvé que par le runner Windows (résidu de classe T6, « un correctif vérifié par un seul environnement »)** : l'absence d'interférence entre deux lanceurs vivants, la récupération après arrêt forcé du lanceur, le fait que le verrou n'est pas hérité par le provider, et la non-récupération d'un journal sans verrou. Le test d'assemblage et les gates ne prouvent que la présence des littéraux et la fidélité du script assemblé, jamais son comportement. La tâche de clôture le consigne dans les suivis plutôt que de le laisser implicite.

## Direction des dépendances (ADR-0022)

`minos-runtime-local → minos-engine` et `minos-integration-git → minos-engine` : inchangés. La primitive partagée est ajoutée dans `minos-engine` (couche basse, déjà dépendance des deux adaptateurs). Aucune dépendance ajoutée ni inversée, aucune exception. À rejouer : `scripts/architecture/check-module-boundaries.py`.

## Risques / Trade-offs

- **[Le verrou fuit vers le provider]** → le handle est ouvert sans `FileShare.Inheritable` ; le test « récupération après arrêt forcé » échouerait (sandbox mort jamais récupéré) si le provider héritait du verrou.
- **[Antivirus ou indexeur Windows tient le `.lock` d'un sandbox mort]** → l'ouverture exclusive échoue : le journal reste « vivant » jusqu'au prochain balayage. Tolérable (asymétrie voulue), signalé en DEBUG.
- **[Journal et verrou désynchronisés par une suppression manuelle ou un antivirus]** → traité comme non prouvable : laissé et signalé, jamais récupéré (spécification).
- **[Le test d'assemblage `WindowsContainmentScriptTest` casse]** → dérive approuvée ajoutée dans la même tâche que le fragment ; aucune régénération du golden.
- **[Le balayage A02 supprime un run vivant]** → âge strictement supérieur à la durée maximale d'un run, répertoire courant protégé, dates futures exclues. Résidu : saut d'horloge en avant (Question ouverte 3).
- **[Un résidu de 2 Gio ralentit le prochain run]** → borne de candidats par déclenchement ; le reste aux déclenchements suivants.
- **[`m24-polyglot-provider-platform` rouge sous Windows]** → connu, hors régression : comparer avec `develop` avant d'investiguer.

## Migration Plan

1. Livrer le lot 1 (A01) et le lot 2 (A02) dans des PR distinctes ; chaque PR est revertible seule.
2. Au premier démarrage d'un nouveau lanceur, l'ancien emplacement de reprise n'est plus balayé ; les journaux d'un ancien build y restent (voir Décision 4).
3. Retour arrière du lot 1 : revenir au gabarit précédent et à l'emplacement `appcontainer-recovery`. Les fichiers `.lock` du nouvel emplacement deviennent inertes.
4. Lot 2 : aucune migration ; le premier déclenchement supprime les résidus déjà accumulés (jusqu'à la borne par appel).

## Open Questions

1. **Drainage des journaux hérités (A01).** Faut-il récupérer à terme les journaux de l'ancien emplacement, par exemple au-delà d'un âge fixé (`mtime` supérieure à la durée de vie A02), en connaissance du fait que l'âge n'est pas une preuve de mort ? Par défaut : non (Décision 4). Réponse sans effet sur les exigences ni sur le découpage ; ajouterait une tâche conditionnelle (voir `tasks.md`, 5.1).
2. **A07 — sonde `doctor` / statuts de provider.** Décision de produit non tranchée : documenter que `doctor` et les statuts qualifient le sandbox avec effets de bord (création/suppression de profil AppContainer, ACL, copie du script lanceur ; cgroups sous Linux), ou scinder une sonde passive d'une qualification active exécutée seulement avant exécution. La règle « une lecture ne mute rien » est explicite pour l'état projet, pas pour l'hôte (R10, R12 ouverts). Le lot 1 supprime le danger pour un sandbox vivant mais pas la mutation. **Aucune tâche bloquante** ; tâche conditionnelle 5.2.
3. **Saut d'horloge en avant (A02).** Faut-il un bail OS en plus de l'âge pour les répertoires de travail ? Le risque est un saut de plus de 24 h, hors des cas courants. Défaut : non.
4. **Disposition `SCRATCH_RECLAMATION` (ADR 0038).** Qualifier « récupération après arrêt brutal » comme disposition distincte exigerait d'amender l'ADR 0038 et `WorkerResourceContainment`. Défaut : ne pas toucher aux dispositions annoncées.
5. **Valeur de la durée de vie A02.** 24 h proposées (alignées sur la TTL de reprise). La fiche d'audit teste −48 h ; toute valeur entre 30 min × marge et 48 h convient aux tests.

## Écarts constatés à l'implémentation (2026-10-07)

Aucun ne change les exigences ; ils précisent les décisions 1 à 5. Le détail et les preuves sont dans la section « Évidence d'implémentation » de `tasks.md`.

- **D1, séquence.** Le verrou est pris **avant** le bloc `try` du lanceur, pas dans celui-ci : un échec de création fait échouer le script avant tout profil, sans passer par le `finally` (rien à nettoyer). Il est relâché par `Close-OwnershipLock` à la toute dernière ligne du `finally`, après la suppression du journal. Le balayage **relit le journal après avoir acquis le verrou** : un propriétaire qui termine normalement entre le listage et l'ouverture du verrou a déjà supprimé son journal, et le balayeur ne récupère alors rien. Un verrou orphelin (sans journal) n'est supprimé que si son ouverture exclusive réussit ; un verrou orphelin tenu par un lanceur vivant, juste après sa création, est laissé.
- **D1, trois issues d'ouverture.** `Get-DeadOwnerLock` renvoie le verrou ouvert (propriétaire mort), le texte `ALIVE` (violation de partage, code Win32 32) ou `$null` (verrou absent, accès refusé, toute autre erreur d'entrée-sortie : non prouvable). Seule la première issue autorise une récupération.
- **D1, héritage du verrou.** Le handle est un `FileStream` ouvert sans `FileShare.Inheritable`, donc non héritable. Ce n'est prouvé que **statiquement** (`WindowsContainmentScriptTest` interdit ce mot dans le fragment) et par la sémantique de .NET, pas par un test dynamique : le provider est de toute façon tué avec le lanceur par `KILL_ON_JOB_CLOSE`, ce qui rend l'héritage inobservable depuis la sortie du lanceur.
- **D2, comparaison au golden.** Le test ne supprime pas le fragment de l'assemblé : il le **remplace** par le texte qualifié de `Write-Recovery` et `Recover-Stale` lu dans le golden pré-remédiation (jamais régénéré), plus deux dérives de points d'appel (prise et relâchement du verrou). La directive `#minos-include` est suivie immédiatement de `$values = Read-Plan $Plan`, sans ligne vide, pour que l'assemblage reproduise exactement la forme du golden. `Write-Recovery` est repris à l'identique dans le fragment.
- **D3.** L'emplacement est la constante `RECOVERY_DIRECTORY = "appcontainer-recovery-v2"`, la valeur « à confirmer » du design.
- **D5, primitive.** `com.minos.io.StaleScratchReclamation` : `reclaim(root, childName, protectedChildren[, now])` et une forme complète à bornes et avertissement injectables, qui renvoie un `Outcome` (examinés, supprimés, échecs, budget épuisé) et ne lève jamais d'exception. Durée de vie `DEFAULT_LIFETIME` = 24 h, au plus 1 024 entrées examinées et 16 candidats traités par appel. Un enfant est ancien si sa date est **strictement** antérieure à `now − durée de vie` ; le critère lit l'entrée elle-même (sans suivre les liens), donc la date du répertoire de run et non celle de son contenu.
- **D5, candidats.** Dans `local-provider-workspaces/` et dans la racine du worker distribué, seuls les enfants nommés par un identifiant de run (UUID) sont candidats, ainsi que `.bundle-*.zip` pour le worker ; un autre nom est laissé. `.entry-*.tmp` pour le cache de dépôts, `.accept-*` pour le cache d'artefacts. L'horloge injectée est utilisée où elle existe (matérialiseur, worker) ; `LocalProviderWorkspace` et `DistributedArtifactBundleStore` utilisent l'horloge système.
- **D5, limite de preuve.** Les deux cas de la primitive propres à Linux (lien symbolique ancien, répertoire en lecture seule) sont écrits mais **n'ont pas pu être exécutés ici** (hôte Windows) : la CI Ubuntu les prouvera. Le cas de la jonction, lui, a tourné sous Windows.
