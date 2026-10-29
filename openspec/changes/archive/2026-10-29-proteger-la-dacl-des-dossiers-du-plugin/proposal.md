# Proposal

## Why

**Incident MINOS-AUD-H23, cause probable établie le 2026-10-15.** Sous Windows, le lanceur du plugin IntelliJ rend privés trois dossiers avant de démarrer le CLI : le dossier MINOS (`user.home/.minos` par défaut, ou le `minosHome` des réglages), `intellij` et `process-ownership` (`MinosStrongProcessLauncher.java:79-85`). `restrictWindowsOwnerOnly` (`:143-171`) **remplace la DACL entière** par une seule entrée au nom du **propriétaire du dossier** (`view.setAcl(List.of(ownerOnly))`, `:161`), que le dossier ait été créé par le lanceur ou non.

Le 8 puis le 15 octobre 2026, un PIT local du plugin a réduit la DACL du profil `C:\Users\fturl` à la seule entrée `AUTORITE NT\Système:(F)`, sans héritage : le profil appartient à Système, et un mutant de `ownershipHome` (`:288-294`, `defaultHome ? root.resolve(".minos") : root`) fait du profil lui-même le dossier MINOS. Plus aucune JVM ne démarrait (`Error loading java.security file`) et l'ACL n'était plus lisible, même en administrateur ; l'utilisateur l'a réparée en administrateur.

Le défaut existe **sans mutation** : un `minosHome` configuré sur le profil, un dossier partagé ou une racine de volume voit sa DACL réécrite au nom de son propriétaire, quel qu'il soit.

## What Changes

- Le lanceur **refuse**, avant toute modification, un dossier MINOS qui est le profil de l'utilisateur, un de ses ancêtres ou une racine de volume.
- Un dossier qui **existait déjà** n'est rendu privé que s'il appartient à l'utilisateur courant ; sinon le lancement échoue avec un message qui dit quoi faire, et la DACL n'est pas touchée.
- L'identité de référence est le propriétaire que le système donne à un fichier créé par le processus (comme `PrivateLocalStorage.verifyOwnedByCurrentUser` du moteur), jamais un nom ou une propriété.
- Un dossier créé par le lanceur garde le comportement actuel.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `client-intellij` : une exigence ajoutée (le lanceur ne réécrit jamais la DACL d'un dossier qui n'est pas à lui).

## Hors périmètre

- Le défaut analogue du moteur (`PrivateLocalStorage.ensurePrivateDirectory` durcit un dossier existant sans vérifier son propriétaire) : relevé dans `design.md`, à instruire dans un changement de `confinement-code-non-fiable` ou des E/S privées.
- Le PIT du plugin : il ne tourne plus sur un poste (`docs/developer/quality/code-audit.md`) ; ce changement retire le danger, il ne rend pas ce PIT sûr sur un poste.
- Linux : le lanceur n'y touche aucun droit de dossier (portée systemd).

## Impact

- **Modules** : `minos-intellij` (production et tests). Aucune dépendance `com.minos:*` ajoutée.
- **Surfaces publiques** : aucune ; un lancement autrefois accepté (dossier MINOS = profil, racine ou dossier d'un autre propriétaire) est désormais refusé avec un message actionnable.
- **Gates** : `python scripts/remediation/check-p0-p2.py` (`:331` exige `attributes.isOther()` dans le lanceur) ; contrôle « aucune dépendance `com.minos:` » de `intellij-plugin.yml`.
- **Plateformes** : Windows seulement pour le comportement ; la garde « profil, ancêtre, racine » est testée sur tous les systèmes.
