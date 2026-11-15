# Proposal

## Why

Constat AUD-SEC-01 de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, sévérité Faible, confirmé au HEAD `89be3fb4`). La décision du 2026-10-13 sur les filtres Git (`ouvrir-le-depot-git-sans-executer-ses-filtres`, D3) ne couvre que l'analyse d'un dépôt local : son design écarte explicitement `JGitRemoteRepositoryMaterializer` (Non-Goals). Le clone d'un dépôt distant et la revalidation de son extraction lisent donc le contenu d'un dépôt non fiable sans décision écrite, et la revalidation ouvre le dépôt sans l'ouverture de confiance que la règle 5 de `.claude/rules/securite.md` impose à tout appel JGit qui lit l'index d'un dépôt analysé.

Le dépôt est public : cette proposition décrit l'exigence et son test, pas un mode d'exploitation.

## What Changes

- La matérialisation d'un dépôt distant (clone puis extraction) n'exécute aucun pilote `filter.*`, quelle que soit la configuration qui le déclare (dépôt, utilisateur, système) : le contenu extrait est le contenu brut des objets Git. [Proposition — option B de la décision D1, en attente du propriétaire]
- La revalidation d'une extraction (après clone et à chaque réutilisation du cache) passe par l'ouverture de confiance du module `minos-integration-git` : propriétaire contrôlé, aucun filtre exécuté, refus fail-closed.
- Un gate littéral refuse toute ouverture JGit directe d'un dépôt analysé hors de cette ouverture.
- La décision est écrite dans un ADR `SEC-*` (à créer en tâche).

Aucune rupture de contrat : les sorties CLI, API Java et MCP de l'indexation distante ne changent pas de forme. Un dépôt distant dont des fichiers passent par un filtre de l'utilisateur (git-lfs) est désormais indexé sur son contenu brut (pointeurs LFS).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `analyse-git` : trois exigences ajoutées (clone distant sans filtre, revalidation par l'ouverture de confiance, garde contre l'ouverture directe). Aucune exigence existante n'est modifiée : « Les filtres de l'utilisateur ne sont pas retirés » reste vraie pour l'analyse locale (`inspect`, `analyze`, `git-activity`).

## Hors périmètre

- L'analyse d'un dépôt local : la décision D3 du 2026-10-13 (filtres de l'utilisateur appliqués) est inchangée.
- L'exécution d'un filtre de l'utilisateur dans un bac à sable ; le téléchargement des objets LFS.
- Le transport du clone (délais, épinglage de l'hôte, budget) et le cache (publication, éviction) : inchangés.
- Les autres constats de sécurité de l'audit (AUD-SEC-02 à 04 : sprint 3 ; AUD-SEC-05 : changement `refuser-un-nom-de-secret-herite-et-une-garde-illisible`).

## Découpage

Un seul constat, un seul module (`minos-integration-git`), une décision de modèle de confiance : changement fusionnable seul. Il n'est pas regroupé avec AUD-SEC-05 et AUD-QUA-05 (module `minos-runtime-local`, aucun fichier commun, aucune décision en attente).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le clone distant ne configure ni registre de filtres ni lecteur de configuration isolé | [Existant vérifié] | `minos-integration-git/src/main/java/com/minos/integration/git/JGitRemoteGitClient.java:25-35` |
| `validateCheckout` ouvre le dépôt par `Git.open` et appelle `status()` | [Existant vérifié] | `JGitRemoteRepositoryMaterializer.java:282-293`, appelé en `:202` et `:253` |
| Seuls les pilotes déclarés par la configuration du dépôt sont redirigés | [Existant vérifié] | `GitIntelligenceService.java:323-341` |
| Le clone distant est hors de la décision du 2026-10-13 | [Existant vérifié] | `openspec/changes/archive/2026-10-13-ouvrir-le-depot-git-sans-executer-ses-filtres/design.md:32` |
| Le clone distant n'exécute aucun filtre | [Proposition] | décision D1 du design, option B recommandée |
| La revalidation passe par l'ouverture de confiance | [Exigence] | règle 5 de `.claude/rules/securite.md` |

## Impact

- **Modules du reactor** : `minos-integration-git` (code et tests). Direction des dépendances inchangée.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune forme modifiée ; écart de fidélité possible sur un dépôt distant à filtres (contenu brut), à documenter.
- **ADR** : un nouvel ADR `SEC-<prochain numéro libre>` (prochain numéro libre lu dans `docs/architecture/adr/README.md` au 2026-11-15, non réservé) « Filtres Git à la matérialisation d'un dépôt distant ». Aucun ADR amendé.
- **Gates à rejouer** : `scripts/quality/check-remote-distributed-consistency.py` (affirme des littéraux de `JGitRemoteGitClient.java` et `JGitRemoteRepositoryMaterializer.java`), `scripts/quality/check-jacoco.py` (scope qui nomme `JGitRemoteRepositoryMaterializer` et `JGitCloneDeadline`), `scripts/architecture/check-module-boundaries.py`, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast`.
- **Ordre** : après `doter-chaque-gate-d-un-auto-test` (sprint 1), qui donne son auto-test au gate que ce changement étend.
- **Plateformes** : Windows et Linux ; le contrôle du propriétaire et la redirection des filtres valent sur les deux.
- **Documentation durable** : `docs/architecture/diagrams/capacites/analyse-git.md`, `docs/user/remote-indexing.md`, `.claude/rules/securite.md` (règle 5), index des ADR et `docs/architecture/arc42/09-decisions.md`.
