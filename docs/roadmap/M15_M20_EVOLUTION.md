# MINOS — Roadmap d'évolution M15 à M20

Statut : **PHASE TERMINÉE — M15 à M20 sont validés, livrés et intégrés.**

Cette roadmap prolonge les jalons C0 à M14 et documente la phase qui a transformé MINOS d'un moteur local fonctionnel en plateforme de Code Intelligence industrialisée, scalable, extensible, intégrée à l'IDE, capable d'analyses avancées et de retrieval sémantique hybride.

Elle complète [`../ROADMAP.md`](../ROADMAP.md), qui reste la vue produit officielle. Les détails de qualification sont conservés dans les roadmaps opérationnelles M15→M20 et les PR/issues correspondantes.

## Principes non négociables

Les évolutions M15 à M20 ont préservé les invariants suivants :

- MINOS reste **local-first** par défaut ;
- le cœur reste indépendant d'un LLM et d'un fournisseur d'IA ;
- les faits, dérivations et heuristiques restent explicitement distingués ;
- toute information dérivée conserve provenance, confiance et preuves lorsque nécessaires ;
- aucune capacité fournisseur absente n'est inventée ;
- les contrats publics ne fuient ni SCIP, ni un backend de stockage, ni un protocole d'exposition ;
- CLI, API, MCP, NEXUS et IntelliJ consomment le même cœur métier ou ses contrats versionnés ;
- l'analyse d'impact reste conservatrice tant qu'une preuve runtime exhaustive n'existe pas ;
- les choix de backend sont gouvernés par des mesures ;
- un jalon n'est terminé qu'après validation reproductible sur un SHA exact.

## Vue d'ensemble livrée

```text
M15  Industrialiser le cœur                       ✅ LIVRÉ
  ↓
M16  Prouver la scalabilité                      ✅ LIVRÉ
  ↓
M17  Généraliser discovery + providers           ✅ LIVRÉ
  ↓
M18  Intégrer MINOS à IntelliJ                   ✅ LIVRÉ
  ↓
M19  Ajouter l'intelligence de programme avancée ✅ LIVRÉ
  ↓
M20  Ajouter la recherche sémantique hybride     ✅ LIVRÉ
```

---

# M15 — Industrialisation du Core Engine

## Question produit

> MINOS peut-il devenir une plateforme modulaire et durable sans modifier les contrats fonctionnels déjà livrés ?

## Résultat

**Oui — 11/11 sous-incréments livrés.**

```text
M15-S1   baseline de non-régression              ✅
M15-S2   Maven multi-module                      ✅
M15-S3   MinosApplication                        ✅
M15-S4   découplage MCP                          ✅
M15-S5   résolution projet commune               ✅
M15-S6   persistance décomposée                  ✅
M15-S7   cache snapshot actif                    ✅
M15-S8   indexes de requête                      ✅
M15-S9   JaCoCo / qualité continue               ✅
M15-S10  CI automatique de PR                    ✅
M15-S11  cohérence documentaire                  ✅
```

Acquis : reactor Maven multi-module, composition root `MinosApplication`, MCP découplé de la CLI métier, résolution projet unique, persistance décomposée, snapshot actif mis en cache, indexes reconstruisibles, JaCoCo et facts calculables.

Décisions : ADR-0022, ADR-0023, ADR-0024.

Roadmap opérationnelle : [`M15_EXECUTION.md`](M15_EXECUTION.md).

---

# M16 — Scalabilité et performance à grande échelle

## Question produit

> MINOS conserve-t-il des performances et une empreinte acceptables lorsqu'il passe de projets moyens à de grands codebases ?

## Résultat

**Oui sous les gates mesurées M16 — 9/9 sous-incréments livrés.**

```text
M16-S1   harness benchmark                       ✅
M16-S2   datasets d'échelle                      ✅
M16-S3   query benchmark                         ✅
M16-S4   MCP sustained load                      ✅
M16-S5   indexing benchmark                      ✅
M16-S6   memory/disk profile                     ✅
M16-S7   backend decision                        ✅
M16-S8   optimisations mesurées uniquement       ✅
M16-S9   retention/compaction                    ✅
```

Le gate STANDARD qualifié couvre 10 000 fichiers, 100 000 symboles, 500 000 occurrences et 250 000 relations. Les mesures p50/p95/p99, heap/RSS/disque, FULL/NONE et MCP long-lived gouvernent les évolutions.

