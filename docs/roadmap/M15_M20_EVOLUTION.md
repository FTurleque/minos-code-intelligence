# MINOS — Roadmap d'évolution M15 à M20

Statut : **PLANIFIÉ — aucun jalon M15 à M20 n'est encore acquis**

Cette roadmap prolonge les jalons C0 à M14 livrés et transforme MINOS d'un moteur local fonctionnel en une plateforme de Code Intelligence industrialisée, scalable, extensible, intégrée à l'IDE et capable d'analyses avancées puis sémantiques.

Elle complète [`../ROADMAP.md`](../ROADMAP.md), qui reste la vue produit officielle.

## Principes non négociables

Les évolutions M15 à M20 doivent préserver les invariants déjà établis :

- MINOS reste **local-first** par défaut ;
- le cœur reste indépendant d'un LLM et d'un fournisseur d'IA ;
- les faits, dérivations et heuristiques restent explicitement distingués ;
- toute information dérivée conserve provenance, confiance et preuves ;
- aucune capacité fournisseur absente n'est inventée ;
- les contrats publics ne fuient ni SCIP, ni un backend de stockage, ni un protocole d'exposition ;
- CLI, API, MCP, NEXUS et futures intégrations IDE consomment le même cœur métier ;
- l'analyse d'impact reste conservatrice tant qu'une preuve runtime exhaustive n'existe pas ;
- les choix de backend sont gouvernés par des mesures, pas par préférence technologique ;
- un jalon n'est terminé qu'après validation reproductible sur un SHA exact.

## Vue d'ensemble

```text
M15  Industrialiser le cœur
  ↓
M16  Prouver la scalabilité
  ↓
M17  Généraliser discovery + providers
  ↓
M18  Intégrer MINOS à IntelliJ
  ↓
M19  Ajouter l'intelligence de programme avancée
  ↓
M20  Ajouter la recherche sémantique hybride
```

Les dépendances ne signifient pas que toute exploration doit attendre le jalon précédent. Elles définissent l'ordre de **promotion en capacité produit supportée**.

---

# M15 — Industrialisation du Core Engine

## Question produit

> MINOS peut-il devenir une plateforme modulaire et durable sans modifier les contrats fonctionnels déjà livrés ?

## Objectif

Supprimer les limites structurelles apparues après M14 : monolithe Maven, composition dispersée, chemin MCP → CLI → moteur, rechargement répété du snapshot et responsabilités de persistance trop concentrées.

M15 doit améliorer l'architecture interne **sans régression fonctionnelle volontaire**.

## Architecture cible

```text
                         ┌──────────────────────┐
                         │   minos-domain       │
                         └──────────┬───────────┘
                                    │
                         ┌──────────▼───────────┐
                         │   minos-engine       │
                         └──────────┬───────────┘
                                    │
              ┌─────────────────────┼─────────────────────┐
              │                     │                     │
    ┌─────────▼────────┐   ┌────────▼─────────┐  ┌────────▼─────────┐
    │ storage-local    │   │ provider-scip    │  │ integration/git  │
    └─────────┬────────┘   └────────┬─────────┘  └────────┬─────────┘
              │                     │                     │
              └─────────────────────┼─────────────────────┘
                                    │
                         ┌──────────▼───────────┐
                         │  MinosApplication    │
                         │  composition root    │
                         └──────┬────┬────┬─────┘
                                │    │    │
                              CLI   API  MCP/NEXUS
```

