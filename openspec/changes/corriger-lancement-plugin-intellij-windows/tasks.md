# Tasks

Conventions de ce fichier : chaque tâche porte entre crochets le constat d'audit puis le nom
exact de l'exigence de `specs/client-intellij/spec.md` qu'elle couvre.

Commandes de test du plugin. Il n'y a pas de wrapper `gradlew` local : `minos-intellij` est un
build Gradle hors reactor. La CI (`.github/workflows/intellij-plugin.yml`, Gradle 9.6.1,
Java 21) exécute, depuis le dossier `minos-intellij`, `gradle --no-daemon test` (job
`windows-ownership` sous `windows-2022`, et `gradle --no-daemon test buildPlugin
verifyPluginProjectConfiguration verifyPluginStructure` dans le job `plugin` sous
`ubuntu-24.04`). Pour une seule classe, la même commande reçoit le filtre standard Gradle :
`gradle --no-daemon test --tests "com.minos.intellij.protocol.<Classe>"`.
Aucun scope JaCoCo ne couvre `minos-intellij` (aucune référence dans
`scripts/quality/check-jacoco.py`) et aucune tâche ne renomme ou ne déplace de code : seul un
rejeu du gate littéral `scripts/remediation/check-p0-p2.py` est requis.

## 1. Lancement d'un lanceur par lots sous Windows

