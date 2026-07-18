# Cahier des charges — MINOS

Statut : **Brouillon de cadrage — à valider avant toute implémentation fonctionnelle**

Date : 19 juillet 2026

Ce document constitue la **source de vérité fonctionnelle et technique de haut niveau** du projet MINOS pendant la phase de cadrage.

Aucune implémentation importante ne doit être considérée comme engagée tant que les objectifs, le périmètre, les responsabilités, les contraintes et les critères de validation décrits ici ne sont pas explicitement validés.

---

## 1. Présentation du projet

### 1.1 Nom

**MINOS**

### 1.2 Nature du produit

MINOS est un **moteur d'intelligence du code** (*Code Intelligence Engine*).

Sa responsabilité est de construire, maintenir et exposer une représentation structurée, persistante, interrogeable et explicable d'un ou plusieurs projets logiciels.

MINOS doit permettre à un humain, un IDE, un outil CLI, un serveur MCP, une API ou un agent IA d'obtenir rapidement des informations précises sur un codebase sans devoir relire ou charger systématiquement l'intégralité du dépôt.

MINOS n'est pas :

- un chatbot ;
- un LLM ;
- un agent IA ;
- un moteur de génération de code ;
- un simple moteur de recherche plein texte ;
- un système de sélection de contexte pour LLM ;
- un produit dépendant d'un fournisseur IA particulier.

### 1.3 Positionnement conceptuel

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

MINOS répond à la question :

> **Que contient le projet, où se trouvent ses éléments, et comment sont-ils reliés ?**

NEXUS répond à la question :

> **Parmi les informations disponibles, lesquelles faut-il fournir à l'IA pour cette tâche précise ?**

MINOS doit fonctionner sans NEXUS.

NEXUS pourra consommer MINOS, mais MINOS ne devra jamais dépendre fonctionnellement de NEXUS.

---

## 2. Vision

MINOS doit devenir la couche de **Code Intelligence** de l'écosystème IA.

