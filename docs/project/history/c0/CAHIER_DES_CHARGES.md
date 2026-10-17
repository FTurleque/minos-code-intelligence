# Cahier des charges — MINOS

Statut : **Validé — référence fonctionnelle de C0**

Date de validation : **19 juillet 2026**

Ce document constitue la **source de vérité fonctionnelle et technique de haut niveau** du projet MINOS pendant la phase C0.

La validation du cahier des charges confirme la vision, le positionnement, les objectifs, les contraintes et les exigences fonctionnelles décrites ci-dessous. Elle ne vaut pas adoption automatique des choix techniques encore explicitement marqués comme hypothèses, propositions ou décisions ouvertes.

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

L'architecture suivante reste une hypothèse de travail à valider techniquement :

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

---

# 8. Responsabilités fonctionnelles de MINOS

## 8.1 Gestion des projets

MINOS doit pouvoir gérer plusieurs projets et workspaces.

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

Le premier périmètre vise les dépôts locaux.

Extensions futures possibles : GitHub, GitLab, archives, workspaces multi-dépôts.

## 8.2 Découverte du dépôt

MINOS doit identifier notamment :

- fichiers sources ;
- fichiers de tests ;
- ressources ;
- configurations ;
- modules ;
- fichiers de build ;
- documentation.

MINOS doit respecter autant que possible `.gitignore` et prévoir `.minosignore`.

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

## 8.3 Indexation sémantique

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

## 8.4 Recherche de symboles

Capacité cible : `find_symbol`.

Recherche par nom simple, nom qualifié, type de symbole, module ou projet lorsque pertinent.

Résultat minimal : nom, type, signature, emplacement, fichier, module, principales relations et provenance.

## 8.5 Recherche d'usages

Capacité cible : `find_usages`.

Peut couvrir : références, imports, appels, héritages, implémentations, injections et autres usages pertinents.

Chaque résultat doit exposer le fichier, le symbole source, la relation, l'emplacement, le niveau de résolution et la provenance.

## 8.6 Dépendances et dépendants

Capacités cibles : `find_dependencies`, `find_dependents`.

Une profondeur configurable devra être envisagée.

## 8.7 Implémentations et appels

Capacités cibles : `find_implementations`, `find_callers`, `find_callees`.

À exposer uniquement lorsque les données disponibles sont suffisamment fiables.

## 8.8 Tests liés

Capacité cible : `get_related_tests`.

Stratégies possibles : références directes, imports, instanciations, appels de méthodes, conventions de nommage, proximité de package ou namespace.

Chaque résultat heuristique doit fournir un niveau de confiance, les raisons et les preuves.

## 8.9 Analyse d'impact

Capacité future : `analyze_impact`.

Doit distinguer impact direct, indirect, profondeur, chemin de dépendance et niveau de confiance.

L'analyse statique ne doit jamais être présentée comme une preuve complète du comportement runtime.

## 8.10 Vue d'architecture

Capacité cible : `get_architecture_overview`.

Informations possibles : modules, packages/namespaces, composants centraux, dépendances structurantes, technologies détectées, zones fortement couplées.

## 8.11 Recherche structurée générale

MINOS doit proposer à terme une recherche structurée au-delà de `find_symbol`.

Cibles possibles : recherche lexicale, par nom qualifié, type de symbole, module, package/namespace, relation, technologie ou autre métadonnée normalisée.

La recherche sémantique vectorielle reste optionnelle et ne doit pas être nécessaire au cœur du produit.

## 8.12 Indexation incrémentale

MINOS devra pouvoir éviter à terme une réindexation complète lorsque seuls certains fichiers ont changé.

Le mécanisme futur devra prendre en compte : ajouts, modifications, suppressions, empreintes de fichiers, changement de configuration de build, changement de dépendances, changement de version/configuration d'un fournisseur et invalidation du cache.

Une indexation complète devra toujours rester disponible comme stratégie de repli.

## 8.13 Git Intelligence

Extension future envisagée : historique d'un symbole, changements récents, fréquence de modification, churn et zones de forte activité.

Git Intelligence ne fait pas partie du MVP initial.

---

# 9. Modèle de données minimal envisagé

Le modèle détaillé reste à valider pendant C0.

Concepts :

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

## 9.1 Symbole

Attributs candidats :

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

Types envisagés :

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

## 9.2 Relations

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

Relations dérivées ou sémantiques supplémentaires possibles :

