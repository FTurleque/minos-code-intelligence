# Design

## Context

Sprint 3 de l'audit du 10 octobre 2026 : **AUD-DEP-15**. HEAD analysé : `2d1cc214` (`develop`).

## État vérifié au HEAD

### AUD-DEP-15 — confirmé

- `docker/Dockerfile.mcp.release` : trois stages de toolchain (`rust-toolchain` `:2`, `go-toolchain` `:3`, `dotnet-toolchain` `:4`) et **un seul stage final, anonyme** (`:6`, base `-jdk`) ; `:61-63` installe `python3-pip build-essential pkg-config libicu-dev` ; `:66-69` copie cargo, rustup, Go et .NET ; `ENTRYPOINT` en `:278`.
- Aucun `HEALTHCHECK` dans `Dockerfile.mcp` ni `Dockerfile.mcp.release`.
- `${MINOS_IMAGE}` est utilisée par **7 services** de `docker/compose-mcp.connected.yaml` et **6** de `docker/compose-mcp.prod.yaml` : `minos-mcp` (`:40` / `:30`), `minos-admin` (`:106` / `:72`), `minos-bootstrap`, `minos-data-bootstrap`, `minos-tools-bootstrap`, `minos-provider-probe`, et `minos-ollama-bootstrap` (connected seulement).
- `minos-mcp` : `sleep infinity` (`connected:42`), `restart: unless-stopped` (`:95`) ; le serveur est lancé par `docker exec` (`docker/scripts/mcp-lifecycle.ps1:452`). Il monte le volume `minos-provider-tools` en lecture seule (`connected:65-68`).
- Le gate « Provider-complete image exact-head » est le job `provider-complete-image` de `docker-release-validation.yml:27-28` (`scripts/ci/qualify-docker-release.sh`) ; il porte `paths-ignore` et n'est pas un check exigé.

**Non lu en entier** à la spécification : `docker/scripts/prod-mcp-release.ps1`, `scripts/install/update-installation.ps1`, `scripts/ci/qualify-docker-upgrade.ps1`. Lus en entier à la tâche 1.1 : voir le relevé ci-dessous.

### Relevé des sites de l'image (tâche 1.1, relu au HEAD `d0aa51a8` le 2026-10-13)

Les trois scripts cités n'écrivent ni ne lisent `MINOS_IMAGE` ; les sites réels sont ailleurs.

| Site | Rôle |
|---|---|
| `docker/compose-mcp.connected.yaml:40,106,152,177,207,238,327` | lecture, 7 services |
| `docker/compose-mcp.prod.yaml:30,72,117,159,189,226` | lecture, 6 services |
| `docker/scripts/mcp-lifecycle.ps1:395` | **écriture** dans le `.env` d'exécution (image résolue `:314-321,364`, vérifiée `:323-344,365`, construite `:381-384`) |
| `docker/scripts/mcp-lifecycle.ps1:406-408,418,482,497-501` | l'image écrite sert au contrôle du runtime Java, à la lecture de l'inventaire des providers, au champ `image` de `installation.json`, à `Validate` et à la suppression par `Uninstall` |
| `docker/scripts/prod-mcp.ps1:266` | **écriture** par le lanceur de développement, qui exécute `compose-mcp.prod.yaml` (`:259`) avec l'image JRE de `Dockerfile.mcp` (`:251-257`) |
| `docker/scripts/verify-run-configurations.ps1:146` | écriture d'un `.env` de vérification pour `docker compose config` |
| `scripts/quality/check-image-pins.py:50` | seule variable d'image admise sans épinglage (`UNPINNED_ALLOWED_VARIABLES`) |
| `scripts/release/build-local-windows-candidate.ps1:215-230` | préconstruction de l'image exacte (même nom que `Resolve-Image`), sans passer par la variable |
| `scripts/ci/qualify-docker-release.sh:42,59-77` | construction et contrôles de l'image de la qualification, sans compose |
| `scripts/ci/qualify-docker-upgrade.ps1:265-266,193-205,303,349,391,343,415-417` | noms des images A et B, image attendue du conteneur `minos-mcp`, nettoyage |
| `docker/scripts/configure-m30-docker-services.ps1:342,400,449` | relit le `.env`, remplace le compose d'exécution par le profil connecté, réécrit le `.env` en conservant toutes ses clés |

