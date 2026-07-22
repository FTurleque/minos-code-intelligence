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

Le protocole SCIP publie désormais des bindings Java officiels :

```text
org.scip-code:scip-java-bindings:0.9.0
```

Ils sont générés à partir du schéma SCIP et utilisent le package Java :

```text
org.scip_code.scip
```

M0 utilisera ces bindings uniquement dans :

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

Cette frontière sera vérifiée par test d'architecture ou test de dépendances avant fusion de la baseline.

## Validation de l'index SCIP

Après génération :

```text
scip lint index.scip
scip stats --from index.scip
scip snapshot --from index.scip --to scip-snapshot
```

`scip test` sera utilisé lorsque des fichiers de test SCIP annotés seront disponibles.

## Versions Java supportées

La documentation courante de `scip-java` annonce :

```text
Java 17  ✅
Java 21  ✅
Java 25  ✅
```

Java 17, 21 et 25 nécessitent des `--add-exports` sur des APIs internes `javac`, gérés par l'intégration de l'indexeur.

La fixture :

```text
fixtures/java/java-25-smoke
```

reste utile, mais son objectif n'est plus de tester une version non documentée. Elle doit vérifier en pratique que :

- notre mode d'installation/lancement de `scip-java` fonctionne avec JDK 25 ;
- un projet Maven `release=25` produit effectivement `index.scip` ;
- les définitions et références minimales sont correctes ;
- aucune régression liée aux exports internes `javac` n'apparaît dans notre environnement M0.

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

### Fixture de compatibilité Java 25

```text
fixtures/java/java-25-smoke
```

But : prouver la compatibilité effective dans l'environnement M0 malgré le support officiellement annoncé.

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
