# Proposal

## Why

Sous Windows, le lanceur AppContainer reprend sur incident en parcourant **tous** les journaux du répertoire de reprise, partagé par `MINOS_HOME`, sans savoir à qui ils appartiennent : il retire l'ACE du SID de chaque chemin listé, supprime le profil AppContainer et le journal. Un second processus MINOS (autre `index`, serveur MCP ou IDE qui indexe, `doctor`, statut de provider) qui démarre un lanceur détruit donc les droits et le profil du provider **encore vivant** d'un autre processus : échec « accès refusé » sans rapport apparent avec la cause. C'est l'équivalent Windows de S3 (Linux), corrigé là par une marque de propriétaire ; aucun correctif équivalent n'existe ici. Un second défaut de même famille, côté disque, laisse indéfiniment les copies de projet et les temporaires d'un run tué (A-02).

## What Changes

Deux lots indépendants, livrables et vérifiables séparément.

- **Lot 1 — A01 (Windows, P1).** Le lanceur AppContainer prouve sa propriété sur son sandbox pendant toute la vie de celui-ci, et le balayage de reprise ne récupère que les sandbox dont le propriétaire est **prouvé mort**. Sans preuve (propriété illisible, journal d'un format antérieur), il ne récupère rien et le signale. La preuve ne dépend ni d'une horloge ni d'un identifiant de processus réutilisable (leçons R2 et R11 du mécanisme Linux). Un lanceur qui ne peut pas établir sa propre propriété refuse de démarrer le sandbox.
- **Lot 2 — A02 (toutes plateformes, P2).** Une borne de vie sur les résidus de travail : au moment où MINOS crée un nouveau répertoire de travail, il supprime les répertoires et temporaires de runs morts plus vieux qu'une durée de vie unique, jamais ceux d'un run vivant ou récent, sans jamais faire échouer l'opération qui déclenche le balayage. Racines concernées : `local-provider-workspaces/` (chemin vivant), `.entry-*.tmp` du cache de dépôts distants (chemin vivant), `.accept-*` et racine des workers distribués (code dormant, ADR 0041).
- A07 n'est **pas** tranché ici (voir `design.md`, Questions ouvertes) : le correctif A01 retire seulement le caractère destructeur de la sonde pour un propriétaire vivant.
- Aucune rupture : pas de changement de contrat CLI, API Java, MCP, IntelliJ ni NEXUS.

## Capabilities

### New Capabilities

- `confinement-code-non-fiable`: confinement du code de provider non fiable (sandbox OS, copie éphémère, résidus). Ce changement y inscrit les deux exigences de non-interférence entre sandbox concurrents (Windows) et de borne de vie des résidus, premières exigences de cette capacité (`openspec/specs/` est vide).

### Modified Capabilities

(aucune : `openspec/specs/` est vide)

## Constats d'audit couverts

| Constat | Qualification | Priorité | Lot |
|---|---|---|---|
| MINOS-AUD-A01 — le lanceur AppContainer détruit les ACL et le profil d'un autre sandbox vivant | DÉFAUT CONFIRMÉ (relu au HEAD) ; impact concurrent PLAUSIBLE | P1 | 1 |
| MINOS-AUD-A02 — répertoires de travail et de transit jamais récupérés après un arrêt brutal | DÉFAUT CONFIRMÉ (même famille que R8) | P2 | 2 |
| MINOS-AUD-A07 — des commandes de lecture/diagnostic mutent le système pour qualifier le sandbox | DÉCISION À CLARIFIER (P3, P2 sous Windows tant que A01 est ouvert) | P3 | aucune tâche bloquante : questions ouvertes |

Hors périmètre : A03, A04, A05, A06, A08, A09, A10 (risques, décisions ou code dormant ADR 0041 traités ailleurs). Hors périmètre aussi : le rattachement de A01 aux systèmes autres que Windows (le sandbox Linux a déjà `CgroupJobOwnership`), la suppression ou la scission de la sonde de qualification (A07), la suppression des journaux hérités d'un build antérieur (question ouverte), la rétention de `runs/` (déjà traitée par `RunDirectoryRetention`) et de `staged-snapshots/` (R8).

## Impact

- **Modules du reactor touchés** : `minos-runtime-local` (gabarit PowerShell du lanceur, fragments Windows, backend AppContainer, `LocalProviderWorkspace`, `DistributedArtifactBundleStore`, `LocalIsolatedIndexWorker`), `minos-engine` (paquet `com.minos.io` : primitive partagée de balayage, voir design), `minos-integration-git` (`JGitRemoteRepositoryMaterializer`, temporaires `.entry-*.tmp`). Aucun nouveau module ; direction des dépendances ADR-0022 inchangée (`minos-runtime-local` et `minos-integration-git` dépendent déjà de `minos-engine` seul).
- **Surfaces publiques** : aucune impactée. CLI, API Java, MCP, IntelliJ et NEXUS ne changent ni de contrat ni de sortie. Effet de bord observable : un `MINOS_HOME` perd, à terme, les répertoires de runs morts.
- **ADR** : aucun nouvel ADR et aucun amendement requis. Références : ADR 0038 (le résidu `SCRATCH_RECLAMATION` y est une dimension de containment ; le lot 2 ne change aucune disposition annoncée), ADR 0036 (*Proposed*, règle de capability-honesty), ADR 0041 (code distribué dormant, non rouvert). Si l'on veut qualifier « récupération du scratch après arrêt brutal » comme disposition distincte dans l'ADR 0038, ce serait un amendement : question ouverte du design, non bloquante.
- **Preuve** : le correctif A01 n'est prouvé que par le runner Windows (poste Windows ou job `windows-2022`) ; sous Linux seuls le test d'assemblage du script et les gates littéraux tournent (voir `design.md`).
- **Gates à rejouer** : `scripts/remediation/check-mnd.py`, `check-minos-01.py`, `check-post-mne.py`, `check-post228-hardening.py` (littéraux du gabarit PowerShell), `scripts/quality/check-remote-distributed-consistency.py` (cite `.bundle-`), `scripts/architecture/check-module-boundaries.py` et `check-private-io.py` ; scopes JaCoCo de `scripts/quality/check-jacoco.py` : `provider-sandbox-windows`, `provider-execution-trust-boundary`, `m25-remote-distributed-indexing`, avec `provider-sandbox-linux` et `m24-polyglot-provider-platform` (rouge connu hors régression sous Windows) en non-régression.
