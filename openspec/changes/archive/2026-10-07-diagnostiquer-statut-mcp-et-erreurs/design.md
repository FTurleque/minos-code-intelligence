# Design

## Context

Voir `proposal.md` (Why, Constats d'audit couverts) et
`specs/surfaces-publiques/spec.md` (exigences). État du code au HEAD `bc1d3421`, relu pour ce
changement :

- **Erreurs (C-01).** `ProjectResolver.ResolutionException extends IllegalArgumentException`
  porte un `ErrorCode` (`PROJECT_NOT_FOUND`, `PROJECT_REFERENCE_AMBIGUOUS`,
  `INVALID_PROJECT_REFERENCE`) et un message qui recopie la référence de l'appelant
  (`"unknown project: " + reference`). Chaque surface a sa propre traduction, qui passe par
  `PublicErrorMessages.sanitize` / `looksSensitive` (liste noire heuristique : toute sous-chaîne
  `X:\`, `/…`, `\\h\s`, `api-key`, `secret`, `token:`…) et retombe sur le nom simple de la classe
  (CLI : `CliCommandSupport.failureMessage`, API : `MinosApiSupport.failureMessage`) ou sur
  l'erreur générique `error: MINOS tool execution failed` (MCP : `MinosMcpTools.execute`, qui ne
  donne un message qu'à une `IllegalArgumentException` sans cause et non sensible, via
  `clientError`). D'où `index-status failed: ResolutionException` pour un chemin, alors que
  `unknown project: <nom>` sort pour un nom ordinaire. `MinosMcpErrorRedactionTest` épingle
  deux cas à préserver : `IOException` avec JDBC / mot de passe / chemin, et
  `IllegalArgumentException` avec `token=` / chemin (les deux donnent l'erreur générique). Les
  goldens `api.golden`, `cli-text.golden`, `cli-json.golden`, `mcp.golden` épinglent
  `unknown project: a2-missing-project` ; `RegistryToleranceCliTest` et
  `LocalProjectSymbolQueryTest` assertent aussi cette forme.
- **Statut MCP (C-02).** `MinosApplicationMcpBackend.projectStructure` et `indexStatus`
  appellent `providerProfiles()` → `ProviderPlatformService.listProviders()` →
  `runtimes.list()` (un `inspect` par provider). La CLI `index-status` n'ajoute pas ce champ.
  `ProviderConformanceKit.evaluate` est pur (descripteur et profil opérationnel déclarés).
  `MinosApplicationMcpBackendM17Test` (présence de `providerProfiles`, `scip-python`,
  `Python 3.10+`) et `ProviderProfilesKeyOrderTest` (neuf clés dans l'ordre, capacités triées)
  figent la forme du champ. Les goldens `mcp.golden` et `mcp-all-tools.golden` contiennent
  `providerProfiles` mais `CharacterizationNormalizer.normalizeProviderRuntimeHostFacts`
  remplace `runtimeState` et `runtimeDiagnostics` par `<host>` : une valeur d'état différente
  ne change pas le golden.
- **Statut sans découverte (C-03, D-02).** `ProjectInspectionService.view(RegisteredProject)`
  appelle `discoveryService.discover(root)` (langues, systèmes de build, nombre de modules)
  avant de lire l'état d'index (`reconciler.observeStatus`, `stateStore.listRuns`,
  historique). `ProjectJson.indexStatus` et `ProjectCommand.renderIndexStatus` n'utilisent
  aucun de ces trois faits. La découverte lève sur un répertoire illisible
  (`visitFileFailed`) ou au-delà du budget de traversée.
- **Défauts de contexte hybride (C-05).** Schéma MCP : `maxTokens` de 128 à 65 536,
  `maxTokensPerDocument` de 32 à 65 536, indépendants. `hybridContextDefaults` (backend MCP) et
  `IdeIntelligenceCommand.hybridContext` appliquent 800 par défaut sans tenir compte de
  `maxTokens` ; `HybridContextBuilder.ContextRequest` refuse `maxTokensPerDocument > maxTokens`.
  Les valeurs en échec sont donc 128 à 799 (la fiche C-05 écrit « 127 ≤ maxTokens ≤ 799 » : 127
  est refusé pour une autre raison, le minimum du schéma).
- **Ouverture de l'API (C-06).** `MinosApiSupport.openApplication` ne capte que `IOException` ;
  `MinosApplication.open` peut lever `IllegalStateException` (racine de composition absente ou
  ambiguë) et `IllegalArgumentException` (`unsupported storage backend: …`, valeur lue dans la
  configuration). Trois façades l'utilisent (`LocalMinosApi`, `LocalMinosMultiRepositoryApi`,
  `LocalProviderPlatformApi`).
- **`ide <op>` (C-16).** `IdeIntelligenceCommand.run` ne capte que
  `IllegalArgumentException | IllegalStateException | UnreadableRegistryException` ; un
  `IOException` remonte au lanceur, qui écrit `error: MINOS bootstrap failed: …`.
  `LazyApplication.OpenFailure` est une `RuntimeException` qui doit continuer de traverser.

## Goals / Non-Goals

**Goals:**
- Une seule politique d'écho de la référence de projet, portée par le résolveur et partagée par
  CLI, API Java et MCP.
- Un statut MCP qui ne peut pas écrire, hacher ni lancer de processus, sans retirer de champ.
- Un statut d'index dont la disponibilité ne dépend pas du système de fichiers du dépôt.

**Non-Goals:**
- Pas de nouveau `ErrorCode` public, pas de changement de catalogue ni de schéma MCP.
- Pas de rendu des langues / modules dans le statut, pas de changement de `project list` ni
  de `project inspect`.
- Pas de tolérance du parcours de découverte lui-même (autre changement), pas de décodage
  allégé du snapshot (D-03).
- Pas de nouvelle règle de redaction : `PublicErrorMessages.looksSensitive` est réutilisée
  telle quelle.
- Cas symétrique non traité : `maxTokensPerDocument` fourni sans `maxTokens` et supérieur au
  défaut de 4 000 reste refusé.

## Decisions

### D1. Message public d'une erreur de référence, porté par le résolveur (C-01)

`ProjectResolver.ResolutionException` gagne une méthode publique de message public (par exemple
`publicMessage()`) :

- `INVALID_PROJECT_REFERENCE` : message inchangé (constant, ne recopie jamais la valeur) ;
- `PROJECT_NOT_FOUND` et `PROJECT_REFERENCE_AMBIGUOUS` : message inchangé si la référence
  n'est pas jugée sensible par `PublicErrorMessages.looksSensitive` ; sinon un texte fixe qui
  nomme la cause, ne recopie pas la valeur et donne l'action (« unknown project reference (value
  not shown); pass a registered project name or UUID (see `minos project list`) » et son pendant
  pour l'ambiguïté). Ces textes fixes doivent eux-mêmes passer `looksSensitive` sans être jugés
  sensibles, car l'API les repasse par `sanitize` (test dédié).

Les trois surfaces consomment cette méthode, toutes en aval de `minos-application` :
CLI (`CliCommandSupport.failureMessage` : branche `ResolutionException` avant `sanitize`, ce qui
couvre aussi `reportingCause`, `GitActivityCommand`, `IdeIntelligenceCommand`…), API
(`MinosApiSupport.execute` : branche `ResolutionException` avant `IllegalArgumentException`,
même code `INVALID_REQUEST`, message public, sans cause), MCP (`MinosMcpTools.execute` : branche
`ResolutionException` avant `IllegalArgumentException`, texte `error: ` + message public).
Cela ne contourne pas `MinosMcpErrorRedactionTest` : seule cette exception typée reçoit un
message, toute autre `IllegalArgumentException` passe toujours par `clientError`.

*Alternatives écartées.* (A) Texte fixe sans écho pour toute référence inconnue (c'est la forme
suggérée par `docs/audit/constats.md`) : plus simple et sans heuristique, mais change `api.golden`,
`cli-text.golden`, `cli-json.golden`, `mcp.golden` et les tests de `RegistryToleranceCliTest` /
`LocalProjectSymbolQueryTest`, et prive l'utilisateur de l'écho d'une faute de frappe ; voir
question ouverte 2. (B) Un nouveau `ErrorCode` public : changement de contrat v1, écarté.
(C) Assouplir `looksSensitive` pour les noms de projet : affaiblit la politique centrale.

*Sûreté.* L'écho d'une valeur à son propre appelant ne révèle rien de nouveau ; la retenue vise
le journal, les transports et les copies tierces (S10). La liste noire reste heuristique : un
faux négatif écho une valeur que l'appelant a lui-même fournie, jamais une donnée interne.

### D2. Erreurs typées du MCP et journal (C-01)

`MinosMcpTools.execute` capte, avant le cas général :

- `ResolutionException` (D1) ;
- `UnreadableRegistryException` (message de dénombrement, sans chemin par construction) ;
- une exception typée nouvelle, locale à `minos-mcp` (par exemple un `McpClientFailure`
  package-private, à message fixe), levée par `MinosApplicationMcpBackend` pour le mode équipe
  désactivé et le jeton absent à la place des `IllegalStateException` / `SecurityException`
  génériques. Les littéraux `MINOS team mode is disabled` et `System.getenv("MINOS_TEAM_TOKEN")`
  restent dans le backend (gate `check-hosted-control-plane-consistency.py`) ; `MinosMcpTools` ne
  doit contenir ni `bearerToken` ni `effective.getMessage()`.

Le reste est inchangé (`GENERIC_TOOL_ERROR`, `clientError`, `RESULT_BUDGET_ERROR`).
`logInternalFailure` écrit en plus la classe de la cause racine (parcours borné, sans message).

### D3. Statut MCP passif et champ `providerProfiles` conservé (C-02)

`ProviderPlatformService` gagne une méthode de profils statiques (par exemple
`listStaticProfiles()`) qui construit les `ProviderView` à partir des fournisseurs et de
`ProviderConformanceKit` sans appeler `runtimes.list()` ni `runtimes.inspect(...)`, avec
l'état d'exécution `NOT_INSPECTED` et le diagnostic « runtime state is not inspected by the
read-only MCP status; run `minos providers` or `minos doctor` ». `MinosApplicationMcpBackend`
l'utilise dans `providerProfiles()` pour les deux outils. `listProviders()` (qui inspecte) reste
utilisé par `providers`, `doctor` et l'API des providers.

La forme du champ (neuf clés, ordre, capacités triées) est inchangée : `ProviderProfilesKeyOrderTest`
et `MinosApplicationMcpBackendM17Test` restent valides ; les goldens ne changent pas
(valeurs normalisées en `<host>`), à vérifier par `A2SurfaceCharacterizationTest` sans
`-Dminos.characterization.write=true`.

*Alternatives écartées.* (A) Retirer `providerProfiles` du statut (fiche C-02) : **rupture** d'un
champ publié alors que les surfaces sont additives ; écarté. (B) Méthode passive
`inspectReadOnly` sur `ProviderRuntimeManager` : change le port du moteur et son implémentation
(`ManagedScip…`, `CompositeProviderRuntimeManager`), pour un état « cheap » dont la fiabilité
(sans hachage, donc sans vérifier l'intégrité) serait trompeuse ; différé, question ouverte 1.
(C) Cache calculé hors MCP : état périmé présenté comme courant, contraire à la
capability-honesty.

### D4. Vue de statut sans découverte (C-03, D-02)

`ProjectInspectionService` sépare `view(RegisteredProject)` en deux : la lecture de l'état
d'index (réconciliation en mémoire, runs, historique : code existant, déplacé dans une méthode
privée partagée) et la découverte. Une vue de statut (par exemple `statusView`) calcule
`rootAvailable` par `Files.isDirectory`, ne découvre pas (langues et systèmes de build vides,
nombre de modules nul : ces champs ne sont pas lus par le statut) et lit l'état d'index ;
`view` conserve son comportement (découverte + état) pour `project inspect`, `project list`,
`project add`. Deux points d'entrée de statut, miroirs de l'existant : une inspection stricte
(MCP) et une inspection tolérante aux entrées de registre illisibles (CLI, Q24), toutes deux
déclarées sur `ProjectOperations` avec une implémentation par défaut qui délègue à
`inspection(...)` afin de ne pas casser les implémentations tierces. `ProjectCommand`
(`runIndexStatus`) et `MinosApplicationMcpBackend.indexStatus` les utilisent ;
`projectStructure` conserve la vue avec découverte. Aucun bail n'est pris, rien n'est écrit
(invariant d'ADR-0039 (l), `ProjectStatusReadIsLeaseFreeTest`).

*Alternative écartée.* Garder la découverte en l'avalant en cas d'échec : le statut paierait
toujours le parcours complet (D-02, coût) et la découverte serait lancée pour rien.

### D5. Plafond par document borné par `maxTokens` (C-05)

Le plafond par document par défaut devient `min(800, maxTokens effectif)`. Dans le backend MCP
(`hybridContextDefaults`) : `maxTokensPerDocument == null ? min(800, maxTokens effectif) : …`,
où le `maxTokens` effectif est celui du client ou le défaut de 4 000. Dans
`IdeIntelligenceCommand.hybridContext` : calculer `maxTokens` d'abord, puis passer
`min(defaults.maxTokensPerDocument(), maxTokens)` comme défaut de
`--max-tokens-per-document`. `HybridContextBuilder.ContextRequest` et son `defaults(...)` ne
changent pas (la validation reste le filet de sécurité). Le plugin conserve son propre
`Math.min(800, maxTokens)` (hors périmètre).

### D6. Traduction de l'ouverture de l'API (C-06)

`MinosApiSupport.openApplication` : `IOException` → `IO_FAILURE` (message de démarrage fourni,
inchangé), `IllegalArgumentException` → `INVALID_REQUEST`, `IllegalStateException` →
`UNAVAILABLE`, autre `RuntimeException` → `EXECUTION_FAILURE`, toutes par `publicFailure` (donc
redactées, sans cause). La fermeture de ce qui a été ouvert reste faite par
`MinosApplication.open`. Le test place la valeur fautive de configuration par propriété système
(`minos.storage.backend`), restaurée en `finally`.

### D7. Erreurs d'exécution de `ide <op>` (C-16)

`IdeIntelligenceCommand.run` : capter `IOException | RuntimeException` après avoir relancé
`LazyApplication.OpenFailure` (comme `CliCommandSupport.run`), message par
`CliCommandSupport.failureMessage` (donc D1), code `EXECUTION_ERROR`. Un échec d'ouverture de
`MINOS_HOME` reste « MINOS bootstrap failed ».

### Direction des dépendances (ADR-0022)

Aucune dépendance nouvelle. `ProjectResolver` (`minos-application`) dépend déjà de
`PublicErrorMessages` (`minos-engine`) ; `minos-cli`, `minos-api` et `minos-mcp` dépendent déjà de
`minos-application` et appellent la nouvelle méthode dans le sens autorisé
(`application -> surfaces`). L'exception typée du MCP reste dans `minos-mcp` ; `minos-cli` ne
dépend pas de `minos-mcp` (ADR-0044). Aucune exception à signaler.

### Qualification des capacités (capability-honesty)

| Capacité | Qualification visée | Remarque |
|---|---|---|
| Erreur de référence actionnable sans fuite | Qualifiée sur Windows et Linux (chaînes de caractères : chemins de lecteur, POSIX et UNC testés sur les deux OS) | la redaction reste une liste noire heuristique |
| Erreurs typées du MCP (registre, mode équipe, jeton) | Qualifiée | |
| Statut MCP sans effet de bord | Qualifiée par un faux gestionnaire de runtimes compteur et par un instantané de `MINOS_HOME` sur les deux OS | l'ouverture de l'application (R12) est hors de la garde |
| État d'exécution des runtimes dans le statut MCP | Non supportée : déclarée `NOT_INSPECTED` | l'état réel reste dans `providers` et `doctor` |
| Statut sans découverte | Qualifiée | ne couvre ni `project list` ni `project inspect` |
| Défauts de contexte hybride | Qualifiée | cas symétrique non traité |
| Ouverture classée de l'API Java | Qualifiée | |

### Windows et Linux

- Les effets de bord supprimés par D3 sont surtout Windows (script lanceur sous
  `%LOCALAPPDATA%\minos-launchers`, sonde AppContainer dans `MINOS_HOME/sandbox`) mais l'extraction
  et le hachage de l'arbre des outils valent sur les deux OS : la garde principale est le gestionnaire
  de runtimes compteur (neutre) plus un instantané de `MINOS_HOME` ; le test Windows vérifie en
  plus l'absence de répertoire de sonde.
- Test de répertoire illisible : `chmod 000` sur POSIX (`@EnabledOnOs(OS.LINUX)`) ; sous
  Windows, équivalent neutre par un service de découverte injecté qui lève et par un budget de
  traversée minimal (`SourceBudgetPolicy`), joués sur les deux OS.
- Les références de test de type chemin sont des chaînes (aucun accès disque) : le comportement est
  identique sur les deux OS. Les goldens de caractérisation ne doivent pas dépendre de l'outillage de
  l'hôte (aucun ajout).

## Risks / Trade-offs

- [Le sens du champ `runtimeState` change dans le statut MCP : un client qui testait `READY`
  voit `NOT_INSPECTED`] → champ et clés conservés, diagnostic qui renvoie vers `providers` /
  `doctor`, note dans `docs/user/mcp.md` à la tâche 4.1 ; question ouverte 1.
- [La liste noire de `looksSensitive` produit des faux positifs (nom de projet « api-key-… »)] →
  le texte retenu reste actionnable ; aucune modification de la politique.
- [`ProjectOperations` est un point d'extension : une méthode abstraite casserait les
  implémentations tierces] → méthodes par défaut qui délèguent à `inspection(...)`.
- [Coût de maintenance de deux vues (`view` et vue de statut)] → le calcul d'état d'index est
  partagé (une seule méthode privée), seule la découverte diffère.
- [Journalisation de la cause racine] → classes seulement, parcours borné ; test de non-fuite.
- [Renommage ou restructuration cassant un gate littéral] → tâches nommant les gates à rejouer
  (aucune chaîne assertée n'est supprimée).

## Migration Plan

Aucune migration de données. Déploiement par fusion dans `develop` ; retour arrière par revert
(aucun état persistant). Les clients du MCP qui lisaient l'état d'exécution des runtimes dans
le statut doivent utiliser `providers` / `doctor` : à annoncer dans `docs/user/mcp.md` (tâche
4.1).

## Questions ouvertes

Aucune ne bloque les tâches ; chacune est une décision de l'utilisateur.

1. **Contenu de `runtimeState` dans le statut MCP** : l'hypothèse de conception est `NOT_INSPECTED`
   (D3, option additive). Autres options : retirer le champ (rupture) ou ajouter une inspection
   passive de faits bon marché. L'invariant « aucun effet de bord » est identique dans tous les
   cas ; seule la valeur affirmée par un scénario de la spec change.
2. **Texte fixe sans écho pour toutes les références inconnues** (forme suggérée par
   `docs/audit/constats.md`) au lieu de la conservation du texte actuel pour les références non
   sensibles : coût = mise à jour de quatre goldens et de deux classes de tests.
3. **Garde de lecture seule sur l'ensemble des 31 outils MCP** (aucune garde équivalente à
   `LazyWiringGuardTest` n'existe pour le MCP) : utile, mais certains outils légitimement
   différents ; non planifiée ici.
4. **Cas symétrique du contexte hybride** (`maxTokensPerDocument` explicite supérieur au défaut de
   `maxTokens`) : relever le défaut de `maxTokens` ou garder le refus actionnable ?

## Écarts constatés à l'implémentation (2026-10-07)

Aucun ne change les exigences ; ils précisent D1 à D7. Le détail et les preuves sont dans la section « Évidence d'implémentation » de `tasks.md`.

- **D2** : le typage des échecs connus passe par une interface `McpClientFailure` et deux sous-classes des exceptions d'origine (`IllegalStateException`, `SecurityException`), pas par une `RuntimeException` unique, pour préserver le contrat du backend. Les cinq outils d'équipe, mode équipe désactivé, changent donc de message dans `mcp-all-tools.golden` (5 lignes régénérées et relues).
- **D3** : la valeur `NOT_INSPECTED` de `runtimeState` est l'**hypothèse de conception** de la question ouverte 1, implémentée telle quelle parce qu'elle est additive et honnête (aucun état n'est déduit sans inspection) ; elle reste une décision de l'utilisateur et se change à un seul endroit (`ProviderPlatformService.RUNTIME_NOT_INSPECTED`).
- **D4** : `ProjectOperations` ne reçoit que `statusInspection` (tolérant) ; le point d'entrée strict est `ProjectInspectionService.inspectStatus`, utilisé par le MCP.
- **D6** : `MinosApiSupport.openApplication` a une surcharge à ouvreur injectable pour tester les classements `UNAVAILABLE` et `EXECUTION_FAILURE`.
