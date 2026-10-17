# Comparatif des fondations de Code Intelligence — MINOS

- Statut : **Étude C0 — conclusions intégrées aux ADR-0002 et ADR-0003**
- Date : **22 juillet 2026**

## 1. Objectif

Cette étude compare les principales briques envisagées pour éviter à MINOS de reconstruire inutilement une infrastructure de Code Intelligence déjà disponible en open source.

Les solutions ne sont pas évaluées comme des produits concurrents complets : elles occupent des niveaux différents de l'architecture.

Les critères MINOS sont :

- précision sémantique ;
- multi-langages ;
- local-first ;
- fonctionnement sur dépôts privés ;
- Windows / Linux / macOS ;
- simplicité d'intégration ;
- indépendance du domaine MINOS ;
- capacité de requêtes relationnelles ;
- extensibilité ;
- coût opérationnel ;
- capacité à alimenter des agents IA avec des résultats compacts.

---

## 2. Synthèse

| Solution | Rôle naturel | Intérêt MINOS | Limite principale | Position C0 |
|---|---|---|---|---|
| **SCIP** | protocole d'indexation sémantique | très élevé | profondeur variable selon l'indexeur | **adopté comme protocole privilégié** |
| **Glean** | fact store + moteur de requêtes code | très élevé fonctionnellement | opérabilité / Linux / intégration | **backend avancé optionnel à tester** |
| **Kythe** | graphe d'interopérabilité et cross-références | moyen à élevé | infrastructure plus lourde, serving à construire | alternative de référence |
| **Joern** | Code Property Graph / data-flow / sécurité | très élevé pour analyses profondes | trop spécialisé pour le socle de navigation | futur fournisseur spécialisé |
| **Backend léger MINOS** | stockage minimal / baseline | indispensable pour M0 | ne doit pas réimplémenter Glean | **chemin de contrôle obligatoire** |

---

# 3. SCIP

## 3.1 Ce que SCIP fournit

SCIP est un protocole agnostique du langage pour représenter de la Code Intelligence produite par des indexeurs spécialisés.

Il vise principalement des besoins comme :

- aller à la définition ;
- trouver les références ;
- trouver les implémentations ;
- transporter des identités et occurrences de symboles.

L'écosystème documenté inclut actuellement notamment :

- Java / Scala / Kotlin ;
- TypeScript / JavaScript ;
- Rust ;
- C / C++ ;
- Python ;
- Ruby ;
- C# / Visual Basic ;
- Dart ;
- PHP ;
- Debian packaging.

## 3.2 Forces

- format commun multi-langages ;
- Protobuf ;
- protocole indépendant du stockage ;
- licence Apache-2.0 ;
- CLI d'inspection et de validation ;
- possibilité de générer des bindings pour l'écosystème choisi ;
- réduit fortement le besoin de développer un frontend sémantique par langage.

La CLI fournit notamment :

```text
lint
print
snapshot
test
stats
expt-convert
```

Les commandes `snapshot` et `test` sont particulièrement intéressantes pour construire les fixtures de validation MINOS.

## 3.3 Limites

SCIP n'est pas à lui seul :

- un moteur de graphe ;
- un fact store ;
- une base de données optimisée pour les requêtes MINOS ;
- un moteur de contrôle de flux ;
- un moteur de data-flow ;
- un moteur complet d'analyse d'impact.

La qualité dépend de l'indexeur utilisé.

MINOS doit donc qualifier chaque `IndexerProvider` séparément.

## 3.4 Décision

SCIP est retenu comme **protocole d'interopérabilité sémantique privilégié**.

Voir ADR-0002.

---

# 4. Glean

## 4.1 Ce que Glean apporte

Glean est un système spécialisé dans les faits relatifs au code source.

Il fournit :

- stockage de faits typés ;
- déduplication ;
- schémas extensibles ;
- moteur de requêtes Angle ;
- prédicats dérivés ;
- requêtes sur définitions, appels, héritage et autres relations selon les indexeurs ;
- stockage RocksDB ;
- possibilités de faits personnalisés ;
- ingestion via plusieurs indexeurs, dont des chemins SCIP.

Sur le plan fonctionnel, Glean correspond donc très bien à la vision long terme de MINOS.

## 4.2 Points bloquants actuels

### Systèmes d'exploitation

La documentation de construction indique actuellement :

> le build est testé uniquement sous Linux.

MINOS doit prendre Windows en compte comme environnement développeur de premier ordre.

### Docker

La documentation officielle indique que l'image Docker Glean est actuellement non fonctionnelle.

L'image de démonstration documentée est également très volumineuse, de l'ordre de 7 Go.

### API

La documentation de requêtes indique actuellement qu'il existe une API cliente Haskell ; les autres couches passent par Thrift.

Pour MINOS, cela implique potentiellement :

- génération / maintenance d'un client Thrift ;
- gestion d'un sidecar ;
- ou orchestration CLI.

### Java

La documentation Java de Glean présente encore un chemin `lsif-java`, alors que la documentation générale actuelle met aussi en avant SCIP pour plusieurs langages.

Cette divergence documentaire doit être tranchée par expérimentation, pas par hypothèse.

## 4.3 Conclusion Glean

Glean reste trop intéressant pour être ignoré, mais trop contraignant aujourd'hui pour être imposé au MVP.

Position :

> **backend avancé optionnel à tester pendant M0.**

Voir ADR-0003.

---

# 5. Kythe

Kythe fournit un écosystème principalement agnostique du langage pour représenter les informations sémantiques du code sous forme de graphe.