Le découpage Maven exact devra être validé par ADR avant migration. L'objectif est d'imposer des frontières de compilation, pas de multiplier artificiellement les modules.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M15-S1 | Baseline de non-régression | Capturer contrats, temps et comportements M14 actuels | replay CLI/API/MCP/indexation |
| M15-S2 | Maven multi-module | Séparer domaine, moteur, adapters, exposition et application | build reactor + tests de frontières |
| M15-S3 | `MinosApplication` | Introduire un composition root unique et des services applicatifs stables | CLI/API/MCP utilisent le même objet racine |
| M15-S4 | Découplage MCP | Supprimer le routage métier MCP → CLI → `MinosLauncher` | MCP appelle les services applicatifs directement |
| M15-S5 | Résolution projet commune | Mutualiser résolution UUID/nom et erreurs d'ambiguïté | 0 implémentation divergente |
| M15-S6 | Persistance décomposée | Séparer repository, active pointer, codec, intégrité et rétention | compatibilité snapshots historiques |
| M15-S7 | Cache snapshot actif | Réutiliser une vue chargée par `(projectId, snapshotId)` | invalidation prouvée à la promotion |
| M15-S8 | Indexes de requête | Indexer symboles, fichiers, occurrences et relations | résultats strictement identiques à la baseline |
| M15-S9 | Qualité continue | JaCoCo + règles ciblées + vérification des frontières | seuils documentés et verts |
| M15-S10 | CI PR | Vérification automatique de chaque PR | `clean verify` bloquant avant merge |
| M15-S11 | Cohérence documentaire | Vérifier/générer les faits calculables de documentation | versions/tools/formats non divergents |

## Indexes minimaux à introduire

```text
symbolId                    -> Symbol
normalizedName              -> Symbols
qualifiedName               -> Symbols
fileId                      -> Symbols
resolvedSymbolId            -> Occurrences
sourceEntity                -> Relationships
targetEntity                -> Relationships
relationshipKind            -> Relationships
```

Ces indexes doivent rester reconstruisibles depuis le snapshot actif.

## Persistance cible

Responsabilités à rendre indépendantes :

```text
SnapshotRepository
SnapshotManifestRepository
ActiveSnapshotRepository
SnapshotCodec
  ├── V1
  ├── V2
  └── versions futures
SnapshotIntegrityService
SnapshotRetentionService
```

M15 ne doit pas imposer une migration prématurée vers SQLite, Lucene, RocksDB ou un autre backend.

## Portes M15

M15 n'est terminé que si :

- toutes les fonctionnalités M14 continuent de passer leurs replays ;
- CLI, API et MCP retournent les mêmes résultats fonctionnels avant/après refactor ;
- le MCP n'exécute plus la CLI comme couche de service ;
- le domaine ne dépend physiquement d'aucun adapter ou protocole ;
- un snapshot actif n'est plus désérialisé intégralement à chaque requête répétée ;
- la promotion d'un snapshot invalide correctement les caches ;
- les snapshots historiques lisibles restent compatibles ou disposent d'une migration explicite ;
- les PR disposent d'une CI automatique bloquante ;
- les seuils de couverture sont définis par criticité et non par pourcentage uniforme arbitraire.

## Hors périmètre

- nouveau langage ;
- nouveau backend persistant choisi sans benchmark ;
- plugin IDE ;
- CPG/data-flow ;
- embeddings.

---

# M16 — Scalabilité et performance à grande échelle

## Question produit

> MINOS conserve-t-il des performances et une empreinte acceptables lorsqu'il passe de projets moyens à de grands codebases ?

## Objectif

Construire une campagne de benchmarks reproductible puis faire évoluer le stockage et les indexes uniquement à partir des résultats mesurés.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M16-S1 | Harness benchmark | Scénarios reproductibles et machine documentée | p50/p95/p99 + mémoire + disque |
| M16-S2 | Datasets d'échelle | Fixtures synthétiques et dépôts réels gradués | tailles et vérités terrain documentées |
| M16-S3 | Query benchmark | Mesurer symboles/usages/relations/architecture/impact | seuils p95 définis |
| M16-S4 | MCP sustained load | Mesurer suites de requêtes répétées | pas de reload complet systématique |
| M16-S5 | Indexing benchmark | Mesurer FULL/NONE et incrémental lorsque qualifié | débit, CPU, mémoire, I/O |
| M16-S6 | Memory/disk profile | Identifier les structures dominantes | profil reproductible |
| M16-S7 | Backend decision | Comparer backend mémoire indexé à des alternatives | ADR fondée sur mesures |
| M16-S8 | Optimisations retenues | Implémenter uniquement les gains prouvés | seuils d'acceptation atteints |
| M16-S9 | Retention/compaction | Politique de nettoyage des snapshots et runs | croissance disque bornée |

