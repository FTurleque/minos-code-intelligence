# Scripts M0 — Expérience A / `scip-java`

Ces scripts exécutent la chaîne de qualification `scip-java` sur un projet Maven Java puis conservent les artefacts nécessaires aux mesures M0.

## Versions de référence

```text
scip-java                 0.13.1
SCIP CLI                  0.7.1
```

Les bindings Java SCIP utilisés par MINOS sont versionnés séparément dans le build principal :

```text
org.scip-code:scip-java-bindings:0.9.0
```

## Prérequis

Les scripts n'installent volontairement aucun outil de manière implicite.

Ils exigent :

- un JDK adapté au projet analysé ;
- Coursier, accessible par la commande `cs` ;
- SCIP CLI, accessible par la commande `scip`.

Les noms de commandes sont configurables par paramètres PowerShell ou variables d'environnement Bash.

## Windows / PowerShell

Depuis la racine de MINOS :

```powershell
.\scripts\m0\run-scip-java.ps1 `
    -ProjectPath .\fixtures\java\java-simple
```

Fixture Java 25 :

```powershell
.\scripts\m0\run-scip-java.ps1 `
    -ProjectPath .\fixtures\java\java-25-smoke
```

Dépôt réel Ariane, exemple si les dépôts sont voisins :

```powershell
.\scripts\m0\run-scip-java.ps1 `
    -ProjectPath ..\ariane-chatbot
```

Paramètres facultatifs :

```powershell
-OutputDirectory <chemin>
-ScipJavaVersion 0.13.1
-CoursierCommand cs
-ScipCommand scip
```

## Bash / Git Bash / Linux / macOS

Le fichier peut être lancé explicitement avec Bash même si le bit exécutable n'est pas préservé par une copie Git :

```bash
bash scripts/m0/run-scip-java.sh fixtures/java/java-simple
```

Fixture Java 25 :

```bash
bash scripts/m0/run-scip-java.sh fixtures/java/java-25-smoke
```

Dépôt réel Ariane :

```bash
bash scripts/m0/run-scip-java.sh ../ariane-chatbot
```

Variables facultatives :

```bash
SCIP_JAVA_VERSION=0.13.1
COURSIER_COMMAND=cs
SCIP_COMMAND=scip
```

## Chaîne exécutée

Le runner lance conceptuellement :

```text
Coursier
   │
   ▼
org.scip-code:scip-java:0.13.1
   │
   ▼
scip-java index
   │
   ▼
index.scip
   │
   ├── scip lint
   ├── scip stats
   └── scip snapshot
```

Les commandes SCIP correspondent à la CLI officielle :

```text
scip lint <index.scip>
scip stats --from <index.scip>
scip snapshot --from <index.scip> --to <directory>
```

## Sorties

Par défaut :

```text
<projet>/.minos-m0/scip-java/
```

avec :

```text
index.scip
lint.txt
stats.txt
environment.txt
snapshot/
```

Le dossier `.minos-m0/` est ignoré par Git dans le dépôt MINOS.

## Rôle des artefacts

### `index.scip`

Entrée binaire utilisée par la baseline `ScipIndexReader` / `ScipIngestionAdapter`.

### `lint.txt`

Anomalies structurelles signalées par SCIP CLI.

### `stats.txt`

Statistiques de l'index utilisées pour qualifier le fournisseur et comparer les runs.

### `snapshot/`

Vue humaine des occurrences et symboles destinée à la vérification contre `expected.json`.

### `environment.txt`

Conserve au minimum :

- date ;
- chemin du projet ;
- version `scip-java` ;
- version Java ;
- version SCIP CLI.

## Ordre d'exécution de l'Expérience A

### A1 — `java-simple`

Objectif : comparer précisément le résultat à la vérité terrain contrôlée.

```text
fixtures/java/java-simple/expected.json
```

### A2 — `java-25-smoke`

Objectif : confirmer que notre mode d'exécution fonctionne réellement sur un projet Maven `release=25`.

```text
fixtures/java/java-25-smoke/expected.json
```

### A3 — `FTurleque/ariane-chatbot`

Objectif : tester un dépôt Maven Java réel avec :

- Java 17 ;
- Quarkus ;
- génération de code ;
- dépendances externes ;
- code applicatif réel.

## Règle d'analyse

Un run réussi techniquement ne suffit pas à qualifier le fournisseur.

Les résultats doivent être confrontés à :

- la vérité terrain ;
- `docs/METRIQUES_VALIDATION.md` ;
- les capacités déclarées dans `IndexerCapabilities` ;
- les limitations observées.

Le résultat final doit alimenter un `ProviderQualityProfile` et non une simple conclusion « fonctionne / ne fonctionne pas ».
