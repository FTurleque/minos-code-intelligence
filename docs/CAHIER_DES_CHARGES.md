# Cahier des charges — MINOS

Statut : **Brouillon de cadrage — à valider avant toute implémentation fonctionnelle**

Date : 19 juillet 2026

Ce document constitue la **source de vérité fonctionnelle et technique de haut niveau** du projet MINOS pendant la phase C0.

Aucune implémentation importante ne doit être considérée comme engagée tant que les objectifs, le périmètre, les responsabilités, les contraintes, les principales décisions d'architecture et les critères de validation décrits ici ne sont pas explicitement validés.

> **Règle de travail : documenter d'abord, décider ensuite, implémenter en dernier.**

---

# 1. Présentation du projet

## 1.1 Nom

**MINOS**

## 1.2 Nature du produit

MINOS est un **moteur d'intelligence du code** (*Code Intelligence Engine*).

Sa responsabilité est de construire, maintenir et exposer une représentation :

- structurée ;
- persistante ;
- interrogeable ;
- explicable ;
- exploitable par des machines ;

d'un ou plusieurs projets logiciels.

MINOS doit permettre à un humain, un IDE, un outil CLI, un serveur MCP, une API ou un agent IA d'obtenir rapidement des informations précises sur un codebase sans devoir relire ou charger systématiquement l'intégralité du dépôt.

MINOS n'est pas :

- un chatbot ;
- un LLM ;
- un agent IA ;
- un moteur de génération de code ;
- un simple moteur de recherche plein texte ;
- un système de sélection du contexte pour LLM ;
- un produit dépendant d'un fournisseur IA particulier.

## 1.3 Formulation synthétique

> **MINOS transforme un codebase en modèle de connaissance du code, puis permet de l'interroger.**

---

# 2. Positionnement dans l'écosystème IA

Deux vues complémentaires doivent être distinguées.

## 2.1 Vue d'orchestration

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

Cette vue exprime des responsabilités fonctionnelles et non des dépendances techniques obligatoires.

## 2.2 Vue du flux de connaissance

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

## 2.3 Frontière MINOS / NEXUS

MINOS répond à la question :

> **Que contient le projet, où se trouvent ses éléments et comment sont-ils reliés ?**

NEXUS répond à la question :

> **Parmi les informations disponibles, lesquelles faut-il fournir à l'IA pour cette tâche précise ?**

MINOS doit fonctionner sans NEXUS.

NEXUS pourra consommer MINOS, mais MINOS ne devra jamais dépendre fonctionnellement de NEXUS.

## 2.4 Frontière avec JARVIS

JARVIS est envisagé comme couche d'orchestration.

Il peut solliciter MINOS, NEXUS et d'autres agents ou capacités, mais ne doit pas absorber leurs domaines respectifs.

MINOS doit fonctionner sans JARVIS.

## 2.5 Alfred et Brainiac

Alfred et Brainiac représentent des agents ou profils spécialisés pouvant consommer les capacités disponibles.

Ils ne constituent pas des dépendances du cœur MINOS.

## 2.6 CEREBRO

Le nom CEREBRO a été évoqué pour un usage potentiel lié à des modèles ou capacités sémantiques.

Son rôle n'est pas défini dans MINOS et reste hors périmètre du cadrage fonctionnel de MINOS.

Voir également : [`ECOSYSTEME.md`](ECOSYSTEME.md).

---

# 3. Vision

MINOS doit devenir la couche de **Code Intelligence** de l'écosystème IA.

À terme, un humain ou un agent doit pouvoir poser des questions telles que :

```text
Où est défini DocumentIngestionService ?
```

```text
Quelles classes utilisent UserRepository ?
```

```text
Qui appelle cette méthode ?
```

```text
Quelles implémentations existent pour cette interface ?
```

```text
De quels composants ce service dépend-il ?
```

```text
Quels composants dépendent de ce service ?
```

```text
Quels tests sont liés à cette classe ?
```

```text
Quels éléments pourraient être impactés si cette méthode change ?
```

```text
Quel est le flux entre l'upload d'un document et son indexation ?
```

```text
Dans quel module se trouve cette fonctionnalité ?
```

