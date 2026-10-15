# Proposal

## Why

Suite de l'incident **MINOS-AUD-H23** (profil Windows réduit à `AUTORITE NT\Système:(F)`, poste inutilisable pour toute JVM) et de la PR #463, qui a corrigé le lanceur du plugin IntelliJ. Le moteur a le même défaut : `PrivateLocalStorage.ensurePrivateDirectory` (`minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:120-125`) rend privé un dossier **qui existait déjà** sans vérifier à qui il appartient. Sur Windows, `harden` (`:368-397`) remplace la DACL par les seules entrées DENY existantes et une entrée ALLOW au nom du **propriétaire du dossier**.

`MinosApplication.open` (`minos-application/.../MinosApplication.java:221`) et `MinosApplicationAssembler.build` (`:47`) l'appellent sur le `MINOS_HOME` choisi par l'utilisateur : un `MINOS_HOME` posé sur le profil (propriétaire Système), un dossier partagé ou une racine de volume perd ses droits au profit de son propriétaire. La Javadoc de `verifyOwnedByCurrentUser` (`:255-263`) dit déjà de l'appeler « sur tout ce qui préexiste, avant de le durcir » ; seul `SandboxLauncherScript` le fait.

## What Changes

Sur un système de fichiers à ACL (Windows), avant de rendre privé un dossier préexistant, `ensurePrivateDirectory` :

- **refuse** le profil de l'utilisateur, un de ses parents et une racine de volume ;
- **refuse** un dossier qui n'appartient pas à l'utilisateur courant (identité = propriétaire d'un fichier créé par le processus, comme aujourd'hui) ;
- dans les deux cas, ne change rien et lève une erreur sans chemin absolu, qui dit quoi faire.

Un dossier créé par l'appel, et les systèmes POSIX, gardent leur comportement : sous POSIX, un non-propriétaire ne peut déjà pas changer les droits d'un dossier, et le conteneur (dossier MINOS en lecture seule pour le plan de requête, parent non inscriptible) ne doit rien écrire de plus.

Le probe d'identité, aujourd'hui créé dans le dossier parent, se replie sur le dossier temporaire du système quand le parent n'est pas inscriptible.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : une exigence ajoutée (un dossier privé préexistant n'est durci que s'il appartient à l'utilisateur et n'est ni le profil, ni un parent, ni une racine).

## Hors périmètre

- Les fichiers préexistants (`hardenExistingFile`) : appelés par des chemins que MINOS a créés ; non relevés ici.
- POSIX : aucune modification (voir What Changes).
- Le PIT local : déjà proscrit (`docs/quality/code-audit.md`).

## Impact

- **Modules** : `minos-engine` (production et tests). Tous les appelants d'`ensurePrivateDirectory` en bénéficient sans changement.
- **Surfaces publiques** : aucune ; un `MINOS_HOME` posé sur le profil, une racine ou un dossier d'un autre compte est refusé sous Windows avec un message actionnable.
- **Gates** : `python scripts/remediation/check-p0-p2.py` (`:139` exige `attributes.isOther()` dans `PrivateLocalStorage.java`) ; `python scripts/architecture/check-private-io.py` ; scope JaCoCo `confined-io-primitives` s'il est fusionné.
