# Modèle de domaine minimal — MINOS

- Statut : **Validé pour M0**
- Date de validation : **22 juillet 2026**

Ce document définit le modèle de domaine minimal à utiliser pour les expérimentations M0. Il reste indépendant de SCIP, Glean, LSIF, LSP, d'un parser particulier et des langages analysés.

Il pourra évoluer après M0, mais toute évolution incompatible devra être motivée par une limite observée sur les fixtures ou dépôts réels.

---

# 1. Objectifs

Le modèle doit représenter de manière structurée et explicable :

- workspaces et projets ;
- modules ;
- fichiers ;
- symboles ;
- occurrences de symboles ;
- emplacements ;
- relations entre entités ;
- cibles externes ou non résolues ;
- provenance ;
- niveau de résolution ;
- preuves ;
- snapshots d'indexation.

Le modèle ne doit pas tenter d'imiter toutes les particularités de tous les langages.

---

# 2. Principes

## 2.1 Domaine indépendant des fournisseurs

Aucun type SCIP, Glean, Angle, Thrift, LSP, AST ou CPG ne doit apparaître dans le domaine public MINOS.

## 2.2 Distinction entre fait, dérivation et heuristique

```text
FACTUAL
DERIVED
HEURISTIC
```

Une information dérivée ou heuristique conserve sa provenance et ses preuves.

## 2.3 Résolution explicite

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

L'absence de résolution est une information valide et ne doit jamais être masquée.

## 2.4 Distinction symbole / occurrence

Un **symbole** représente une entité sémantique.

Une **occurrence** représente l'apparition localisée d'un symbole dans un fichier.

Exemple :

```text
Symbol
UserService.save(User)

Occurrences
- déclaration dans UserService.java
- appel dans UserResource.java
- appel dans UserServiceTest.java
```

Cette séparation est fondamentale pour `find_usages`.

## 2.5 Identité et continuité

MINOS distingue :

- identité technique dans un snapshot ;
- clé logique déterministe ;
- continuité historique éventuelle entre snapshots.

Un renommage ou déplacement ne doit pas être déclaré identique sans preuve suffisante.

---

# 3. Hiérarchie structurelle

```text
Workspace
   │
   └── Project
         │
         ├── Module
         │    └── SourceFile
         │          ├── Symbol
         │          └── SymbolOccurrence
         │
         └── IndexSnapshot
```

---

# 4. Workspace

Regroupe un ou plusieurs projets analysables ensemble.

```text
Workspace
- id
- name
- rootPath?
- projectIds
- createdAt
- metadata
```

Le workspace prépare notamment :

- multi-dépôts ;
- relations inter-projets ;
- résolution cross-repository future.

---

# 5. Project

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

Le chemin local ne constitue pas à lui seul l'identité du projet.

---

# 6. Module

Représente une unité structurelle ou de build.

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

Un projet mono-module possède simplement un module racine logique ou implicite.

---