```text
Quelles sont les classes centrales de cette partie de l'application ?
```

MINOS doit privilégier, dans cet ordre général :

1. la précision des informations ;
2. la qualité de la résolution des symboles ;
3. l'explicabilité ;
4. la rapidité des requêtes ;
5. l'efficacité pour les agents IA ;
6. la réduction du contexte et des tokens ;
7. l'extensibilité multi-langages ;
8. le fonctionnement local et hors ligne autant que possible.

La quantité de fonctionnalités est secondaire par rapport à la fiabilité du modèle de connaissance du code.

---

# 4. Problème à résoudre

Lorsqu'un développeur ou un agent IA travaille sur un projet logiciel, il doit souvent parcourir de nombreux fichiers pour comprendre :

- la structure du dépôt ;
- les modules ;
- les packages ou espaces de noms ;
- les fichiers ;
- les classes, interfaces, fonctions et méthodes ;
- les dépendances ;
- les références ;
- les appels ;
- les relations d'héritage et d'implémentation ;
- les tests ;
- les flux fonctionnels ;
- l'architecture générale.

Cette exploration entraîne :

- une consommation élevée de tokens ;
- une consommation inutile de crédits IA ;
- des recherches répétitives ;
- des analyses lentes ;
- une compréhension partielle ;
- des hallucinations sur l'architecture ;
- des modifications sans connaissance suffisante des impacts ;
- une redécouverte permanente du même codebase.

MINOS doit construire une représentation persistante et réutilisable du code afin de réduire cette redécouverte.

---

# 5. Utilisateurs et consommateurs visés

MINOS doit pouvoir être consommé à terme par :

- des développeurs ;
- des outils CLI ;
- GitHub Copilot ;
- Claude ;
- ChatGPT / OpenAI ;
- IntelliJ IDEA ;
- VS Code ;
- des agents IA personnalisés ;
- JARVIS ;
- Alfred ;
- Brainiac ;
- NEXUS ;
- des serveurs MCP ;
- des pipelines CI/CD ;
- des outils d'analyse automatisée.

Ces consommateurs ne doivent pas connaître les détails internes de SCIP, Glean ou d'un indexeur particulier.

---

# 6. Principes architecturaux fondamentaux

## 6.1 Agnosticisme du langage

MINOS doit être **agnostique du langage**.

Java, TypeScript et Python ne sont que des exemples possibles de langages de validation.

Le cœur de MINOS ne doit contenir aucune liste fermée de langages supportés.

Des langages comme Kotlin, Scala, JavaScript, Go, Rust, C, C++, C#, Visual Basic, Ruby, PHP, Dart, SQL ou d'autres doivent pouvoir être ajoutés par intégration d'un fournisseur adapté.

## 6.2 Agnosticisme de l'indexeur

MINOS doit être **agnostique du moteur d'indexation**.

Un fournisseur pourra s'appuyer sur :

- SCIP ;
- un indexeur natif Glean ;
- LSIF ;
- un serveur de langage ;
- une API de compilateur ;
- un AST ;
- Tree-sitter ;
- un Code Property Graph ;
- un outil spécialisé ;
- une technologie future.

MINOS doit sélectionner les fournisseurs selon leurs capacités réelles, et non uniquement selon le nom du langage.

## 6.3 Réutilisation avant réimplémentation

MINOS ne doit pas réinventer systématiquement des briques open source matures.

L'objectif est de réutiliser autant que possible :

- les indexeurs sémantiques ;
- les protocoles d'interopérabilité ;
- les moteurs de faits et de requêtes ;
- les moteurs spécialisés ;

tout en conservant les contrats métier et la valeur propre de MINOS.

## 6.4 Local-first

Par défaut :

- aucune source ne doit être envoyée vers un service externe ;
- aucune analyse cloud ne doit être obligatoire ;
- aucun LLM ne doit être nécessaire au fonctionnement de base ;
- les dépôts privés doivent pouvoir être analysés localement ;
- les intégrations externes doivent être opt-in.

## 6.5 Résultats fondés sur les preuves

MINOS doit distinguer les faits déterministes des résultats dérivés ou heuristiques.

Statuts conceptuels envisagés :

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

