---
id: ADR-0057
title: "Finaliser les frontières hexagonales sans refaire les corrections acquises"
domain: architecture
rules: true
---

# ADR 0057 — Finaliser les frontières hexagonales sans refaire les corrections acquises

Date : 2026-10-05. Statut : **Accepted — direction validée ; travaux résiduels planifiés**.

## Context

Minos reste un monolithe modulaire local-first avec un plugin IntelliJ externe. `develop` a déjà une couche application sans dépendance Maven vers les adaptateurs, une racine `minos-bootstrap`, un package par module et des gardes de sources (ADR 0042/0044/0045). Cela n'interdit pas encore tout mélange de responsabilités à l'intérieur des modules.

Restent notamment `LocalAutonomousIndexOperations` dans la CLI, `OllamaEmbeddingProvider` et son instanciation dans la configuration applicative, les I/O concrètes de `com.minos.io` dans engine, et le staging local concret dans `ScipProjectSnapshotLifecycle`.

## Decision

1. Préserver le monolithe modulaire, le bootstrap existant et le client IntelliJ externe. Aucun microservice, framework serveur ou migration JPMS n'est introduit par ce chantier.
2. Déplacer la coordination d'indexation de la CLI vers un cas d'usage partagé, avec des ports et les garanties actuelles de reprise, verrouillage, empreinte et synchronisation.
3. Garder SCIP comme adaptateur de normalisation/acquisition. Fournir son staging et sa publication par des ports injectés ; ne pas remplacer une dépendance de stockage par une dépendance vers bootstrap.
4. Séparer le calcul sémantique des appels au fournisseur Ollama. Le port et les modèles restent côté cœur/cas d'usage ; l'adaptateur HTTP et sa construction relèvent d'une frontière technique et du bootstrap. Le module/package exact est un choix d'implémentation à justifier, pas un nouveau module imposé d'avance.
5. Réduire progressivement le mélange des politiques et mécanismes I/O dans engine, avec une cartographie préalable. Ne pas multiplier mécaniquement les modules, casser les contrats existants ou disperser les primitives durcies. Une extraction physique nécessitant une nouvelle frontière doit expliciter son graphe et sa compatibilité avant code.
6. Organiser les cas d'usage par capacité et faire consommer leurs résultats par API/CLI/MCP/NEXUS. Conserver les contrats publics. La scission de `minos-api` en deux artefacts demeure une option à évaluer ; elle n'est pas ratifiée par ce document.
7. Protéger les frontières par les gardes existantes, complétées au niveau des packages/classes si nécessaire. ArchUnit est une option de réalisation, pas une dépendance de production imposée.

## Do's and Don'ts

### Do

- Préserver le monolithe modulaire, le bootstrap existant et le client IntelliJ externe.
- Fournir le staging et la publication SCIP par des ports injectés.
- Séparer le calcul sémantique des appels au fournisseur Ollama ; l'adaptateur HTTP et sa construction relèvent d'une frontière technique et du bootstrap.
- Faire consommer les cas d'usage par API/CLI/MCP/NEXUS en conservant les contrats publics.

### Don't

- Aucun microservice, framework serveur ni migration JPMS.
- Ne pas remplacer une dépendance de stockage par une dépendance vers bootstrap.
- Ne pas multiplier mécaniquement les modules ni disperser les primitives durcies.
- Ne pas imposer ArchUnit comme dépendance de production.

## Alternatives, conséquences et limites

Une refonte globale augmenterait le risque de régression sur des garanties déjà qualifiées. Chaque lot sera comportementalement conservateur, réversible et validé avant le suivant. Le coût est une transition en plusieurs PR et des façades temporaires éventuelles pour les consommateurs publics.

La consolidation storage relève de l'ADR 0055. La séparation lecture/écriture relève de l'ADR 0056. Les nouvelles fonctionnalités de l'étude Semble/Serena (#333, ADR proposés 0048–0054) ne sont ni acceptées ni implémentées par ce chantier ; coordonner les fichiers sémantiques communs avant d'y travailler.

