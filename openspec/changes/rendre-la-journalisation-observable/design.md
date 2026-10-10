# Design

## Context

Sprint 2 de l'audit du 10 octobre 2026 : **AUD-DEP-08**, **AUD-QUA-04**, **AUD-QUA-06**. HEAD analysé : `465e970d` (`develop`). Les trois constats ont des périmètres de code distincts (POM de `minos-app` ; plugin Gradle ; `minos-runtime-local` et un garde Python), donc trois lots de tâches fusionnables séparément, réunis pour l'objectif commun du sprint : « une erreur laisse une trace journalisée, sans fuite ».

## État vérifié au HEAD

### AUD-DEP-08 — confirmé, **action de l'audit à corriger**

- `minos-app/pom.xml:30` : `org.slf4j:slf4j-nop`, seule dépendance SLF4J à implémentation du module ; le jar ombré (`maven-shade-plugin`, `ServicesResourceTransformer` déjà présent) embarque donc `NOPServiceProvider`. `pom.xml` racine : `slf4j.version` = 2.0.20 et `dependencyManagement` gère `slf4j-api`, `slf4j-simple` et `slf4j-nop`. `minos-storage-postgresql` utilise `slf4j-simple` en `test` seulement.
- Le code de MINOS : 48 appels `LOGGER.log` (`System.Logger`) dans 24 fichiers de production ; aucun import `org.slf4j` en production. `System.Logger` est adossé à `java.util.logging` par défaut (commentaire de `minos-engine/src/test/java/com/minos/testsupport/LogCapture.java`).
- **Le sens de `slf4j-jdk-platform-logging`.** L'action de l'audit (« remplacer `slf4j-nop` par `slf4j-jdk-platform-logging` pour router SLF4J vers `System.Logger` ») ne correspond pas à ce module : les notes de version SLF4J 2.0.19 décrivent `SLF4JPlatformLogger.isLoggable()` qui convertit les `System.Logger.Level` en niveaux SLF4J, c'est-à-dire un `System.Logger` **adossé à SLF4J** (appels `System.Logger` → SLF4J). Le sens utile ici (appels SLF4J → journal du JDK) est celui de **`slf4j-jdk14`**. Conséquences d'une application à la lettre de l'audit : aucun fournisseur SLF4J dans le jar (SLF4J retombe sur NOP avec l'avertissement « No SLF4J providers were found » sur stderr) et, si `System.Logger` était redirigé vers SLF4J, les journaux de MINOS tomberaient eux aussi dans ce vide. **Preuve à la tâche 1.1** (lire le contenu du jar par `javap`/liste de `META-INF/services` avant de toucher au POM) : la source consultée est la page de nouveautés du site SLF4J, pas le jar, que le poste n'a pas dans son dépôt Maven local (`slf4j-jdk-platform-logging` et `slf4j-jdk14` absents de `~/.m2`).
- Les bibliothèques concernées : SDK MCP 2.0.1 et JGit 7.8.0 (SLF4J) ; leurs erreurs sont aujourd'hui perdues.

### AUD-QUA-04 — confirmé, quatre sites et non trois