Un résultat dérivé doit pouvoir fournir :

- son origine ;
- son niveau de confiance ;
- les preuves utilisées ;
- éventuellement le chemin de relations ayant conduit au résultat.

Aucune heuristique ne doit être présentée comme une certitude.

## 6.6 Efficacité en tokens

MINOS doit retourner par défaut des résultats ciblés et structurés plutôt que des fichiers complets.

## 6.7 Extensibilité

Un nouveau langage, un nouvel indexeur, un nouveau moteur spécialisé ou un nouveau backend doit pouvoir être ajouté sans réécriture du domaine principal.

---

# 7. Architecture conceptuelle cible

L'architecture suivante est une hypothèse de travail à valider :

```text
Dépôts / Workspaces
        │
        ▼
Découverte du projet
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
 SCIP  Indexeurs Glean natifs      Autres fournisseurs
  │                               AST / LSP / LSIF / CPG
  └──────────────────┬──────────────────┘
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
        Glean              Backend futur
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
         Requêtes Code Intelligence
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
         CLI        MCP        API
                     │
                     ▼
                   NEXUS
```

MINOS doit rester la façade qui normalise et explique les résultats, indépendamment des moteurs utilisés en dessous.

---

# 8. Gestion des projets et workspaces

MINOS doit pouvoir gérer plusieurs projets.

Informations minimales envisagées :

```text
id
nom
chemin local
type
langages détectés
technologies détectées
systèmes de build détectés
date de dernière indexation
état de l'index
version du schéma d'index
```

Le premier périmètre vise les dépôts locaux.

Extensions futures :

- GitHub ;
- GitLab ;
- archives ;
- workspaces multi-dépôts.

Le modèle doit permettre de distinguer projet, module et workspace.

---

# 9. Découverte et scanner de dépôt

MINOS doit identifier notamment :

- fichiers sources ;
- fichiers de tests ;
- ressources ;
- configurations ;
- modules ;
- fichiers de build ;
- documentation.

MINOS doit respecter autant que possible :

```text
.gitignore
```

et prévoir :

```text
.minosignore
```

Exclusions par défaut envisagées selon le contexte :

```text
.git/
target/
build/
node_modules/
.idea/
out/
dist/
```

Les fichiers binaires ne doivent pas être analysés inutilement.

La découverte du dépôt ne doit pas être confondue avec l'analyse sémantique du code.

---

# 10. Modèle de fournisseurs d'indexation

Concepts candidats :

```text
IndexerProvider
IndexerRegistry
IndexerCapabilities
IndexingRequest
IndexingResult
```

Les fournisseurs doivent déclarer leurs capacités.

Exemples :

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

MINOS ne doit jamais supposer qu'un fournisseur fournit toutes les capacités simplement parce qu'il supporte un langage.

La négociation des capacités doit permettre de sélectionner le meilleur fournisseur disponible pour un besoin donné.

---

# 11. Stratégie SCIP

Hypothèse actuelle : SCIP peut réduire fortement la quantité de code spécifique à développer pour l'indexation sémantique multi-langages.

SCIP est envisagé comme **protocole d'interopérabilité sémantique privilégié**, mais non obligatoire.

MINOS doit évaluer :

- la qualité des indexeurs ;
- leur couverture ;
- leur maintenance ;
- leur licence ;
- leur fonctionnement hors ligne ;
- leur facilité d'installation ;
- leur performance ;
- leur comportement sur des projets réels.

SCIP ne doit pas devenir le modèle de domaine MINOS.

Les types protobuf SCIP ne doivent pas fuiter dans les contrats publics MINOS.

Un fournisseur non-SCIP doit pouvoir être préféré s'il offre une meilleure précision ou des capacités absentes.

---

# 12. Stratégie Glean

Hypothèse actuelle : Glean peut fournir une grande partie de l'infrastructure de :

- stockage des faits de code ;
- schémas typés ;
- déduplication ;
- interrogation ;
- traversée de relations ;
- dérivation de faits ;
- persistance de connaissances spécifiques à MINOS.

Stratégie candidate :

> **Glean-first, not Glean-locked.**

Cette stratégie n'est pas encore acceptée.

