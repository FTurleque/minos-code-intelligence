# Modèle de domaine minimal — MINOS

Statut : **Proposition C0 — à valider**

Ce document formalise le modèle de domaine minimal de MINOS avant toute implémentation. Il doit rester indépendant de SCIP, Glean, LSIF, LSP, d'un parser particulier ou d'un langage de programmation précis.

---

# 1. Objectifs

Le modèle doit permettre de représenter de manière stable et explicable :

- les projets et workspaces ;
- les modules ;
- les fichiers ;
- les symboles ;
- leurs emplacements ;
- leurs relations ;
- la provenance des faits ;
- le niveau de résolution ;
- les preuves ;
- les snapshots d'indexation.

Le modèle ne doit pas tenter de représenter toutes les particularités de tous les langages. Les concepts spécifiques à un fournisseur ou à un langage doivent rester extensibles via des métadonnées ou des extensions dédiées.

---

# 2. Principes

## 2.1 Domaine indépendant des fournisseurs

Aucun type SCIP, Glean, Angle, Thrift, LSP, AST ou CPG ne doit apparaître dans le domaine public MINOS.

## 2.2 Distinction fait / dérivation

Une information peut être :

```text
FACTUAL
DERIVED
HEURISTIC
```

Une information dérivée ou heuristique doit conserver sa provenance et ses preuves.

## 2.3 Résolution explicite

Statuts conceptuels :

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

L'absence de résolution n'est pas une erreur à masquer.

## 2.4 Identité et continuité

MINOS doit distinguer :

- l'identité d'un objet dans un snapshot d'index ;
- sa clé logique déterministe ;
- sa continuité éventuelle entre plusieurs snapshots.

La continuité à travers un renommage ou un déplacement ne peut pas toujours être déterminée de manière certaine et ne doit pas être simulée artificiellement.

---

# 3. Entités principales

## 3.1 Workspace

Regroupe un ou plusieurs projets pouvant être analysés ensemble.

Attributs candidats :

```text
Workspace
- id
- name
- rootPath
- projectIds
- createdAt
- metadata
```

Le workspace devient particulièrement utile pour les relations inter-dépôts futures.

## 3.2 Project

Représente un dépôt ou projet analysable par MINOS.

```text
Project
- id
- workspaceId?
- name
- rootPath
- detectedLanguages
- detectedBuildSystems
- detectedTechnologies
- currentSnapshotId?
- indexStatus
- createdAt
- updatedAt
- metadata
```

Le chemin local ne doit pas être utilisé comme seule identité métier du projet.

## 3.3 Module

Représente une unité structurelle ou de build d'un projet.

```text
Module
- id
- projectId
- parentModuleId?
- name
- path
- buildSystem?
- sourceRoots
- testRoots
- metadata
```

Un projet peut être mono-module.

## 3.4 SourceFile

Représente un fichier connu du projet.

```text
SourceFile
- id
- projectId
- moduleId?
- relativePath
- language?
- fileKind
- contentHash
- size
- lastModifiedAt?
- metadata
```

`fileKind` doit pouvoir distinguer au minimum :

```text
SOURCE
TEST
RESOURCE
CONFIGURATION
BUILD
DOCUMENTATION
GENERATED
OTHER
```

`contentHash` est requis afin de préparer l'indexation incrémentale.

---

# 4. Symbole

## 4.1 Définition

Un symbole est une déclaration adressable ou référencée dans le code.

Exemples : classe, interface, fonction, méthode, constructeur, champ, propriété, enum, annotation.

## 4.2 Modèle candidat

```text
Symbol
- id
- symbolKey
- projectId
- moduleId?
- fileId?
- parentSymbolId?
- kind
- name
- qualifiedName?
- signature?
- language
- location?
- visibility?
- modifiers
- resolutionStatus
- origin
- external
- generated
- providerReferences
- metadata
```

## 4.3 Types de symboles communs

```text
CLASS
INTERFACE
RECORD
STRUCT
ENUM
ANNOTATION
TRAIT
METHOD
CONSTRUCTOR
FUNCTION
FIELD
PROPERTY
VARIABLE
TYPE_ALIAS
NAMESPACE
PACKAGE
OTHER
```

Le modèle doit permettre des types spécifiques sans forcer toutes les langues dans une taxonomie trop pauvre.

## 4.4 `id` et `symbolKey`

Deux concepts sont séparés.

### `id`

Identifiant technique interne MINOS d'un symbole connu dans un index.

Il peut être opaque.

### `symbolKey`

Clé logique déterministe utilisée pour retrouver une déclaration équivalente lorsque les informations structurelles n'ont pas changé.

Composition candidate :

```text
project + language + kind + qualifiedName + signature
```

Cette clé :

- distingue les méthodes surchargées ;
- ne dépend pas d'un ID Glean ou SCIP ;
- peut être recalculée ;
- ne garantit pas la continuité après renommage ou déplacement.

La continuité historique avancée pourra être ajoutée plus tard avec Git Intelligence.

## 4.5 Symboles externes

MINOS doit pouvoir représenter un symbole référencé mais non déclaré dans le dépôt local.

