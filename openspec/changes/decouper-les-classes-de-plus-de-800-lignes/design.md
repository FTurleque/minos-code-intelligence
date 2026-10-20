# Design

## Context

Constat du superviseur de code (fin de tour) sur deux fichiers de production de plus de 800 lignes. Le dépôt n'a pas de limite de taille : le seuil est celui du superviseur. Ce design tranche **quelles responsabilités secondaires se détachent proprement**, à quel prix pour les gates et pour la couverture, et ce qui reste volontairement en place.

`PRIVATE` désigne `minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java` ; `MANAGER` désigne `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/install/ManagedScipProviderRuntimeManager.java`.

## État vérifié au HEAD

### Base de lecture

`origin/develop` = `f0f6e535` (PR #510, migration de l'installation des outils SCIP vers les primitives). Les branches `refactor/complexite-points-superviseur` (`c4531427`) et `refactor/duplications-points-superviseur` (`ae8ff500`) ne sont **pas** dans `develop` (`git merge-base --is-ancestor` : non, pour les deux) et modifient `PRIVATE` et `MANAGER`. Les numéros de ligne ci-dessous sont ceux de la pile `develop` + ces deux branches, fusionnée dans une branche locale : le seul conflit est `MANAGER`, dans `acquireAndExtractZip`, résolu en gardant `moveFile` et `extractDistribution` de `develop` et la factorisation de la branche (`topLevelDirectory` devient un paramètre). `wc -l` : `PRIVATE` 1 031 lignes, `MANAGER` 1 019. Les deux fichiers sont à relire sur le `develop` réel (tâche 1.1).

### `PRIVATE` — ce qui se détache et ce qui reste

| Bloc | Lignes | Taille | Appelants | Couverture locale (Windows, `verify`) |
|---|---|---|---|---|
| `WindowsDacl` (lancement d'`icacls`, lecture SDDL) | `PRIVATE:801-924` | 124 | `CapabilityProbe.real()` (`:977`, `:982`) ; test `PrivateLocalStorageDaclReadTest` | 55/57 lignes, 35/46 branches |
| Diagnostic d'un refus d'écriture (DENY explicite) | `PRIVATE:619-696` | 78 | six appels internes (`:199`, `:209`, `:223`, `:241`, `:517`, `:585`) ; test `WriteDenyClassificationTest` | 27/29, 26/34 |
| Propriétaire du processus (sonde, cache) | `PRIVATE:416-479` | 64 | `ensurePrivateDirectory` (`:147`) ; cinq appels de `SandboxLauncherScript` à `verifyOwnedByCurrentUser` | 24/24, 10/12 |
| Crochet d'essai : interface `CapabilityProbe` (le `ThreadLocal` et `useForTesting` restent) | `PRIVATE:928-986` | 59 | quatre fichiers de test à retyper : `MinosApplicationTest` (`minos-bootstrap`, `:150`), `FakeCapabilityProbe`, `PrivateLocalStorageStreamsTest`, `PrivateLocalStorageWindowsAclTest` ; `DurableAtomicFileTest` et `PrivateLocalStorageTest` n'utilisent que `FakeCapabilityProbe` | 9/9 lignes, pas de branche |
| Garde des emplacements partagés (H23) | `PRIVATE:159-178` | 20 | `ensurePrivateDirectory` | 10/10, 16/16 |
| Cœur : création, durcissement POSIX et ACL, vérification, flux privés | le reste | ~686 | tous les modules | — |

Total local du fichier : 346/386 lignes (90 %), 167/212 branches (79 %). Sonar, sur la branche principale (analyse du 10 octobre, antérieure à plusieurs changements) : 289 lignes à couvrir, 79 non couvertes ; chiffre indicatif seulement.

- **`WindowsDacl`** n'a **aucune dépendance** vers le reste de la classe : elle lance un processus, lit un fichier SDDL et décide. Elle est déjà `static final` et de visibilité de package. C'est la coupe la plus propre.
- **Le diagnostic de refus** est fait de fonctions pures sur des entrées ACL, sauf la surcharge à chemins qui lit la vue ACL par `aclView(location)` (`PRIVATE:687`), donc par le `ThreadLocal` du crochet d'essai : l'extraction lui passe la vue en paramètre.
- **La sonde du propriétaire** n'utilise **pas** le crochet d'essai (`probeOwner` appelle `Files.createTempFile` directement, `PRIVATE:463`, `:469`) : aucune dépendance cachée.
- **La garde H23** est trop petite (20 lignes) pour justifier une classe et elle est intimement liée à `ensurePrivateDirectory` : **elle reste**.
- **Le cœur** reste dans la classe : y séparer la logique POSIX de la logique ACL demanderait de défaire `harden` et `PROTECTED_LOCATIONS`, qui partagent l'état par JVM ; le gain ne justifie pas le risque.

Estimation : retirer `WindowsDacl`, le diagnostic et la sonde (124 + 78 + 64 = −266 lignes, +5 de délégation) laisse **~770 lignes**, trop près de 800. Avec `CapabilityProbe` (−59), **~711**. Cible de la tâche 2.6 : ≤ 730.

### `MANAGER` — ce qui se détache et ce qui reste

| Bloc | Lignes | Taille | Appelants | Couverture locale (Windows, `verify`) |
|---|---|---|---|---|
| Classpath de scip-java : récupération, journal, vérification, installation, échange de répertoire (`scipJavaFetchCommand`, `fetchVerifiedClasspath`, `resolvedJars`, `jarList`, `installedClasspathVerifies`, `installClasspath`, `swapIn`, `DirectoryMover`, `restore`, `copyJar`) | `MANAGER:366-516` | 151 | `installJava` ; tests `ClasspathLockTest`, `ScipJavaClasspathInstallationTest`, `ManagedToolInstallationFilesTest`, `ManagedScipProviderRuntimeManager{,Runtime}Test` | 79/83 lignes (95 %), 25/30 branches |
| Extraction bornée d'archives (`extractZipBounded`, `extractDistribution`, `openExtractedFile`) | `MANAGER:869-928` | 60 | `acquireAndExtractZip`, `seedAssembledTree` ; tests `ManagedToolInstallationFilesTest`, `ManagedScipProviderRuntimeManagerRuntimeTest` | 30/30 lignes, 13/14 branches |
| Gestes d'installation : `run`, `moveFile`, `moveDirectory`, `writeNewFile`, `deleteRecursively` (aujourd'hui `private static`, sauf `run` : ils passent en visibilité de package) | `MANAGER:518-523`, `:984-1018` | 41 | tout le fichier ; tests `InstallationEnvironmentIsolationTest`, `ManagedRuntimeCommandRunnerTest` | 28/28 lignes, 6/8 branches |
| Acquisition épinglée de Coursier, Maven, Node (`ensureCoursier`, `ensureMaven`, `ensureNode`, `acquireAndExtractZip`, chemins d'exécutables) | `MANAGER:556-675`, `:930-934` | ~125 | `installJava`, `installTypeScript`, `seedComponents` | 8/71 lignes (11 %), 3/34 branches |
| Amorçage depuis la charge utile embarquée (`SeedOutcome`, `seedFromPayload`, `needsSeeding`, `shippedAndMissing`, `installed`, `seedComponents`, `seedAssembledTree`, `verifyInstalledEmbeddedTrees`, `embeddedTreeProblem`, `containsLink`, `originDiagnostic`) | `MANAGER:677-867` | 191 | `inspect*`, `install*` ; test `EmbeddedToolsSeedingTest` | 89/110 lignes (81 %), 60/79 branches |
| Inspection, installation, exécuteur, diagnostics, chemins | le reste | ~450 | `DefaultMinosApplicationComposer` (`new ManagedScipProviderRuntimeManager(home)`) | 124/189 lignes (66 %), 67/127 branches |

Total local de la classe (nom de classe et classes internes) : 358/511 lignes (70 %), 174/292 branches (60 %) ; `verify` de `minos-provider-scip` sous Windows, 387 tests, 13 ignorés, sur la pile fusionnée (ce qui valide aussi la résolution du conflit).

**Conséquence sur le plancher** (`check-jacoco.py`, classe 0,61 / 0,49) : retirer les trois blocs bien couverts de D3 (classpath, extraction, gestes : 137 lignes couvertes sur 141) laisse au manager 221/370 lignes (**59,7 %**) et 130/240 branches (**54,2 %**). Le plancher de ligne de 0,61 serait donc franchi **par le seul fait du découpage**, alors que rien n'a perdu de couverture : c'est l'objet de D6.

- **Extraction bornée d'archives** : sans état, deux appels bruts `Files.createDirectories` (`MANAGER:890`, `:893`) qui sont **exactement** le `max` 2 de l'entrée de l'allowlist. Coupe très propre.
- **Classpath** : presque entièrement statique ; les deux méthodes d'instance lisent chacune un chemin du manager : `installedClasspathVerifies` le listing (`scipJavaClasspathFile()`, `MANAGER:427`), `installClasspath` la racine (`scipJavaClasspathRoot()`, `:448`) ; les deux deviennent des paramètres. Le bloc utilise trois constantes privées du manager : `CLASSPATH_FILE` (`:467`, aussi lue par `scipJavaClasspathFile()` `:952`), `MAX_CLASSPATH_JAR_BYTES` (`:513`) et `MAX_INSTALL_LOG_LINE_CHARS` (`:393`). Les deux dernières partent avec le bloc ; `CLASSPATH_FILE` y est déplacée en visibilité de package et le manager la référence, pour ne laisser aucune dépendance de l'extrait vers le manager.
- **Gestes d'installation** : partagés par tous les blocs ci-dessus ; c'est ce qui rend les autres extractions possibles sans dépendance en retour vers le manager.
- **Acquisition** : dépend de `toolsRoot`, de `source()` et du registre d'origine (`origins`) ; propre à Windows pour Coursier et Node (`MANAGER:559-561` refuse hors Windows). Extractible comme objet portant ces trois collaborateurs, **mais couverte à 11 %** (8/71 lignes, 3/34 branches) : aucun test ne traverse le téléchargement, l'extraction et la publication de Coursier, Maven ou Node. La déplacer en ferait du code neuf à 11 % de couverture, très en dessous du seuil de 80 % de SonarCloud.
- **Amorçage** : le plus gros bloc (191 lignes), couvert à 81 % (89/110) par `EmbeddedToolsSeedingTest` grâce au crochet de plateforme `minos.tools.platform` (`EmbeddedToolsCatalog.currentPlatform`, `:210-211`), donc exécutable sous Linux. Mais **le plus couplé** : il appelle `ensureCoursier/Maven/Node`, `installJavaWindowsRuntime` et six accesseurs de chemins du manager.
- **`ManagedPolyglotScipRuntimeManager` et `ManagedScipPythonRuntimeManager`** ont leurs propres `run` et `moveDirectory` (`ManagedScipPythonRuntimeManager.java:253`, `:282`) : doublons constatés, non traités.

### Ce que les gates et les ratchets exigent de ces fichiers

| Garde | Ce qu'il lit | Effet de l'extraction |
|---|---|---|
| `scripts/architecture/check-private-io.py` + `private-io-allowlist.json` (27 entrées, somme des `max` = 60) | un maximum **par fichier et par méthode brute** | `PRIVATE` `Files.createTempFile` `max` 4 = `:221` + `:463` + `:469` + `:919` : 1 reste, 2 vont à la classe du propriétaire, 1 à `WindowsDacl`. `MANAGER` `Files.createDirectories` `max` 2 = `:890` + `:893` : l'entrée change de clé de fichier. Sans cela, le gate échoue deux fois : appel hors liste, puis entrée obsolète. Après : 29 entrées (deux clés de fichier nouvelles pour `PRIVATE`, une clé remplacée pour `MANAGER`), somme des `max` inchangée à 60. |
| `scripts/quality/check-jacoco.py` | préfixes de **noms de classes** (`startswith`) ; `confined-io-primitives` préfixe `com/minos/io/` (tout le package) ; `m24-polyglot-provider-platform` préfixe `.../install/ManagedScipProviderRuntimeManager` avec plancher de classe 0,61 / 0,49 | `com/minos/io/` couvre les classes nouvelles sans rien changer. Une classe nouvelle de `.../install/` **sort du scope m24** tant qu'elle n'y est pas déclarée, et le plancher du manager porte sur un code différent (voir D6). |
| `scripts/remediation/check-post-mne.py` (`:112`, `:224`, `:234`) | `MANAGER` doit contenir `BoundedInputStream`, `BoundedLineReader`, `FileTreeOperations.deleteRecursively` ; absence de `.sorted(Comparator.reverseOrder()).toList()` et `Files.readAllLines(log)` | les trois chaînes quittent `MANAGER` avec le classpath et les gestes ; le gate est repointé |
| `scripts/remediation/check-mnd.py` (`:60`, `:118`, `:122`) | `MANAGER` contient `MAX_COURSIER_ARCHIVE_BYTES`, `source().acquire(`, `coursierArtifact(), archivePartial, MAX_COURSIER_ARCHIVE_BYTES` ; n'a pas `BodyHandlers.ofFile(archivePartial)` | inchangé : l'acquisition reste dans `MANAGER` (D7) |
| `scripts/quality/check-tools-manifest.py` (`:39`, `:223`) | constantes `SCIP_JAVA_VERSION`, `SCIP_TYPESCRIPT_VERSION` de `MANAGER` | inchangé : elles restent publiques sur le manager |
| `scripts/remediation/check-p0-p2.py` (`:130`, `:139`) | `PRIVATE` contient `attributes.isOther()` | inchangé : `harden` et `requireType` restent |
| `quality/spotbugs-baseline/minos-engine.xml`, `minos-provider-scip.xml` (comparaison par `instanceHash`, qui dépend du nom de classe ; `code-audit.yml`, **hors checks exigés**) | `PRIVATE.currentOwner` (NP) ; `MANAGER.extractZipBounded` (NP), `inspectJavaInstalled` (ENV, devenue `addProjectJdkDiagnostics` sur la pile, `MANAGER:295`, par le refactor de complexité), `gitBashForGit` (NP) | la baseline est à **rafraîchir** après chaque déplacement (`.claude/rules/tests-java.md`), en vérifiant que seules ces alertes changent de classe, jamais à masquer |
| SonarCloud (seuil 80 % de couverture du nouveau code, par module) | lignes nouvelles du module | chaque classe nouvelle est entièrement « code neuf » : voir D5 |

Aucune autre occurrence des noms de membres déplacés n'a été trouvée dans `scripts/`, `.github/` ni `openspec/specs/`. Les pages `docs/architecture/arc42/06-vue-execution.md` et `docs/architecture/diagrams/seq-erreur-provider.md` citent `ManagedScipProviderRuntimeManager.inspect` et `install`, qui **restent** sur le manager.

### Recouvrements avec les changements actifs

`completer-les-primitives-d-e-s-privees`, `migrer-l-installation-des-outils-scip-vers-les-primitives`, `rendre-l-echec-de-scip-java-sous-windows-actionnable` et `verifier-le-proprietaire-des-dossiers-prives` nomment l'une ou l'autre classe. Leurs tâches restantes sont des mesures manuelles, des relectures de baseline SpotBugs et des mises à jour de suivi : **aucune ne modifie ces fichiers**.

Trois recouvrements réels :

- **`private-io-allowlist.json`** : une série de migration qui retire des entrées en même temps provoque un conflit de fusion, à résoudre en gardant les deux effets.
- **`figer-la-dette-de-frontieres-par-des-cliquets`** (0/14) : sa tâche 3.2 crée la règle A12 `PROCESS_LAUNCHERS` = `{PrivateLocalStorage}` (design `:61`), parce que `PrivateLocalStorage` est la seule classe du moteur à contenir `ProcessBuilder` (`icacls`). Après l'extraction, le `ProcessBuilder` est dans `WindowsDacl` (`PRIVATE:827`, `:865`) : la liste fermée doit devenir `{WindowsDacl}`. **Séquencement** : le second des deux changements à être appliqué adapte la liste ; la tâche 2.2 le fait si A12 existe déjà. Ce changement-là écarte l'extraction du mécanisme d'ACL comme « une conception » hors de son lot ; l'extraction faite ici reste **dans le package `com.minos.io`** (aucun des neuf modules qui nomment `PrivateLocalStorage` ne change) et ne la contredit pas, ce que `trancher-les-decisions-d-architecture-du-sprint-10` (point AUD-ARC-05) laisse aussi ouvert.
- **Lignes citées devenues fausses** : `executer-scip-java-sur-classpath-verifie` (cite `MANAGER:303-379` et `:226-249`) et `surveiller-les-outils-embarques` (cite `:88`) citent des lignes du manager que ce changement déplace ; ce ne sont pas des conflits d'écriture, mais ces citations sont à relire à leur archivage.

Les tests de garde de `minos-app` (`DuplicationGuardTest`, `JsonEscapeGuardTest`, `JsonSourceScanner`) parcourent les sources par `Files.walk` et ne citent ni `PrivateLocalStorage` ni le manager ; aucun test n'atteint `currentOwner` par réflexion ; aucun `ServiceLoader` n'est concerné. `check-module-boundaries.py` (A2 à A10) ne voit aucune arête nouvelle : mêmes package et module.

### Non mesuré

Couverture Sonar réelle des blocs (l'API ne donne rien pour une branche non principale de cette organisation) ; couverture **Linux** des blocs (les chiffres ci-dessus sont ceux d'un `verify` local sous Windows : `./mvnw -B -ntp -pl minos-engine,minos-provider-scip -am verify`, dont le module `minos-runtime-local` échoue sur ce poste — `powershell.exe` absent du `PATH` et tests d'AppContainer — puis `-Dtest="com/minos/adapter/scip/**/*"` pour `minos-provider-scip` seul) ; durée des suites de tests avant et après. Aucun code de production n'a été modifié pour rédiger ce design.

## Goals / Non-Goals

**Goals** : `PRIVATE` et `MANAGER` sous 800 lignes (cibles : ~711 et ~765) ; chaque classe issue du découpage a une seule raison de changer, ses tests dans son module et son entrée dans les gates ; zéro différence observable.

**Non-Goals** : un seuil de taille dans un gate ; un nouveau module ou package ; la migration des appels bruts vers les primitives ; l'unification des managers frères ; la réorganisation interne du cœur de `PRIVATE`.

## Decisions

### D1. La preuve d'un remaniement est une caractérisation ; les coutures ont un rouge

Aucun comportement ne change : les tests existants, verts avant et après, sont la preuve. Là où une couture est ajoutée pour couvrir sous Linux du code qui ne s'exécutait que sous Windows (D5), le test qui l'utilise est écrit d'abord et échoue (il ne compile pas) tant que la couture n'existe pas.

### D2. `PRIVATE` : quatre extractions dans `com.minos.io`

Toutes dans le package et le module actuels (ADR 0044) :

- **`WindowsDacl`** (visibilité de package, `final`, constructeur privé). `protect(Path)` délègue à une surcharge `protect(Path, String osName, String systemRoot, Launcher)` ; `Launcher` et `requireSuccess` suivent. Les messages d'erreur sont conservés au caractère près.
- **`WriteDenyDiagnosis`** : `carriesWriteDenyForOwner` (visibilité de package, utilisée par `WriteDenyClassificationTest`), `namesTheProcessUser`, `writeProtected`, `WRITE_RIGHTS`, `WRITE_PROTECTED_MESSAGE` et les deux formes d'`explainDenied` ; celle à chemins reçoit un `Function<Path, AclFileAttributeView>`. Le contrat « le message ne nomme jamais le chemin » est inchangé.
- **`ProcessOwner`** : `current(Path near)` avec son cache `volatile`, `probe(Path near)`, `unverifiable(IOException)` et `verify(Path)`. `PrivateLocalStorage.verifyOwnedByCurrentUser` reste publique et délègue ; son contrat Javadoc reste sur `PrivateLocalStorage`.
- **`CapabilityProbe`** : interface publique de premier niveau (voir D4).

`PrivateLocalStorage` garde le cœur, la garde H23, les flux privés, `ownerOnlyAcl`, `PROTECTED_LOCATIONS`, le `ThreadLocal` du crochet et les méthodes publiques `useForTesting`, `resetCapabilityProbeForTesting`.

### D3. `MANAGER` : trois extractions dans `…/runtime/install`

Toutes dans le package et le module actuels :

- **`BoundedZipExtraction`** : `extractZipBounded`, `extractDistribution`, `openExtractedFile`.
- **`ManagedToolInstallation`** : `run`, `moveFile`, `moveDirectory`, `writeNewFile`, `deleteRecursively`. `copyPackagedResource` **reste** sur le manager : elle charge une ressource par sa classe et trois tests l'appellent à cet endroit ; elle appelle `ManagedToolInstallation.writeNewFile` et `moveFile`.
- **`ScipJavaClasspathInstallation`** : tout le bloc classpath, dont `swapIn` et `DirectoryMover` (appelés par `ManagedToolInstallationFilesTest:179,194,206,224`) ; ses méthodes prennent la racine du classpath ou le listing en paramètre ; `CLASSPATH_FILE` y est déplacée (voir ci-dessus).

`list`, `inspect`, `install`, `executor`, les constantes publiques (`SCIP_*`), `SANDBOXED_EXECUTION_NOT_QUALIFIED` et les diagnostics de prérequis restent sur le manager. Les trois classes sont `final`, de visibilité de package, à constructeur privé ; ce sont des regroupements de fonctions, pas des surfaces au sens de l'ADR 0045.

### D4. `CapabilityProbe` devient un type de premier niveau (question Q3)

C'est le seul déplacement qui touche des fichiers d'un autre module (`MinosApplicationTest` dans `minos-bootstrap`) : un changement de nom de type dans des tests, pas dans la surface de production. Recommandation : le faire, parce qu'il ôte du code **de test** d'une classe **de production** et fait descendre `PRIVATE` à ~715 lignes. Sans lui : ~775, au-dessus de la marge utile.

### D5. La couverture : un déplacement est du code neuf, les coutures le couvrent sous Linux

SonarCloud juge le nouveau code module par module (80 %) : chaque ligne déplacée compte. Les tests existants restent dans le même module, donc la couverture suit. Deux précautions :

- `WindowsDacl.protect` n'avait de couverture que sous Windows (premier `return` sur le nom du système). La couture de la tâche 2.1 la fait exécuter aussi sous Linux.
- Sonar importe les rapports Linux **et** Windows (job `sonar` de `pr-ci.yml`), mais la fusion fichier par fichier n'a jamais été prouvée (réserve consignée à la PR #421). La tâche 3.6 la vérifie sur la première PR par l'API, fichier par fichier ; un module sous le seuil reçoit des tests, jamais une exclusion.

### D6. Planchers JaCoCo : la couverture agrégée ne baisse pas

Le gate raisonne par préfixe de nom de classe. Découper un fichier change le dénominateur de chaque classe : le manager, une fois ses blocs bien couverts partis, passe de 70 % à **59,7 %** de lignes sans qu'une seule ligne ait perdu sa couverture (voir ci-dessus), et franchirait son plancher de 0,61. Règle : on **additionne** les compteurs ligne et branche (couverts / total) des classes issues du découpage avant et après ; la somme après n'est pas inférieure à la somme avant (tâches 1.2, 2.6, 3.5). Chaque classe nouvelle de `…/install/` entre dans le scope `m24-polyglot-provider-platform` avec un plancher égal à sa mesure moins environ cinq points (convention AUD-TST-02) ; le plancher du manager est **recalculé sur ce qui lui reste** par la même convention, et la baisse apparente de son plancher est consignée avec le calcul qui montre que la somme des classes ne baisse pas. Un plancher n'est jamais abaissé sans ce calcul. `com/minos/io/` est déjà un préfixe de package : rien à déclarer pour `PRIVATE`.

### D7. Acquisition et amorçage de `MANAGER` : mesure faite, une exclusion et une option

Sans eux, `MANAGER` descend à **~765 lignes** (151 + 60 + 41 = −252 pour les trois extractions de D3, +8 de délégation, −10 d'imports) : sous 800, marge de 4 % (~35 lignes), que le prochain ajout de provider consomme.

- **L'acquisition épinglée de Coursier, Maven et Node (−125) n'entre pas** : couverte à 11 %, elle deviendrait du code neuf à 11 % dans un module jugé à 80 %. Elle fait l'objet d'un changement ultérieur qui commence par les tests (un `PinnedArtifactSource` factice servant une archive, sans réseau), puis déplace.
- **L'amorçage depuis la charge utile (−191) est une option** (Q2) : couvert à 81 % lignes et 76 % branches sous Windows, exécutable sous Linux par le crochet `minos.tools.platform`, il ramènerait `MANAGER` à **~575 lignes**. Mais il appelle l'acquisition et six accesseurs de chemins du manager : il faudrait lui passer ces gestes par une interface de package (une dizaine de méthodes) que le manager implémente, sans changer l'ordre des effets. Il n'entre dans ce changement que si la tâche 1.2 confirme au moins 80 % de lignes sur les rapports combinés Linux et Windows de `develop` ; sinon il rejoint l'acquisition.

### D8. Ordre et isolement des lots

Deux PR, une par module, indépendantes : `PRIVATE` (section 2) puis `MANAGER` (section 3), après la fusion préalable des deux branches `refactor/*`. Dans chaque PR, un commit par extraction : une régression se bissecte à la classe près.

## Direction des dépendances

Aucun import entre packages ajouté ni retiré ; toutes les classes nouvelles sont dans le package de leur classe d'origine, donc dans le même module. Pas de cycle de packages (règle A8 de `check-module-boundaries.py`), pas de dépendance Maven nouvelle, ADR 0022 et ADR 0044 respectés. Entre classes d'un même package, `ScipJavaClasspathInstallation`, `BoundedZipExtraction` et le manager dépendent de `ManagedToolInstallation`, jamais l'inverse.

## Qualification des capacités

Sans objet : aucune capacité ajoutée, modifiée ni requalifiée. `PrivateLocalStorage` garde ses niveaux de confidentialité (ADR 0061) et le manager ses états `READY`, `BLOCKED`, `NOT_INSTALLED` ; la mention « exécution sous AppContainer non qualifiée » (`SANDBOXED_EXECUTION_NOT_QUALIFIED`) reste sur le manager.

## Windows et Linux

`WindowsDacl` ne s'exécute qu'avec Windows ; sa couture (D5) la teste sous les deux systèmes. Le scope JaCoCo `m24-polyglot-provider-platform` est rouge hors régression sur poste Windows : comparer à `develop` avant d'attribuer un écart au découpage. Toute garantie vaut sur les deux systèmes : aucune branche `os.name` n'est ajoutée ni retirée.

## Risks / Trade-offs

- **Fail-closed** : l'ordre vérification → exécution du lancement d'`icacls` et le refus quand le SDDL est illisible ou contient une entrée conditionnelle de refus (`(XD;`) doivent rester identiques ; le relecteur de sécurité relit `WindowsDacl` et `ProcessOwner` avant la PR.
- **Régression silencieuse d'un message** : les messages sans chemin de `PRIVATE` sont affirmés par des tests (`IoRefusalMessageTest`, `WriteDenyClassificationTest`) ; ils doivent rester verts sans modification d'attente.
- **Entrées de l'allowlist** : oublier de déplacer une entrée fait échouer le gate deux fois ; l'ordre « classe d'accueil, entrée, gate » dans une même tâche l'évite.
- **Couverture Sonar** : voir D5 ; c'est le risque principal, car il ne se voit qu'une fois la PR ouverte.
- **Tests d'un autre module** : le retypage de `CapabilityProbe` (D4) touche `minos-bootstrap` ; rejouer `-pl minos-engine,minos-bootstrap -am`.
- **Conflit avec la série de migration des écritures brutes** : voir « Recouvrements » ; un conflit sur `private-io-allowlist.json` se résout en gardant les deux effets.
- **Gain** : si, à la lecture sur le `develop` réel (1.1), une extraction ne retire pas les lignes annoncées, le dire et ne pas la faire.

## Questions ouvertes (décisions du propriétaire)

- **Q1.** Ajouter un cliquet de taille de fichier (liste nominative, `max` par fichier) pour que le seuil de 800 lignes cesse d'être celui du seul superviseur ? Recommandation : non dans ce changement. L'audit du 10 octobre 2026 exclut les seuils bloquants ; si le propriétaire le veut, c'est un changement à part qui commence par mesurer tous les fichiers au-dessus du seuil.
- **Q2.** L'amorçage depuis la charge utile (D7, −191 lignes, 81 % de couverture mesurée) entre-t-il dans ce changement ou dans un suivant ? Recommandation : dans un suivant, avec l'acquisition. `MANAGER` à ~765 lignes est conforme au seuil, mais avec une marge mince (~35 lignes) : si le propriétaire tient à une marge confortable, l'amorçage est la seule coupe restante à ce prix. Elle est la plus couplée, et sa couverture, juste au-dessus de 80 %, laisse peu de marge à la PR.
- **Q3.** Extraire `CapabilityProbe` en type public de premier niveau (D4) ? Recommandation : oui.
