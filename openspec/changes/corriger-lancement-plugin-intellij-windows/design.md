# Design

## Context

Voir `proposal.md` (Why) pour la motivation. État du code au HEAD (`develop` @ bc1d3421), relu
pour ce changement :

- `MinosCliClient.run` résout le lanceur (`MinosExecutableResolver.resolve`), construit la
  commande par `MinosCommandLine.build(resolvedExecutable, arguments, osName)` puis la donne à
  `MinosStrongProcessLauncher.start`.
- Pour un `.cmd`/`.bat` sous Windows, `MinosCommandLine.build` produit
  `[cmd.exe, /d, /v:off, /s, /c, <chaîne rendue>]` où chaque jeton est entouré de `"` et où
  `quoteWindows` double `^` et `%` et échappe `& | < > "` par `^`.
- `MinosStrongProcessLauncher.writeWindowsPlan` encode chaque élément en Base64 dans un plan ;
  `windows-cli-job-owner-v1.ps1` le relit, le supprime, puis appelle `CreateProcessW` avec une
  ligne de commande reconstruite par `BuildCommandLine` / `QuoteArgument` (règles CRT : un
  argument qui contient `"` ou un espace est entouré de `"` et ses `"` deviennent `\"`).
- Résultat : `cmd.exe /d /v:off /s /c "\"C:\...\x.cmd\" \"a\""`. `cmd.exe` ne connaît pas `\"`
  et lit `\"C:\...\x.cmd\"` comme nom de commande. Reproduit à nouveau pour cette étude, hors
  dépôt, avec la même sérialisation et un vrai `.cmd` : exit 1, « '\"C:\...\x.cmd\"' n'est pas
  reconnu ». Le lanceur installé par la distribution Windows est lui-même un `.cmd` d'une ligne
  qui délègue à `app\minos.exe` (`scripts/release/build-windows-distribution.ps1`).
- Les gates `scripts/remediation/check-p0-p2.py` affirment, entre autres, les chaînes
  `MinosExecutableResolver.resolve(settings.executable, osName)`, l'interdiction de
  `MinosCommandLine.build(settings.executable`, `attributes.isOther()` et
  `@Service(Service.Level.APP)` : ces noms ne doivent pas bouger.

Écarts constatés par rapport à la fiche d'audit (`docs/audit/annexes/C-surfaces.md`) :

1. C-04, validation : le script de test proposé (`echo [%~2]`) est lui-même faux pour l'argument
   `a&b` (le `&` développé par `%~2` est relu comme séparateur de commandes : essayé hors
   dépôt, `cmd.exe` exécute `b]`). Le fixture doit copier chaque argument dans une variable
   entre guillemets (`set "A2=%~2"`) puis l'afficher avec l'expansion retardée.
2. C-04 : le contournement `minos.exe` est documenté dans `docs/user/mcp.md` et
   `docs/user/README.md`, pas dans `docs/user/intellij-plugin.md` comme la fiche le suggère.
3. C-08 : voir Questions ouvertes (la preuve centrale est inexacte).
4. C-09 : la « dérive de contrat » est en réalité conforme à l'ADR-0027 (« accepte uniquement
   `protocolVersion = "1"` »).

## Goals / Non-Goals

**Goals:**

- Un lanceur `.cmd`/`.bat` démarre sous Windows via la même frontière Job Object que `minos.exe`
  et reçoit ses arguments intacts ; un test démarre un vrai `.cmd`.
- Refuser plutôt que corrompre : un argument qu'un `.cmd` ne peut pas recevoir fidèlement est
  rejeté avant tout démarrage, avec un message actionnable.
- Aucune régression Linux et aucun changement de protocole ni de dépendance.

**Non-Goals:**

- Réécrire le script de lancement ou changer la mécanique Job Object / cgroup.
- Changer le comportement de délai, d'environnement, de handshake (questions ouvertes).
- Toucher au reactor Maven, à la CLI, au MCP, à l'API Java ou à NEXUS.

## Decisions

### D1 — Mode « ligne cmd brute » explicite dans le plan

Quand la commande est exactement `[cmd.exe, /d, /v:off, /s, /c, <chaîne>]`,
`writeWindowsPlan` ajoute au plan une clé de mode (par exemple `command.mode=cmd-c`) ; en
l'absence de clé, le comportement actuel (`argv` sérialisé CRT) est conservé. Le script, pour ce
mode, **vérifie strictement la forme** (6 éléments, commutateurs attendus, premier élément =
`cmd.exe` du répertoire système déjà résolu par Java) puis construit
`"<cmd.exe>" /d /v:off /s /c "<chaîne>"` sans appliquer `QuoteArgument` à la chaîne ; toute
autre forme avec ce mode lève une erreur (échec fermé, exit non nul, aucun démarrage).
La détection de la forme est partagée (une méthode de `MinosCommandLine` utilisée par le
lanceur) afin que le producteur et le consommateur ne divergent pas.