```text
external = true
```

Un symbole externe peut disposer d'un nom qualifié ou d'une identité fournisseur même si son fichier source n'est pas connu.

## 4.6 Symboles non résolus

Une référence non résolue ne doit pas provoquer la création d'un faux symbole résolu.

Elle peut être représentée par :

- une cible absente ;
- une `UnresolvedTarget` ;
- ou un symbole marqué `UNRESOLVED`.

Le choix précis sera arrêté lors de la validation du modèle.

---

# 5. Emplacement

```text
SymbolLocation
- fileId
- startLine
- startColumn
- endLine
- endColumn
```

Les colonnes peuvent être absentes lorsqu'un fournisseur ne les fournit pas.

MINOS doit conserver autant que possible l'emplacement de la déclaration et l'emplacement d'une relation ou occurrence.

---

# 6. Relation

## 6.1 Modèle candidat

```text
Relationship
- id
- sourceSymbolId
- targetSymbolId?
- unresolvedTarget?
- kind
- location?
- resolutionStatus
- origin
- confidence?
- evidence
- providerReferences
- metadata
```

## 6.2 Relations factuelles primitives

```text
DECLARES
CONTAINS
IMPORTS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
RETURNS
ACCEPTS
READS
WRITES
INSTANTIATES
```

Une relation primitive doit correspondre à une information suffisamment précise et observable.

## 6.3 Relations dérivées ou sémantiques

```text
DEPENDS_ON
INJECTS
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Ces relations doivent conserver leurs preuves.

## 6.4 `USES`

`USES` ne doit pas devenir une relation primitive générique lorsque MINOS peut représenter la relation réelle : `CALLS`, `REFERENCES`, `IMPORTS`, `INSTANTIATES`, etc.

`USES` pourra éventuellement être une vue agrégée de plusieurs relations.

---

# 7. Provenance

Chaque fait important doit pouvoir indiquer son origine.

```text
Origin
- providerId
- providerType
- providerVersion?
- indexRunId?
- sourceType
```

Exemples de `sourceType` :

```text
SCIP
GLEAN
LSP
COMPILER
AST
CPG
DERIVED_BY_MINOS
HEURISTIC_BY_MINOS
```

La provenance doit permettre d'expliquer d'où vient une information et de diagnostiquer les divergences entre fournisseurs.

---

# 8. Preuve

```text
Evidence
- type
- description
- sourceSymbolId?
- targetSymbolId?
- location?
- providerReference?
- weight?
- metadata
```

Exemples :

```text
DIRECT_REFERENCE
DIRECT_CALL
IMPORT
NAMING_CONVENTION
PACKAGE_PROXIMITY
TYPE_RELATIONSHIP
TEST_LOCATION
DERIVATION_PATH
```

Les preuves ne doivent pas être limitées à des chaînes de texte non structurées.

---

# 9. Snapshot d'indexation

```text
IndexSnapshot
- id
- projectId
- createdAt
- status
- schemaVersion
- minosVersion?
- projectFingerprint
- buildFingerprint?
- providerConfigurations
- sourceFileCount
- symbolCount
- relationshipCount
- metadata
```

Le snapshot sert à :

- savoir avec quelles données une réponse a été produite ;
- préparer l'indexation incrémentale ;
- diagnostiquer les différences entre indexations ;
- assurer la reproductibilité des mesures.

---

# 10. Résultats de requêtes

Les résultats publics ne doivent pas exposer directement les entités de persistence.

DTO conceptuels :

```text
SymbolResult
UsageResult
RelationshipResult
DependencyResult
RelatedTestResult
ImpactResult
ArchitectureOverview
```

Ils doivent pouvoir inclure :

- symbole ;
- emplacement ;
- relation ;
- provenance ;
- statut de résolution ;
- niveau de confiance ;
- preuves ;
- plage de code pertinente.

---

# 11. Questions encore ouvertes

1. `PACKAGE` et `NAMESPACE` doivent-ils être des `Symbol` ou des conteneurs distincts ?
2. Comment représenter précisément les symboles externes non présents dans l'index local ?
3. Quelle forme exacte doit prendre `symbolKey` ?
4. Faut-il distinguer `Declaration`, `Symbol` et `Occurrence` dans le domaine public ?
5. Une relation doit-elle toujours cibler un symbole, ou peut-elle cibler un module/fichier/package ?
6. Comment représenter les relations inter-dépôts ?
7. Quelles métadonnées spécifiques à un langage peuvent être normalisées sans surcharger le cœur ?
8. Comment versionner le modèle sans casser les consommateurs ?

---

# 12. Critères de validation C0

Le modèle sera considéré suffisamment défini pour M0 lorsque :

- il permet de représenter les fixtures prévues ;
- les méthodes surchargées sont distinguables ;
- les symboles externes et non résolus sont représentables ;
- aucune dépendance SCIP/Glean n'apparaît dans les contrats ;
- la provenance et les preuves sont représentables ;
- les relations factuelles et dérivées sont séparées ;
- un second langage peut être représenté sans modification structurelle du cœur.