Décision backend : conserver les snapshots fichiers versionnés avec vues/indexes reconstruisibles tant qu'aucune mesure ne justifie un backend plus complexe.

Décision : ADR-0025.

Roadmap opérationnelle : [`M16_EXECUTION.md`](M16_EXECUTION.md).

---

# M17 — Provider & Discovery Platform

## Question produit

> MINOS peut-il devenir réellement extensible à de nouveaux langages et systèmes de build sans ajouter de branches spécifiques dans le cœur ?

## Résultat

**Oui — 9/9 sous-incréments livrés.**

```text
M17-S1   Discovery SPI                            ✅
M17-S2   Provider SPI                             ✅
M17-S3   Capability model v2                      ✅
M17-S4   Gradle                                   ✅
M17-S5   npm/pnpm/yarn workspaces                 ✅
M17-S6   Kotlin                                   ✅
M17-S7   Python / scip-python                     ✅
M17-S8   Provider conformance kit                 ✅
M17-S9   Installation provider extensible         ✅
```

Acquis : détecteurs composables, `IndexerProvider`, profils exhaustifs `FULL/PARTIAL/EXPERIMENTAL/UNSUPPORTED`, discovery Gradle et workspaces JS, Kotlin/Maven, Python/scip-python, conformance kit et runtime provider extensible.

Décision : ADR-0026.

Roadmap opérationnelle : [`M17_EXECUTION.md`](M17_EXECUTION.md).

---

# M18 — MINOS for IntelliJ

## Question produit

> Un développeur peut-il exploiter MINOS quotidiennement dans son IDE sans passer en permanence par la CLI ou par un agent IA ?

## Résultat

**Oui — 9/9 sous-incréments livrés.**

```text
M18-S1   Contrat IDE / handshake v1               ✅
M18-S2   Plugin bootstrap                          ✅
M18-S3   Project status                            ✅
M18-S4   Navigation symboles                       ✅
M18-S5   Architecture graph                        ✅
M18-S6   Impact + related tests                    ✅
M18-S7   Index lifecycle                           ✅
M18-S8   Git intelligence factuelle                ✅
M18-S9   Packaging / Plugin Verifier               ✅
```

Le plugin IntelliJ est un client externe Java 21 du moteur MINOS Java 24, via protocole local JSON `minos-ide` v1. Il ne duplique ni les stores ni les analyses métier.

Qualification exacte :

```text
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
```

Merge : `faa51f63c5967d874a7a6685b6b513b83bb736b4`.

Décision : ADR-0027.

Roadmap opérationnelle : [`M18_EXECUTION.md`](M18_EXECUTION.md).

---

# M19 — Advanced Code Intelligence

## Question produit

> MINOS peut-il analyser la structure d'exécution et les flux de données sans confondre faits statiques, approximations et comportements runtime ?

## Résultat

**Oui dans les limites statiques explicitement qualifiées — 9/9 sous-incréments livrés.**

```text
M19-S1   Program graph model                       ✅
M19-S2   Call graph v2                             ✅
M19-S3   Control Flow Graph                        ✅
M19-S4   Data Flow / DEF_USE                       ✅
M19-S5   Interprocedural Flow                      ✅
M19-S6   CPG composition                           ✅
M19-S7   Impact v2                                 ✅
M19-S8   Security primitives                       ✅
M19-S9   API / MCP exposure                        ✅
```

Ordre livré :

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

Le `ProgramGraph` est capability-honest. Une capacité provider absente reste indisponible ; aucun CFG, def-use ou taint n'est inventé. Les chemins sécurité restent observés, bornés et non exhaustifs.

Qualification exacte :

```text
Validated HEAD: 859138cbfdd4e0722a6366efd97fa62ad95c2443
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

Merge : `3630ebd0f229e1bc028e92444bfa34c3e7609596`.

Décision : ADR-0028.

Roadmap opérationnelle : [`M19_EXECUTION.md`](M19_EXECUTION.md).

---

# M20 — Semantic & Hybrid Code Intelligence

## Question produit

> MINOS peut-il retrouver du code par intention ou concept tout en conservant ses faits déterministes comme source d'autorité ?

## Résultat

**Oui — 9/9 sous-incréments livrés et qualifiés exact-head.**

```text
M20-S1   Semantic document model                   ✅
M20-S2   Embedding provider SPI                    ✅
M20-S3   Vector store abstraction                  ✅
M20-S4   Semantic search                           ✅
M20-S5   Hybrid ranking                            ✅
M20-S6   Context builder v2                        ✅
M20-S7   Incremental semantic index                ✅
M20-S8   MCP / API                                 ✅
M20-S9   NEXUS integration v2                      ✅
```

## Architecture livrée

```text
                ┌── Lexical / Symbol search ──┐
                │                             │
