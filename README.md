# MINOS

**MINOS** est un moteur d’intelligence du code (*Code Intelligence Engine*) conçu pour construire une compréhension structurée, persistante, interrogeable et explicable de projets logiciels.

MINOS fonctionne **localement**, reste **agnostique du langage**, indépendant des fournisseurs d’IA et découplé des moteurs d’indexation ou de stockage utilisés en interne.

MINOS n’est ni un chatbot, ni un LLM, ni un simple moteur de recherche textuelle.

Il vise notamment à répondre à des questions comme :

- Où est défini ce symbole ?
- Qui l’utilise, l’appelle, l’étend ou l’implémente ?
- De quoi dépend-il et qu’est-ce qui dépend de lui ?
- Quels tests lui sont liés ?
- Quelle est la topologie du projet et quels modules sont centraux dans le graphe observé ?
- Quels éléments peuvent potentiellement être impactés par une modification ?
- Quelles relations inter-dépôts sont réellement prouvables ?
- Quels fichiers et zones ont été modifiés récemment dans Git ?

## Position dans l’écosystème

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

MINOS reste autonome et ne dépend fonctionnellement ni de JARVIS, ni de NEXUS, ni d’Alfred, ni de Brainiac.

Voir [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md).

## État actuel

Les jalons **C0 à M12 sont terminés, validés et livrés**.

### M11 — API publique

```text
head validé   fae552e8e6f2aa66c327fb80485f5bad448d7520
merge         3780785f167cf373dfe0e9cf34f3c3862e87b868
sources       154 main / 79 test
tests         214/214 PASS
```

Contrat :

```text
com.minos.api.MinosApi
com.minos.api.LocalMinosApi
CONTRACT_VERSION = 1
```

### M12 — Multi-dépôts et intelligence Git

```text
head validé   6c771909e0b97b49fbd8e49090522d8a6c0b53aa
merge         3bc6cc364b6d7d651c1c9ab3a93ecac28ce02e86
sources       158 main / 83 test
tests         221/221 PASS
```

M12 apporte : workspaces multi-projets, résolution cross-repository exacte, inspection Git Java pure via JGit, historique borné, activité par fichier/auteur/zone et contrat public additif.

## M13 — Intégration NEXUS

M13 est **intégralement implémenté** sur la branche :

```text
m13/nexus-integration
```

Suivi : issue #37 / PR Draft #38.

Compagnon NEXUS : `FTurleque/nexus-context-engine` issue #11 / PR Draft #12.

### Principe

MINOS et NEXUS gardent leurs responsabilités :

```text
MINOS
  faits / symboles / relations / provenance / preuves
                    │
                    │ JSON local versionné
                    ▼
NEXUS
  index / recherche / ranking / sélection / budget / ContextBundle
```

NEXUS compile en Java 21 et MINOS impose Java 24. M13 utilise donc une frontière **inter-processus**, sans dépendance Maven croisée.

### Export MINOS

Contrat :

```text
NexusExportContract.CONTRACT_VERSION = 1
NexusExportContract.PRODUCER = MINOS
```

Commande :

```powershell
java -Dminos.home=<home> `
  -jar .\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar `
  nexus-export --root <project-root>
```

Le JSON exporte uniquement le snapshot actif et conserve :

- identité projet/snapshot ;
- symboles locaux ;
- chemins relatifs sûrs ;
- kinds, noms, signatures, langues et plages ;
- origine et qualité d’identité ;
- relations résolues ;
- nature, confiance et preuves ;
- limitations explicites.

Les `fileId` SCIP stables sont reconstruits vers les chemins réels avec la même identité :

```text
file:<sha256(projectId + US + relativePath)>
```

Aucun faux chemin n’est produit lorsqu’un identifiant reste non résolu.

### Consommation NEXUS

NEXUS utilise un `MinosCodeIndexImporter` optionnel derrière son contrat `CodeIndexImporter`.

Configuration côté NEXUS :

```text
NEXUS_MINOS_JAR=<MINOS shaded jar>
NEXUS_MINOS_JAVA=<java 24 executable>
NEXUS_MINOS_HOME=<MINOS home>                  optionnel
NEXUS_MINOS_TIMEOUT_SECONDS=<1..300>           optionnel
```

L’intégration est désactivée par défaut. Lorsqu’elle est active :

- NEXUS lance MINOS localement avec Java 24 ;
- valide la version du contrat et la racine projet ;
- importe seulement les kinds/relations ayant une équivalence explicite ;
- conserve `sourceProvider=minos` ;
- injecte ces faits avant l’import SCIP direct ;
- ne modifie ni `SearchService`, ni le ranking, ni `DefaultContextBuilder`.

MINOS ne calcule jamais le budget ou le ranking NEXUS.

### Qualification M13

MINOS :

```text
NexusExportContractTest
NexusExportIntegrationTest
```

NEXUS :

```text
MinosCodeIndexImporterTest
FakeMinosExportMain
MinosRealIntegrationTest
```

Replay MINOS attendu :

```text
M13 MINOS export: contract=1, project=<uuid>, snapshot=<snapshot>, symbols=<n>, relations=<n>
```

Replay inter-dépôt attendu :

