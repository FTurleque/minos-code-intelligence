# Design

## Context

Sprint 2 de l'audit du 10 octobre 2026 : **AUD-SEC-01**. Le HEAD analysé est `465e970d` (`develop`). Le chemin concerné : CLI `git-activity` (`GitActivityCommand`) ou API Java (`LocalMinosMultiRepositoryApi.inspectGit` / `analyzeGitActivity`) → `GitIntelligenceService.inspect` / `analyze` → `open(projectRoot)` → `repositoryView(...)`.

## État vérifié au HEAD

### AUD-SEC-01 — confirmé, preuve complétée sur la version réellement embarquée

- `GitIntelligenceService.java:178-192` : `open` fait `toRealPath()` sur la racine puis `new FileRepositoryBuilder().findGitDir(root.toFile())`, **sans** `setMustExist`, `addCeilingDirectory`, ni contrôle de propriétaire. L'environnement Git (`GIT_DIR`, `GIT_WORK_TREE`, `GIT_CEILING_DIRECTORIES`) est volontairement ignoré (test `theGitEnvironmentCannotRedirectTheAnalysedRepository`) : `GIT_CEILING_DIRECTORIES` ne peut donc pas servir de plafond.
- `GitIntelligenceService.java:207` : `Status status = repository.isBare() ? null : git.status().call();` — dans `repositoryView`, atteint par `inspect` **et** par `analyze`.
- **JGit 7.8.0** (`pom.xml:64`, `7.8.0.202609011348-r`). L'audit n'avait relu que le jar 5.13.3 et une copie amont ; les sources 7.8.0 du dépôt Maven local (`org.eclipse.jgit-7.8.0.202609011348-r-sources.jar`, 971 fichiers) montrent :
  - `WorkingTreeIterator.java:1433` `getCleanFilterCommand()` lit `state.walk.getFilterCommand("clean")` ; `:483-496` : si la commande n'est pas un filtre intégré enregistré (`FilterCommandRegistry.isRegistered`), `fs.runInShell(filterCommand, …)` est lancé ;
  - `TreeWalk.java` `getFilterCommand` : l'attribut `filter=<pilote>` (fichiers `.gitattributes`, `info/attributes`) désigne un pilote dont la commande vient de `config.getString("filter", <pilote>, "clean")`, `config` étant la configuration du dépôt, **fichiers inclus compris** (`include.path`) ;
  - `FS_POSIX.runInShell` = `sh -c <cmd>` ; `FS_Win32.runInShell` = `cmd.exe /c <cmd>` ;
  - `grep -rli "safe.directory" org` sur les 971 sources : **aucune occurrence**. JGit 7.8.0 n'a aucune notion de répertoire sûr ni de propriétaire.