# 7. SourceFile

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
- generated
- metadata
```

`fileKind` :

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

`contentHash` est obligatoire pour préparer les snapshots et l'indexation incrémentale.

---

# 8. Symbol

## 8.1 Définition

Un symbole est une entité sémantique adressable ou référencée.

```text
Symbol
- id
- symbolKey
- identityQuality
- projectId
- moduleId?
- declarationFileId?
- parentSymbolId?
- kind
- name
- qualifiedName?
- signature?
- language
- declarationLocation?
- visibility?
- modifiers
- resolutionStatus
- origin
- external
- generated
- providerReferences
- extensions
```

## 8.2 Types communs

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

Pour M0, `PACKAGE` et `NAMESPACE` sont représentés comme des symboles lorsque le fournisseur permet de les adresser sémantiquement.

Cette décision évite d'introduire prématurément une hiérarchie de conteneurs spécifique à chaque langage.

## 8.3 `id`

Identifiant technique interne MINOS dans un snapshot ou store.

Il peut être opaque.

## 8.4 `symbolKey`

Clé logique déterministe candidate :

```text
project + language + kind + qualifiedName + signature
```

Elle doit :

- distinguer les surcharges ;
- être indépendante des IDs SCIP/Glean ;
- être recalculable ;
- rester déterministe à données identiques.

Elle ne garantit pas la continuité après renommage ou déplacement.

Cette continuité relève de la future Git Intelligence.

## 8.5 `identityQuality`

La force de l'identité logique est explicite :

```text
CANONICAL
STRUCTURAL_FALLBACK
PROVIDER_SCOPED_FALLBACK
```

Une identité structurelle peut utiliser le projet, le langage, le kind, le
chemin, le nom, la signature et la déclaration. Une identité scoped au
fournisseur reste exploitable pour la traçabilité, mais ne permet jamais de
réconcilier automatiquement deux fournisseurs. L'identifiant fournisseur brut
n'est ni `symbolKey`, ni `qualifiedName`.

---

# 9. SymbolOccurrence

Une occurrence décrit une apparition localisée d'un symbole.

```text
SymbolOccurrence
- id
- projectId
- moduleId?
- fileId
- symbolRef
- roles
- location
- resolutionStatus
- origin
- providerReferences
- extensions
```

Rôles communs :

```text
DECLARATION
DEFINITION
REFERENCE
IMPORT
TYPE_USAGE
CALL
READ
WRITE
INSTANTIATION
```

Une occurrence peut avoir plusieurs rôles lorsque cela est pertinent.

Cette entité permet de préserver plusieurs usages identiques d'un même symbole à des emplacements différents sans dupliquer le symbole lui-même.

---

# 10. Référence à un symbole

MINOS distingue trois situations.

## 10.1 Symbole local résolu

```text
ResolvedSymbolRef
- symbolId
```

## 10.2 Symbole externe résolu

Un symbole externe connu peut être représenté par un `Symbol` :

```text
external = true
```

avec, lorsque disponible :

- nom qualifié ;
- package/module externe ;
- identité fournisseur ;
- version de dépendance.

## 10.3 Cible non résolue

Une référence non résolue ne doit pas créer un faux `Symbol` résolu.

```text
UnresolvedSymbolRef
- displayName?
- qualifiedNameCandidate?
- language?
- reason?
- providerReferences
```

---

# 11. SymbolLocation

```text
SymbolLocation
- fileId
- startLine
- startColumn?
- endLine
- endColumn?
- positionEncoding
```

Les lignes sont obligatoires lorsque le fournisseur les fournit de façon fiable.

Les colonnes peuvent être absentes.

Lorsqu'elles sont présentes, leur unité est explicite :

```text
UTF8_CODE_UNITS
UTF16_CODE_UNITS
UTF32_CODE_UNITS
UNKNOWN
```

Les lignes MINOS sont en base 1 ; les colonnes restent des offsets base 0 dans
l'unité déclarée. `UNKNOWN` est conservé si le fournisseur ne précise rien.

---

# 12. Références génériques entre entités

Toutes les relations de Code Intelligence ne relient pas nécessairement deux symboles.

Exemples :

```text
SourceFile IMPORTS SourceFile
Module DEPENDS_ON Module
Project DEPENDS_ON Project
Symbol CALLS Symbol
```

MINOS utilise donc conceptuellement :

```text
CodeEntityRef
- entityType
- entityId
```

`entityType` :

```text
WORKSPACE
PROJECT
MODULE
SOURCE_FILE
SYMBOL
```

Une cible non résolue peut remplacer `CodeEntityRef` lorsque nécessaire.

---

# 13. Relationship

```text
Relationship
- id
- source
- target?
- unresolvedTarget?
- kind
- informationType
- location?
- resolutionStatus
- origin
- confidence?
- evidence
- providerReferences
- extensions
```

`source` et `target` utilisent `CodeEntityRef`.

## 13.1 Relations factuelles primitives

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

Une relation primitive correspond à une information observable ou fournie directement avec un niveau de résolution explicite.

## 13.2 Relations dérivées

```text
DEPENDS_ON
INJECTS
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Une relation dérivée doit conserver ses preuves.

## 13.3 `USES`

`USES` n'est pas une relation primitive lorsque la relation précise est connue.

Elle peut devenir une vue agrégée de :

```text
REFERENCES
CALLS
IMPORTS
INSTANTIATES
READS
WRITES
...
```

---

# 14. Origin

```text
Origin
- providerId
- providerType
- providerVersion?
- indexRunId?
- sourceType
```

`sourceType` :

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

La provenance permet :

