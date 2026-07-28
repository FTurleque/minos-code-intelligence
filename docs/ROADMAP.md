# Feuille de route — MINOS

Statut : **C0 à M20 terminés, validés et livrés. M21 — Production Integrity & Surface Convergence est EN COURS. M22→M27 sont planifiés comme trajectoire post-consolidation.**

L'état courant livré est résumé dans [`STATUS.md`](STATUS.md). Les décisions architecturales durables sont dans [`adr/`](adr/README.md). Les preuves historiques restent sous [`history/milestones/`](history/milestones/README.md).

La trajectoire M15 à M20 est détaillée dans [`roadmap/M15_M20_EVOLUTION.md`](roadmap/M15_M20_EVOLUTION.md). La consolidation post-M20 est pilotée par [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md).

## Principes

- chaque jalon ferme une question produit identifiable ;
- une capacité n'est acquise qu'avec une preuve reproductible ;
- un nouveau commit invalide la qualification exacte d'un SHA antérieur tant qu'une politique plus fine n'a pas été explicitement qualifiée ;
- CLI, API, MCP, NEXUS et IntelliJ ne dupliquent pas le métier ;
- les décisions durables sont formalisées en ADR ;
- les optimisations et choix de backend sont gouvernés par des mesures ;
- les capacités provider absentes ne sont jamais inventées ;
- les faits, dérivations et heuristiques restent explicitement distingués ;
- les facts documentaires calculables sont dérivés du code quand c'est possible ;
- la consolidation et la dette de production sont fermées avant de promouvoir une nouvelle phase fonctionnelle lourde.

---

## C0 — Cadrage fonctionnel et architectural

**TERMINÉ.** Définition du rôle de MINOS, de ses frontières et de sa place dans l'écosystème.

## M0 — Faisabilité technique

**TERMINÉ ET LIVRÉ — ADOPTER_AVEC_CONTRAINTES.**

Acquis : qualification SCIP Java/TypeScript, baseline SCIP → MINOS, backend local léger, Glean optionnel et frontière fournisseur.

## M1 — Découverte des projets et orchestration

**TERMINÉ ET LIVRÉ.**

Acquis : registre projets/workspaces, découverte langages/builds/modules/racines, ignores, négociation des indexeurs, lifecycle et promotion atomique.

## M2 — Intelligence des symboles

**TERMINÉ ET LIVRÉ.**

Acquis : symboles normalisés, identités stables qualifiées, emplacements, externes/non résolus, recherche et snapshots persistants.

## M3 — Intelligence des relations

**TERMINÉ ET LIVRÉ.**

Acquis : références, implémentations, appels lorsqu'ils existent, dépendances dérivées, provenance, preuves, confiance et requêtes directionnelles.

## M4 — Recherche et contexte compact

**TERMINÉ ET LIVRÉ.**

Acquis : recherche structurée, sorties compactes, bornes résultats/tokens/profondeur, extraits pertinents et récupération explicite de source complète.

## M5 — Tests liés et dérivations explicables

**TERMINÉ ET LIVRÉ.**

Acquis : `RELATED_TEST`, signaux de nommage/référence/appel/proximité, score, raisons et preuves structurées.

## M6 — Intelligence d'architecture

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : topologie modules/namespaces, dépendances inter-modules, concentration, centralité relative, technologies factuelles et contexte de module.

## M7 — Indexation incrémentale

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : fingerprints, ChangeSet, snapshots d'empreintes, invalidation conservatrice, plans `NONE/INCREMENTAL/FULL` et fallback complet.

## M8 — Analyse d'impact

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : impact direct/indirect, chemins explicatifs, confiance conservatrice, profondeur/résultats bornés, cycles, tests potentiellement impactés et limitations runtime.

## M9 — CLI stabilisée

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : administration, import SCIP explicite, statut, recherche/symboles/relations/tests liés, architecture, impact, formats structurés et codes de sortie stables.

## M10 — Serveur MCP

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : serveur MCP STDIO Java officiel, tools read-only, schémas bornés, erreurs structurées et shaded JAR. Le catalogue courant exact est généré dans [`generated/product-facts.md`](generated/product-facts.md).

## M11 — API publique

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Contrat public versionné autour de `MinosApi` et `LocalMinosApi`, sans exposition de SCIP, du stockage ou des modèles internes.

## M12 — Multi-dépôts et intelligence Git

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Acquis : workspaces publics, JGit, activité Git bornée, résolution cross-repository exacte et contrat public additif.

## M13 — Intégration NEXUS

**TERMINÉ, VALIDÉ ET LIVRÉ.**

MINOS exporte un contrat JSON local versionné ; NEXUS reste propriétaire du ranking global, de la sélection et du budget de contexte multi-source.

## M14 — Indexation autonome et installation PROD

**TERMINÉ, VALIDÉ ET LIVRÉ.**

Parcours utilisateur :

```text
minos doctor
minos tools install <provider>
minos project add <root> --name <project>
minos index <project>
```