`docker/scripts/prod-mcp-release.ps1` délègue tout à `mcp-lifecycle.ps1` (`:46-62`, il ne transmet que `-ImageTag`). `scripts/install/update-installation.ps1` ne remplace que les fichiers du programme (`:74-83`) : le répertoire d'exécution Docker (`runtime\compose-mcp.prod.yaml`, `.env`, `installation.json`) vit hors de l'arbre installé et n'est réécrit que par l'action `Install`, que l'installateur rejoue par `docker/scripts/configure-docker-mcp.ps1:155`. C'est donc `Install` qui donne la seconde image à une installation existante ; `update-installation.ps1` n'a qu'à livrer les fichiers.

`scripts/ci/qualify-docker-upgrade.ps1` installe le candidat A avec **sa** recette (`-SourceRoot`, `:286`) mais avec le script de cycle de vie courant (`:165-171`) : le script courant doit donc savoir installer une recette à une seule image (Dockerfile sans cible `query`).

### Classement des services (tâche 1.1, règle D2)

| Service | Commande réellement exécutée | Image |
|---|---|---|
| `minos-mcp` | `sleep infinity`, puis `docker exec … java -cp /opt/minos/minos.jar com.minos.mcp.MinosMcpServer` | requête |
| `minos-bootstrap` | `java … com.minos.cli.DockerRuntimeBootstrap configure-project-paths` (réécrit des chemins dans le registre) | requête |
| `minos-data-bootstrap` | `/bin/sh -c` : `chown`, `chmod` de la racine de données | requête |
| `minos-ollama-bootstrap` (connected) | `/bin/sh -c` : `find`, `chown` du volume de modèles | requête |
| `minos-admin` | `java … com.minos.cli.MinosLauncher` (indexation, `tools list`, `tools verify`) : lance les providers | administration |
| `minos-tools-bootstrap` | `/bin/sh -c` : copie `/opt/minos/provider-tools` (outils embarqués dans l'image) vers le volume | administration |
| `minos-provider-probe` | `/bin/sh -c` : `javac`, `mvn`, `scip-*`, `dotnet`, `go`, `rustc`, `cargo`, `rust-analyzer` | administration |

`minos-postgres` et `minos-ollama` gardent leurs images épinglées (hors sujet).

### Preuve préalable : le plan de requête n'exécute aucun outil de provider (tâche 1.2, 2026-10-13)

**Lecture du code.** Les lancements de processus du code de production (`new ProcessBuilder`) sont dans `minos-provider-scip` (`runtime/install/Managed*RuntimeManager` : `tools install`, `tools verify`), `minos-runtime-local` (`ProcessIndexerExecutor` : indexation ; bacs à sable ; `ProcessIdentity` : `whoami.exe`, Windows), `minos-engine` (`PrivateLocalStorage.WindowsDacl` : `icacls.exe`, Windows) et `minos-app` (`DockerMcpTransport` : routeur natif vers Docker, hors conteneur). `minos-mcp` ne dépend que de `minos-domain`, `minos-engine`, `minos-application` et `minos-bootstrap` ; `MinosApplicationMcpBackend` n'appelle que des services de lecture, et des providers il ne lit que `ProviderPlatformService.listStaticProfiles()` (profils statiques, état d'exécution non inspecté). Aucun des 31 outils MCP n'indexe, n'installe ni ne vérifie un outil.

