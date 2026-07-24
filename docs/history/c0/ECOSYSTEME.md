# Écosystème IA — Positionnement de MINOS

Statut : **Proposition de cadrage — à valider pendant C0**

Ce document décrit la place conceptuelle de MINOS dans l'écosystème IA global.

Il complète le cahier des charges et la vue d'architecture interne de MINOS. Il ne définit pas de dépendances techniques obligatoires entre les projets.

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

## 2. Deux vues complémentaires

### 2.1 Vue d'orchestration de l'écosystème

```text
JARVIS
  │
  ├── sollicite MINOS pour comprendre le code
  ├── sollicite NEXUS pour construire le contexte pertinent
  └── mobilise ALFRED / BRAINIAC selon la tâche
```

Cette vue décrit **qui orchestre quelles capacités**.

### 2.2 Vue du flux de connaissance

```text
CODEBASE / WORKSPACE
        │
        ▼
      MINOS
 Code Intelligence
« Je comprends le code »
        │
        ▼
      NEXUS
Context Intelligence
« Je sélectionne le bon contexte »
        │
        ▼
 AGENT / LLM / IDE
```

Cette vue décrit **comment la connaissance du code peut alimenter un contexte IA**.

Les deux vues sont compatibles mais ne doivent pas être confondues.

---

## 3. JARVIS — Orchestration

JARVIS représente la couche d'orchestration de l'écosystème.

Son rôle envisagé est de coordonner les capacités disponibles et de décider quels services ou agents mobiliser pour une tâche.

JARVIS peut notamment :

- interroger MINOS pour obtenir de l'intelligence structurée sur le code ;
- solliciter NEXUS pour construire ou optimiser le contexte destiné à une IA ;
- mobiliser Alfred ou Brainiac selon leurs profils et capacités ;
- enchaîner plusieurs capacités dans un workflow plus large.

JARVIS ne doit pas absorber les responsabilités propres à MINOS ou NEXUS.

MINOS ne doit pas nécessiter JARVIS pour fonctionner.

---

## 4. MINOS — Code Intelligence

MINOS est la couche de **Code Intelligence**.

Sa question fondamentale est :

> **Que contient le codebase, où se trouvent ses éléments et comment sont-ils reliés ?**

MINOS fournit notamment :

- la structure des projets et workspaces ;
- les fichiers, modules, packages ou namespaces ;
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

MINOS ne sélectionne pas le contexte final à envoyer à un LLM.

---

## 5. NEXUS — Context Intelligence

NEXUS est la couche de **Context Intelligence**.

Sa question fondamentale est :

> **Parmi toutes les informations disponibles, lesquelles faut-il fournir à l'IA pour accomplir cette tâche précise ?**

NEXUS peut consommer les informations fournies par MINOS, mais peut également utiliser d'autres sources de contexte.

Ses responsabilités envisagées comprennent notamment :

- comprendre l'intention d'une tâche ;
- rechercher les sources de contexte disponibles ;
- sélectionner les informations pertinentes ;
- classer ou scorer ces informations ;
- respecter un budget de contexte ou de tokens ;
- construire un bundle de contexte adapté au modèle ou à l'agent cible.

NEXUS ne doit pas reconstruire le graphe de code que MINOS fournit déjà.

MINOS ne doit pas intégrer les responsabilités de sélection de contexte propres à NEXUS.

---

## 6. ALFRED et BRAINIAC — Agents ou profils spécialisés

Alfred et Brainiac représentent des agents, profils ou capacités IA spécialisées de l'écosystème.

Ils peuvent potentiellement :

- être orchestrés par JARVIS ;
- recevoir du contexte préparé par NEXUS ;
- interroger directement MINOS lorsque leur tâche nécessite de la Code Intelligence ;
- combiner plusieurs sources selon leurs capacités.

Leur rôle précis appartient à leurs projets respectifs et ne doit pas être imposé par MINOS.

---

## 7. Relation fonctionnelle entre MINOS et NEXUS

La séparation fondamentale est la suivante :

```text
MINOS
« Je sais ce qui existe dans le code et comment c'est relié. »

NEXUS
« Je sais quelles informations sont pertinentes pour cette tâche. »
```

Exemple :

```text
Question utilisateur
« Corrige le bug dans DocumentIngestionService »

MINOS peut fournir :
- DocumentIngestionService
- ses méthodes
- ses dépendances
- ses dépendants
- ses appelants
- ses tests liés
- les plages de code pertinentes

NEXUS peut ensuite décider de fournir à l'agent :
- DocumentIngestionService#ingest
- DocumentParser
- DocumentIngestionServiceTest
```

Cette séparation doit rester stable, même si les détails techniques d'intégration évoluent.

---

## 8. Autonomie des composants

Les règles d'indépendance envisagées sont :

- MINOS fonctionne sans NEXUS ;
- MINOS fonctionne sans JARVIS ;
- MINOS fonctionne sans Alfred ni Brainiac ;
- NEXUS peut utiliser MINOS mais ne doit pas être son unique source ;
- JARVIS peut orchestrer MINOS et NEXUS sans absorber leurs domaines ;
- Alfred et Brainiac peuvent consommer des capacités sans devenir des dépendances du cœur MINOS.

Les intégrations doivent passer par des contrats explicites :

```text
CLI
API
MCP
contrats applicatifs stables
```

---

## 9. AI Skills Registry

MINOS ne doit pas gérer directement les skills IA.

Un registre de skills pourra néanmoins référencer des capacités s'appuyant sur MINOS.

Exemple :

```text
Skill : java-impact-analysis
        │
        ▼
      MINOS
        ├── find_usages
        ├── find_dependencies
        ├── get_related_tests
        └── analyze_impact
```

Le skill orchestre une capacité métier ; MINOS reste le moteur de connaissance du code.

---

## 10. CEREBRO

Le nom **CEREBRO** a été évoqué dans l'écosystème pour un usage possible lié à des modèles ou à des capacités sémantiques.

Aucune responsabilité définitive n'est actuellement attribuée à CEREBRO dans le projet MINOS.

MINOS ne doit donc pas dépendre d'un composant nommé CEREBRO tant que son rôle n'est pas défini dans le projet concerné.

Cette question reste **hors périmètre de C0 MINOS**, sauf si elle devient nécessaire pour clarifier une frontière d'intégration.

---

## 11. Principe de gouvernance de l'écosystème

Chaque composant doit avoir une responsabilité claire et pouvoir évoluer indépendamment.

La cible conceptuelle est :

```text
JARVIS  = Orchestration
MINOS   = Code Intelligence
NEXUS   = Context Intelligence
ALFRED  = Agent / profil spécialisé
BRAINIAC = Agent / profil spécialisé
```

Les noms et rôles des composants externes à MINOS restent gouvernés par leurs propres projets.

Ce document décrit uniquement les frontières nécessaires pour éviter que MINOS n'absorbe des responsabilités qui ne lui appartiennent pas.