## Échelles minimales de campagne

La campagne doit couvrir plusieurs ordres de grandeur, par exemple :

```text
10 000 fichiers
50 000 fichiers
100 000 fichiers

100 000 symboles
1 000 000 symboles
5 000 000 occurrences
10 000 000 occurrences
plusieurs millions de relations
```

Les datasets exacts pourront être adaptés à la réalité des providers et des machines de qualification.

## Mesures obligatoires

```text
cold_start_time
snapshot_load_time
query_index_build_time
warm_query_latency p50/p95/p99
peak_heap
retained_heap
process_rss
snapshot_disk_size
indexes_disk_size
FULL_index_duration
files_per_second
loc_per_second
MCP_sequence_latency
```

Au minimum pour :

```text
find-symbol
find-usages
dependencies
dependents
search
architecture
impact
related-tests
```

## Stratégie backend

L'ordre de décision doit être :

```text
1. mesurer le backend mémoire indexé
2. identifier précisément le goulot
3. prototyper une alternative adaptée au goulot
4. mesurer sur les mêmes datasets
5. décider par ADR
```

Candidates possibles, non présélectionnés :

- stockage fichier + indexes mémoire ;
- SQLite ;
- Lucene pour certaines recherches ;
- backend clé/valeur embarqué ;
- backend mixte.

## Portes M16

M16 n'est terminé que si :

- les benchmarks sont reproductibles et versionnés ;
- les seuils p95/p99 du produit sont explicites ;
- aucune optimisation ne dégrade la déterminisme ou l'exactitude ;
- l'utilisation répétée via MCP ne reconstruit pas inutilement toute la connaissance ;
- le disque est borné par une politique de rétention documentée ;
- un choix de backend éventuel est justifié par comparaison objective ;
- les grands datasets ne provoquent pas une explosion mémoire silencieuse.

---

# M17 — Provider & Discovery Platform

## Question produit

> MINOS peut-il devenir réellement extensible à de nouveaux langages et systèmes de build sans ajouter de branches spécifiques dans le cœur ?

## Objectif

Transformer la découverte et l'indexation en plateforme d'extensions explicites, puis élargir le support produit au-delà de Java/Maven et TypeScript/NPM.

## Architecture cible

```text
ProjectDiscovery
      │
      ├── ProjectDetector SPI
      ├── BuildSystemDetector SPI
      ├── SourceRootDetector SPI
      └── LanguageDetector SPI

IndexerRegistry
      │
      └── IndexerProvider SPI
             ├── scip-java
             ├── scip-typescript
             └── futurs providers
```

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M17-S1 | Discovery SPI | Détecteurs composables sans `if language` central | tests de plugins |
| M17-S2 | Provider SPI | Lifecycle runtime/index/normalize uniforme | provider test kit |
| M17-S3 | Capability model v2 | Capacités plus fines et qualifiées | aucune capacité implicite |
| M17-S4 | Gradle | Découverte Java/Kotlin Gradle | fixtures mono/multi-module |
| M17-S5 | JS workspace ecosystems | npm workspaces + pnpm/yarn selon qualification | fixtures monorepo |
| M17-S6 | Nouveau langage JVM | Kotlin prioritaire si provider viable | symboles/usages/relations qualifiés |
| M17-S7 | Nouveau langage hors JVM | Python prioritaire si provider viable | mêmes contrats MINOS |
| M17-S8 | Provider conformance kit | Suite standardisée de qualification | score/profil par provider |
| M17-S9 | Installation provider | Installation/doctor/tools extensibles | aucune logique hardcodée par commande |