Acquis : discovery, négociation provider, diagnostic runtime, fingerprints, exécution, normalisation, staging/promotion, distribution Windows native, runtime Java embarqué, installation CLI/MCP et release explicite.

---

# Phase M15→M20 — Industrialisation et complétion

```text
M15  Industrialiser le Core Engine             ✅
  ↓
M16  Prouver la scalabilité                    ✅
  ↓
M17  Généraliser discovery + providers         ✅
  ↓
M18  Intégrer MINOS à IntelliJ                 ✅
  ↓
M19  Intelligence de programme avancée         ✅
  ↓
M20  Recherche sémantique hybride              ✅
```

## M15 — Industrialisation du Core Engine

**TERMINÉ, VALIDÉ ET LIVRÉ — 11/11.**

Acquis : reactor Maven multi-module, `MinosApplication`, MCP découplé de la CLI métier, résolution projet commune, persistance décomposée, cache snapshot actif, indexes reconstruisibles, JaCoCo/CI et facts calculables.

- roadmap : [`roadmap/M15_EXECUTION.md`](roadmap/M15_EXECUTION.md)
- décisions : ADR-0022 à ADR-0024
- issue : #55
- PR finale : #62

## M16 — Scalabilité et performance à grande échelle

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9.**

Acquis : profils `SMOKE/STANDARD/EXTENDED/STRESS`, gate STANDARD 10k fichiers/100k symboles/500k occurrences/250k relations, p50/p95/p99, heap/RSS/disque, MCP long-lived, benchmark FULL/NONE, décision backend mesurée et rétention bornée.

- roadmap : [`roadmap/M16_EXECUTION.md`](roadmap/M16_EXECUTION.md)
- décision : [ADR-0025](adr/0025-measurement-gated-storage-backend-evolution.md)
- issue : #63
- PR finale : #64

## M17 — Provider & Discovery Platform

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9.**

Acquis : SPI discovery/provider, profils `FULL/PARTIAL/EXPERIMENTAL/UNSUPPORTED`, Gradle, npm/pnpm/yarn workspaces, Kotlin/Maven, Python/scip-python, conformance kit et runtime provider extensible.

- roadmap : [`roadmap/M17_EXECUTION.md`](roadmap/M17_EXECUTION.md)
- décision : [ADR-0026](adr/0026-discovery-provider-spi-and-explicit-capability-profiles.md)
- issue : #65

## M18 — MINOS for IntelliJ

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9.**

Acquis : protocole `minos-ide` v1, plugin IntelliJ Java 21 autonome, statut projet/provider/snapshot, navigation symboles, graphe d'architecture, impact/tests liés, lifecycle d'indexation, activité Git factuelle, packaging et Plugin Verifier.

Qualification exacte :

```text
Validated HEAD: 0186146668c12027f44b55d0511a45e89e6dee61
M18 FINAL INTELLIJ INTEGRATION VALIDATION SUCCESS
```

Merge : `faa51f63c5967d874a7a6685b6b513b83bb736b4`.

- roadmap : [`roadmap/M18_EXECUTION.md`](roadmap/M18_EXECUTION.md)
- décision : [ADR-0027](adr/0027-intellij-external-client-and-versioned-cli-protocol.md)
- issue : #67
- PR finale : #68

## M19 — Advanced Code Intelligence

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9.**

Acquis : `ProgramGraph` capability-honest, call graph v2, CFG, data-flow/def-use, propagation interprocédurale bornée, CPG déterministe, Impact v2, primitives sécurité et `AdvancedCodeIntelligenceApi` v1.

Qualification exacte :

```text
Validated HEAD: 859138cbfdd4e0722a6366efd97fa62ad95c2443
M19 FINAL ADVANCED CODE INTELLIGENCE VALIDATION SUCCESS
```

Merge : `3630ebd0f229e1bc028e92444bfa34c3e7609596`.

- roadmap : [`roadmap/M19_EXECUTION.md`](roadmap/M19_EXECUTION.md)
- décision : [ADR-0028](adr/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md)
- issue : #69
- PR finale : #70

## M20 — Semantic & Hybrid Code Intelligence

**TERMINÉ, VALIDÉ ET LIVRÉ — 9/9.**

Acquis : documents sémantiques `SYMBOL/FILE/CHUNK`, SPI `EmbeddingProvider`, provider local opt-in `local-hash`, vector store reconstruisible, recherche sémantique `HEURISTIC`, ranking hybride lexical+graph+semantic, Recall@K/MRR/nDCG@K, contexte v2 borné, index sémantique incrémental, API Java v1 additive, 23 tools MCP et signaux NEXUS v2.

Qualification exacte Windows PowerShell :

```text
Validated HEAD: 8d882e67649667898d55f0be97982b2f217027ba
M20 FINAL SEMANTIC HYBRID CODE INTELLIGENCE VALIDATION SUCCESS
Maven Java 24: 13/13 modules SUCCESS
JaCoCo: all gates PASS
MCP STDIO: 23 tools
```

