# M1 — Baseline de découverte des projets

Statut : **premier incrément — validation locale requise**

Date : **22 juillet 2026**

Suivi : issue #6.

## Objectif

Le premier incrément M1 établit une découverte locale déterministe avant toute orchestration d'indexeur.

Il répond uniquement à la question :

> Quels faits structurels MINOS peut-il observer sur un projet local sans exécuter SCIP, Glean ou un autre fournisseur ?

## Contrats

`ProjectDiscovery` décrit :

- la racine locale observée ;
- un nom d'affichage ;
- les langages détectés ;
- les systèmes de build détectés ;
- les modules observés ;
- les racines source/test par module.

Il ne fournit volontairement **aucun identifiant métier de projet**. Le modèle de domaine M0 précise que le chemin local ne suffit pas à établir l'identité d'un projet ; cette responsabilité appartiendra au registre local M1.

## Détection initiale

Langages :

- Java via les racines Maven conventionnelles `src/main/java` et `src/test/java` ;
- TypeScript via la présence réelle de fichiers `.ts` / `.tsx` sous `src`, `test` ou `tests`.

Systèmes de build :

- Maven via `pom.xml` ;
- npm via `package.json`.

Modules :

- toute racine contenant un marqueur de build reconnu est conservée ;
- un projet sans marqueur obtient un module racine implicite ;
- les chemins de module et de source sont toujours relatifs à la racine du projet ;
- les résultats sont triés afin de rester déterministes entre exécutions.

## Exclusions techniques initiales

La traversée ignore les répertoires de métadonnées, dépendances et sorties les plus évidents :

```text
.git
.idea
.minos-m0
node_modules
target
dist
out
```

Cette liste n'est **pas** la stratégie finale d'ignore de M1. La prise en charge de `.gitignore` et `.minosignore` reste un incrément séparé et doit être testée explicitement.

## Fixtures couvertes

Les tests utilisent les fixtures M0 existantes :

- `fixtures/java/java-multi-module` ;
- `fixtures/typescript/typescript-modules` ;
- une fixture temporaire vérifie que `node_modules` ne devient pas un module MINOS.

Attendus structurants :

```text
java-multi-module
  build     MAVEN
  language  JAVA
  modules   root, api, app

TypeScript modules
  build     NPM
  language  TYPESCRIPT
  modules   root, packages/api, packages/app
```

## Frontière fournisseur

Le test d'architecture M0 est étendu aux packages `discovery` et `orchestration`.

Aucun type SCIP, Protobuf ou Glean ne peut donc être introduit dans ces contrats sans faire échouer la suite de tests.

## Hors périmètre de cet incrément

- registre persistant de projets ;
- identité stable de projet/workspace ;
- parsing de `.gitignore` / `.minosignore` ;
- Gradle, pnpm, yarn ou autres systèmes de build ;
- `IndexerRegistry` ;
- négociation de capacités ;
- exécution d'indexeurs ;
- cycle de vie et état d'index.

Ces éléments restent dans M1 et seront ajoutés par incréments après validation de cette baseline.

## Validation locale attendue

Depuis la branche M1 :

```powershell
.\mvnw.cmd clean verify
```

La PR M1 doit rester en Draft tant que cette validation n'a pas été obtenue sur son head courant.
