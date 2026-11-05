# Architecture Decision Records

MINOS utilise les Architecture Decision Records (ADR) pour documenter les **choix techniques structurants et durables**.

Les ADR décrivent l’architecture courante et son raisonnement. Les preuves, mesures, SHA et résultats de validation propres à un jalon sont conservés séparément dans [`../../project/history/milestones/`](../../project/history/milestones/README.md).

## Statuts

- **Proposed** — en évaluation ;
- **Accepted** — direction architecturale courante ;
- **Superseded** — remplacée par un ADR plus récent ;
- **Rejected** — évaluée et non retenue.

## Quand écrire un ADR

Un ADR mémorise une **décision d'architecture durable, encore en vigueur, coûteuse à inverser**, dont les raisons se perdraient sans lui. Il faut répondre aux trois questions, sinon ce n'est pas un ADR :

1. **Décision** : quel choix entre plusieurs options réelles est fixé ? Un constat, une mesure, un plan ou un état d'avancement n'est pas une décision.
2. **Portée et durée** : contraint-il durablement plusieurs modules, une surface publique (CLI, API Java, MCP, NEXUS, plugin), un format persisté, un modèle de confiance ou de sécurité, la toolchain ou la livraison ?
3. **Coût du retour arrière** : l'inverser casserait-il des données, des contrats, des garanties de sécurité ou une part importante du code ?

| Ce n'est pas un ADR | Où l'écrire |
|---|---|
| état d'un jalon, preuve de qualification, SHA, mesure datée | `docs/project/history/` ou le suivi d'audit |
| plan, backlog, ordre de travail | `docs/project/roadmap/` ou un changement OpenSpec |
| exigence et scénarios d'un comportement | `openspec/specs/` par un changement OpenSpec |
| étude, comparaison, option sans décision | `docs/project/research/` |
| description d'un mécanisme, mode d'emploi | `docs/developer/` ou `docs/user/` |
| convention locale, nommage, style | `.claude/rules/` ou une règle de linter |
| correctif, fonctionnalité, classe | le commit et la PR |

**Cycle de vie.** Une proposition (`Proposed`) qui n'est portée par aucun changement OpenSpec ne reste pas indéfiniment au registre : elle est archivée `Rejected`. Un ADR amendé ne coexiste pas avec son amendement : si l'amendement couvre toute la décision, l'ancien passe `Superseded` ; s'il est partiel, un **ADR consolidé** réunit la décision en vigueur et remplace les deux. Un ADR `Superseded`, `Rejected` ou reclassé part dans [`archive/`](archive/) avec un bandeau qui dit ce qui le remplace ; il n'est jamais supprimé, et Archgate ne lit pas ce sous-dossier. Une décision réalisée une fois pour toutes, qui n'impose plus de règle au code, est archivée ; si elle impose encore une règle, l'ADR garde cette règle.

**Contrôle.** Un ADR actif dont la contrainte se vérifie par lecture de fichiers porte une règle Archgate compagnon (`{ID}-{slug}.rules.ts`) qui lit les mêmes sources que le gate Python correspondant ; sinon sa section `Compliance and Enforcement` dit comment la décision est tenue.

## Format

Chaque ADR suit le format et la convention Archgate ([GEN-0073](GEN-0073-tenir-le-registre-d-adr-au-format-archgate.md)) : fichier `{PRÉFIXE}-{NNNN}-{slug}.md`, en-tête YAML en ligne 1 (`id: {PRÉFIXE}-{NNNN}` — préfixe du domaine `ARCH`, `BE`, `DATA`, `GEN` ou `SEC`, numéro unique jamais renuméroté —, `title`, `domain`, `rules`, `files` facultatif), titre `#` égal au `title`, sections `Context`, `Decision`, `Do's and Don'ts`, `Consequences`, `Compliance and Enforcement`, `References`, texte en français. Archgate lit ce dossier (`.archgate/config.json`, `paths.adrs`) ; une règle Archgate vit à côté de son ADR (`{ID}-{slug}.rules.ts`). Gabarit : [`gabarit/template.md`](gabarit/template.md).

## Index