```text
M13 MINOS->NEXUS: symbols=<n>, relations=<n>, nexus-symbols=<n>, search=<n>
```

La preuve réelle doit montrer `GreetingPort` dans NEXUS avec `sourceProvider=minos` puis dans les résultats de recherche.

Voir [`docs/m13/NEXUS_INTEGRATION.md`](docs/m13/NEXUS_INTEGRATION.md) et [`docs/m13/DECISION_M13.md`](docs/m13/DECISION_M13.md).

## Serveur MCP local

M10 reste disponible avec **15 tools read-only** via STDIO :

```text
minos_project_structure
minos_index_status
minos_search_code
minos_find_symbols
minos_find_usages
minos_find_implementations
minos_find_callers
minos_find_callees
minos_dependencies
minos_dependents
minos_related_tests
minos_symbol_context
minos_module_context
minos_architecture
minos_impact
```

Après :

```powershell
.\mvnw.cmd clean package
```

le build produit notamment :

```text
target/minos-code-intelligence-0.1.0-SNAPSHOT-all.jar
```

Le serveur MCP se lance avec :

```powershell
java -cp .\target\minos-code-intelligence-0.1.0-SNAPSHOT-all.jar com.minos.mcp.MinosMcpServer
```

Le home MINOS est résolu dans cet ordre :

```text
-Dminos.home=<path>
MINOS_HOME=<path>
~/.minos
```

## Stack technique

```text
Langage        Java 24
Build          Apache Maven 3.9.x
Wrapper        Maven Wrapper 3.3.4 / Maven 3.9.16
SCIP           scip-java-bindings 0.9.0
MCP SDK        Java MCP SDK 2.0.0
MCP transport  STDIO local
API M11        Java in-process, contrat v1
API M12        Java in-process additive, contrat multi-repo v1
Git M12        Eclipse JGit 7.6.0.202603022253-r
NEXUS M13      JSON local inter-processus, contrat v1
Framework      Aucun framework serveur dans le cœur
```

## Architecture

```text
Repository / Workspace
        │
        ├── Git Intelligence
        ▼
Project Discovery / Registry
        │
        ├── Workspace Intelligence
        ▼
Fingerprint / Invalidation
        │
        ▼
Indexer Registry / Negotiation
        │
        ▼
Incremental Planner
  NONE / FULL / INCREMENTAL
        │
        ▼
Indexing Lifecycle / Atomic Promotion
        │
        ▼
MINOS Normalization
        │
        ▼
CodeKnowledgeStore
        │
        ▼
MINOS Query Services
        │
        ├── Symbol Intelligence
        ├── Relationship Intelligence
        ├── Compact Context
        ├── Related Tests
        ├── Architecture Intelligence
        └── Impact Analysis
        │
        ├──────────┬──────────┬──────────┬───────────────┐
        ▼          ▼          ▼          ▼               ▼
       CLI      MCP STDIO   API M11   API M12       NEXUS export
```

Principe structurant :

> **MINOS-first, Glean-optional.**

## Principes d’architecture

- faits, dérivations et heuristiques sont distingués explicitement ;
- toute dérivation importante conserve provenance et preuves ;
- les limitations d’un fournisseur ne deviennent jamais des garanties ;
- une portée incrémentale n’est jamais exécutée sans capacité fournisseur qualifiée ;
- l’analyse d’impact décrit des impacts potentiels observables, jamais une certitude runtime ;
- CLI, MCP et API restent des couches d’exposition ;
- une relation cross-repository requiert une preuve d’identité exacte et unique ;
- l’activité Git n’est pas une mesure automatique d’importance architecturale ;
- M13 ne déplace ni ranking, ni sélection, ni budget de contexte depuis NEXUS vers MINOS ;
- MINOS reste utilisable sans NEXUS.

## Documents de référence

- [`docs/STATUS.md`](docs/STATUS.md) — état opérationnel et porte active ;
- [`docs/ROADMAP.md`](docs/ROADMAP.md) — feuille de route ;
- [`docs/CAHIER_DES_CHARGES.md`](docs/CAHIER_DES_CHARGES.md) — cahier des charges ;
- [`docs/MVP.md`](docs/MVP.md) — MVP ;
- [`docs/ECOSYSTEME.md`](docs/ECOSYSTEME.md) — positionnement ;
- [`docs/m10/MCP_SERVER.md`](docs/m10/MCP_SERVER.md) — serveur MCP ;
- [`docs/m11/API.md`](docs/m11/API.md) — API publique ;
- [`docs/m12/MULTI_REPO_GIT.md`](docs/m12/MULTI_REPO_GIT.md) — multi-dépôts et Git ;
- [`docs/m13/NEXUS_INTEGRATION.md`](docs/m13/NEXUS_INTEGRATION.md) — intégration NEXUS ;
- [`docs/m13/DECISION_M13.md`](docs/m13/DECISION_M13.md) — décision M13 ;
- [`docs/adr/`](docs/adr/) — décisions d’architecture.

## Règle de développement

> **Mesurer avant d’industrialiser.**

MINOS doit produire des faits, profils de qualité et décisions documentées avant d’ajouter une infrastructure ou une sémantique non nécessaire à la prochaine porte de décision.
