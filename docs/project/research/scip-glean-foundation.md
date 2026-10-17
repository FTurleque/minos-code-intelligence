# Étude de fondation — SCIP + Glean

Statut : **Document de recherche C0 — hypothèses à valider**

## Objectif

Ce document rassemble les premières hypothèses techniques concernant l'utilisation de SCIP et Glean comme briques structurantes potentielles de MINOS.

Il ne remplace ni le cahier des charges ni les expérimentations. Chaque hypothèse devra être validée avant de devenir une décision d'architecture acceptée.

---

## 1. Pourquoi ne pas développer tous les analyseurs de langages nous-mêmes ?

Une Code Intelligence précise nécessite plus qu'un simple parsing syntaxique.

Un analyseur de qualité doit potentiellement comprendre :

- l'identité des symboles ;
- les portées ;
- les imports ;
- les surcharges ;
- l'héritage ;
- les implémentations ;
- les types ;
- le système de build ;
- les dépendances ;
- les sources générées ;
- les références entre fichiers.

Réimplémenter ces mécanismes pour chaque langage retarderait fortement la valeur propre de MINOS.

Hypothèse actuelle :

> MINOS doit réutiliser des indexeurs sémantiques matures lorsqu'ils existent et sont suffisamment fiables.

---

## 2. Rôle envisagé de SCIP

SCIP est envisagé comme une couche d'interopérabilité entre les indexeurs spécifiques aux langages et MINOS.

```text
Dépôt
  │
  ▼
Indexeur sémantique spécifique au langage
  │
  ▼
SCIP
  │
  ├── symboles
  ├── occurrences
  ├── définitions
  ├── références
  └── relations disponibles
  │
  ▼
Ingestion MINOS / Glean
```

### Avantages potentiels

- format d'échange commun ;
- réduction du besoin de parsers spécifiques à MINOS ;
- séparation entre indexation et interrogation ;
- support possible de plusieurs écosystèmes ;
- pont potentiel vers Glean.

### Limites

SCIP ne garantit pas une profondeur sémantique identique pour chaque langage.

MINOS devra suivre les capacités de chaque fournisseur indépendamment.

Exemple :

```text
Fournisseur A
- définitions : oui
- références : oui
- implémentations : oui
- graphe d'appels : partiel
- flux de données : non

Fournisseur B
- définitions : oui
- références : oui
- graphe d'appels : oui
- flux de données : oui
```

La présence d'un index SCIP ne doit donc jamais suffire à conclure que toutes les capacités sont disponibles.

---

## 3. Rôle envisagé de Glean

Glean est un système open source spécialisé dans le stockage, la dérivation et l'interrogation de faits typés sur le code.

Caractéristiques intéressantes pour MINOS :

- stockage orienté faits de code ;
- schémas typés ;
- déduplication ;
- requêtes déclaratives ;
- interrogation des relations ;
- ajout de faits personnalisés ;
- intégrations d'indexation existantes ;
- possibilités d'ingestion SCIP.

Hypothèse : Glean pourrait fournir une part importante de l'infrastructure de connaissance sous MINOS.

Cette hypothèse doit être validée techniquement et opérationnellement.

---

## 4. Pourquoi Glean ne doit pas devenir le domaine MINOS

MINOS a besoin de concepts stables indépendamment du backend.

Exemples :

```text
SymbolResult
UsageResult
DependencyResult
ImpactResult
Evidence
Confidence
IndexStatus
```

Frontière candidate :

```text
Consommateur
    │
    ▼
Service de requêtes MINOS
    │
    ▼
CodeKnowledgeStore
    │
    ▼
Adaptateur Glean
    │
    ▼
Glean
```

Les identifiants Glean, prédicats Angle et types Thrift ne doivent pas fuiter vers les contrats publics MINOS.

---

## 5. Ce que MINOS pourrait réutiliser de Glean

- stockage persistant des faits ;
- ingestion des résultats d'indexation ;
- schémas typés ;
- requêtes sémantiques ;
- recherches de définitions et références ;
- parcours transitifs ;
- schémas personnalisés pour les faits dérivés ;
- requêtes locales performantes.

---

## 6. Ce que MINOS doit posséder