L'objectif à long terme est qu'un agent puisse interroger un codebase avec des questions telles que :

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
Quelle est la topologie générale de ce projet ?
```

MINOS doit privilégier :

1. la précision des informations ;
2. la qualité de la résolution des symboles ;
3. l'explicabilité des résultats ;
4. la rapidité des requêtes ;
5. l'efficacité pour les agents IA ;
6. la réduction du contexte et des tokens ;
7. l'extensibilité multi-langages ;
8. le fonctionnement local et hors ligne autant que possible.

La quantité de fonctionnalités est secondaire par rapport à la fiabilité du modèle de connaissance du code.

---

## 3. Problème à résoudre

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

MINOS doit permettre de construire une représentation persistante et réutilisable du code afin de réduire cette redécouverte.

---

## 4. Utilisateurs et consommateurs visés

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

Ces consommateurs ne doivent pas connaître les détails internes de SCIP, Glean ou d'un moteur d'indexation spécifique.

---

## 5. Principes architecturaux fondamentaux

### 5.1 Indépendance vis-à-vis des langages

MINOS doit être **agnostique du langage**.

Java, TypeScript et Python ne sont que des exemples de langages pouvant servir à la validation initiale.

Le cœur de MINOS ne doit contenir aucune liste fermée de langages supportés.

L'ajout futur de langages comme :

- Kotlin ;
- Scala ;
- JavaScript ;
- Go ;
- Rust ;
- C ;
- C++ ;
- C# ;
- Visual Basic ;
- Ruby ;
- PHP ;
- Dart ;
- SQL ;
- ou tout autre langage ;

doit pouvoir être réalisé par l'ajout ou l'intégration d'un fournisseur adapté, sans réécriture du domaine MINOS.

### 5.2 Indépendance vis-à-vis des indexeurs

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

MINOS doit sélectionner les fournisseurs selon leurs capacités et non uniquement selon le nom du langage.

### 5.3 Glean fortement réutilisé, mais non imposé au domaine

Glean est actuellement le **backend privilégié à évaluer** pour le stockage et l'interrogation des faits de code.

La stratégie envisagée est :

> **Glean-first, not Glean-locked.**

Cette formulation reste à valider formellement pendant le cadrage et les expérimentations.

Le domaine MINOS ne doit pas exposer directement :

- les types internes de Glean ;
- Angle ;
- Thrift ;
- les identifiants de faits Glean ;
- les détails de stockage Glean.

Une abstraction MINOS, provisoirement nommée :

```text
CodeKnowledgeStore
```

doit isoler le domaine du backend.

### 5.4 SCIP comme protocole privilégié, non obligatoire

SCIP est envisagé comme **protocole d'interopérabilité sémantique privilégié** lorsqu'un indexeur de qualité existe pour le langage concerné.

SCIP ne doit pas être obligatoire.

MINOS doit pouvoir intégrer un fournisseur non-SCIP si celui-ci fournit une meilleure qualité ou des capacités absentes de SCIP.

### 5.5 Fonctionnement local en priorité

Par défaut :

- aucune source ne doit être envoyée vers un service externe ;
- aucune analyse cloud ne doit être obligatoire ;
- aucun LLM ne doit être nécessaire au fonctionnement de base ;
- les dépôts privés doivent pouvoir être analysés localement ;
- les intégrations externes doivent être explicitement activées.

### 5.6 Résultats fondés sur des preuves

MINOS doit distinguer les faits déterministes des résultats dérivés ou heuristiques.

Statuts envisagés :

```text
RESOLU
PARTIELLEMENT_RESOLU
NON_RESOLU
HEURISTIQUE
```

Les valeurs techniques exposées par les API pourront rester normalisées en anglais si nécessaire, mais leur signification et leur documentation doivent être en français.

Un résultat dérivé doit pouvoir fournir :

- son origine ;
- son niveau de confiance ;
- les preuves utilisées ;
- éventuellement le chemin de relations ayant conduit au résultat.

Aucune heuristique ne doit être présentée comme une certitude.

---

## 6. Architecture conceptuelle cible

L'architecture suivante est une **hypothèse de travail à valider**, et non une architecture définitivement acceptée.

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
 SCIP  Indexeurs Glean natifs     Autres fournisseurs
  │                                  AST / LSP / LSIF / CPG
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

---

## 7. Responsabilités envisagées de MINOS

MINOS doit posséder fonctionnellement :

- le registre des projets et workspaces ;
- la découverte de la structure d'un dépôt ;
- la détection des langages ;
- la détection des systèmes de build ;
- le registre des fournisseurs d'indexation ;
- le modèle de capacités des fournisseurs ;
- l'orchestration des indexeurs ;
- le modèle de domaine normalisé ;
- le modèle de provenance ;
- le modèle de confiance ;
- les services de requêtes ;
- les relations dérivées spécifiques à MINOS ;
- l'analyse des tests liés ;
- l'analyse d'impact ;
- la vue d'architecture ;
- la génération de réponses compactes ;
- les contrats CLI ;
- les contrats MCP ;
- les contrats API ;
- les contrats d'intégration avec NEXUS.

MINOS ne doit pas obligatoirement posséder :

- son propre parser pour chaque langage ;
- son propre compilateur ;
- son propre moteur de résolution sémantique complet ;
- son propre moteur de stockage de graphe si une solution open source adaptée existe.

---

## 8. Capacités fonctionnelles cibles

### 8.1 Gestion des projets

MINOS doit pouvoir gérer plusieurs projets.

Informations minimales envisagées :

```text
id
nom
chemin local
type de projet
langages détectés
technologies détectées
systèmes de build détectés
date de dernière indexation
état de l'index
version du schéma
```

Le premier périmètre doit viser les dépôts locaux.

Des extensions futures pourront supporter :

- GitHub ;
- GitLab ;
- archives ;
- workspaces multi-dépôts.

### 8.2 Découverte du dépôt

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

et prévoir son propre mécanisme :

```text
.minosignore
```

Répertoires à exclure par défaut selon le contexte :

```text
.git/
target/
build/
node_modules/
.idea/
out/
dist/
```

### 8.3 Indexation sémantique

MINOS doit pouvoir exploiter un ou plusieurs fournisseurs capables d'extraire ou fournir :

- définitions ;
- références ;
- implémentations ;
- relations de type ;
- relations d'appel ;
- relations inter-fichiers ;
- relations inter-modules ;
- éventuellement relations inter-dépôts ;
- éventuellement flux de contrôle ;
- éventuellement flux de données.

Toutes les capacités ne seront pas disponibles pour tous les langages.

MINOS doit connaître les capacités réelles de chaque fournisseur.

### 8.4 Recherche de symboles

Capacité cible :

```text
find_symbol
```

Doit permettre de rechercher par :

- nom simple ;
- nom qualifié ;
- type de symbole ;
- éventuellement module ou projet.

Résultat minimal :

- nom ;
- type ;
- signature ;
- emplacement ;
- fichier ;
- module ;
- principales relations ;
- provenance.

### 8.5 Recherche d'usages

Capacité cible :

```text
find_usages
```

Doit permettre d'identifier selon les informations disponibles :

- références ;
- imports ;
- appels ;
- héritages ;
- implémentations ;
- injections ;
- autres usages pertinents.

Chaque résultat doit exposer :

- le fichier ;
- le symbole source ;
- la relation ;
- l'emplacement ;
- le niveau de résolution ;
- la provenance.

### 8.6 Dépendances et dépendants

Capacités cibles :

```text
find_dependencies
find_dependents
```

Définition :

```text
dependencies = ce dont le symbole dépend

