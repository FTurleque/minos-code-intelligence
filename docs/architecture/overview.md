# Vue d'ensemble de l'architecture — MINOS

Statut : **Proposition — à valider pendant C0**

Ce document décrit une architecture candidate. Il ne constitue pas encore une décision définitive.

La source de vérité fonctionnelle reste le [`CAHIER_DES_CHARGES.md`](../CAHIER_DES_CHARGES.md).

---

## 1. Finalité

MINOS est la couche de **Code Intelligence** de l'écosystème.

Sa responsabilité est de transformer un ou plusieurs dépôts logiciels en une représentation structurée, explicable et interrogeable du code.

MINOS ne décide pas quelles informations doivent être injectées dans un prompt IA. Cette responsabilité appartient à NEXUS.

```text
CODEBASE / WORKSPACE
        │
        ▼
      MINOS
 Code Intelligence
        │
        ▼
      NEXUS
Context Intelligence
        │
        ▼
 AGENT / LLM / IDE
```

---

## 2. Objectifs architecturaux

MINOS doit être :

- agnostique du langage ;
- agnostique de l'indexeur ;
- agnostique du backend de stockage à la frontière du domaine ;
- local-first ;
- indépendant de tout LLM ou fournisseur IA ;
- capable de réponses déterministes lorsque les preuves le permettent ;
- explicite sur l'incertitude ;
- optimisé pour des réponses compactes consommables par machine ;
- extensible vers les workspaces multi-dépôts.

---

## 3. Architecture générale candidate

```text
Dépôts / Workspaces
          │
          ▼
Découverte du projet et des langages
          │
          ├── structure
          ├── langages
          ├── systèmes de build
          └── racines de sources/tests
          │
          ▼
Orchestrateur d'indexation
          │
          ▼
Registre des fournisseurs
          │
    ┌─────┼───────────────────────────────┐
    ▼     ▼                               ▼
   SCIP  Indexeurs Glean natifs     Autres fournisseurs
    │                              AST / LSP / LSIF / CPG
    └─────────────────┬───────────────────┘
                      ▼
          Ingestion Code Intelligence
                      │
                      ▼
           Modèle normalisé MINOS
                      │
                      ▼
              CodeKnowledgeStore
                      │
           ┌──────────┴──────────┐
           ▼                     ▼
        Adaptateur            Adaptateur
          Glean                 futur
       privilégié
                      │
                      ▼
        Couche d'intelligence MINOS
                      │
           ┌──────────┼──────────┐
           ▼          ▼          ▼
         Graphe     Analyse    Recherche
                      │
                      ▼
          Services de requêtes MINOS
                      │
         ┌────────────┼────────────┐
         ▼            ▼            ▼
      Symboles     Relations   Vues compactes
      Usages       Dépendances Contexte
      Appels       Tests       Preuves
      Impact       Architecture Confiance
                      │
                      ▼
              Couche d'exposition
              ┌───────┼───────┐
              ▼       ▼       ▼
             CLI     MCP     API
                      │
                      ▼
                    NEXUS
```

---

## 4. Séparation des responsabilités

### 4.1 Découverte du projet

Responsabilités :

- racines de projet ;
- modules ;
- systèmes de build ;
- langages ;
- racines sources/tests ;
- chemins ignorés ;
- fournisseurs candidats.

Cette couche ne réalise pas l'analyse sémantique du code.

### 4.2 Registre des fournisseurs

Le registre maintient les fournisseurs d'indexation disponibles.

La sélection doit être fondée sur les capacités réellement offertes.

Exemples de capacités :

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
TYPE_RELATIONSHIPS
CALL_RELATIONSHIPS
CROSS_FILE
CROSS_MODULE
CROSS_REPOSITORY
CONTROL_FLOW
DATA_FLOW
```

Un fournisseur peut être excellent sur un langage et limité sur un autre. MINOS ne doit pas supposer une couverture uniforme.

### 4.3 Ingestion Code Intelligence

Cette couche transforme les données propres aux fournisseurs en concepts MINOS.

Entrées possibles :

- SCIP ;
- faits natifs Glean ;
- LSIF ;
- sortie d'un serveur de langage ;
- API de compilateur ;
- AST ;
- Code Property Graph ;
- analyseur spécialisé futur.

Aucune représentation externe ne doit fuiter directement dans le domaine MINOS.

### 4.4 Modèle normalisé MINOS

Concepts candidats :

```text
Project
Workspace
Module
SourceFile
Symbol
SymbolLocation
Relationship
Evidence
IndexSnapshot
```

Les identifiants externes SCIP ou Glean peuvent être conservés comme métadonnées, mais ne doivent pas constituer l'abstraction principale du domaine.

### 4.5 CodeKnowledgeStore

`CodeKnowledgeStore` est une abstraction possédée par MINOS.

Elle représente les capacités dont MINOS a besoin pour stocker et interroger les connaissances du code, sans recopier l'API d'un produit particulier.

Opérations conceptuelles possibles :

```text
storeSymbols
storeRelationships
findSymbol
findUsages
findRelationships
traverseDependencies
findCallers
findCallees
queryEvidence
```

L'implémentation privilégiée à évaluer est Glean.

Les consommateurs MINOS ne doivent pas avoir à connaître :

- Glean ;
- Angle ;
- RocksDB ;
- Thrift.

### 4.6 Couche d'intelligence MINOS

Cette couche ajoute les connaissances dérivées propres à MINOS.

Exemples :

```text
DEPENDS_ON
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Toute information dérivée doit conserver :

