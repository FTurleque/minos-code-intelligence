# Architecture Decision Records

MINOS utilise les Architecture Decision Records (ADR) pour documenter les **choix techniques structurants et durables**.

Les ADR décrivent l’architecture courante et son raisonnement. Les preuves, mesures, SHA et résultats de validation propres à un jalon sont conservés séparément dans [`../history/milestones/`](../history/milestones/README.md).

## Statuts

- **Proposed** — en évaluation ;
- **Accepted** — direction architecturale courante ;
- **Superseded** — remplacée par un ADR plus récent ;
- **Rejected** — évaluée et non retenue.

## Index

| ADR | Décision | Statut | Origine |
|---|---|---|---|
| [0001](0001-language-and-indexer-agnostic-core.md) | Keep the MINOS core language- and indexer-agnostic | Accepted | C0/M0 |
| [0002](0002-scip-preferred-interoperability-protocol.md) | Use SCIP as the preferred semantic indexing interoperability protocol | Accepted | M0 |
| [0003](0003-glean-behind-code-knowledge-store.md) | Keep Glean optional behind a MINOS-owned CodeKnowledgeStore port | Accepted | M0 |
| [0004](0004-stack-java-maven-core-sans-framework.md) | Implement the MINOS core with Maven and no server framework | Partially superseded by ADR-0005 | C0/M0 |
| [0005](0005-aligner-java-24-environnement-developpement.md) | Align MINOS on Java 24 | Accepted | M0 |
| [0006](0006-promouvoir-les-index-de-maniere-atomique.md) | Promouvoir les index de manière atomique | Accepted | M1 |
| [0007](0007-attribuer-identites-projet-workspace-dans-registre-local.md) | Attribuer les identités projet/workspace dans un registre local | Accepted | M1 |
| [0008](0008-negocier-indexeurs-par-capacites-explicites.md) | Négocier les indexeurs par capacités explicites | Accepted | M1 |
| [0009](0009-normalized-symbol-identity.md) | Modéliser les identités de symboles sans inventer de canonicité | Accepted | M2 |
| [0010](0010-normalized-relationship-semantics.md) | Normaliser les relations avec provenance, preuve et confiance explicites | Accepted | M3 |
| [0011](0011-bounded-code-search-context.md) | Borner explicitement la recherche et le contexte de code | Accepted | M4 |
| [0012](0012-explainable-related-tests.md) | Conserver les tests liés comme dérivations explicables | Accepted | M5 |
| [0013](0013-factual-architecture-intelligence.md) | Séparer les faits d’architecture de leur interprétation | Accepted | M6 |
| [0014](0014-safe-incremental-indexing.md) | N’utiliser l’indexation incrémentale que sous preuve explicite de capacité | Accepted | M7 |
| [0015](0015-conservative-impact-analysis.md) | Traiter l’analyse d’impact comme une estimation potentielle du graphe observé | Accepted | M8 |
| [0016](0016-stable-cli-contract.md) | Stabiliser la CLI comme surface d’exposition du cœur métier | Accepted | M9 |
| [0017](0017-mcp-stdio-read-only.md) | Exposer MINOS en MCP via STDIO read-only | Accepted | M10 |
| [0018](0018-versioned-public-java-api.md) | Versionner une API Java publique indépendante des modèles internes | Accepted | M11 |
| [0019](0019-cross-repository-identity-and-git-facts.md) | Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git | Accepted | M12 |
| [0020](0020-minos-nexus-json-boundary.md) | Intégrer NEXUS par un contrat JSON local versionné | Accepted | M13 |

## Règle de rédaction

Un ADR doit capturer :

1. le contexte et la tension architecturale ;
2. la décision retenue ;
3. ses conséquences et limites ;
4. un lien vers les preuves historiques lorsqu’elles existent.

Un ADR ne doit pas devenir un journal de build ou de PR. Les nombres de tests, replays détaillés, benchmarks et états intermédiaires appartiennent à `history/milestones/`.