dependents = ce qui dépend du symbole
```

Une profondeur configurable devra être envisagée.

### 8.7 Implémentations et appels

Capacités cibles :

```text
find_implementations
find_callers
find_callees
```

Ces fonctionnalités devront être exposées uniquement lorsque les données du fournisseur sont suffisamment fiables.

### 8.8 Tests liés

Capacité cible :

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

### 8.9 Analyse d'impact

Capacité cible future :

```text
analyze_impact
```

MINOS devra distinguer :

- impact direct ;
- impact indirect ;
- profondeur ;
- chemin de dépendance ;
- niveau de confiance.

L'analyse statique ne devra jamais être présentée comme une preuve complète du comportement runtime.

### 8.10 Vue d'architecture

Capacité cible :

```text
get_architecture_overview
```

Informations possibles :

- modules ;
- packages / namespaces ;
- composants centraux ;
- dépendances structurantes ;
- technologies détectées ;
- zones fortement couplées.

Les inférences architecturales devront être distinguées des faits détectés directement.

---

## 9. Modèle de données minimal envisagé

Le modèle reste à valider.

Concepts envisagés :

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

### 9.1 Symbole

Un symbole normalisé pourrait contenir :

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

Le modèle doit rester extensible.

### 9.2 Relation

Relations factuelles envisagées :

```text
DECLARES
CONTAINS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
```

Relations dérivées possibles :

```text
DEPENDS_ON
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Une relation doit pouvoir conserver :

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

---

## 10. Stratégie SCIP

Hypothèse actuelle : SCIP peut réduire fortement la quantité de code spécifique à développer pour l'indexation sémantique multi-langages.

MINOS doit évaluer :

- la qualité des indexeurs disponibles ;
- leur couverture fonctionnelle ;
- leur maintenance ;
- leur licence ;
- leur fonctionnement hors ligne ;
- leur facilité d'installation ;
- leur performance ;
- leur capacité à fonctionner sur des projets réels.

SCIP ne doit pas devenir le modèle de domaine MINOS.

---

## 11. Stratégie Glean

Hypothèse actuelle : Glean peut fournir une grande partie de l'infrastructure de :

- stockage des faits de code ;
- schémas typés ;
- déduplication ;
- interrogation ;
- traversée de relations ;
- dérivation de faits ;
- persistance de connaissances spécifiques à MINOS.

Les points à valider avant adoption sont notamment :

