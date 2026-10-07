# Dépannage

Ce guide couvre l’installation native M14, les providers, l’indexation autonome, les snapshots, MCP et NEXUS.

## Commencer par `doctor`

Pour une installation utilisateur :

```powershell
minos.cmd doctor
```

Pour un checkout source :

```powershell
.\minos.cmd doctor
```

Le diagnostic indique le runtime MINOS, les commandes projet disponibles et l’état de chaque provider géré.

## `BUILD FAILURE` lors du développement de MINOS

Ce cas concerne le checkout source :

```powershell
java -version
.\mvnw.cmd -version
```

Le développement de MINOS exige Java 24 et Maven 3.9.x via le wrapper.

Une distribution installée embarque son propre runtime et n’exige pas un JDK système pour exécuter la CLI/MCP.

## `project root is not registered`

Diagnostic :

```powershell
minos.cmd project list
minos.cmd inspect <project>
```

Correction :

```powershell
minos.cmd project add <root> --name <name>
```

## Projet enregistré mais racine indisponible

`inspect` expose `rootAvailable`.

Si la racine a été déplacée, si une lettre de lecteur a changé ou si un volume n’est plus monté, MINOS conserve l’identité enregistrée mais ne peut plus découvrir/lire le projet.

Réenregistrer le bon projet ou rétablir exactement la racine attendue.

## `index` échoue avant de lancer le provider

Exécuter :

```powershell
minos.cmd index <project> --dry-run --format json
minos.cmd doctor --format json
minos.cmd tools list --format json
```

Causes typiques :

- aucun provider qualifié pour le langage/build détecté ;
- runtime provider non installé ;
- `JAVA_HOME` absent ou ne contenant pas `javac` pour `scip-java` ;
- `node`/`npm` absents pour `scip-typescript` hors Windows (sous Windows, Node.js est livré dans la distribution complète) ;
- configuration projet non supportée par la qualification courante.

Installer un provider :

```powershell
minos.cmd tools install scip-java
# ou
minos.cmd tools install scip-typescript
```

## `scip-java` est `BLOCKED`

`scip-java` utilise le JDK du projet, pas le runtime Java embarqué de MINOS. Sur Windows, le poste doit fournir, en plus des outils livrés par MINOS (Coursier, Maven, classpath de scip-java) : un **JDK complet** (`JAVA_HOME` avec `javac.exe` et `jar.exe`), **Git for Windows** (`Git\bin\bash.exe`), **Windows PowerShell 5.1** et `csc.exe` (livrés par Windows). Chaque manque est nommé par `minos.cmd doctor` avec le préfixe `machine prerequisite (not shipped by MINOS)`.

Vérifier :

```powershell
$env:JAVA_HOME
& "$env:JAVA_HOME\bin\javac.exe" -version
```

Puis relancer :

```powershell
minos.cmd doctor
minos.cmd index <project> --dry-run
```

## Windows : « cannot grant AppContainer read access … without administrator privileges »

**MINOS n'exige jamais de droits administrateur pour indexer** : ni pour installer, ni pour lancer, ni pour utiliser le MCP natif, ni pour indexer avec `scip-java` ou un autre provider — y compris en développement, lancé depuis IntelliJ avec un JDK installé sous `Program Files`.

La sandbox Windows (AppContainer + Job Object) accorde un accès lecture temporaire à chaque racine dont un provider a réellement besoin (runtime managé MINOS, `JAVA_HOME` du projet, etc.), puis le révoque à la fin de l'exécution. Cet octroi mute la DACL de la ressource ; un utilisateur standard ne peut le faire que sur une ressource qu'il possède (son propre profil, `%LOCALAPPDATA%`, un répertoire MINOS géré). Il ne peut jamais muter la DACL d'un objet appartenant à `TrustedInstaller`/`Administrators`, ce qui inclut la plupart des sous-répertoires de `Program Files` et de `%SystemRoot%`, avec ou sans élévation.