Glean doit être placé derrière une abstraction appartenant à MINOS, provisoirement nommée :

```text
CodeKnowledgeStore
```

Le contrat doit être défini à partir des cas d'usage MINOS et non comme une copie de l'API Glean.

Les éléments suivants ne doivent pas fuiter dans les contrats publics :

- types Glean ;
- Angle ;
- Thrift ;
- identifiants internes Glean ;
- détails de stockage.

Points à valider :

- installation locale ;
- Windows ;
- Linux ;
- macOS ;
- coût de démarrage ;
- consommation mémoire ;
- taille disque ;
- complexité opérationnelle ;
- gestion des processus ;
- intégration Java ;
- communication RPC / Thrift / CLI ;
- évolutions de schéma ;
- reconstruction après corruption ;
- petits et gros projets.

Décisions possibles après M0 :

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

---

# 13. Modèle de données minimal

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

Le modèle doit rester indépendant de SCIP et de Glean.

## 13.1 SourceFile

Informations candidates :

```text
id
projectId
moduleId
relativePath
language
sourceType
contentHash
size
lastModified
```

`sourceType` pourra distinguer notamment :

```text
MAIN
TEST
RESOURCE
CONFIGURATION
DOCUMENTATION
```

## 13.2 Symbol

Informations candidates :

```text
id
projectId
moduleId
fileId
parentSymbolId
kind
name
qualifiedName
signature
language
startLine
startColumn
endLine
endColumn
visibility
modifiers
resolutionStatus
metadata
```

Types de symboles envisagés :

```text
CLASS
INTERFACE
RECORD
ENUM
ANNOTATION
METHOD
CONSTRUCTOR
FIELD
FUNCTION
```

Le modèle doit rester extensible pour les concepts propres aux langages.

L'identité d'une méthode surchargée doit tenir compte de sa signature.

## 13.3 Relationship

Relations factuelles candidates :

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
```

Relations dérivées ou sémantiques possibles :

```text
USES
DEPENDS_ON
INJECTS
RELATED_TEST
TESTS
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Chaque relation doit pouvoir conserver :

```text
sourceSymbolId
targetSymbolId
kind
location
resolutionStatus
confidence
origin
evidence
```

Les relations factuelles doivent être distinguées des relations dérivées.

---

# 14. Cas d'usage fonctionnels

## 14.1 Recherche de symbole

Capacité cible :

```text
find_symbol
```

Recherche par :

- nom simple ;
- nom qualifié ;
- type de symbole ;
- projet ;
- module lorsque pertinent.

Résultat minimal :

- nom ;
- type ;
- signature ;
- emplacement ;
- fichier ;
- module ;
- principales relations ;
- provenance.

## 14.2 Recherche d'usages

Capacité cible :

```text
find_usages
```

Peut identifier selon les capacités disponibles :

- références ;
- imports ;
- appels ;
- héritages ;
- implémentations ;
- injections ;
- autres usages pertinents.

Chaque résultat doit exposer :

- fichier ;
- symbole source ;
- relation ;
- emplacement ;
- niveau de résolution ;
- provenance.

## 14.3 Implémentations

```text
find_implementations
```

Doit distinguer les implémentations résolues des résultats partiels ou heuristiques.

## 14.4 Appelants et appelés

```text
find_callers
find_callees
```

À exposer lorsque les données disponibles sont suffisamment fiables.

## 14.5 Dépendances et dépendants

```text
find_dependencies
find_dependents
```

Définition :

```text
dependencies = ce dont le symbole dépend

dependents = ce qui dépend du symbole
```

Une profondeur configurable doit être prévue.

## 14.6 Tests liés

```text
get_related_tests
```

Stratégies possibles :

- références directes ;
- imports ;
- instanciations ;
- appels de méthodes ;
- conventions de nommage ;
- proximité de package ou namespace.

Chaque résultat heuristique doit fournir :

- un niveau de confiance ;
- les raisons ;
- les preuves.

## 14.7 Vue d'architecture

```text
get_architecture_overview
```

Informations possibles :

- modules ;
- packages ou namespaces ;
- composants centraux ;
- dépendances structurantes ;
- technologies détectées ;
- zones fortement couplées.