**Exécution.** Jar du HEAD monté seul dans `eclipse-temurin:25.0.4.1_1-jre-noble@sha256:d9a39a23…` (la base JRE de `Dockerfile.mcp`), conteneur durci comme `minos-mcp` (`--network none --read-only --cap-drop ALL`, `no-new-privileges`, uid 10001, `/tmp` en `noexec`). Index réel obtenu sans provider : `project add` puis `import-scip` de l'artefact versionné `fixtures/typescript/typescript-modules/.minos-m0/scip-typescript/index.scip` (19 symboles, 67 occurrences). Les 31 outils de `tools/list` sont appelés un par un ; la même suite est rejouée, sur les mêmes données, dans l'image complète construite depuis le Dockerfile du HEAD.

| Données montées | Image complète (JDK, toolchains) | JRE et jar seuls | Écart |
|---|---|---|---|
| en écriture | 26 réponses valides, 5 refus `MINOS team mode is disabled` (`minos_team_*`, mode équipe non configuré) | 26 réponses valides, les 5 mêmes refus | aucun, hors `latencyMillis` de `minos_hybrid_search` et `minos_semantic_search` |
| en lecture seule (ce que fait le compose) | 31 échecs `MINOS tool execution failed` | 31 échecs identiques | aucun |

Aucun outil MCP ne dépend de la chaîne de compilation : la séparation peut se faire.

**Observation hors périmètre, à traiter ailleurs.** Avec la racine de données en lecture seule — le montage que déclarent les deux fichiers compose pour `minos-mcp` — tout outil qui lit le registre échoue, quelle que soit l'image : `InterProcessLocalProjectRegistry.withLock` ouvre `registry/.registry.lock` en écriture (`BoundedFileLease.openPrivateLockChannel`), refusé par un système de fichiers en lecture seule. Les qualifications Docker ne le voient pas : elles s'arrêtent à `initialize` et `tools/list` (`MinosDockerMcpSmoke`), qui ne lisent pas le registre. Ce changement ne corrige pas ce défaut et ne le masque pas : la requête de symboles de la qualification de release (D3) monte les données en écriture et le dit. **Suite (2026-10-13)** : traité par le changement `lire-le-registre-sans-ecrire`, qui remonte ces données en lecture seule dans la qualification.

### Écarts avec l'action proposée par l'audit

1. **L'audit parle de deux services ; il y en a sept.** « Faire pointer `minos-mcp` sur la première cible » laisse cinq services à classer. Trois (`minos-bootstrap`, `minos-data-bootstrap`, `minos-ollama-bootstrap`) semblent n'avoir besoin que de `java` ou `sh` : à vérifier commande par commande.
2. **L'image est construite sur le poste de l'utilisateur.** `mcp-lifecycle.ps1:381` fait un seul `docker build` et écrit une seule variable (`:395`). Deux cibles demandent deux constructions `--target` et une seconde variable dans les deux compose.
3. **La mise à niveau A→B part d'une installation à une seule image.** `scripts/ci/qualify-docker-upgrade.ps1` et `scripts/install/update-installation.ps1` doivent gérer l'apparition de la seconde variable ; cette qualification est un check exigé sur `main` (`release-promotion-gate.yml`).
4. **Le plan de requête monte les outils de provider.** Il faut prouver qu'il n'en exécute aucun avant de lui retirer les runtimes qui les feraient tourner ; sinon une requête qui en dépend échouerait dans la nouvelle image.
5. **`HEALTHCHECK`** : cité par l'audit comme absent, mais sans objet sur un conteneur `sleep infinity` (D4).

## Goals / Non-Goals

**Goals** : le conteneur persistant ne contient que ce qu'exige le plan de requête ; chaque service a une image choisie et justifiée ; installer, mettre à jour et qualifier fonctionnent avec deux images ; un compilateur réintroduit dans l'image de requête échoue en CI.

**Non-Goals** : alléger l'image d'administration ; changer le mode de lancement du serveur ; publier des images.

## Decisions

### D1. Deux cibles d'un même Dockerfile — **décidé par le propriétaire le 2026-10-13**