```text
DEPENDS_ON
INJECTS
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

`USES` ne doit pas devenir une relation primitive ambiguë si des relations plus précises peuvent être représentées.

Chaque relation doit pouvoir conserver : source, cible, type, emplacement, statut de résolution, confiance, origine et preuves.

---

# 10. Stratégie SCIP

Hypothèse actuelle : SCIP peut réduire fortement la quantité de code spécifique à développer pour l'indexation sémantique multi-langages.

MINOS doit évaluer la qualité, la couverture, la maintenance, la licence, le fonctionnement hors ligne, l'installation, les performances et le comportement sur des projets réels de chaque indexeur retenu.

SCIP ne doit pas devenir le modèle de domaine MINOS.

---

# 11. Stratégie Glean

Hypothèse actuelle : Glean peut fournir une grande partie de l'infrastructure de stockage, schémas typés, déduplication, interrogation, traversée de relations et dérivation de faits.

Points à valider : installation locale, Windows/Linux/macOS, coût de démarrage, mémoire, disque, complexité opérationnelle, intégration avec la stack MINOS, communication RPC/Thrift/CLI ou autre, mises à jour de schéma, reconstruction et comportement selon la taille des projets.

Décision finale possible : `ADOPTER`, `ADOPTER_AVEC_CONTRAINTES`, `REVOIR`, `REMPLACER`.

---

# 12. Efficacité pour les agents IA

MINOS doit réduire la quantité de contexte envoyée aux modèles.

Par défaut, une requête doit privilégier : symbole, signature, emplacement, relations, preuves et plage de code pertinente.

Le contenu complet d'un fichier ne doit être retourné que lorsqu'il est explicitement demandé.

Métriques futures :

- `Code Exploration Reduction` ;
- `Estimated Tokens Avoided` ;
- `Average Context Size` ;
- taille moyenne d'une réponse MINOS ;
- nombre de fichiers évités par requête.

---

# 13. Interfaces d'exposition envisagées

## 13.1 CLI

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

## 13.2 MCP

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

Chaque outil doit avoir une responsabilité précise, des paramètres explicites, une réponse structurée et des limites de volume.

## 13.3 API

Une API pourra être ajoutée lorsque les contrats métier seront stabilisés.

Exemples conceptuels :

```text
POST /projects
POST /projects/{id}/index
GET  /projects/{id}/symbols
GET  /projects/{id}/symbols/{symbolId}
GET  /projects/{id}/symbols/{symbolId}/usages
GET  /projects/{id}/symbols/{symbolId}/dependencies
GET  /projects/{id}/architecture
POST /projects/{id}/impact
```

Le framework serveur reste une décision différée.

---

# 14. Sécurité et confidentialité

MINOS doit pouvoir analyser des dépôts privés.

Exigences : aucune donnée externe par défaut, pas de cloud automatique, intégrations externes opt-in, chemins exclus, `.gitignore`, `.minosignore`, aucun LLM obligatoire et politique configurable pour les secrets/fichiers sensibles.

---

# 15. Stratégie de tests

Les tests sont une exigence du produit et non un ajout de fin de projet.

Fixtures minimales : projet simple mono-module, relations entre symboles, projet multi-module, héritage/implémentations, appels/surcharges, sources/tests, références non résolues/dépendances manquantes, second écosystème de langage.

Les fixtures doivent permettre d'asserter exactement les symboles et relations attendus.

Niveaux de tests : unitaires, normalisation fournisseur → MINOS, intégration backend, end-to-end d'indexation, régression des requêtes.

---

# 16. Exigences non fonctionnelles et métriques

## 16.1 Précision

La précision prime sur la quantité. Un résultat inconnu doit rester inconnu.

## 16.2 Performance

Mesurer temps d'indexation complet, futur temps incrémental, latence p50/p95, mémoire et taille disque.

## 16.3 Qualité de recherche

Lorsque pertinent : précision, rappel et stabilité du classement.

## 16.4 Explicabilité

100 % des relations heuristiques ou dérivées doivent pouvoir exposer origine, confiance et preuves.

## 16.5 Extensibilité

L'ajout d'un langage, indexeur ou backend ne doit pas nécessiter une réécriture des services métier.

## 16.6 Maintenabilité

Préférer des abstractions simples et testables. Ne pas sur-fragmenter prématurément les modules physiques.

## 16.7 Open source

MINOS doit pouvoir devenir open source. Les licences structurantes devront être vérifiées avant publication.

---

# 17. Intégrations de l'écosystème

MINOS doit rester un projet indépendant.

Intégrations futures : NEXUS, JARVIS, Alfred, Brainiac, IDE et agents externes.

L'**AI Skills Registry** reste indépendant de MINOS : MINOS ne gère pas les skills, mais des skills pourront appeler ses capacités via MCP/API/CLI.

---

# 18. Périmètre du premier MVP envisagé

Le MVP devra démontrer que MINOS peut :

1. enregistrer un dépôt local ;
2. détecter ses langages et son système de build ;
3. sélectionner un fournisseur adapté ;
4. produire ou ingérer un index sémantique ;
5. normaliser les principaux symboles ;
6. normaliser les principales relations ;
7. stocker ou interroger les connaissances via une abstraction MINOS ;
8. exécuter `find_symbol` ;
9. exécuter `find_usages` ;
10. interroger dépendances et dépendants ;
11. retourner des résultats structurés et compacts ;
12. fonctionner sans LLM, cloud ou NEXUS ;
13. démontrer son extensibilité sur au moins deux écosystèmes de langage distincts.

Le choix exact des langages reste à confirmer.

---

# 19. Hors périmètre du premier MVP

Ne pas implémenter prématurément : tous les langages, parser maison complet, runtime parfait, embeddings/base vectorielle/LLM obligatoires, MCP/API de production, intégration NEXUS/JARVIS, plugins IDE, GitHub/GitLab distant, analyse dynamique parfaite ou plateforme cloud.

---

# 20. Décisions techniques encore ouvertes après validation fonctionnelle

La validation du cahier des charges ne tranche pas automatiquement :

1. adoption définitive de SCIP ;
2. adoption définitive de Glean ;
3. forme exacte de `CodeKnowledgeStore` ;
4. stockage des métadonnées ;
5. langages du MVP ;
6. langage principal d'implémentation de MINOS ;
7. version de ce langage ;
8. système de build ;
9. framework éventuel pour une API future ;
10. identité stable des symboles ;
11. granularité de l'indexation incrémentale ;
12. critères d'acceptation précis des fournisseurs.

Ces décisions doivent être traitées par ADR et, lorsque nécessaire, validées pendant M0.

---

# 21. Gouvernance

Le cahier des charges est **validé**.

C0 reste ouvert jusqu'à validation des décisions structurantes nécessaires au lancement de M0.

Toute décision durable doit être décrite, comparée à ses alternatives, justifiée et enregistrée dans une ADR.

Le développement fonctionnel significatif ne commence qu'après clôture de C0.