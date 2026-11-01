# Design

## Context

Le backend MCP Docker monte une racine des projets entière dans deux conteneurs, dont l'un a une sortie réseau. L'utilisateur veut que seuls les dépôts qu'il a explicitement inscrits soient visibles du conteneur, puis indexés. Abréviations : `PROD` = `docker/compose-mcp.prod.yaml`, `CONN` = `docker/compose-mcp.connected.yaml`, `LC` = `docker/scripts/mcp-lifecycle.ps1`, `SW` = `scripts/install/switch-mcp-backend.ps1`, `ZIP` = `scripts/install/install-windows.ps1`, `TPL` = `packaging/windows/minos-installer.iss.template`, `MAP` = `minos-domain/src/main/java/com/minos/domain/registry/ProjectPathMapping.java`, `STORE` = `minos-storage/src/main/java/com/minos/storage/home/ProjectPathMappingStore.java`, `REG` = `minos-storage/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java`, `PG` = `minos-storage/src/main/java/com/minos/storage/postgresql/project/PostgresProjectRegistry.java`, `BOOT` = `minos-cli/src/main/java/com/minos/cli/DockerRuntimeBootstrap.java`, `SVC` = `minos-application/src/main/java/com/minos/application/ProjectPathMappingService.java`. HEAD analysé : `98dfbcca` (`develop`).

## État vérifié au HEAD

- **Montage de la racine** : `PROD:54-57` (`minos-mcp`) et `PROD:100-103` (`minos-admin`) montent `${MINOS_PROJECTS_DIR}` sur `/workspace/projects`, `read_only: true` ; même chose `CONN:70-73` et `CONN:134-137`. `minos-admin` est sur `minos-admin-egress` (`CONN:138`). `working_dir: /workspace/projects` dans les deux services (`PROD:44`, `PROD:91`).
- **Bootstrap** : `PROD:122` et `CONN:157` lancent `configure-project-paths ${MINOS_HOST_PROJECTS_ROOT} /workspace/projects`, sans réseau (`network_mode: none`). `BOOT:43-46` n'accepte que cette forme ; `SVC:41-49` crée la correspondance une seule fois et rend `CONFLICT` pour une autre.
- **Écriture du `.env`** : `LC:560-561` écrit `MINOS_PROJECTS_DIR` et `MINOS_HOST_PROJECTS_ROOT` depuis `-ProjectsRoot`. `LC:581` lance `minos-bootstrap`, `LC:624` recrée `minos-mcp` (`up -d --force-recreate`). Tous les appels Compose passent par la fonction `Compose` (`LC:134`, un seul `-f $ComposeFile`) sauf la lecture des plafonds (`LC:168`).
- **Correspondance** : `MAP` est un couple `(hostRoot, containerRoot)` ; `STORE` lit et écrit le format 1 (`formatVersion`, `hostRoot`, `containerRoot`, clés fermées `STORE:28`, bornes de `BoundedProperties` calées sur ce nombre de clés `STORE:53`). Aucun code de production n'appelle `hostToContainer` ni `containerToHost`.
- **Registre** : `REG:307-313` écrit `rootRelativePath`, relatif à la racine de l'emplacement courant ; dans le conteneur (`MINOS_RUNTIME_LOCATION=docker`), c'est la racine conteneur. `REG:346-352` résout ce chemin sous la racine conteneur. `PG:339-348` et `PG:355-360` font de même pour PostgreSQL (`portable`). `rootAvailable` = `Files.isDirectory(rootPath)` (`minos-application/src/main/java/com/minos/application/ProjectInspectionService.java:174`).
- **Inscription en Docker** : `minos-admin project add /workspace/projects/<chemin>` (`docs/user/docker-runtime.md:285`) ; le plan d'administration ne lance aucun provider et refuse `index` (`docs/user/docker-runtime.md:286`, SEC-0074) — l'emplacement du refus dans le code n'a pas été relu pour cette spécification.
- **Désinscription** : aucune commande `project remove` ; `ProjectRegistry.deleteProject` (`minos-engine/src/main/java/com/minos/registry/ProjectRegistry.java:102`) n'est appelé que par un retour arrière (`minos-application/src/main/java/com/minos/application/remote/RemoteIndexingUseCase.java:231`).
- **Installeurs** : page Docker `TPL:1343-1347` (champ « Racine des projets », `/DOCKERPROJECTSROOT`), contrôle `TPL:1509-1515` (`UnsafeDockerProjectsRootReason`), valeur enregistrée `TPL:840`, transmission `-ProjectsRoot` `TPL:1026`. `SW:51` définit `Get-UnsafeDockerProjectsRootReason` (jumelle de `ZIP`, `check-twin-script-functions.py`), `SW:249` réutilise un runtime seulement si la racine du marqueur est identique.
- **Montage par projet déjà pratiqué** : `scripts/ci/qualify-docker-release.sh:319` et `:335` montent un seul projet sur `/workspace/projects/typescript-modules` (lecture seule) et l'inscrivent par ce chemin : la forme cible fonctionne sous Linux avec les images actuelles.
- **Linux** : `LC` est écrit pour PowerShell 5.1 et 7 (`LC:40`) et exécuté sous Linux par `scripts/ci/qualify-docker-upgrade.ps1`, qui inscrit `/workspace/projects/upgrade-fixture` (`:350`).
- **Contrats littéraux** : `minos-app/src/test/java/com/minos/packaging/M29DockerAdministrationContractTest.java:31` et `:44` affirment `target: /workspace/projects\n        read_only: true` ; `scripts/install/test_docker_script_contexts.py:100` affirme `MINOS_HOST_PROJECTS_ROOT` ; `docker/scripts/verify-run-configurations.ps1:149-150` écrit les deux variables.