- installation locale ;
- Windows ;
- Linux ;
- macOS ;
- coût de démarrage ;
- consommation mémoire ;
- taille disque ;
- complexité opérationnelle ;
- gestion des processus ;
- intégration depuis Java ;
- communication RPC / Thrift / CLI ;
- mises à jour de schémas ;
- reconstruction après corruption ;
- fonctionnement sur petits projets ;
- fonctionnement sur gros projets.

La décision finale pourra être :

```text
ADOPTER
ADOPTER_AVEC_CONTRAINTES
REVOIR
REMPLACER
```

---

## 12. Efficacité pour les agents IA

MINOS doit être conçu pour réduire la quantité de contexte envoyée aux modèles.

Par défaut, une requête doit privilégier :

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

Exemple de résultat compact cible :

```json
{
  "symbol": "DocumentIngestionService",
  "kind": "CLASS",
  "qualifiedName": "fr.ariane.chatbot.document.DocumentIngestionService",
  "location": {
    "file": "src/main/java/.../DocumentIngestionService.java",
    "startLine": 12,
    "endLine": 120
  },
  "relationships": {
    "dependencies": 3,
    "dependents": 2,
    "relatedTests": 1
  }
}
```

---

## 13. Interfaces d'exposition envisagées

### 13.1 CLI

Commandes envisagées à terme :

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

La CLI n'est pas prioritaire tant que le cœur des requêtes n'est pas validé.

### 13.2 MCP

MINOS devra être conçu pour devenir un serveur MCP de qualité, mais MCP restera une couche d'exposition.

Outils envisagés :

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

Chaque outil devra :

- avoir une responsabilité précise ;
- accepter des paramètres explicites ;
- retourner une réponse structurée ;
- proposer des limites de résultats ;
- éviter les réponses excessivement volumineuses.

### 13.3 API

Une API pourra être ajoutée lorsque les contrats métier seront stabilisés.

Le framework serveur ne doit pas être choisi définitivement pendant la phase de cadrage.

---

## 14. Sécurité et confidentialité

MINOS doit pouvoir analyser des dépôts privés.

Exigences :

- aucune donnée envoyée vers un service externe par défaut ;
- aucune analyse cloud automatique ;
- intégrations externes explicitement activées ;
- possibilité d'exclure des fichiers ou chemins ;
- respect de `.gitignore` autant que possible ;
- support prévu de `.minosignore` ;
- aucune dépendance obligatoire à un LLM ;
- secrets et fichiers sensibles exclus lorsque cela est pertinent et configurable.

---

## 15. Exigences non fonctionnelles

### 15.1 Précision

La précision prime sur la quantité de relations produites.

Un résultat inconnu ou non résolu doit rester inconnu ou non résolu.

### 15.2 Performance

Les requêtes sur un index existant doivent viser une latence faible.

Les objectifs chiffrés seront fixés après les premiers benchmarks.

### 15.3 Extensibilité

L'ajout d'un langage, d'un indexeur ou d'un backend ne doit pas obliger à modifier les services métier existants.

### 15.4 Maintenabilité

MINOS doit préférer des abstractions simples et testables.

La modularisation Maven physique ne doit être introduite que lorsqu'elle apporte une vraie séparation utile.

### 15.5 Testabilité

Le cœur MINOS doit pouvoir être testé sans lancer Glean.

Une implémentation mémoire ou un double de test de `CodeKnowledgeStore` doit être possible.

### 15.6 Open source

MINOS doit être conçu pour pouvoir devenir open source.

Les licences de toutes les dépendances structurantes devront être vérifiées et documentées avant publication.

---

## 16. Périmètre du premier MVP envisagé

Le MVP devra démontrer que MINOS peut :

