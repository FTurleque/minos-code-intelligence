# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-07**. HEAD analysé : `2d1cc214` (`develop`). `APP` = `minos-application/src/main/java/com/minos`.

## État vérifié au HEAD

### AUD-PERF-07 — confirmé à la lecture, **non mesuré**

- `APP/program/analysis/ProgramGraphService.java:115` : `providerKey(project, snapshot)` ; `:122` : `cache.get(key)`.
- `FingerprintConstrainedJavaProgramGraphProvider.java:61-63` : sans empreinte exacte, délégation à `delegate.cacheKey`.
- `JavaProgramGraphEngine.java:25-31` puis `JavaSourceWorkspace.java:97-119` : `stateFingerprint` calcule un SHA-256 de chaque source Java.
- `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:200-208` : `fingerprintStore.promote` seulement si l'arbre est resté stable pendant le run.
- La statistique `cacheStats().providerKeyNanos()` existe déjà.

**Non mesuré** : la fréquence réelle des snapshots sans empreinte, et le temps de calcul de la clé sur un dépôt réel. La confiance du constat est « plausible ». La tâche 1.1 le mesure avant toute modification ; si le coût est négligeable, le changement se réduit à documenter la mesure. **Mesuré le 2026-10-13 : confiance requalifiée « confirmé »** (voir « Mesures du 2026-10-13 »).

### Écarts avec l'action proposée par l'audit