query ──────────┼── Graph signals ────────────┼── Hybrid Ranking ──> bounded context
                │                             │
                └── Semantic embeddings ──────┘
```

Les embeddings sont **optionnels** et restent des signaux `HEURISTIC`. Ils ne deviennent jamais une relation ni une preuve structurelle.

`SemanticDocument` utilise des unités `SYMBOL`, `FILE`, `CHUNK` avec `stableKey + checksum`. `SemanticVectorStore` est local, versionné et reconstruisible. L'index sémantique incrémental réutilise les vecteurs inchangés et ré-embed uniquement les documents ajoutés/modifiés.

Activation native de référence :

```text
MINOS_SEMANTIC_PROVIDER=local-hash
```

Le ranking hybride conserve séparément les signaux lexical, graphe et sémantique. La qualification contrôlée mesure Recall@K, MRR et nDCG@K et exige un gain face à la baseline lexicale.

Le contexte v2 est borné par nombre de documents, budget global de tokens et budget par document.

Surfaces :

- `SemanticCodeIntelligenceApi` v1 additive ;
- catalogue MCP porté à **23 tools read-only** ;
- `minos_semantic_index_status` ;
- `minos_semantic_search` ;
- `minos_hybrid_search` ;
- `minos_hybrid_context` ;
- NEXUS semantic signals v2.

## Frontière MINOS / NEXUS

```text
MINOS
  possède les faits de code, graphes, recherches et signaux calculés

NEXUS
  possède le ranking contextuel global, la sélection et le budget de contexte multi-source
```

M20 enrichit ce que MINOS fournit à NEXUS sans transformer MINOS en moteur général de contexte utilisateur.

## Qualification finale

```text
Validated HEAD: 8d882e67649667898d55f0be97982b2f217027ba
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
Maven Java 24: 13/13 modules SUCCESS
JaCoCo: all gates PASS
MCP STDIO: 23 tools
```

Merge : `2d095dd2c9f0d362ee54a9840b2b3e1d217579c1`.

Décision : ADR-0029.

Roadmap opérationnelle : [`M20_EXECUTION.md`](M20_EXECUTION.md).

---

# Matrice finale des jalons M15→M20

| Jalon | Finalité principale | État | Livrable structurant |
|---|---|---|---|
| M15 | Core industrialisé | ✅ livré | modules + `MinosApplication` + cache/index + CI |
| M16 | Scalabilité prouvée | ✅ livré | benchmark suite + backend decision + retention |
| M17 | Extensibilité langages/builds | ✅ livré | discovery/provider SPI + conformance kit |
| M18 | Expérience IntelliJ | ✅ livré | plugin IntelliJ consommant les contrats MINOS |
| M19 | Intelligence avancée | ✅ livré | call/CFG/data-flow/CPG + impact/security v2 |
| M20 | Intelligence sémantique | ✅ livré | embeddings optionnels + hybrid retrieval |

## Politique de validation appliquée

Chaque jalon de cette phase dispose de :

1. une issue principale ;
2. une roadmap opérationnelle ;
3. les ADR nécessaires ;
4. des critères de sortie mesurables ;
5. des fixtures/datasets représentatifs ;
6. une qualification sur le SHA final exact ;
7. une mise à jour de l'état livré après intégration.

Pour M16, M19 et M20, une simple suite de tests verte ne suffisait pas : performance et/ou qualité algorithmique faisaient partie des gates.

---

# Après M20

M20 marque **la fin de cette phase de maturation, pas la fin du produit**.

Aucun M21 n'est défini dans cette roadmap. Toute nouvelle phase doit repartir d'une question produit et de critères de réussite explicites.

Pistes historiquement laissées hors engagement M15→M20 :

- indexation distante directe GitHub/GitLab ;
- exécution distribuée de l'indexation ;
- service MINOS hébergé ;
- collaboration multi-utilisateur ;
- analyse runtime/dynamique ;
- support massif de langages sans provider qualifié ;
- évolution de la coopération NEXUS/MINOS sans remplacement implicite de NEXUS.