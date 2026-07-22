# ADR-0005 — Aligner MINOS sur Java 24 avec l'environnement de développement

- Statut : **Acceptée**
- Date de décision : **22 juillet 2026**
- Remplace : **ADR-0004 pour la version Java uniquement**

## Contexte

ADR-0004 avait retenu Java 25 LTS pour le cœur MINOS.

Lors du premier bootstrap M0 sur le poste de développement principal, l'environnement réel a montré que les autres projets et la toolchain locale sont alignés sur **Java 24.0.1**.

Imposer Java 25 uniquement à MINOS obligerait à maintenir plusieurs JDK locaux sans bénéfice fonctionnel démontré pour le projet à ce stade.

La cohérence de l'environnement de développement, la simplicité d'exploitation et la réduction des différences entre projets sont prioritaires pendant M0.

## Décision

MINOS utilise :

```text
Java 24
Apache Maven 3.9.x
Maven Wrapper
aucun framework serveur dans le cœur
```

Le bootstrap M0 fixe :

```text
maven.compiler.release = 24
```

et Maven Enforcer accepte la famille :

```text
[24,25)
```

## Règle d'alignement

MINOS ne doit pas imposer au poste de développement un JDK supplémentaire uniquement pour lui-même.

La règle est :

> **la version Java de MINOS suit la version Java de référence de l'environnement de développement principal et des projets associés, sauf contrainte technique démontrée et documentée.**

Une future montée vers Java 25, 26 ou une autre version devra être envisagée de manière coordonnée, pas comme une exception locale à MINOS.

## Conséquence pour `scip-java`

La documentation officielle actuelle de `scip-java` indique :

- son lanceur Java fonctionne avec un **JDK 17 ou supérieur** ;
- sa matrice de versions Java explicitement ciblées liste Java 17, 21 et 25.

Java 24 n'est donc pas une version explicitement ciblée dans cette matrice, même si le lanceur accepte un JDK 17+.

M0 doit par conséquent tester **Java 24 réellement**, sans installer Java 25 uniquement pour satisfaire l'indexeur.

La fixture de compatibilité devient :

```text
fixtures/java/java-24-smoke
```

Si `scip-java` ne sait pas indexer correctement un projet Java 24, ce résultat sera enregistré comme une **limitation du fournisseur `scip-java`**. Il ne modifiera pas automatiquement la version Java de MINOS.

## Éléments de l'ADR-0004 conservés

Les décisions suivantes restent valides :

- Apache Maven 3.9.x ;
- Maven Wrapper ;
- Maven 3.9.16 comme distribution de référence du bootstrap M0 ;
- aucun Spring, Quarkus ou Micronaut dans le cœur M0/MVP ;
- les frameworks d'exposition réseau restent périphériques et différés ;
- les fournisseurs externes restent isolés du domaine MINOS.

## Raisons

- cohérence avec le poste de développement principal ;
- cohérence avec les autres projets Java actuellement maintenus ;
- pas de multiplication inutile des JDK installés ;
- réduction des différences de toolchain entre projets ;
- reproductibilité plus simple en développement local ;
- aucune fonctionnalité M0/MVP n'exige Java 25 à ce stade.

## Risque assumé

Java 24 n'est pas une version LTS.

Ce risque est accepté consciemment au profit de l'alignement de l'écosystème de développement. Une évolution future du JDK devra être coordonnée au niveau des projets concernés.

## Validation

Cette ADR résulte d'une décision explicite du propriétaire du projet pendant M0 :

> **MINOS doit utiliser le même JDK que les autres projets ; l'environnement de développement de référence est actuellement Java 24.**