Les faits détectés et les inférences architecturales doivent être distingués.

## 14.8 Analyse d'impact

```text
analyze_impact
```

Doit distinguer :

- impact direct ;
- impact indirect ;
- profondeur ;
- chemin explicatif ;
- niveau de confiance ;
- tests potentiellement impactés.

L'analyse statique ne doit jamais être présentée comme une preuve complète du comportement runtime.

## 14.9 Recherche structurée générale

Capacité cible :

```text
search_code
```

ou équivalent interne.

La recherche doit pouvoir couvrir progressivement :

- symboles ;
- fichiers ;
- packages ou namespaces ;
- modules ;
- relations.

Le premier niveau doit proposer au minimum :

- recherche lexicale ;
- recherche par nom de symbole ;
- recherche par nom qualifié.

L'architecture doit permettre plus tard :

- recherche sémantique ;
- embeddings ;
- recherche hybride.

Aucun service d'embeddings externe ne doit être obligatoire.

## 14.10 Contexte compact d'un symbole

Capacité cible future :

```text
get_symbol_context
```

Doit pouvoir retourner :

- symbole ;
- signature ;
- emplacement ;
- relations pertinentes ;
- plage de code utile ;
- preuves ;

sans charger le fichier complet par défaut.

---

# 15. Efficacité pour les agents IA

MINOS doit réduire la quantité de contexte nécessaire à la compréhension d'un codebase.

Par défaut, une réponse doit privilégier :

```text
symbole
signature
emplacement
relations
preuves
plage de code pertinente
```

et non :

```text
fichier source complet
```

Le contenu complet d'un fichier ne doit être retourné que lorsqu'il est explicitement demandé.

Les réponses doivent prévoir :

- limites de résultats ;
- limites de profondeur ;
- pagination lorsque nécessaire ;
- formats structurés ;
- plages de code ciblées.

Métriques futures spécifiques IA :

```text
Code Exploration Reduction
Estimated Tokens Avoided
Average Context Size
```

---

# 16. Interfaces d'exposition

Les couches d'exposition ne doivent contenir aucune logique d'analyse métier principale.

## 16.1 CLI

Commandes candidates :

```text
minos project add
minos project list
minos index
minos search
minos find-symbol
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
minos inspect
```

Les sorties machine doivent pouvoir être structurées, notamment en JSON.

## 16.2 MCP

MINOS doit être conçu pour devenir un serveur MCP de qualité.

Le MCP reste une couche d'exposition.

Outils candidats :

```text
get_project_structure
search_code
find_symbol
find_usages
find_implementations
find_callers
find_callees
find_dependencies
find_dependents
get_related_tests
get_symbol_context
get_file_symbols
get_module_context
get_architecture_overview
analyze_impact
get_index_status
```

Chaque outil doit :

- avoir une responsabilité claire ;
- être spécialisé plutôt que trop générique ;
- accepter des paramètres explicites ;
- retourner une sortie structurée ;
- proposer une limite configurable de résultats ;
- éviter les réponses excessivement volumineuses ;
- être déterministe lorsque les données le permettent.

Éviter les outils du type :

```text
query_everything
```

## 16.3 API

Une API pourra être ajoutée lorsque les contrats métier seront stabilisés.

Exemples conceptuels :

```text
POST /api/projects
POST /api/projects/{projectId}/index
GET  /api/projects/{projectId}/symbols/search
GET  /api/projects/{projectId}/symbols/{symbolId}
GET  /api/projects/{projectId}/symbols/{symbolId}/usages
GET  /api/projects/{projectId}/symbols/{symbolId}/dependencies
GET  /api/projects/{projectId}/symbols/{symbolId}/tests
```

La logique métier ne doit pas être placée dans les contrôleurs REST.

Le choix du framework serveur reste ouvert pendant C0.

---

# 17. Indexation complète et incrémentale

MINOS ne doit pas réindexer systématiquement l'intégralité d'un dépôt lorsque cela peut être évité.

La cible future doit distinguer :

```text
Full indexing
Incremental indexing
```

MINOS doit pouvoir détecter progressivement :