| ADR | Décision | Statut | Origine |
|---|---|---|---|
| [DATA-0006](DATA-0006-promouvoir-les-index-de-maniere-atomique.md) | Promouvoir les index de manière atomique | Accepted | M1 |
| [DATA-0007](DATA-0007-attribuer-identites-projet-workspace-dans-registre-local.md) | Attribuer les identités projet/workspace dans registre local | Accepted | M1 |
| [ARCH-0008](ARCH-0008-negocier-indexeurs-par-capacites-explicites.md) | Négocier les indexeurs par capacités explicites | Accepted | M1 |
| [DATA-0009](DATA-0009-normalized-symbol-identity.md) | Modéliser les identités de symboles sans inventer de canonicité | Accepted | M2 |
| [DATA-0010](DATA-0010-normalized-relationship-semantics.md) | Normaliser les relations avec provenance, preuve et confiance explicites | Accepted | M3 |
| [BE-0011](BE-0011-bounded-code-search-context.md) | Borner explicitement la recherche et le contexte de code | Accepted | M4 |
| [DATA-0012](DATA-0012-explainable-related-tests.md) | Conserver les tests liés comme dérivations explicables | Accepted | M5 |
| [BE-0014](BE-0014-safe-incremental-indexing.md) | N’utiliser l’indexation incrémentale que sous preuve explicite de capacité | Accepted | M7 |
| [BE-0015](BE-0015-conservative-impact-analysis.md) | Traiter l’analyse d’impact comme une estimation potentielle du graphe observé | Accepted | M8 |
| [ARCH-0016](ARCH-0016-stable-cli-contract.md) | Stabiliser la CLI comme surface d’exposition du cœur métier | Accepted | M9 |
| [ARCH-0017](ARCH-0017-mcp-stdio-read-only.md) | Exposer MINOS en MCP via STDIO read-only | Accepted | M10 |
| [ARCH-0018](ARCH-0018-versioned-public-java-api.md) | Versionner une API Java publique indépendante des modèles internes | Accepted | M11 |
| [DATA-0019](DATA-0019-cross-repository-identity-and-git-facts.md) | Résoudre les relations cross-repository uniquement par identité exacte et séparer les faits Git | Accepted | M12 |
| [ARCH-0020](ARCH-0020-minos-nexus-json-boundary.md) | Intégrer NEXUS par un contrat JSON local versionné | Accepted | M13 |
| [DATA-0024](DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md) | Mettre en cache une vue de snapshot actif et reconstruire ses indexes en mémoire | Accepted | M15-S7/S8 |
| [BE-0026](BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md) | Étendre discovery/providers par SPI et interdire les capacités implicites | Accepted | M17 |
| [ARCH-0027](ARCH-0027-intellij-external-client-and-versioned-cli-protocol.md) | Isoler le plugin IntelliJ en client Java 21 et négocier un protocole CLI JSON versionné | Accepted | M18 |
| [BE-0029](BE-0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md) | Garder la couche sémantique optionnelle, reconstruisible et distincte des faits structurés | Accepted | M20 |
| [BE-0030](BE-0030-java-ast-reference-provider-with-explicit-capability-limits.md) | Fournir un provider Java AST de référence avec limitations et capabilities explicites | Accepted | M22 |
| [BE-0031](BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) | Ajouter un provider learned local qualifié et conserver ANN derrière une décision mesurée | Accepted | M23 |
| [DATA-0034](DATA-0034-partial-runtime-observations-with-explicit-static-correlation.md) | Conserver les observations runtime partielles séparées des faits statiques et corrélées à un snapshot exact | Accepted | M26 |
| [BE-0039](BE-0039-reprise-indexation-apres-interruption.md) | Reprendre une indexation interrompue au lieu de tout réindexer | Accepted | — |
| [ARCH-0042](ARCH-0042-racine-de-composition.md) | Racine de composition une fois `minos-application` réduite à ses ports | Accepted (option c : module minos-bootstrap) | Audit 2026-09, A2 |
| [GEN-0043](GEN-0043-retrait-des-artefacts-de-jalon.md) | Politique de retrait des scripts et workflows de jalon (permanent / gelé par assertion / archivé) | Accepted | Audit 2026-09, G3 |
| [ARCH-0056](ARCH-0056-ports-de-lecture-des-snapshots.md) | Port de lecture neutre et compatibilité de SnapshotQueryView | Accepted |
| [ARCH-0057](ARCH-0057-finaliser-les-frontieres-hexagonales.md) | Cas d'usage partagés et frontières résiduelles, bootstrap existant préservé | Accepted — travaux résiduels |
| [ARCH-0058](ARCH-0058-declarer-les-usages-de-modules.md) | Tout module utilisé est déclaré ; la règle ArchUnit stricte devient obligatoire | Accepted |
| [SEC-0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) | Écrire un fichier privé à deux niveaux de confidentialité : durci, ou hérité de la racine | Accepted |
| [ARCH-0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) | Cœur agnostique du langage et de l'indexeur, SCIP protocole privilégié | Accepted | Consolidation du registre, 2026-10-23 |
| [BE-0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) | Providers SCIP polyglottes sous preuve, livrés dans le paquet | Accepted | Consolidation du registre, 2026-10-23 |
| [DATA-0066](DATA-0066-port-de-connaissance-et-persistance-des-snapshots.md) | Port de connaissance et persistance des snapshots : format V3, compatibilité, dédoublonnage | Accepted | Consolidation du registre, 2026-10-23 |
| [GEN-0067](GEN-0067-socle-d-execution-java-25-et-maven.md) | Socle d'exécution : Java 25, Maven par le wrapper, sans framework serveur | Accepted | Consolidation du registre, 2026-10-23 |
| [ARCH-0068](ARCH-0068-modules-dependances-et-propriete-des-packages.md) | Modules, dépendances et propriété des packages | Accepted | Consolidation du registre, 2026-10-23 |
| [ARCH-0069](ARCH-0069-backends-mcp-natif-et-docker.md) | Backends MCP natif et Docker | Accepted | Consolidation du registre, 2026-10-23 |
| [SEC-0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) | Code non fiable : confinement des providers et indexation distante | Accepted | Consolidation du registre, 2026-10-23 |
| [SEC-0071](SEC-0071-plan-de-controle-d-equipe-embarque.md) | Plan de contrôle d'équipe embarqué | Accepted | Consolidation du registre, 2026-10-23 |
| [ARCH-0072](ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md) | Graphe de programme honnête sur ses capacités | Accepted | Consolidation du registre, 2026-10-23 |
| [GEN-0073](GEN-0073-tenir-le-registre-d-adr-au-format-archgate.md) | Tenir le registre d'ADR au format Archgate, à l'emplacement arc42 (convention d'identifiant, critères, cycle de vie) | Accepted | Décision du propriétaire, 2026-10-23 |
| [SEC-0074](SEC-0074-java-non-indexable-depuis-windows-voie-wsl2.md) | scip-java sous Windows : bloqué en natif, aucune voie d'indexation Java, WSL2 retenu pour la suite | Accepted | Décision du propriétaire, 2026-10-31 (remplace ADR-0060) |
| [SEC-0075](SEC-0075-exposer-au-runtime-docker-seulement-les-projets-inscrits.md) | Le runtime Docker n'expose que les projets inscrits | Accepted | Décision du propriétaire, 2026-11-01 |
| [SEC-0076](SEC-0076-depot-maven-designe-en-lecture-seule-et-maven-hors-ligne.md) | scip-java sous Linux : dépôt Maven désigné en lecture seule, Maven hors ligne, réseau fermé | Accepted | Décision du propriétaire, 2026-10-31 (complète SEC-0074) |

## Archives

ADR remplacés, rejetés ou reclassés (non lus par Archgate) :

| ADR | Décision | Statut d'archive |
|---|---|---|
| [0001](archive/0001-language-and-indexer-agnostic-core.md) | Conserver un cœur MINOS agnostique du langage et de l'indexeur | Superseded by ADR-0064 |
| [0002](archive/0002-scip-preferred-interoperability-protocol.md) | Utiliser SCIP comme protocole d'interopérabilité sémantique privilégié | Superseded by ADR-0064 |
| [0003](archive/0003-glean-behind-code-knowledge-store.md) | Isoler le backend de connaissance et traiter Glean comme backend avancé optionnel | Superseded by ADR-0066 |
| [0004](archive/0004-stack-java-maven-core-sans-framework.md) | Implémenter le cœur MINOS en Java 25 avec Maven, sans framework serveur | Superseded by ADR-0067 |
| [0005](archive/0005-aligner-java-24-environnement-developpement.md) | Aligner MINOS sur Java 24 avec l'environnement de développement | Superseded by ADR-0067 |
| [0013](archive/0013-factual-architecture-intelligence.md) | Séparer les faits d’architecture de leur interprétation | Archivé, reclassé : exigence de sortie de l'analyse d'architecture, tenue par la capacité OpenSpec `program-graph-et-architecture`, pas une décision d'architecture |
| [0021](archive/0021-native-runtime-autonomous-indexing.md) | Utiliser un runtime MINOS natif pour l'indexation autonome | Superseded by ADR-0069 |
| [0022](archive/0022-maven-reactor-and-module-boundaries.md) | Imposer les frontières MINOS par un reactor Maven progressif | Superseded by ADR-0068 |
| [0023](archive/0023-decomposed-local-snapshot-persistence.md) | Décomposer la persistance locale des snapshots sans changer le format disque | Superseded by ADR-0066 |
| [0025](archive/0025-measurement-gated-storage-backend-evolution.md) | Gouverner l’évolution du backend par des mesures reproductibles | Superseded by ADR-0066 |
| [0028](archive/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md) | Capability-honest program graph and bounded advanced analysis | Superseded by ADR-0072 |
| [0032](archive/0032-evidence-gated-polyglot-scip-providers.md) | Evidence-gated polyglot SCIP providers | Superseded by ADR-0065 |
| [0033](archive/0033-immutable-remote-revisions-and-verified-worker-artifacts.md) | Immutable remote revisions and verified worker artifacts | Superseded by ADR-0070 |
| [0035](archive/0035-opt-in-tenant-control-plane-with-external-keys.md) | Opt-in tenant control plane with external keys | Superseded by ADR-0071 |
| [0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) | Fail-closed production boundaries and measured ProgramGraph convergence | Superseded by ADR-0070, ADR-0071, ADR-0072 (§ 3, § 4, § 1-2) |
| [0037](archive/0037-first-class-native-and-docker-runtime-backends.md) | Traiter native et Docker comme backends MCP explicites et fail-closed | Superseded by ADR-0069 |
| [0038](archive/0038-aggregate-worker-resource-containment.md) | Confiner les ressources des workers non fiables de manière agrégée et fail-closed | Superseded by ADR-0070 |
| [0040](archive/0040-distribution-auto-portante-indexeurs-embarques.md) | Livrer les indexeurs dans le paquet, pas après l'installation | Superseded by ADR-0065 |
| [0041](archive/0041-indexation-distante-de-code-non-fiable.md) | Indexation distante de code non fiable : quota d'écriture OS ou fermeture assumée | Superseded by ADR-0070 |
| [0044](archive/0044-un-package-un-module.md) | Un package, un module | Superseded by ADR-0068 |
| [0045](archive/0045-constructeur-unique-et-point-d-entree-nomme.md) | Constructeur unique et point d'entrée nommé ; racine de composition regroupée par domaine | Archivé, reclassé : convention de code tenue par `.claude/rules/java-production.md` (constructeur unique, point d'entrée nommé) ; la refonte de la racine de composition est réalisée |
| [0046](archive/0046-format-de-snapshot-v3-chaines-utf8.md) | Format de snapshot V3 : chaînes UTF-8, lecture de tous les formats antérieurs | Superseded by ADR-0066 |
| [0047](archive/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) | Snapshot en mémoire : dédoublonnage des chaînes, table de chaînes, pagination ou mappage | Superseded by ADR-0066 |
| [0048](archive/0048-evaluation-comparative-reproductible.md) | Évaluer les changements sur un protocole commun | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0049](archive/0049-retrieval-hybride-explicable.md) | Faire évoluer le classement hybride par profils mesurés | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0050](archive/0050-unites-recherche-contexte-budgete.md) | Assembler un contexte cohérent, compact et traçable | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0051](archive/0051-embeddings-cpu-optionnels.md) | Évaluer un provider d'embeddings CPU léger et local | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0052](archive/0052-fraicheur-hors-mcp.md) | Actualiser les index hors du chemin MCP en lecture seule | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0053](archive/0053-mcp-profils-progressifs.md) | Réduire le coût de découverte MCP avec des profils explicites | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0054](archive/0054-bridge-intellij-symbolique-optionnel.md) | Étudier un bridge IntelliJ de lecture symbolique | Rejected (proposition sans mise en œuvre ni changement OpenSpec) ; l'étude reste dans `docs/project/research/minos-evolution-2026-10/` |
| [0055](archive/0055-unifier-les-adaptateurs-de-stockage.md) | Réunir les adaptateurs dans minos-storage | Superseded by ADR-0068 |
| [0059](archive/0059-migrer-le-socle-vers-java-25-lts.md) | Migrer le socle MINOS vers Java 25 LTS et garder la baseline par un gate | Superseded by ADR-0067 |
| [0062](archive/0062-confirmer-l-adr-0044-et-geler-les-racines-de-packages.md) | Confirmer les emplacements de l'ADR 0044 et ne créer de package que sous les racines de son module | Superseded by ADR-0068 |
| [0063](archive/0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md) | Écrire les ADR au format Archgate dans le registre arc42 | Superseded by GEN-0073 |
| [0060](archive/0060-scip-java-windows-natif-bloque-indexation-java-par-docker.md) | scip-java sous Windows : non prêt en natif, indexation Java par le runtime Docker | Superseded by SEC-0074 (le runtime Docker ne lance aucun provider) |

[Backlog, tâches et démarrage Claude](../../project/roadmap/storage-hexagonal-2026-10/README.md).

## Règle de rédaction

Un ADR doit capturer :

1. le contexte et la tension architecturale ;
2. la décision retenue ;
3. ses conséquences et limites ;
4. un lien vers les preuves historiques lorsqu’elles existent.

Un ADR ne doit pas devenir un journal de build ou de PR. Les nombres de tests, replays détaillés, benchmarks et états intermédiaires appartiennent aux preuves de qualification.
