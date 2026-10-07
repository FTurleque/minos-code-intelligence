<!-- Annexe C de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants C-NN y valent MINOS-AUD-CNN dans docs/audit/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit C — Surfaces publiques versionnées (CLI, MCP, API Java, NEXUS, composition, client IntelliJ)

Base : `develop` @ bc1d3421. Lecture seule : aucun fichier du dépôt modifié, ni Maven, ni git d'écriture. Deux reproductions ont été faites **hors dépôt**, dans le scratchpad (`cmdtest/`, `pem/`) : compilation isolée de `PublicErrorMessages` (C-01) et rejeu du vrai script `windows-cli-job-owner-v1.ps1` (C-04). Le MCP `minos` n'a pas été utilisé. Les outils `mcp__jetbrains__*` n'étaient pas nécessaires (Grep/Read ont suffi).

Constats déjà clos ou connus et NON re-signalés (sauf précision) : Q1, Q2, Q11, Q19-Q24, S14, S18, S19, A11 ; S10 « `rootPath` absolu dans `minos_project_structure` » (`ProjectJson.java:32`) et « emails d'auteurs dans `git-activity` » (`GitActivityCommand.java:133`) restent ouverts tels quels ; R12 (lecture sur `MINOS_HOME` en lecture seule) reste ouvert. Q25/Q26 : sans objet ici.

Correction de périmètre : `scripts/quality/check-tools-manifest.py` ne vérifie PAS les outils MCP. Il garde le catalogue des outils embarqués (scip-java, node, maven…, `embedded-tools.json`). La dérive catalogue MCP vs doc est gardée par `scripts/docs/product-facts.py --check` (comptage 31 : code, `docs/user/mcp.md` et `product-facts.md` concordent). Les schémas et le comportement ne sont gardés par aucun script (voir C-11, C-13).

Sommaire : C-01 ... C-16.

---

### C-01 — `minos_index_status` / `minos_project_structure` : l'erreur est opaque (« MINOS tool execution failed ») y compris pour une référence de projet inconnue