- fichier ajouté ;
- fichier modifié ;
- fichier supprimé.

Puis mettre à jour uniquement les portions concernées de l'index et du graphe lorsque les fournisseurs le permettent.

Le modèle doit prévoir :

- empreinte de contenu ;
- version du schéma ;
- version de l'analyseur ou fournisseur ;
- empreinte du build ;
- snapshot d'index ;
- règles d'invalidation ;
- repli vers une indexation complète.

L'indexation incrémentale n'est pas obligatoire dans le premier MVP.

---

# 18. Git Intelligence

Extension future envisagée :

```text
get_symbol_history
get_recent_changes
get_file_churn
get_recently_modified_symbols
```

Exemples d'informations :

- nombre de modifications d'un symbole ;
- date de dernière modification ;
- fréquence de changement ;
- zones de forte activité ;
- relations entre historique Git et composants du code.

Cette capacité n'est pas obligatoire pour le MVP.

---

# 19. Intégrations futures

## 19.1 NEXUS

MINOS expose des faits, relations, preuves et vues compactes.

NEXUS sélectionne les informations pertinentes pour une tâche et construit le contexte.

## 19.2 JARVIS

JARVIS peut orchestrer MINOS sans devenir une dépendance du cœur.

## 19.3 Alfred et Brainiac

Ces agents ou profils peuvent consommer MINOS via des contrats d'exposition.

## 19.4 AI Skills Registry

MINOS ne gère pas directement les skills IA.

Un skill pourra néanmoins utiliser les capacités de MINOS.

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

## 19.5 IDE et outils externes

Intégrations futures possibles :

- IntelliJ IDEA ;
- VS Code ;
- GitHub Copilot ;
- Claude ;
- ChatGPT / OpenAI ;
- agents personnalisés ;
- pipelines CI/CD.

---

# 20. Sécurité et confidentialité

MINOS doit pouvoir analyser des dépôts privés.

Exigences :

- aucune donnée envoyée vers un service externe par défaut ;
- aucune analyse cloud automatique ;
- intégrations externes opt-in ;
- possibilité d'exclure des fichiers ou chemins ;
- respect de `.gitignore` autant que possible ;
- support de `.minosignore` ;
- aucune dépendance obligatoire à un LLM ;
- secrets et fichiers sensibles exclus lorsque pertinent et configurable.

Le comportement par défaut doit être conservateur.

---

# 21. Stratégie de tests

Les tests doivent être présents dès le début de l'implémentation technique.

Types de tests à prévoir :

- tests unitaires ;
- tests d'intégration ;
- tests de parsing/indexation lorsque MINOS contrôle cette couche ;
- tests de normalisation ;
- tests de détection de symboles ;
- tests de relations ;
- tests de recherche ;
- tests de comportement des adaptateurs ;
- tests d'indexation incrémentale lorsqu'elle sera développée.

Des projets fixtures synthétiques doivent être créés.

Exemple :

```text
UserResource
    │
    ▼
UserService
    │
    ▼
UserRepository
```

Tests attendus :

```text
find_usages UserService
→ UserResource
```

```text
find_dependencies UserService
→ UserRepository
```

Les fixtures doivent inclure progressivement :

- mono-module ;
- multi-module ;
- surcharges ;
- héritage ;
- implémentations ;
- références cross-file ;
- dépendances manquantes ;
- symboles non résolus ;
- au moins deux écosystèmes de langage.

---

# 22. Métriques

MINOS doit pouvoir mesurer à terme :

```text
Indexing Time
Incremental Indexing Time
Symbol Detection Accuracy
Relationship Detection Accuracy
Search Precision
Search Recall
Query Latency
Index Size
Memory Footprint
```

Pour l'utilisation IA :

```text
Code Exploration Reduction
Estimated Tokens Avoided
Average Context Size
```

Les mesures doivent être faites sur des environnements de référence documentés.

---

# 23. Exigences non fonctionnelles

## 23.1 Précision

La précision prime sur la quantité de relations produites.

Un résultat inconnu ou non résolu doit rester inconnu ou non résolu.

## 23.2 Performance

Les requêtes sur un index existant doivent viser une latence faible.