- registre des projets et workspaces ;
- découverte des langages et builds ;
- registre des indexeurs ;
- sélection selon les capacités ;
- orchestration de l'indexation ;
- modèle normalisé public et métier ;
- provenance et confiance ;
- dépendances dérivées ;
- analyse des tests liés ;
- analyse d'impact ;
- vue d'architecture ;
- contexte compact ;
- contrats CLI ;
- contrats MCP ;
- contrats API ;
- contrats d'intégration NEXUS.

---

## 7. Hypothèse de stockage séparé

Une piste à évaluer :

```text
Métadonnées MINOS
(registre, configuration, snapshots)
        │
        ▼
Stockage local léger

Faits de code et requêtes graphe
        │
        ▼
      Glean
```

Aucune décision n'est prise à ce stade.

---

## 8. Questions opérationnelles concernant Glean

Avant adoption, il faudra répondre notamment à :

1. Quelle est la difficulté d'installation locale ?
2. MINOS peut-il orchestrer Glean sans exposer sa complexité ?
3. Quel est le coût de démarrage ?
4. Quel est le coût d'indexation selon la taille du dépôt ?
5. Quelle est la taille disque ?
6. Comment isoler les bases par projet ou workspace ?
7. Comment gérer les évolutions de schéma ?
8. Comment communiquer avec Glean depuis Java ?
9. Un processus sidecar est-il acceptable ?
10. Une distribution pratique est-elle possible sous Windows, Linux et macOS ?
11. Que se passe-t-il en cas de base corrompue ?
12. MINOS peut-il reconstruire tout l'état dérivé ?

---

## 9. Options d'intégration Glean

### Option A — Processus sidecar

```text
MINOS JVM
   │
   │ RPC
   ▼
Processus Glean
   │
   ▼
Base Glean
```

Avantages : isolation de la stack non-Java et frontière claire.

Inconvénients : gestion de processus, distribution, IPC ou ports locaux.

### Option B — Orchestration CLI pour M0

```text
Spike MINOS
   │
   ▼
CLI Glean
   │
   ▼
Base Glean
```

Avantages : chemin de validation rapide.

Inconvénients : inadapté comme API long terme.

Usage envisagé : **M0 uniquement**.

### Option C — Client RPC généré

Évaluer si les clients Thrift/RPC permettent une intégration Java maintenable.

---

## 10. Expérimentations envisagées après C0

### Expérience A — Indexation Java

```text
Dépôt représentatif
    │
    ▼
scip-java
    │
    ▼
index.scip
```

À vérifier : symboles, surcharges, définitions, références, implémentations, multi-module et cas framework pertinents.

### Expérience B — SCIP vers Glean

```text
index.scip
    │
    ▼
scip-to-glean
    │
    ▼
Base Glean
```

À vérifier : définition, références, implémentations et appelants lorsque disponibles.

### Expérience C — Normalisation MINOS

Implémenter uniquement le strict minimum nécessaire à la validation :

```text
find_symbol
find_usages
```

Les résultats doivent être des types MINOS.

### Expérience D — Preuve non-Java

Exécuter le même pipeline conceptuel sur un second écosystème.

L'objectif est de valider l'architecture, pas de multiplier immédiatement les langages.

---

## 11. Porte de décision

À la fin de M0 :

### ADOPTER

SCIP + Glean offrent une précision, des performances et une opérabilité locale suffisantes.

### ADOPTER_AVEC_CONTRAINTES

Glean reste utile, mais un backend plus léger est nécessaire dans certains contextes.

### REVOIR

SCIP reste utile mais Glean est trop complexe ; conserver `CodeKnowledgeStore` et changer de backend.

### REMPLACER

Les hypothèses principales échouent ; réévaluer la fondation tout en préservant les frontières du domaine MINOS.

---

## 12. Recommandation de travail actuelle

La recommandation actuelle, encore non validée, est :

> Réutiliser fortement SCIP et Glean pour éviter de reconstruire une infrastructure mature, tout en laissant MINOS posséder son domaine, son orchestration, son explicabilité et ses contrats publics.

En résumé :

> **SCIP et Glean fournissent potentiellement les faits. MINOS fournit la Code Intelligence. NEXUS fournit la Context Intelligence.**