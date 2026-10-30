---
id: ADR-0021
title: "Utiliser un runtime MINOS natif pour l'indexation autonome"
domain: architecture
rules: false
---

# ADR-0021 — Utiliser un runtime MINOS natif pour l'indexation autonome

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0069](../ARCH-0069-backends-mcp-natif-et-docker.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

Date : 24 juillet 2026

Statut : **Accepted — partiellement remplacé par ADR 0037**

Origine : M14

Relations : partiellement remplacé par [ADR 0037](0037-first-class-native-and-docker-runtime-backends.md) (rôle de Docker, voir ADR 0037 § « Relation avec ADR-0021 »).

## Context

MINOS dispose déjà d'un mode Docker PROD adapté au serveur MCP : image JRE minimale, aucun port réseau, `network_mode: none`, filesystem read-only et projets montés en lecture seule.

M14 ajoute une autre responsabilité : exécuter automatiquement les indexeurs sémantiques correspondant au projet (`scip-java`, `scip-typescript`, puis d'autres providers). Ces indexeurs doivent fonctionner avec les toolchains, wrappers, dépendances et conventions réelles du projet. Certains déclenchent une compilation ou nécessitent Node/npm.

Transformer le conteneur MCP en environnement de build universel créerait une tension forte avec son objectif de surface de lecture durcie : image beaucoup plus lourde, multiplication des toolchains, mappings de chemins hôte/conteneur, caches de build, accès réseau potentiel et compatibilité plus faible avec les projets locaux.

## Decision

Le **runtime natif local** devient le chemin principal pour :

- la CLI MINOS ;
- l'administration des projets ;
- l'exécution des providers ;
- l'indexation autonome ;
- le MCP local lorsqu'aucune isolation Docker spécifique n'est demandée.

Le runtime natif utilise les chemins réels du poste et exécute les providers dans l'environnement du projet.

Le **Docker MCP existant est conservé comme mode durci optionnel de consommation read-only**. Il ne devient pas le moteur de compilation/indexation des projets.

L'installation utilisateur native doit à terme embarquer son propre runtime Java afin que l'exécution de MINOS ne dépende pas du `JAVA_HOME` utilisateur. Les prérequis nécessaires au build du projet restent, eux, des prérequis du provider et du projet.

## Do's and Don'ts

### Do

- Utiliser le runtime natif local comme chemin principal pour la CLI, l’administration des projets, l’exécution des providers, l’indexation autonome et le MCP local sans isolation Docker demandée.
- Exécuter les providers dans l’environnement du projet, avec les chemins réels du poste.
- Embarquer dans l’installation utilisateur native son propre runtime Java.
- Tracer explicitement les commandes exécutées par un provider.

### Don't

- Ne pas installer silencieusement les dépendances métier d’un projet.
- Ne pas faire dépendre l’exécution de MINOS du `JAVA_HOME` de l’utilisateur.
- Ne pas faire du conteneur MCP de consommation read-only un environnement de build universel.

## Consequences

### Positives

- chemins identiques entre projet, CLI et provider ;
- réutilisation naturelle des Maven/Gradle wrappers et de Node installés pour le projet ;
- pas de conteneur de build universel à maintenir ;
- Docker MCP conserve son profil de sécurité minimal ;
- simplification de la configuration MCP native (`minos mcp`) ;
- diagnostics provider plus proches de l'environnement réellement utilisé par le développeur.

### Contraintes

- l'installation native doit être versionnée et reproductible ;
- les providers doivent être gérés/diagnostiqués séparément du runtime MINOS ;
- les commandes exécutées par un provider doivent être explicitement tracées ;
- MINOS ne doit pas installer silencieusement les dépendances métier d'un projet ;
- les mappings entre registre natif et Docker doivent rester explicites si les deux modes partagent des données.

## Compliance and Enforcement

- Aucun gate ni test ne cite cet ADR.
- Tests `BoundedFileLeaseSharedTest` et `BoundedFileLeaseSharedProcessTest` (`minos-engine`) — une lecture prend un bail partagé sur un verrou protégé en écriture sans le créer ni le modifier (note « le plan de requête lit sans écrire »).
- Test `M29DockerQueryImageContractTest` (`minos-app`) — la cible `query` de l’image Docker ne porte aucune chaîne de compilation (note AUD-DEP-15).
- Revue : un provider ne déclenche aucune installation silencieuse de dépendances du projet et ses commandes sont tracées.

## Alternatives rejetées

### Tout exécuter dans le conteneur MCP

Rejeté comme chemin principal : incompatible avec l'objectif de conteneur minimal read-only et trop dépendant de la diversité des toolchains projet.

### Abandonner Docker

Rejeté : le mode Docker reste utile comme surface MCP locale durcie, sans réseau et en lecture seule.

### Exiger un JDK 24 système pour toute installation utilisateur

Rejeté comme cible produit : Java 24 reste la toolchain de développement MINOS, mais la distribution utilisateur doit embarquer le runtime nécessaire à MINOS.

## References

- roadmap opérationnelle : [`../../project/roadmap/M14_EXECUTION.md`](../../../project/roadmap/M14_EXECUTION.md)
- architecture d'indexation : [`../../developer/indexing-and-storage.md`](../../../developer/indexing-and-storage.md)
- MCP : [`../../user/mcp.md`](../../../user/mcp.md)
- issue : #42

## Mise en œuvre — note du 2026-10-13

La baseline du produit est passée de Java 24 à **Java 25 LTS** ([ADR 0059](0059-migrer-le-socle-vers-java-25-lts.md)). La décision est inchangée : la distribution utilisateur embarque son runtime, désormais produit par le JDK 25.

## Mise en œuvre — image de requête séparée (2026-10-13, AUD-DEP-15)

Le contexte de cet ADR décrit le conteneur MCP comme une « image JRE minimale ». Depuis l'image provider-complete de M29, ce n'était plus vrai : le conteneur persistant `minos-mcp` et le plan d'administration partageaient une image qui embarque JDK, compilateur C, Rust, Go, .NET et Node. `docker/Dockerfile.mcp.release` construit désormais deux cibles : `query` (JRE et jar), utilisée par `minos-mcp` et par les tâches qui n'exécutent que `java` ou `sh`, et `admin` (l'image complète), utilisée par les services qui lancent ou sondent des providers. La décision est inchangée et mieux tenue : le conteneur MCP redevient une surface de lecture sans chaîne de compilation. Détail d'exploitation et migration d'une installation existante : [`../../user/docker-runtime.md`](../../../user/docker-runtime.md), « Deux images : requête et administration ».

## Mise en œuvre — le plan de requête lit sans écrire (2026-10-13)

La décision présente le Docker MCP comme un « mode durci optionnel de consommation read-only », et les fichiers Compose montent le répertoire de données en lecture seule pour `minos-mcp`. Du 10 août 2026 (`845136ed`) à ce correctif, ce n'était pas tenu : toute lecture du registre des projets ouvrait son fichier de verrou en écriture, et les outils MCP qui lisent un projet échouaient sur ce montage. Les lectures du registre et des observations runtime prennent désormais un bail **partagé** sur le verrou existant, ouvert en lecture (`BoundedFileLease.acquireShared`), sans rien créer, durcir ni migrer ; les écritures gardent leur bail exclusif. La qualification de l'image interroge le serveur sur un volume monté en lecture seule. Limites et conditions : [`docs/user/docker-runtime.md`](../../../user/docker-runtime.md) (« Le plan de requête lit sans écrire »). Changement OpenSpec : `lire-le-registre-sans-ecrire`.