L'ordre Kotlin/Python peut être révisé par disponibilité réelle des providers et valeur utilisateur ; la plateforme SPI reste la capacité obligatoire.

## Provider conformance kit

Chaque provider supporté devra être qualifié sur :

```text
symbols
identity quality
references
unresolved references
implementations / inheritance
calls
multi-module / workspace
test sources
partial build behavior
incremental capability
position encoding
provider runtime installation
```

Chaque capacité doit être classée explicitement, par exemple :

```text
FULL
PARTIAL
EXPERIMENTAL
UNSUPPORTED
```

## Portes M17

M17 n'est terminé que si :

- ajouter un provider ne nécessite pas de modifier le domaine ;
- ajouter un build system ne nécessite pas de coder une branche dans un orchestrateur central ;
- le provider test kit produit un profil reproductible ;
- les limitations sont exposées à CLI/API/MCP ;
- au moins un écosystème supplémentaire au périmètre M14 est qualifié de bout en bout ;
- Java/TypeScript existants passent toujours leur qualification historique.

---

# M18 — MINOS for IntelliJ

## Question produit

> Un développeur peut-il exploiter MINOS quotidiennement dans son IDE sans passer en permanence par la CLI ou par un agent IA ?

## Objectif

Livrer une intégration IntelliJ native centrée sur navigation, architecture, impact et état d'indexation, en consommant les contrats publics MINOS sans réimplémenter le moteur.

## Principes

- le plugin IntelliJ est un **client** de MINOS ;
- aucun calcul métier majeur ne doit être dupliqué dans le plugin ;
- le plugin doit fonctionner sans LLM ;
- les actions peuvent utiliser une API locale/processus MINOS, mais le protocole retenu doit être versionné ;
- l'IDE ne devient pas une condition pour utiliser MINOS.

## Expérience cible

```text
MINOS Tool Window
├── Project
│   ├── provider
│   ├── snapshot
│   ├── READY / STALE / FAILED
│   └── reindex
├── Architecture
│   └── graphe interactif
├── Symbols
├── Usages
├── Dependencies
├── Related Tests
├── Impact
└── Git Activity
```

Actions contextuelles :

```text
Find MINOS usages
Find dependents
Find implementations
Related tests
Analyze impact
Show in architecture
Copy symbol id / qualified identity
```

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M18-S1 | Contrat IDE | Définir protocole/version/compatibilité | ADR + fixture client |
| M18-S2 | Plugin bootstrap | Projet IntelliJ installable | sandbox IDE |
| M18-S3 | Project status | Afficher état/runtime/snapshot | cohérence avec CLI |
| M18-S4 | Navigation symboles | Aller aux définitions/usages/implémentations | navigation fichier/ligne fiable |
| M18-S5 | Architecture graph | Graphe interactif, filtre module, navigation | grands graphes bornés |
| M18-S6 | Impact + tests | Vues impact et related tests | preuves visibles |
| M18-S7 | Index lifecycle | Index/reindex/doctor depuis l'IDE | erreurs non destructives |
| M18-S8 | Git intelligence | Zones/activité factuelle | séparation activité/importance |
| M18-S9 | Packaging | Distribution/versioning plugin | installation documentée |

## Portes M18

M18 n'est terminé que si :

- le plugin ne dépend pas des classes internes du moteur ;
- une version de protocole incompatible est détectée proprement ;
- la navigation ouvre le bon fichier et la bonne position ;
- le graphe utilise les mêmes arêtes que CLI/API/MCP ;
- l'utilisateur peut comprendre pourquoi un impact ou test est proposé ;
- l'indexation déclenchée depuis l'IDE conserve la sécurité de promotion atomique ;
- le plugin reste optionnel.

---

# M19 — Advanced Code Intelligence

## Question produit

> MINOS peut-il analyser la structure d'exécution et les flux de données sans confondre faits statiques, approximations et comportements runtime ?

## Objectif

Étendre le graphe de connaissance vers un modèle de programme plus riche : call graph amélioré, control flow, data flow puis Code Property Graph, afin d'améliorer impact, sécurité et compréhension de code.

