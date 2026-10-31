---
id: ARCH-0069
title: "Backends MCP natif et Docker"
domain: architecture
rules: false
---

# Backends MCP natif et Docker

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0021](archive/0021-native-runtime-autonomous-indexing.md), [ADR-0037](archive/0037-first-class-native-and-docker-runtime-backends.md) (l'ADR 0037 remplace partiellement l'ADR 0021)
- **Remplacé par** : —

## Context

Les indexeurs sémantiques (`scip-java`, `scip-typescript`, puis d'autres providers) doivent fonctionner avec les toolchains, wrappers, dépendances et conventions réelles du projet ; certains déclenchent une compilation ou exigent Node et npm. Transformer le conteneur MCP en environnement de build universel contredirait son rôle de surface de lecture durcie (image JRE minimale, aucun port réseau, `network_mode: none`, système de fichiers en lecture seule, projets montés en lecture seule) : image plus lourde, toolchains multipliées, correspondances de chemins hôte et conteneur, caches de build, accès réseau potentiel (ADR 0021 § Context).

Docker doit pourtant pouvoir servir de backend autonome de premier rang sans casser les intégrations des clients IA (Copilot, Claude, Codex). Le risque principal serait de masquer une indisponibilité de Docker en ouvrant l'application native, ou d'obliger chaque client IA à connaître la topologie du backend. Ouvrir `MinosApplication` initialise immédiatement le registre, les snapshots, l'état d'index, les empreintes, le vector store et les runtimes des providers : le choix du backend intervient donc avant cette ouverture (ADR 0037 § Context).

Options écartées :

- **Tout exécuter dans le conteneur MCP** : incompatible avec un conteneur minimal en lecture seule et trop dépendant de la diversité des toolchains des projets (ADR 0021 § Alternatives rejetées).
- **Abandonner Docker** : le mode Docker reste utile comme surface MCP locale durcie, sans réseau et en lecture seule (ADR 0021 § Alternatives rejetées).
- **Configurer chaque client IA directement vers `docker exec`** : le backend deviendrait visible dans chaque configuration tierce et chaque bascule une mutation multi-client risquée (ADR 0037 § Alternatives rejetées).
- **Essayer Docker puis tomber silencieusement sur le natif** : le runtime réellement utilisé deviendrait ambigu, les données pourraient diverger, et un utilisateur qui demande l'isolation Docker obtiendrait un comportement natif sans le savoir (ADR 0037 § Alternatives rejetées).
- **Partager automatiquement le même chemin physique de home** : Windows et Linux ont des emplacements physiques différents ; une identité logique et une correspondance typée remplacent la substitution de chaînes (ADR 0037 § Alternatives rejetées).

## Decision

### 1. Runtime natif

- Le runtime natif local est le chemin principal pour la CLI, l'administration des projets, l'exécution des providers, l'indexation autonome, et le MCP local lorsqu'aucune isolation Docker n'est demandée. Il utilise les chemins réels du poste et exécute les providers dans l'environnement du projet (ADR 0021 § Decision).
- L'installation utilisateur native embarque son propre runtime Java : l'exécution de MINOS ne dépend pas du `JAVA_HOME` de l'utilisateur. Les prérequis du build du projet restent ceux du provider et du projet (ADR 0021 § Decision). Le JDK de ce runtime est celui de l'[ADR 0067](GEN-0067-socle-d-execution-java-25-et-maven.md).
- L'installation native est versionnée et reproductible ; les providers sont gérés et diagnostiqués séparément du runtime MINOS ; les commandes qu'un provider exécute sont tracées explicitement ; MINOS n'installe jamais silencieusement les dépendances métier d'un projet ; les correspondances entre registre natif et Docker restent explicites si les deux modes partagent des données (ADR 0021 § Consequences ; principes conservés par l'ADR 0037 § « Relation avec ADR-0021 »).

### 2. Docker : backend autonome, plan de requête durci

- Docker est un backend autonome de premier rang, et non nécessairement un simple MCP en lecture seule non autonome (ADR 0037 § « Relation avec ADR-0021 »).
- L'administration et l'indexation Docker forment un plan distinct, afin de ne pas affaiblir le serveur MCP en lecture seule (ADR 0037 § « Relation avec ADR-0021 »).
- Le conteneur MCP est une surface de lecture sans chaîne de compilation. `docker/Dockerfile.mcp.release` construit deux cibles : `query` (JRE et jar), utilisée par `minos-mcp` et par les tâches qui n'exécutent que `java` ou `sh`, et `admin` (image complète), utilisée par les services qui lancent ou sondent des providers (ADR 0021 § « Mise en œuvre — image de requête séparée »).
- Le plan de requête lit sans écrire : les fichiers Compose montent le répertoire de données en lecture seule pour `minos-mcp` ; les lectures du registre et des observations runtime prennent un bail partagé sur le verrou existant, ouvert en lecture (`BoundedFileLease.acquireShared`), sans rien créer, durcir ni migrer ; les écritures gardent leur bail exclusif (ADR 0021 § « Mise en œuvre — le plan de requête lit sans écrire »).

### 3. Point d'entrée stable

Les clients IA invoquent `minos.exe mcp`. Le lanceur résout le home client, puis charge une configuration de backend versionnée avant toute initialisation métier propre au runtime (ADR 0037 § « Point d'entrée stable »).

### 4. Contrat de backend

- Le format courant est `formatVersion=1` et accepte exclusivement `backend=native` ou `backend=docker`. La configuration est stockée sous `<MINOS_HOME>/runtime/backend.properties` et contient aussi le nom du conteneur Docker géré et le délai de sonde (ADR 0037 § « Contrat backend »).
- Une installation sans fichier, antérieure à ce contrat, est migrée une seule fois vers une configuration native explicite ; cette migration de compatibilité n'est pas un repli à l'exécution. Dès qu'un fichier existe, une version, un backend, une propriété inconnus ou une valeur invalide échouent fermé (ADR 0037 § « Contrat backend »).

### 5. Routage

- **`native`** : le routeur ouvre `MinosApplication` sur le home résolu et démarre `MinosMcpServer` dans le processus courant (ADR 0037 § « Routage natif »).
- **`docker`** : le routeur n'ouvre pas `MinosApplication` côté hôte. Il vérifie dans l'ordre `docker version` et la disponibilité du démon, puis l'existence et l'état `Running` du conteneur MINOS configuré, et seulement ensuite ouvre une session STDIO `docker exec -i` vers `com.minos.mcp.MinosMcpServer`. L'entrée, la sortie et stderr de la session sont reliées directement au processus `minos.exe`, ce qui préserve le transport MCP STDIO (ADR 0037 § « Routage Docker » ; [ADR 0017](ARCH-0017-mcp-stdio-read-only.md)).
- Si Docker, le démon ou le conteneur est indisponible, MINOS échoue explicitement : **aucun repli Docker → natif** (ADR 0037 § « Routage Docker »).
- Les sondes sont bornées dans le temps. Une interruption détruit le processus enfant et restaure le statut d'interruption Java. Les erreurs d'amorçage restent associées au code d'erreur d'exécution existant de la CLI ; une session MCP normale retourne succès (ADR 0037 § « Routage Docker »).

### 6. Bascule de backend

La bascule prépare le backend cible, le valide, fait le handshake, écrit atomiquement `backend.properties`, et seulement ensuite retire ce qui peut l'être de l'ancien backend. Une validation échouée laisse la configuration active inchangée (ADR 0037 § Switching).

### 7. Parité

L'acceptation de cette décision n'établit pas la parité fonctionnelle de Docker avec le natif : aucun document ne la déduit de cet ADR (ADR 0037 § Statut, § Do's and Don'ts et § Consequences). Voir « Écarts constatés ».

L'emplacement des classes du routage (`com.minos.app`, route `minos mcp` par le SPI `McpLaunchRoute`) relève de l'[ADR 0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) ; l'indexation Java sous Windows, que le runtime Docker n'assure pas, de l'[ADR 0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) ; la reprise d'une indexation interrompue, de l'[ADR 0039](BE-0039-reprise-indexation-apres-interruption.md).

## Do's and Don'ts

### Do

- Utiliser le runtime natif comme chemin principal de la CLI, de l'administration, des providers et de l'indexation autonome, avec un runtime Java embarqué.
- Tracer explicitement les commandes exécutées par un provider.
- Garder `minos.exe mcp` comme point d'entrée stable des clients IA et charger `<MINOS_HOME>/runtime/backend.properties` (`formatVersion=1`) avant toute initialisation métier propre au runtime.
- Échouer fermé, dès qu'un fichier existe, sur une version, un backend, une propriété ou une valeur inconnus ou invalides.
- Pour `docker`, sonder le démon puis l'état `Running` du conteneur configuré, avec des sondes bornées, avant d'ouvrir la session `docker exec -i`.
- Servir le MCP Docker depuis la cible `query`, sans chaîne de compilation, sur des données montées en lecture seule.

### Don't

- Ne pas installer silencieusement les dépendances métier d'un projet.
- Ne pas faire dépendre l'exécution de MINOS du `JAVA_HOME` de l'utilisateur.
- Ne pas tomber de Docker vers le natif, ni ouvrir `MinosApplication` côté hôte quand le backend est `docker`.
- Ne pas configurer chaque client IA directement vers `docker exec`.
- Ne pas faire du conteneur MCP de requête un environnement de build universel.
- Ne pas déduire la parité Docker de l'acceptation de cette décision.

## Consequences

### Positives

- Chemins identiques entre projet, CLI et provider ; les wrappers Maven et Gradle et le Node installés pour le projet sont réutilisés ; les diagnostics des providers sont proches de l'environnement du développeur (ADR 0021 § Consequences).
- Les configurations des clients IA restent stables ; le backend est un choix MINOS explicite, versionné et testable ; une bascule n'exige pas de réécrire les clients (ADR 0037 § Consequences).
- Sélectionner Docker ne crée ni registre, ni snapshot, ni vector store natif par effet de bord ; l'indisponibilité de Docker est visible et déterministe (ADR 0037 § Consequences).
- Le serveur MCP Docker garde un profil de sécurité minimal (ADR 0021 § Consequences).

### Contraintes

- Le home côté hôte conserve la petite configuration de routage même lorsque les données métier vivent dans le volume Docker (ADR 0037 § Consequences).
- Le conteneur est préparé et validé avant de sélectionner `docker` (ADR 0037 § Consequences).
- Deux images Docker sont construites et qualifiées : `query` et `admin` (ADR 0021 § « Mise en œuvre — image de requête séparée »).

## Compliance and Enforcement

- Tests `McpBackendRouterTest` et `McpBackendRouterLifecycleTest` (`minos-app`, lancés par `mvnw verify`) — configuration invalide ou inconnue refusée, home et configuration vérifiés avant tout effet Docker, Docker indisponible sans repli natif, ordre démon → conteneur → STDIO, backend natif seul en mode `native`, application native fermée une fois la session terminée.
- Portée JaCoCo `m29-backend-routing` de `scripts/quality/check-jacoco.py` (job `verify`, `.github/workflows/pr-ci.yml:335`) — couverture des classes `McpBackend*` de `minos-app`.
- Test `M29DockerQueryImageContractTest` (`minos-app`) — la cible `query` de `docker/Dockerfile.mcp.release` ne porte aucune chaîne de compilation, et chaque service de `docker/compose-mcp.prod.yaml` et `docker/compose-mcp.connected.yaml` utilise l'image de son rôle (`minos-mcp` sur l'image de requête).
- Tests `BoundedFileLeaseSharedTest` et `BoundedFileLeaseSharedProcessTest` (`minos-engine`) — une lecture prend un bail partagé sur un verrou protégé en écriture sans le créer ni le modifier.
- Aucun gate Python ne garde cette décision.
- Revue : aucun chemin d'erreur Docker n'ouvre l'application native ; un provider ne déclenche aucune installation silencieuse de dépendances du projet et ses commandes sont tracées ; aucun document n'annonce la parité Docker sur la seule foi de cet ADR.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché. Écart non tranché par le propriétaire au 2026-10-23.

- L'ADR 0037 déclare « parité Docker non acquise » alors que la roadmap présente le jalon M29 « Autonomous Docker Runtime & Native Parity » comme terminé (issue #107 fermée, PR #108 intégrée) — preuve : `docs/project/governance/ROADMAP.md:52`.

## References

- ADR remplacés : [0021](archive/0021-native-runtime-autonomous-indexing.md) (runtime natif, notes de mise en œuvre du 2026-10-13), [0037](archive/0037-first-class-native-and-docker-runtime-backends.md) (backends MCP explicites, contrat S1).
- ADR liés : [0017](ARCH-0017-mcp-stdio-read-only.md) (MCP STDIO en lecture seule), [0039](BE-0039-reprise-indexation-apres-interruption.md) (reprise d'indexation), [0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) (aucune voie d'indexation Java depuis Windows, WSL2 retenu), [0067](GEN-0067-socle-d-execution-java-25-et-maven.md) (runtime Java embarqué), [0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) (emplacement des classes de routage, SPI `McpLaunchRoute`) ; [0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md) (distribution auto-portante, qui complète l'ADR 0037).
- Exploitation : [`docs/user/docker-runtime.md`](../../user/docker-runtime.md) (« Deux images : requête et administration », « Le plan de requête lit sans écrire »), [`docs/user/mcp.md`](../../user/mcp.md), [`docs/user/production-installation.md`](../../user/production-installation.md).
- Roadmap : [`docs/project/roadmap/M29_EXECUTION.md`](../../project/roadmap/M29_EXECUTION.md), [`docs/project/roadmap/M14_EXECUTION.md`](../../project/roadmap/M14_EXECUTION.md) ; [`docs/project/governance/ROADMAP.md`](../../project/governance/ROADMAP.md).
