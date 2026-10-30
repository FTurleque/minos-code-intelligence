# Section 9 — Décisions architecturales

> Index des ADR existants dans `docs/architecture/adr/`. Ne pas reproduire les ADR ici.
> Pour lire un ADR complet, suivre le lien de la colonne « Fichier ».

---

## Index des ADR

| Identifiant | Titre | Statut | Date | Remplace / Remplacé par |
|-------------|-------|--------|------|------------------------|
| [DATA-0006](../adr/DATA-0006-promouvoir-les-index-de-maniere-atomique.md) | Promouvoir les index de manière atomique | Accepted | 2026-07-22 | — |
| [DATA-0007](../adr/DATA-0007-attribuer-identites-projet-workspace-dans-registre-local.md) | Attribuer les identités projet/workspace dans le registre local | Accepted | 2026-07-22 | — |
| [ARCH-0008](../adr/ARCH-0008-negocier-indexeurs-par-capacites-explicites.md) | Négocier les indexeurs par capacités explicites | Accepted | 2026-07-22 | — |
| [DATA-0009](../adr/DATA-0009-normalized-symbol-identity.md) | Modéliser les identités de symboles sans inventer de canonicité | Accepted | 2026-07-24 | — |
| [DATA-0010](../adr/DATA-0010-normalized-relationship-semantics.md) | Normaliser les relations avec provenance, preuve et confiance explicites | Accepted | 2026-07-24 | — |
| [BE-0011](../adr/BE-0011-bounded-code-search-context.md) | Borner explicitement la recherche et le contexte de code | Accepted | 2026-07-24 | — |
| [DATA-0012](../adr/DATA-0012-explainable-related-tests.md) | Conserver les tests liés comme dérivations explicables | Accepted | 2026-07-24 | — |
| [BE-0014](../adr/BE-0014-safe-incremental-indexing.md) | N'utiliser l'indexation incrémentale que sous preuve explicite de capacité | Accepted | 2026-07-24 | — |
| [BE-0015](../adr/BE-0015-conservative-impact-analysis.md) | Traiter l'analyse d'impact comme une estimation potentielle du graphe observé | Accepted | 2026-07-24 | — |
| [ARCH-0016](../adr/ARCH-0016-stable-cli-contract.md) | Stabiliser la CLI comme surface d'exposition du cœur métier | Accepted | 2026-07-24 | — |
| [ARCH-0017](../adr/ARCH-0017-mcp-stdio-read-only.md) | Exposer MINOS en MCP via STDIO read-only | Accepted | 2026-07-24 | — |
| [ARCH-0018](../adr/ARCH-0018-versioned-public-java-api.md) | Versionner une API Java publique indépendante des modèles internes | Accepted | 2026-07-24 | — |
| [DATA-0019](../adr/DATA-0019-cross-repository-identity-and-git-facts.md) | Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git | Accepted | 2026-07-24 | — |
| [ARCH-0020](../adr/ARCH-0020-minos-nexus-json-boundary.md) | Intégrer NEXUS par un contrat JSON local versionné | Accepted | 2026-07-24 | — |
| [DATA-0024](../adr/DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md) | Mettre en cache une vue de snapshot actif et reconstruire ses indexes en mémoire | Accepted | 2026-07-26 | — |
| [BE-0026](../adr/BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md) | Étendre discovery/providers par SPI et interdire les capacités implicites | Accepted | 2026-07-27 | — |
| [ARCH-0027](../adr/ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md) | Isoler le plugin IntelliJ en client Java 21 et négocier un protocole CLI JSON versionné | Accepted | 2026-07-27 | — |
| [BE-0029](../adr/BE-0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md) | Garder la couche sémantique optionnelle, reconstruisible et distincte des faits structurés | Accepted | 2026-07-27 | — |
| [BE-0030](../adr/BE-0030-java-ast-reference-provider-with-explicit-capability-limits.md) | Fournir un provider Java AST de référence avec limitations et capabilities explicites | Accepted | 2026-07-28 | — |
| [BE-0031](../adr/BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) | Ajouter un provider learned local qualifié et conserver ANN derrière une décision mesurée | Accepted | 2026-07-28 | — |
| [DATA-0034](../adr/DATA-0034-partial-runtime-observations-with-explicit-static-correlation.md) | Conserver les observations runtime partielles séparées des faits statiques et corrélées à un snapshot exact | Accepted | 2026-07-29 | — |
| [BE-0039](../adr/BE-0039-reprise-indexation-apres-interruption.md) | Reprendre une indexation interrompue au lieu de tout réindexer | Accepted — implémenté au sprint 2 | 2026-09-26 | — |
| [ARCH-0042](../adr/ARCH-0042-racine-de-composition.md) | Racine de composition une fois `minos-application` réduite à ses ports | Accepted — option (c) retenue | 2026-09-27 | — |
| [GEN-0043](../adr/GEN-0043-retrait-des-artefacts-de-jalon.md) | Politique de retrait des scripts et workflows de jalon | Accepted | — | — |
| [ARCH-0056](../adr/ARCH-0056-ports-de-lecture-des-snapshots.md) | Port de lecture neutre et compatibilité de SnapshotQueryView | Accepted | 2026-10-05 | — |
| [ARCH-0057](../adr/ARCH-0057-finaliser-les-frontieres-hexagonales.md) | Cas d'usage partagés et frontières résiduelles, bootstrap existant préservé | Accepted — travaux résiduels planifiés | 2026-10-05 | — |
| [ARCH-0058](../adr/ARCH-0058-declarer-les-usages-de-modules.md) | Déclarer chaque module utilisé | Accepted | 2026-10-09 | — |
| [SEC-0060](../adr/SEC-0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md) | scip-java sous Windows : non prêt en natif, indexation Java par le runtime Docker | Accepted | 2026-10-18 | — |
| [SEC-0061](../adr/SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) | Écrire un fichier privé à deux niveaux de confidentialité | Accepted | 2026-10-23 | — |
| [ARCH-0064](../adr/ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) | Cœur agnostique du langage et de l'indexeur, SCIP protocole privilégié | Accepted | 2026-10-23 | Remplace ADR-0001, ADR-0002 |
| [BE-0065](../adr/BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) | Providers SCIP polyglottes sous preuve, livrés dans le paquet | Accepted | 2026-10-23 | Remplace ADR-0032, ADR-0040 |
| [DATA-0066](../adr/DATA-0066-port-de-connaissance-et-persistance-des-snapshots.md) | Port de connaissance et persistance des snapshots : format V3, compatibilité, dédoublonnage | Accepted | 2026-10-23 | Remplace ADR-0003, ADR-0023, ADR-0046, ADR-0047 |
| [GEN-0067](../adr/GEN-0067-socle-d-execution-java-25-et-maven.md) | Socle d'exécution : Java 25, Maven par le wrapper, sans framework serveur | Accepted | 2026-10-23 | Remplace ADR-0004, ADR-0005, ADR-0059 |
| [ARCH-0068](../adr/ARCH-0068-modules-dependances-et-propriete-des-packages.md) | Modules, dépendances et propriété des packages | Accepted | 2026-10-23 | Remplace ADR-0022, ADR-0044, ADR-0055, ADR-0062 |
| [ARCH-0069](../adr/ARCH-0069-backends-mcp-natif-et-docker.md) | Backends MCP natif et Docker | Accepted | 2026-10-23 | Remplace ADR-0021, ADR-0037 |
| [SEC-0070](../adr/SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) | Code non fiable : confinement des providers et indexation distante | Accepted | 2026-10-23 | Remplace ADR-0033, ADR-0036, ADR-0038, ADR-0041 |
| [SEC-0071](../adr/SEC-0071-plan-de-controle-d-equipe-embarque.md) | Plan de contrôle d'équipe embarqué | Accepted | 2026-10-23 | Remplace ADR-0035, ADR-0036 |
| [ARCH-0072](../adr/ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md) | Graphe de programme honnête sur ses capacités | Accepted | 2026-10-23 | Remplace ADR-0028, ADR-0036 |
| [GEN-0073](../adr/GEN-0073-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 | Accepted | 2026-10-23 | Remplace ADR-0063 |

## ADR archivés

Remplacés, rejetés ou reclassés le 2026-10-23 (tri du registre) ; conservés dans `docs/architecture/adr/archive/`.

| Identifiant | Titre | Statut d'archive |
|---|---|---|
| [ADR-0001](../adr/archive/0001-language-and-indexer-agnostic-core.md) | Conserver un cœur MINOS agnostique du langage et de l'indexeur | Superseded by ADR-0064 |
| [ADR-0002](../adr/archive/0002-scip-preferred-interoperability-protocol.md) | Utiliser SCIP comme protocole d'interopérabilité sémantique privilégié | Superseded by ADR-0064 |
| [ADR-0003](../adr/archive/0003-glean-behind-code-knowledge-store.md) | Isoler le backend de connaissance et traiter Glean comme backend avancé optionnel | Superseded by ADR-0066 |
| [ADR-0004](../adr/archive/0004-stack-java-maven-core-sans-framework.md) | Implémenter le cœur MINOS en Java 25 avec Maven, sans framework serveur | Superseded by ADR-0067 |
| [ADR-0005](../adr/archive/0005-aligner-java-24-environnement-developpement.md) | Aligner MINOS sur Java 24 avec l'environnement de développement | Superseded by ADR-0067 |
| [ADR-0013](../adr/archive/0013-factual-architecture-intelligence.md) | Séparer les faits d’architecture de leur interprétation | Archivé, reclassé |
| [ADR-0021](../adr/archive/0021-native-runtime-autonomous-indexing.md) | Utiliser un runtime MINOS natif pour l'indexation autonome | Superseded by ADR-0069 |
| [ADR-0022](../adr/archive/0022-maven-reactor-and-module-boundaries.md) | Imposer les frontières MINOS par un reactor Maven progressif | Superseded by ADR-0068 |
| [ADR-0023](../adr/archive/0023-decomposed-local-snapshot-persistence.md) | Décomposer la persistance locale des snapshots sans changer le format disque | Superseded by ADR-0066 |
| [ADR-0025](../adr/archive/0025-measurement-gated-storage-backend-evolution.md) | Gouverner l’évolution du backend par des mesures reproductibles | Superseded by ADR-0066 |
| [ADR-0028](../adr/archive/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md) | Capability-honest program graph and bounded advanced analysis | Superseded by ADR-0072 |
| [ADR-0032](../adr/archive/0032-evidence-gated-polyglot-scip-providers.md) | Evidence-gated polyglot SCIP providers | Superseded by ADR-0065 |
| [ADR-0033](../adr/archive/0033-immutable-remote-revisions-and-verified-worker-artifacts.md) | Immutable remote revisions and verified worker artifacts | Superseded by ADR-0070 |
| [ADR-0035](../adr/archive/0035-opt-in-tenant-control-plane-with-external-keys.md) | Opt-in tenant control plane with external keys | Superseded by ADR-0071 |
| [ADR-0036](../adr/archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) | Fail-closed production boundaries and measured ProgramGraph convergence | Superseded by ADR-0070, ADR-0071, ADR-0072 (§ 3, § 4, § 1-2) |
| [ADR-0037](../adr/archive/0037-first-class-native-and-docker-runtime-backends.md) | Traiter native et Docker comme backends MCP explicites et fail-closed | Superseded by ADR-0069 |
| [ADR-0038](../adr/archive/0038-aggregate-worker-resource-containment.md) | Confiner les ressources des workers non fiables de manière agrégée et fail-closed | Superseded by ADR-0070 |
| [ADR-0040](../adr/archive/0040-distribution-auto-portante-indexeurs-embarques.md) | Livrer les indexeurs dans le paquet, pas après l'installation | Superseded by ADR-0065 |
| [ADR-0041](../adr/archive/0041-indexation-distante-de-code-non-fiable.md) | Indexation distante de code non fiable : quota d'écriture OS ou fermeture assumée | Superseded by ADR-0070 |
| [ADR-0044](../adr/archive/0044-un-package-un-module.md) | Un package, un module | Superseded by ADR-0068 |
| [ADR-0045](../adr/archive/0045-constructeur-unique-et-point-d-entree-nomme.md) | Constructeur unique et point d'entrée nommé ; racine de composition regroupée par domaine | Archivé, reclassé |
| [ADR-0046](../adr/archive/0046-format-de-snapshot-v3-chaines-utf8.md) | Format de snapshot V3 : chaînes UTF-8, lecture de tous les formats antérieurs | Superseded by ADR-0066 |
| [ADR-0047](../adr/archive/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) | Snapshot en mémoire : dédoublonnage des chaînes, table de chaînes, pagination ou mappage | Superseded by ADR-0066 |
| [ADR-0048](../adr/archive/0048-evaluation-comparative-reproductible.md) | Évaluer les changements sur un protocole commun | Rejected |
| [ADR-0049](../adr/archive/0049-retrieval-hybride-explicable.md) | Faire évoluer le classement hybride par profils mesurés | Rejected |
| [ADR-0050](../adr/archive/0050-unites-recherche-contexte-budgete.md) | Assembler un contexte cohérent, compact et traçable | Rejected |
| [ADR-0051](../adr/archive/0051-embeddings-cpu-optionnels.md) | Évaluer un provider d'embeddings CPU léger et local | Rejected |
| [ADR-0052](../adr/archive/0052-fraicheur-hors-mcp.md) | Actualiser les index hors du chemin MCP en lecture seule | Rejected |
| [ADR-0053](../adr/archive/0053-mcp-profils-progressifs.md) | Réduire le coût de découverte MCP avec des profils explicites | Rejected |
| [ADR-0054](../adr/archive/0054-bridge-intellij-symbolique-optionnel.md) | Étudier un bridge IntelliJ de lecture symbolique | Rejected |
| [ADR-0055](../adr/archive/0055-unifier-les-adaptateurs-de-stockage.md) | Réunir les adaptateurs dans minos-storage | Superseded by ADR-0068 |
| [ADR-0059](../adr/archive/0059-migrer-le-socle-vers-java-25-lts.md) | Migrer le socle MINOS vers Java 25 LTS et garder la baseline par un gate | Superseded by ADR-0067 |
| [ADR-0062](../adr/archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md) | Confirmer les emplacements de l'ADR 0044 et ne créer de package que sous les racines de son module | Superseded by ADR-0068 |
| [ADR-0063](../adr/archive/0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md) | Écrire les ADR au format Archgate dans le registre arc42 | Superseded by GEN-0073 |
