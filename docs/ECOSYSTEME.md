# Écosystème IA — Positionnement de MINOS

Statut : **Proposition de cadrage — à valider pendant C0**

Ce document décrit la place conceptuelle de MINOS dans l'écosystème IA global.

Il complète le cahier des charges et la vue d'architecture interne de MINOS. Il ne définit pas encore des dépendances techniques obligatoires entre les projets.

---

## 1. Vue d'ensemble

La vision d'écosystème envisagée est la suivante :

```text
                       JARVIS
                    Orchestration
                         │
            ┌────────────┴────────────┐
            │                         │
            ▼                         ▼
          NEXUS                     MINOS
   Context Intelligence       Code Intelligence
            │                         │
            └────────────┬────────────┘
                         ▼
                 ALFRED / BRAINIAC
                  Agents / profils IA
```

Cette représentation exprime des **responsabilités fonctionnelles distinctes**.

Elle ne signifie pas que MINOS dépend techniquement de JARVIS, NEXUS, Alfred ou Brainiac.

MINOS doit rester utilisable de manière autonome via ses propres interfaces d'exposition, notamment la CLI, le MCP et l'API.

---

## 2. JARVIS — Orchestration

JARVIS représente la couche d'orchestration de l'écosystème.

Son rôle envisagé est de coordonner les capacités disponibles et de décider quels services ou agents mobiliser pour une tâche.

JARVIS peut notamment orchestrer :

- MINOS pour obtenir de l'intelligence structurée sur le code ;
- NEXUS pour construire ou optimiser le contexte destiné à une IA ;
- Alfred et Brainiac pour exécuter des tâches selon leurs profils et capacités respectifs.

JARVIS ne doit pas absorber les responsabilités propres à MINOS ou NEXUS.

---

## 3. MINOS — Code Intelligence

MINOS est la couche de **Code Intelligence**.

Sa question fondamentale est :

> **Que contient le codebase, où se trouvent ses éléments et comment sont-ils reliés ?**

MINOS fournit notamment :

- la structure des projets et workspaces ;
- les symboles ;
- les définitions ;
- les références et usages ;
- les implémentations ;
- les relations d'héritage ;
- les appels lorsque l'information est disponible ;
- les dépendances et dépendants ;
- les tests liés ;
- les chemins d'impact ;
- les vues d'architecture ;
- les preuves et niveaux de confiance associés aux résultats.

MINOS doit exposer ces informations de manière structurée, compacte et indépendante du fournisseur d'indexation ou du backend utilisé.

---

## 4. NEXUS — Context Intelligence

NEXUS est la couche de **Context Intelligence**.

Sa question fondamentale est :

> **Parmi toutes les informations disponibles, lesquelles faut-il fournir à l'IA pour accomplir cette tâche précise ?**

NEXUS peut consommer les informations fournies par MINOS, mais peut également utiliser d'autres sources de contexte.

NEXUS est responsable notamment de :

- comprendre le besoin de contexte associé à une tâche ;
- sélectionner les informations pertinentes ;
- classer et prioriser les éléments ;
- respecter un budget de contexte ou de tokens ;
- construire un contexte compact destiné à un modèle ou un agent.

MINOS ne doit pas réaliser cette sélection à la place de NEXUS.

---

## 5. Alfred et Brainiac — Agents ou profils spécialisés

Alfred et Brainiac représentent des capacités ou profils IA spécialisés de l'écosystème.

Ils peuvent utiliser les informations fournies par MINOS et NEXUS sans connaître les technologies internes employées par ces moteurs.

Exemple conceptuel :

```text
Demande utilisateur
       │
       ▼
     JARVIS
       │
       ├── demande à MINOS :
       │   "Quels symboles et dépendances concernent cette fonctionnalité ?"
       │
       ├── demande à NEXUS :
       │   "Quel contexte faut-il fournir pour cette tâche ?"
       │
       ▼
ALFRED / BRAINIAC
       │
       ▼
Traitement de la tâche
```

Le rôle exact d'Alfred et Brainiac dans cette chaîne appartient à leurs projets respectifs et ne doit pas être imposé par MINOS.

---

## 6. Relations et dépendances autorisées

La relation conceptuelle cible est :

```text
MINOS ─────► fournit de la Code Intelligence
   │
   ├────────────► NEXUS peut la consommer
   │
   ├────────────► JARVIS peut la consommer
   │
   ├────────────► Alfred peut la consommer
   │
   └────────────► Brainiac peut la consommer
```

En revanche, MINOS ne doit pas dépendre fonctionnellement de ces consommateurs.

La règle d'architecture est :

> **MINOS produit de la connaissance structurée sur le code. Les autres composants décident comment l'utiliser.**

---

## 7. Vue combinée Code Intelligence + Context Intelligence

Le flux détaillé envisagé peut être représenté ainsi :

```text
Repository / Workspace
         │
         ▼
       MINOS
  Code Intelligence
         │
         ├── symboles
         ├── relations
         ├── usages
         ├── dépendances
         ├── tests
         ├── architecture
         └── impacts
         │
         ▼
       NEXUS
 Context Intelligence
         │
         ├── sélection
         ├── classement
         ├── réduction
         └── budget de contexte
         │
         ▼
   Contexte optimisé
         │
         ▼
ALFRED / BRAINIAC / autre agent
```

Cette vue est complémentaire à la vue orchestrée par JARVIS :

- la première explique le **flux de données et de connaissance** ;
- la seconde explique la **place des composants dans l'écosystème**.

---

## 8. Principe à préserver

Les composants doivent rester découplés :

```text
JARVIS   = Orchestration
MINOS    = Code Intelligence
NEXUS    = Context Intelligence
ALFRED   = Agent / profil spécialisé
BRAINIAC = Agent / profil spécialisé
```

Cette répartition est une proposition de cadrage à valider pendant C0.

Aucune dépendance directe de MINOS vers JARVIS, NEXUS, Alfred ou Brainiac ne doit être introduite sans décision d'architecture explicite.