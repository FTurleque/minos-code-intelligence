# ADR-0004 — Implémenter le cœur MINOS en Java 25 avec Maven, sans framework serveur

- Statut : **Proposée — à valider pendant C0**
- Date de proposition : **22 juillet 2026**

## Contexte

MINOS doit disposer d'une stack d'implémentation principale sans confondre :

- le langage dans lequel MINOS est développé ;
- les langages que MINOS sait analyser.

Le cœur restera agnostique des langages analysés même s'il est lui-même développé en Java.

Les contraintes principales sont :

- Windows / Linux / macOS ;
- local-first ;
- bonne intégration Protobuf ;
- possibilité d'utiliser Thrift si Glean est retenu ;
- outillage mature ;
- performances suffisantes pour des services locaux ;
- maintenance longue durée ;
- tests et packaging reproductibles ;
- éviter un framework serveur avant qu'une API réseau soit réellement nécessaire.

## État de l'écosystème au 22 juillet 2026

- Java 25 est une version LTS publiée en septembre 2025 ;
- Java 26 est une version non-LTS ;
- Maven 3.9.16 est la branche stable recommandée actuellement ;
- Maven 4 n'est pas encore GA et reste une version de prévisualisation / release candidate.

## Décision proposée

### Langage principal

```text
Java 25 LTS
```

### Build

```text
Apache Maven 3.9.x
```

La version exacte utilisée par la CI et le wrapper devra être épinglée au bootstrap ; au moment de cette ADR, la version stable observée est `3.9.16`.

### Framework

Le cœur M0/MVP ne doit dépendre d'aucun framework serveur.

Architecture :

```text
Java 25
  │
  ├── domaine MINOS
  ├── services de requêtes
  ├── adaptateurs
  ├── ingestion SCIP
  └── CLI / harness M0

Framework serveur
  └── différé jusqu'au besoin API/MCP approprié
```

Un framework comme Quarkus pourra être évalué ultérieurement pour une couche d'exposition réseau, sans pénétrer le domaine.

## Raisons du choix Java 25

- LTS actuelle adaptée à un nouveau projet ;
- très bonne portabilité desktop / serveur ;
- Protobuf mature ;
- clients Thrift possibles ;
- bon support du parallélisme et des charges locales ;
- écosystème de tests et de profiling mature ;
- cohérence avec plusieurs projets existants servant de terrains de validation ;
- facilite le développement et la maintenance dans l'environnement principal du projet.

## Pourquoi Java 25 et non Java 26

Java 26 est une version non-LTS.

MINOS étant un projet d'infrastructure destiné à évoluer sur plusieurs années, une LTS est préférable pour éviter une migration de runtime semestrielle sans bénéfice nécessaire.

## Pourquoi Maven 3.9.x

- stable et recommandé actuellement par Apache Maven ;
- Maven 4 n'est pas encore GA ;
- très bon support des projets Java et de la CI ;
- simplicité pour un projet dont l'auteur maîtrise déjà fortement Maven ;
- possibilité de migrer vers Maven 4 ultérieurement lorsque celui-ci sera GA et suffisamment stabilisé.

## Pourquoi pas de framework serveur dans le cœur

M0 doit essentiellement :

- exécuter des indexeurs ;
- ingérer des données ;
- normaliser les résultats ;
- interroger un store ;
- mesurer les performances.

Ajouter immédiatement un framework serveur apporterait :

- temps de démarrage ;
- dépendances ;
- configuration ;
- abstractions réseau inutiles ;
- risque de couplage prématuré.

Les couches :

```text
CLI
MCP
API
```

doivent rester des adaptateurs périphériques.

## Alternatives étudiées

### Java 21

Valide et LTS, mais moins pertinent pour un projet neuf lancé après la disponibilité de Java 25 LTS, sauf contrainte de compatibilité externe découverte ultérieurement.

### Java 26

Non retenu comme cible principale car non-LTS.

### Kotlin

Intéressant sur la JVM, mais ajouterait un langage d'implémentation supplémentaire sans avantage décisif pour le cœur initial.

### Go

Très intéressant pour les binaires et les outils locaux, et proche de certains outils SCIP. Non retenu comme choix principal compte tenu de l'écosystème existant et de la préférence pour un domaine riche fortement typé en Java.

### Rust

Excellent pour performance et distribution native, mais coût de développement et d'intégration plus élevé pour le contexte du projet. Peut rester pertinent pour des composants spécialisés futurs.

### Maven 4

Non retenu au lancement tant qu'il n'est pas GA.

### Gradle

Techniquement viable, mais aucun bénéfice suffisant n'est identifié pour justifier de remplacer Maven dans le contexte du projet.

## Règles résultantes proposées

1. `maven.compiler.release = 25` au bootstrap.
2. Maven Wrapper afin d'épingler la version de build.
3. Pas de Spring / Quarkus / Micronaut dans le domaine ou les services M0.
4. Une couche API future peut choisir son framework indépendamment.
5. Les DTO/domain objects ne dépendent pas du framework d'exposition.
6. Les adaptateurs d'indexeurs externes sont isolés du domaine.
7. Les outils externes restent exécutables comme processus séparés lorsque nécessaire.

## Validation attendue

Avant acceptation de cette ADR, confirmer :

- Java 25 comme runtime de référence ;
- Maven comme build principal ;
- absence de besoin immédiat d'un framework serveur ;
- compatibilité suffisante des bibliothèques requises par le spike SCIP.

## Sources officielles

Consultées le 22 juillet 2026 :

- OpenJDK 25 : https://openjdk.org/projects/jdk/25/
- Oracle Java SE Support Roadmap : https://www.oracle.com/java/technologies/java-se-support-roadmap.html
- Apache Maven — installation : https://maven.apache.org/install.html
- Apache Maven — historique des versions : https://maven.apache.org/docs/history.html