- **Qualification** : DÉFAUT CONFIRMÉ (chemin de code + exécution isolée de `PublicErrorMessages`).
- **Priorité** : **P1**. C'est la cause la plus probable du symptôme observé en pratique avec un agent (les LLM passent très souvent le chemin du dépôt comme `project`), et la même défaillance touche CLI, API et MCP. L'outil « de diagnostic » ne diagnostique rien.
- **Preuves** :
  - `minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:156-174` (`execute` : seule `IllegalArgumentException` donne un message ; tout autre `Exception`, dont `IOException`, `SecurityException`, `IllegalStateException`, `UnreadableRegistryException`, renvoie `GENERIC_TOOL_ERROR`, ligne 47), `:204-220` (`clientError` : si `PublicErrorMessages.looksSensitive(detail)` → opaque), `:222-225` (le log ne contient que `type=<classe>`, jamais le message).
  - `minos-application/src/main/java/com/minos/application/ProjectResolver.java:21` (`ResolutionException extends IllegalArgumentException`) et `:124-126` (`"unknown project: " + reference` : on recopie la valeur fournie par l'appelant).
  - `minos-engine/src/main/java/com/minos/diagnostics/PublicErrorMessages.java:53-65` et `:74-87` : toute sous-chaîne `X:\` ou `/...` est classée « chemin sensible », ainsi que `api-key`, `secret`, `password`, `key=`, `token:` dans un simple nom de projet.
  - `MinosApplicationMcpBackend.java:93-101` (`indexStatus` → `projects.inspectProject`), `:285-296` (`hosted()` → `IllegalStateException("MINOS team mode is disabled")`, `hostedToken()` → `SecurityException("MINOS_TEAM_TOKEN is required…")` : messages sûrs, tous deux transformés en opaque).
  - `MinosMcpErrorRedactionTest.java:19-30` épingle volontairement « tout `IOException` → message générique » ; aucun test ne couvre une référence de projet de type chemin.
  - Mêmes effets hors MCP : `CliCommandSupport.java:166-169` (repli sur `getSimpleName()` → `error: index-status failed: ResolutionException`) et `MinosApiSupport.java:173-175` (message API = `ResolutionException`).
- **Comportement actuel** (exécuté en isolé sur le vrai `PublicErrorMessages`) :
  `sanitize("unknown project: N:\workspace-dev\minos-code-intelligence", F)` → `F` ; `"unknown project: /home/me/proj"` → `F` ; `"unknown project: my-api-key-service"` → `F` ; `"unknown project: demo"` → inchangé. Donc `minos_index_status {project:"N:\\workspace-dev\\minos-code-intelligence"}` renvoie `error: MINOS tool execution failed`, sans dire « référence inconnue, donne un nom ou un UUID de `minos project list` ». Les autres causes réelles d'échec de ce tool (voir C-02, C-03) sont toutes des exceptions non-IAE : même rendu opaque, et le journal n'indique que la classe.
- **Comportement attendu + source** : ADR-0017 « les erreurs restent bornées » et `docs/developer/public-surfaces.md` (l'erreur est une surface publique) ; `openspec/config.yaml` « Fail-closed » n'impose pas l'opacité d'une erreur d'usage. `ProjectResolver.ErrorCode` (`PROJECT_NOT_FOUND`, `PROJECT_REFERENCE_AMBIGUOUS`, `INVALID_PROJECT_REFERENCE`) existe déjà : c'est le code typé que S10 réclame (« des codes d'erreur typés seraient plus sûrs »). Le message attendu pour un échec client est fixe et n'écho pas l'entrée.
- **Cause** : la politique de redaction est une liste noire heuristique appliquée à des messages qui recopient l'entrée de l'appelant ; le MCP n'a pas de canal d'erreur typé hors `IllegalArgumentException`.
- **Impact** : agents incapables de se corriger ; support impossible ; la même redaction rend aussi opaques les `error:` de la CLI (visible dans le plugin IntelliJ : « MINOS command failed (exit 1): … ResolutionException »).
- **Correction minimale proposée** : (1) dans `MinosMcpTools.execute`, capter `ProjectResolver.ResolutionException` avant `IllegalArgumentException` et renvoyer un texte fixe par code (`error: PROJECT_NOT_FOUND; pass a registered project name or UUID (see minos project list)`), sans écho ; (2) renvoyer aussi un texte fixe et sûr pour `UnreadableRegistryException` (son message est déjà conçu sans chemin, `UnreadableRegistryException.java:21-23`), `SecurityException`/`IllegalStateException` du mode team (`MINOS_TEAM_TOKEN` absent, mode désactivé) ; (3) journaliser aussi le **nom de la méthode et la classe racine** (jamais le message) pour que l'opérateur sache laquelle des causes de C-02/C-03 s'applique ; (4) même traitement dans `CliCommandSupport.failureMessage` et `MinosApiSupport.failureMessage` (on garde `looksSensitive` pour les messages venant de couches internes, pas pour les `ResolutionException`).
- **Validation (JUnit 5)** : dans `minos-mcp/src/test/java/com/minos/mcp/`, nouveau `MinosMcpProjectReferenceErrorTest` : `MinosApplication.open(@TempDir home)`, `new MinosMcpTools(new MinosApplicationMcpBackend(app))` (le constructeur est package-private, la classe de test est dans le même package, comme `MinosApplicationMcpBackendM17Test`), appeler `minos_index_status` avec `project=` `"C:\\Users\\x\\proj"` puis `"/home/x/proj"` puis `"my-api-key-service"` ; assertions : `isError==true` et le texte contient `PROJECT_NOT_FOUND` et ne contient ni `C:\` ni `/home`. Cas bonus : registre avec un fichier de projet corrompu + nom introuvable → le texte contient « registry entr » (message `UnreadableRegistryException`).
- **Dépendances** : Q27 (même zone, `PublicErrorMessages`), S10 (liste noire heuristique). Ne rien modifier à `MinosMcpErrorRedactionTest` : ses deux premiers cas (IOException avec JDBC/secret) doivent rester verts.

---

### C-02 — Le MCP « lecture seule » inspecte les runtimes providers à chaque appel de statut : effets de bord, hachage de gros arbres, sonde AppContainer (Windows)

- **Qualification** : DÉFAUT CONFIRMÉ pour le chemin d'appel et les écritures ; l'impact en durée/échec sur `minos_index_status` est un RISQUE vraisemblable (non chronométré ici, aucun build possible).
- **Priorité** : **P1** (viole l'invariant central ADR-0017 et la formulation de l'ADR-0052 « lecture MCP sans effet de bord ») ; à défaut de preuve de cause racine du symptôme, P1 reste justifié par l'invariant.
- **Preuves (chaîne complète, Windows x64)** :
  1. `MinosApplicationMcpBackend.java:93-101` et `:86-91` : `indexStatus`/`projectStructure` ajoutent `providerProfiles()` (`:306-320`) → `ProviderPlatformService.listProviders()`.
  2. `ProviderPlatformService.java:34-41` → `runtimes.list()` → `CompositeProviderRuntimeManager.java:36-41` → `inspect` de chaque provider.
  3. `ManagedScipProviderRuntimeManager.java:141-151` : `inspect` → `inspectTypeScript()/inspectJava()` (`:184-187`, `:221`) → `seedFromPayload` (`:485-507`) qui, si un composant livré manque, **extrait/écrit** `MINOS_HOME/tools` (`seedComponents` `:537-556`, `seedAssembledTree` `:563-580` : archive jusqu'à 256 Mio, arbre jusqu'à 512 Mio, `deleteRecursively`/`move`), sous un bail jusqu'à 5 min (`SEED_LOCK_TIMEOUT`, `:83`), et dans tous les cas appelle `verifyInstalledEmbeddedTrees` (`:587-605`) → `integrityManifestMatches` → `directoryDigest` (`ManagedPolyglotScipRuntimeManager.java:361-391`) = **SHA-256 de tous les fichiers de l'arbre** à chaque appel.
  4. `StrongOwnedProcessExecutors.java:52-57` (`qualifyOwnership`) → `WorkerSandboxBackends.java:149-162` → `WindowsAppContainerWorkerSandboxBackend.discover` (`:92-122`) : le **constructeur** (`:83-90`) fait `SandboxLauncherScript.removeLegacyCopy` (suppression d'un fichier sous `MINOS_HOME`) et `materialize` (écriture/vérification du script sous `%LOCALAPPDATA%\minos-launchers`) à chaque appel ; la première fois par `MINOS_HOME` il lance `probeOsIsolation` (`:357-396`) : crée `MINOS_HOME/sandbox/appcontainer-probe-*`, démarre PowerShell sous AppContainer (timeout 15 s + 5 s de terminaison), supprime. `discover` n'attrape que `IOException|IllegalArgumentException|IllegalStateException` (`:111`) : une `UncheckedIOException`, `SecurityException` ou `UnsupportedOperationException` remonte et fait échouer l'appel du tool (opaque, voir C-01).
- **Comportement actuel** : un tool MCP censé être lecture seule peut écrire dans `MINOS_HOME` et dans `%LOCALAPPDATA%`, lancer un processus PowerShell, hacher des centaines de Mio et dépasser le délai de requête du client MCP au premier appel. La CLI `index-status` n'a pas ces effets (elle n'ajoute pas `providerProfiles`).
- **Comportement attendu + source** : ADR-0017 (« aucune mutation… les tools délèguent aux services existants »), `docs/user/mcp.md` (« Le MCP reste read-only »), ADR-0052 (« Maintenir lecture MCP sans effet de bord »), `public-surfaces.md` (« MCP … strictement read-only »).
- **Cause** : `providerProfiles` réutilise `ProviderPlatformService.listProviders()` (conçu pour `providers`/`doctor`, qui peuvent légitimement matérialiser) sans mode « inspection passive ».
- **Impact** : latence et échecs intermittents sur `minos_index_status`/`minos_project_structure`, écritures hors du périmètre annoncé, surface supplémentaire sur un `MINOS_HOME` en lecture seule (R12).
- **Correction minimale proposée** : retirer `providerProfiles` de `minos_index_status` (la CLI ne l'a pas ; `minos_project_structure` peut garder un profil statique issu de `provider.descriptor()` et `ProviderConformanceKit`, sans `runtimes.list()`), ou ajouter à `ProviderRuntimeManager` une méthode passive (`inspectReadOnly`) qui n'appelle ni `seedFromPayload`, ni `qualifyOwnership`, ni `discover`. Garder l'état d'exécution (`runtimeState`) dans `providers`/`doctor` uniquement. Si le champ doit rester (contrat additif v1), l'alimenter depuis un cache calculé hors MCP.
- **Validation (JUnit 5)** : `minos-mcp/src/test/java/com/minos/mcp/MinosMcpReadOnlyHomeTest` : ouvrir `MinosApplication` sur un `@TempDir`, snapshot de l'arborescence (chemins + tailles + mtimes) avant/après un appel à chacun des 31 tools via `MinosApplicationMcpBackend` (pas besoin de projet indexé pour 24 d'entre eux), assertion « arborescence identique octet par octet » sur le modèle de `LazyWiringGuardTest` (CLI). Variante Windows-only avec un `ProviderRuntimeManager` factice dont `inspect` compte ses appels, pour verrouiller « `minos_index_status` n'appelle pas `inspect` ». À noter : `LazyWiringGuardTest` couvre la CLI, **aucune garde équivalente n'existe pour le MCP**.
- **Dépendances** : ADR-0052/0053 (étude, statut Proposed), R12, S15/S16 (script de sandbox). Aucun golden de caractérisation ne mentionne `providerProfiles` (grep dans `A2SurfaceCharacterizationTest`), mais `MinosApplicationMcpBackendM17Test` et `ProviderProfilesKeyOrderTest` l'assertent : à adapter.

---

### C-03 — Le statut d'un projet (CLI `project list`/`index-status`, MCP `minos_index_status`, plugin) paie une découverte complète du dépôt ; un seul répertoire illisible suffit à l'échouer

- **Qualification** : DÉFAUT CONFIRMÉ (couplage inutile ; échec sur `visitFileFailed`) / RISQUE (coût).
- **Priorité** : **P2**.
- **Preuves** : `ProjectInspectionService.java:132-143` (`view()` appelle `discoveryService.discover(rootPath)` pour obtenir langues/builds/modules) ; `:90-108` (`inventory()` le fait pour chaque projet enregistré → `project list` coûte la somme de tous les dépôts) ; `ProjectDiscoveryService.java:126-155` (`walkFileTree` complet, `visitFileFailed` **relance** l'exception, `:150-154`) ; `SourceBudgetPolicy.java:75-85` (`IOException "project discovery exceeds traversal budget"` au-delà de `maxFiles×multiplicateur`, 100 000 fichiers par défaut, lignes 9-10 et 38). Or `ProjectJson.indexStatus` (`ProjectJson.java:54-72`) n'utilise ni langues, ni builds, ni modules, ni `rootPath` : `index-status` n'a pas besoin de découverte. Côté plugin : `MinosCliClient.java:51-64` (`resolveProject` = `project list` complet à chaque résolution, délai 30 s).
- **Comportement actuel** : un dépôt monorepo dépassant le budget de traversée, un répertoire en accès refusé, un chemin trop long (A10) ou un fichier verrouillé fait échouer `minos_index_status` et `index-status` (IOException → opaque en MCP, C-01) alors que l'état d'index est lisible ; `project list` rend la ligne `UNREADABLE` (Q8) mais n'a pas rétabli l'état de l'index.
- **Comportement attendu + source** : Q2/P1 (« une lecture de statut ne mute rien et ne dépend que de l'état publié », `ProjectInspectionService.java:145-146`) ; l'esprit de Q8 (domaines de défaillance séparés).
- **Cause** : un seul `ProjectView` sert de vue pour trois usages (structure, statut, inventaire).
- **Impact** : disponibilité du statut ; temps de réponse O(taille des dépôts) ; en plugin, timeout de 30 s sur `project list` avec plusieurs gros projets.
- **Correction minimale proposée** : séparer `view()` en deux (`statusView` sans `discover`, `structureView` avec). `minos_index_status`, `index-status` et `resolveProject` du plugin n'utilisent que `statusView` ; `rootAvailable` reste un `Files.isDirectory`. Pour `project list`, rendre la découverte tolérante (échec de découverte = ligne dégradée, déjà le cas via `catch` de `inventory()`) et optionnelle derrière un drapeau si le contrat JSON le permet de façon additive.
- **Validation** : `minos-bootstrap/src/test/java/com/minos/bootstrap/application/` (à côté de `ProjectInventoryToleranceTest`) : projet enregistré dont la racine contient un sous-répertoire non lisible (POSIX : `chmod 000`, `@EnabledOnOs(LINUX)`) ou un `SourceBudgetPolicy(1,1)` injecté ; `inspectProject` doit répondre avec l'état d'index et sans lever. Côté MCP, `minos-mcp` : `indexStatus` ne doit plus lever pour ce projet.
- **Dépendances** : C-01 (diagnostic), A9/A10.

---

### C-04 — Plugin IntelliJ sous Windows : le lanceur `minos.cmd` (défaut documenté) ne démarre pas, double échappement `cmd /c` + `CommandLineToArgvW`

- **Qualification** : DÉFAUT CONFIRMÉ par reconstitution du pipeline avec le vrai script de lancement (le côté Java est lu, pas exécuté dans l'IDE).
- **Priorité** : **P1** si le défaut est réel dans le plugin livré (le défaut est le lanceur par défaut, `MinosSettingsState.java:81-83` → `minos.cmd`, et `docs/user/intellij-plugin.md:61,69`). Contournement : configurer `minos.exe`.
- **Preuves** : `MinosCommandLine.java:12-34` (`build` : pour un `.cmd/.bat` sous Windows → `cmd.exe /d /v:off /s /c <rendu>`), `:39-45` (`renderWindowsBatchCommand`, chaque jeton entre `"…"`), `:47-58` (`quoteWindows` échappe `^ % & | < > "` par `^`) ; le tout est donné à `MinosStrongProcessLauncher.start` (`MinosCliClient.java:136-149`), qui écrit un plan (`MinosStrongProcessLauncher.java:358-386`) puis le script `windows-cli-job-owner-v1.ps1` reconstruit la ligne de commande avec `BuildCommandLine` / `QuoteArgument` (`windows-cli-job-owner-v1.ps1:244-282`) : tout argument contenant un guillemet est entouré de `"` et ses guillemets sont échappés `\"` (règles CRT). `cmd.exe /s /c` ne connaît pas `\"` : il retire le premier et le dernier `"` et lit `\"C:\…\minos.cmd\"` comme nom de commande.
- **Comportement actuel (observé)** : j'ai généré un plan identique à celui que `writeWindowsPlan` écrit (`cmd.exe /d /v:off /s /c "<rendu de MinosCommandLine>"`) pour un `echoargs.cmd`, puis lancé le script réel : sortie `'\"C:\Users\…\echoargs.cmd\"' n'est pas reconnu en tant que commande interne ou externe` (exit 1), pour `simple`, `List<String>` et `a&b`. Aucun test existant ne lance un vrai `.cmd` : `MinosCommandLineTest.java:22-32` vérifie seulement `contains(...)` sur la chaîne.
- **Comportement attendu + source** : `docs/user/intellij-plugin.md:61,69` (`minos.cmd` est le lanceur nominal sous Windows) ; `public-surfaces.md` (client IDE externe du protocole CLI JSON).
- **Cause** : deux couches de quoting superposées (rendu façon `cmd` + sérialisation façon CRT) ; la seconde est invisible à la première.
- **Impact** : le plugin est inutilisable avec le lanceur par défaut sous Windows ; effet secondaire non vérifié mais visible dans la sortie : `%` doublé en `%%` (le rendu suppose le contexte `.bat`, or `/c` n'interprète pas `%%`), `^` doublé, ce qui altère les arguments texte libres (`semantic-search`, symboles).
- **Correction minimale proposée** : dans le script (ou dans le plan), traiter le cas `cmd.exe … /c <chaîne>` : écrire la chaîne **brute** entourée de `"` sans passer par `QuoteArgument` ; et dans `quoteWindows` ne plus doubler `^`/`%`/`&` à l'intérieur de guillemets, refuser plutôt (`IllegalArgumentException`) les arguments contenant `"`, `%`, `^` pour un `.cmd`. Alternative plus simple : résoudre `minos.cmd` vers le `minos.exe` voisin quand il existe (le doc fait de `app\minos.exe` le chemin recommandé).
- **Validation (JUnit 5)** : `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosCommandLineBatchLaunchTest` avec `@EnabledOnOs(OS.WINDOWS)` : `@TempDir` contenant `echoargs.cmd` (`@echo off` / `echo [%~1]` / `echo [%~2]`) ; `ProcessBuilder pb = new ProcessBuilder(MinosCommandLine.build(cmd.toString(), List.of("simple","50% off","a&b"), "Windows 11"));` ; `try (var launch = MinosStrongProcessLauncher.start(pb, home.toString())) { … }` (classes de même package) ; lire stdout, attendre `[simple]`, `[50% off]`, `[a&b]`. Rouge avant correctif sur ce poste.
- **Dépendances** : G5 (le gate du plugin n'est exigé par rien, donc la CI ne l'aurait pas vu), T6.

---

### C-05 — `minos_hybrid_context` (et `ide hybrid-context`) : un `maxTokens` < 800 est refusé alors que le schéma l'autorise

- **Qualification** : DÉFAUT CONFIRMÉ (lecture).
- **Priorité** : **P2**.
- **Preuves** : `McpToolSchemas.java:100-107` (`maxTokens` 128..65536 et `maxTokensPerDocument` 32..65536 indépendants) ; `MinosApplicationMcpBackend.java:64` (`DEFAULT_HYBRID_CONTEXT_DOCUMENT_TOKENS = 800`) et `:408-416` (le défaut est appliqué sans tenir compte de `maxTokens`) ; `HybridContextBuilder.java:68-70` (`maxTokensPerDocument > maxTokens` → `IllegalArgumentException("maxTokensPerDocument must be between 32 and maxTokens")`) ; même défaut côté CLI IDE `IdeIntelligenceCommand.java:187-197` (`defaults.maxTokensPerDocument()` = 800) ; le plugin contourne : `MinosM21Client.java:87` (`Math.min(800, maxTokens)`). Les tests passent toujours les deux valeurs : `MinosMcpToolsTest.java:135`, `CliValidInvocationsTest.java:251`.
- **Comportement actuel** : `{project, query, maxTokens: 500}` → `error: maxTokensPerDocument must be between 32 and maxTokens` alors que le client n'a jamais envoyé ce paramètre (127 ≤ maxTokens ≤ 799 échouent).
- **Comportement attendu + source** : schéma publié (un défaut ne doit pas rendre invalide une combinaison que le schéma accepte) ; contrat additif.
- **Correction minimale proposée** : `maxTokensPerDocument == null ? Math.min(800, maxTokens) : …` dans `hybridContextDefaults`, même chose dans `IdeIntelligenceCommand.hybridContext` (`HybridContextBuilder.ContextRequest.defaults` ne change pas).
- **Validation** : `MinosMcpToolsTest` : appeler `minos_hybrid_context` avec `maxTokens=500` seulement et un backend factice qui capture la requête : attendre `maxTokensPerDocument() == 500` ; idem `IdeIntelligenceCommandTest` pour `--max-tokens 200` seul.
- **Dépendances** : aucune.

---

### C-06 — `MinosApiSupport.openApplication` ne traduit que `IOException` : les `RuntimeException` d'ouverture sortent brutes de `new LocalMinosApi(Path)` & consorts

- **Qualification** : DÉFAUT CONFIRMÉ (lecture) ; l'exception exacte dépend de la configuration.
- **Priorité** : **P2** (contrat v1 : tout échec public est un `MinosApiException` avec message redacté).
- **Preuves** : `MinosApiSupport.java:78-84` (`catch (IOException)` seulement) ; `LocalMinosApi.java:67-69`, `:526-528`, `LocalMinosMultiRepositoryApi.java:23,326-327`, `LocalProviderPlatformApi.java:17,49-50` (constructeurs `throws MinosApiException`) ; `MinosApplication.java:216-233` (`open` peut lever `IllegalStateException` de `MinosApplicationComposers.java:40-52` — absent/ambigu —, ou `IllegalArgumentException` de `StorageBackendConfiguration` : `"unsupported storage backend: " + value`, valeur lue dans `config/minos.properties`) ; javadoc `MinosApiSupport.java:14-33` (le message public passe toujours par `PublicErrorMessages`).
- **Comportement actuel** : `-Dminos.storage.backend=oracle` → `new LocalMinosApi(home)` lève `IllegalArgumentException("unsupported storage backend: oracle")`, non redactée, non classée, non attrapable par `catch (MinosApiException)`. Les autres surfaces s'en sortent (la CLI attrape `Exception` dans `MinosLauncher.java:92-95`, `LazyApplication.OpenFailure`).
- **Correction minimale** : `catch (IOException | RuntimeException failure)` → `publicFailure(ErrorCode.IO_FAILURE or INVALID_REQUEST…)` selon le type (`IllegalArgumentException` → `INVALID_REQUEST`, `IllegalStateException` → `UNAVAILABLE`), en conservant la fermeture déjà faite par `MinosApplication.open` en cas d'échec (`:230-232`).
- **Validation** : `minos-api/src/test/java/com/minos/api/OpenFailureTranslationTest` : `System.setProperty("minos.storage.backend","bogus")` (restaurer en `finally`), `assertThrows(MinosApi.MinosApiException.class, () -> new LocalMinosApi(@TempDir home))` et `code() == INVALID_REQUEST` ; même assertion pour `LocalMinosMultiRepositoryApi` et `LocalProviderPlatformApi`.
- **Dépendances** : Q27 (messages bruts), `OwnedApplicationLifecycleTest` (ne pas casser la sémantique de propriété).

---

### C-07 — Plugin : « Index », « Reindex Full » et « Synchronize semantic index » tournent sous le délai global de 30 s (max 300 s)

- **Qualification** : DÉFAUT DE CONCEPTION CONFIRMÉ (lecture) / DÉCISION À CLARIFIER.
- **Priorité** : **P2**.
- **Preuves** : `MinosSettingsState.java:25,31,73-74` (défaut 30 s, borne haute 300 s, sinon retour à 30), `MinosCliClient.java:103-110` (`index`), `:127-165` (même boucle d'attente et `supervisor.stop(null)` pour toute commande), `MinosToolWindowPanel.java:209` (appel de `client.index`), `MinosM21Client.java:61-65` (`semantic-index-sync`). `docs/user/intellij-plugin.md:63` documente 30 s ; `:346-347` : « tue un processus dépassant ce délai ».
- **Comportement actuel** : une indexation réelle dépasse 30 s ; le plugin tue l'arbre de processus (frontière Job Object/cgroup) au milieu d'un run, laissant un run `INTERRUPTED` (R1) et l'utilisateur sans résultat. Aucun réglage par classe de commande ; 300 s reste insuffisant pour un gros monorepo.
- **Attendu + source** : `docs/user/intellij-plugin.md:129-136` (Index/Reindex/Plan sont des actions de premier plan) et ADR 0039 (reprise).
- **Correction minimale** : table de délais par commande (lecture 30 s ; `index`, `semantic-index-sync`, `doctor` : 30 min ou « sans délai, annulable par la barre de progression »), l'annulation `ProgressManager.checkCanceled()` existe déjà (`MinosCliClient.java:155`).
- **Validation** : `MinosProcessSupervisorTest`/nouveau `MinosCliClientTimeoutTest` : un faux exécutable qui dort 3 s ; avec `timeout` de la classe « long » = 10 s la commande aboutit, avec la classe « lecture » à 1 s elle échoue.
- **Dépendances** : R1 (reprise), G5.

---

### C-08 — Plugin : la persistance du plan Windows écrit l'environnement complet de l'IDE ; le `.ps1` est réécrit à chaque appel

- **Qualification** : RISQUE (secrets en clair sur disque, durée bornée) + RISQUE non vérifié (concurrence de remplacement).
- **Priorité** : **P2** (données sensibles) / P3 (concurrence).
- **Preuves** : `MinosStrongProcessLauncher.java:358-386` (`writeWindowsPlan` : toutes les variables de `original.environment()` — l'environnement hérité de l'IDE, y compris `MINOS_TEAM_TOKEN`, jetons cloud… — encodées en Base64, pas chiffrées, dans `cli-*.plan`) ; `:40` (`STALE_PLAN_AGE = 24 h` : après un crash de l'IDE le plan reste jusqu'à 24 h) ; `:80-87` (répertoire `<MINOS_HOME>/intellij/process-ownership` ou `%USERPROFILE%\.minos` si `MINOS_HOME` du réglage est vide, `:288-295`) ; `:297-309` et `:342-348` (`installWindowsLauncher` : réécrit le `.ps1` et le remplace par `ATOMIC_MOVE | REPLACE_EXISTING` à chaque commande, puis l'exécute avec `-ExecutionPolicy Bypass`, `:89-92`) ; `MinosCliClient.java:132-177` (aucun verrou : deux actions en parallèle sont possibles).
- **Comportement actuel** : l'ACL est « propriétaire seul » (`restrictWindowsOwnerOnly`), donc pas d'escalade ; mais (a) un secret d'environnement survit sur disque pendant la durée du processus et jusqu'à 24 h en cas de crash, (b) le script exécutable est dans un répertoire sous `MINOS_HOME`, ce que S15 a corrigé côté cœur (script sorti de `MINOS_HOME`, empreinte revérifiée) et que le plugin n'a pas repris, (c) le remplacement d'un fichier qu'une autre commande est en train d'ouvrir peut échouer sous Windows (`AccessDeniedException`, remonté comme `Cannot start MINOS executable …`).
- **Correction minimale** : ne passer à la plan que la liste blanche des variables nécessaires (`MINOS_HOME`, `PATH`, `SystemRoot`, `JAVA_HOME`…) comme S7 l'a fait pour les lanceurs du cœur, ne réécrire le `.ps1` que si l'empreinte diffère, et sérialiser `start` par un `ReentrantLock` statique.
- **Validation** : `MinosStrongProcessLauncherTest` (existant, 246 lignes) : plan écrit pour un `ProcessBuilder` dont l'environnement contient `FAKE_SECRET=x` ; relire le plan, décoder, asserter l'absence de `FAKE_SECRET`.
- **Dépendances** : S5/S7/S15/S16 (même famille), T6.

---

### C-09 — Plugin : un handshake (processus JVM) précède chaque action M21 ; la fenêtre de versions négociée est ignorée

- **Qualification** : AMÉLIORATION (performance) + DÉRIVE de contrat (champs `minCompatibleVersion`/`maxCompatibleVersion`).
- **Priorité** : **P3**.
- **Preuves** : `MinosM21Client.java:91-94` (`execute` : `client.handshake()` systématique, puis `client.executeJson` qui re-vérifie `ensureHandshake`) ; `MinosCliClient.java:44-49` (le handshake n'est pas mémorisé par ce chemin), `:179-196` (`configurationKey` recalcule l'identité du binaire à chaque appel) ; `IdeCommand.java:61-67` publie `minCompatibleVersion`/`maxCompatibleVersion` que `MinosProtocolHandshake.java:12-30` ne lit jamais (égalité stricte avec `"1"`).
- **Impact** : deux démarrages de JVM pour une action M21 ; une future version `1.1` additive serait refusée par le plugin alors que le contrat annonce une fenêtre.
- **Correction minimale** : réutiliser la capacité mise en cache (`verifiedConfiguration` + `capabilities` du dernier handshake) ; accepter `protocolVersion` dans `[min, max]` du plugin.
- **Validation** : `MinosM21ClientTest` : faux `MinosCliClient` comptant les handshakes sur trois actions → 1 seul.
- **Dépendances** : aucune.

---

### C-10 — `index-status`/`inspect <nom>` : code 3 avec stdout vide quand un nom est introuvable devant des entrées illisibles

- **Qualification** : DÉCISION À CLARIFIER (conforme à `CLI-SUIVI.md` § 2, mais contradictoire avec le libellé du code 3).
- **Priorité** : **P3**.
- **Preuves** : `ProjectCommand.java:94-100` (`UnreadableRegistryException` → message sur stderr, **rien** sur stdout, `return PARTIAL_RESULT`) ; `MinosCli.java:74` (« 3  partial result: valid for what was read ») ; `scripts/lib/partial-result-commands.json` (`index-status`, `inspect`, `project inspect` classées « peuvent rendre 3 ») ; les consommateurs qui acceptent `{0,3}` puis parsent le JSON (scripts `m14`/`m29`, voir `Q25-Q26-SUIVI.md` § 2) reçoivent une sortie vide.
- **Comportement attendu** : un code 3 doit toujours être accompagné d'un JSON valide pour ce qui a été lu (c'est le sens unique annoncé, ADR 0016 via `docs/user/cli.md:458`). Le cas « introuvable + illisibles » n'a rien à rapporter : c'est un échec indéterminé.
- **Option** : soit code 1 (échec) avec le message véridique actuel, soit stdout JSON `{"project":null,"degradedCount":N,…}`. À trancher avec le propriétaire du contrat CLI.
- **Validation** : étendre `PartialResultCommandsContractTest` (commande listée ⇒ stdout JSON parsable pour tous les cas qui rendent 3).
- **Dépendances** : Q24/Q26, doctrine `CLI-SUIVI.md`.

---

### C-11 — `minos_architecture_graph` : `format=json` + `module` ne rend pas un graphe de voisinage (description et doc divergent du code)

- **Qualification** : DÉRIVE DOC/CONTRAT (comportement verrouillé par un golden).
- **Priorité** : **P3**.
- **Preuves** : `MinosMcpTools.java:112` (description : « optionally focus on one module and its direct neighbours ») ; `docs/user/mcp.md` (exemple « Voisinage d'un module : minos_architecture_graph module=packages/api format=json ») ; `MinosApplicationMcpBackend.java:167-172` (`json` + `module` → `moduleContext` = comptes et rangs, `ArchitectureResultRenderer.java:77-107`, aucun voisin) alors que `mermaid`/`dot` + `module` filtrent bien le graphe (`:173-181`). `A2SurfaceCharacterizationTest.java:229-231` fige le comportement.
- **Impact** : un client qui demande le voisinage en JSON obtient un autre schéma, sans le dire.
- **Correction minimale** : soit rendre le JSON filtré (même sous-graphe que mermaid, additif via nouvelle clé), soit corriger description + doc (« json + module = résumé du module »). Le golden doit être régénéré si le comportement change.
- **Validation** : test MCP : `format=json,module=X` contient `moduleDependencies` restreintes aux voisins de `X`.
- **Dépendances** : golden `A2SurfaceCharacterizationTest`.

---

### C-12 — Inventaire CLI incomplet : `mcp` et les huit opérations `ide <op>` absentes de `minos --help` et de `product-facts`

- **Qualification** : DÉRIVE DOC/CONTRAT (ADR-0016 « contrat CLI stable » doit être lisible).
- **Priorité** : **P3**.
- **Preuves** : `MinosCli.java:25-77` (USAGE sans `mcp`, `ide` n'affiche que `ide handshake`), `MinosCliRunner.java:30-36` (usage `mcp` servi à part) ; `docs/generated/product-facts.md` (section « Commandes CLI » : `ide handshake` seulement, pas `mcp`, pas `ide program-graph|impact-v2|…`) alors que `docs/developer/public-surfaces.md:96-109` les liste ; `MinosLauncher.java:73-79` : seul `minos mcp` exact est routé, `minos mcp --stdio` devient `error: unknown command: mcp` (`MinosCli.java:240`), message trompeur puisque `mcp` existe.
- **Correction minimale** : ajouter `mcp` et `ide <op>` à USAGE et au générateur de facts ; faire répondre à `mcp <argument>` « mcp takes no option » (exit 2) à la place de « unknown command ».
- **Validation** : `StableCliHelpTest` : `usage()` contient `mcp` et les huit `ide`. `scripts/docs/product-facts.py --check` en CI.
- **Dépendances** : aucune.

---

### C-13 — MCP : la validation des arguments dépend du validateur de schéma du SDK (fail-open s'il est absent) et n'est testée par aucun test de bout en bout

- **Qualification** : RISQUE.
- **Priorité** : **P3**.
- **Preuves** : `MinosMcpServer.java:56-61` (`McpServer.sync(...)` sans `validateToolInputs(true)` ni validateur explicite) ; SDK `mcp-core-2.0.1`, `ToolInputValidator.validate` : `validator == null` ⇒ `return null` (pas d'erreur) ; `McpServer` : `validateToolInputs = true` par défaut mais le validateur vient d'un `ServiceLoader` (`mcp-json-jackson3`). `McpToolSchemas.java:187-192` publie `additionalProperties:false`, mais le handler (`MinosMcpTools.java:152`) ignore les propriétés inconnues et ne rejette que les types/bornes qu'il connaît. `MinosMcpServerIntegrationTest` n'essaie qu'un `depth:99` (rejeté aussi par le handler). Le gate `ServicesResourceTransformer` (`minos-app/pom.xml:63`) fusionne bien les services, mais rien ne vérifie le validateur dans le JAR ombré. `slf4j-nop` (`minos-app/pom.xml:30`) efface en plus l'avertissement du SDK.
- **Impact** : S10 « arguments inconnus non rejetés » est vrai au niveau du handler, faux au niveau transport tant que le validateur est chargé ; l'état réel dépend d'un chargement non testé.
- **Correction minimale** : appeler `validateToolInputs(true)` et vérifier `McpJsonDefaults.getSchemaValidator()` non nul au démarrage (échec explicite), et rejeter dans `MinosMcpTools.arguments(...)` toute clé hors schéma (liste dérivée de `McpToolSchemas`).
- **Validation** : `MinosMcpServerIntegrationTest` (ou nouveau test dans `minos-app`, shaded jar) : `client.callTool("minos_impact", Map.of("project","p","symbolId","s","bogus",1))` → `isError == true`. Variante unitaire `MinosMcpToolsTest` pour le handler seul.
- **Dépendances** : S10.

---

### C-14 — NEXUS : la résolution des `fileId` de forme `file:<sha256>` parcourt tout le dépôt sans politique d'ignore, et la moindre erreur d'accès fait échouer l'export

- **Qualification** : RISQUE.
- **Priorité** : **P3**.
- **Preuves** : `NexusExportService.java:205-258` (`resolveFilePaths` : `Files.walkFileTree(root, new SimpleFileVisitor<>() …)` à `:222`, budget `MAX_FILE_PATH_CANDIDATES` 1 000 000, ni `ProjectIgnorePolicy` ni `isRecursableDirectory`, `visitFileFailed` non surchargé donc relance l'`IOException`) ; l'id `file:` est produit par `ScipIngestionAdapter.java:336-341` quand aucun `explicitFileId` n'est fourni. Un export échoue en bloc si un sous-répertoire est en accès refusé, ou perd des symboles avec `FILE_PATH_DISCOVERY_TRUNCATED` / `UNRESOLVED_SYMBOL_FILE_ID_OMITTED` sur un dépôt avec `node_modules`.
- **Attendu** : ADR-0020 (frontière JSON) : un contrat « capability-honest » qui dit ce qu'il n'a pas pu lire. Les limitations existent déjà : il manque `FILE_PATH_DISCOVERY_UNREADABLE`.
- **Correction minimale** : surcharger `visitFileFailed` (ajouter la limitation et continuer), sauter les répertoires ignorés par défaut (`.git`, `node_modules`, `target`).
- **Validation** : `NexusExportBoundedSelectionTest`/nouveau `NexusExportUnreadableDirectoryTest` (POSIX, `@EnabledOnOs(LINUX)`) : sous-répertoire `chmod 000` → export réussi + limitation présente.
- **Dépendances** : ADR-0020, G2.

---

### C-15 — MCP : bornes de réponse et de durée trop larges, pas d'annulation, et aucune mention que le contenu renvoyé est non fiable

- **Qualification** : AMÉLIORATION (vérifiée par lecture).
- **Priorité** : **P3**.
- **Preuves** : `MinosMcpTools.java:45` (plafond de réponse 8 MiB, ≈ 2 M tokens, plus que tout contexte de modèle) ; `MinosApplicationMcpBackend.java:56-57` (`minos_program_graph` : 10 000 nœuds / 50 000 arêtes par défaut) ; `MinosMcpTools.java:156-174` (aucun délai ni annulation par appel) ; `MinosApplicationMcpBackend.java:103-110` et `MinosMcpTools.java:233` (`includeSource` par défaut `true` : du code et des commentaires du projet indexé partent vers le client sans marqueur d'origine) ; `MinosMcpServer.java:58` (les « instructions » du serveur ne disent pas que le contenu est issu du dépôt et doit être traité comme donnée).
- **Impact** : réponses inutilisables par le client, risque d'injection par contenu de dépôt (commentaire « ignore previous instructions »), appels longs non interruptibles.
- **Correction minimale** : défauts plus bas pour `program_graph` (ex. 1 000/5 000, additif), mention « tool results include untrusted repository content » dans `instructions` et dans les descriptions des tools qui renvoient du source, délai par appel configurable.
- **Validation** : `MinosMcpToolsTest` : `instructions` contient la mention ; test de budget avec `MinosMcpTools(backend, maxResultBytes)` déjà possible (`:74-79`).
- **Dépendances** : ADR-0053 (profils, Proposed).

---

### C-16 — `ide <op>` : un `IOException` d'exécution est rapporté « MINOS bootstrap failed »

- **Qualification** : AMÉLIORATION (libellé trompeur, code de sortie exact).
- **Priorité** : **P3**.
- **Preuves** : `IdeIntelligenceCommand.java:91-97` (n'attrape que `IllegalArgumentException | IllegalStateException | UnreadableRegistryException`) ; un `IOException` d'un service remonte à `MinosLauncher.java:92-95` → `error: MINOS bootstrap failed: …`, exit 1. Les autres commandes passent par `CliCommandSupport.run` (`:103-106`, `error: <commande> failed: …`).
- **Impact** : le plugin affiche « MINOS bootstrap failed » pour une recherche sémantique qui échoue (confusion avec C-04/C-06).
- **Correction minimale** : attraper `IOException` et `RuntimeException` dans `run`, comme `CliCommandSupport.run`.
- **Validation** : `IdeIntelligenceCommandTest` : service factice levant `IOException` → stderr `error: …`, sans « bootstrap », exit 1.
- **Dépendances** : aucune.

---

## (1) Carte réelle du périmètre

| Module / composant | Points d'entrée | Rôle | Dépendances sortantes | Tests | Lacunes constatées |
|---|---|---|---|---|---|
| `minos-cli` (≈ 40 fichiers) | `MinosLauncher.main/launch` (`:40-97`), `MinosCliRunner.run`, `MinosCli` (table de routes `:85-203`), `CliOptions` (parseur unique), `LazyApplication`, `IdeCommand`/`IdeIntelligenceCommand`, `McpLaunchRoutes` (SPI) | dispatcher, codes 0/1/2/3, usage sans état | `minos-application`, `minos-engine`, `minos-nexus`, SPI `minos-app`/`minos-bootstrap` | `LazyWiringGuardTest`, `StableCliHelpTest`, `CliValidInvocationsTest`, `IdeIntelligenceCommandTest`, `PartialResultCommandsContractTest`, goldens | C-10, C-12, C-16 ; aucune garde « arborescence identique » pour le MCP |
| `minos-mcp` (8 fichiers) | `MinosMcpServer.main/run`, `MinosMcpTools.specifications` (31 outils), `MinosApplicationMcpBackend`, `McpToolSchemas`, `McpArgumentBounds` | serveur STDIO du SDK MCP 2.0.1, validation des bornes en octets UTF-8, plafond de réponse 8 MiB | `minos-application`, SDK MCP | `MinosMcpToolsTest`, `MinosMcpSchemaBoundContractTest`, `MinosMcpArgumentBoundsTest`, `MinosMcpErrorRedactionTest`, `MinosMcpServerLifecycleTest`, E2E STDIO dans `minos-app` | C-01, C-02, C-05, C-11, C-13, C-15 ; `minos_index_status`/`project_structure` jamais testés de bout en bout par STDIO |
| `minos-api` | `MinosApi`, `MinosMultiRepositoryApi`, `AdvancedCodeIntelligenceApi`, `SemanticCodeIntelligenceApi`, `ProviderPlatformApi`, `MinosTeamApi` + implémentations `Local*`, `MinosApiSupport` | API Java v1 (`CONTRACT_VERSION = "1"`), DTO immuables via `immutable()`, classification des erreurs | `minos-application` | 12 classes de test (contrat, taxonomie null, import outcome, tolérance) | C-06 ; DTO : copies défensives présentes (`MinosApi.java:591`) |
| `minos-nexus` | `NexusExportService.export`, `NexusSemanticSignalService` | projection JSON v1 du snapshot actif | `minos-domain`, `minos-engine` | 3 classes de test | C-14 |
| `minos-app` | `McpLaunchRouteProvider`, `McpBackendRouter`, `DockerMcpTransport`, `McpBackendConfigurationStore`, `NexusExportBridgeMain` | assemblage final, route `minos mcp` (natif ou Docker), JAR ombré | `minos-cli`, `minos-mcp`, `minos-bootstrap` | `McpBackendRouter*Test`, `ShadedJar*IT`, goldens de caractérisation | `loadOrMigrate` écrit `runtime/backend.properties` au premier `minos mcp` (R12) |
| `minos-bootstrap` | `DefaultMinosApplicationComposer` (ServiceLoader, ADR 0042) | composition root | tout | `DefaultMinosApplicationComposerTest`, `MinosApplicationTest`… | non audité en détail |
| `minos-intellij` (Gradle, Java 21, 16 classes + 1 `.ps1`) | `MinosCliClient`, `MinosM21Client`, `MinosProjectService`, `MinosStrongProcessLauncher`, `MinosProcessSupervisor`, `MinosCommandLine`, `MinosExecutableResolver` | client du protocole `minos-ide` v1 par processus local, supervision Job Object/cgroup, résolution du lanceur hors dossier projet | IntelliJ Platform 2026.1, Gson ; **aucune dépendance `com.minos:*`** (vérifié dans `build.gradle.kts` et par grep des imports `com.minos.*` : uniquement `com.minos.intellij.*`) | 12 classes de test ; supervision/orphelins solides | C-04, C-07, C-08, C-09 ; aucun test ne lance un `.cmd` réel ; aucun test du protocole contre la vraie CLI (pas de contrat dérivé de `IdeCommand`) |

Observations positives vérifiées : validation en octets UTF-8 des deux côtés du schéma (`McpArgumentBounds`), codes d'erreur API typés avec redaction unique, usage CLI analysé avant toute ouverture de `MINOS_HOME` (`LazyApplication`), résolution du lanceur du plugin sans consulter le répertoire projet, frontière JSON NEXUS portée par des records immuables, aucune écriture sur `System.out` hors transport MCP (grep), journalisation MCP sans message d'exception.

## (2) Ce que je n'ai PAS examiné

- Exécution réelle : aucun `mvnw`, aucun `gradlew`, aucun test lancé ; les verdicts reposent sur la lecture, plus deux reproductions isolées (`PublicErrorMessages` compilé seul ; script `.ps1` réel avec un plan reconstitué). Le délai réel de `minos_index_status` n'a pas été chronométré (C-02 est un risque de durée, un défaut d'effets de bord).
- Le plugin n'a pas été exécuté dans IntelliJ : C-04 vient du pipeline reconstitué (côté Java lu, pas lancé) ; un runner Windows reste à confirmer.
- Hors périmètre ou non lus : corps de `ProjectCommand`, `IndexCommand`, `TeamCommand`, `RemoteIndexCommand`, `RuntimeCommand`, `DoctorCommand`, `ToolsCommand` au-delà des points cités ; `LocalMinosMultiRepositoryApi`, `LocalMinosTeamApi`, `LocalAdvancedCodeIntelligenceApi`, `LocalSemanticCodeIntelligenceApi` (seul `LocalMinosApi` et `MinosApiSupport` lus) ; `NexusSemanticSignalService` ; `DefaultMinosApplicationComposer` et le câblage des providers ; UI du plugin (`MinosToolWindowPanel`, `ArchitectureGraphPanel`, actions) hors points cités ; scripts `docker/scripts/mcp-lifecycle.ps1`, installeur, packaging ; scripts de qualité sauf `check-tools-manifest.py` (lu en en-tête).
- Concurrence des handlers MCP sur une `MinosApplication` partagée (le SDK exécute-t-il les appels en parallèle ? thread-safety des services ?) : non analysée.
- Mode Docker du MCP : le jeton `MINOS_TEAM_TOKEN` n'est pas transmis par `docker exec` (`DockerMcpTransport.java:77-79`), donc les tools team ne voient que l'environnement du conteneur ; non vérifié dans la doc `docs/user/docker-runtime.md`.
- Linux : le plugin (systemd user scope) et la sonde bubblewrap côté provider n'ont pas été analysés ; toutes les reproductions sont Windows.