- `grep -rn "catch (Throwable" minos-intellij/src/main/java` → **4** occurrences : `MinosEditorActions.java:69` (corps de la tâche) et `:83` (dans `onSuccess`, autour de `present`), `MinosM21Actions.java:204`, `MinosToolWindowPanel.java:280`. Les trois `Task.Backgroundable` sont `MinosEditorActions.java:53`, `MinosM21Actions.java:193`, `MinosToolWindowPanel.java:269`.
- `grep -rn "Logger\|thisLogger" minos-intellij/src/main/java` → aucune occurrence. `ProcessCanceledException` est déjà relancée avant le `catch (Throwable)` (donc l'annulation est correcte) ; ce qui est avalé : les `Error` et toute exception sans trace.
- Affichage : `MinosToolWindowPanel.java:140-141` (`failure.getMessage() == null ? "Unknown failure" : …`), appelé par `MinosUiController.showError`.
- Réserve sur la preuve : l'audit compte « 8 fenêtres dupliquées entre `MinosEditorActions:31-40` et `MinosM21Actions:81-90` » ; la comparaison directe de ces lignes montre dix lignes identiques (la classe interne `SymbolAction` et sa méthode `update`), mais ce n'est pas la tâche d'arrière-plan elle-même, qui est dupliquée avec des variantes (`MinosEditorActions` a deux champs de plus). La duplication utile à traiter est celle du bloc `run`/`onSuccess`.
- Cadre de test du plugin : JUnit 6.1.3 simple (`useJUnitPlatform`), sans fixture de plateforme ; les classes testées aujourd'hui ne dépendent pas de l'IDE (`MinosCliClientTest`, etc.).

### AUD-QUA-06 — confirmé, **inventaire plus large que l'audit**

Relevé complet des appels `LOGGER.log(…)` qui passent la cause ou `getMessage()` :

| Site | Chemin absolu concaténé | Cause passée | Nature |
|---|---|---|---|
| `RunDirectoryRetention.java:180` | oui (`target`) | oui | récupération (cité) |
| `RunDirectoryRetention.java:201-202` | oui (`child`) | oui | récupération (cité : `:201`) |
| `RunDirectoryRetention.java:206` | non | oui | récupération (non cité) |
| `ProviderResidueReclamation.java:62-63` | oui (`directory`) | oui | récupération (cité) |
| `ProviderResidueReclamation.java:80-81` | oui (`normalized`) | oui | récupération (**non cité**) |
| `LinuxBubblewrapWorkerSandboxBackend.java:302-304` | non | `getMessage()` | diagnostic de bac à sable |
| `LinuxBubblewrapWorkerSandboxBackend.java:334` | non | oui | diagnostic de bac à sable |
| `WindowsJobObjectProcessOwnership.java:50-51` | non | oui | diagnostic (commentaire : « the real cause must still be observable ») |
| `WindowsAppContainerWorkerSandboxBackend.java:119-121`, `:387-388` | non | oui | diagnostic |
| `IndexingResumePlanner.java:143-145` | non | `refusal.getMessage()` | refus composé par MINOS |

- Régime inverse dans les mêmes modules : `RunDirectoryRetention.java:327-328` (classe seule), `ProcessIdentity.java:47` (« without the cause: it may carry a path »), `ProjectIgnorePolicy.java:76-79` (chemin relatif + classe), `IndexingRunExecutor.java:114-115` (classe seule), `ProviderWorkspaceFiles.java:117` (relatif portable + classe), `FileIndexStateStore.java:484` (nom de fichier + classe). Un **troisième** régime : `LinuxCgroupJob.describeFailure` / `redactPaths` (réécriture relative à la racine de cgroup), et `MinosApiSupport.logFailure` (structure seule, sans cause).
- Règle écrite : `docs/audit/PROMPT-FIABILITE.md:127` parle de messages « passés par `PublicErrorMessages` et sans chemin absolu » ; l'audit étend l'exigence au journal. Le serveur MCP STDIO journalise sur stderr, capturé par les clients.
- Conséquence de l'inventaire : l'action « aligner les trois sites et interdire de concaténer un `Path` absolu dans `LOGGER.log` » est **insuffisante** (cinq sites de récupération, dont un non cité, passent la cause qui peut porter un chemin : `AccessDeniedException.getMessage()` est le chemin) et une interdiction **générale** de la cause contredirait la décision délibérée des diagnostics de bac à sable (la cause est voulue pour observer la dégradation). D3 en tire la forme du garde.

## Goals / Non-Goals

**Goals** : les erreurs des bibliothèques atteignent stderr ; aucune tâche du plugin n'avale d'exception sans trace ; les messages de récupération de fichiers ne contiennent ni chemin absolu ni cause ; un nouveau journal fautif échoue en CI.

**Non-Goals** : unifier les régimes en un utilitaire commun ; reconfigurer les niveaux ; journaliser sur stdout (réservé au MCP STDIO) ; rendre les diagnostics de bac à sable sans chemin.

## Decisions

### D1. `slf4j-jdk14` à la place de `slf4j-nop`

Le fournisseur SLF4J du jar ombré devient `org.slf4j:slf4j-jdk14` (même version, `dependencyManagement` à compléter d'une ligne) : les appels SLF4J des bibliothèques passent par `java.util.logging`, le même backend que `System.Logger`, configurable par `-Djava.util.logging.config.file` et par `JAVA_TOOL_OPTIONS`. Le `ConsoleHandler` par défaut écrit sur **stderr** ; stdout reste réservé au protocole MCP.

Alternative écartée : `slf4j-simple` (déjà géré, écrit sur stderr par défaut) donnerait un second régime de configuration (propriétés `org.slf4j.simpleLogger.*`) à côté de JUL. Alternative écartée : conserver `slf4j-nop` et documenter : laisse le diagnostic d'incident impossible.

Risques à mesurer (tâche 1.3) et non à présumer :
- **Bruit** : le niveau par défaut de JUL est `INFO`. Le SDK MCP et JGit journalisent-ils à `INFO` en régime normal ? La sortie stderr d'un échange MCP complet et d'un `git-activity` est relevée ; si le bruit est notable, un `logging.properties` embarqué fixe `org.eclipse.jgit` et `io.modelcontextprotocol` à `WARNING`.
- **Fuite** : JGit ou le SDK peuvent écrire un URI avec identifiants dans un message d'erreur. Un clone d'une URL `https://user:secret@…` qui échoue est provoqué et sa sortie stderr est lue (`secret` absent exigé) ; sinon, le niveau est relevé ou le message filtré avant de fusionner.
- **Runtime jpackage** : le module `java.logging` doit rester dans les racines calculées par `jdeps` (`Resolve-JdkModules`) ; vérifié par la liste imprimée par `build-windows-distribution.ps1` (`jpackage runtime roots (jdeps)`).

### D2. Une tâche d'arrière-plan unique pour le plugin — **détail du journal en attente du propriétaire**

Nouveau package feuille `com.minos.intellij.task` (il ne dépend ni de `ui` ni de `actions`, ce qui préserve la règle A8 « aucun cycle de packages », plugin compris), classe `MinosBackgroundTask`. Le comportement exigé : l'opération s'exécute dans `run` ; `ProcessCanceledException` est relancée ; **les `Error` sont relancées** (la plateforme les rapporte) ; toute autre `Exception` est journalisée avec `Logger.getInstance(MinosBackgroundTask.class)` (pile complète) puis transmise au rappel d'échec. `MinosToolWindowPanel.showError` utilise une fonction `describe(Throwable)` testable : message, ou `Unknown failure (<type>)` quand il manque ou est vide.

Niveau de journal : `warn` avec la pile (l'échec est déjà montré à l'utilisateur ; `error` déclencherait en plus le rapport d'erreur interne de l'IDE). Le message de l'exception figure dans `idea.log`, fichier local de l'utilisateur dont le support a besoin pour diagnostiquer. **Décision en attente (D2-a)** : le critère de sortie du sprint 2 dit « sans chemin absolu » pour les tâches du plugin aussi. Option A (recommandée) : pile et message complets dans `idea.log` (local, pas capturé par un client MCP). Option B : type et pile sans le message (diagnostic dégradé quand la cause est dans le message, par exemple une erreur de parsing). Si B est retenue, seule la phrase « avec son message » de la spec change.

Test : sans fixture de plateforme, `MinosBackgroundTask` reçoit sa « sortie de journal » et son rappel par constructeur (le `Logger` n'est pas moqué) ; `describe` est une fonction pure.

### D3. Le garde de journalisation : règle sans exception, règle à liste nominative

Dans `check-private-io.py` (déjà câblé dans `pr-ci.yml:99` et autotesté par `test_check_private_io.py`, avec la mécanique d'allowlist nominative en cliquet et le retrait des commentaires et littéraux), deux règles de plus sur les appels `LOGGER.log(…)` des sources de production de tous les modules sauf `minos-intellij` :

1. **`Path` concaténé** : si le fichier déclare `Path <nom>` (paramètre, variable ou variable de boucle) et que `<nom>` apparaît avec `+` ou `.toString()` dans les arguments d'un appel `LOGGER.log`, c'est un échec. **Aucune exception.** Limite assumée : le garde ne connaît pas les types ; un `Path` obtenu par un appel imbriqué n'est pas détecté.
2. **Cause ou `getMessage()` passé** : l'appel dont le dernier argument est un identifiant de `Throwable` ou dont les arguments contiennent `getMessage()` n'est accepté que pour les entrées de `private-io-allowlist.json` : (fichier, règle, maximum d'occurrences, justification d'au moins 20 caractères). Entrées initiales après correction : les six diagnostics et refus du tableau ci-dessus (`LinuxBubblewrapWorkerSandboxBackend` ×2, `WindowsJobObjectProcessOwnership`, `WindowsAppContainerWorkerSandboxBackend` ×2, `IndexingResumePlanner`).

Choix du lieu : étendre `check-private-io.py` plutôt qu'écrire un second script évite un nouveau câblage dans `invariants` (que `check-ci-wiring.py` et `check-single-execution.py` vérifient) ; le coût est un nom de script qui ne dit plus tout ce qu'il garde, atténué par la mise à jour de sa docstring et de `docs/developer/quality-gates.md`. **Décision en attente (D3-a)** : si le propriétaire préfère un script dédié (`check-log-hygiene.py`), les tâches 3.1 à 3.3 sont inchangées sauf le nom et le câblage (à ajouter à `invariants`, `required-checks.json` n'est pas concerné).

### D4. Régime des sites corrigés

Cinq sites de récupération : message `MINOS could not … '<chemin relatif à la racine gérée>': <classe>`, sans cause. Pour `RunDirectoryRetention` la racine gérée est `runsRoot` ; pour `ProviderResidueReclamation` c'est `root`. Le chemin relatif est obtenu par `relativize` après les contrôles de confinement déjà présents. Aucun nouvel utilitaire (le troisième régime, `describeFailure`, reste hors périmètre).

## Risks / Trade-offs

- Bruit ou fuite par les bibliothèques (D1) : mesurés avant fusion, jamais présumés.
- Le garde de D3 n'est pas un analyseur de types : documenté dans sa docstring ; l'auto-test fixe ce qu'il voit et ne voit pas.
- Les justifications d'allowlist sont une dette nommée : les diagnostics de bac à sable pourront alimenter un lot ultérieur d'utilitaire commun.

## Windows et Linux

JUL/stderr identique ; le test du jar ombré s'exécute sur les deux jobs `Verify` (`ShadedJar*IT`). Les chemins relatifs des journaux utilisent `Path.relativize` puis un séparateur portable (comme `ProviderWorkspaceFiles.portable`). Le plugin est qualifié sur Linux (build, Plugin Verifier) et Windows (tests de propriété de processus) par son workflow existant.

## Qualification de la capacité

`journalisation` : **qualifiée** quand les trois lots sont verts sur `Verify` ×2 et `IntelliJ plugin (gate)` ; le contrôle de niveau par l'utilisateur est **non supporté** (documenté).