Les objectifs chiffrés seront validés après les premiers benchmarks.

## 23.3 Maintenabilité

MINOS doit préférer des abstractions simples et testables.

La modularisation physique ne doit être introduite que lorsqu'elle apporte une séparation réellement utile.

## 23.4 Testabilité

Le cœur MINOS doit pouvoir être testé sans lancer Glean.

Un backend mémoire ou double de test de `CodeKnowledgeStore` doit être possible.

## 23.5 Open source

MINOS doit être conçu pour pouvoir devenir open source.

Les licences des dépendances structurantes doivent être vérifiées et documentées avant publication.

## 23.6 Multi-plateforme

La cible locale doit prendre en compte au minimum :

- Windows ;
- Linux ;
- macOS.

Les choix d'infrastructure doivent être évalués en conséquence.

---

# 24. Stack technique — décision ouverte

Aucune stack complète n'est figée pendant C0.

Orientations à étudier :

- Java comme langage principal de MINOS ;
- version LTS pertinente au moment de l'implémentation ;
- Maven comme système de build possible ;
- Quarkus ou framework Java léger pour une future API si nécessaire ;
- fonctionnement du cœur sans framework serveur obligatoire.

Ces choix doivent être comparés et documentés avant engagement.

Le retrait du `pom.xml` pendant C0 est volontaire : la documentation du besoin précède le bootstrap technique définitif.

---

# 25. Périmètre du premier MVP envisagé

Le MVP doit démontrer que MINOS peut :

1. enregistrer un dépôt local ;
2. détecter ses langages et son système de build ;
3. sélectionner un fournisseur d'indexation adapté ;
4. produire ou ingérer un index sémantique ;
5. normaliser les principaux symboles ;
6. normaliser les principales relations ;
7. stocker ou interroger les connaissances via une abstraction MINOS ;
8. exécuter `find_symbol` ;
9. exécuter `find_usages` ;
10. exécuter `find_implementations` ;
11. interroger dépendances et dépendants ;
12. retourner des résultats structurés et compacts ;
13. fonctionner sans LLM, sans cloud et sans NEXUS ;
14. démontrer son extensibilité avec au moins deux écosystèmes de langage distincts.

`find_callers` et `find_callees` peuvent être inclus si les fournisseurs sélectionnés offrent une précision suffisante.

Java est un candidat naturel pour le premier terrain de validation, mais ce choix doit être confirmé en C0.

Le second écosystème doit être choisi pour réellement challenger l'agnosticisme du cœur.

---

# 26. Hors périmètre du premier MVP

Ne pas implémenter prématurément :

- tous les langages ;
- un parser maison complet ;
- une résolution runtime parfaite ;
- des embeddings obligatoires ;
- une base vectorielle obligatoire ;
- une analyse par LLM obligatoire ;
- un serveur MCP complet de production ;
- une API REST de production ;
- l'intégration NEXUS ;
- l'intégration JARVIS ;
- les plugins IntelliJ ;
- les extensions VS Code ;
- GitHub ou GitLab comme source distante ;
- une analyse parfaite des appels dynamiques ;
- une plateforme cloud hébergée ;
- une analyse d'impact complète ;
- l'indexation incrémentale complète ;
- Git Intelligence complète.

---

# 27. Critères mesurables proposés pour le MVP

Les valeurs sont des propositions à challenger pendant C0 puis à affiner pendant M0.

## 27.1 Symboles

Sur les fixtures contrôlées :

- 100 % des symboles de premier niveau attendus détectés ;
- 100 % des surcharges attendues identifiables sans ambiguïté ;
- aucun doublon normalisé pour une même déclaration.

## 27.2 Références

Objectif initial :

- au moins 99 % des références internes statiquement résolvables correctement reliées sur les fixtures contrôlées.

## 27.3 Requêtes

Sur les graphes de fixtures :

- `find_usages` ;
- `find_dependencies` ;
- `find_dependents` ;

doivent retourner des résultats déterministes vérifiés automatiquement.

## 27.4 Isolation du backend

- aucun type Glean dans le domaine public MINOS ;
- aucune requête Angle exposée aux consommateurs ;
- possibilité de tester les services principaux avec un backend mémoire.