1. **La clé calculée avant le cache est une garde, pas une maladresse.** Sans empreinte du snapshot, hacher les sources est le **seul** moyen qu'a le service de savoir que le graphe en cache correspond encore aux fichiers. « Chercher d'abord en cache » sans autre précaution servirait un graphe périmé.
2. **L'invalidation « taille + date de modification » proposée par l'audit est une heuristique déjà rejetée dans ce dépôt** (`LocalSourceReader.java:133-135` : un fichier peut changer sans changer de taille ni, selon le système de fichiers, de date observable). L'adopter ici contredirait une décision écrite.
3. **L'autre option de l'audit (limitation `PROGRAM_GRAPH_FINGERPRINT_UNAVAILABLE` qui désactive l'analyse Java) change une sortie publique** : elle doit être additive, et « désactiver » n'est pas la seule lecture possible (voir D1).
4. **Des gates et un test de qualification encodent le chemin actuel** : `check-advanced-provider-consistency.py:96` exige `provider.cacheKey(project, snapshot)` ; `check-mnd.py:101-104`, `check-mne.py:98-101` ; `ProgramGraphPerformanceQualificationTest.java:76` affirme `providerKeyNanos() > 0`.

## Mesures du 2026-10-13

HEAD mesuré : `787a1478` (`develop`). Les références de « État vérifié au HEAD » ont été relues à ce HEAD : inchangées.

### Protocole (tâche 1.1)

- **Machine** : AMD Ryzen 7 5700G (8 cœurs, 16 fils), 48 Go, Windows 10 Pro 19045, NTFS sur SSD NVMe ; Temurin 25.0.4.1 ; stockage **local** (`MinosApplication.open` sur un `MINOS_HOME` jetable). D'autres builds Maven tournaient sur le poste : **les temps absolus sont bruités** ; les compteurs et les rapports ne le sont pas.
- **Corpus** : ce dépôt, les 1 236 fichiers `*.java` suivis par Git (8 084 657 octets), plugin IntelliJ et fixtures compris. C'est une borne haute : le périmètre d'un `minos index` réel n'a pas été mesuré.
- **Snapshot** : publié par `snapshotStore().publish` avec un symbole Java synthétique par fichier, comme le fait `ProgramGraphPerformanceQualificationTest.java:49-54` (le jar de développement n'indexe pas le Java par scip-java sous Windows). Équivalent pour ce chemin : `JavaSourceWorkspace.discover` ne lit du snapshot que `language` et `fileId` des symboles (`JavaSourceWorkspace.java:44-55`). Contrôle avec 20 symboles par fichier (24 720 symboles, 3 JVM) : mêmes ordres de grandeur.
- **Avec empreinte exacte** : `fingerprintStore().publish` puis `promote` (comme `ProgramGraphPerformanceQualificationTest.java:55-59`). **Sans empreinte exacte** : aucune empreinte publiée pour ce snapshot. C'est l'état que laisse une indexation pendant laquelle un fichier change : `LocalAutonomousIndexOperations.java:201-220` ne publie ni ne promeut rien quand `stable` est faux, et `fingerprints.load(projectId, snapshotId)` rend alors vide (`FingerprintConstrainedJavaProgramGraphProvider.java:93-94`). L'indexation réelle interrompue n'a pas été rejouée.
- **Appels** : `programGraphService().getGraph(id, 100_000, 500_000)`. Par JVM : un premier appel (échec de cache), puis 10 succès de cache chronométrés (identité de l'instance vérifiée, `misses == 1`), `providerKeyNanos` relevé par différence entre deux `cacheStats()`. 5 JVM par situation, alternées. Un succès de cache supplémentaire, hors chronométrage, sous JFR (`jdk.FileRead`, seuil 0) compte les fichiers lus.
- **Programme** : jetable, hors de l'arbre (dossier temporaire de session), non conservé. Aucun code de production modifié.

### Résultats (succès de cache)

| | Avec empreinte exacte | Sans empreinte exacte |
|---|---|---|
| Fichiers `.java` lus par appel | **0** | **1 236** (tous) |
| Octets de sources lus par appel | 0 | 8 084 657 (tout le corpus) |
| `providerKeyNanos`, second appel (n = 5 JVM) | médiane 0,65 ms (0,38 – 1,24) | médiane **928 ms** (691 – 1 158) |
| `providerKeyNanos`, tous les succès (n = 50) | médiane 0,52 ms (0,36 – 1,24) | médiane **867 ms** (538 – 1 290) |
| Temps total de l'appel (n = 50) | médiane 5,75 ms (4,10 – 16,98) | médiane 872 ms (542 – 1 295) |
| Part de la clé dans l'appel (n = 50) | médiane 8,4 % (4,3 – 13,1) | médiane **99,4 %** (98,5 – 99,6) |
| Appel hors clé (n = 50) | médiane 5,3 ms | médiane 4,8 ms |

Premier appel (échec de cache, n = 5) : 8,4 à 10,3 s avec empreinte, 8,7 à 10,9 s sans ; la clé y pèse 31 à 45 ms avec empreinte, 0,86 à 1,09 s sans (environ 10 % de l'appel). Le graphe obtenu est le même dans les deux cas (100 000 nœuds, limite atteinte ; `CONTROL_FLOW`, `LOCAL_DATA_FLOW`, `INTERPROCEDURAL_DATA_FLOW`).

### Lecture

- **Le chemin décrit par l'audit se produit** : sans empreinte exacte, chaque succès de cache relit tous les fichiers Java du snapshot ; avec empreinte, aucun. Le compteur ne dépend pas de la charge du poste.
- **Le coût n'est pas négligeable sur ce dépôt** : la clé multiplie le temps d'un succès de cache par environ 150 (médianes) et en représente 99 %. Soit environ 0,7 ms par fichier ; la part due à l'ouverture des fichiers (et à l'antivirus) et celle due au SHA-256 n'ont pas été séparées.
- **Il ne concerne que les processus durables** (serveur MCP) : le cache est en mémoire (`ProgramGraphService.java:44`), une commande CLI repart d'un échec de cache où la clé pèse environ 10 %.
- **Borne** : au-delà de 2 000 fichiers Java, `discover` échoue (`JavaSourceWorkspace.java:60-62`) et la clé ne lit plus aucune source ; le coût maximal est donc celui de 2 000 fichiers et 64 Mio.

**Confiance requalifiée : « confirmé »** (mesuré, coût réel). Restent **non mesurés** : la fréquence des snapshots sans empreinte en usage réel, le coût à cache disque froid, Linux, un snapshot issu d'une indexation réelle, le stockage PostgreSQL.

### Constats de la tâche 1.2

**D'où vient le graphe.** `JavaProgramGraphEngine.analyze` (`JavaProgramGraphEngine.java:33-56`) construit le graphe **depuis les fichiers du disque** : le snapshot ne fournit que la liste des fichiers (`JavaSourceWorkspace.java:44-55`) et son identifiant ; `JavaAstParser.java:38-47` fait analyser ces chemins par le compilateur du JDK. Un fichier créé depuis l'indexation est ignoré ; un fichier du snapshot disparu rend un graphe vide avec `JAVA_ADVANCED_PROVIDER_SOURCE_MISSING` (`JavaSourceWorkspace.java:77-79`). Le snapshot ne contient pas le texte des sources : le moteur ne peut pas reconstruire l'état indexé.

- **Avec empreinte exacte** : avant de construire, `matchesExactSnapshot` vérifie que le disque égale l'état indexé (`FingerprintConstrainedJavaProgramGraphProvider.java:70-74`, `:142-164`), sinon graphe vide avec `SOURCE_MISMATCH_LIMITATION`. La clé (`:64-65`) ne lit aucune source : dans un même processus, une modification postérieure à la mise en cache n'est donc pas revue, et le graphe servi reste celui de l'état indexé (lu, non mesuré ; `ProgramGraphPerformanceQualificationTest.java:86` rouvre l'application pour observer la limitation).
- **Sans empreinte exacte** : le graphe reflète le disque **au moment de la construction**, sans comparaison possible avec l'état indexé ; seule la clé par contenu le fait reconstruire quand un fichier change. Sur un échec de cache, les sources sont hachées deux fois et analysées une fois (`JavaProgramGraphEngine.java:28-30`, `:42`, `:47`).

**Conséquence pour D1-A** : « servir le graphe du snapshot » n'est pas réalisable à la lettre. Avec une clé dérivée de l'identité du snapshot, le graphe mis en cache serait celui du disque au premier appel du processus : ni l'état indexé, ni l'état courant, et il peut différer d'un processus à l'autre. La limitation devrait dire cela, pas seulement « l'arbre a pu changer depuis l'indexation ».

**Littéraux à préserver ou à mettre à jour** (les trois premiers gates rejoués verts à ce HEAD) :

- `scripts/quality/check-advanced-provider-consistency.py:96` : `provider.cacheKey(project, snapshot)` dans `ProgramGraphService` (présent en `ProgramGraphService.java:273`) ; `:95` : `default String cacheKey` dans `ProgramGraphProvider`.
- `scripts/remediation/check-mnd.py:101-104` : `WeightedGraph`, `evidenceWeight`, `stringWeight`, `maximumWeight` dans `ProgramGraphService` ; `DEFAULT_MAX_FINGERPRINT_CACHE_WEIGHT_BYTES`, `trimCache`, `fingerprintCacheStats` dans `FingerprintConstrainedJavaProgramGraphProvider`.
- `scripts/remediation/check-mne.py:98-101` : `sha256Exact`, `grew while being fingerprinted`, `MAX_SECURITY_CONFIG_BYTES` exigés et `private static String sha256(Path file)` interdit, dans `FingerprintConstrainedJavaProgramGraphProvider`.
- `ProgramGraphPerformanceQualificationTest.java:76` : `assertTrue(stats.providerKeyNanos() > 0L)`. Ce test ne couvre que le chemin **avec** empreinte ; l'assertion reste vraie tant que la clé est chronométrée.
- **Non cité jusqu'ici** : `scripts/quality/check-vertical-decomposition-consistency.py:119-124` exige dans ce test les chaînes `recordsColdWarmCacheHitAndModifiedSourceDisposition`, `KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE` et `SOURCE_MISMATCH_LIMITATION` ; `scripts/m28/run-program-graph-performance.sh:49-58` (et le `.ps1`) exigent du JSON produit un succès et un échec de cache, `warm_identity_hit` et cette même décision. La réécriture prévue en D4 doit les conserver ou mettre ces scripts à jour dans la même tâche.

### Ce que les mesures établissent pour D1

- **A** : supprime la relecture (succès de cache ramené à celui du chemin avec empreinte, environ 5 ms ici). Le graphe servi n'est pas celui du snapshot (voir ci-dessus) et n'est plus reconstruit quand un fichier change, jusqu'à une nouvelle indexation ou un redémarrage.
- **B** : paie la clé (environ 0,9 s ici) une fois par délai au lieu d'une fois par appel ; garde la reconstruction sur changement, avec un retard borné par le délai.
- **C** : supprime la relecture et le premier appel de 9 s, mais retire l'analyse Java dans cette situation.
- **Ne rien changer** : chaque appel de `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` servi par le cache d'un serveur durable coûte environ 0,9 s sur ce dépôt au lieu de 5 ms ; le graphe suit le disque. L'issue « coût négligeable » de la tâche 1.3 n'est pas celle que montrent les chiffres ; la fréquence de la situation reste inconnue.

## Mise en œuvre du 2026-10-13 (option B)

### Avant / après (même protocole, même corpus, 5 JVM par situation)

Le programme de mesure attend réellement 5,2 s pour la colonne « après le délai ». Le poste était moins chargé pour la seconde campagne (construction du graphe en 5 à 6 s au lieu de 8 à 11 s) : **les durées ne se comparent pas d'une campagne à l'autre**, les compteurs et les parts si.

| Succès de cache, sans empreinte exacte | Avant | Après, dans le délai | Après, délai expiré |
|---|---|---|---|
| Fichiers `.java` lus par appel | 1 236 | **0** | 1 236, une fois |
| `providerKeyNanos` | médiane 867 ms (538 – 1 290), n = 50 | médiane **0,27 ms** (0,14 – 0,47), n = 46 | médiane 564 ms (467 – 608), n = 5 |
| Temps total de l'appel | médiane 872 ms (542 – 1 295) | médiane **3,2 ms** (2,2 – 5,5) | médiane 568 ms (471 – 612) |
| Part de la clé dans l'appel | 99,4 % | 8,1 % (6,1 – 12,7) | 99 % |

Avec empreinte exacte, rien ne change : 0 source lue, clé médiane 0,31 ms, appel médian 3,2 ms (n = 50), y compris après 5,2 s d'attente.

**Observé et attendu** : le délai court depuis le hachage, pas depuis la fin de la construction. Quand la première construction dure plus de 5 secondes (4 JVM sur 5 ici), le deuxième appel rehache une fois (591 à 714 ms), puis les suivants sont servis sans relecture. Faire courir le délai depuis la fin de la construction aurait rendu fausse la borne « au plus 5 secondes » que porte la limitation.

### Écarts avec le design initial

1. **La limitation est émise sur toute réponse sans empreinte exacte dont des sources Java sont lues**, pas seulement quand la clé est réutilisée (D3) : sortie déterministe, visible en CLI.
2. **Le port `ProgramGraphProvider` gagne `resolveCacheKey`** (méthode par défaut, additive) et le littéral du gate `check-advanced-provider-consistency.py` passe de `provider.cacheKey(project, snapshot)` à `provider.resolveCacheKey(project, snapshot)` ; `default String cacheKey` reste exigé.
3. **Le compteur de lectures des tests observe les lectures réelles** (événements `jdk.FileRead`) au lieu d'un espace de sources injecté : le rouge de la tâche 2.1 a ainsi pu être mesuré sur le code du HEAD, sans point d'injection.
4. **`ProgramGraphPerformanceQualificationTest` n'est pas réécrit mais complété** (D4) : ses littéraux et le contrat des scripts `scripts/m28/` sont conservés.
5. **Aucun golden de caractérisation ne change** : les cas sans empreinte de `minos-app/src/test/resources/characterization/` ne lisent aucune source Java. La limitation est couverte par un test de surface dédié (`ProgramGraphSourceStateLimitationSurfaceTest`).
6. Le fournisseur `JavaSourceProgramGraphProvider` utilisé seul (sans magasin d'empreintes, hors composition de production) garde son comportement : il hache à chaque appel et ne déclare rien.

### Non vérifié

`minos-intellij/gradlew test` n'a pas été lancé (Gradle 9.8.1 absent du poste : téléchargement non demandé). À la lecture, le plugin ne lit aucune limitation (`MinosM21Client` rend le `JsonObject` tel quel). Linux, PostgreSQL et un snapshot issu d'une indexation réelle restent non mesurés.

## Goals / Non-Goals

**Goals** : connaître le coût réel ; ne plus relire toutes les sources à chaque appel servi par le cache ; ne jamais présenter comme frais un graphe dont la fraîcheur n'est pas prouvée.

**Non-Goals** : réindexer ; changer la promotion de l'empreinte ; introduire une heuristique de fraîcheur.

## Decisions

### D1. Que faire quand le snapshot n'a pas d'empreinte exacte — **décidé : option B** (propriétaire, 2026-10-13)

**Décision.** Option B : la clé est hachée une fois, puis n'est rehachée qu'après un délai borné. Raison établie par les mesures du 2026-10-13 : le moteur construit le graphe **depuis le disque** et ne peut pas reconstruire l'état indexé ; l'option A aurait servi un graphe figé au premier appel du processus, ni indexé ni courant, jusqu'à une réindexation ou un redémarrage. B garde un graphe qui **suit le disque avec un retard borné** et ramène un succès de cache à un appel sans relecture. La recommandation initiale (A) est abandonnée.

**Option A (recommandée à la spécification, non retenue) : servir le graphe du snapshot et le déclarer.** Sans empreinte exacte, la clé de cache est dérivée de l'identité du snapshot (aucune lecture de source) ; le graphe est construit depuis l'état décrit par le snapshot et la réponse porte une limitation additive (par exemple `PROGRAM_GRAPH_SOURCE_STATE_UNVERIFIED`) : « l'arbre source a pu changer depuis l'indexation ; relancer `minos index` ». C'est capability-honest : rien n'est présenté comme frais, rien n'est recalculé à chaque appel. À établir en tâche 1.2 : le moteur Java lit-il les sources courantes pour **construire** le graphe ? Si oui, le graphe construit reflète le disque au moment de la construction, et la limitation le dit.

**Option B : hacher une fois, puis ne rehacher qu'après un délai borné.** La clé coûteuse est mémorisée par (projet, snapshot) pour une durée courte et configurable ; pendant ce délai le cache est servi sans relecture, avec la même limitation. Garde plus de fraîcheur que A, au prix d'un paramètre et d'un comportement dépendant du temps (plus difficile à tester).

**Option C (variante de l'audit) : désactiver l'analyse Java** sans empreinte. Le plus strict ; il retire une capacité dans une situation courante en IDE. Non recommandé.

**Écartée** : taille + date de modification (écart 2).

### D2. La mesure précède la décision

`providerKeyNanos` est relevé sur ce dépôt, avec empreinte exacte puis sans (indexation pendant laquelle un fichier est modifié), sur un succès de cache. Le résultat est écrit dans `design.md` et dans `SUIVI.md` ; il fixe la confiance du constat (« confirmé » ou « non reproduit »).

### D3. La limitation est additive et commune aux trois outils

Une seule constante, `ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION`, de valeur `PROGRAM_GRAPH_REFLECTS_DISK_AS_OF_AT_MOST_5_SECONDS_AGO_NOT_INDEXED_STATE`, émise par le service et relayée telle quelle par `minos_program_graph`, `minos_impact_v2` et `minos_security_paths` (MCP, CLI, API Java). Les limitations de ces sorties sont des identifiants : le texte dit ce que les mesures établissent (le graphe est le disque tel qu'il était il y a au plus 5 secondes, pas l'état indexé) ; la documentation nomme la commande `minos index`. Aucun champ existant n'est modifié.

**Quand elle est émise.** Sur **toute** réponse dont la contribution Java vient des sources du disque sans empreinte exacte, y compris le premier appel qui vient de hacher (l'âge est alors nul, et « au plus 5 secondes » reste vrai). Elle ne l'est ni avec une empreinte exacte, ni quand aucune source Java n'est lue (le graphe est alors l'état indexé). La limiter aux seules réponses servies par une clé réutilisée aurait rendu une sortie publique dépendante de l'instant de l'appel, donc des tests et des goldens non déterministes ; et la CLI, qui ouvre un processus par commande, ne l'aurait jamais montrée alors que la tâche 3.1 l'exige.

**Comment le service le sait.** Le port `ProgramGraphProvider` gagne une méthode par défaut `resolveCacheKey`, qui rend la clé et dit si elle représente des sources lues il y a au plus le délai ; `cacheKey` reste, et la valeur par défaut l'enveloppe. Le service appelle `provider.resolveCacheKey(project, snapshot)` : le littéral du gate `check-advanced-provider-consistency.py` est mis à jour dans la même tâche. La clé étant dérivée du contenu, une clé réutilisée et la même clé recalculée désignent le même graphe : la limitation voyage avec le graphe mis en cache.

### D4. Le test de qualification suit

`ProgramGraphPerformanceQualificationTest` garde son nom de méthode, ses littéraux et l'assertion `providerKeyNanos() > 0`, et affirme en plus ce qui compte : avec empreinte exacte, un succès de cache ne lit aucune source (lectures réelles comptées par les événements `jdk.FileRead`) et ne porte pas la limitation ; sans empreinte, sur une horloge manuelle, un appel dans le délai ne relit aucune source alors qu'un fichier a changé, et un appel après le délai relit chaque source et reconstruit le graphe. Le JSON produit gagne cinq champs additifs ; les scripts `scripts/m28/` n'en lisent aucun.

### D5. Le délai : 5 secondes, constante nommée, non réglable

`ProgramGraphService.SOURCE_STATE_REUSE_SECONDS = 5`. Justification par les mesures : la clé coûte ici 0,87 s en médiane (1,29 s au pire) pour 1 236 fichiers, soit environ 0,7 ms par fichier ; à la borne du fournisseur (2 000 fichiers) l'ordre de grandeur est 1,4 s. Un délai de 5 s reste donc toujours supérieur au coût qu'il épargne, et ramène la part de la clé d'environ 99 % d'un succès de cache à au plus 0,87 s toutes les 5 s (moins de 20 % d'une rafale continue). Il reste court devant le cycle « modifier un fichier, puis interroger » et devant la construction du graphe (8 à 11 s ici). **Aucun réglage utilisateur n'est ajouté** : le design ne l'exige pas, et l'identifiant de la limitation porte la valeur ; un réglage ferait varier un identifiant public.

La mémorisation (`SourceStateKeyMemo`) est par projet et snapshot, une entrée par projet, bornée à 64 entrées de taille fixe (un identifiant de snapshot et une empreinte SHA-256), comme le cache d'empreintes voisin. Les appels concurrents d'un même projet partagent un seul hachage. L'horloge est monotone et injectable (`System::nanoTime` en production) ; une horloge qui recule n'est pas crue. Seule une clé qui a réellement lu des sources est mémorisée.

## Direction des dépendances

Changement interne à `minos-application` ; le test de qualification reste dans `minos-bootstrap`. Aucune arête nouvelle.

## Qualification des capacités

- Graphe de programme Java avec empreinte exacte : qualifié, inchangé.
- Graphe de programme Java sans empreinte exacte : servi sans mention avant ce changement ; devient **partiel et déclaré** (option B) : il suit le disque avec un retard d'au plus 5 secondes, et la limitation le dit.

## Windows et Linux

Aucune différence de comportement. La précision des dates de modification diffère entre systèmes de fichiers : une raison de plus d'écarter l'heuristique.

## Risks / Trade-offs

- Option B (retenue) : dans un processus durable, une modification faite moins de 5 secondes après le dernier hachage n'est pas vue par l'appel suivant ; elle l'est au premier appel après le délai. C'est ce que déclare la limitation.
- Option B : la sortie des trois outils gagne une limitation pour tout projet Java indexé sans empreinte exacte, y compris en CLI. Aucun golden de caractérisation n'en contient (voir « Mise en œuvre »).
- Une limitation nouvelle peut surprendre un client strict : additive, documentée, et le plugin IntelliJ est vérifié (tâche 3.3).
- Si la mesure montre un coût négligeable, ne rien changer est une issue valable et doit être dite.
