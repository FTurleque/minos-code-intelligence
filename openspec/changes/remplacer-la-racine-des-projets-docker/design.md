# Design

## Context

Constat du 1er novembre 2026 sur une installation 1.3.0 réelle (voir la proposition). `BOOT` = `minos-cli/src/main/java/com/minos/cli/DockerRuntimeBootstrap.java`, `SVC` = `minos-application/src/main/java/com/minos/application/ProjectPathMappingService.java`, `REG` = `minos-storage/src/main/java/com/minos/storage/local/registry/LocalProjectRegistry.java`, `MAP` = `minos-domain/src/main/java/com/minos/domain/registry/ProjectPathMapping.java`, `LC` = `docker/scripts/mcp-lifecycle.ps1`, `SW` = `scripts/install/switch-mcp-backend.ps1`, `ZIP` = `scripts/install/install-windows.ps1`, `CFG` = `docker/scripts/configure-docker-mcp.ps1`, `TPL` = `packaging/windows/minos-installer.iss.template`. HEAD analysé : `98dfbcca` (`develop`).

## État vérifié au HEAD

- `BOOT:43-46` : seule la forme `configure-project-paths <host-root> <container-root>` est acceptée. `BOOT:58-61` : `CONFLICT` → « refusing to replace an existing project path mapping implicitly », code 1.
- `SVC:41-49` : `configure` rend `UNCHANGED` pour la même correspondance, `CONFLICT` pour une autre, sans écrire. Aucune autre méthode.
- `REG:307-313` : avec une correspondance, une inscription de projet ne stocke que `rootRelativePath`, relatif à la racine. `REG:344-352` : à la lecture, ce chemin relatif est résolu sous la racine **courante**. Remplacer seulement le fichier de correspondance re-pointerait donc chaque projet vers `<nouvelle racine>/<même chemin relatif>`, c'est-à-dire un dossier absent (`rootAvailable: false`) ou **un autre dépôt du même nom**, auquel l'index de l'ancien projet serait attribué sans prévenir.
- `REG:542-546` : la correspondance est lue dans `<MINOS_HOME>/runtime/project-paths.properties`, le registre local vit dans `<MINOS_HOME>/registry` (`minos-storage/src/main/java/com/minos/storage/local/LocalStorageBackend.java:38`). `InterProcessLocalProjectRegistry` sérialise les écritures par un bail de fichier `.registry.lock`.
- `REG:416-427` : la migration d'une inscription héritée la sauvegarde une fois (`.m29-v1.bak`) avant de la réécrire ; les entrées `.bak` ne sont pas lues comme inscriptions (`REG:478-484`, suffixe `.properties`).
- `minos-application/src/main/java/com/minos/application/ProjectInspectionService.java:174` : `rootAvailable` = `Files.isDirectory(project.rootPath())`.
- `minos-storage/src/main/java/com/minos/storage/postgresql/project/PostgresProjectRegistry.java:49` : le registre PostgreSQL lit la même correspondance et stocke aussi des racines relatives (`:339-348`). Le bootstrap Docker n'a pas de réseau (`docker/compose-mcp.prod.yaml:117-142`, `network_mode: none`).
- Chaîne d'appel de l'installeur : `TPL:1017-1031` → `SW:430` (`& $DockerConfiguratorPath`) → `CFG:178` (`Invoke-DockerWorkflow -Action Install`) → `docker/scripts/prod-mcp-release.ps1` → `LC:581` (`Compose run minos-bootstrap`, commande fixée par la composition). `CFG:170-174` réécrit `minos.storage.backend=local` dans les données Docker **avant** l'Install, même pour une cible PostgreSQL : au moment du bootstrap, la configuration ne dit plus quel stockage portait les projets.
- `SW:378` : un runtime est réutilisé seulement si la racine des projets du marqueur est identique ; une autre racine passe par l'installation complète. `SW:379-382` : l'instantané de restauration ne couvre que `<docker>\runtime` et le marqueur, pas les données Docker (`docker-data`), où vivent la correspondance et le registre.
- `ZIP:122-138` contrôle la racine avant l'extraction ; `ZIP:217-231` appelle `SW` après.

## Décisions

### D1 — Voie explicite : `configure-project-paths --replace`

Le bootstrap accepte `--replace` en premier argument d'option (`configure-project-paths --replace <host> <container>`). Sans l'option, rien ne change. Les installeurs ne le passent que si la racine choisie diffère de la racine enregistrée **et** que l'utilisateur l'a confirmé. Option écartée : supprimer le fichier de correspondance depuis PowerShell (remplacement implicite, re-pointage silencieux des projets).

### D2 — Projets : rebasés ou isolés, jamais re-pointés ni désinscrits

Pour chaque inscription du registre local, la racine hôte absolue est calculée avec l'**ancienne** correspondance (`MAP` : jointure de la racine hôte et du chemin relatif). Puis :