Si `JAVA_HOME` (ou un autre répertoire d'outillage : `DOTNET_ROOT`, `CARGO_HOME`, `RUSTUP_HOME`, `COURSIER_CACHE`) pointe vers un JDK/toolchain installé sous `Program Files`, l'indexation échoue proprement avec ce message plutôt que de demander une élévation ou de contourner la sandbox. C'est un refus volontaire (« fail-closed ») : MINOS ne modifiera jamais un répertoire système ou appartenant à un autre compte pour continuer.

Correction : installer/pointer `JAVA_HOME` (ou la variable concernée) vers une toolchain possédée par l'utilisateur courant, par exemple une JDK installée sous `%LOCALAPPDATA%` (beaucoup de gestionnaires de JDK — SDKMAN pour Windows, Coursier `cs java`, une extraction manuelle d'archive — installent déjà à cet emplacement) plutôt que sous `Program Files`.

Le périmètre M14 initial qualifie le provider Java sur Maven. Un projet hors de ce périmètre doit rester explicitement non couvert plutôt que recevoir une fausse garantie.

## Windows : « private storage is write-protected by an explicit deny entry »

MINOS ne retire jamais un refus (ACE `DENY`) posé par un administrateur sur `MINOS_HOME` ou sur un de ses sous-répertoires : si ce
refus l'empêche d'écrire, la commande échoue en le disant, sans chemin dans le message. Un `MINOS_HOME` protégé en écriture fait
aujourd'hui échouer **même les commandes de lecture** (`project list`, `doctor`…) : une commande de lecture ouvre encore le stockage en
écriture (suivi sous la référence R12, non fermée). Pour lever la protection, retirez le refus vous-même :
`icacls "<MINOS_HOME>" /remove:d <compte> /T` (un refus hérité d'un parent est devenu explicite sur les objets que MINOS a durcis,
d'où `/T`).

## Windows : « MINOS AppContainer recovery journal … has no provable owner »

Le lanceur AppContainer note, dans `MINOS_HOME\sandbox\appcontainer-recovery-v2\`, le profil et les droits temporaires de chaque
sandbox en cours (un fichier `Minos.Worker.<guid>.json` et son verrou `Minos.Worker.<guid>.lock`). Le verrou est tenu par le lanceur
pendant toute la vie du sandbox ; Windows le libère à la mort du processus, quelle qu'en soit la cause. Au démarrage, un lanceur ne
récupère donc que les sandbox dont le verrou est libre (propriétaire mort) : il retire leurs droits, supprime leur profil, puis le
journal. Un sandbox **vivant** d'un autre processus MINOS n'est jamais touché, ni par un second `index`, ni par `doctor` ou par une
commande de statut de provider.

L'avertissement signale un journal que MINOS **laisse en place** parce que la mort de son propriétaire n'est pas prouvée : verrou
absent, verrou illisible ou erreur d'entrée-sortie. MINOS ne devine jamais (ni horloge, ni identifiant de processus) : sans preuve, il
ne récupère rien. Si vous savez qu'aucun MINOS ne tourne, supprimez à la main le journal et son profil :

```powershell
Get-ChildItem "$env:MINOS_HOME\sandbox\appcontainer-recovery-v2" | Where-Object Name -like 'Minos.Worker.*'
```

Les journaux d'un MINOS **antérieur** (`MINOS_HOME\sandbox\appcontainer-recovery\`) ne sont plus balayés ni examinés par le nouveau
lanceur : un ancien lanceur encore actif sur le même `MINOS_HOME` détruirait sinon les sandbox du nouveau format. Les profils
AppContainer et les droits qu'ils auraient laissés restent en place jusqu'à suppression manuelle : `icacls "<racine>" /remove:g
"*<SID>"` pour chaque chemin du journal, puis suppression du journal. Ils sont peu nombreux : l'ancien lanceur nettoyait tout à
chaque démarrage.

## Espace disque dans `MINOS_HOME`

Une copie de projet (`local-provider-workspaces/`), un temporaire de clonage distant (`remote-cache/repositories/.entry-*.tmp`) ou un
répertoire d'extraction (`distributed-artifacts/.accept-*`, indexation distribuée, code dormant selon l'ADR 0041) d'un run **tué** (arrêt brutal, plantage, coupure) n'est jamais supprimé
par ce run. MINOS en borne la vie : au moment où il crée un nouveau résidu du même genre, il supprime ceux dont la dernière
modification remonte à plus de **24 heures**.

- Jamais un run vivant ou récent, ni le répertoire du run courant, ni un résidu daté dans le futur (saut d'horloge).
- Jamais une entrée valide du cache de dépôts ni une entrée vérifiée du cache d'artefacts.
- Jamais un lien suivi : un lien ou une jonction ancien est supprimé lui-même, jamais sa cible.
- Au plus 16 résidus par déclenchement : un `MINOS_HOME` très encombré se vide en plusieurs runs.
- Jamais une cause d'échec : un résidu qui refuse la suppression (droits, verrou de fichier, antivirus) est signalé par un
  avertissement sans chemin et laissé en place ; l'opération qui l'a déclenché réussit.

Si l'espace presse avant le prochain run, supprimez à la main `local-provider-workspaces/` quand aucune indexation n'est en cours.

## `index` échoue sur un répertoire illisible

Un répertoire de votre projet que MINOS ne peut pas ouvrir (un volume Docker `pgdata/` en 0700 appartenant à `root`, une ACL Windows qui refuse le listage) faisait échouer à lui seul la découverte, l'empreinte et la copie de travail du provider, donc tout `index`. Désormais :

- un répertoire illisible **ignoré** (`.gitignore` ou `.minosignore`) ou **durci** (`.git`, `.idea`, `.minos`, `node_modules`, `target`, `dist`, `out`) est écarté avec un avertissement, par exemple `MINOS discovery could not read the project file 'pgdata' and treats it as absent: AccessDeniedException` (nom relatif au projet, jamais le chemin absolu ; dix avertissements au plus par opération). Il ne contribue ni à l'index ni à l'empreinte ;
- un répertoire illisible **non ignoré** continue de faire échouer l'opération : un répertoire de sources que MINOS ne peut pas lire ne doit pas devenir un index silencieusement incomplet. Le message nomme le répertoire relatif et la sortie : `data/db: cannot be read and is not ignored; add it to .minosignore (or fix its permissions) so that MINOS skips it` ;
- la racine du projet, ou la racine d'un scope, illisible échoue toujours.

Pour qu'un répertoire illisible soit écarté, ajoutez-le à `.minosignore` à la racine du projet (ou changez ses droits) :

```bash
echo 'data/db/' >> .minosignore
```

```powershell
Add-Content -Path .minosignore -Value 'data/db/'
```

Un `.gitignore` ou un `.minosignore` enregistré avec un BOM UTF-8 (Windows PowerShell 5.1 `Out-File -Encoding utf8`) est lu normalement : le BOM est retiré, la première règle s'applique. La casse des motifs sous NTFS et le comptage des liens non suivis dans un diagnostic `NO_CHANGES` ne sont pas traités : voir les questions ouvertes de `openspec/changes/tolerer-repertoires-illisibles-a-la-decouverte/design.md`.

## Windows : `minos.properties` ou un fichier de secret enregistré avec un BOM

Windows PowerShell 5.1 (`Out-File -Encoding utf8`) et d'anciens éditeurs enregistrent un fichier UTF-8 avec un BOM (octets `EF BB BF`) en tête. MINOS le retire, **une seule fois**, à la lecture de `config/minos.properties`, des autres fichiers de propriétés que MINOS lit (registre, état d'index…) et des fichiers de secret lus par `MinosRuntimeSettings` (par exemple le fichier de mot de passe PostgreSQL désigné par `minos.postgres.passwordFile`) : la première propriété est lue sous son vrai nom et le secret ne contient pas le BOM. Un fichier qui commence par **deux** BOM est refusé avec le message `starts with a repeated UTF-8 byte order mark` : réenregistrez-le en UTF-8 sans BOM. Un BOM ailleurs qu'en tête reste une donnée. Un fichier UTF-16 (autre encodage proposé par ces outils) n'est pas du UTF-8 valide et reste refusé.

## Windows : le bac à sable est indisponible, `minos-launchers` refusé

Les scripts des lanceurs du bac à sable vivent sous `%LOCALAPPDATA%\minos-launchers\<sha256>\` et sont contrôlés avant chaque
lancement. Quand un contrôle échoue, MINOS **refuse** : le backend est déclaré indisponible (avertissement dans le journal, aucun repli
vers un bac à sable moins isolant) et `doctor` le montre. Les causes, par message :

- `another principal can replace what is under its directory` : un autre compte que vous, SYSTEM ou Administrateurs peut supprimer
  un enfant de `%LOCALAPPDATA%`, réécrire sa DACL ou en prendre possession (une ACE accordée à « Tout le monde », à « Utilisateurs »
  ou à un groupe tiers). Vérifiez `icacls "%LOCALAPPDATA%"` et retirez la ACE.
- `its directory is owned by another principal` : le propriétaire de `%LOCALAPPDATA%` n'est ni vous, ni SYSTEM, ni Administrateurs.
- `reached through a link or a reparse point` : un ancêtre du répertoire est une jonction ou un lien (profil redirigé derrière une
  jonction). MINOS ne suit pas ce chemin ; placez `%LOCALAPPDATA%` sur un vrai répertoire.
- `owned by another principal` (racine, répertoire ou fichier) ou `integrity check failed` : le contenu ou le propriétaire de
  `minos-launchers` n'est pas celui que MINOS a créé. Cas fréquent : une session **élevée** après une racine créée non élevée (ou
  l'inverse) : le propriétaire diffère. Supprimez `%LOCALAPPDATA%\minos-launchers` depuis la session qui ne la reconnaît pas ; MINOS la
  recrée.

Le désinstalleur ne supprime pas `%LOCALAPPDATA%\minos-launchers` (quelques dizaines de Ko par version du script) : supprimez-le à la
main si vous désinstallez MINOS.

## `scip-typescript` est `BLOCKED`

Vérifier :

```powershell
node --version
npm --version
```

MINOS installe le provider, **pas les dépendances métier du projet**.

Si `node_modules` ou les dépendances nécessaires au projet sont absentes, les préparer selon le workflow normal du projet avant `minos index`.

## Provider installé mais indexation échoue

Chaque run conserve ses diagnostics sous :

```text
<MINOS_HOME>/runs/<runId>/<provider>/
```

Consulter :

```text
provider.stdout.log
provider.stderr.log
process.txt
failed-index.scip   # uniquement s’il existe
```

Le message CLI indique le `runId` ou le fichier de log lorsque cela est disponible.

## Un `index.scip` existant dans le projet a disparu

Le runtime M14 est conçu pour préserver/restaurer un `index.scip` préexistant autour de l’exécution provider.

Si ce contrat semble violé, ne relancer pas plusieurs indexations en parallèle. Conserver le répertoire `<MINOS_HOME>/runs/<runId>` et signaler le SHA exact de MINOS.

## Import manuel d’un artefact SCIP

Pour diagnostiquer un artefact externe sans lancer le provider :

```powershell
minos.cmd import-scip <project> `
  --file <index.scip> `
  --provider <provider-id> `
  --format json
```

La forme historique `index --scip` reste temporairement acceptée mais est dépréciée.

## `NO_CHANGES`

Ce résultat signifie que le fingerprint courant correspond à la baseline active et que le planner M7 a choisi `NONE`.

Pour forcer une requalification :

```powershell
minos.cmd index <project> --force-full
```

## Pourquoi MINOS fait `FULL` pour une petite modification ?

C’est volontaire lorsque le provider sélectionné ne possède pas une capacité `INCREMENTAL_INDEXING` explicitement qualifiée.

M14 n’invente pas un incrémental que le fournisseur ne prouve pas.

## `STALE`

`STALE` signifie qu’un refresh a échoué mais qu’un ancien snapshot actif reste disponible :

```mermaid
stateDiagram-v2
    READY --> REFRESHING
    REFRESHING --> READY: nouveau snapshot promu
    REFRESHING --> STALE: échec
    STALE --> REFRESHING: nouvelle tentative
```

Les requêtes peuvent continuer à lire l’ancien snapshot. Corriger la cause provider/build puis relancer `index`.

## `FAILED`

`FAILED` signifie qu’aucun snapshot actif utilisable n’existe après un échec initial.

Corriger `doctor`/provider/build puis relancer l’indexation.

## Le workspace a changé pendant l’indexation

MINOS compare un fingerprint avant/après le run.

Si le workspace change pendant l’exécution, le fingerprint baseline n’est pas promu. Le prochain `index` replanifiera conservativement le projet.

## Pas de résultat dans `find-callers` / `find-callees`

Une liste vide signifie qu’aucune relation `CALLS` correspondante n’est présente dans le snapshot observé. Cela ne prouve pas une absence runtime.

Limites possibles : dispatch dynamique, réflexion, configuration runtime ou capacités incomplètes du provider.

## `impact` retourne des limitations

Normal : l’impact est volontairement conservateur et décrit une estimation du graphe observé, pas une preuve d’exhaustivité runtime.

## MCP natif ne démarre pas

Installation :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd mcp
```

Dans une configuration MCP, utiliser le launcher directement :

```text
command = <installation>\minos.cmd
args    = mcp
```

Le processus MCP utilise stdout pour le protocole ; ne pas insérer de wrapper qui écrit du texte arbitraire sur stdout.

## Docker MCP ne démarre pas

Le mode Docker est optionnel et séparé du runtime natif.

Vérifier :

```powershell
docker version
.\docker\scripts\prod-mcp-release.ps1 -Action Status
.\docker\scripts\prod-mcp-release.ps1 -Action Validate
```

Le home Docker est distinct du home natif afin de ne pas mélanger des chemins `N:\...` avec `/workspace/projects/...`.

## MCP : erreur de schéma

Les schemas rejettent les clés inconnues et valeurs hors bornes. Voir [mcp.md](mcp.md).

## `nexus-export` échoue

Préconditions : projet enregistré, snapshot actif et racine réelle accessible.

```powershell
minos.cmd inspect <project>
minos.cmd index-status <project>
minos.cmd nexus-export --root <root> > export.json
```

Si aucun snapshot n’existe encore :

```powershell
minos.cmd index <project>
```

## `project list` sort avec le code 3

Diagnostic :

```powershell
minos.cmd project list --format json
```

Le code 3 veut dire « inventaire partiel » : les projets lisibles sont listés, et une ou plusieurs entrées sont abîmées. Elles figurent dans la liste à l'état `UNREADABLE` et dans `degraded` (identifiant d'entrée et raison, sans chemin). Le fichier concerné est `registry/projects/<identifiant>.properties` du `MINOS_HOME`, ou `cli-index-history/<identifiant>.properties` pour un historique.

Correction : réparer ou supprimer l'entrée nommée (un projet supprimé du registre se réenregistre avec `project add`), puis relancer `project list` : le code 0 confirme que l'inventaire est complet.

## `inspect <nom>` ou `index-status <nom>` sort avec le code 3

Le registre contient une entrée illisible et le projet est désigné **par son nom** : MINOS ne peut pas prouver que ce nom est unique, ni qu'il n'existe pas.

- Si le projet est affiché, `warning: N registry entries are unreadable, so this name cannot be proven unique` : la réponse est valide, le code dit qu'elle est incomplète.
- Si rien n'est affiché, `N registry entries are unreadable, so it cannot be told whether this project exists` : ce n'est pas « projet inexistant » (`unknown project`, code 1).
- `unknown project (the reference is not shown); pass a registered project name or UUID` : la référence passée ressemblait à un chemin (`C:\…`, `/home/…`) ou à un secret, donc elle n'est pas répétée. Une commande de projet attend le **nom** enregistré ou l'**UUID**, jamais le chemin : `minos project list` donne les deux. Un simple nom inconnu est répété tel quel (`unknown project: demo`).

Diagnostic : `minos.cmd project list --format json` nomme les entrées illisibles (`degraded`). Contournement immédiat : désigner le projet par son identifiant (UUID), qui ne lit que son entrée. Correction : réparer ou supprimer l'entrée nommée.

## Changer temporairement de home

```powershell
$env:MINOS_HOME = 'N:\temp\minos-home'
minos.cmd project list
```

Depuis un checkout source, la propriété JVM reste prioritaire :

```powershell
java -Dminos.home=N:\temp\minos-home -jar <minos-all.jar> project list
```

## Réinitialiser un environnement de test

Utiliser de préférence un nouveau `MINOS_HOME` vide. Ne supprimer pas arbitrairement des fichiers dans un home partagé contenant des snapshots utiles.

## Collecter un diagnostic reproductible

Pour un checkout source :

```text
git rev-parse HEAD
java -version
.\mvnw.cmd -version
```

Toujours fournir :

```text
MINOS --version
commande exacte
code de sortie
stdout
stderr
MINOS_HOME
minos doctor --format json
minos index <project> --dry-run --format json
runId
logs du run provider
```

Pour un problème de release, ajouter le nom du ZIP et son SHA-256.