## Ordre de construction

```text
Call Graph v2
     ↓
Control Flow Graph
     ↓
Data Flow Graph
     ↓
Code Property Graph
     ↓
Impact v2 / Security Intelligence
```

Le CPG n'est pas un objectif décoratif : il n'est promu que si les capacités en aval justifient son coût.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M19-S1 | Program graph model | Modèle générique d'arêtes/nœuds de programme | provider-independent |
| M19-S2 | Call graph v2 | Appels directs avec provenance/capacité | précision/rappel mesurés |
| M19-S3 | CFG | Blocs et flux de contrôle | fixtures branches/loops/exceptions |
| M19-S4 | Data flow | Def-use et propagation locale | vérités terrain |
| M19-S5 | Interprocedural flow | Propagation bornée entre fonctions | cycles/limites explicites |
| M19-S6 | CPG composition | Vue unifiée code/property graph | pas de duplication incohérente |
| M19-S7 | Impact v2 | Impact enrichi par appels/flux | amélioration mesurée |
| M19-S8 | Security primitives | Sources/sinks/sanitizers et taint borné | aucune vulnérabilité affirmée sans preuve suffisante |
| M19-S9 | API/MCP exposure | Contrats bornés d'analyse avancée | schemas versionnés |

## Nature de l'information

MINOS doit continuer à exposer explicitement :

```text
FACTUAL
DERIVED
HEURISTIC
```

Un chemin data-flow incomplet ne doit jamais être présenté comme une preuve d'absence de flux.

Un résultat de sécurité devra préciser au minimum :

- source ;
- sink ;
- chemin observé ;
- éventuels sanitizers ;
- confiance ;
- limitations provider/langage ;
- nature factuelle ou dérivée.

## Portes M19

M19 n'est terminé que si :

- les graphes avancés ont des vérités terrain contrôlées ;
- précision et rappel sont mesurés par capacité ;
- les limites interprocédurales et dynamiques sont explicites ;
- Impact v2 démontre un gain réel face à M8 ;
- les analyses de sécurité sont explicables et bornées ;
- le stockage/scalabilité M16 supporte les nouveaux volumes ;
- un provider incapable de produire un type de graphe ne reçoit aucune donnée inventée.

---

# M20 — Semantic & Hybrid Code Intelligence

## Question produit

> MINOS peut-il retrouver du code par intention ou concept tout en conservant ses faits déterministes comme source d'autorité ?

## Objectif

Ajouter une couche sémantique optionnelle et locale permettant la recherche conceptuelle et le retrieval hybride, sans remplacer la recherche structurée existante.

## Architecture cible

```text
                ┌── Lexical / Symbol search ──┐
                │                             │
query ──────────┼── Graph / Architecture ─────┼── Hybrid Ranking ──> bounded context
                │                             │
                ├── Git signals ──────────────┤
                │                             │
                └── Semantic embeddings ──────┘
```

Les embeddings sont un signal de ranking/rappel. Ils ne deviennent jamais une preuve de relation de code.

## Sous-incréments

| Étape | Fonction | Résultat attendu | Gate |
|---|---|---|---|
| M20-S1 | Semantic document model | Définir unités indexables stables | symbols/files/chunks explicites |
| M20-S2 | Embedding provider SPI | Modèles locaux interchangeables | aucune dépendance cloud obligatoire |
| M20-S3 | Vector store abstraction | Backend local reconstructible | migration/rebuild documentés |
| M20-S4 | Semantic search | Recherche par intention | benchmark de pertinence |
| M20-S5 | Hybrid ranking | Fusion lexical + graph + semantic | gain mesuré vs lexical seul |
| M20-S6 | Context builder v2 | Contexte hybride borné | budget tokens/tailles respecté |
| M20-S7 | Incremental semantic index | Ré-embedding ciblé des changements | invalidation sûre |
| M20-S8 | MCP/API | Recherche sémantique explicite | nature/ranking exposés |
| M20-S9 | NEXUS integration v2 | MINOS fournit signaux sémantiques sans voler le rôle de NEXUS | responsabilités préservées |

