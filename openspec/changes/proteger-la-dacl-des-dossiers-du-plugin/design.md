# Design

## Context

Suite de l'incident MINOS-AUD-H23 (audit outillé du 8 octobre 2026, reproduit le 15 octobre ; `docs/quality/code-audit-couverture.md` § 7.1 sur la branche `docs/completer-la-mutation-et-son-document`). HEAD analysé : `74d85e4f` (`develop`). `L` = `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosStrongProcessLauncher.java`.

## État vérifié au HEAD

- `L:79-85` : `startWindows` appelle `ensureWindowsPrivateDirectory` sur le dossier MINOS, `intellij` et `process-ownership`.
- `L:122-134` : `ensureWindowsPrivateDirectory` refuse un lien ou un point d'analyse, crée le dossier s'il manque, puis appelle `restrictWindowsOwnerOnly` **dans tous les cas**.
- `L:143-171` : `restrictWindowsOwnerOnly` lit le propriétaire du dossier (`Files.getOwner`) et remplace la DACL par une entrée unique à son nom, sans drapeau d'héritage, puis vérifie que toutes les entrées sont à ce propriétaire.
- `L:288-294` : `ownershipHome` rend `user.home/.minos` par défaut, ou le chemin configuré tel quel.
- Moteur, pour comparaison : `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java:255-286` (`verifyOwnedByCurrentUser`) prend comme identité de référence le propriétaire d'un fichier que le processus crée, une fois par JVM ; `:120-125` (`ensurePrivateDirectory`) durcit un dossier existant **sans** l'appeler.
- Gate littéral : `scripts/remediation/check-p0-p2.py:331` exige `attributes.isOther()` dans `L`.

## Décisions

### D1. Refus des emplacements partagés, avant toute modification

Le dossier MINOS ne peut être ni `user.home`, ni un ancêtre de `user.home`, ni une racine de volume. Refus par `IOException` actionnable (« choisissez un dossier dédié à MINOS »), avant toute création ou écriture de droits. La comparaison porte sur les chemins absolus normalisés ; sous Windows, `Path.equals` et `startsWith` ignorent la casse.

### D2. Un dossier préexistant doit appartenir à l'utilisateur courant

Le lanceur note si le dossier existait. S'il existait, son propriétaire doit être l'utilisateur courant ; sinon refus, DACL intacte. L'utilisateur courant est le propriétaire d'un fichier temporaire créé par le processus (dans le dossier temporaire du système, qui n'influe pas sur le propriétaire attribué), mémorisé pour la JVM. Un dossier créé par le lanceur garde le comportement actuel : il est à l'utilisateur, ou au groupe Administrateurs sous une IDE élevée, et devient privé à son propriétaire.

Alternative écartée : ne jamais durcir un dossier préexistant. Une installation existante dont `~/.minos` hérite des droits du profil ne serait alors plus privée ; c'est un recul de confidentialité, pas une protection.

### D3. Un point de test sans fixture

Les décisions D1 et D2 sont des méthodes de paquet testables : `refuseSharedLocation(Path directory, Path userHome)` (tous systèmes) et `requireOwnedBy(Path directory, UserPrincipal expected, String label)` (Windows, sur un vrai dossier, avec un principal attendu fourni par le test pour simuler un dossier d'un autre propriétaire sans droits d'administration).

## Défaut analogue relevé dans le moteur (hors périmètre)

`PrivateLocalStorage.ensurePrivateDirectory` (`:120-125`) appelle `hardenDirectory` sur un dossier existant sans `verifyOwnedByCurrentUser`, et `ownerOnlyAcl` met le **propriétaire du dossier** dans l'entrée ALLOW. Un `MINOS_HOME` configuré sur le profil (propriétaire Système) aurait la même conséquence que H23. Les appelants qui passent un chemin choisi par l'utilisateur ne sont pas inventoriés ici ; à instruire dans un changement des E/S privées (`check-private-io.py`).

## Risques

- Une installation dont `~/.minos` a été créé par un autre compte (copie de profil, outil lancé en administrateur) est désormais refusée : le message dit de recréer le dossier ou d'en reprendre la propriété.
- Le probe crée un fichier dans le dossier temporaire du système, une fois par JVM ; il est supprimé aussitôt.
