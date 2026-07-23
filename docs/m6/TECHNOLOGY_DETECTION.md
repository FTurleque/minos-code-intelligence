# M6.6 — Détection factuelle des technologies

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE EN ATTENTE**

Suivi : issue #13.

Base : M6.5 a livré le classement relatif directionnel des composants centraux.

## Objectif

Exposer les technologies réellement observées par la découverte de projet sans
transformer des conventions, noms de fichiers non qualifiés ou dépendances non
analysées en affirmations architecturales.

M6.6 réutilise exclusivement `ProjectDiscovery` comme source factuelle.

## Périmètre actuel

Deux catégories seulement sont qualifiées :

```text
LANGUAGE
BUILD_SYSTEM
```

Valeurs actuellement produites par M1 :

```text
LANGUAGE     JAVA
LANGUAGE     TYPESCRIPT
BUILD_SYSTEM MAVEN
BUILD_SYSTEM NPM
```

Aucun autre nom n'est créé par M6.6.

## Contrats

- `ArchitectureTechnologyCategory` ;
- `ArchitectureTechnology` ;
- `ArchitectureTechnologyReport` ;
- `ArchitectureTechnologyService` ;
- `ProjectArchitectureQuery.getArchitectureTechnologies(...)`.

Chaque `ArchitectureTechnology` expose :

- un ID canonique stable, par exemple `technology:language:java` ;
- le nom observé ;
- sa catégorie ;
- les IDs des modules où elle est observée ;
- une nature `FACTUAL` ;
- des preuves d'observation.

Le rapport projet/snapshot est `DERIVED` car il agrège et déduplique ces faits.

## Source des faits

### Langages

Un langage est repris depuis les `SourceRoot` factuels de `ProjectDiscovery`.
La preuve conserve le type de racine (`SOURCE` ou `TEST`) et son chemin relatif.

Exemple :

```text
Language JAVA observed in source root 'api/src/main/java'
```

### Systèmes de build

Un système de build est repris depuis `DiscoveredModule.buildSystems()`.
M6.6 ne redétecte pas les fichiers lui-même et ne crée pas une seconde logique de
découverte.

Dans M1 :

- `MAVEN` est observé lorsqu'un `pom.xml` visible qualifie le module ;
- `NPM` est observé lorsqu'un `package-lock.json` visible qualifie le module.

La présence seule d'un `package.json` suffit à découvrir un module Node, mais ne
suffit pas à affirmer `NPM` pour ce module.

## Agrégation

Les observations identiques sont dédupliquées au niveau projet. Une technologie
peut donc référencer plusieurs modules.

Ordre de restitution déterministe :

```text
LANGUAGE par nom
puis BUILD_SYSTEM par nom
```

Les IDs de modules sont également triés.

## Replay réel TypeScript

La fixture versionnée :

```text
fixtures/typescript/typescript-modules
```

est redécouverte réellement pendant `ArchitectureRealFixtureMeasurementTest`, en
plus du replay SCIP déjà utilisé par M6.3 et M6.5.

La porte M6.6 exige :

```text
technologies = [TYPESCRIPT, NPM]

TYPESCRIPT : packages/api + packages/app
NPM        : module racine uniquement
```

Le rattachement npm à la racine est important : les sous-modules disposent de
`package.json`, mais le verrou npm qualifié par M1 est le `package-lock.json`
racine. M6.6 ne propage donc pas artificiellement `NPM` aux workspaces enfants.

La sortie Maven doit contenir :

```text
M6.6 typescript-modules technologies: ...
```

## Chaîne file-backed

`LocalProjectArchitectureQuery` suit le même contexte que les vues précédentes :

```text
registre projet
 -> snapshot actif
 -> ProjectDiscovery
 -> ArchitectureOverview
 -> ArchitectureTechnologyReport
```

Le rapport reste ainsi associé au même projet et au même snapshot que les autres
vues M6.

## Hors périmètre

M6.6 ne qualifie pas encore :

- frameworks (`Spring`, `React`, etc.) ;
- runtimes (`JVM`, `Node.js`) ;
- versions de langage ou de runtime ;
- bibliothèques (`JUnit`, `Jackson`, etc.) ;
- bases de données ;
- outils de test, lint, bundling ou CI ;
- rôles architecturaux.

Ces informations nécessiteraient une analyse explicite des manifestes,
dépendances ou configurations et ne doivent pas être déduites par simple nommage.

## Porte locale

```powershell
.\mvnw.cmd clean verify
```

La porte doit confirmer :

- compilation Java 24 ;
- suite complète verte ;
- tests unitaires de détection et déduplication ;
- chaîne file-backed ;
- replay réel TypeScript avec la ligne `M6.6 typescript-modules technologies` ;
- aucune régression M2 à M6.5.

## Suite

Après M6.6, M6 doit encore assembler une vue métier compacte réunissant topologie,
dépendances, concentration, centralité et technologies, puis fournir
`get_module_context` avant la validation finale du jalon.
