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

**Non lu en entier** : `docker/scripts/prod-mcp-release.ps1`, `scripts/install/update-installation.ps1`, `scripts/ci/qualify-docker-upgrade.ps1`. La tâche 1.1 les relève avant d'écrire.

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

### D1. Deux cibles d'un même Dockerfile — **décision en attente du propriétaire**

Recommandation : stages nommés `query` et `admin` dans `Dockerfile.mcp.release`. Le jar et l'utilisateur d'exécution sont définis une fois ; `check-image-pins.py` continue d'exiger une source par image de base ; la base JRE de `query` est la même référence que celle de `Dockerfile.mcp`.

Alternative : réutiliser `docker/Dockerfile.mcp` comme image de requête. Moins de lignes, mais deux Dockerfiles à tenir alignés (utilisateur, chemins, variables) et un fichier aujourd'hui réservé au développement devient un livrable.

### D2. Deux variables, un classement écrit

`MINOS_IMAGE` garde son sens (administration) pour ne pas casser une installation existante ; `MINOS_QUERY_IMAGE` est ajoutée. Dans chaque compose, un commentaire par service dit pourquoi il utilise l'une ou l'autre. Règle : un service qui lance un provider, un outil embarqué ou une sonde de provider utilise l'image d'administration ; tout autre utilise l'image de requête.

Si `MINOS_QUERY_IMAGE` est absente (installation non migrée), le lancement **échoue avec un message actionnable** qui nomme la commande de mise à jour ; il ne retombe pas en silence sur l'image d'administration.

### D3. La preuve porte sur le contenu de l'image

`qualify-docker-release.sh` exécute dans l'image de requête une liste de commandes qui doivent être **absentes** (`gcc`, `cc`, `cargo`, `rustc`, `go`, `dotnet`, `pip3`, `npm`, `node`, `javac`) et vérifie que le plan de requête répond (poignée de main MCP et une requête sur un index existant). Un test de contrat de `minos-app` affirme, statiquement, que le stage `query` ne contient ni `COPY --from=*-toolchain` ni les paquets de compilation.

### D4. Pas de `HEALTHCHECK` dans ce changement

Un `HEALTHCHECK` sur `sleep infinity` serait toujours vert. Un contrôle utile exigerait de changer le mode de lancement du serveur (processus principal du conteneur), ce qui touche le contrat STDIO de l'ADR 0017. Consigné comme observation ; non traité.

## Direction des dépendances

Aucune dépendance de module. Les tests de contrat restent dans `minos-app` (assemblage final distribué).

## Qualification des capacités

- Plan de requête MCP sur l'image de requête : **non qualifiée** au HEAD ; qualifiée par D3.
- Indexation, sondes de providers, amorçage des outils sur l'image d'administration : inchangées, à rejouer.
- Mise à niveau d'une installation à une image vers deux images : **non qualifiée** ; qualifiée par la tâche 3.3.

## Windows et Linux

Les images sont Linux. `mcp-lifecycle.ps1` s'exécute sous Windows (Docker Desktop) et Linux : les deux constructions et l'écriture de la seconde variable doivent passer sur les deux ; `verify-run-configurations.ps1` est mis à jour. Les `.ps1` restent en CRLF.

## Risks / Trade-offs

- Temps de construction locale : la cible `query` réutilise les couches du JRE et du jar ; surcoût faible, à mesurer (tâche 2.3).
- Une requête qui dépend en fait d'un runtime de provider échouerait : couvert par la preuve de la tâche 1.2, faite **avant** la séparation.
- Deux images à garder cohérentes (même commit) : l'étiquette de commit est vérifiée sur les deux par la qualification.
- Conflits de lignes avec `migrer-le-socle-vers-java-25` et `aligner-node-et-l-inventaire-de-l-image-docker` : ce changement se fait après eux.