- [x] 1.1 [MINOS-AUD-C04 | Un lanceur par lots démarre sous Windows et reçoit ses arguments intacts] Écrire d'abord le test rouge `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosCommandLineBatchLaunchTest.java` (`@EnabledOnOs(OS.WINDOWS)`, package `com.minos.intellij.protocol`) : `@TempDir` avec un sous-dossier contenant un espace, fixture `.cmd` qui copie chaque argument dans une variable entre guillemets avant de l'afficher (voir design, remarque 1 : ne pas utiliser `echo [%~2]`), commande construite par `MinosCommandLine.build(cmd, List.of("simple", "a&b", "x y|<>^"), "Windows 11")`, démarrée par `MinosStrongProcessLauncher.start` et supervisée par `MinosProcessSupervisor` ; asserter les sorties `[simple]`, `[a&b]`, `[x y|<>^]` et le code de sortie 0, plus un second cas (code de sortie 3 restitué). Vérification : `cd minos-intellij && gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCommandLineBatchLaunchTest"` ÉCHOUE avant le correctif (stderr « n'est pas reconnu », exit 1) ; capturer cet échec dans la description de la PR.
- [x] 1.2 [MINOS-AUD-C04 | Un lanceur par lots démarre sous Windows et reçoit ses arguments intacts] Implémenter le mode « ligne cmd brute » (design D1) : `MinosStrongProcessLauncher.writeWindowsPlan` écrit la clé de mode quand la commande a la forme `[cmd.exe, /d, /v:off, /s, /c, chaîne]` (détection partagée via une méthode de `MinosCommandLine`) ; `windows-cli-job-owner-v1.ps1` (`BuildCommandLine` / `Read-Plan`) construit alors `"<cmd.exe>" /d /v:off /s /c "<chaîne>"` sans `QuoteArgument` et refuse toute autre forme (erreur, exit non nul). Ne pas renommer `MinosExecutableResolver.resolve(settings.executable, osName)`, ni retirer `attributes.isOther()`. Vérification : le test de 1.1 passe (`gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCommandLineBatchLaunchTest"`) ; ajouter dans `MinosStrongProcessLauncherTest` (`@EnabledOnOs(OS.WINDOWS)`) un cas où un plan portant la clé de mode et une forme invalide est refusé sans démarrer de processus ; puis `gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosStrongProcessLauncherTest"` reste vert (dont `ownershipPlanIsDeletedBeforeOwnedChildCompletes`).
- [x] 1.3 [MINOS-AUD-C04 | Les arguments qu'un lanceur par lots ne peut pas recevoir sont refusés] Modifier `MinosCommandLine.quoteWindows` (design D2) : entourer de `"` sans doubler `^`/`%` ni échapper `&|<>` ; lever `IllegalArgumentException` (message nommant l'argument et recommandant `minos.exe`) pour `"` et `%`, y compris dans le chemin du lanceur. Vérification dans la même tâche, tests purs ajoutés à `MinosCommandLineTest` (valides sous Linux grâce au paramètre `osName`) : `say "hi"`, `%PATH%`, `50% off` refusés pour un `.cmd` avec `"Windows 11"` ; `a&b` et `x y|<>^` rendus sans transformation ; saut de ligne toujours refusé ; l'assertion existante `routesWindowsCmdLaunchersThroughCmdExeWithDelayedExpansionDisabled` reste vraie. Commande : `gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCommandLineTest"`.
- [x] 1.4 [MINOS-AUD-C04 | Le lancement par lots conserve la frontière de propriété du processus] Ajouter à `MinosCommandLineBatchLaunchTest` un cas Windows : un `.cmd` qui démarre un processus enfant de longue durée publiant son PID, puis `MinosProcessSupervisor.stop(null)` ; asserter que le `.cmd` et l'enfant sont morts (attente bornée) et qu'aucun `cli-*.plan` ne subsiste dans `<home>/intellij/process-ownership`. Le scénario « Indisponibilité de la frontière de propriété » est couvert par le test existant `configuredOwnershipHomeRejectsAWindowsJunctionBeforeAclMutation` (`MinosStrongProcessLauncherTest`), qui refuse un répertoire de propriété non sûr avant tout démarrage quel que soit le lanceur ; ne pas le dupliquer. Vérification : `gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCommandLineBatchLaunchTest"` sous `windows-2022`.
- [x] 1.5 [MINOS-AUD-C04 | Les lanceurs natifs ne passent pas par un interpréteur de commandes] Compléter `MinosCommandLineTest` : pour `minos.exe` avec `"Windows 11"` et pour `minos` avec `"Linux"`, des arguments contenant `"` et `%` donnent `[exécutable, arguments…]` inchangés, sans `cmd.exe`. Vérification : `gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCommandLineTest"` (Windows et Linux).
- [x] 1.6 [MINOS-AUD-C04 | Les arguments qu'un lanceur par lots ne peut pas recevoir sont refusés] Mettre à jour `docs/user/intellij-plugin.md` (section Dépannage, `Cannot start MINOS executable` et paragraphe « Exécution en arrière-plan ») : arguments `"` et `%` refusés avec un lanceur `.cmd`, contournement `<installation>\app\minos.exe` (même recommandation que `docs/user/mcp.md`), `.bat` tiers à expansion retardée non supporté. Relire chaque affirmation contre le code (`MinosCommandLine`). Vérification : `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check` réussissent ; la ligne « tue un processus dépassant ce délai » n'est pas modifiée (Q1 ouverte).

## 2. Non-régression et invariants du plugin

- [x] 2.1 [MINOS-AUD-C04 | Le plugin reste un client externe du protocole CLI inchangé] Vérifier que le plugin n'ajoute aucune dépendance `com.minos:*` (`grep --line-number --fixed-strings 'implementation("com.minos:' minos-intellij` ne renvoie rien : même garde que l'étape « Verify no MINOS engine dependency leaks into plugin » de `.github/workflows/intellij-plugin.yml`) et que les arguments du protocole sont inchangés (`MinosProtocolHandshakeTest` : `acceptsProtocolV1WithCapabilities`, `rejectsIncompatibleProtocolVersion`, `rejectsHandshakeWithoutCapabilities`). Rejouer `python scripts/remediation/check-p0-p2.py` (chaînes littérales sur `MinosCliClient.java`, `MinosExecutableResolver.java`, `MinosStrongProcessLauncher.java`, `MinosSettingsState.java`). Vérification : sortie « P0-P2 AUDIT REMEDIATION CONSISTENCY SUCCESS ».
- [ ] 2.2 [MINOS-AUD-C04 | Un lanceur par lots démarre sous Windows et reçoit ses arguments intacts] Confirmer la correction par la CI : `gradle --no-daemon test` vert dans `windows-ownership` (`windows-2022`) et `gradle --no-daemon test buildPlugin verifyPluginProjectConfiguration verifyPluginStructure` vert dans le job `plugin` (`ubuntu-24.04`) pour le commit candidat ; la PR indique que la correction n'a pas été exécutée dans une IDE réelle (qualification « partielle », design D4).

## Tâches différées, hors périmètre bloquant

Ces éléments ne sont ni cochables ni planifiés tant que l'utilisateur n'a pas tranché les
questions ouvertes du design ; ils sont listés pour la traçabilité constat → tâche.

- [MINOS-AUD-C07, P2, DÉCISION À CLARIFIER] Délais par classe de commande : attend Q1. Si l'option A est retenue, écrire d'abord le test rouge (exécutable factice qui dort quelques secondes, délai « lecture » à 1 s échoue, délai « long » aboutit) après extraction d'une politique de délai pure ; commande `gradle --no-daemon test --tests "com.minos.intellij.protocol.MinosCliClientTimeoutTest"` (classe à créer).
- [MINOS-AUD-C08, P2] Liste blanche d'environnement du plan Windows et emplacement du script : attend Q3 ; la fiche est inexacte sur la persistance du secret (plan supprimé avant le démarrage de la CLI, test existant `ownershipPlanIsDeletedBeforeOwnedChildCompletes`).
- [MINOS-AUD-C09, P3] Mémoriser les capacités du handshake pour les actions M21 (test cible `MinosM21ClientTest` avec un faux client comptant les handshakes sur trois actions, ce qui exige d'abord de rendre `MinosCliClient` substituable) ; l'acceptation d'une fenêtre de versions exige un amendement de l'ADR-0027 ou un nouvel ADR à proposer (Proposed) : attend Q4.

## Workflow follow-up

- Archiver le changement après revue ; l'archivage crée la spec `client-intellij` à partir du delta.
- Mettre à jour `docs/STATUS.md` et `docs/ROADMAP.md` seulement si le contenu qualifié du plugin y est décrit ; aucun ADR à mettre à jour.

## Évidence d'implémentation (2026-10-07)

Branche locale `fix/audit-2026-10-plugin-intellij-windows` (créée depuis la branche de la PR #353). Le détail des écarts avec le design est dans « Écarts constatés à l'implémentation » de `design.md`.

**Comment les tests ont tourné ici.** Il n'y a ni `gradle` ni `gradlew` locaux, et le plugin Gradle d'IntelliJ téléchargerait l'IDE entier : le build Gradle du plugin n'a **pas** été exécuté. Les classes de la tranche concernée (`MinosCommandLine`, `MinosStrongProcessLauncher`, `MinosProcessSupervisor`, `MinosProtocolHandshake`) sont du Java pur, sauf deux appels à la plateforme IntelliJ dans le superviseur. Les tests ont donc été compilés avec `javac --release 21` et exécutés avec le lanceur JUnit 6.1.3 du dépôt Maven local, sur ce poste Windows, avec deux substituts jetables (`OSProcessUtil.killProcessTree`, `ProcessCanceledException`) et un `MinosCliClient` réduit à ses deux constantes de protocole extraites du vrai fichier. Ce harnais est dans le scratchpad de la session et **n'est pas dans le dépôt**.

| Tâche | Rouge d'abord | Tests verts (harnais local, Windows) |
|---|---|---|
| 1.1 | `MinosCommandLineBatchLaunchTest` : **3 tests rouges au HEAD sur un vrai `.cmd`**, avec le message exact de l'audit : `'\"C:\...\echo-arguments.cmd\"' n'est pas reconnu en tant que commande interne ou externe`, code 1 | voir 1.2 |
| 1.2 | idem | `MinosCommandLineBatchLaunchTest` (3) : arguments `simple`, `a&b`, `x y\|<>^` reçus intacts dans un dossier à espace, code 3 restitué ; `MinosStrongProcessLauncherTest` (8, dont `ownershipPlanIsDeletedBeforeOwnedChildCompletes`, 1 ignoré, propre à Linux) |
| 1.3 | tests écrits avec le correctif | `MinosCommandLineTest` : 11 tests (3 existants + 8) : `say "hi"`, `%PATH%`, `50% off` refusés avec l'argument nommé et `minos.exe` recommandé, `a&b` et `x y\|<>^` rendus sans transformation, chemin de lanceur refusé, argument long tronqué, caractères de contrôle toujours refusés |
| 1.4 | **rouge au HEAD** (le `.cmd` ne démarre pas, donc l'enfant n'existe jamais) | `.cmd` qui démarre un enfant PowerShell de longue durée : `stop(null)` termine le `.cmd` et l'enfant, aucun `cli-*.plan` ne subsiste |
| 1.5 | — | `minos.exe` (Windows) et `minos` (Linux) : `"` et `%` transmis tels quels, sans `cmd.exe` |
| 1.6 | `check-current-docs.py`, `product-facts.py --check` | `docs/user/intellij-plugin.md` |
| 2.1 | — | `MinosProtocolHandshakeTest` (3), garde de dépendance `implementation("com.minos:` sans résultat, `check-p0-p2.py` vert |

**Refus par le script (1.2).** `aPlanCarryingTheRawCmdModeWithAnyOtherShapeIsRefusedBeforeAnyProcessStarts` fabrique à la main des plans qui portent `command.mode=cmd-c` avec une autre forme (3 éléments, exécutable autre que le `cmd.exe` système, commutateur `/v:on`, chaîne vide) et un mode inconnu : le script échoue à chaque fois sans créer de processus, et un **témoin** avec la forme exacte et le même environnement démarre bien (sans ce témoin, un plan mal formé aurait pu passer pour un refus).

**Autres vérifications.** Tests voisins du superviseur (`MinosProcessSupervisorTest`, `...OrphanTest`, `...HostProtectionTest`, `MinosStrongProcessLauncherProvenanceTest`) : 12 exécutés, 5 ignorés, 0 échec. 26 scripts `check-*.py` verts ; le reactor Maven n'est pas modifié (aucun `verify` à rejouer pour ce changement).

**Non fait, d'où la case 2.2 laissée décochée** : (a) le build Gradle du plugin et la CI (`windows-ownership` sous `windows-2022`, job `plugin` sous `ubuntu-24.04`) ; (b) `MinosCliClientTest`, `MinosM21ClientTest`, `MinosProjectListTest`, `MinosExecutableResolverTest` et la compilation de `MinosCliClient`, qui dépendent de la plateforme IntelliJ : ils n'ont pas tourné ; la signature de `MinosCommandLine.build` n'a pas changé ; (c) le plugin n'a pas été exécuté dans une vraie IDE (qualification « partielle », design D4) ; (d) les cas Linux des tests purs n'ont tourné que sous Windows (ils ne dépendent que du paramètre `osName`).

**Décisions qui restent à l'utilisateur** : Q1 (délais, C07), Q2 (résolution automatique du `minos.exe` voisin, `%` isolé), Q3 (liste blanche d'environnement, C08), Q4 (handshake répété, fenêtre de versions, C09).
