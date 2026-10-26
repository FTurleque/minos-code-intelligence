# Proposal

## Why

Le constat **AUD-ARC-01** (audit du 10 octobre 2026) relève que les surfaces utilisent des types de `minos-engine` au lieu de passer par `minos-application` (ADR 0058 § 3). La règle A11 tient cette dette : 41 types dans le garde Python et 36 dans la table bytecode, au HEAD `9b710cbe`.

Le lot 6, le **noyau transverse**, porte sur des types utilitaires que toutes les surfaces nomment : le message d'erreur public, la version de l'artefact, l'exception de registre illisible, l'entrée dégradée, la correspondance des chemins hôte et conteneur. La décision D5 du lot 1 fixe la règle, type par type : un type sans E/S ni dépendance au moteur descend dans `minos-domain` ; les autres restent dans A11, avec une justification écrite. C'est le premier lot de la phase 3, et il n'a aucun prérequis.

## What Changes

- **`com.minos.diagnostics.PublicErrorMessages`** : le paquet, qui ne contient que cette classe, descend entier dans `minos-domain`. Son nom complet ne change pas (ADR 0068 § 3, étape 1). La racine `diagnostics` passe ainsi du moteur au domaine (A14).
- **`UnreadableRegistryException`, `DegradedEntry` et `ProjectPathMapping`** descendent dans `com.minos.domain.registry`. Le paquet `com.minos.registry` garde ses ports (`ProjectRegistry`, `RegisteredProject`, `ProjectPathMappings`…) dans le moteur. Les fichiers qui les importent sont mis à jour.
- **Restent dans le moteur, justifiés dans la table A11** :
  - `ProjectPathMappings`, port d'E/S persisté implémenté par le stockage ;
  - `MinosVersion`, placé dans `com.minos.runtime` par l'ADR 0068 § 3 (décision Q1, recommandation : le garder).
- **`minos-integration-git` gagne une dépendance directe à `minos-domain`**, dans le sens permis. Son `GitIntelligenceService` utilise `PublicErrorMessages`. Le changement touche la table `ALLOWED_DEPENDENCIES`, `ModuleArchitectureTest.ALLOWED`, l'arc42 § 5 et le document généré.
- **Cliquets A11** :
  - garde Python : 41 → **33** types, en retirant `PublicErrorMessages` (API, CLI, MCP), `DegradedEntry` et `ProjectPathMapping` (CLI), et `UnreadableRegistryException` (CLI, MCP, NEXUS) ;
  - table bytecode : 36 → **28** types.
- **Tests déplacés avec leurs classes** : `PublicErrorMessagesTest`, `PublicErrorMessagesLeafNameTest`, `UnreadableRegistryExceptionTest` et `DegradedEntryTest` passent dans `minos-domain`. Un test de `ProjectPathMapping` est ajouté dans le domaine pour la couverture par module. Les commentaires des fichiers déplacés passent en anglais.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `frontieres-de-modules` : une exigence est ajoutée (le noyau transverse est dans le domaine, et les types restants sont justifiés).

## Hors périmètre

- Les lots 3 (contrôle hébergé), 4 (Git et résultats de requête) et 5 (export NEXUS) d'AUD-ARC-01.
- Déplacer `MinosVersion` sans décision (Q1) ; déplacer les ports de `com.minos.registry`.
- Tout changement de sortie, de message ou de format persisté. `ProjectPathMapping` garde ses clés de propriétés.

## Impact

- **Modules** : `minos-domain` reçoit 4 classes et leurs tests ; `minos-engine` les perd. Les imports changent dans `minos-application`, `minos-cli`, `minos-api`, `minos-mcp`, `minos-nexus`, `minos-storage-local`, `minos-storage-postgresql`, `minos-provider-scip`, `minos-bootstrap` et les tests. Un POM change (`minos-integration-git`).
- **Surfaces publiques** : aucune rupture de l'API Java publiée. Aucun des types déplacés n'apparaît dans une signature publique de `minos-api`, ni dans `docs/user/java-api.md`. La sortie de la CLI et du MCP est inchangée, ce que gardent les goldens.
- **ADR** : aucun nouvel ADR si Q1 est tranchée « garder ». Notes de mise en œuvre de l'ADR 0058 § 3 (lot 6) et de l'ADR 0068 (racine `diagnostics` et arête `minos-integration-git` → `minos-domain`).
- **Gates** :
  - `check-module-boundaries.py` (A2 `ALLOWED_DEPENDENCIES`, A11, A14) et son auto-test ;
  - `ModuleArchitectureTest` (`ALLOWED`, table bytecode) ;
  - `check-comment-language.py` (maximums du moteur et du domaine) ;
  - `check-jacoco.py` (portée `domain-invariants` : `com/minos/domain/`) ;
  - base SpotBugs (l'entrée `EI_EXPOSE_REP` de `UnreadableRegistryException` passe du moteur au domaine).
- **Plateformes** : aucun comportement propre au système. `ProjectPathMapping` traite des chemins Windows et POSIX déjà testés.
- **Constat** : AUD-ARC-01, lot 6.
