# Section 9 — Décisions architecturales

> Index des ADR existants dans `docs/adr/`. Ne pas reproduire les ADR ici.
> Pour lire un ADR complet, suivre le lien de la colonne « Fichier ».

---

## Index des ADR

| Identifiant | Titre | Statut | Date | Remplace / Remplacé par |
|-------------|-------|--------|------|------------------------|
| [ADR-0001](../../adr/0001-language-and-indexer-agnostic-core.md) | Conserver un cœur MINOS agnostique du langage et de l'indexeur | Accepted | 2026-07-22 | — |
| [ADR-0002](../../adr/0002-scip-preferred-interoperability-protocol.md) | Utiliser SCIP comme protocole d'interopérabilité d'indexation sémantique privilégié | Accepted | 2026-07-22 | — |
| [ADR-0003](../../adr/0003-glean-behind-code-knowledge-store.md) | Garder Glean optionnel derrière un port CodeKnowledgeStore MINOS | Accepted | 2026-07-22 | — |
| [ADR-0004](../../adr/0004-stack-java-maven-core-sans-framework.md) | Implémenter le cœur MINOS avec Maven sans framework serveur | Partiellement remplacé par ADR-0005 | 2026-07-22 | ADR-0005 |
| [ADR-0005](../../adr/0005-aligner-java-24-environnement-developpement.md) | Aligner MINOS sur Java 24 | Accepted | 2026-07-22 | Remplace partiellement ADR-0004 |
| [ADR-0006](../../adr/0006-promouvoir-les-index-de-maniere-atomique.md) | Promouvoir les index de manière atomique | Accepted | 2026-07-22 | — |
| [ADR-0007](../../adr/0007-attribuer-identites-projet-workspace-dans-registre-local.md) | Attribuer les identités projet/workspace dans le registre local | Accepted | 2026-07-22 | — |
| [ADR-0008](../../adr/0008-negocier-indexeurs-par-capacites-explicites.md) | Négocier les indexeurs par capacités explicites | Accepted | 2026-07-23 | — |
| [ADR-0009](../../adr/0009-normalized-symbol-identity.md) | Modéliser les identités de symboles sans inventer de canonicité | Accepted | 2026-07-23 | — |
| [ADR-0010](../../adr/0010-normalized-relationship-semantics.md) | Normaliser les relations avec provenance, preuve et confiance explicites | Accepted | 2026-07-23 | — |
| [ADR-0011](../../adr/0011-bounded-code-search-context.md) | Borner explicitement la recherche et le contexte de code | Accepted | 2026-07-23 | — |
| [ADR-0012](../../adr/0012-explainable-related-tests.md) | Conserver les tests liés comme dérivations explicables | Accepted | 2026-07-23 | — |
| [ADR-0013](../../adr/0013-factual-architecture-intelligence.md) | Séparer les faits d'architecture de leur interprétation | Accepted | 2026-07-24 | — |
| [ADR-0014](../../adr/0014-safe-incremental-indexing.md) | N'utiliser l'indexation incrémentale que sous preuve explicite de capacité | Accepted | 2026-07-24 | — |
| [ADR-0015](../../adr/0015-conservative-impact-analysis.md) | Traiter l'analyse d'impact comme une estimation potentielle du graphe observé | Accepted | 2026-07-24 | — |
| [ADR-0016](../../adr/0016-stable-cli-contract.md) | Stabiliser la CLI comme surface d'exposition du cœur métier | Accepted | 2026-07-24 | — |
| [ADR-0017](../../adr/0017-mcp-stdio-read-only.md) | Exposer MINOS en MCP via STDIO read-only | Accepted | 2026-07-24 | — |
| [ADR-0018](../../adr/0018-versioned-public-java-api.md) | Versionner une API Java publique indépendante des modèles internes | Accepted | 2026-07-24 | — |
| [ADR-0019](../../adr/0019-cross-repository-identity-and-git-facts.md) | Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git | Accepted | 2026-07-24 | — |
| [ADR-0020](../../adr/0020-minos-nexus-json-boundary.md) | Intégrer NEXUS par un contrat JSON local versionné | Accepted | 2026-07-25 | — |
| [ADR-0021](../../adr/0021-native-runtime-autonomous-indexing.md) | Utiliser un runtime MINOS natif pour l'indexation autonome | Partiellement remplacé par ADR-0037 | 2026-07-25 | ADR-0037 |
| [ADR-0022](../../adr/0022-maven-reactor-and-module-boundaries.md) | Imposer les frontières MINOS par un reactor Maven progressif | Accepted | 2026-07-26 | — |
| [ADR-0023](../../adr/0023-decomposed-local-snapshot-persistence.md) | Décomposer la persistance locale des snapshots sans changer le format disque | Accepted | 2026-07-26 | — |
| [ADR-0024](../../adr/0024-active-snapshot-query-view-and-rebuildable-indexes.md) | Mettre en cache une vue de snapshot actif et reconstruire ses indexes en mémoire | Accepted | 2026-07-26 | — |
| [ADR-0025](../../adr/0025-measurement-gated-storage-backend-evolution.md) | Gouverner l'évolution du backend par des mesures reproductibles | Accepted | 2026-07-27 | — |
| [ADR-0026](../../adr/0026-discovery-provider-spi-and-explicit-capability-profiles.md) | Étendre discovery/providers par SPI et interdire les capacités implicites | Accepted | 2026-07-27 | — |
| [ADR-0027](../../adr/0027-intellij-external-client-and-versioned-cli-protocol.md) | Isoler le plugin IntelliJ en client Java 21 et négocier un protocole CLI JSON versionné | Accepted | 2026-07-27 | — |
| [ADR-0028](../../adr/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md) | Composer un program graph capability-honest et borner toutes les analyses avancées | Accepted | 2026-07-27 | — |
| [ADR-0029](../../adr/0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md) | Garder la couche sémantique optionnelle, reconstruisible et distincte des facts structurés | Accepted | 2026-07-27 | — |
| [ADR-0030](../../adr/0030-java-ast-reference-provider-with-explicit-capability-limits.md) | Fournir un provider Java AST de référence avec limitations et capabilities explicites | Accepted | 2026-07-28 | — |
| [ADR-0031](../../adr/0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) | Ajouter un provider learned local qualifié et conserver ANN derrière une décision mesurée | Accepted | 2026-07-28 | — |
| [ADR-0032](../../adr/0032-evidence-gated-polyglot-scip-providers.md) | Étendre les providers SCIP polyglottes uniquement sous preuves de capability, identité, provenance et plateforme | Accepted | 2026-07-28 | — |
| [ADR-0033](../../adr/0033-immutable-remote-revisions-and-verified-worker-artifacts.md) | Épingler les sources distantes et n'accepter que des artefacts worker bornés, vérifiés et concordants | Accepted | 2026-07-29 | — |
| [ADR-0034](../../adr/0034-partial-runtime-observations-with-explicit-static-correlation.md) | Conserver les observations runtime partielles séparées des faits statiques et corrélées à un snapshot exact | Accepted | 2026-07-29 | — |
| [ADR-0035](../../adr/0035-opt-in-tenant-control-plane-with-external-keys.md) | Ajouter un contrôle tenant opt-in, chiffré, audité et alimenté par des clés externes | Accepted | 2026-07-29 | — |
| [ADR-0036](../../adr/0036-fail-closed-production-boundaries-and-measured-program-graph.md) | Converger par mesures et interdire les claims sandbox/hosted non qualifiés | Proposed | 2026-07-30 | — |
| [ADR-0037](../../adr/0037-first-class-native-and-docker-runtime-backends.md) | Router `minos mcp` vers un backend natif ou Docker explicite, versionné et fail-closed | Accepted — parité pending | 2026-08-02 | Remplace partiellement ADR-0021 |
