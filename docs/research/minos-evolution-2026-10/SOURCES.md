# Sources et périmètre de preuve

Consultation : 2026-10-04. Sources officielles uniquement. Cette étude est une analyse documentaire et une lecture ciblée du code, **pas un audit exhaustif ni un benchmark exécuté**.

## Révisions figées

| Projet | Révision consultée | Rôle |
|---|---|---|
| MINOS develop | c9a339088f81b6b31c65c7ad12bc718be2a98a7b | base de conception et future branche documentaire |
| MINOS main | 730b760020b8ed9d69666c32a35fcff05bf21bdb | comparaison initiale, ne sert pas de base d'implémentation |
| Semble main | aa634b14dc81ba6925a130cb999081f3240e2a3c | approche de recherche |
| Serena main | d0f7f92631c23dc4c5ed0b5ccd35bc623b19a809 | approche symbolique et IDE |

develop contient des changements de septembre/octobre absents de main. Les conclusions du premier échange basées sur main sont donc révisées ici : minos-bootstrap est la racine de composition, les caches ont été optimisés, et la qualification hostile est refusée par l'ADR-0041.

## MINOS : preuves lues

| Source | Observation utilisée |
|---|---|
| [docs/adr/0042-racine-de-composition.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/adr/0042-racine-de-composition.md) | Composition minos-bootstrap, frontières et ports |
| [docs/adr/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/adr/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) | Évolution mémoire conditionnée aux mesures ; ne pas imposer V4/mmap |
| [docs/adr/0027-intellij-external-client-and-versioned-cli-protocol.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/adr/0027-intellij-external-client-and-versioned-cli-protocol.md) | Plugin externe et protocole versionné |
| [docs/adr/README.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/adr/README.md) | Numérotation et statuts |
| [docs/audit/S23-SUIVI.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/audit/S23-SUIVI.md) | État des lots récents, qualification offline restante, blocage Windows Java |
| [docs/ROADMAP.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/ROADMAP.md) | Principes et historique à préserver |
| [docs/STATUS.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/docs/STATUS.md) | État produit déclaré, branche de développement |
| [minos-application/src/main/java/com/minos/application/semantic/HybridSearchService.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-application/src/main/java/com/minos/application/semantic/HybridSearchService.java) | Score lexical, pondérations fixes, degré du graphe, cache pondéré existant |
| [minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java) | Unités SYMBOL/FILE/CHUNK et lectures bornées |
| [minos-application/src/main/java/com/minos/application/semantic/HybridContextBuilder.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-application/src/main/java/com/minos/application/semantic/HybridContextBuilder.java) | Budget contenu et overhead fixe ; absence de déduplication dans le builder lu |
| [minos-application/src/main/java/com/minos/application/semantic/EmbeddingProvider.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-application/src/main/java/com/minos/application/semantic/EmbeddingProvider.java) | SPI existant et limitations |
| [minos-mcp/src/main/java/com/minos/mcp/McpToolSchemas.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-mcp/src/main/java/com/minos/mcp/McpToolSchemas.java) | Schémas bornés et surfaces |
| [minos-intellij/src/main/java/com/minos/intellij/service/MinosProjectService.java](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/minos-intellij/src/main/java/com/minos/intellij/service/MinosProjectService.java) | Usage PSI déjà présent pour le contexte éditeur |
| [benchmarks/scalability/README.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/benchmarks/scalability/README.md) | Banc existant Windows/PowerShell, données hors dépôt |
| [.github/workflows/pr-ci.yml](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/.github/workflows/pr-ci.yml) | Gates produit actuels |
| [CONTRIBUTING.md](https://github.com/FTurleque/minos-code-intelligence/blob/c9a339088f81b6b31c65c7ad12bc718be2a98a7b/CONTRIBUTING.md) | Politique propriétaire et contribution autorisée par le commanditaire |

## Concurrents

- [Semble README figé](https://github.com/MinishLab/semble/blob/aa634b14dc81ba6925a130cb999081f3240e2a3c/README.md) : BM25/Model2Vec, RRF, fragments et cache incrémental ; MIT affichée.
- [Semble benchmarks officiels](https://minish.ai/packages/semble/benchmarks/) : méthode de comparaison ; les nombres publiés ne sont pas repris comme promesse MINOS. Page évolutive, protocole exact à figer en U0.
- [Serena README figé](https://github.com/oraios/serena/blob/d0f7f92631c23dc4c5ed0b5ccd35bc623b19a809/README.md) : LSP/JetBrains, navigation/édition, mémoire et répartition des licences.
- [Serena outils](https://oraios.github.io/serena/01-about/035_tools.html) : opérations et interface progressive ; page évolutive.
- [Serena JetBrains](https://oraios.github.io/serena/02-usage/025_jetbrains_plugin.html) : rôle du plugin comme backend ; page évolutive.
- [Serena licences](https://oraios.github.io/serena/01-about/060_license.html) : application GPL-3.0-or-later, SolidLSP MIT, versions historiques distinctes.

Aucun code de Semble/Serena n'est incorporé. Les algorithmes inspirent des hypothèses à vérifier ; le choix d'une bibliothèque, du modèle et de ses poids doit refaire l'inventaire de licences exact à la version retenue. Cette étude ne tranche pas une compatibilité juridique d'assemblage.

## Limites de l'analyse

Lecture ciblée des classes, contrats et suivis ; pas d'exécution JVM, de plugin IDE, de provider ou de modèle. Les comportements déduits de fichiers précis sont distingués des performances à mesurer. Aucune vérification de validité de tous les claims historiques du dépôt. Les exemples privés métier ne sont pas utilisés comme corpus déjà disponible. Les estimations de roadmap sont des estimations de charge et non un engagement.

Les seuils chiffrés nouveaux sont des critères proposés. L'analyse ne conclut pas que BM25, RRF, Model2Vec ou le bridge IDE gagneront sur les projets MINOS avant U0 et leurs expériences respectives.