1. enregistrer un dépôt local ;
2. détecter ses langages et son système de build ;
3. sélectionner un fournisseur d'indexation adapté ;
4. produire ou ingérer un index sémantique ;
5. normaliser les principaux symboles ;
6. normaliser les principales relations ;
7. stocker ou interroger les connaissances via une abstraction MINOS ;
8. exécuter `find_symbol` ;
9. exécuter `find_usages` ;
10. interroger les dépendances et dépendants ;
11. retourner des résultats structurés et compacts ;
12. fonctionner sans LLM, sans cloud et sans NEXUS ;
13. démontrer son extensibilité avec au moins deux écosystèmes de langage distincts.

Le choix exact des deux langages de validation reste à confirmer.

Java est un candidat naturel pour le premier test compte tenu de l'environnement existant, mais MINOS ne doit pas devenir Java-centric.

---

## 17. Hors périmètre du premier MVP

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
- l'analyse parfaite des appels dynamiques ;
- une plateforme cloud hébergée.

---

## 18. Phase C0 — Cadrage avant implémentation

Avant tout développement fonctionnel important, les éléments suivants doivent être validés :

### 18.1 Besoin

- vision ;
- utilisateurs ;
- cas d'usage ;
- frontière MINOS / NEXUS ;
- valeur propre de MINOS.

### 18.2 Périmètre

- MVP ;
- hors périmètre ;
- priorités ;
- langages de validation ;
- types de projets ciblés.

### 18.3 Architecture

- modèle d'indexeurs ;
- place de SCIP ;
- place de Glean ;
- abstraction `CodeKnowledgeStore` ;
- modèle de données minimal ;
- modèle des symboles ;
- modèle des relations ;
- stratégie de stockage des métadonnées ;
- stratégie d'exposition future.

### 18.4 Contraintes

- local-first ;
- multi-plateforme ;
- sécurité ;
- licences ;
- performances ;
- fonctionnement hors ligne.

### 18.5 Validation

- critères mesurables du MVP ;
- jeux de données de test ;
- dépôts de référence ;
- benchmarks ;
- critères d'abandon ou de remplacement de SCIP/Glean.

Aucune ADR structurante ne doit passer au statut **Acceptée** avant cette validation.

---

## 19. Décisions ouvertes

Les points suivants restent à trancher :

1. Glean doit-il être le backend par défaut du MVP ou seulement un backend de référence ?
2. Quel protocole de communication utiliser entre MINOS Java et Glean ?
3. Les métadonnées de projets doivent-elles être stockées dans une base légère séparée ?
4. Quels langages utiliser pour démontrer réellement l'agnosticisme du cœur ?
5. Quel niveau minimal de support doit être exigé d'un fournisseur ?
6. Comment définir l'identité stable d'un symbole entre deux indexations ?
7. Quel format normalisé exposer pour les relations inter-langages ?
8. Comment représenter les symboles externes et non résolus ?
9. Quelles capacités doivent appartenir au MVP et lesquelles doivent être repoussées ?
10. Quels critères rendent Glean opérationnellement acceptable sur Windows ?
11. Quelle granularité retenir pour l'indexation incrémentale future ?
12. Quel framework, s'il en faut un, utiliser pour l'API future ?

---

## 20. Critères de validation du cahier des charges

Le cahier des charges pourra être considéré comme validé lorsque :

- la vision de MINOS est stable ;
- la frontière avec NEXUS est sans ambiguïté ;
- le périmètre du MVP est validé ;
- les fonctionnalités prioritaires sont classées ;
- les principaux modèles métier sont définis ;
- la stratégie SCIP est décidée ;
- la stratégie Glean est décidée ;
- les contraintes local-first et multi-langages sont validées ;
- les critères mesurables du MVP sont définis ;
- les principales ADR structurantes sont acceptées ;
- la roadmap est alignée sur ces décisions.

---

## 21. Règle de gouvernance du projet

Pendant la phase de cadrage :

> **Documenter d'abord, décider ensuite, implémenter en dernier.**

Toute décision structurante doit être :

1. décrite ;
2. comparée à ses alternatives ;
3. justifiée ;
4. validée ;
5. enregistrée dans une ADR si elle engage durablement l'architecture.

Le développement fonctionnel significatif de MINOS ne commencera qu'après validation du cadrage initial.