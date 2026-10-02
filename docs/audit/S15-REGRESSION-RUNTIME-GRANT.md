# Régression du lot S7/S15 : le bac à sable AppContainer ne lit plus le runner de `scip-java`

Constat du 2026-10-02, sur `develop` après la PR #321 (merge `2663bbda`). Ce fichier consigne la décision, écrite avant
le code, puis la preuve.

## Constat

Sous Windows, `ManagedScipProviderRuntimeManager.installJavaWindowsRuntime` crée `tools\scip-java\<version>\runtime`
avec `PrivateLocalStorage.ensurePrivateDirectory`, puis y copie le runner (`scip-java-windows-runner.ps1`) et le
fichier `ScipWriter.java` que le runner lit à côté de lui (`$PSScriptRoot`).

Depuis la PR #321, `PrivateLocalStorage` exécute `icacls <dir> /inheritance:d` : la DACL de `runtime\` est
**protégée**, ne contient que l'entrée du propriétaire, et ne reçoit plus rien de son parent.

Or le bac à sable (`WindowsAppContainerWorkerSandboxBackend`) accorde `(OI)(CI)RX` à l'identité AppContainer sur la
racine `tools\<provider>\<version>` seulement (`managedRuntimeRoot`) et suppose que tout ce qu'elle contient en hérite.
L'entrée héritable ne traverse plus `runtime\` : le runner n'est plus lisible, et PowerShell répond que l'argument
`-File` n'existe pas (code `-196608`).

## Options

| Option | Effet | Verdict |
|---|---|---|
| Désactiver la protection de `runtime\` (`/inheritance:e`) | Rétablit l'héritage | Rejetée : affaiblit S5/S6/S15, ces dossiers restent propriétaire seul |
| Accorder `RX` sur le seul fichier runner (`readFile`) | Le runner redevient lisible | Insuffisante : `ScipWriter.java`, lu par le runner à côté de lui, resterait illisible (échec reporté d'une étape) |
| Accorder `(OI)(CI)RX` sur le **dossier parent réel** de chaque fichier d'argument situé sous la racine gérée | Le runner et ses voisins sont lisibles | **Retenue** |
| Accorder tous les dossiers intermédiaires, ou `MINOS_HOME` | | Rejetée : plus large que nécessaire, et propagation sur des arbres entiers (Node, Maven) sans bénéfice |

## Décision

Dans `addExistingArgumentAccess`, quand un argument existant est un **fichier régulier** dont le chemin réel
(`toRealPath`) est sous `tools`, la racine `tools\<provider>\<version>` reste accordée comme avant, et le **dossier
parent réel** du fichier l'est aussi lorsqu'il n'est pas déjà cette racine.

Pourquoi le parent et pas le fichier : l'ACE posée sur le dossier se propage aux fichiers qui héritent de lui (le
runner et son voisin), alors qu'une ACE sur le seul runner laisserait `ScipWriter.java` hors d'atteinte. Le parent est
la plus petite portée qui couvre le script et ce qu'il lit à côté de lui. Aucun dossier intermédiaire n'a besoin
d'ACE : un AppContainer traverse sans droit sur les dossiers parents (privilège de contournement du parcours), c'est
déjà ce que `readFile` suppose pour un fichier hors de `tools`.

Bornes :

* seul un chemin **réel** sous `tools` déclenche l'ajout : un lien ou une jonction qui sort de `tools` retombe sur
  l'ancien comportement (le fichier seul, pas son dossier) ;
* le parent d'un chemin réel sous la racine du provider est lui-même sous cette racine : jamais d'élargissement à
  `tools` ni à `MINOS_HOME` ;
* seuls les **arguments** sont concernés, pas l'exécutable : aucun exécutable géré ne vit dans un dossier protégé qui ne
  soit pas la racine (voir plus bas).

## Autres chemins du même type

Dossiers que `PrivateLocalStorage` protège (propriétaire seul, sans héritage), et position par rapport à la racine
accordée :

| Chemin | Rôle | Concerné ? |
|---|---|---|
| `tools\scip-java\<v>\runtime` | runner et `ScipWriter.java` | **oui**, corrigé ici |
| `tools\coursier\<id>` | est la racine accordée ; `cs.exe` en hérite | non |
| `tools\maven\<v>`, `tools\nodejs\<v>` | sont la racine accordée ; les sous-dossiers de l'archive sont créés sans protection et héritent | non |
| `tools\scip-typescript\<v>` | racine accordée (un `partial` protégé y est déplacé) ; les sous-dossiers viennent de `npm` et héritent | non |
| `tools\scip-java\<v>` et `tools\scip-java` | la racine reçoit l'ACE directement | non |

## À traiter plus tard (hors périmètre, constatés, non corrigés ici)

1. **Défaut plus profond sous AppContainer** : PowerShell 5.1 ne peut pas changer de répertoire courant sous
   AppContainer sur ce poste, `C:\` n'étant pas lisible par ALL APPLICATION PACKAGES. L'index Java complet y échoue
   encore (`Install-CommandShims`, `Microsoft.PowerShell.Core\FileSystem`).
2. **Message d'erreur final** de l'indexation sans le détail `index failed: IllegalStateException`.
3. **Le runner masque le vrai échec** derrière un message plus général.
