# ADR-0004 — Implémenter le cœur MINOS en Java 25 avec Maven, sans framework serveur

- Statut : **Partiellement remplacée par ADR-0005**
- Date de décision : **22 juillet 2026**
- Remplacement : **la cible Java 25 est remplacée par Java 24 via ADR-0005**

> Cette ADR conserve la trace de la décision C0 initiale. La version Java courante de MINOS est désormais définie par **ADR-0005**. Les décisions Maven 3.9.x, Maven Wrapper et cœur sans framework serveur restent valides.

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

## Décision initiale C0

C0 avait retenu :

```text
Java 25 LTS
Apache Maven 3.9.x
Maven Wrapper
aucun framework serveur dans le cœur
```

La partie **Java 25** de cette décision est remplacée pendant M0 par **ADR-0005 — Aligner MINOS sur Java 24 avec l'environnement de développement**.

La décision courante est donc :

```text
Java 24                       ← ADR-0005
Apache Maven 3.9.x            ← conservé
Maven Wrapper                 ← conservé
aucun framework serveur       ← conservé
```

## Build

La version Maven de référence au bootstrap M0 reste `3.9.16` et doit être épinglée par Maven Wrapper.

## Framework

Le cœur M0/MVP ne dépend d'aucun framework serveur.

Architecture actuelle après ADR-0005 :

```text
Java 24
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

## Pourquoi Maven 3.9.x

- stable et éprouvé ;
- très bon support des projets Java et de la CI ;
- simplicité pour un projet dont l'auteur maîtrise déjà fortement Maven ;
- possibilité de migrer vers Maven 4 ultérieurement lorsque cette migration apportera un bénéfice réel.

## Alternatives étudiées lors de C0

La sélection initiale avait comparé Java 21, Java 25, Java 26, Kotlin, Go et Rust.

Cette comparaison reste un historique de C0, mais **la cohérence avec la toolchain Java 24 déjà utilisée sur le poste de développement et les autres projets devient la contrainte opérationnelle prioritaire via ADR-0005**.

## Règles courantes après ADR-0005

1. `maven.compiler.release = 24` au bootstrap.
2. Maven Wrapper épingle Maven `3.9.16` au lancement de M0.
3. Pas de Spring / Quarkus / Micronaut dans le domaine ou les services M0.
4. Une couche API future peut choisir son framework indépendamment.
5. Les DTO/domain objects ne dépendent pas du framework d'exposition.
6. Les adaptateurs d'indexeurs externes sont isolés du domaine.
7. Les outils externes restent exécutables comme processus séparés lorsque nécessaire.

## Validation

Cette ADR reste l'historique de la décision de stack prise à la clôture de C0.

Pendant M0, la décision explicite d'aligner MINOS sur le JDK déjà utilisé dans l'environnement de développement a conduit à **ADR-0005**, qui remplace uniquement la cible Java 25 par **Java 24**.

La stack courante doit être lue conjointement avec ADR-0005.