Décision : stages nommés `query` et `admin` dans `Dockerfile.mcp.release`. `check-image-pins.py` continue d'exiger une référence épinglée par image de base ; la base JRE de `query` est la même référence que celle de `Dockerfile.mcp`.

Alternative écartée : réutiliser `docker/Dockerfile.mcp` comme image de requête. Moins de lignes, mais deux Dockerfiles à tenir alignés (utilisateur, chemins, variables) et un fichier aujourd'hui réservé au développement devient un livrable.

Mise en œuvre :

- Un stage `licenses` (base JDK, même épinglage que `admin`) extrait les notices du jar ; `query` et `admin` les copient. La base JRE n'a pas `jar`, et faire copier `query` depuis `admin` obligerait à construire toute la chaîne de compilation pour obtenir l'image de requête.
- `admin` reste le **dernier** stage : une construction sans `--target` (ancien script, commande recopiée d'une documentation) produit l'image complète, comme avant, et jamais une image de requête étiquetée comme image d'administration.
- Chaque image porte l'étiquette `io.minos.image.plane` (`query` ou `admin`) ; `io.minos.providers.prepared` vaut `true` sur `admin` et `false` sur `query`. Le script de cycle de vie ne réutilise une image que si son étiquette de plan est la bonne.
- Nom de l'image de requête : celui de l'image d'administration suivi de `-query` (`minos-code-intelligence:<tag>-query`).

### D2. Deux variables, un classement écrit

`MINOS_IMAGE` garde son sens (administration) pour ne pas casser une installation existante ; `MINOS_QUERY_IMAGE` est ajoutée. Dans chaque compose, un commentaire par service dit pourquoi il utilise l'une ou l'autre. Règle : un service qui lance un provider, un outil embarqué ou une sonde de provider utilise l'image d'administration ; tout autre utilise l'image de requête.

Si le fichier compose du runtime demande `MINOS_QUERY_IMAGE` et que le `.env` ne la définit pas (runtime à moitié migré), le lancement **échoue avant tout appel à Docker, avec un message actionnable** qui nomme la commande de mise à jour ; il ne retombe pas sur l'image d'administration. `Start` et `Validate` refusent aussi une variable qui ne désigne pas une image de requête (étiquette `io.minos.image.plane`).

Précision apportée à l'implémentation (voir « Écarts constatés à l'implémentation ») : le runtime laissé par une version à une seule image — son fichier compose ne demande pas l'image de requête — reste utilisable, avec un avertissement, jusqu'à l'`Install` qui le migre. Le refuser rendrait impossible la qualification A → B, qui démarre le candidat A avec le script courant, et casserait toute installation entre la mise à jour de ses fichiers et cet `Install`.

### D3. La preuve porte sur le contenu de l'image

`qualify-docker-release.sh` exécute dans l'image de requête une liste de commandes qui doivent être **absentes** (`gcc`, `cc`, `cargo`, `rustc`, `go`, `dotnet`, `pip3`, `npm`, `node`, `javac`) et vérifie que le plan de requête répond (poignée de main MCP et une requête sur un index existant). Un test de contrat de `minos-app` affirme, statiquement, que le stage `query` ne contient ni `COPY --from=*-toolchain` ni les paquets de compilation.

### D4. Pas de `HEALTHCHECK` dans ce changement

Un `HEALTHCHECK` sur `sleep infinity` serait toujours vert. Un contrôle utile exigerait de changer le mode de lancement du serveur (processus principal du conteneur), ce qui touche le contrat STDIO de l'ADR 0017. Consigné comme observation ; non traité.

## Écarts constatés à l'implémentation (2026-10-13)

