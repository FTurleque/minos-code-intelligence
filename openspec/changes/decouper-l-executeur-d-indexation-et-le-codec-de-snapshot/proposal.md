# Proposal

## Why

Constat **AUD-ARC-09** de l'audit du 2026-11-15 (sprint 8) : deux classes de plus de 1 000 lignes vivent sur des chemins souvent modifiés.

- `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java` — **1 025 lignes**, classe à constructeur privé dont le seul point d'entrée est `static IndexingRun execute(RunRequest, Collaborators)`. Elle porte la validation de la requête, la reprise (BE-0039), l'exécution des cibles et leurs points de reprise, la mise en place et la promotion du snapshot, et la persistance de toutes les issues d'un run.
- `minos-storage/src/main/java/com/minos/storage/codec/SnapshotBinaryCodecSupport.java` — **1 106 lignes**. Elle porte le cadre des trois formats, le codage de chaque entité du snapshot, l'encodage des chaînes et le lecteur borné.

Le changement archivé `2026-10-29-decouper-les-classes-de-plus-de-800-lignes` avait laissé ces deux fichiers hors lot (sa décision Q5) ; l'audit les reprend pour le découpage des responsabilités, sans seuil de taille.

## What Changes

- `IndexingRunExecutor` garde son point d'entrée, ses enregistrements `RunRequest` et `Collaborators` et l'ordre de ses refus ; ses phases deviennent des classes du même package, à collaborateurs reçus par constructeur : reprise, exécution des cibles, publication du snapshot, issues du run.
- `SnapshotBinaryCodecSupport` garde les constantes de format, les plafonds et les points d'entrée appelés par `SnapshotCodecV1`, `SnapshotCodecV2`, `SnapshotCodecV3`, `SnapshotCodec`, `SnapshotLimits` et `KnowledgeSnapshotCodecs` ; le codage des entités, l'encodage des chaînes et le lecteur borné deviennent des classes du même package.
- Aucun comportement observable ne change : mêmes octets persistés, mêmes messages, même ordre de refus, mêmes sorties publiques.
- Les gates qui affirment des chaînes dans ces deux fichiers et les scopes JaCoCo qui les ciblent par préfixe suivent le découpage dans la même PR.

Deux PR, une par module ; chacune fusionnable seule.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

Aucune : remaniement à comportement constant, `skip_specs: true`. Les exigences de `persistance-snapshots` et de `frontieres-de-modules` restent vraies sans modification.

## Hors périmètre

- Un seuil de taille de fichier dans un gate (écarté par la décision Q1 du précédent du 2026-10-29 et par AUD-QUA-07).
- Un nouveau module, un nouveau package, un type rendu public.
- Tout changement du format persisté, des plafonds ou de la politique de reprise.
- La réduction des lectures de la voie WSL (AUD-PERF-07, sprint 6, changement `reduire-les-lectures-de-la-voie-wsl`), qui modifie `IndexingRunExecutor` et se fusionne avant.
- Les autres fichiers de plus de 800 lignes.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `IndexingRunExecutor` : 1 025 lignes, constructeur privé, entrée statique unique | [Existant vérifié] | `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:44-54` ; `wc -l` |
| `SnapshotBinaryCodecSupport` : 1 106 lignes | [Existant vérifié] | `wc -l` |
| Deux gates affirment des chaînes dans le fichier du codec | [Existant vérifié] | `scripts/remediation/check-mne.py:37`, `:84-85` ; `scripts/remediation/check-audit-remediation-v2.py:138-144` |
| Trois scopes JaCoCo ciblent ces classes par préfixe | [Existant vérifié] | `scripts/quality/check-jacoco.py:27-29`, `:281-298` |
| Les noms des classes extraites | [Proposition] | design D2, D3 ; arrêtés à l'implémentation après relecture |

## Impact

- **Modules du reactor** : `minos-engine` (`com.minos.orchestration`), `minos-storage` (`com.minos.storage.codec`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; toutes les classes concernées sont de visibilité package.
- **ADR** : aucun à écrire ni à amender. BE-0039 cite `IndexingRunExecutor` par son nom, qui reste.
- **Gates à rejouer** : `scripts/remediation/check-mne.py`, `scripts/remediation/check-audit-remediation-v2.py`, `scripts/architecture/check-module-boundaries.py` (A8, A14, A15), `scripts/architecture/check-private-io.py`, `scripts/quality/check-comment-language.py`, `scripts/quality/check-jacoco.py` (scopes `critical-orchestration`, `resume-orchestration`, `storage-codec-and-state`, `persistence-cache-indexes`, `global`) et son `--self-test` ; baselines SpotBugs des deux modules.
- **Plateformes** : Windows et Linux — aucun comportement propre à une plateforme ; la couverture se compare sur le rapport Linux de la CI.
- **Documentation durable et diagrammes** : `docs/architecture/arc42/05-vue-blocs.md:55`, `docs/architecture/arc42/06-vue-execution.md:53`, `docs/developer/architecture.md:100`, `docs/developer/indexing-and-storage.md`, `docs/architecture/diagrams/capacites/decouverte-et-negociation.md`, `docs/architecture/diagrams/capacites/persistance-snapshots.md`, `docs/architecture/diagrams/seq-indexation-nominale.md` (relecture ; mise à jour seulement si un nom cité bouge).

## Questions ouvertes

Aucune bloquante. D5 (objets à état ou classes statiques par phase) porte une recommandation dans le design.
