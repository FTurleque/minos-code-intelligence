# M0 — Préparation de l'expérimentation `scip-java`

Date : **22 juillet 2026**

Statut : **Préparation de l'Expérience A**

## Versions vérifiées

Les composants sont versionnés indépendamment :

```text
SCIP CLI                         0.7.1
Bindings Java SCIP               0.9.0
scip-java                        0.13.1
```

Cette distinction est importante : la version de la CLI SCIP n'est pas la version du protocole/bindings ni celle de l'indexeur JVM.

## Commande d'indexation de référence

À la racine d'un projet Maven Java :

```text
scip-java index
```

Le résultat attendu est :

```text
index.scip
```

Pour Maven, `scip-java` utilise par défaut :

```text
--batch-mode clean verify -DskipTests
```

Les arguments Maven peuvent être remplacés après `--`.

Exemple :

```text
scip-java index -- --batch-mode -DskipTests package
```

## Installation privilégiée pour M0

La documentation officielle propose notamment :

- image Docker `ghcr.io/scip-code/scip-java` ;
- lancement Java via Coursier ;
- fat jar ;
- utilisation comme bibliothèque Java.

Pour MINOS M0, le **lancement Java / Coursier** est le candidat principal pour Windows et les tests reproductibles, car il évite de rendre Docker obligatoire.

Coordonnée actuelle :

```text
org.scip-code:scip-java:0.13.1
```

Commande conceptuelle :

```text
coursier launch org.scip-code:scip-java:0.13.1 -- index
```

Le mode fat jar reste une alternative intéressante après téléchargement initial.

## Bindings Java du protocole SCIP

MINOS ne doit pas dépendre des anciens bindings propres à une implémentation d'indexeur.

Le protocole SCIP publie des bindings Java officiels :

```text
org.scip-code:scip-java-bindings:0.9.0
```

Ils sont générés à partir du schéma SCIP et utilisent le package Java :

```text
org.scip_code.scip
```

M0 utilise ces bindings uniquement dans :

```text
io.github.fturleque.minos.adapter.scip
```

Ils ne doivent jamais apparaître dans :

```text
domain
store
query
CLI/MCP/API publiques
```

Cette frontière est vérifiée par test d'architecture.

## Validation de l'index SCIP

Après génération :

```text
scip lint index.scip
scip stats --from index.scip
scip snapshot --from index.scip --to scip-snapshot
```

`scip test` sera utilisé lorsque des fichiers de test SCIP annotés seront disponibles.

## JDK de référence MINOS

ADR-0005 aligne MINOS sur :

```text
Java 24
```

La règle M0 est explicite :

> **MINOS n'installe pas un JDK supplémentaire uniquement pour satisfaire un fournisseur d'indexation.**

La documentation actuelle de `scip-java` indique deux informations distinctes :

1. son lanceur Java fonctionne avec un **JDK 17 ou supérieur** ;
2. sa matrice de versions Java explicitement ciblées liste actuellement :

```text
Java 17  ✅
Java 21  ✅
Java 25  ✅
```

Java 24 n'est donc pas explicitement ciblé dans cette matrice, même si le lanceur accepte un JDK 17+.

Cette absence doit être **mesurée**, pas contournée.

La fixture de compatibilité devient :

```text
fixtures/java/java-24-smoke
```

Elle doit vérifier en pratique que :

- `scip-java` se lance avec le JDK 24 du poste de développement ;
- un projet Maven `release=24` produit effectivement `index.scip` ;
- les définitions et références minimales sont correctes ;
- aucune incompatibilité avec les APIs internes `javac` n'apparaît ;
- le résultat peut être ingéré par la baseline MINOS.

Si cette expérience échoue, le verdict portera sur **la compatibilité de `scip-java` avec Java 24**, pas sur la version Java de MINOS.

## Support Maven à qualifier

La documentation annonce l'auto-configuration suivante :

| Langage | Maven |
|---|---|
| Java | supporté |
| Kotlin | non supporté automatiquement |

Elle confirme que MINOS doit qualifier un fournisseur par :

```text
langage + système de build + version + capacités
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

### Fixture de compatibilité Java 24

```text
fixtures/java/java-24-smoke
```

But : qualifier explicitement `scip-java` dans l'environnement Java 24 réellement utilisé par MINOS.

## Première stratégie d'ingestion MINOS

```text
index.scip
    │
    ▼
org.scip-code:scip-java-bindings:0.9.0
    │
    ▼
ScipIndexReader
    │
    ▼
ScipIngestionAdapter
    │
    ├── Symbol
    ├── SymbolOccurrence
    └── Relationship
         │
         ▼
CodeKnowledgeStore
```

Les ranges SCIP typés (`SingleLineRange`, `MultiLineRange`) doivent être privilégiés. L'ancien tableau `range` ne sert que de repli de compatibilité.

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

- https://github.com/scip-code/scip-java
- https://github.com/scip-code/scip-java/releases/tag/v0.13.1
- https://github.com/scip-code/scip-java/blob/main/docs/getting-started.md
- https://github.com/scip-code/scip
- https://github.com/scip-code/scip/blob/main/docs/CLI.md
- https://github.com/scip-code/scip/tree/main/bindings/java
