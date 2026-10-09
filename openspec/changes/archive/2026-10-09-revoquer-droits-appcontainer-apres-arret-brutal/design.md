# Design

## Context

`windows-appcontainer-sandbox-v4.ps1.template` : `Grant-AppContainerDirectory` (lignes 469-476) et `Grant-AppContainerFile` (478-484) appellent `icacls /grant` ; `Remove-AppContainerPath` (486-489) appelle `icacls /remove:g`, depuis le bloc de nettoyage (ligne 558) qu'un processus tué n'exécute pas. Les octrois se font ligne 525 (lecture, `(OI)(CI)RX`), 530 (fichiers, `RX`) et 535 (écriture, `(OI)(CI)M`). La propriété exclusive d'un run (verrou `CreateNew`/`FileShare.None`) et la reprise d'un run mort existent depuis A01 (`appcontainer-recovery-ownership.ps1frag`).

## Decisions

1. **Journal d'octroi avant l'octroi** (écriture durable dans le répertoire de récupération possédé par le run) : une ligne par (chemin, SID, droits). Un crash entre l'écriture et l'octroi laisse une ligne sans entrée : le retrait est idempotent (`icacls /remove:g` sans effet).
2. **Retrait à la reprise** : quand un lanceur prouve que le propriétaire d'un run est mort, il retire, pour chaque ligne du journal, l'entrée du SID sur le chemin, puis supprime le profil (comportement A01 inchangé). Une propriété non prouvable n'est jamais récupérée (exigence existante).
3. **Recensement des orphelins existants** : un diagnostic liste les entrées `S-1-15-2-…` sur les chemins que MINOS accorde et dont aucun run vivant n'est propriétaire ; il ne retire qu'à la demande explicite de l'utilisateur.
4. **Exécution des tests** : les tests Windows qui démarrent le bac à sable vérifient en fin de classe qu'aucune entrée de leur SID ne reste ; l'audit par mutation de ce module se fait sur un runner éphémère.

## Risks / Trade-offs

- Un chemin d'octroi supprimé entre-temps → retrait ignoré (déjà le comportement de `Remove-AppContainerPath`).
- Coût : une écriture durable par octroi, négligeable devant le démarrage du conteneur.
- Windows seulement ; Linux non concerné (bubblewrap ne modifie pas les ACL de l'hôte).

## Direction des dépendances

Changement interne à `minos-runtime-local` ; aucune nouvelle dépendance.
