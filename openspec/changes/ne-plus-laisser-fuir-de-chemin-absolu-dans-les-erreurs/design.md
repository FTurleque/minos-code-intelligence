# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-05** puis **AUD-SEC-04**. HEAD analysé : `949e1569` (`develop`). `ENG` = `minos-engine/src/main/java/com/minos`. Règle de référence : `.claude/rules/securite.md`, point 7 (aucun chemin absolu dans une sortie publique ni un journal). `config/minos.properties` désigne un fichier de `MINOS_HOME` à l'exécution, pas un fichier du dépôt.

## État vérifié au HEAD

### AUD-SEC-05 — confirmé

- `ENG/diagnostics/PublicErrorMessages.java:76` : `if (index > 0 && isPathIdentifierChar(detail.charAt(index - 1))) continue;` ; `:102-104` : lettre, chiffre, `_`, `.`, `-` sont des caractères d'identifiant. Un `/` ou un `C:\` précédé de l'un d'eux n'est donc jamais examiné.
- `sanitize` (`:45-50`) n'aplatit que `\r` et `\n` ; aucun autre caractère de contrôle n'est retiré. Dans `\u001b[31m/home/u/x`, le `m` qui clôt la séquence ANSI précède le `/`.
- Appelants en production : **12** classes (`git grep` hors `PublicErrorMessages.java`), dont `minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:228` (`clientError`).
- `minos-engine/src/test/java/com/minos/diagnostics/PublicErrorMessagesTest.java:109` : `request to example.com/status failed` doit rester non sensible.
- À la lecture : une URL est **déjà** classée sensible (le `/` qui suit `:` dans `://` n'est pas précédé d'un caractère d'identifiant). Élargir la forme lecteur Windows ne change donc rien pour `http://…`.

### AUD-SEC-04 — confirmé, plus large que le constat

- `ENG/storage/MinosRuntimeSettings.java:64` : `if (Files.isRegularFile(configuration))`, qui suit les liens ; sinon la configuration reste vide, sans erreur.
- `ENG/io/BoundedProperties.java:157-160` : `requireRegularFile` lève `IOException(label + " must be a regular non-symlink file: " + source)`, `source` étant absolu (`:158`).
- `ENG/io/DurableAtomicFile.java:62` (`… parent durability sync failed: " + target`) et `:227` (`… directory durability sync: " + directory`).
- `minos-storage-local/src/main/java/com/minos/storage/local/incremental/FileProjectFingerprintSnapshotStore.java:610` et `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java:586` : même message, suivi de `file`.
- `minos-storage-local/src/main/java/com/minos/storage/local/registry/InterProcessLocalProjectRegistry.java:163-164` : le message se termine par `entry.getFileName()`, **pas** par le chemin absolu. Ce site, donné comme fuyant par la contre-vérification, est en réalité déjà à la forme cible ; il sert de modèle et n'est pas à corriger.

**Non vérifié** : aucun build ni test lancé. Non relevé : la liste exhaustive des messages d'exception qui concatènent un `Path` dans tout le reactor (les cinq sites sont ceux du motif « fichier refusé » et de la durabilité ; un relevé complet est la tâche 3.1). Non vérifié : que `check-private-io.py` admet un test d'existence `NOFOLLOW_LINKS` dans `MinosRuntimeSettings`.

### Écarts avec l'action proposée par l'audit

1. **AUD-SEC-05 — la règle `/[^\s/]+/` sans frontière est trop large.** Elle classerait sensible tout chemin **relatif** à deux barres (`packages/app/src`), les identifiants SCIP et des noms de modules. Or `openspec/specs/decouverte-et-negociation/spec.md` exige des messages qui nomment un répertoire par son chemin relatif (`data/db`), et 12 classes publient par ce détecteur : leurs messages actionnables deviendraient génériques.
2. **AUD-SEC-05 — le cas réel est la séquence de contrôle.** Retirer les séquences ANSI entières (pas seulement le caractère d'échappement : il resterait `[31m`) et les caractères de contrôle avant l'analyse rend sa frontière au chemin. Seule la forme lecteur Windows `[A-Za-z]:[\\/]` peut être reconnue sans frontière, car elle n'a pas d'homonyme relatif.
3. **AUD-SEC-04 — « traiter `NoSuchFileException` comme absent » ne fonctionne pas.** `requireRegularFile` lève une `IOException` simple pour un fichier absent : retirer le pré-test sans changer la primitive ferait échouer tout `MINOS_HOME` sans `minos.properties`, c'est-à-dire l'installation par défaut.
4. **AUD-SEC-04 — le pré-test cache un fail-open.** Répertoire ou lien cassé à la place du fichier : configuration ignorée, bascule possible sur le stockage par défaut. L'audit ne le relève pas.
5. **AUD-SEC-04 — cinq sites, pas deux**, et un sixième cité à tort (voir ci-dessus).

## Goals / Non-Goals

**Goals** : un chemin absolu masqué par une séquence de contrôle ou accolé à un mot ne franchit plus la barrière ; les messages actionnables à chemin relatif restent publiés ; les primitives n'écrivent plus de chemin absolu ; une configuration présente mais illisible est refusée.

**Non-Goals** : supprimer l'heuristique ; changer un code de sortie ou une classe d'erreur publique ; toucher aux journaux.

## Decisions

### D1. Normaliser avant d'analyser

`sanitize` et `looksSensitive` retirent d'abord les séquences d'échappement ANSI complètes (CSI et OSC) puis tout caractère de contrôle restant. L'analyse et le message publié portent sur le texte normalisé : aucune séquence de contrôle n'atteint une sortie publique.

### D2. Forme Windows sans frontière, forme POSIX avec frontière

`[A-Za-z]:` suivi de `\` ou `/` est sensible où qu'il apparaisse. La forme POSIX garde sa frontière. **Limite déclarée** : `v1.2/home/u` (sans séquence de contrôle) reste non détecté, parce qu'il est indiscernable de `packages/home/u`. La réponse à cette limite est le lot 2 : ne plus écrire de chemin absolu à la source.

### D3. L'absence est typée par la primitive, pas devinée par l'appelant

`BoundedProperties` offre une lecture qui rend « absent » sans exception quand rien n'existe à cet emplacement (test `NOFOLLOW_LINKS`), et refuse tout ce qui existe sans être un fichier régulier. `MinosRuntimeSettings.load` l'appelle sans pré-test. Alternative écartée : un `Files.exists(…, NOFOLLOW_LINKS)` dans `MinosRuntimeSettings`, qui laisse deux contrôles séparés du même fichier. Les littéraux exigés par `check-audit-remediation-v2.py:150-157` et `check-post-mne.py:179-185` sont conservés.

### D4. Étiquette fixe ou nom relatif dans les messages

Les cinq messages nomment la cible par l'étiquette déjà passée à la primitive (`label`, `operation`) ou par le nom de fichier, comme `InterProcessLocalProjectRegistry.java:163-164`. Le chemin absolu reste disponible dans la cause ou le journal interne, sous la règle de `check-private-io.py`.

### D5. Garde par tests de comportement

Pas de nouveau gate littéral : un test par site affirme que le message ne contient pas le répertoire temporaire du test.

## Direction des dépendances

`minos-engine` et `minos-storage-local` seulement ; `storage → engine` existe déjà. Aucune arête nouvelle.

## Qualification des capacités

- Détection d'un chemin absolu derrière une séquence de contrôle ou un lecteur Windows accolé : **qualifiée** par les tests du lot 1.
- Détection d'un chemin POSIX accolé à un identifiant : **non supportée**, déclarée (D2).
- Messages des primitives sans chemin absolu : **partielle** tant que le relevé 3.1 n'est pas clos (cinq sites connus).

## Windows et Linux

La forme lecteur et les chemins UNC concernent Windows ; `forceDirectory` (`DurableAtomicFile.java:223`) ne s'exécute pas sous Windows, son message se teste sous Linux. Créer un lien symbolique sous Windows exige un privilège : les tests de lien reprennent la garde de `minos-engine/src/test/java/com/minos/io/BoundedPropertiesSymlinkTest.java` (`:18`, `:28` : le test se termine si le lien ne peut pas être créé) ; le cas « répertoire à la place du fichier » se teste sur les deux systèmes.

## Risks / Trade-offs

- Un message aujourd'hui publié qui contient `X:/` ou `X:\` accolé à un mot devient générique : voulu.
- Un `MINOS_HOME` où `config/minos.properties` est un répertoire démarrait ; il échouera. Erreur d'usage à rendre actionnable (étiquette « MINOS runtime configuration »), à noter dans `docs/user/troubleshooting.md`.
- Un test existant peut affirmer le chemin dans un message : relevé en tâche 3.1.

## Relevé d'implémentation (2026-10-17)

### Tâche 2.2 — messages devenus différents

- Aucun message d'appelant n'est devenu générique : `MinosMcpErrorRedactionTest`, `MinosMcpTypedErrorsTest` et le reste de `minos-mcp` et `minos-cli` passent.
- **Un message rendu autrement** : `IndexCommandRefusalReasonTest` attendait qu'une séquence ANSI soit rendue `_[31mred` ; elle est maintenant retirée en entier (D1), le BEL et le NUL aussi, soit `bell nul bidi_ end red` (seul le caractère de format bidi, que `PublicErrorMessages` ne touche pas, reste remplacé par `_` à l'affichage). Attente du test mise à jour.
- `sanitize` aplatit aussi `\t` en espace (avant : conservé) ; sans effet sur les tests existants.

### Tâche 3.1 — sites de message qui concatènent un `Path`

Les cinq sites du design sont confirmés au HEAD. **Corrigés en plus**, parce que leur absence rendait le correctif sans effet pour l'appelant ou qu'ils sont le même motif dans le même fichier :
- `FileProjectFingerprintSnapshotStore` `:376` et `:598` (répertoire de projet non régulier) ;
- `FileIndexStateStore` `:578` (répertoire), `:173` (« cannot read MINOS project state »), `:636` et `:644` (« cannot read/write MINOS index state ») : le message qui atteint l'appelant est celui de l'`UncheckedIOException`, pas celui de sa cause.

**Relevés et non corrigés** (même famille « fichier refusé » ou contenu invalide, hors du périmètre des cinq sites ; à traiter dans un changement dédié) :
- refus de type de cible : `InterProcessLocalProjectRegistry:172`, `ProjectPathMappingStore:46` et `:104`, `FileSemanticVectorStore:344`, `:361`, `:366` ;
- contenu invalide ou illisible : `FileIndexStateStore:401`, `:404`, `:610`, `:615`, `:621`, `:673` ; `LocalProjectRegistry:306`, `:313`, `:317`, `:320`, `:376`, `:381`, `:387`, `:406` ; `FileSemanticVectorStore:105`, `:142`, `:155`, `:173`, `:284` ; `SnapshotRetentionService:196`.

Aucun test existant n'affirme un chemin absolu dans un de ces messages (les tests relevés n'affirment que le libellé : « regular non-symlink », « non-symlink directory »).

### Écarts avec le design

- D3 est réalisé par `BoundedProperties.loadIfPresent` (rend `Optional.empty()` si rien n'existe, test `NOFOLLOW_LINKS`) ; `MinosRuntimeSettings.load` l'appelle sans pré-test. Les littéraux exigés par `check-audit-remediation-v2.py` et `check-post-mne.py` sont conservés ; `check-private-io.py` admet le test d'existence dans la primitive.
- Le message de durabilité de `DurableAtomicFile` (`:62`, `:227`) n'était pas testable sans couture : `ensureDirectory(Path, String, DirectorySync)` et `unsupportedDirectorySync` sont ajoutés en visibilité de paquet, sans changement de comportement public.