- sa provenance ;
- ses preuves ;
- son niveau de confiance.

### 4.7 Services de requêtes

Les services exposent des cas d'usage indépendants de la technologie de stockage.

Premières opérations candidates :

```text
findSymbol
findUsages
findImplementations
findDependencies
findDependents
findCallers
findCallees
getRelatedTests
```

Opérations futures :

```text
analyzeImpact
getArchitectureOverview
getSymbolContext
getModuleContext
```

---

## 5. Résolution, preuves et confiance

Chaque relation doit permettre de distinguer au minimum :

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

Les valeurs techniques pourront rester normalisées en anglais, mais leur documentation doit être en français.

Chaque résultat dérivé ou heuristique doit pouvoir exposer :

```text
origin
confidence
evidence
path
```

Exemple :

```text
Relation : RELATED_TEST
Source : DocumentIngestionServiceTest
Cible : DocumentIngestionService
Résolution : HEURISTIC
Confiance : 0.98
Preuves :
- importe le symbole cible
- appelle ingest(Document)
- appartient à la même hiérarchie de package
```

---

## 6. Stratégie SCIP candidate

SCIP est envisagé comme protocole d'interopérabilité privilégié lorsqu'un indexeur suffisamment fiable existe.

SCIP :

- n'est pas le modèle de domaine MINOS ;
- n'est pas obligatoire pour chaque langage ;
- doit rester derrière un adaptateur d'ingestion.

Flux candidat :

```text
Indexeur spécifique au langage
        │
        ▼
      SCIP
        │
        ▼
Adaptateur d'ingestion SCIP
        │
        ▼
Modèle normalisé MINOS
```

D'autres flux doivent rester possibles.

---

## 7. Stratégie Glean candidate

Glean est le candidat privilégié à évaluer pour le stockage et l'interrogation détaillée des faits de code.

Cependant :

> Glean est un choix d'infrastructure, pas une frontière du domaine.

Architecture candidate :

```text
Domaine MINOS
     │
     ▼
CodeKnowledgeStore
     │
     ▼
GleanCodeKnowledgeStore
     │
     ▼
   Glean
```

Ce découplage doit permettre à MINOS de :

- remplacer Glean si ses contraintes deviennent excessives ;
- utiliser un backend plus léger pour certains cas ;
- disposer d'un backend mémoire pour les tests ;
- combiner plusieurs moteurs spécialisés à terme.

---

## 8. Principe multi-langages

Le cœur MINOS ne doit jamais contenir une liste fermée de langages.

L'abstraction principale doit être fondée sur les **capacités**.

Java, TypeScript ou Python peuvent servir de terrains de validation, mais ne constituent pas des frontières architecturales.

---

## 9. Moteurs spécialisés futurs

MINOS pourra éventuellement orchestrer d'autres moteurs pour certaines analyses.

Exemple conceptuel :

```text
find_symbol       -> index sémantique / faits SCIP
find_usages       -> Glean / index sémantique
find_callers      -> Glean / fournisseur spécifique
analyze_data_flow -> futur fournisseur CPG
security_analysis -> futur moteur spécialisé
```

MINOS reste la façade qui choisit, normalise et explique les résultats.

---

## 10. Non-objectifs pendant C0 et M0

Ne pas chercher à construire immédiatement :

- un parser Java maison ;
- un framework complet de parsers multi-langages ;
- une résolution parfaite des appels dynamiques ;
- un serveur MCP de production ;
- l'intégration NEXUS ;
- des embeddings ;
- une recherche vectorielle ;
- une indexation cloud ;
- une plateforme REST publique.

L'objectif de C0 est de cadrer.

L'objectif de M0 sera de valider les choix techniques par des expérimentations mesurables.