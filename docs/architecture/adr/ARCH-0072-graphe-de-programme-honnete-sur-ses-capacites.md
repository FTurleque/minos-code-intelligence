---
id: ARCH-0072
title: "Graphe de programme honnête sur ses capacités"
domain: architecture
rules: false
---

# Graphe de programme honnête sur ses capacités

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0028](archive/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md), [ADR-0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 1 et § 2
- **Remplacé par** : —

## Context

- MINOS expose un graphe d'appels, du flux de contrôle, du flux de données, une propagation interprocédurale, une vue Code Property Graph, l'analyse d'impact v2 et des chemins orientés sécurité. Les snapshots persistent des symboles, occurrences et relations normalisés, mais les providers n'exposent pas uniformément des faits de flux de contrôle ou de données (ADR 0028, Context).
- Promouvoir des faits absents en arêtes de CFG ou de flux apparemment exactes violerait la distinction de MINOS entre information factuelle, dérivée et heuristique (ADR 0028, Context).
- Un provider Java avancé qui concentre découverte, parsing, CFG, def-use, flux interprocédural, taint et assemblage dans une seule implémentation masque ses responsabilités, et une analyse sur un arbre de travail qui diffère du snapshot actif produit des faits périmés (ADR 0036, Context et Rejected alternatives).
- Le graphe de programme a besoin d'une décision de performance explicite plutôt que d'une migration de stockage ou de cache présumée (ADR 0036, Context).

## Decision

### 1. Un modèle et une SPI indépendants du provider (ADR 0028)

MINOS fournit un modèle `ProgramGraph` et une SPI `ProgramGraphProvider` indépendants du provider. L'analyse avancée suit ces règles :

1. Le snapshot Code Knowledge persisté reste l'autorité.
2. Les graphes de programme sont des vues reconstructibles ; aucun format persisté autoritatif nouveau n'est requis.
3. Les relations `CALLS` historiques peuvent être projetées directement en arêtes du graphe d'appels en conservant nature, confiance, provenance et preuve.
4. Les relations `READS`/`WRITES` historiques ne produisent qu'un flux local *potentiel*, explicitement dérivé, qui porte la limitation `EXECUTION_ORDER_NOT_PROVEN`.
5. Un CFG exact, un def-use exact et des annotations de sécurité exigent un `ProgramGraphProvider` explicitement capable ; une capacité indisponible reste indisponible.
6. Plusieurs providers se composent de manière déterministe. Une collision d'identifiant stable dont le contenu diffère est rejetée, jamais écrasée silencieusement.
7. L'analyse interprocédurale est bornée par la profondeur et les limites de résultats demandées, et signale cycles et troncature.
8. Le CPG est une vue de requête composée, pas une seconde source de vérité.
9. L'analyse de sécurité rapporte les chemins source → sink observés et les sanitizers ; elle ne revendique ni la présence ni l'absence exhaustive de vulnérabilité. Le provider Java lit ses règles source, sink et sanitizer uniquement dans le dépôt analysé (`.minos/java-advanced-provider.properties`), qui n'est pas de confiance : toute réponse construite sur ces règles porte la limitation additive `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`, relayée par `minos_program_graph`, `minos_impact_v2` et `minos_security_paths`. Un sanitizer déclaré ne retire jamais un chemin : le chemin est rapporté avec `sanitizedPathObserved=true`. Des règles fournies par l'opérateur hors du dépôt ne sont pas prises en charge et relèvent d'une décision ultérieure distincte (ADR 0028 § 9, note AUD-SEC-11 du 2026-10-17).
10. L'exposition par l'API publique et le MCP est additive et versionnée indépendamment du contrat stable `MinosApi` v1 ([ADR 0018](ARCH-0018-versioned-public-java-api.md), [ADR 0017](ARCH-0017-mcp-stdio-read-only.md)).

### 2. Un provider Java de production contraint par le snapshot exact (ADR 0036 § 1)

- La composition de production utilise `FingerprintConstrainedJavaProgramGraphProvider`. L'empreinte exacte persistée du projet fait partie de la clé de cache. En cas d'absence dans le cache, chaque source Java représentée par le snapshot structuré actif est vérifiée contre sa taille et son SHA-256 persistés avant l'analyse AST.
- Un arbre de travail qui diffère du snapshot actif échoue fermé avec `JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT`.
- Le provider Java de référence et ses limites de capacité explicites relèvent de l'[ADR 0030](BE-0030-java-ast-reference-provider-with-explicit-capability-limits.md), qui reste en vigueur.

### 3. Des responsabilités décomposées sans changer les revendications (ADR 0036 § 2)

`JavaSourceProgramGraphProvider` reste une façade stable. Découverte et empreinte, parsing AST, CFG, def-use, résolution interprocédurale, taint, émission déterministe du graphe et assemblage des capacités vivent dans des composants ciblés. La décomposition préserve :

- des identifiants de nœuds et d'arêtes déterministes ;
- la provenance du provider ;
- la preuve et la confiance ;
- la sémantique de capacités et de limitations ;
- l'absence de revendication d'attribution par le compilateur à partir de données AST issues du seul parsing.

### 4. Une évolution du backend soumise à la mesure (ADR 0036 § 1 et Rejected alternatives)

Le cache `ProgramGraph` en mémoire reste le backend candidat tant que des profils de performance ne prouvent pas un goulot qui exige une autre conception. Ni ANN ni cache `ProgramGraph` en base ne sont introduits sans profil de performance qui démontre un goulot concret ; l'[ADR 0031](BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) applique la même discipline à l'ANN de la recherche sémantique.

## Do's and Don'ts

### Do

- Garder le snapshot Code Knowledge persisté comme autorité ; les graphes de programme sont des vues reconstructibles.
- Marquer tout flux local dérivé de `READS`/`WRITES` comme potentiel, avec la limitation `EXECUTION_ORDER_NOT_PROVEN`.
- Rejeter, à la composition, les collisions d'identifiant stable dont le contenu diffère.
- Borner l'analyse interprocédurale par la profondeur et les limites de résultats, et signaler cycles et troncature.
- Déclarer `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY` sur toute réponse construite sur des règles lues dans le dépôt analysé.
- Composer en production `FingerprintConstrainedJavaProgramGraphProvider`, avec l'empreinte exacte du projet dans la clé de cache.
- Échouer fermé (`JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT`) quand l'arbre de travail diffère du snapshot actif.
- Garder `JavaSourceProgramGraphProvider` comme façade mince sur des composants ciblés.

### Don't

- Ne pas produire de CFG exact, de def-use exact ni d'annotation de sécurité sans `ProgramGraphProvider` capable.
- Ne pas inférer par défaut un CFG ou un flux de données à partir d'heuristiques syntaxiques dans le cœur.
- Ne pas faire du CPG une seconde source de vérité, ni persister un CPG monolithique comme nouvelle autorité.
- Ne pas présenter les chemins de sécurité comme preuve exhaustive de présence ou d'absence de vulnérabilité, ni les rapporter comme des vulnérabilités.
- Ne jamais retirer un chemin parce qu'un sanitizer est déclaré.
- Ne pas recréer un provider Java monolithique.
- Ne pas introduire d'ANN ni de cache `ProgramGraph` en base sans profil de performance qui démontre un goulot.

## Consequences

### Positives

- Les snapshots et providers existants restent compatibles ; la précision du CFG et des flux progresse provider par provider, sans branche dans l'orchestration du cœur (ADR 0028).
- Les consommateurs distinguent capacités indisponibles, dérivées et factuelles (ADR 0028).
- Les capacités avancées sont prouvées depuis la même composition de production que l'API, la CLI, l'IDE et le MCP ; l'analyse sur des sources périmées est empêchée et non seulement documentée (ADR 0036).
- Le provider Java évolue sans redevenir un monolithe (ADR 0036).

### Négatives

- Plus de classes ciblées et de tests à garder synchronisés (ADR 0036, Costs).
- La validation de l'empreinte exacte ajoute un hachage des sources à chaque absence dans le cache `ProgramGraph` (ADR 0036, Costs).

### Risques

- L'analyse reste bornée : un résultat tronqué l'annonce, mais n'est pas complet (ADR 0028 § 7).
- Les règles de sécurité viennent du dépôt analysé : un dépôt hostile peut omettre ses sources et n'obtenir aucun chemin ; la limitation le déclare, sans le compenser (ADR 0028 § 9).

## Compliance and Enforcement

- Gate `scripts/quality/check-vertical-decomposition-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — `JavaSourceProgramGraphProvider` reste une façade d'au plus 80 lignes qui délègue à `JavaProgramGraphEngine` sans `TreeScanner`, `JavacTask`, `MessageDigest` ni `Files.readAllBytes` ; chaque composant ciblé existe ; `JavaSourceProgramGraphDecompositionTest` prouve un pipeline déterministe ; `ProgramGraphPerformanceQualificationTest` porte `KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE` et `SOURCE_MISMATCH_LIMITATION` ; la portée JaCoCo couvre les composants et `FingerprintConstrainedJavaProgramGraphProvider` ; les tests verticaux API, CLI et MCP exigent les capacités avancées et la nature `DERIVED`.
- Gate `scripts/quality/check-advanced-provider-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — la SPI `ProgramGraphProvider` résout sa clé de cache, `ProgramGraphService` n'ajoute pas lui-même la capacité `INTERPROCEDURAL_DATA_FLOW`, et le provider de sidecar porte ses limitations (`ADVANCED_PROGRAM_SIDECAR_STALE_SNAPSHOT`, `ADVANCED_PROGRAM_FACTS_PROVIDER_ASSERTED`) et le contrôle SHA-256 de ses faits.
- Tests de `minos-application` (lancés par `mvnw verify`) : `ProgramGraphSecurityRulesProvenanceTest` (limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY` déclarée par les trois analyses ; un sanitizer sur le chemin le marque sans le retirer), `ProgramGraphComposerLimitationsTest` (la troncature déclare ses causes).
- Tests de `minos-bootstrap` : `ProgramGraphAnalysisTest` (`cpgCompositionRejectsConflictingStableIds` ; flux potentiel explicite dérivé des relations), `ProgramGraphPerformanceQualificationTest` (source modifiée refusée, cache contraint par l'empreinte).
- Le harnais de jalon `scripts/history/m19/run-final.ps1` est une archive : aucun workflow ne le lance.
- Pas de règle Archgate : les règles portent sur le contenu des graphes produits (nature des arêtes, limitations, collisions, bornes) ; une règle textuelle n'exigerait que la présence de chaînes, ce que les gates font déjà.
- Revue : une capacité avancée n'est publiée que par un provider capable ; tout flux dérivé porte sa limitation.

## References

- ADR remplacés : [0028](archive/0028-capability-honest-program-graph-and-bounded-advanced-analysis.md), [0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 1 et § 2 (preuves d'acceptation M28 : [`M28_EXECUTION.md`](../../project/roadmap/M28_EXECUTION.md)).
- ADR liés : [0017](ARCH-0017-mcp-stdio-read-only.md) (MCP en lecture seule), [0018](ARCH-0018-versioned-public-java-api.md) (API Java versionnée), [0026](BE-0026-discovery-provider-spi-and-explicit-capability-profiles.md) (profils de capacités explicites), [0030](BE-0030-java-ast-reference-provider-with-explicit-capability-limits.md) (provider Java AST de référence), [0031](BE-0031-local-learned-semantic-retrieval-with-measurement-gated-ann.md) (ANN soumis à la mesure), [0065](BE-0065-providers-scip-polyglottes-livres-dans-le-paquet.md) (aucune capacité avancée promue sans preuve), [0070](SEC-0070-code-non-fiable-confinement-et-indexation-distante.md) et [0071](SEC-0071-plan-de-controle-d-equipe-embarque.md) (autres parts de l'ADR 0036).
- Spécification courante : [`openspec/specs/program-graph-et-architecture/spec.md`](../../../openspec/specs/program-graph-et-architecture/spec.md).
- Documentation : [`docs/developer/advanced-program-provider.md`](../../developer/advanced-program-provider.md).
- arc42 : [§ 9 Décisions](../arc42/09-decisions.md).
