# Proposal

## Why

L'exigence « confinement-code-non-fiable › Les autorisations accordées à un conteneur sont retirées même après un
arrêt brutal » suppose que tout droit accordé au SID d'un conteneur reste nommé par un journal de reprise tant que son
retrait n'est pas prouvé. Le nettoyage du lanceur AppContainer Windows doit suivre ce principe fail-closed dans tous
les cas, y compris quand un retrait échoue. Ce changement rend ce comportement explicite, le spécifie et le teste.

## What Changes

- Un retrait de droit dont `icacls` rend un code de sortie non nul est une erreur.
- À la fin d'un run, le journal de reprise n'est supprimé qu'après le retrait prouvé de chaque droit journalisé
  et la suppression prouvée du profil ; sinon il reste en place, réécrit avec les seuls chemins dont le retrait a
  échoué, avec le fichier de verrou relâché à côté, et un avertissement nomme le journal (par son nom de fichier
  seulement).
- Pendant la reprise d'un sandbox dont le propriétaire est mort, la même règle s'applique : un retrait ou une
  suppression de profil en échec laisse le journal et son verrou pour le lanceur suivant, qui rejoue le reste
  de la reprise (supprimer un profil absent est sans effet).
- Le code de sortie du lanceur reste celui du provider : un nettoyage incomplet est rattrapé par le lanceur
  suivant, il ne change pas le résultat de l'indexation.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `confinement-code-non-fiable` : l'exigence « Les autorisations accordées à un conteneur sont retirées même
  après un arrêt brutal » reçoit une phrase normative et deux scénarios de retrait en échec (fin de run et
  reprise).

## Hors périmètre

- Le lanceur Linux (bubblewrap, cgroup v2) : il n'accorde aucune ACL d'hôte.
- Le lanceur Job Object sans AppContainer (`windows-job-object-owner-v1.ps1`) : il n'accorde aucun droit à un SID
  de conteneur.
- Le recensement des autorisations orphelines déjà présentes sur un poste (exigence « Les autorisations
  orphelines existantes sont recensées ») : inchangé.
- Le plugin IntelliJ : il ne porte aucune copie du lanceur AppContainer ni du fragment de reprise.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Aucune autorisation d'un run terminé ou mort ne subsiste après la reprise | [Exigence] | `openspec/specs/confinement-code-non-fiable/spec.md`, exigence citée |
| `icacls /remove:g` d'un SID absent rend 0 ; sous un refus `OWNER RIGHTS:(WDAC)` il rend 5 ; `DeleteAppContainerProfile` d'un profil absent rend `S_OK` | [Existant vérifié] | sonde PowerShell 5.1 exécutée le 2026-11-04 sur Windows 10 19045 |
| Le plugin ne copie ni le gabarit AppContainer ni le fragment de reprise | [Existant vérifié] | aucune occurrence de `Remove-AppContainerPath` ni de `appcontainer-recovery-ownership` hors de `minos-runtime-local` |

## Impact

- **Modules du reactor** : `minos-runtime-local` (gabarit et fragment PowerShell, tests).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun ; la décision reste celle du journal de reprise et du verrou de propriété existants.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast`, `openspec validate --all --strict`,
  `python scripts/docs/check-capability-dossiers.py` ; tests `WindowsContainmentScriptTest` (empreinte du
  fragment de reprise et dérive approuvée du gabarit) et `WindowsAppContainerRecoveryOwnershipTest`.
- **Plateformes** : Windows seulement (AppContainer) ; Linux inchangé.
- **Documentation durable et diagrammes** : dossier `confinement-code-non-fiable` (vue V2, ligne de traçabilité).

## Questions ouvertes

Aucune.
