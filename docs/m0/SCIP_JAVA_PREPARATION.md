# M0 — Préparation de l'expérimentation `scip-java`

Date : **22 juillet 2026**

Statut : **Préparation de l'Expérience A**

## Versions vérifiées

- `scip-java` : **0.12.3** ;
- SCIP CLI : **0.7.1**.

## Commande d'indexation de référence

À la racine d'un projet Maven Java :

```text
scip-java index
```

Le résultat attendu est :

```text
index.scip
```

Pour Maven, `scip-java` utilise par défaut un build conceptuellement équivalent à :

```text
--batch-mode clean verify -DskipTests
```

Les arguments Maven peuvent être remplacés après `--`.

Exemple :

```text
scip-java index -- --batch-mode -DskipTests package
```

## Installation privilégiée pour M0

La documentation propose plusieurs modes :

- Docker ;
- lancement Java via Coursier ;
- fat jar ;
- dépendance Java directe.

Pour MINOS M0, le **lancement Java / Coursier** est le candidat principal pour Windows et pour les tests reproductibles, car il évite de coupler l'expérience à Docker.

Coordonnée publiée :

```text
com.sourcegraph:scip-java_2.13:0.12.3
```

Commande conceptuelle :

```text
coursier launch com.sourcegraph:scip-java_2.13:0.12.3 -- index
```

Le mode fat jar reste une alternative intéressante pour un fonctionnement local après téléchargement initial.

## Validation de l'index SCIP

Après génération :

```text
scip lint index.scip
scip stats --from index.scip
scip snapshot --from index.scip --to scip-snapshot
```

`scip test` sera utilisé lorsque des fichiers de test SCIP annotés seront disponibles.

## Point de vigilance — versions Java

La documentation `scip-java 0.12.3` liste explicitement comme supportées :

```text
Java 11
Java 17
Java 21
```

Java 25 n'est pas listé dans la matrice publiée.

Pour Java 17 et versions plus récentes documentées, `scip-java` nécessite des exports internes du compilateur `javac`.

Conséquence M0 :

> **le support de projets compilés en Java 25 doit être testé explicitement et ne doit pas être supposé.**

Le premier dépôt réel `FTurleque/ariane-chatbot` utilise Java 17 et constitue donc un cas supporté documenté.

Une fixture séparée `java-25-smoke` doit vérifier :

- lancement de `scip-java` sous l'environnement retenu ;
- build Maven `release=25` ;
- génération effective de `index.scip` ;
- absence d'erreur liée aux APIs internes `javac` ;
- définitions et références minimales.

Un échec sur Java 25 ne remet pas en cause le langage d'implémentation de MINOS : les indexeurs sont des fournisseurs externes et peuvent utiliser leur propre toolchain. Il qualifierait cependant `scip-java` avec une limitation de version Java.

## Support Maven à qualifier

La documentation annonce l'auto-configuration Maven pour Java.

Elle n'annonce pas d'auto-configuration Maven équivalente pour Scala ou Kotlin. Cette distinction confirme que MINOS doit qualifier un fournisseur par :

```text
langage + build + version + capacités
```

et non uniquement par nom de langage.

## Dépôts / fixtures de l'Expérience A

### Fixture contrôlée

```text
fixtures/java/java-simple
```

Elle couvre :

- record ;
- interface ;
- implémentation ;
- constructeur ;
- références cross-file ;
- appels ;
- test.

### Dépôt réel

```text
FTurleque/ariane-chatbot
```

Caractéristiques observées :

- Maven ;
- Java 17 ;
- Quarkus ;
- génération de code ;
- dépendances externes ;
- tests Quarkus.

### Fixture de compatibilité

```text
fixtures/java/java-25-smoke
```

But : qualifier explicitement la compatibilité `scip-java` avec un projet Java 25.

## Sorties attendues

Pour chaque cas :

```text
index.scip
scip lint
scip stats
scip snapshot
ProviderQualityProfile
rapport d'erreurs / limitations
```

## Sources officielles

- https://github.com/sourcegraph/scip-java
- https://sourcegraph.github.io/scip-java/docs/getting-started.html
- https://github.com/scip-code/scip
- https://github.com/scip-code/scip/blob/main/docs/CLI.md
