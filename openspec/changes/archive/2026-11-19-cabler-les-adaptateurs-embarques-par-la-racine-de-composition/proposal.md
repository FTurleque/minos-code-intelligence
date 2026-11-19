# Proposal

## Why

Constat **AUD-ARC-01** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité moyenne) : `minos-application` choisit et instancie elle-même trois adaptateurs d'infrastructure, hors de la racine de composition que l'ADR ARCH-0042 désigne comme seul lieu de câblage :

- le client HTTP Ollama (`OllamaEmbeddingProvider`, `java.net.HttpURLConnection`), instancié par `MinosApplicationRuntimeConfiguration` ;
- l'analyseur Java fondé sur `javac` (13 classes `Java*`, 1 805 lignes, `javax.tools` et `com.sun.source`), instancié par `ProgramGraphService` ;
- le lecteur de fichiers sidecar (`FileProgramGraphProvider`), ajouté d'office par `ProgramGraphService`.

La règle A2 du garde tient à la lettre (aucun module adaptateur n'est nommé) mais aucun garde ne voit ces dépendances : elles portent sur le JDK, pas sur un module. Le sprint 8 de l'audit (AUD-ARC-04, AUD-ARC-07) dépend de ce constat.

## What Changes

- Le port `MinosApplicationComposer` gagne trois fabriques, implémentées par `DefaultMinosApplicationComposer` (`minos-bootstrap`) : le fournisseur d'embeddings appris à partir de réglages déjà validés, la liste de production des fournisseurs du graphe de programme, et la complétion d'une liste de fournisseurs.
- `MinosApplicationRuntimeConfiguration` lit et valide les réglages sémantiques, puis demande le fournisseur à la racine de composition ; elle ne nomme plus `OllamaEmbeddingProvider`.
- `ProgramGraphService` reçoit sa liste de fournisseurs complète et n'en instancie plus ; `MinosApplicationAssembler` obtient par la racine de composition la liste de production (fournisseur de relations compris) et la complétion d'une liste surchargée. La complétion garde sa règle : l'analyseur de sources Java n'est ajouté que si le fournisseur de relations est présent, le lecteur de sidecar toujours.
- **RUPTURE** (classe interne `com.minos.program.analysis.ProgramGraphService`, pas `com.minos.api`) : le constructeur public à deux arguments et les deux méthodes publiques `productionProviders` disparaissent (`ProgramGraphService.java:68`, `:92`, `:96`). Le régime est celui des entrées existantes de `docs/user/java-api.md:373` : rupture de source et binaire documentée, avec le remplacement.
- Le garde `scripts/architecture/check-module-boundaries.py` gagne une règle et deux tables à cliquet : les fichiers de `minos-application` qui nomment une API d'infrastructure du JDK (`java.net.HttpURLConnection`, `java.net.http`, `javax.tools`, `com.sun.source`), et les classes d'adaptateur embarquées que nulle autre source de production de l'application ne nomme.
- Cinq assertions littérales de gates suivent le câblage (`check-semantic-retrieval-consistency.py:85`, `check-p0-p2.py:218-220`, `check-advanced-provider-consistency.py:98`, `check-java-ast-provider-consistency.py:104-106`, dont `new RelationshipProgramGraphProvider()` en `:105`).
- Un ADR du domaine `ARCH` (prochain numéro libre à l'écriture, lu dans `docs/architecture/adr/README.md`, non réservé) consigne la décision : où vivent ces adaptateurs et pourquoi.

Aucune sortie de la CLI, de l'API Java `com.minos.api`, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : exigences ajoutées sur le câblage des fournisseurs d'embeddings et de graphe de programme par la racine de composition, et sur les deux listes fermées du garde.

## Hors périmètre

- Le déplacement physique des 17 classes vers un package gardé ou un module adaptateur : décision D1 du design, portée par l'ADR ; ce changement ne déplace aucun fichier.
- La façade `MinosApplication` et ses accesseurs (AUD-ARC-02) : changement `injecter-les-collaborateurs-des-cas-d-usage`.
- L'historique `cli-index-history` (AUD-ARC-03) : changement `porter-l-historique-d-import-derriere-un-port`.
- La source unique des tables du garde et de `ModuleArchitectureTest` (AUD-ARC-04) et la prose d'architecture (AUD-ARC-07) : sprint 8, bénéficiaires de ce changement.
- Les caches du graphe de programme (AUD-PERF-04) et tout changement de comportement des analyseurs.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `MinosApplicationRuntimeConfiguration` instancie `OllamaEmbeddingProvider` | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/MinosApplicationRuntimeConfiguration.java:53` |
| `ProgramGraphService` instancie les fournisseurs de relations, Java et sidecar | [Existant vérifié] | `minos-application/src/main/java/com/minos/program/analysis/ProgramGraphService.java:92-98`, `:317-321` |
| 17 classes de `minos-application` forment la famille des adaptateurs embarqués, `SourceStateKeyMemo` comprise | [Existant vérifié] | design, « État vérifié au HEAD » |
| 10 fichiers de production de `minos-application` importent une API d'infrastructure du JDK | [Existant vérifié] | design, « État vérifié au HEAD » |
| La racine de composition est le seul lieu de câblage des adaptateurs | [Décision] | `docs/architecture/adr/ARCH-0042-racine-de-composition.md:25` |
| Les classes restent à leur chemin dans ce changement | [Proposition] | design, D1 (attend le propriétaire) |
| Les fabriques du composeur sont abstraites, sans valeur par défaut | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : `minos-application` (configuration, assembleur, `ProgramGraphService`, port du composeur), `minos-bootstrap` (`DefaultMinosApplicationComposer`), tests de `minos-application` et de `minos-bootstrap`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucun changement de sortie. Classes internes : `MinosApplicationComposer` gagne trois méthodes abstraites (à implémenter par toute implémentation tierce) et `ProgramGraphService` perd un constructeur et deux méthodes statiques publics (**RUPTURE** de source et binaire d'une classe interne) ; les deux sont documentés dans `docs/user/java-api.md`.
- **ADR** : à écrire, domaine `ARCH`, prochain numéro libre à l'écriture ; « Mise en œuvre » d'ARCH-0042 à compléter.
- **Gates à rejouer** : `check-module-boundaries.py` et `test_check_module_boundaries.py`, `check-semantic-retrieval-consistency.py`, `check-p0-p2.py`, `check-advanced-provider-consistency.py`, `check-java-ast-provider-consistency.py`, `check-vertical-decomposition-consistency.py`, `check-mnd.py`, `check-mne.py`, `check-ci-wiring.py`, `check-single-execution.py` ; scopes JaCoCo `semantic-learned-provider` et ceux du graphe de programme (`check-jacoco.py:76-93`).
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, `docs/architecture/arc42/05-vue-blocs.md:90` (phrase « ports seulement vers les adaptateurs »), `docs/user/java-api.md`, index des ADR et `docs/architecture/arc42/09-decisions.md`.

## Questions ouvertes

- Bloquante pour l'ADR, non pour les specs : D1 (emplacement physique des adaptateurs). Les exigences sont écrites pour tenir quelle que soit l'option retenue.