Merge : `2d095dd2c9f0d362ee54a9840b2b3e1d217579c1`.

- roadmap : [`roadmap/M20_EXECUTION.md`](roadmap/M20_EXECUTION.md)
- décision : [ADR-0029](adr/0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md)
- issue : #71
- PR finale : #72

---

# Phase post-M20 — Intégrité production puis évolutions

L'ordre suivant est volontaire : **M21 consolide d'abord le produit livré. Les jalons M22→M27 ne doivent pas servir à contourner une dette M21 non fermée.**

```text
M21  Production Integrity & Surface Convergence   🚧 EN COURS
  ↓
M22  Advanced Provider Intelligence               ⏳ PLANIFIÉ
  ↓
M23  Semantic Retrieval 2.0                       ⏳ PLANIFIÉ
  ↓
M24  Polyglot Expansion                           ⏳ PLANIFIÉ
  ↓
M25  Remote & Distributed Indexing                ⏳ PLANIFIÉ
  ↓
M26  Runtime & Dynamic Intelligence               ⏳ PLANIFIÉ
  ↓
M27  Team / Hosted Mode                           ⏳ PLANIFIÉ
```

## M21 — Production Integrity & Surface Convergence

**EN COURS — consolidation post-M20.**

Question produit :

> MINOS peut-il devenir un produit continuellement qualifié, cohérent sur toutes ses surfaces et distribuable avec un niveau de confiance production, sans affaiblir ses invariants ?

Axes : CI exploitable, quality gates M19/M20, frontières Maven robustes, documentation single-source-of-truth, supply-chain release, parité IntelliJ M19/M20, providers avancés réellement qualifiés et scalabilité sémantique mesurée.

- roadmap : [`roadmap/M21_EXECUTION.md`](roadmap/M21_EXECUTION.md)
- issue : #73
- branche : `m21-production-integrity`

## M22 — Advanced Provider Intelligence

**PLANIFIÉ — ne démarre pas avant la fermeture des gates structurants M21.**

Question cible :

> MINOS peut-il alimenter réellement CFG, def-use, flux interprocéduraux et primitives de sécurité avec des providers qualifiés, sans confondre capacité du moteur et fait effectivement prouvé par un provider ?

Cibles : providers avancés Java en premier, fixtures contrôlées, précision/rappel, provenance complète, capacités exhaustives et fallback explicite. TypeScript/Python suivent uniquement lorsque les indexeurs disponibles permettent une qualification équivalente.

## M23 — Semantic Retrieval 2.0

**PLANIFIÉ.**

Question cible :

> MINOS peut-il fournir un retrieval réellement sémantique de qualité production tout en restant local-first, optionnel, mesuré et non autoritatif ?

Cibles : provider local d'embeddings de code réellement appris, qualité Recall@K/MRR/nDCG, format vectoriel compact, caches de vues sémantiques et ANN/vector index seulement si les mesures M21-S8 le justifient.

## M24 — Polyglot Expansion

**PLANIFIÉ.**

Question cible :

> MINOS peut-il étendre sa couverture de langages sans abaisser les exigences de capabilities, stable identity, provenance et conformance ?

Cibles possibles : C/C++, C#, Go, Rust et autres providers uniquement après évaluation de leurs indexeurs et contrats de symboles/relations. Aucun langage n'est déclaré supporté sur la seule présence d'un parseur.

## M25 — Remote & Distributed Indexing

**PLANIFIÉ — opt-in.**

Question cible :

> MINOS peut-il indexer des dépôts distants ou distribuer l'exécution sans abandonner la reproductibilité, la sécurité et l'autorité locale des snapshots ?

Cibles : GitHub/GitLab distants, workers isolés, transport d'artefacts versionné, cache contrôlé, provenance de l'exécution et politique explicite de secrets/réseau.

## M26 — Runtime & Dynamic Intelligence

**PLANIFIÉ.**

Question cible :

> MINOS peut-il rapprocher faits statiques et observations runtime sans transformer une trace partielle en vérité exhaustive ?

Cibles : traces d'appels/exécution, couverture runtime, hot paths observés, rapprochement symbolique statique↔runtime, provenance temporelle et nature explicite des observations.

## M27 — Team / Hosted Mode

**PLANIFIÉ — dépend des exigences de sécurité et gouvernance des données.**

Question cible :

> MINOS peut-il proposer collaboration multi-utilisateur et service hébergé sans perdre ses garanties de confidentialité, d'isolation, de provenance et de reproductibilité ?

Cibles : espaces partagés, isolation tenant, politiques de rétention, authentification/autorisation, audit, chiffrement et mode local toujours disponible. Aucun service hébergé n'est requis pour le cœur MINOS.

## Règle de promotion post-M20

M22→M27 sont des **directions planifiées**, pas des capacités acquises. Pour être promu en jalon actif, chacun doit disposer d'une roadmap opérationnelle, de critères de sortie mesurables, des ADR nécessaires et d'une qualification reproductible sur un SHA exact.
