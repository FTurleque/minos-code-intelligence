# Proposal

## Why

Constat **AUD-ARC-03** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité faible) : le format de persistance `cli-index-history/<projet>.properties` est écrit par `LocalProjectOperations` et relu par `ProjectInspectionService`, deux classes de `minos-application`, sans port ni adaptateur de stockage. Le nom du dossier, les quatre clés et l'enregistrement `IndexHistory` sont écrits deux fois ; une évolution du format ne casse aucun contrat compilé. Avec le backend PostgreSQL, cet historique reste sur fichiers sans que la documentation le dise. Le critère de sortie du sprint 7 range cet historique parmi les adaptateurs à câbler par `minos-bootstrap` derrière un port.

## What Changes

- Un port d'historique d'import (nom proposé : `IndexImportHistoryStore`) est ajouté à `minos-engine`, avec son enregistrement de valeur.
- Son implémentation fichier vit dans `minos-storage`, package `com.minos.storage.home` (magasins du home indépendants du backend), et détient seule le nom du dossier, les clés et les bornes de lecture ; `minos-bootstrap` la fournit par une fabrique de `MinosApplicationComposer`, la même pour tout backend.
- `LocalProjectOperations` et `ProjectInspectionService` reçoivent le port ; ils ne nomment plus ni le dossier ni les clés, et leurs deux enregistrements `IndexHistory` disparaissent.
- Le format sur disque est inchangé : un historique écrit par une version antérieure est relu à l'identique.
- La documentation des backends dit que l'historique d'import est un fichier de `MINOS_HOME`, quel que soit le backend.

Aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. Aucune rupture.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : exigences ajoutées sur le port d'historique d'import, son unique détenteur de format et sa compatibilité de lecture.

## Hors périmètre

- Porter l'historique dans PostgreSQL : non décidé ; l'historique reste un fichier de `MINOS_HOME` (design, D2).
- La façade `MinosApplication` (AUD-ARC-02) et les adaptateurs embarqués (AUD-ARC-01) : changements voisins du sprint 7.
- Le contenu de l'historique (champs ajoutés, rétention) : inchangé.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le littéral `cli-index-history` est écrit dans deux classes de production | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:63` ; `ProjectInspectionService.java:76` |
| Les clés `snapshotId`, `providerId`, `providerVersion`, `completedAt` sont écrites d'un côté et lues de l'autre | [Existant vérifié] | `LocalProjectOperations.java:213-229` ; `ProjectInspectionService.java:241-248` |
| Deux enregistrements `IndexHistory` de même forme | [Existant vérifié] | `LocalProjectOperations.java:240` ; `ProjectInspectionService.java:279` |
| L'historique est une preuve secondaire : son échec d'écriture n'annule pas l'import | [Existant vérifié] | `minos-cli/src/test/java/com/minos/cli/ImportHistoryFailureTest.java:19-64` |
| Deux tests de `minos-application` écrivent ou relisent le format eux-mêmes | [Existant vérifié] | `LocalProjectOperationsHistoryTest.java:53-93` ; `ProjectInspectionServiceRefusalMessageTest.java:38-45` |
| Port dans `minos-engine`, implémentation dans `minos-storage`, fournie par la racine de composition | [Proposition] | design, D1 |
| L'historique reste sur fichiers avec PostgreSQL | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : `minos-engine` (port), `minos-storage` (implémentation et tests, dont les cas portés depuis `LocalProjectOperationsHistoryTest`), `minos-application` (deux classes, port du composeur, deux tests adaptés), `minos-bootstrap` (fabrique, test d'inspection après import).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucun changement de sortie. `MinosApplicationComposer` gagne une méthode abstraite (additif pour ses appelants, à implémenter par une implémentation tierce). `MinosApplication` ne gagne aucun accesseur public pour ce port (A11).
- **ADR** : aucun nouveau. L'ADR commun du sprint (domaine `ARCH`, prochain numéro libre à l'écriture, changement `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`) cite ce port parmi les adaptateurs câblés par la racine.
- **Gates à rejouer** : `check-module-boundaries.py` (A2, A3, A8, A14, règles de packages de stockage), `check-private-io.py`, `check-comment-language.py`, `check-current-docs.py` ; aucun scope JaCoCo ne cite ces classes au HEAD.
- **Plateformes** : Windows et Linux — droits privés du fichier par les primitives existantes (ACL sous Windows, mode POSIX sous Linux), sans branche nouvelle.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, page des backends de stockage de `docs/user/`, `docs/developer/indexing-and-storage.md`.

## Questions ouvertes

Aucune bloquante. D2 (historique hors du backend sélectionné) est soumise au propriétaire avec une recommandation.
