# ADR 0057 — Finaliser les frontières hexagonales sans refaire les corrections acquises

Date : 2026-10-05. Statut : **Accepted — direction validée ; travaux résiduels planifiés**.

## Contexte

Minos reste un monolithe modulaire local-first avec un plugin IntelliJ externe. `develop` a déjà une couche application sans dépendance Maven vers les adaptateurs, une racine `minos-bootstrap`, un package par module et des gardes de sources (ADR 0042/0044/0045). Cela n'interdit pas encore tout mélange de responsabilités à l'intérieur des modules.

Restent notamment `LocalAutonomousIndexOperations` dans la CLI, `OllamaEmbeddingProvider` et son instanciation dans la configuration applicative, les I/O concrètes de `com.minos.io` dans engine, et le staging local concret dans `ScipProjectSnapshotLifecycle`.

## Décision

1. Préserver le monolithe modulaire, le bootstrap existant et le client IntelliJ externe. Aucun microservice, framework serveur ou migration JPMS n'est introduit par ce chantier.
2. Déplacer la coordination d'indexation de la CLI vers un cas d'usage partagé, avec des ports et les garanties actuelles de reprise, verrouillage, empreinte et synchronisation.
3. Garder SCIP comme adaptateur de normalisation/acquisition. Fournir son staging et sa publication par des ports injectés ; ne pas remplacer une dépendance de stockage par une dépendance vers bootstrap.
4. Séparer le calcul sémantique des appels au fournisseur Ollama. Le port et les modèles restent côté cœur/cas d'usage ; l'adaptateur HTTP et sa construction relèvent d'une frontière technique et du bootstrap. Le module/package exact est un choix d'implémentation à justifier, pas un nouveau module imposé d'avance.
5. Réduire progressivement le mélange des politiques et mécanismes I/O dans engine, avec une cartographie préalable. Ne pas multiplier mécaniquement les modules, casser les contrats existants ou disperser les primitives durcies. Une extraction physique nécessitant une nouvelle frontière doit expliciter son graphe et sa compatibilité avant code.
6. Organiser les cas d'usage par capacité et faire consommer leurs résultats par API/CLI/MCP/NEXUS. Conserver les contrats publics. La scission de `minos-api` en deux artefacts demeure une option à évaluer ; elle n'est pas ratifiée par ce document.
7. Protéger les frontières par les gardes existantes, complétées au niveau des packages/classes si nécessaire. ArchUnit est une option de réalisation, pas une dépendance de production imposée.

## Alternatives, conséquences et limites

Une refonte globale augmenterait le risque de régression sur des garanties déjà qualifiées. Chaque lot sera comportementalement conservateur, réversible et validé avant le suivant. Le coût est une transition en plusieurs PR et des façades temporaires éventuelles pour les consommateurs publics.

La consolidation storage relève de l'ADR 0055. La séparation lecture/écriture relève de l'ADR 0056. Les nouvelles fonctionnalités de l'étude Semble/Serena (#333, ADR proposés 0048–0054) ne sont ni acceptées ni implémentées par ce chantier ; coordonner les fichiers sémantiques communs avant d'y travailler.

La fermeture de `remote index` non fiable (ADR 0041), MCP read-only, les limites de ressources, les contrôles privés I/O, les résultats partiels et l'isolation des secrets restent des invariants. Aucun benchmark n'a démontré de gain à ce stade.

## Mise en œuvre

[Backlog SH-05 à SH-12](../roadmap/storage-hexagonal-2026-10/README.md). Ne pas rouvrir A2/A3/A4 comme travaux non faits ; les utiliser comme régressions à protéger. Les résidus A5 et le chargeur SPI signalé dans l'ADR 0042 sont suivis explicitement.

## Références

- [Cockburn — Ports and Adapters](https://alistair.cockburn.us/hexagonal-architecture)
- [ArchUnit — couches, packages et cycles](https://www.archunit.org/userguide/html/000_Index.html)
- [JetBrains — services](https://plugins.jetbrains.com/docs/intellij/plugin-services.html) et [threading](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [MCP — transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