- audit ;
- comparaison de fournisseurs ;
- explicabilité ;
- reproduction des résultats.

## 14.1 ProviderReference

```text
ProviderReference
- providerId
- externalId
```

Cette référence opaque conserve l'identité publiée par un fournisseur sans la
faire fuiter dans les identités ou contrats métier. Un symbole, une occurrence,
une cible non résolue ou une preuve peut en porter plusieurs.

---

# 15. Evidence

```text
Evidence
- type
- description?
- source?
- target?
- location?
- providerReference?
- weight?
- metadata
```

Types initiaux :

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

Les preuves restent structurées ; une simple chaîne de texte n'est pas suffisante comme unique modèle.

---

# 16. IndexSnapshot

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
- occurrenceCount
- relationshipCount
- metadata
```

Le snapshot sert à :

- identifier les données ayant produit une réponse ;
- préparer l'incrémental ;
- diagnostiquer les différences ;
- assurer la reproductibilité ;
- empêcher qu'un index partiel remplace silencieusement un index sain.

Sa promotion est atomique : un run fournisseur réussi, un artefact final
lisible et une ingestion MINOS réussie sont tous nécessaires. Sinon le
snapshot actif précédent est conservé. Les shards et journaux d'un run échoué
restent des diagnostics et ne deviennent pas des faits résolus du snapshot
actif. ADR-0006 fixe cet invariant indépendamment du fournisseur et du backend.

---

# 17. Extensions spécifiques

Le champ conceptuel `extensions` peut contenir des données spécifiques à un langage ou fournisseur.

Règles :

1. les clés doivent être namespacées ;
2. aucune extension n'est requise pour comprendre les cas d'usage de base ;
3. un consommateur générique doit pouvoir ignorer une extension inconnue ;
4. une donnée qui devient transversale à plusieurs langages peut être promue dans le modèle commun via ADR.

Exemple conceptuel :

```text
extensions["java.annotationRetention"]
extensions["typescript.exportKind"]
```

---

# 18. Version du modèle

Le modèle possède une `schemaVersion` indépendante de la version des fournisseurs.

Les évolutions doivent suivre trois catégories :

```text
compatible
migration_required
breaking
```

Les snapshots conservent la version de schéma utilisée.

---

# 19. Résultats de requêtes

Les interfaces publiques ne doivent pas exposer directement les entités de persistance.

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

Ils peuvent inclure :

- symbole ;
- occurrences pertinentes ;
- emplacement ;
- relations ;
- provenance ;
- statut de résolution ;
- confiance ;
- preuves ;
- plage de code pertinente.

---

# 20. Décisions C0 prises

Les questions principales sont tranchées pour M0 :

1. `PACKAGE` et `NAMESPACE` sont des `Symbol` adressables lorsque pertinent.
2. Les symboles externes résolus sont des `Symbol external=true`.
3. Les cibles réellement non résolues utilisent `UnresolvedSymbolRef`.
4. `Symbol` et `SymbolOccurrence` sont distincts.
5. Une `Relationship` relie des `CodeEntityRef`, pas uniquement des symboles.
6. Les relations inter-dépôts utiliseront les mêmes références avec `Project` / `Workspace`.
7. Les données spécifiques utilisent des extensions namespacées.
8. Le modèle est versionné via `schemaVersion`.
9. `symbolKey` est déterministe mais ne promet pas la continuité historique après rename/move.
10. La continuité historique est différée à Git Intelligence.
11. La qualité de l'identité et l'encodage des positions sont explicites.
12. Les identifiants fournisseur restent dans `ProviderReference`.
13. La promotion d'un snapshot fournisseur est atomique.

---

# 21. Validation M0

Le modèle est considéré suffisamment défini pour commencer M0 si les fixtures démontrent que :

- les surcharges sont distinguables ;
- plusieurs occurrences d'un même symbole sont conservées ;
- les relations fichier/module/symbole sont représentables ;
- les symboles externes sont représentables ;
- les cibles non résolues sont représentables ;
- aucun type SCIP/Glean ne traverse le domaine ;
- la provenance et les preuves sont conservées ;
- les résultats Java et TypeScript utilisent le même modèle commun.

Si une fixture M0 invalide une hypothèse, le modèle doit être corrigé avant M1 et la décision documentée.