Forces pertinentes :

- modèle de graphe extensible ;
- cross-références ;
- Java et C++ ;
- extracteurs de compilation ;
- prise en compte explicite des informations de build ;
- philosophie d'interopérabilité très proche de MINOS ;
- gestion assumée de données partielles plutôt que de données incorrectes.

Cependant, le stockage Kythe est explicitement conçu comme représentation persistante et non comme moteur général de requêtes performant. Des index/serving tables supplémentaires doivent être construits pour servir efficacement les consommateurs.

Kythe pourrait donc remplacer une partie de SCIP + backend, mais au prix d'une infrastructure spécifique importante.

## Position MINOS

Kythe reste une **alternative de référence**, notamment si SCIP s'avère insuffisant sur certains écosystèmes ou si ses extracteurs de build deviennent utiles.

Il n'est pas retenu comme fondation principale du MVP.

---

# 6. Joern

Joern produit des **Code Property Graphs** et fournit des analyses plus profondes que la simple navigation de symboles.

Capacités particulièrement intéressantes :

- AST ;
- graphe de contrôle ;
- data-flow ;
- appels ;
- analyse de taint ;
- requêtes de graphe ;
- nombreux langages ;
- fonctionnement possible même lorsque l'environnement de build est incomplet.

Son orientation première reste toutefois l'analyse statique profonde et la recherche de vulnérabilités.

L'utiliser comme fondation unique de `find_symbol` / `find_usages` imposerait une complexité inutile au cœur MINOS.

## Position MINOS

Joern est conservé comme candidat futur de type :

```text
SpecializedAnalysisProvider
```

pour :

```text
CONTROL_FLOW
DATA_FLOW
TAINT_ANALYSIS
SECURITY_ANALYSIS
DEEP_CALL_GRAPH
```

Il ne remplace pas SCIP comme protocole d'entrée privilégié.

---

# 7. Baseline légère MINOS

Pour évaluer Glean honnêtement, M0 doit disposer d'un chemin qui ne l'utilise pas.

```text
Repository
    │
    ▼
IndexerProvider
    │
    ▼
SCIP / autre représentation
    │
    ▼
Adaptateur MINOS
    │
    ▼
Modèle normalisé
    │
    ▼
CodeKnowledgeStore léger
```

Cette baseline ne doit pas chercher à reproduire Angle ou un moteur de graphe complet.

Elle doit seulement permettre de mesurer correctement les cas d'usage minimum :

```text
find_symbol
find_usages
find_implementations
find_dependencies
find_dependents
```

Elle doit aussi permettre de tester le domaine sans Glean.

Implémentations possibles pendant M0 :

- mémoire ;
- SQLite ou autre stockage embarqué minimal ;
- index spécialisés très simples si nécessaire.

Le choix de production est volontairement différé.

---

# 8. Architecture résultante

```text
                         Repository
                             │
                             ▼
                     Indexer Orchestrator
                             │
                             ▼
                       IndexerRegistry
                             │
               ┌─────────────┼─────────────┐
               ▼             ▼             ▼
             SCIP          Native        Specialized
          Providers       Providers       Providers
               │             │             │
               └─────────────┼─────────────┘
                             ▼
                    MINOS Normalization
                             │
                             ▼
                    CodeKnowledgeStore
                             │
                  ┌──────────┼──────────┐
                  ▼          ▼          ▼
               Memory     Lightweight  Glean
                tests       default?   advanced?
                             │
                             ▼
                   MINOS Query Services
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
             CLI            MCP            API
                             │
                             ▼
                           NEXUS
```

Les points `default?` et `advanced?` sont précisément ce que M0 doit mesurer.

---

# 9. Décisions C0 résultantes

## Accepté

- cœur MINOS agnostique du langage et de l'indexeur ;
- SCIP comme protocole sémantique privilégié mais non obligatoire ;
- `CodeKnowledgeStore` comme frontière MINOS ;
- aucun backend imposé dans le domaine ;
- chemin sans Glean obligatoire ;
- Glean évalué comme backend avancé ;
- moteurs CPG comme fournisseurs spécialisés futurs.

## À mesurer pendant M0

- qualité réelle de `scip-java` ;
- qualité d'un second indexeur ;
- richesse des faits SCIP pour les cas MINOS ;
- coût d'un import direct SCIP ;
- apport réel de Glean ;
- opérabilité Glean sous environnement Windows cible ;
- communication avec Glean ;
- performances comparées ;
- coût mémoire et disque ;
- valeur des requêtes Angle par rapport au backend léger.

---

# 10. Sources officielles

Consultées le 22 juillet 2026 :

- SCIP : https://github.com/scip-code/scip
- CLI SCIP : https://github.com/scip-code/scip/blob/main/docs/CLI.md
- scip-java : https://github.com/sourcegraph/scip-java
- Glean : https://glean.software/
- Introduction Glean : https://glean.software/docs/introduction/
- Build Glean : https://glean.software/docs/building/
- Docker Glean : https://glean.software/docs/docker/
- API de requêtes Glean : https://glean.software/docs/query/intro/
- Java dans Glean : https://glean.software/docs/indexer/lsif-java/
- Python/SCIP dans Glean : https://glean.software/docs/indexer/scip-python/
- Kythe : https://kythe.io/docs/kythe-overview.html
- Stockage Kythe : https://kythe.io/docs/kythe-storage.html
- Joern : https://docs.joern.io/
- Code Property Graph Joern : https://docs.joern.io/code-property-graph/
