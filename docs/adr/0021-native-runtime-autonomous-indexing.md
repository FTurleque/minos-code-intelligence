# ADR-0021 — Utiliser un runtime MINOS natif pour l'indexation autonome

Date : 24 juillet 2026

Statut : **Accepted**

Origine : M14

## Contexte

MINOS dispose déjà d'un mode Docker PROD adapté au serveur MCP : image JRE minimale, aucun port réseau, `network_mode: none`, filesystem read-only et projets montés en lecture seule.

M14 ajoute une autre responsabilité : exécuter automatiquement les indexeurs sémantiques correspondant au projet (`scip-java`, `scip-typescript`, puis d'autres providers). Ces indexeurs doivent fonctionner avec les toolchains, wrappers, dépendances et conventions réelles du projet. Certains déclenchent une compilation ou nécessitent Node/npm.

Transformer le conteneur MCP en environnement de build universel créerait une tension forte avec son objectif de surface de lecture durcie : image beaucoup plus lourde, multiplication des toolchains, mappings de chemins hôte/conteneur, caches de build, accès réseau potentiel et compatibilité plus faible avec les projets locaux.

## Décision

Le **runtime natif local** devient le chemin principal pour :

- la CLI MINOS ;
- l'administration des projets ;
- l'exécution des providers ;
- l'indexation autonome ;
- le MCP local lorsqu'aucune isolation Docker spécifique n'est demandée.

Le runtime natif utilise les chemins réels du poste et exécute les providers dans l'environnement du projet.

Le **Docker MCP existant est conservé comme mode durci optionnel de consommation read-only**. Il ne devient pas le moteur de compilation/indexation des projets.

L'installation utilisateur native doit à terme embarquer son propre runtime Java afin que l'exécution de MINOS ne dépende pas du `JAVA_HOME` utilisateur. Les prérequis nécessaires au build du projet restent, eux, des prérequis du provider et du projet.

## Conséquences

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

## Alternatives rejetées

### Tout exécuter dans le conteneur MCP

Rejeté comme chemin principal : incompatible avec l'objectif de conteneur minimal read-only et trop dépendant de la diversité des toolchains projet.

### Abandonner Docker

Rejeté : le mode Docker reste utile comme surface MCP locale durcie, sans réseau et en lecture seule.

### Exiger un JDK 24 système pour toute installation utilisateur

Rejeté comme cible produit : Java 24 reste la toolchain de développement MINOS, mais la distribution utilisateur doit embarquer le runtime nécessaire à MINOS.

## Liens

- roadmap opérationnelle : [`../roadmap/M14_EXECUTION.md`](../roadmap/M14_EXECUTION.md)
- architecture d'indexation : [`../developer/indexing-and-storage.md`](../developer/indexing-and-storage.md)
- MCP : [`../user/mcp.md`](../user/mcp.md)
- issue : #42

## Mise en œuvre — note du 2026-10-13

La baseline du produit est passée de Java 24 à **Java 25 LTS** ([ADR 0059](0059-migrer-le-socle-vers-java-25-lts.md)). La décision est inchangée : la distribution utilisateur embarque son runtime, désormais produit par le JDK 25.

## Mise en œuvre — image de requête séparée (2026-10-13, AUD-DEP-15)

Le contexte de cet ADR décrit le conteneur MCP comme une « image JRE minimale ». Depuis l'image provider-complete de M29, ce n'était plus vrai : le conteneur persistant `minos-mcp` et le plan d'administration partageaient une image qui embarque JDK, compilateur C, Rust, Go, .NET et Node. `docker/Dockerfile.mcp.release` construit désormais deux cibles : `query` (JRE et jar), utilisée par `minos-mcp` et par les tâches qui n'exécutent que `java` ou `sh`, et `admin` (l'image complète), utilisée par les services qui lancent ou sondent des providers. La décision est inchangée et mieux tenue : le conteneur MCP redevient une surface de lecture sans chaîne de compilation. Détail d'exploitation et migration d'une installation existante : [`../user/docker-runtime.md`](../user/docker-runtime.md), « Deux images : requête et administration ».
