# Proposal

## Why

**AUD-PERF-04** (moyenne, sprint 9 de l'audit du 10 octobre 2026, dépend d'AUD-ARC-02). Une indexation dont une cible est à la racine du projet hache **trois fois** tout l'arbre source :

1. empreinte « avant » : `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:316` ;
2. empreinte du scope racine, pour le point de reprise : `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:320-321` → `minos-engine/src/main/java/com/minos/orchestration/ExecutionCheckpoints.java:39` ;
3. empreinte « après » : `LocalAutonomousIndexOperations.java:200`.

Or l'empreinte d'un scope vide **est** l'empreinte du projet (`minos-engine/src/main/java/com/minos/incremental/ProjectFingerprintService.java:59-60`) : la deuxième recalcule la première. Chaque capture rouvre et hache chaque fichier en entier (`:324`), jusqu'au budget source de 100 000 fichiers ou 2 Gio, même quand un seul fichier a changé. Deux coûts que l'audit ne compte pas s'ajoutent : en reprise, un hachage par cible réutilisée (`minos-engine/src/main/java/com/minos/orchestration/IndexingResumePlanner.java:268`) ; et un hachage par scope non racine.

Le coût en temps **n'a pas été mesuré** sur ce constat.

## What Changes

- **L'empreinte « avant » est réutilisée pour le scope racine** : au plus deux hachages complets (avant, après) par indexation à cible racine, au lieu de trois.
- **La valeur persistée du point de reprise reste identique**, de sorte qu'une reprise commencée avant le changement est toujours acceptée.
- **La réutilisation est refusée quand l'égalité des politiques n'est pas prouvée** : dans ce cas l'empreinte du scope est recalculée, comme aujourd'hui.
- **L'empreinte « après » reste un hachage complet** : le raccourci taille / date proposé par l'audit est sorti du périmètre (voir `design.md`, D3).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `decouverte-et-negociation` : deux exigences ajoutées (au plus deux hachages complets par indexation à cible racine ; l'empreinte de scope persistée ne change pas de valeur et la preuve de stabilité reste un hachage complet).

## Hors périmètre

- Le raccourci taille / date de modification / clé de fichier pour l'empreinte « après » : il affaiblit la preuve de stabilité (D3). Une décision contraire demande son propre changement et un amendement de l'ADR 0039.
- Dériver l'empreinte d'un scope non racine par filtrage de l'empreinte du projet : gain réel mais invariants d'ancêtres à prouver (D4) ; reporté.
- Le hachage de vérification à la reprise (`IndexingResumePlanner.java:268`) : c'est une garde fail-closed de l'ADR 0039, conservée.
- Le parcours de découverte et la lecture des sources par les providers (AUD-PERF-01, changement `lire-chaque-source-une-fois-par-fichier`).
- L'unification de l'orchestration (AUD-ARC-02), prérequis traité par `unifier-l-orchestration-d-indexation`.

## Impact

- **Modules du reactor** : `minos-engine` (`orchestration`, `incremental`), `minos-application` (le cas d'usage d'indexation issu d'AUD-ARC-02 transmet l'empreinte), `minos-cli` si le changement précède l'unification. Aucune dépendance ajoutée.
- **Surfaces publiques** : aucune sortie ne change (CLI, API Java, MCP, IntelliJ, NEXUS). Le format des points de reprise est inchangé.
- **ADR** : note de « Mise en œuvre » de l'ADR 0039 (une empreinte de scope racine peut être fournie par l'appelant) ; aucun nouvel ADR.
- **Gates à rejouer** : `python scripts/quality/check-jacoco.py` (scopes `critical-orchestration` et `resume-orchestration`), `python scripts/remediation/check-post-mne.py`, `python scripts/architecture/check-private-io.py`, la baseline `quality/spotbugs-baseline/minos-engine.xml` (3 entrées sur `ProjectFingerprintService`, repérées par numéros de ligne).
- **Plateformes** : Windows et Linux, sans différence attendue.