- dans la nouvelle racine hôte → nouveau chemin relatif (`rootRelativePath`) ;
- hors de la nouvelle racine → inscription **isolée** : `rootRelativePath` est retiré, `detachedHostRoot=<racine hôte absolue>` est écrit. À la lecture, une inscription isolée est résolue vers `<registre>/detached-roots/<id>`, dossier que MINOS ne crée jamais : `rootAvailable` vaut `false`, l'index n'est pas touché, et aucun autre dépôt ne peut lui être attribué. Une mise à jour de l'inscription (espace de travail) conserve `detachedHostRoot`.
- une inscription déjà isolée dont la racine hôte entre dans la nouvelle racine est rattachée (retour à l'ancienne racine sans perte).

Options écartées : désinscrire (perte de l'index), garder les chemins relatifs tels quels (collision possible avec un autre dépôt).

### D3 — Ordre et reprise après interruption

Sous le bail exclusif du registre : (1) refus si une inscription est illisible ou porte la forme héritée sans pouvoir être calculée ; (2) sauvegarde de chaque inscription modifiée (`<id>.properties.<horodatage>.root-replacement.bak`, publication exclusive) ; (3) chaque inscription passe à la forme **isolée**, indépendante de la correspondance ; (4) écriture atomique de la nouvelle correspondance ; (5) rattachement des inscriptions isolées que la nouvelle racine contient. Une interruption à n'importe quelle étape laisse des inscriptions soit sous l'ancienne correspondance encore en place, soit isolées : aucune n'est re-pointée. Relancer `--replace` avec la même racine reprend l'étape 5 (idempotent).

### D4 — Refus fail-closed

- Inscription illisible : le remplacement est refusé, rien n'est écrit, le message compte les inscriptions concernées sans chemin.
- Stockage PostgreSQL : en Java, refus si `minos.storage.backend=postgresql` dans la configuration du MINOS_HOME ; dans `SW`, refus avant toute écriture si la cible ou le marqueur précédent déclare PostgreSQL (D4 doublé parce que `CFG:170-174` réécrit la configuration avant le bootstrap).

### D5 — Installeurs : comparaison, confirmation, restauration

- Une fonction PowerShell pure `Get-RecordedDockerProjectsRoot` lit `hostRoot` dans `<données Docker>\runtime\project-paths.properties` (échappements Java retirés) et le rend s'il diffère de la racine choisie, chaîne vide sinon ou sans fichier ; la comparaison ignore séparateurs, séparateur final et casse. Elle est copiée à l'identique dans `SW` et `ZIP` et déclarée dans `TWINS`.
- `SW` : paramètre `-ReplaceDockerProjectsRoot`. Racine différente sans le paramètre → refus avant `PREPARE` avec le remède. Avec le paramètre et une racine différente → instantané de `runtime\project-paths.properties` et de `registry\projects` des données Docker, `-ReplaceProjectsRoot` transmis à `CFG` → `prod-mcp-release.ps1` → `LC`, qui lance `minos-bootstrap configure-project-paths --replace …` ; en cas d'échec ultérieur, l'instantané est restauré avant de redémarrer l'ancien runtime.
- `ZIP` : même paramètre, même comparaison **avant** l'extraction, transmis à `SW`.
- `TPL` : sur la page Docker, si une racine différente est enregistrée, question Oui/Non qui nomme l'ancienne racine et l'effet sur les projets ; Oui → `-ReplaceDockerProjectsRoot`. En mode silencieux, `/REPLACEDOCKERPROJECTSROOT=1` vaut confirmation ; sans lui, `SW` refuse.

## Direction des dépendances

`domain` (calcul de racine) ← `engine` (port et rapport) ← `storage` (implémentation, package `com.minos.storage.local.registry`, qui dépend déjà de `com.minos.storage.home`) ← `bootstrap` (câblage). `application` ne nomme que le port ; `cli` ne nomme que le service. Aucun package nouveau, aucun cycle : `com.minos.storage.home` ne dépend pas du registre.

## Qualification

- Rebasage, isolement, reprise et refus : **qualifiés** par des tests Java multiplateformes (`minos-domain`, `minos-storage`, `minos-application`, `minos-cli`).
- Fonction PowerShell et transmission de l'option : **qualifiées** par un test sous Windows PowerShell 5.1 (`@EnabledOnOs(WINDOWS)`) et par contrat textuel ; la composition (`LC`) par `test_mcp_lifecycle_query_image.py` ou un contrat équivalent.
- Page de l'assistant : **partielle** — contrat textuel et compilation par `build-local-windows-candidate.ps1` ; le changement de racine sur une installation existante est vérifié à la main (tâche manuelle).
- Registre PostgreSQL : **non supporté** (refus).

## Risques

- Une version antérieure de MINOS qui lirait une inscription isolée la signalerait illisible (code 3) sans la perdre ; une rétrogradation après remplacement est hors périmètre.
- Un projet isolé dont le dépôt a été déplacé sous la nouvelle racine doit être réinscrit à la main ; l'ancienne inscription garde l'ancien index jusqu'à `project remove`.
