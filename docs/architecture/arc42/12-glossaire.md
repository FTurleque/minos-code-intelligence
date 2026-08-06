# Section 12 — Glossaire

---

## Termes métier

| Terme | Définition |
|-------|-----------|
| **Code Intelligence** | Ensemble des capacités permettant de naviguer, comprendre et analyser du code source : définitions, références, implémentations, callers/callees, impact, architecture |
| **Snapshot** | Image point-in-temps des faits de code indexés (symboles, relations) pour un projet, promue atomiquement |
| **Capacité (IndexerCapability)** | Fonctionnalité déclarée explicitement par un provider : `DEFINITIONS`, `REFERENCES`, `IMPLEMENTATIONS`, `CALL_RELATIONSHIPS`, etc. |
| **Provider** | Composant qui implémente le port `IndexerProvider` pour un outil d'indexation donné (ex : SCIP Java) |
| **Fact** | Donnée structurée et traçable (symbole, relation) issue d'un provider d'indexation |
| **Impact** | Estimation conservative de l'ensemble des symboles potentiellement affectés par la modification d'un symbole donné |
| **Tenant** | Unité d'isolement dans le mode équipe opt-in (workspace partagé, RBAC, audit) |
| **Workspace** | Espace de travail partagé entre membres d'un tenant |

---

## Termes techniques

| Terme | Définition |
|-------|-----------|
| **SCIP** | Source Code Intelligence Protocol — protocole Protobuf agnostique du langage pour représenter les résultats d'indexation sémantique (symboles, occurrences, relations) |
| **Glean** | Fact store open-source de Meta pour la Code Intelligence ; optionnel derrière le port `CodeKnowledgeStore` (ADR-0003) |
| **MCP** | Model Context Protocol — protocole JSON-RPC 2.0 STDIO permettant aux agents IA de consommer MINOS comme fournisseur de contexte |
| **NEXUS** | Orchestrateur externe consommant l'export JSON de snapshot normalisé MINOS |
| **Shaded JAR** | JAR autonome contenant MINOS et toutes ses dépendances (produit par `maven-shade-plugin` dans `minos-app`) |
| **CodeKnowledgeStore** | Port du moteur définissant le contrat de stockage/requête provider-indépendant |
| **SnapshotQueryView** | Vue en mémoire du snapshot actif, reconstruite à la promotion, permettant des requêtes sans I/O |
| **Evidence** | Type du domaine portant la provenance d'un fait (provider, type de preuve, confiance) |
| **ResolutionStatus** | État de résolution d'un symbole ou d'une relation (RESOLVED, UNRESOLVED, PARTIAL…) |
| **ProgramGraph** | Graphe de programme capability-honest : nœuds et arêtes typés, avec capacités et limitations déclarées |
| **EmbeddingProvider** | Port SPI pour les providers de vecteurs sémantiques (optionnel) |
| **HMAC-SHA-256** | Algorithme de signature utilisé pour les tokens bearer `mht1` et la chaîne d'audit tenant |
| **AES-256-GCM** | Algorithme de chiffrement symétrique utilisé pour le control plane tenant |
| **JGit** | Bibliothèque Java pure implémentant le protocole Git, utilisée par `minos-integration-git` |
| **Testcontainers** | Framework Java permettant d'instancier des conteneurs Docker dans les tests (PostgreSQL) |
| **CI-friendly version** | Pattern Maven `${revision}` permettant de définir la version au build sans modifier les POM |
| **ProcessBuilder** | API Java utilisée par `minos-runtime-local` pour lancer les processus indexeurs externes |

---

## Acronymes

| Acronyme | Développement |
|---------|--------------|
| ADR | Architecture Decision Record |
| CLI | Command-Line Interface |
| MCP | Model Context Protocol |
| SCIP | Source Code Intelligence Protocol |
| SPI | Service Provider Interface |
| RBAC | Role-Based Access Control |
| ANN | Approximate Nearest Neighbor |
| JVM | Java Virtual Machine |
| JAR | Java Archive |
| JDBC | Java Database Connectivity |
| STDIO | Standard Input / Output |
| JSON | JavaScript Object Notation |
| HMAC | Hash-based Message Authentication Code |
| GCM | Galois/Counter Mode (chiffrement authentifié) |
| NEXUS | Nom propre — orchestrateur externe MINOS |
| CPG | Code Property Graph |
