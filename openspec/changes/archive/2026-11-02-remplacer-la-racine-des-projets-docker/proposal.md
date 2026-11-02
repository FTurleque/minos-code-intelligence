# Proposal

## Why

Constat du 1er novembre 2026, sur une installation 1.3.0 réelle, avec un installeur construit depuis `develop` (`98dfbcca`, qui contient la PR #627 « refuser le profil comme racine des projets »). L'installation précédente montait le profil (`MINOS_PROJECTS_DIR=C:/Users/<compte>`) ; `%LOCALAPPDATA%\MINOS\docker-data\runtime\project-paths.properties` contient `hostRoot=C:/Users/<compte>` et `containerRoot=/workspace/projects`.

Le nouvel installeur refuse le profil. L'utilisateur choisit un dossier dédié, et l'étape « Image Docker MINOS » échoue : le conteneur `minos-bootstrap` répond « error: refusing to replace an existing project path mapping implicitly » (`minos-cli/src/main/java/com/minos/cli/DockerRuntimeBootstrap.java:60`), parce que `ProjectPathMappingService.configure` (`minos-application`) rend `CONFLICT` dès qu'une autre correspondance est enregistrée et qu'aucune voie explicite de remplacement n'existe. Le basculement est annulé proprement, mais l'utilisateur ne peut plus réinstaller le backend Docker, ni avec l'ancienne racine (refusée) ni avec une nouvelle (conflit), et aucun remède n'est documenté hormis une désinstallation avec purge qui perd l'index.

## What Changes

- **Voie explicite de remplacement** : `configure-project-paths --replace <racine-hôte> <racine-conteneur>` dans le bootstrap Docker. Sans `--replace`, le comportement est inchangé (`CONFLICT`, code 1).
- **Projets inscrits sous l'ancienne racine** (registre local) : rebasés s'ils se trouvent sous la nouvelle racine ; sinon **isolés** : ils restent inscrits avec leur index, leur racine n'est plus résolue sous la nouvelle racine et `project list` les signale `rootAvailable: false`. Chaque inscription modifiée est sauvegardée à côté d'elle avant d'être réécrite. Aucun projet n'est désinscrit ni ne pointe vers un autre dossier ; une inscription isolée est rattachée de nouveau si une racine ultérieure la contient.
- **Refus** (fail-closed) : remplacement refusé si une inscription du registre est illisible ou si le stockage configuré est PostgreSQL (le bootstrap n'a pas de réseau et ne peut pas rebaser le registre PostgreSQL).
- **Installeurs** : l'installeur Windows, l'installation ZIP et `switch-mcp-backend.ps1` comparent la racine choisie à la racine enregistrée avant toute écriture. Différente et non confirmée : refus avec un message actionnable. Confirmée (question de l'assistant, `/REPLACEDOCKERPROJECTSROOT=1` en mode silencieux, `-ReplaceDockerProjectsRoot` des scripts) : `--replace` est transmis jusqu'au bootstrap, et le registre et la correspondance sont restaurés si le basculement échoue ensuite.
- Documentation : `docs/user/docker-runtime.md`, `docs/user/troubleshooting.md`.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `distribution-docker` : trois exigences ajoutées (remplacement explicite seulement ; projets rebasés ou isolés sans perte ; confirmation et restauration côté installeurs).

## Hors périmètre

- **Registre PostgreSQL** : le remplacement y est refusé avec un remède (désinstaller avec purge, ou revenir à l'ancienne racine). Rebaser les racines PostgreSQL exige un plan qui atteint la base : changement séparé.
- **Réinscription automatique** d'un projet isolé dont le dépôt a été déplacé sous la nouvelle racine : l'utilisateur le réinscrit (`project add`) puis retire l'ancienne inscription (`project remove`).
- Points de reparse et jonctions sous la nouvelle racine : non résolus, comme pour #627.
- Installation Linux : aucun installeur ne change la racine ; `--replace` y est disponible pour un opérateur qui appelle le bootstrap.

## Impact

- **Modules du reactor** : `minos-domain` (`ProjectPathMapping` : calcul d'une racine hôte et rebasage), `minos-engine` (port `ProjectPathMappings` : méthode `replace` et son rapport), `minos-storage` (`ProjectRootReplacement` et lecture des inscriptions isolées dans `com.minos.storage.local.registry`), `minos-bootstrap` (câblage du port), `minos-application` (`ProjectPathMappingService.replace`), `minos-cli` (`DockerRuntimeBootstrap --replace`). Direction des dépendances inchangée.
- **Scripts livrés** (ASCII, Windows PowerShell 5.1) : `packaging/windows/minos-installer.iss.template`, `scripts/install/switch-mcp-backend.ps1`, `scripts/install/install-windows.ps1`, `docker/scripts/configure-docker-mcp.ps1`, `docker/scripts/prod-mcp-release.ps1`, `docker/scripts/mcp-lifecycle.ps1`. Aucun nouveau fichier livré.
- **Surfaces publiques** : CLI, API Java, MCP, IntelliJ et NEXUS inchangés (`rootAvailable` existe déjà). Additif : option `--replace` du bootstrap, paramètre `/REPLACEDOCKERPROJECTSROOT` de l'installeur, paramètre `-ReplaceDockerProjectsRoot` des scripts. Format du registre local : propriété additive `detachedHostRoot`, lue par cette version seulement (une version antérieure signale l'inscription illisible sans la perdre).
- **ADR** : aucun nouvel ADR ; applique ARCH-0042 (port fourni par la racine de composition) et `.claude/rules/securite.md` (points 1 et 6).
- **Gates à rejouer** : `check-twin-script-functions.py` (nouvelle fonction jumelle), `verify-installer-template.ps1`, `check-private-io.py`, `check-module-boundaries.py`, `check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`.
- **Plateformes** : code Java sur Windows et Linux ; installeurs Windows seulement.
