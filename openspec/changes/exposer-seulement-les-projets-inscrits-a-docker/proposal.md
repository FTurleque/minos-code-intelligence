# Proposal

## Why

En backend MCP Docker, l'utilisateur choisit une « racine des projets » (par exemple `N:\workspace-dev`) que Compose monte **entière**, en lecture seule, sur `/workspace/projects` dans `minos-mcp` et dans `minos-admin` (`docker/compose-mcp.prod.yaml:54-57` et `:100-103`, `docker/compose-mcp.connected.yaml:70-73` et `:134-137`, variable `MINOS_PROJECTS_DIR`). Le conteneur lit alors tout ce qui se trouve sous cette racine : autres dépôts, copies de travail d'agents, fichiers divers, y compris ceux qui ne sont inscrits dans aucun registre MINOS. `minos-admin` a en plus une sortie réseau (dépendances). Les PR #627 (refus des racines dangereuses) et #632 (remplacement explicite de la racine) réduisent les pires cas sans changer ce modèle : tout dossier placé sous la racine reste exposé.

Un lien symbolique ou une jonction ne permet pas de construire une racine qui ne contiendrait que les dépôts voulus : Docker Desktop ne les suit pas. La seule exposition minimale est **un montage par projet**, ajouté quand l'utilisateur inscrit ce projet et retiré quand il le retire.

Le backend natif n'est pas concerné : il lit les projets là où ils sont et n'a pas de racine.

## What Changes

- **Plus de racine montée** : les fichiers Compose livrés ne montent plus `MINOS_PROJECTS_DIR`. `/workspace/projects` ne contient que les montages en lecture seule des projets inscrits, un par projet, sur `/workspace/projects/<nom>`.
- **Table des montages** : la correspondance hôte↔conteneur du runtime Docker (`<données Docker>/runtime/project-paths.properties`) passe au format 2 : une racine conteneur (`/workspace/projects`) et une liste bornée de montages `<chemin de montage> ↔ <dossier hôte>`. Elle est écrite par le bootstrap Java (`DockerRuntimeBootstrap`, primitives d'E/S privées), qui refuse noms dangereux, doublons, chevauchements et dépassement du plafond.
- **Fichier d'override généré** : `docker/scripts/mcp-lifecycle.ps1` génère, à partir de la table, un fichier Compose `compose-mcp.projects.yaml` dans le runtime, passé à **chaque** appel Compose ; il est régénéré avant chaque démarrage, jamais édité à la main. Un dossier hôte disparu est omis (avertissement), sans bloquer le serveur MCP.
- **Inscription d'un projet Docker** (côté hôte) : nouvelle action du cycle de vie (`-Action AddProject -ProjectPath <dossier> [-Name <nom>]`, relayée par `minos-docker.ps1`) qui valide le dossier (règle de #627 appliquée **au projet**, point de reparse refusé), ajoute le montage, recrée `minos-mcp`, puis inscrit le projet par `minos-admin project add /workspace/projects/<nom>`. L'indexation suit ensuite la voie existante du runtime Docker, inchangée.
- **Retrait d'un projet Docker** : `-Action RemoveProject -Name <nom>` retire le montage et recrée `minos-mcp` ; l'inscription et l'index restent, le projet passe à `rootAvailable: false`.
- **Migration** d'une installation à racine unique (format 1), au premier `Install` de cette version : un montage par projet inscrit, sur **le même chemin conteneur qu'avant** (`/workspace/projects/<chemin relatif>`), donc sans réécrire le registre, local ou PostgreSQL. Un projet inscrit à la racine elle-même, isolé par #632 ou dont le dossier est dangereux n'est pas monté (avertissement). Une inscription illisible fait échouer la migration avant toute écriture et l'ancien runtime est conservé.
- **Installeur Windows** : la page Docker ne demande plus de racine. `/DOCKERPROJECTSROOT`, `-ProjectsRoot` et la valeur `ProjectsRoot` enregistrée ne montent plus rien ; ils sont ignorés avec un avertissement (D7 du design).
- **ADR** : nouvel ADR de sécurité proposé (`SEC-0075`, statut Proposed) : « le runtime Docker n'expose que les projets inscrits ».
- Documentation : `docs/user/docker-runtime.md`, `docs/user/mcp.md`, `docs/user/troubleshooting.md`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `distribution-docker` : exigences ajoutées (aucune racine montée ; montage par projet inscrit ; table bornée et noms sûrs ; override régénéré ; retrait ; migration sans réécriture du registre ; installeur sans racine globale ; diagnostic honnête). Les exigences portant sur la racine unique, ajoutées par les changements `refuser-le-profil-comme-racine-des-projets` et `remplacer-la-racine-des-projets-docker`, sont retirées ou modifiées par ce changement une fois ceux-ci archivés (tâche 1.1).

## Hors périmètre

- **Désinscription d'un projet** (`project remove`) : aucune commande CLI ne retire une inscription au HEAD (`ProjectRegistry.deleteProject` n'est appelé que par un retour arrière, `RemoteIndexingUseCase.java:231`). Le retrait d'un projet Docker retire son exposition, pas son inscription ni son index. Une commande `project remove` est un changement séparé (D6).
- **Indexation dans le conteneur** : le plan d'administration ne lance aucun provider (SEC-0070, SEC-0074, `docs/user/docker-runtime.md:286`) ; ce changement ne modifie pas la voie d'indexation, il exige seulement que le projet soit monté.
- **Ajout à chaud d'un montage** : Docker ne sait pas ajouter un montage à un conteneur en cours d'exécution ; `minos-mcp` est recréé et les sessions MCP attachées sont interrompues.
- Backend natif, plugin IntelliJ, NEXUS : inchangés.
- Liens symboliques à l'intérieur d'un projet monté : non suivis vers l'hôte (Docker Desktop ne les suit pas ; sous Linux, une cible absolue est résolue dans l'espace de noms du conteneur).
- Interface graphique de gestion des projets exposés : hors périmètre ; l'inscription se fait par script.