## Évaluation

La qualité ne peut pas être mesurée seulement par latence. Il faut un dataset de requêtes avec pertinence attendue :

```text
"where is authentication enforced"
"code that validates JWT expiration"
"persistence boundary for project snapshots"
"logic responsible for impact propagation"
```

Mesures possibles :

```text
Recall@K
MRR
nDCG@K
context precision
context recall
Code Exploration Reduction
latency p50/p95/p99
index size
embedding rebuild cost
```

## Frontière MINOS / NEXUS

La frontière existante doit être conservée :

```text
MINOS
  possède les faits de code, graphes, recherches et signaux calculés

NEXUS
  possède le ranking contextuel global, la sélection et le budget de contexte multi-source
```

M20 peut enrichir ce que MINOS sait fournir à NEXUS, mais ne transforme pas MINOS en moteur général de contexte utilisateur.

## Portes M20

M20 n'est terminé que si :

- la recherche sémantique est optionnelle ;
- le produit reste utilisable sans modèle d'embeddings ;
- aucun résultat vectoriel n'est présenté comme fait structurel ;
- le ranking hybride apporte un gain mesurable sur une vérité terrain ;
- le ré-index sémantique incrémental est cohérent avec les snapshots actifs ;
- les coûts disque, mémoire et temps de reconstruction sont documentés ;
- API/MCP distinguent clairement recherche structurée et recherche sémantique ;
- la frontière de responsabilité avec NEXUS reste explicite.

---

# Matrice des jalons

| Jalon | Finalité principale | Dépend de | Livrable structurant |
|---|---|---|---|
| M15 | Core industrialisé | M14 | modules + `MinosApplication` + cache/index + CI |
| M16 | Scalabilité prouvée | M15 | benchmark suite + backend decision + retention |
| M17 | Extensibilité langages/builds | M15, M16 | discovery/provider SPI + conformance kit |
| M18 | Expérience IntelliJ | M15 | plugin IntelliJ consommant les contrats MINOS |
| M19 | Intelligence avancée | M16, M17 | call/CFG/data-flow/CPG + impact/security v2 |
| M20 | Intelligence sémantique | M16 | embeddings optionnels + hybrid retrieval |

M18 peut avancer en parallèle de M17 après stabilisation des contrats M15.

M19 et M20 peuvent avoir des prototypes précoces, mais leur promotion produit exige les garanties de scalabilité de M16.

---

# Politique de validation M15-M20

Chaque jalon doit disposer avant implémentation complète de :

1. une issue principale ;
2. une roadmap opérationnelle de jalon ;
3. les ADR nécessaires ;
4. des critères de sortie mesurables ;
5. des fixtures ou datasets représentatifs ;
6. une qualification sur le SHA final exact ;
7. une mise à jour de `docs/STATUS.md` uniquement après livraison.

La validation finale doit conserver au minimum :

```text
HEAD exact
Java / Maven
OS lorsque pertinent
nombre de sources main/test
nombre de tests
failures/errors/skipped
BUILD SUCCESS/FAILURE
benchmarks significatifs
replays fonctionnels
limitations restantes
```

Pour M16, M19 et M20, une simple suite de tests verte ne suffit pas : les performances et/ou la qualité algorithmique font partie de la gate.

---

# Après M20

Les thèmes suivants restent volontairement hors engagement M15-M20 tant qu'un besoin produit et des critères de réussite ne sont pas cadrés :

- indexation distante directe GitHub/GitLab ;
- exécution distribuée de l'indexation ;
- service MINOS hébergé ;
- collaboration multi-utilisateur ;
- analyse runtime/dynamique ;
- support massif de langages sans provider qualifié ;
- remplacement de NEXUS par MINOS.

M20 doit être considéré comme la fin de cette phase de maturation, pas comme la fin du produit.