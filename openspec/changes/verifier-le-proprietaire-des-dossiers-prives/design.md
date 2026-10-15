# Design

## Context

Suite de MINOS-AUD-H23 et de la PR #463 (garde du lanceur du plugin, fusionnée le 2026-10-15, `f50bb1b1`). HEAD analysé : `f50bb1b1` (`develop`). `P` = `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java`.

## État vérifié au HEAD

- `P:120-125` : `ensurePrivateDirectory` = `createPrivateDirectories`, puis `hardenDirectory`, puis `verifyPrivateDirectory`, que le dossier ait existé ou non.
- `P:368-397` : sur ACL, `harden` lit le propriétaire du dossier et écrit `ownerOnlyAcl` (DENY existants, puis ALLOW du propriétaire) ; au premier contact d'une JVM, la DACL est réécrite même si elle semble déjà bonne, puis protégée de l'héritage.
- `P:264-286` : `verifyOwnedByCurrentUser` compare le propriétaire au propriétaire d'un fichier probe créé **dans le parent** du chemin vérifié, mémorisé pour la JVM. Appelé seulement par `SandboxLauncherScript` (`:108`, `:117`, `:141`, `:171`, `:242`).
- Appelants qui passent un chemin choisi par l'utilisateur : `MinosApplication.open` (`MinosApplication.java:221`), `MinosApplicationAssembler.build` (`:47`), `McpBackendConfigurationStore` (`:109`). Une cinquantaine d'autres appels portent sur des dossiers sous le `MINOS_HOME`.
- Image Docker : `MINOS_HOME=/var/lib/minos`, propriété de l'utilisateur 10001, parent `/var/lib` non inscriptible, monté en lecture seule pour le plan de requête MCP (`scripts/ci/qualify-docker-release.sh:309-330`).

## Décisions

### D1. Sur ACL seulement

La garde s'applique quand le système de fichiers n'est pas POSIX (Windows). Sous POSIX, `setPosixFilePermissions` par un non-propriétaire échoue déjà ; ajouter un probe y casserait le plan de requête en lecture seule du conteneur.

### D2. Profil, parents, racine : refus avant toute écriture

`user.home` n'est consulté que s'il est absolu et désigne un dossier existant (un `user.home` inconnu, « ? », ne doit pas faire refuser un dossier). Comparaison sur chemins absolus normalisés (insensible à la casse sous Windows).

### D3. Dossier préexistant d'un autre compte : refus, DACL intacte

`verifyOwnedByCurrentUser` est appelé sur un dossier préexistant avant `hardenDirectory`. Le message ne contient aucun chemin (règle des sorties) et dit de reprendre la propriété ou de choisir un autre `MINOS_HOME`.

### D4. Probe replié sur le dossier temporaire

Si le probe ne peut pas être créé dans le parent (non inscriptible), il l'est dans le dossier temporaire du système : le propriétaire attribué à un nouveau fichier ne dépend pas du dossier.

### D5. Point de test

Un point d'entrée de paquet `ensurePrivateDirectory(Path, UserPrincipal current, Path userHome)` permet de simuler un dossier d'un autre propriétaire (principal attendu fourni par le test) et un profil redirigé vers un dossier temporaire, sans droits d'administration ni contact avec le vrai profil.

## Risques

- Un `MINOS_HOME` créé par une exécution élevée (propriétaire : Administrateurs) puis ouvert sans élévation est désormais refusé avec un message ; il était déjà inaccessible à l'utilisateur non élevé après durcissement.
- Un appel de plus à `Files.getOwner` par dossier préexistant sous Windows.