La fermeture de `remote index` non fiable (ADR 0041), MCP read-only, les limites de ressources, les contrôles privés I/O, les résultats partiels et l'isolation des secrets restent des invariants. Aucun benchmark n'a démontré de gain à ce stade.

## Mise en œuvre

[Backlog SH-05 à SH-12](../../project/roadmap/storage-hexagonal-2026-10/README.md). Ne pas rouvrir A2/A3/A4 comme travaux non faits ; les utiliser comme régressions à protéger. Les résidus A5 et le chargeur SPI signalé dans l'ADR 0042 sont suivis explicitement.

Cycles de packages (point 7, mis en œuvre le 2026-10-10, audit AUD-ARC-06) : le garde `scripts/architecture/check-module-boundaries.py` (règle A8) refuse tout cycle de packages absent de la table `KNOWN_PACKAGE_CYCLES` et toute entrée de cette table qui ne décrit plus exactement un cycle, de sorte qu'elle ne puisse que rétrécir. Les quatre cycles existants (quinze packages, dont trois dans le plugin IntelliJ, hors reactor) y ont d'abord été gelés, puis **levés** par le changement `casser-les-cycles-de-packages` (13 classes déplacées dans leur module, sans nouvel ADR : ADR 0044 respecté) ; la table est vide et le garde refuse tout cycle futur.

Cas d'usage d'indexation (point 2, mis en œuvre le 2026-10-21, audit AUD-ARC-02, changement `unifier-l-orchestration-d-indexation`, tâche SH-05) : la préparation, l'exécution sous bail, la reprise d'un run promu, la promotion de l'empreinte, la synchronisation sémantique et la rétention vivent dans `ProjectIndexingUseCase` (`minos-application`, package `com.minos.application.indexing`), invocable sans la CLI ; `LocalAutonomousIndexOperations` ne fait plus que projeter son résultat sur les vues de la commande. Le coordinateur sans appelant `IncrementalIndexingCoordinator` et `IncrementalIndexingResult` sont supprimés, leurs tests reportés sur le cas d'usage. Une baseline d'empreinte illisible est désormais déclarée par un diagnostic sans chemin ; l'échec de publication de l'empreinte reste une erreur de la commande (décision du propriétaire du 2026-10-21).

## Compliance and Enforcement

- Gate `scripts/architecture/check-module-boundaries.py` (job `invariants`) — règle A2 (frontières hexagonales, POM et sources) et règle A8 (aucun cycle de packages, table `KNOWN_PACKAGE_CYCLES` vide, plugin compris) ; auto-test `test_check_module_boundaries.py`.
- Test `ModuleArchitectureTest` (`minos-app`) — dépendances autorisées entre modules.
- Règle Archgate `0057-finaliser-les-frontieres-hexagonales.rules.ts` / `no-jpms-module-descriptor` : refuse tout `module-info.java` dans les sources d'un module du reactor (liste lue dans les `<modules>` du POM racine), premier pas d'une migration JPMS exclue par la décision 1 ; contrôle propre à Archgate, aucun gate Python ne le fait (lancée par `archgate check`, pas par la CI).
- Revue : chaque lot reste comportementalement conservateur, réversible et validé avant le suivant.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Point 4 : `OllamaEmbeddingProvider` vit dans `minos-application` et y est instancié par la configuration applicative — preuve : `minos-application/src/main/java/com/minos/application/MinosApplicationRuntimeConfiguration.java:53`.
- Point 3 : `ScipProjectSnapshotLifecycle` construit directement un `FileSymbolSnapshotStore` au lieu de recevoir son staging par un port injecté — preuve : `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java:41`, `:82`.

## References

- [Cockburn — Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture)
- [ArchUnit — couches, packages et cycles](https://www.archunit.org/userguide/html/000_Index.html)
- [JetBrains — services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html) et [threading](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [MCP — transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