1. **Le refus D2 porte sur l'état incohérent, pas sur l'absence seule de la variable** (exigence et scénarios de la spécification précisés en conséquence). Raison ci-dessus.
2. **`scripts/install/update-installation.ps1` n'a aucun site à modifier.** Il ne remplace que les fichiers du programme ; l'installation reçoit sa seconde image par l'`Install` que l'installateur rejoue (`configure-docker-mcp.ps1:155`). Le fichier ne reçoit qu'un commentaire ; la preuve est le scénario 12 de `verify-windows-upgrade-transaction.ps1`.
3. **Sites ajoutés au périmètre** par le relevé : `docker/scripts/prod-mcp.ps1` (lanceur de développement, qui exécute le même compose), `docker/scripts/configure-m30-docker-services.ps1` (copie le profil connecté par-dessus le runtime : même refus), `scripts/quality/check-image-pins.py` (seconde variable admise sans épinglage).
4. **Environnement ambiant.** Pour Compose, l'environnement du processus l'emporte sur `--env-file`. Le script de cycle de vie masque donc `MINOS_QUERY_IMAGE` de l'environnement pendant chaque appel à Compose et la restaure ensuite : sans cela une variable laissée par le shell appelant déplaçait le plan de requête sur une autre image que celle qui a été vérifiée (défaut introduit puis corrigé pendant l'implémentation, vu au rejeu local de la mise à niveau).
5. **`Stop` et `Uninstall` restent possibles sur un runtime à moitié migré** : Compose refuse d'interpoler un fichier dont une variable exigée manque, même pour `stop` et `down` ; un substitut qui ne nomme aucune image lui est passé pour ces deux actions seulement.
6. **La requête de symboles de D3 monte les données en écriture** (voir l'observation de la tâche 1.2) et importe un artefact SCIP versionné : aucun provider ne tourne dans le conteneur Docker, où l'indexation est refusée par conception.
7. **`scripts/release/build-local-windows-candidate.ps1` n'est pas modifié.** Sa préconstruction, sans `--target`, produit l'image d'administration (dernier stage) ; l'installateur construit alors la seule image de requête (quelques secondes).

## Direction des dépendances

Aucune dépendance de module. Les tests de contrat restent dans `minos-app` (assemblage final distribué).

## Qualification des capacités

- Plan de requête MCP sur l'image de requête : **non qualifiée** au HEAD ; qualifiée par D3.
- Indexation, sondes de providers, amorçage des outils sur l'image d'administration : inchangées, à rejouer.
- Mise à niveau d'une installation à une image vers deux images : **non qualifiée** ; qualifiée par la tâche 3.3.

## Windows et Linux

Les images sont Linux. `mcp-lifecycle.ps1` s'exécute sous Windows (Docker Desktop) et Linux : les deux constructions et l'écriture de la seconde variable doivent passer sur les deux ; `verify-run-configurations.ps1` est mis à jour. Les `.ps1` restent en CRLF.

## Risks / Trade-offs

- Temps de construction locale : la cible `query` ne partage aucune couche de toolchain avec `admin`. **Mesuré le 2026-10-13** (Docker Desktop 29.8.2, 16 CPU, `linux/amd64`, `--network=host`) : avant, l'image unique du HEAD en 566 s (première construction, images de base à télécharger, aucune couche en cache), 5 826 395 403 octets ; après, `--target admin --no-cache` en 290 s (images de base déjà présentes), 5 826 393 983 octets, et `--target query --no-cache` en 6 s, 510 811 418 octets (1 s avec le cache). Les deux constructions d'`admin` ne sont pas comparables entre elles (téléchargements, miroir `snapshot.ubuntu.com`) ; le surcoût de la seconde cible, lui, est de 6 s, soit 1 à 2 % de la construction.
- Une requête qui dépend en fait d'un runtime de provider échouerait : couvert par la preuve de la tâche 1.2, faite **avant** la séparation.
- Deux images à garder cohérentes (même commit) : l'étiquette de commit est vérifiée sur les deux par la qualification.
- Conflits de lignes avec `migrer-le-socle-vers-java-25` et `aligner-node-et-l-inventaire-de-l-image-docker` : ce changement se fait après eux.
