# Proposal

## Why

Le lanceur AppContainer Windows accorde au SID du conteneur des droits `(OI)(CI)RX` ou `M` sur des répertoires réels de l'hôte (runtimes, outils, répertoires de travail) et ne les retire qu'en fin d'exécution normale. Un lanceur tué (timeout, arrêt brutal, JVM abattue) laisse ces droits en place : l'audit du 8 octobre 2026 a trouvé **460 entrées explicites** `S-1-15-2-…` sur la racine du JDK du poste de développement (MINOS-AUD-H22). Pendant le PIT de `minos-runtime-local`, le poste a en outre perdu l'usage de son JDK et la lecture du DACL du profil, cause non établie (H23). La récupération par propriétaire livrée pour A01 retire bien les autorisations **journalisées** d'un run mort, au lancement suivant dans le même répertoire de reprise (`MINOS_HOME/sandbox/appcontainer-recovery-v2`). Deux trous restent : chaque autorisation était journalisée **après** son application (un arrêt entre les deux laissait une entrée qu'aucun journal ne nommait), et un répertoire de reprise jamais réutilisé (`MINOS_HOME` temporaire des tests, minion PIT tué) laisse ses journaux et ses entrées sans reprise. Constats : `docs/quality/code-audit-constats.md` § 5.

## What Changes

- Chaque autorisation est **journalisée avant d'être appliquée** (le journal existait, il était écrit après l'octroi). *(fait)*
- La reprise d'un run mort retire les autorisations journalisées : **existait déjà** (A01) ; un chemin journalisé mais jamais accordé est repris sans erreur. *(test ajouté)*
- Un diagnostic (`doctor` ou script dédié) recense les entrées `S-1-15-2-…` orphelines sur les chemins que MINOS accorde, et propose leur retrait.
- Précautions d'exécution : les tests et l'audit par mutation de `minos-runtime-local` sous Windows s'exécutent dans une machine jetable ; la documentation d'audit le dit.
- Alternative à évaluer dans la conception : copies privées des runtimes au lieu de droits sur les originaux.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `confinement-code-non-fiable` : exigence ajoutée sur la réversibilité des autorisations accordées à l'hôte après un arrêt brutal.

## Hors périmètre

- Réparer le poste touché par H23 : action de l'utilisateur, en administrateur.
- Le bac à sable Linux (aucune modification d'ACL de l'hôte).

## Impact

- Module : `minos-runtime-local` (script `windows-appcontainer-sandbox-v4.ps1.template`, fragments `appcontainer-recovery-ownership`, `WindowsAppContainerWorkerSandboxBackend`) ; éventuellement `minos-cli` (`doctor`).
- Surfaces publiques : diagnostic additif seulement.
- ADR : amende l'ADR 0038 (récupération du bac à sable Windows) ; à confirmer en conception.