## 27.5 Local-first

- aucune dépendance cloud obligatoire ;
- aucune source envoyée vers un service externe par défaut.

## 27.6 Latence

Cibles initiales à confirmer :

```text
find_symbol p95 < 100 ms
find_usages p95 < 250 ms
requête de dépendance profondeur 1 p95 < 250 ms
```

## 27.7 Explicabilité

100 % des relations heuristiques ou dérivées doivent pouvoir exposer :

- leur origine ;
- leur niveau de confiance ;
- leurs preuves.

---

# 28. Phase C0 — cadrage avant implémentation

Avant tout développement fonctionnel important, valider :

## 28.1 Besoin

- vision ;
- utilisateurs ;
- cas d'usage ;
- frontière MINOS / NEXUS ;
- rôle dans l'écosystème ;
- valeur propre de MINOS par rapport aux solutions existantes.

## 28.2 Périmètre

- MVP ;
- hors périmètre ;
- priorités ;
- langages de validation ;
- types de projets ciblés.

## 28.3 Architecture

- modèle d'indexeurs ;
- place de SCIP ;
- place de Glean ;
- abstraction `CodeKnowledgeStore` ;
- modèle de données minimal ;
- modèle des symboles ;
- modèle des relations ;
- stratégie de stockage des métadonnées ;
- stratégie d'exposition future ;
- choix de stack technique.

## 28.4 Contraintes

- local-first ;
- multi-plateforme ;
- sécurité ;
- licences ;
- performances ;
- fonctionnement hors ligne.

## 28.5 Validation

- critères mesurables ;
- fixtures ;
- dépôts de référence ;
- benchmarks ;
- critères d'abandon ou de remplacement de SCIP/Glean.

Aucune ADR structurante ne doit passer au statut **Acceptée** avant cette validation.

---

# 29. Décisions ouvertes

1. Glean doit-il être le backend par défaut du MVP ou un backend de référence ?
2. Quel protocole de communication utiliser entre MINOS Java et Glean ?
3. Les métadonnées de projets doivent-elles être stockées dans une base légère séparée ?
4. Quels langages utiliser pour démontrer l'agnosticisme du cœur ?
5. Quel niveau minimal de support exiger d'un fournisseur ?
6. Comment définir l'identité stable d'un symbole entre deux indexations ?
7. Quel format normalisé exposer pour les relations inter-langages ?
8. Comment représenter les symboles externes et non résolus ?
9. Quelles capacités appartiennent au MVP ?
10. Quels critères rendent Glean acceptable sur Windows ?
11. Quelle granularité retenir pour l'indexation incrémentale future ?
12. Quelle stratégie adopter pour les métadonnées MINOS ?
13. Quelle version Java LTS retenir si Java est confirmé ?
14. Maven doit-il être le build officiel de MINOS ?
15. Quel framework, s'il en faut un, utiliser pour l'API future ?
16. Quelle part de la recherche générique appartient au MVP ?
17. Comment mesurer concrètement la réduction de contexte et de tokens ?

---

# 30. Critères de validation du cahier des charges

Le cahier des charges peut être considéré comme validé lorsque :

- la vision de MINOS est stable ;
- la frontière avec NEXUS est sans ambiguïté ;
- la place dans l'écosystème est comprise ;
- le périmètre du MVP est validé ;
- les fonctionnalités prioritaires sont classées ;
- les principaux modèles métier sont définis ;
- la stratégie SCIP est décidée ;
- la stratégie Glean est décidée ;
- les contraintes local-first et multi-langages sont validées ;
- la stratégie de tests est validée ;
- les critères mesurables du MVP sont définis ;
- les principales ADR structurantes sont acceptées ;
- la roadmap est alignée sur ces décisions ;
- le plan d'expérimentations M0 est prêt.

---

# 31. Gouvernance

Pendant la phase C0 :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

Toute décision structurante doit être :

1. décrite ;
2. comparée à ses alternatives ;
3. justifiée ;
4. validée ;
5. enregistrée dans une ADR si elle engage durablement l'architecture.

Le développement fonctionnel significatif de MINOS ne commencera qu'après validation du cadrage initial.