## Impact

- **Modules du reactor** : `minos-domain` (table des montages et résolution hôte d'un chemin relatif), `minos-storage` (`ProjectPathMappingStore` format 2, lecture du format 1), `minos-application` (`ProjectPathMappingService` : ajout, retrait, migration), `minos-cli` (`DockerRuntimeBootstrap` : sous-commandes de montage et de migration). `minos-engine` (port `ProjectPathMappings`) si le port doit porter la table. Direction des dépendances inchangée, aucun module ni package nouveau.
- **Scripts et fichiers livrés** : `docker/compose-mcp.prod.yaml`, `docker/compose-mcp.connected.yaml`, `docker/scripts/mcp-lifecycle.ps1`, `docker/scripts/prod-mcp-release.ps1`, `docker/scripts/minos-docker.ps1`, `docker/scripts/configure-docker-mcp.ps1`, `scripts/install/switch-mcp-backend.ps1`, `scripts/install/install-windows.ps1`, `packaging/windows/minos-installer.iss.template`, `docker/scripts/prod-mcp.ps1` (lanceur de développement). Aucun nouveau fichier livré : l'override est généré dans le runtime.
- **Surfaces publiques** : CLI, API Java et MCP inchangés. Additif : actions `AddProject` / `RemoveProject` du cycle de vie, sous-commandes du bootstrap Docker. Changement de comportement voulu (fail-closed sur l'exposition) : `/DOCKERPROJECTSROOT` et `-ProjectsRoot` ne montent plus rien. Format 2 du fichier de correspondance : une version antérieure le refuse (`unsupported project path mapping format`), sans le perdre.
- **ADR** : nouveau `SEC-0075` (Proposed, à accepter par le propriétaire) ; amende la description du montage dans `ARCH-0069` (« projets montés en lecture seule ») sans changer sa décision.
- **Gates et tests à rejouer** : `M29DockerAdministrationContractTest` (affirme `target: /workspace/projects`), `scripts/install/test_docker_script_contexts.py` (affirme `MINOS_HOST_PROJECTS_ROOT`), `test_mcp_lifecycle_query_image.py`, `verify-run-configurations.ps1`, `verify-installer-template.ps1`, `verify-mcp-backend-lifecycle.ps1`, `check-twin-script-functions.py`, `check-private-io.py`, `check-module-boundaries.py`, `check-comment-language.py`, `scripts/ci/qualify-docker-upgrade.ps1` et `scripts/ci/qualify-docker-release.sh` (Linux), scripts `scripts/m29/run-s3.ps1`, `run-s4.ps1`, `run-s5.ps1`, `run-s7.ps1`.
- **Plateformes** : code Java et cycle de vie (`mcp-lifecycle.ps1` sous PowerShell 7) sur Windows et Linux ; installeur Inno Setup sur Windows seulement.