- Appelants confirmés : `GitActivityCommand` (CLI), `LocalMinosMultiRepositoryApi.java:192` (`inspectGit`) et `:203` (`analyzeGitActivity`), plugin IntelliJ via `minos git-activity`.
- Aucun test existant ne couvre un projet situé **sous** la racine du dépôt (la remontée n'est ni testée ni documentée : `docs/developer/multi-repo-git.md` n'en parle pas).
- **Non reproduit ici** : l'exécution du témoin n'a pas été rejouée sur le poste (le constat l'établit par la lecture du code ; la tâche 1.1 en fait le premier test rouge). La lecture des sources 7.8.0 lève la réserve de l'audit sur la version.

### Écarts avec l'action proposée par l'audit

- L'audit propose `setGitDir(root.resolve(".git"))` avec `setMustExist(true)` « ou » `addCeilingDirectory(root.getParent())` et de refuser un gitdir hors de la racine. Cela **supprime la remontée** : un projet enregistré dans un sous-dossier d'un monorepo (`~/mono/service-a`) ne serait plus analysable, ni un projet dont le `.git` est un fichier pointant vers `…/.git/worktrees/x` (worktree, sous-module) hors de la racine. Voir D1.
- L'audit propose de « retirer les sections `filter` de la configuration en mémoire » : retenu, mais **uniquement pour la configuration du dépôt** (D3), car celle de l'utilisateur est de confiance et c'est elle qui porte `git-lfs`.
- Un refus fondé sur le propriétaire seul **ne protège pas** le cas « dépôt reçu en archive avec son `.git` » (extrait par l'utilisateur, donc à lui) : c'est le retrait des filtres qui le ferme. Les deux mesures sont donc nécessaires et se complètent.

## Goals / Non-Goals

**Goals** : aucune commande déclarée par un dépôt ne s'exécute pendant `git-activity` ; un dépôt appartenant à un autre compte n'est pas analysé ; le résultat dit ce qu'il n'a pas pu faire ; la remontée vers un dépôt parent légitime continue de fonctionner.

**Non-Goals** : plafonner ou interdire la remontée (D1) ; exécuter les filtres de l'utilisateur dans un bac à sable ; couvrir `JGitRemoteRepositoryMaterializer` ; ajouter une option de configuration.

## Decisions

### D1. Garder la remontée, la rendre visible et lui imposer le propriétaire — **décision en attente du propriétaire**

| Option | Effet | Coût |
|---|---|---|
| **A (recommandée)** | La remontée reste ; le dépôt trouvé doit avoir le bon propriétaire (D2) ; si le dépôt n'est pas à la racine du projet, la limitation `REPOSITORY_ABOVE_PROJECT_ROOT` le dit | Un dépôt personnel `~/.git` (dotfiles) est encore remonté, mais signalé |
| B | Plafond au dossier personnel de l'utilisateur quand le projet y est | Évite le `~/.git` ; comportement différent selon l'emplacement du projet |
| C | Pas de remontée (`setMustExist` sur `root/.git`) | Casse monorepos, worktrees, sous-modules ; l'audit la propose mais n'a pas mesuré cet usage |

Recommandation : **A**. Le danger exécutable est fermé par D2 et D3 quelle que soit l'option ; B et C ne servent qu'à « analyser le bon dépôt ». Si le propriétaire choisit B ou C, seule la tâche 2.3 change.

### D2. Le propriétaire est comparé à celui d'un fichier que le processus crée

`Files.getOwner(gitDir)` est comparé à `Files.getOwner(sonde)`, la sonde étant un fichier temporaire créé par MINOS dans son répertoire privé (le même mécanisme que `PrivateLocalStorage`). Raisons :

- Comparer au nom `user.name` échoue pour les comptes de domaine Windows (nom qualifié) et refuserait des utilisateurs légitimes.
- Un processus **élevé** sous Windows crée des fichiers dont le propriétaire est `BUILTIN\Administrators` : la comparaison à la sonde donne alors le propriétaire que le système attribuera aux fichiers de ce processus, et reste cohérente sur les runners de CI (qui s'exécutent élevés).
- Le seam de test est un `Supplier<UserPrincipal>` du propriétaire attendu, injecté dans le constructeur package-private ; on ne peut pas créer un dépôt appartenant à un autre compte en CI sans privilège.

Limite assumée, à documenter : un dépôt créé par un compte élevé puis analysé par un processus non élevé (ou l'inverse) est refusé ; le message dit de corriger le propriétaire. C'est le comportement de git (« dubious ownership »). Le refus est **fail-closed** : si le propriétaire ne peut pas être lu (système de fichiers sans notion de propriétaire), l'analyse échoue avec un message distinct.

### D3. Retirer de la configuration en mémoire les filtres du dépôt — **décision en attente du propriétaire sur le périmètre**

Avant `git.status()`, pour chaque sous-section de `filter` présente dans la configuration **du dépôt** (`FileBasedConfig` du dépôt, fichiers inclus fusionnés), `unsetSection("filter", sous-section)` est appliqué **sans** `save()`. Le fichier n'est jamais modifié ; l'objet `Repository` est celui du seul appel.

| Option | Effet |
|---|---|
| **A (recommandée)** | Seuls les filtres du dépôt sont retirés ; ceux de `~/.gitconfig` et du système restent (c'est le modèle de confiance de git : seul le dépôt est non fiable) |
| B | Tous les niveaux sont neutralisés | 
| C | Aucun retrait, seulement le propriétaire : n'a aucun effet sur un `.git` livré dans une archive |

Conséquence de A, à accepter : un dépôt qui déclare **lui-même** `filter.lfs.*` dans son `.git/config` (`git lfs install --local`) verra ses fichiers suivis par LFS compter comme modifiés si leur date est incertaine : `clean` peut être `false` à tort. D'où la limitation nommée `REPOSITORY_FILTERS_NOT_APPLIED` (D4), émise **seulement** quand au moins un filtre a été retiré. Le cas courant (LFS installé globalement) n'est pas touché.

Le mécanisme exact (retrait en mémoire, ou sous-classe de `FS` dont `runInShell` refuse, ou `FilterCommandRegistry`) est tranché par le test rouge de la tâche 1.1 : celui qui laisse le témoin **non créé** dans les quatre cas (filtre dans `.git/config`, dans un fichier inclus, attribut dans `.gitattributes`, attribut dans `info/attributes`) et que le résultat `clean` reste calculé. Le retrait en mémoire est le premier essai ; la sous-classe de `FS` est le repli si l'`include` résiste.

### D4. Deux limitations additives, pas de champ nouveau

`RepositoryView.limitations` est déjà une liste de chaînes (`NO_ORIGIN_REMOTE`, `DETACHED_HEAD`, `SHALLOW_HISTORY`, `UNBORN_HEAD`…, 11 valeurs distinctes dans `GitIntelligenceService`, documentées dans `docs/developer/multi-repo-git.md` à partir de la ligne 179). Sont ajoutées `REPOSITORY_FILTERS_NOT_APPLIED` et `REPOSITORY_ABOVE_PROJECT_ROOT`. Aucun schéma n'énumère les valeurs (`grep` sur `*.json` et `docs/` : seules des occurrences de documentation et de tests) ; ni les goldens de caractérisation ni le contrat MCP ne les contiennent. La documentation `docs/developer/multi-repo-git.md` liste les valeurs : elle est mise à jour dans la même tâche.

### D5. Pas de nouvelle propriété de configuration ni de contournement

Aucun réglage `minos.git.trustForeignRepositories`. Un utilisateur qui veut analyser un dépôt d'un autre propriétaire corrige le propriétaire, comme avec git. Réintroduire un contournement relève d'une décision séparée.

## Risks / Trade-offs

- Faux positif de `clean` pour un dépôt à filtre local (D3-A) : signalé par la limitation, jamais silencieux.
- Refus à tort sous Windows élevé / non élevé (D2) : message actionnable, documenté ; le test de la sonde garde la cohérence sur la CI.
- La lecture du propriétaire d'un répertoire réseau (SMB) peut échouer : fail-closed avec message distinct, pas d'analyse.
- Les limitations nouvelles ne changent aucun code de sortie.

## Windows et Linux

Les deux plateformes sont couvertes par les mêmes tests unitaires. Le test du témoin utilise comme filtre une commande `java -cp … Witness <fichier>` (même schéma que `GitEnvironmentProbe` dans `GitIntelligenceServiceTest`), valable sous `sh -c` comme sous `cmd.exe /c`, avec guillemets adaptés. Aucun comportement propre à une plateforme n'est introduit ; seuls la lecture du propriétaire (SID / uid) et le shell des filtres diffèrent, et sont exercés par les deux jobs `Verify`.

## Qualification de la capacité

`analyse-git` : **qualifiée** après la tâche 4 (tests rouges puis verts sur les deux jobs `Verify`). Le retrait des filtres du dépôt est une capacité **partielle** documentée (D3-A, limitation `REPOSITORY_FILTERS_NOT_APPLIED`).

## Migration

Aucune donnée, aucun schéma, aucune configuration. Retour arrière : revert du commit.
