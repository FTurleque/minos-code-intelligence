# Proposal

## Why

**AUD-ARC-02** (moyenne, sprint 9 de l'audit du 10 octobre 2026 ; il débloque AUD-ARC-01 et AUD-PERF-04) et la part « coordinateur » d'**AUD-QUA-02**. Deux orchestrations de l'indexation incrémentale coexistent au HEAD `949e1569` :

- `minos-engine/src/main/java/com/minos/orchestration/IncrementalIndexingCoordinator.java` (220 lignes, classe publique en `:24`) : **0 appelant de production**, 4 classes de test, 7 tests.
- `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java` (`:144-225`, préparation en `:311-353`) : le chemin que le produit emprunte réellement.

Les deux ne font pas la même chose. Des tests d'intégration réels (fixture, rédaction des diagnostics) valident donc un chemin que personne n'exécute, et le correctif D06 (empreinte capturée avant la découverte) a dû être écrit deux fois (coordinateur `:86-90`, CLI `:314-316`). Le chantier SH-05 (`docs/project/roadmap/storage-hexagonal-2026-10/TASKS.md`, ADR 0057 § 2) risque d'en créer une troisième.

## What Changes

- **Une seule orchestration**, comme cas d'usage de `minos-application`, invocable sans CLI ; la CLI lui délègue et ne garde que les arguments, les vues de surface, les formats et les codes de sortie.
- **Le coordinateur est supprimé** avec son type de résultat ; ses tests sont **reportés** sur le chemin du produit, pas supprimés.
- **Deux comportements du coordinateur sont repris** parce qu'ils sont meilleurs : le diagnostic d'une baseline d'empreinte illisible, et l'échec de publication de l'empreinte rendu comme diagnostic (décision en attente, voir `design.md`, D3).
- **Gates littéraux et documents** qui nomment le fichier de la CLI ou le coordinateur mis à jour dans la même tâche.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `decouverte-et-negociation` : une exigence modifiée (« L'empreinte de référence est capturée avant la découverte » ne nomme plus deux flux) et deux ajoutées (une seule orchestration, invocable sans CLI ; une baseline illisible est déclarée).
- `frontieres-de-modules` : une exigence modifiée (« L'incrémental ne dépend plus de l'orchestration en retour » ne nomme plus des classes retirées) et une ajoutée (les surfaces n'assemblent pas le cycle d'indexation).

## Hors périmètre

- Réduire les autres imports du moteur par les surfaces et la règle de liste fermée A11 : AUD-ARC-01, sprint 10, changement actif `figer-la-dette-de-frontieres-par-des-cliquets`. Ce changement en est le prérequis, pas la réalisation.
- Le nombre de hachages de l'arbre source (AUD-PERF-04) : changement `hacher-l-arbre-source-une-fois-par-indexation`, à faire après celui-ci.
- Le contrat d'erreur du port d'état d'index (AUD-QUA-03) et les autres classes sans appelant (reste d'AUD-QUA-02) : changements `donner-un-contrat-d-erreur-au-port-d-etat-d-index` et `retirer-les-services-sans-appelant-de-production`.
- Une exposition d'écriture par le MCP (ADR 0017) ; l'indexation distante (`LocalRemoteIndexOperations`), qui continue d'appeler le même chemin.
- Les autres tâches du chantier hexagonal (SH-02, SH-03, SH-06, SH-08).

## Impact

- **Modules du reactor** : `minos-application` (cas d'usage nouveau), `minos-cli` (délégation), `minos-engine` (suppression du coordinateur et de `IncrementalIndexingResult`), `minos-bootstrap`, `minos-app` (tests reportés). Aucune dépendance Maven ajoutée.
- **Surfaces publiques** : CLI — sorties et codes de sortie de `minos index` **inchangés à l'octet**, sauf décision D3 (un diagnostic additif à la place d'une erreur) ; API Java et MCP — aucune méthode ajoutée ni retirée ; IntelliJ et NEXUS — aucun effet.
- **ADR** : note de « Mise en œuvre » de l'ADR 0057 (§ 2 réalisé) ; aucun nouvel ADR attendu. Tâche SH-05 du backlog cochée.
- **Gates à rejouer** : `python scripts/remediation/check-post-mne.py` (`:269-276`), `python scripts/quality/check-remote-distributed-consistency.py` (`:130`), `python scripts/quality/check-polyglot-provider-consistency.py` (`:104`, `:243-244`), `python scripts/architecture/check-module-boundaries.py`, `python scripts/quality/check-single-execution.py`, `python scripts/quality/check-jacoco.py`, `python scripts/docs/check-current-docs.py`, goldens de `minos-app`.
- **Plateformes** : Windows et Linux, sans différence attendue.