Alternatives écartées : (a) deviner côté script que « le dernier argument de cmd.exe /c est
brut » : implicite et applicable à tout `cmd.exe` ; (b) lancer le `.cmd` directement avec
`CreateProcessW` : délègue à `cmd.exe` un échappement partiel, source connue d'injections ;
(c) corriger seulement `quoteWindows` pour produire du texte déjà CRT-compatible : impossible,
`cmd.exe /s /c` n'a pas la même grammaire que le CRT.

Le script est réinstallé depuis le jar à chaque appel : le script et le code Java qui
l'alimentent ne peuvent pas diverger entre deux versions du plugin.

### D2 — `quoteWindows` ne modifie plus l'argument, il le valide

Chaque jeton est entouré de `"` sans autre transformation. Dans ce rendu, `& | < > ^` sont
littéraux (essayé hors dépôt : `simple`, `a&b`, `x y|<>^` arrivent intacts à un `.cmd` avec
`/d /v:off /s /c`). Sont **refusés** (`IllegalArgumentException`, message d'action) le guillemet
`"` (rompt l'équilibre des guillemets de `/s`) et `%` (développé par `cmd.exe` même entre
guillemets : `%FOO%` est remplacé par la valeur de la variable ; un `%` isolé passe mais n'est
pas distinguable sans analyser l'environnement). La règle s'applique à l'exécutable comme aux
arguments. Les caractères de contrôle restent refusés comme aujourd'hui.

Alternative écartée : conserver un échappement par `^`/`%%` : faux hors d'un contexte `.bat`
(`%%` reste `%%` en ligne de commande) et sans effet entre guillemets.

### D3 — Test qui lance un vrai `.cmd`

`MinosCommandLineBatchLaunchTest` (`@EnabledOnOs(OS.WINDOWS)`, package
`com.minos.intellij.protocol` pour accéder aux classes à visibilité package) : `@TempDir` dont un
sous-dossier contient un espace ; fixture `.cmd` selon la remarque 1 ; commande construite par
`MinosCommandLine.build(…, "Windows 11")`, démarrée par `MinosStrongProcessLauncher.start`,
supervisée par `MinosProcessSupervisor`. Rouge avant correctif, vert après. Les cas de refus
sont des tests purs dans `MinosCommandLineTest` (le paramètre `osName` permet de les exécuter
sous Linux).

### D4 — Qualification de la capacité `client-intellij` (règle de design)

| Périmètre | Qualification | Preuve attendue |
|---|---|---|
| Lancement d'un `.cmd`/`.bat` sous Windows (nominal et refus) | **qualifiée** pour le périmètre testé, une fois les tests verts sur `windows-2022` | `MinosCommandLineBatchLaunchTest` |
| Lancement natif (`minos.exe`, `minos`) | qualifiée (tests existants) | `MinosCommandLineTest`, `MinosStrongProcessLauncherTest` |
| Plugin exécuté dans une vraie IDE sous Windows | **non qualifiée** : aucun runner n'exécute l'IDE | aucune ; à ne pas présumer |
| `.bat` tiers activant l'expansion retardée en interne | **non supportée** : un `!` dans un argument peut y être altéré | documenté |
| macOS | **non supportée** (le lanceur de propriété échoue fermé) | comportement existant |

### D5 — Direction des dépendances (ADR-0022) et surfaces

Aucun module du reactor n'est touché. Le plugin reste hors reactor, Java 21, sans dépendance
`com.minos:*` (ADR-0027 respecté, aucune exception à expliquer). Aucune surface versionnée
(CLI, API Java, MCP) ne change.

## Windows et Linux

- Windows : seul chemin modifié (`.cmd`/`.bat` ; le script `.ps1`, le plan, `cmd.exe`
  canonique du répertoire système). Les lanceurs natifs gardent la sérialisation CRT.
- Linux : `MinosCommandLine.build` retourne déjà `[exécutable, arguments…]` ; la propriété de
  processus passe par une portée systemd utilisateur et échoue fermée si elle est indisponible
  (comportement existant, non modifié). Les tests purs du changement s'exécutent aussi sous
  Linux (job `plugin` d'Ubuntu 24.04) ; le test de lancement réel ne tourne que sous Windows.

## Risks / Trade-offs

- [Un utilisateur du lanceur `.cmd` ne peut plus passer `%` ou `"` dans une recherche
  sémantique] → refus explicite plutôt que corruption silencieuse ; contournement : configurer
  `app\minos.exe` ; question ouverte Q2 sur la résolution automatique du voisin natif.
- [Un `.bat` tiers qui active l'expansion retardée altère `!`] → documenté comme non supporté
  (D4) ; le `minos.cmd` livré ne l'active pas.
- [Le script ne peut être testé que sous Windows] → le test réel est dans le job
  `windows-ownership` ; la forme stricte du mode `cmd-c` limite la surface en cas d'erreur.
- [Fixture de test fragile aux métacaractères] → remarque 1 : copie en variable quotée.

## Migration Plan

Aucune migration de données. Le plan est un fichier temporaire supprimé avant chaque exécution
de la CLI ; le script est réécrit à chaque appel. Retour arrière : revert du commit
(plan sans clé de mode = comportement actuel).

## Open Questions

Aucune ne bloque les tâches de ce changement ; chacune attend l'utilisateur.

- **Q1 — C-07, délais des commandes longues (DÉCISION À CLARIFIER, P2).** Preuves relues, elles
  tiennent : `MinosSettingsState` (défaut 30 s, bornes 1 à 300 s, valeur hors bornes ramenée à
  30), `MinosCliClient.run` (échéance unique puis `supervisor.stop(null)` pour toute commande),
  appelants `MinosToolWindowPanel.runIndex` (Index, Reindex Full, Plan) et
  `MinosM21Client.synchronizeSemanticIndex` ; la documentation décrit ce comportement
  (30 s, « tue un processus dépassant ce délai »). Une indexation réelle peut dépasser 30 s ; le
  plugin tue alors l'arbre de processus en cours de run (reprise : ADR-0039). Options : (A) table
  de délais par classe de commande (lecture courte, indexation/synchronisation longue) ;
  (B) pas de délai pour les commandes longues, annulation par la barre de progression
  (`ProgressManager.checkCanceled`, déjà présent) ; (C) conserver et documenter. Choisir change
  le schéma des réglages persistés (champ additif) et la documentation utilisateur. À noter pour
  la mise en oeuvre : `MinosCliClient.run` dépend de services IntelliJ (projet, réglages), donc
  le test proposé par la fiche (`MinosCliClientTimeoutTest`) exige d'extraire d'abord une
  politique de délai pure et testable.
- **Q2 — C-04, politique `%` et lanceur voisin.** Faut-il, en plus du refus, résoudre
  `minos.cmd` vers le `minos.exe` voisin (`<installation>\app\minos.exe`) quand il existe ? Cela
  supprime la limite des arguments pour l'installation standard mais change le binaire réellement
  lancé (identité, ADR-0027 « CLI locale ») ; et faut-il accepter un `%` isolé ?
- **Q3 — C-08, environnement du plan Windows (RISQUE, fiche inexacte).** La fiche affirme qu'un
  secret d'environnement « survit sur disque pendant la durée du processus et jusqu'à 24 h ».
  Au HEAD, `windows-cli-job-owner-v1.ps1` lit le plan puis le supprime
  (`Remove-Item -LiteralPath $Plan`) **avant** de démarrer la CLI, et le test
  `ownershipPlanIsDeletedBeforeOwnedChildCompletes` (`MinosStrongProcessLauncherTest`) le
  vérifie avec un secret factice. Ne restent que : la fenêtre entre l'écriture du plan et sa
  suppression par PowerShell (plan en ACL propriétaire seul), et les cas d'échec avant lecture
  (jusqu'à `STALE_PLAN_AGE`, purgé au lancement suivant). L'exigence « liste blanche, rien sur
  disque après lancement » n'est donc pas retenue. À décider : veut-on tout de même transmettre
  une liste blanche (quelles variables : `MINOS_*`, `PATH`, `SystemRoot`, jetons d'équipe ou de
  fournisseur dont la CLI a besoin ?) ; le risque de régression fonctionnelle est réel et la
  référence « S7/S15/S16 » de la fiche n'a pas été relue ici. La concurrence de remplacement du
  `.ps1` est à moitié couverte : l'installation est déjà sérialisée dans le processus par un
  verrou statique (`WINDOWS_INSTALL_LOCK`), mais un remplacement par une autre instance d'IDE
  pendant l'exécution du script n'est pas vérifié.
- **Q4 — C-09, handshake répété et fenêtre de versions (P3).** `MinosM21Client.execute` lance
  un handshake (un processus JVM) avant chaque action pour lire les capacités, puis la
  commande : deux processus par action. Mémoriser les capacités ne change aucun contrat. En
  revanche accepter `protocolVersion` dans `[minCompatibleVersion, maxCompatibleVersion]` contredit
  la section « Compatibilité » de l'ADR-0027 (Accepted) : cela exigerait un amendement ou un
  nouvel ADR à proposer (statut Proposed), à la main de l'utilisateur.
