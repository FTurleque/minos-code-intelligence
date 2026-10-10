# Design

## Context

Suite de `durcir-les-gardes-d-architecture` (PR #394) : la règle A8 gèle les quatre cycles de packages existants dans `KNOWN_PACKAGE_CYCLES`. Ce changement les **lève**. Le HEAD analysé est `68e5fe76` (`develop`, après fusion des PR #392 à #396).

## État vérifié au HEAD

`python scripts/architecture/check-module-boundaries.py` → « A8 package cycles: 4 known cycles over 15 packages, none new (51 packages analysed, plugin included) ». Arêtes de classes à l'intérieur de chaque cycle (recalcul sur les sources de production : imports et noms qualifiés) :

| Cycle | Sens | Arêtes de classes |
|---|---|---|
| `application` (8 packages) | retour vers `application` | `ProjectResolver` importé par `application.dynamic`, `.semantic`, `architecture`, `impact`, `program.analysis`, `workspace` ; `MinosApplication` (même package) importe chacun d'eux |
| | `application.semantic` → `output` | `OllamaEmbeddingProvider` → `DeterministicJson` |
| | `output` → les packages de requête | rendus → `application` (3 imports), `.dynamic`, `.semantic`, `architecture`, `impact`, `program.analysis` |
| `discovery` ↔ `discovery.spi` | spi → discovery | `BuildSystemDetector` → `ProjectDiscovery`, `ProjectIgnorePolicy` ; `LanguageDetector` → `ProjectDiscovery` ; `ProjectDetector` → `ProjectIgnorePolicy` ; `SourceRootDetector` → `ProjectDiscovery`, `ProjectIgnorePolicy` |
| | discovery → spi | `ProjectDiscoveryService`, `DefaultDiscoveryPlugins` → les quatre détecteurs |
| `incremental` ↔ `orchestration` | incremental → orchestration | 14 arêtes depuis 6 classes (`IncrementalIndexingCoordinator`, `IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `IncrementalIndexingResult`, `ProjectFingerprintSnapshotAlignmentService`, `ProjectInvalidationService`) |
| | orchestration → incremental | 3 arêtes : `ExecutionCheckpoints` → `ProjectFingerprintService` ; `IndexingLifecyclePlanSupport` et `IndexingLifecycleService` → `IncrementalIndexingPlan` |
| `intellij` : `protocol`, `service`, `ui` | protocol → ui | `MinosCliClient` → `MinosRegistryNotice` (classe sans import) |
| | ui → protocol, service | `MinosToolWindowPanel` → `MinosCliClient`, `MinosProjectNotRegisteredException`, `MinosProjectService` ; `service` → `protocol` |

## Goals / Non-Goals

**Goals** : `KNOWN_PACKAGE_CYCLES` vide ; aucun comportement observable modifié ; aucun module créé ; chaque lot vérifiable seul.

**Non-Goals** : supprimer du code mort (AUD-ARC-02, sprint 9) ; résorber les arêtes surfaces → moteur (sprint 10) ; déplacer `com.minos.output` hors de `minos-application` ; modifier l'API Java versionnée.

## Decisions

### D1. Treize déplacements de classes lèvent les quatre cycles (simulation)

Une simulation sur le graphe de classes (mêmes entrées que la règle A8, déplacement hypothétique) applique les 13 déplacements ci-dessous et ne laisse **aucun** cycle de packages, plugin compris :

| Lot | Classe | Vers | Usages en production |
|---|---|---|---|
| A | `ProjectResolver` | `com.minos.application.resolution` | 16 classes (api 1, application 11, cli 2, mcp 2) |
| A | `DeterministicJson` | `com.minos.output.json` | 18 classes (application 12, cli 4, mcp 2) |
| B | `IncrementalIndexingCoordinator` | `com.minos.orchestration` | 0 |
| B | `IncrementalIndexingPlan` | idem | 6 (cli 1, engine 5) |
| B | `IncrementalIndexingPlanner` | idem | 4 (application 2, cli 1, engine 1) |
| B | `IncrementalIndexingResult` | idem | 1 (engine) |
| B | `ProjectFingerprintSnapshotAlignmentService` | idem | 0 |
| B | `ProjectInvalidationService` | idem | 4 (application 2, cli 1, engine 1) |
| C | `BuildSystemDetector`, `LanguageDetector`, `ProjectDetector`, `SourceRootDetector` | `com.minos.discovery` | 2 chacune (engine) |
| D | `MinosRegistryNotice` | `com.minos.intellij.protocol` | 1 (`MinosCliClient`) |

Pourquoi ces déplacements-là, et pas d'autres :

- **Lot A.** Déplacer `ProjectResolver` seul réduit la composante de 8 à 3 packages (`application`, `.semantic`, `output`) ; il faut aussi sortir `DeterministicJson`, une classe **sans import interne**, d'`output`. Un sous-package `output.json` est un nœud distinct du graphe, sans dépendance retour. `ProjectResolver` ne dépend que de `diagnostics` et `registry` (moteur) : son nouveau package n'ajoute aucune arête.
- **Lot B.** Déplacer les six classes d'`incremental` qui utilisent `orchestration` dans `orchestration` supprime toutes les arêtes `incremental → orchestration` ; il reste `orchestration → incremental` (3 arêtes, à sens unique). L'alternative « déplacer la fermeture de `IncrementalIndexingPlan` et `ProjectFingerprintService` vers `orchestration` » lève aussi le cycle mais déplace **10** classes et mélange la logique d'empreinte et l'orchestration. Déplacer seulement `IncrementalIndexingPlan` (ou plus `ProjectFingerprintService`) ne lève rien.
- **Lot D.** Déplacer la notice vers `service` laisserait `protocol` ↔ `service` ; la déplacer vers `protocol` (où son seul utilisateur la lit) ne laisse aucun cycle.

### D2. Lot C : trois options, une recommandée (à confirmer)

| Option | Déplacement | Usages touchés | Contrat |
|---|---|---|---|
| **C1 (recommandée)** | dissoudre `discovery.spi` : 4 interfaces → `com.minos.discovery` | 2 classes d'engine par interface | l'SPI est documenté (ADR 0026, `docs/developer/polyglot-providers.md`) mais aucun implémenteur n'existe hors d'`minos-engine` (pas de `META-INF/services`) |
| C2 | `ProjectDiscoveryService` + `DefaultDiscoveryPlugins` → `com.minos.discovery.service` | `ProjectDiscoveryService` : 5 classes de production dont `MinosApplication`, plus des tests | `ProjectDiscoveryService` figure dans la signature documentée `LocalProjectArchitectureQuery.defaults(registry, snapshots, discovery)` |
| C3 | modèle `ProjectDiscovery` + `ProjectIgnorePolicy` → un package `model` | 33 classes (`ProjectDiscovery` : 27) | grosse dispersion, pour un modèle central |

C1 est la moins coûteuse et ne touche aucun type d'une signature publique documentée ; son défaut est de perdre le nom de package `spi`. C2 conserve `spi` mais déplace un service utilisé par `MinosApplication`. Le choix est à confirmer avant le lot C ; les deux autres lots ne dépendent pas de lui.

### D3. Aucune façade de compatibilité

`ProjectResolver` apparaît dans des constructeurs publics de `minos-application`. Ce module n'est pas l'API Java versionnée : aucune signature de `minos-api` n'expose `ProjectResolver` (`MinosApiSupport` est de visibilité package, il ne fait que capturer `ProjectResolver.ResolutionException`), et `docs/user/java-api.md:359` cite déjà les constructeurs directs comme **remplacés** par `LocalProjectArchitectureQuery.defaults(...)`. Le déplacement est donc une rupture de source pour des appelants internes, corrigée dans le même lot, pas pour un contrat public. Aucune façade `@Deprecated` n'est laissée dans l'ancien package : elle recréerait l'arête que ce lot supprime.

### D4. Les tests suivent leur production

Règle A3 (ADR 0044) : un test se déclare dans le package dont la production est dans le même module. Les tests de chaque classe déplacée sont déplacés avec elle, avec leurs imports ; ceux qui utilisaient la visibilité de package restent valides car la classe et ses membres de package voyagent ensemble. Un test qui atteignait un membre de package d'une classe restée dans l'ancien package est à revoir cas par cas (signalé par la compilation).

### D5. Gate par lot : retirer l'entrée de la table

Pour chaque lot, la première tâche retire l'entrée correspondante de `KNOWN_PACKAGE_CYCLES` : le garde devient rouge (« package cycle not in KNOWN_PACKAGE_CYCLES … »), puis vert quand les déplacements sont faits. Ce rouge/vert est la preuve du lot, sans build Maven. À la fin, la table est vide, la règle et ses tests restent.

### D6. Ordre et conflits avec les autres chantiers

Faire ce changement **avant** AUD-ARC-02 (sprint 9) évite deux réécritures de `IncrementalIndexingCoordinator` ; l'inverse est possible (S9 supprimerait le coordinateur, le lot B déplacerait cinq classes au lieu de six). Les lots A, C et D sont indépendants de S9.

## Gates littéraux et documents concernés

| Fichier | Citation | Lot |
|---|---|---|
| `scripts/quality/check-jacoco.py:22` | scope `project-resolution`, préfixe `com/minos/application/ProjectResolver` | A |
| `scripts/m15/run-final.ps1:62` | lit `minos-application\src\main\java\com\minos\application\ProjectResolver.java` | A |
| `scripts/remediation/check-post-mne.py:103,254` | lit `minos-application/.../output/DeterministicJson.java` | A |
| `docs/user/java-api.md:359` | cite `ProjectResolver` dans la table de migration | A |
| `docs/adr/0026-discovery-provider-spi-and-explicit-capability-profiles.md`, `docs/developer/polyglot-providers.md` | nom du package `spi` | C |
| `scripts/quality/check-polyglot-provider-consistency.py:97-99,259` | `ProjectDiscovery.java`, `DefaultDiscoveryPlugins.java`, `ProjectDiscoveryService.java` (**non déplacés** par C1) | C (à rejouer) |
| `scripts/remediation/check-mnd.py:57-58` | `ProjectIgnorePolicy.java`, `DefaultDiscoveryPlugins.java` (**non déplacés**) | C (à rejouer) |
| `scripts/architecture/check-module-boundaries.py` | `KNOWN_PACKAGE_CYCLES` | tous |

Aucun script ne cite les classes du lot B ni `MinosRegistryNotice`.

## Qualification des capacités

| Élément | Statut | Raison |
|---|---|---|
| Absence de cycle après les 13 déplacements | **Qualifiée par simulation** | Même graphe que la règle A8 ; à confirmer par la garde sur le code réel (preuve de chaque lot). |
| Comportement inchangé | **À qualifier** | Goldens de caractérisation et tests des modules ; `verify` complet sur Windows et Linux. |
| Lot D (plugin) | **Partielle en local** | Gradle et Java 21 du plugin ne sont pas garantis sur le poste ; la CI `IntelliJ Plugin Validation` le qualifie. |

## Windows et Linux

Renommages de packages sans comportement propre à un OS. Attention aux chemins Windows : les déplacements de fichiers doivent être faits avec `git mv` pour que l'historique suive, et la casse des répertoires est cohérente avec les noms de package. Les gates qui lisent des chemins avec `\` (script PowerShell M15) sont mis à jour dans la même tâche.

## Risks / Trade-offs

- [Conflit avec des PR en cours qui touchent ces classes] → faire des lots courts et fusionner chacun avant le suivant.
- [Un test reposait sur la visibilité de package d'un voisin resté dans l'ancien package] → la compilation le révèle ; le corriger sans élargir la visibilité sans raison.
- [Le lot C change un nom de package cité dans un ADR accepté] → note de suivi dans l'ADR 0026, sans changer la décision.
- [Une classe déplacée est référencée par réflexion ou `ServiceLoader`] → aucun `META-INF/services` ne la cite (recherche faite) ; la garde A8 ne voit pas la réflexion, d'où le `verify` complet.

## Direction des dépendances (ADR 0022)

Aucune dépendance Maven ajoutée ni modifiée. Les classes restent dans leur module ; `ModuleArchitectureTest` et `check-module-boundaries.py` (A2, A3, A7) doivent rester verts.

## Décisions qui vous attendent

1. **Lot C** : C1 (dissoudre `spi`, recommandé), C2 ou C3.
2. **Pas de façade de compatibilité** pour `ProjectResolver` (recommandé).
3. **Noms des nouveaux packages** : `com.minos.application.resolution` et `com.minos.output.json`.
4. **Ordre par rapport au sprint 9** (D6).