Non vérifié : création par Docker Desktop (Windows, WSL2) d'un point de montage imbriqué `/workspace/projects/a/b` sur un système de fichiers racine en lecture seule (D3 en dépend ; preuve en tâche 3.3).

## Écarts avec la demande

- « Initialiser puis indexer » : l'indexation n'a pas lieu dans le conteneur (SEC-0074). Ce changement rend le projet visible et inscrit ; l'indexation reste la voie existante.
- « Retrait d'un projet » : sans commande de désinscription au HEAD, le retrait retire l'exposition, pas l'inscription (D6).

## Décisions

### D1 — Ce qui est monté : un montage par projet, aucune racine

`PROD` et `CONN` ne montent plus aucun dossier de projets. `/workspace/projects` reste la racine conteneur (c'est elle que le registre connaît) ; elle ne contient que des montages en lecture seule, un par projet exposé, identiques dans `minos-mcp` et `minos-admin`. Une table vide est valide : le serveur MCP démarre, sans projet.

Options pesées :

- **A. Montage par projet (retenu).** Exposition minimale ; seule voie compatible avec Docker Desktop, qui ne suit ni lien ni jonction.
- **B. Racine conservée, filtrage dans MINOS.** Le conteneur voit toujours tout ; un défaut du filtre suffit à lire un autre dépôt. Rejeté : la frontière doit être le montage.
- **C. Copie des projets dans un volume.** Index et sources divergent, coût disque, synchronisation à inventer. Rejeté.

### D2 — Génération des montages : override Compose généré depuis une table tenue en Java

- **Source de vérité** : la table des montages, dans `<données Docker>/runtime/project-paths.properties`, **format 2** : `formatVersion=2`, `containerRoot=/workspace/projects`, puis pour chaque montage `mount.<i>.path` (chemin relatif sous la racine conteneur) et `mount.<i>.hostRoot` (dossier hôte absolu). Écrite seulement par `BOOT` (primitives `PrivateLocalStorage` et `DurableAtomicFile`, comme le format 1), qui valide la table entière avant d'écrire (D4). Le format 1 reste lu (migration, D5).
- **Fichier généré** : `LC` obtient la table par `BOOT list-project-mounts --format json` (conteneur `minos-bootstrap`, sans réseau) et écrit `compose-mcp.projects.yaml` dans le runtime : un bloc `volumes` par service (`minos-mcp`, `minos-admin`), chaque montage en syntaxe longue, `read_only: true`, chaque scalaire écrit comme une chaîne JSON (YAML en est un sur-ensemble : aucune injection par un chemin). `Compose` (`LC:134`) et la lecture des plafonds (`LC:168`) ajoutent `-f compose-mcp.projects.yaml`. L'override est régénéré avant chaque `up`, `run minos-admin` et `Validate` : une édition à la main est écrasée.
- **Source disparue** : un `hostRoot` qui n'est plus un dossier est omis de l'override, avec un avertissement qui nomme le montage (pas le chemin) ; le projet répond `rootAvailable: false`. Sinon Compose refuserait de démarrer `minos-mcp` pour un seul dossier supprimé.

Options écartées :

- **Variables numérotées dans le `.env`** (`MINOS_PROJECT_01_DIR`…) : Compose ne sait pas omettre un montage dont la variable est vide ; il faudrait monter un dossier factice par emplacement libre. Rejeté.
- **Table tenue par PowerShell** (`projects.json` dans le runtime) : deux sources (table PowerShell, registre Java) qui peuvent diverger, et validation dupliquée en Pascal, PowerShell et Java. Rejeté.

### D3 — Correspondance hôte↔conteneur : chemins conteneur stables, registre inchangé

Le registre, local comme PostgreSQL, continue de stocker un chemin relatif à `/workspace/projects`. Un projet inscrit par cette version est monté sur `/workspace/projects/<nom>` et inscrit avec `rootRelativePath=<nom>`. La résolution côté conteneur (`REG:346-352`, `PG:355-360`) ne change pas.

Côté hôte, `MAP` gagne la résolution d'un chemin relatif par la table : le montage dont le chemin préfixe le chemin relatif donne le dossier hôte, le reste est joint. Hors de tout montage : pas de dossier hôte (projet non exposé). Cette résolution sert au diagnostic (D8) et au code de remplacement de #632.

### D4 — Noms, plafond et refus

- **Nom d'un nouveau montage** : `[a-z0-9][a-z0-9._-]{0,62}`, ni `.` ni `..`, unique sans tenir compte de la casse. Par défaut, dérivé du nom du dossier (minuscules, caractères hors jeu remplacés par `-`) ; si le résultat est invalide ou déjà pris, refus qui demande `-Name`.
- **Chemins de montage migrés** (D5) : segments sûrs (règle `normalizeRelative` de `MAP`), plusieurs segments admis.
- **Chevauchements refusés** : aucun chemin de montage ne préfixe un autre ; aucun dossier hôte n'est égal à un autre, ni ne le contient (un même dossier sous deux noms, ou un projet exposé deux fois).
- **Plafond** : 64 montages (D9). Au-delà, refus sans écriture.
- **Dossier hôte** (contrôle côté hôte, dans `LC`, avant d'appeler `BOOT`) : absolu, existant, pas un point de reparse (jonction, lien symbolique) ni sous un parent qui en est un, et accepté par la règle de #627 appliquée au projet (`Get-UnsafeDockerProjectsRootReason` : racine de volume, profil et ses parents, `%LOCALAPPDATA%`, `%APPDATA%`, dossiers de MINOS). Sous Linux, la même fonction refuse `/`, le dossier personnel et ses parents, et les dossiers de MINOS. Le message ne contient aucun chemin (`.claude/rules/securite.md`, point 7).
- `BOOT` revalide ce qu'il peut sans l'hôte (chemin absolu, noms, chevauchements, plafond) : un appel direct du bootstrap ne contourne que le contrôle propre à l'hôte, et `BOOT` n'est appelé que par `LC`.

### D5 — Migration d'une installation à racine unique

Au premier `Install` de cette version sur des données Docker en format 1 (racine hôte `R`) :

1. **Inventaire** : `BOOT migrate-project-mounts --dry-run` liste les projets du registre par leur chemin relatif. Le registre local est lu par `minos-bootstrap` ; PostgreSQL n'étant joignable que par le réseau du runtime, l'inventaire est fait par le même point d'entrée lancé dans un conteneur `minos-admin` (`--entrypoint`) quand le stockage est PostgreSQL. Une inscription illisible arrête la migration avant toute écriture, l'ancien runtime est conservé et le message compte les inscriptions concernées.
2. **Calcul** : chaque chemin relatif `p` donne le montage `p ↔ R/p` ; des chemins imbriqués (un monorepo `a` et son sous-projet `a/b`) donnent un seul montage, le plus court. Ne sont **pas** montés, avec un avertissement par cas : un projet inscrit à `.` (la racine elle-même — le monter reproduirait l'exposition supprimée), une inscription isolée par #632 (`detachedHostRoot`), un dossier refusé par D4.
3. **Écriture** : la table format 2 remplace le format 1 (publication atomique, sauvegarde `project-paths.properties.<horodatage>.format1.bak` à côté). Le registre n'est pas réécrit : chaque projet monté garde le même chemin conteneur qu'avant.
4. **Retour arrière** : si l'`Install` échoue ensuite, `SW` restaure la sauvegarde (instantané de `runtime\` des données Docker, comme #632) avant de redémarrer l'ancien runtime.

Option écartée : renommer chaque projet en `/workspace/projects/<nom>` à la migration. Cela réécrit chaque inscription (et la base PostgreSQL, que le bootstrap n'atteint pas) pour un gain cosmétique.

Lien avec #632 : son option `--replace` porte sur une racine unique ; avec une table format 2, `BOOT` la refuse (aucune racine à remplacer) et nomme `AddProject`. Les inscriptions qu'elle a isolées restent isolées.

### D6 — Retrait d'un projet

`-Action RemoveProject -Name <nom>` retire le montage de la table (BOOT), régénère l'override et recrée `minos-mcp`. Les projets inscrits sous ce montage restent inscrits avec leur index (`rootAvailable: false`) ; la sortie les nomme. Un nouvel `AddProject` du même dossier sous le même nom les rend de nouveau disponibles. Une commande CLI `project remove` (retrait de l'inscription et sort de l'index) est un changement séparé, qui touche la surface CLI publique et la rétention des index.

### D7 — Installeur Windows et paramètres hérités

La page Docker ne contient pas de champ « Racine des projets » ; elle indique la commande d'inscription d'un projet. `SW` et `ZIP` n'exigent pas `-ProjectsRoot` ; `SW:249` ne compare pas de racine. `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` du registre Windows sont **ignorés avec un avertissement** dans le journal d'installation : aucun dossier n'est monté du seul fait de ce paramètre. Option écartée : les refuser, ce qui casse les installations scriptées existantes sans gain de sécurité. La valeur `ProjectsRoot` du registre Windows est supprimée par l'installation.

### D8 — Diagnostic honnête

`-Action Status` liste les montages (nom, disponible ou omis) par leur nom, sans chemin hôte absolu (`.claude/rules/securite.md`, point 7) ; `-ShowHostPaths` ajoute les chemins hôte pour l'utilisateur de son propre poste (D10). `installation.json` remplace `projectsRoot` par `projectMounts` (compte et noms). `docs/user/docker-runtime.md` décrit le modèle, la migration et ses avertissements.

## Direction des dépendances

`domain` (table des montages, résolution hôte) ← `storage` (`ProjectPathMappingStore`, formats 1 et 2) ; `engine` (port `ProjectPathMappings`) si le port expose la table ; `application` (`ProjectPathMappingService` : ajouter, retirer, migrer) ne nomme que le port, fourni par `minos-bootstrap` (ARCH-0042) ; `cli` (`DockerRuntimeBootstrap`) ne nomme que le service. Aucun module, aucun package nouveau, aucun cycle (A8).

## Qualification

| Capacité | Statut visé | Preuve |
|---|---|---|
| Table format 2, validation, refus | qualifiée | tests unitaires `minos-domain`, `minos-storage`, `minos-application`, `minos-cli`, Windows et Linux (CI) |
| Override généré, quoting, source omise | qualifiée | test Python/PowerShell de `LC` sur arbre temporaire ; `qualify-docker-upgrade.ps1` sous Linux |
| Montage par projet dans `minos-mcp` et `minos-admin` | qualifiée sous Linux (CI), **partielle** sous Windows | `qualify-docker-release.sh` et `qualify-docker-upgrade.ps1` (Linux) ; Docker Desktop : tâche manuelle |
| Migration format 1 → 2, stockage local | qualifiée | `qualify-docker-upgrade.ps1` (installation de la version précédente, projet inscrit, mise à jour) |
| Migration avec PostgreSQL géré | **partielle** | tests Java ; parcours réel manuel (aucune CI ne lance le profil connecté) |
| Point de montage imbriqué (`a/b`) sous Docker Desktop | **non vérifiée** | tâche manuelle 3.3 ; si elle échoue, D5 monte le premier segment et le signale |
| Page Docker de l'installeur | **partielle** | contrat textuel, compilation ISCC ; parcours manuel |

## Risques

- Recréer `minos-mcp` interrompt les sessions MCP attachées ; les clients relancent la commande `docker exec`. L'action le dit avant de recréer.
- Un projet que l'utilisateur croyait exposé (dossier sous l'ancienne racine, jamais inscrit) ne l'est plus après la migration : rupture voulue, signalée par la documentation et la sortie de la migration (compte des montages créés).
- Un dossier hôte supprimé puis recréé par un autre outil au même chemin est remonté : même comportement que la racine actuelle, limité désormais à ce dossier.
- Docker Desktop partage chaque dossier monté avec la VM : 64 montages restent sous les limites connues, non mesurées pour cette spécification.

## Décisions du propriétaire (validées le 2026-11-01)

- **D6** : le retrait retire l'exposition seulement ; une commande `project remove` est un changement séparé.
- **D7** : `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` enregistrée sont ignorés avec un avertissement.
- **D9** : plafond de 64 montages.
- **D10** : diagnostic par nom de montage ; chemins hôte seulement avec `-ShowHostPaths`.
- **D11** : #632 est fusionnée avant l'implémentation ; la branche `fix/proposer-de-creer-la-racine-des-projets` ne donne pas de PR ; ce changement est implémenté ensuite.
- **D12** : ADR `SEC-0075` « Le runtime Docker n'expose que les projets inscrits », rédigé en tâche 1.2 au statut Proposed, accepté par le propriétaire avant la section 6.
