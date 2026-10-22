# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-08** (statut « Spécifié », `docs/project/audits/2026-10-10/SUIVI.md:587`). HEAD analysé : `5ce7b79b` (`develop` du 2026-10-22). Chemins complets des fichiers cités par leur nom :

- `minos-intellij/src/main/java/com/minos/intellij/actions/MinosM21Actions.java`, `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosM21Client.java`, `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosM21ClientTest.java`, `minos-intellij/src/main/resources/META-INF/plugin.xml` ;
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java` ;
- `minos-engine/src/main/java/com/minos/incremental/BuildDescriptorPolicy.java` ;
- `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1` (le « runner »).

## État vérifié au HEAD

### Méthode d'inventaire (reproductible)

Fichiers suivis par Git sous `*/src/main/**` (`git ls-files '*/src/main/*'`). Un **segment à jalon** est `M` ou `m` suivi d'un ou plusieurs chiffres, placé en début de segment d'identifiant (début du mot, après `_`, ou `M` majuscule après une minuscule ou un chiffre) et suivi de la fin du mot, de `_`, de `$` ou d'une majuscule. Sont lus : le nom de chaque fichier ; les identifiants Java hors littéraux et commentaires (une seule passe lexicale qui reconnaît chaînes, blocs de texte, caractères et commentaires) ; les attributs `class=` des descripteurs XML ; dans les `.ps1`, les noms `MINOS_*` écrits dans des littéraux (variables d'environnement, y compris dans le C# embarqué). Les littéraux de chaîne et les commentaires ne sont pas lus.

Résultat au HEAD : **2 fichiers**, **16 identifiants distincts** et **8 références** `class=` de `plugin.xml`, **aucun faux positif** : `arm64`, `ARM64`, `MACOS_ARM64`, `System32`, `windowsSystem32` et les empreintes base64 ne sont pas retenus, alors qu'un motif naïf `[Mm][0-9]+` retient 30 occurrences de `arm64` et 26 de `System32`/`system32`.

### AUD-QUA-08 — confirmé, périmètre plus large que l'audit

- **Plugin** : les deux fichiers de production ci-dessus (`MinosM21Actions` : 227 lignes, Javadoc `:23` « M21-S6 surface parity » ; `MinosM21Client` : 118 lignes, `@Service(Service.Level.PROJECT)` `:13`, sans `@State` ni état persistant) et leur test. Dans `plugin.xml`, 8 des 16 actions ont `class="com.minos.intellij.actions.MinosM21Actions$…"` : `:39-41` (groupe `Minos.Advanced.Popup`, `:38`), `:44-45` et `:47-49` (groupe `Minos.Semantic.Popup`, `:43`), d'identifiants `Minos.ProgramGraph`, `Minos.ImpactV2`, `Minos.SecurityPaths`, `Minos.SemanticIndexStatus`, `Minos.SemanticIndexSync`, `Minos.SemanticSearch`, `Minos.HybridSearch`, `Minos.HybridContext`.
- **Catalogue** (`ScipIndexerCatalog.java`, 441 lignes) : `:41` `qualifiedM1Descriptors` (Javadoc `:40` « Historical M1 catalog preserved for compatibility/replay » : scip-java et scip-typescript), `:45` `registerQualifiedM1`, `:50` `qualifiedM17Providers` (`:49` « Historical M17 catalog preserved for exact regression replays » : Java, TypeScript, Python), `:58` `qualifiedM17Descriptors`, `:63` `qualifiedM24Providers` (`:62`, catalogue courant, qui **étend** `qualifiedM17Providers`, `:64`), `:72` `qualifiedM24Descriptors`.
  - Appelants de production : `DefaultMinosApplicationComposer.java:65` (`qualifiedM24Descriptors`) et `:71` (`qualifiedM24Providers`) dans `minos-bootstrap` ; `ScipProjectSnapshotLifecycle.java:42` (`qualifiedM1Descriptors`) dans le constructeur à un argument, que seuls des tests appellent (la production passe par le constructeur à trois arguments, `DefaultMinosApplicationComposer.java:89`).
  - `qualifiedM17Descriptors` n'a **aucun appelant** (ni production, ni test) ; `registerQualifiedM1` n'a qu'un appelant, de test.
- **Descripteurs de build** (`BuildDescriptorPolicy.java`) : `:15` `M17_DEFAULT_FILE_NAMES`, `:29` `M24_ADDITIONAL_FILE_NAMES` (CMake, Go, Cargo, `:30-36`), `:54` `m17Defaults()` (« politique historique », `:53`), `:59` `m24Defaults()` (« défaut courant », `:58`). Appelants : `ProjectFingerprintService.java:44`, `ProjectInvalidationService.java:36` (`minos-engine`), `FileProjectFingerprintSnapshotStore.java:74-75` (`minos-storage-local`), qui les nomme déjà `CURRENT_BUILD_DESCRIPTOR_POLICY` et `LEGACY_BUILD_DESCRIPTOR_POLICY`. Aucun nom de politique n'est écrit sur disque : seules les empreintes calculées le sont (`:494`, `:498`).
- **Tests appelants** (`git grep`) : `M17ProviderPlatformTest`, `M24PolyglotProviderTest`, `ProviderCatalogPortTest`, `IncrementalIndexingRealFixtureTest` (`minos-app`) ; `ProviderPlatformDiagnosticRedactionTest` (`minos-bootstrap`) ; `RootScopeFingerprintReuseTest` (`minos-application`) ; `ScipIndexerCatalogTest`, `ScipResumableArtifactCapabilityTest`, `EmbeddedToolsCatalogTest` (`minos-provider-scip`) ; `FileProjectFingerprintSnapshotStoreTest` (`minos-storage-local`).
- **Variables d'environnement** du runner (fichier en CRLF) : `MINOS_M14_MAVEN_CMD`, `MINOS_M14_SCIP_JAVAC_EXE`, `MINOS_M14_BASH_EXE`, `MINOS_M14_SCIP_JAVAC_SCRIPT`. Trajet : le runner sauvegarde les valeurs antérieures (`:395-397`), pose les trois premières dans son propre processus (`:399-401`) ; les shims C# qu'il compile les lisent (`:101`, `:123`, `:172-173`) ; le shim Maven pose la quatrième pour le shim javac qu'il lance (`:129`) ; le runner restaure les trois premières (`:445-447`). Aucune autre occurrence dans les fichiers suivis (ni code Java, ni test, ni `docs/user/`) : ce sont des noms internes au runner, sans contrat.
- **Chaînes publiques citant un jalon** (littéraux de `*/src/main/*.java`, `git grep -nE '"[^"]*\bM[0-9]+\b[^"]*"'` hors lignes de commentaire) : **39 lignes dans 15 fichiers** — 6 descriptions d'outils MCP (`MinosMcpTools.java:113,116,122,124,128,134`), 16 limitations et diagnostics du catalogue (`ScipIndexerCatalog.java:92-395`), des messages d'erreur (`RuntimeObservationSession.java:43`, `LocalMinosMultiRepositoryApi.java:328`, `JavaTaintAnalyzer.java:257`, `ScipSymbolNormalizer.java:128`, `ManagedPolyglotScipRuntimeManager.java:97,99,129,136`, les cinq fabriques de `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/plan/`), l'en-tête d'un manifeste écrit sur disque (`DistributedArtifactBundleStore.java:350`) et deux **valeurs de données** : `"M3"` (`DependencyDerivationService.java:104`) et `"M5"` (`RelatedTestDerivationService.java:307`), version d'`Origin` des relations dérivées.
- **Ces chaînes sont figées** : les limitations du catalogue (« not claimed by M24 », « M17 Kotlin fixture », « M0-qualified », « M24 qualifies »…) figurent dans `api.golden`, `cli-json.golden`, `mcp-all-tools.golden` et `mcp.golden` de `minos-app/src/test/resources/characterization/` ; `"M3"` et `"M5"` dans trois de ces goldens et dans trois tests ; le message de `RuntimeObservationSession.java:43` est exigé par `scripts/quality/check-runtime-dynamic-consistency.py:64`.
- **Commentaires citant un jalon** : 112 lignes Java (`git grep -nE '^\s*(//|\*|/\*).*\bM[0-9]+\b' -- '*/src/main/*.java'`).

### Gates et autres dépendances de nom

- **`scripts/docs/product-facts.py:60`** cherche `qualifiedM17Providers` et `qualifiedM24Providers` par l'expression `public static List<IndexerProvider> {catalog}\(\) \{` et exige 7 fournisseurs (`:70-71`, message « qualified M24 catalog »). C'est le **seul gate vivant** qui cite un identifiant à renommer (`grep -rn` dans `scripts/`, `.github/`, `.claude/`, hors `scripts/history/`).
- `scripts/quality/check-jacoco.py` cite les **classes** `ScipIndexerCatalog` (`:105`, scope `m24-polyglot-provider-platform`) et `FileProjectFingerprintSnapshotStore` (`:31`) : aucune classe du reactor n'est renommée, les préfixes ne changent pas.
- **Baseline SpotBugs** : aucune entrée de `quality/spotbugs-baseline/` ne nomme une méthode ou une classe renommée (`minos-engine.xml:353-369` porte sur `ProjectFingerprintService.ancestorDescriptors`, inchangée).
- **Plugin** : plancher JaCoCo par paquet (`minos-intellij/build.gradle.kts:136-157`, `com.minos.intellij.protocol` ≥ 0,47 en lignes) : la classe renommée reste dans son paquet. SonarCloud est lancé par le scanner Maven (`.github/workflows/pr-ci.yml:473-478`) et le plugin n'est pas un module du reactor (`pom.xml:24-37`) : le plugin n'est **pas analysé** par SonarCloud.
- **Historique** : `scripts/history/m21/check-m21-parity.py:56-58` lit les trois fichiers du plugin ; son seul appelant (`scripts/history/m21/run-s6.ps1:40,48`) le cherche à un chemin qui n'existe plus. `scripts/history/m15/run-s3.ps1:126,132` cite `qualifiedM1Descriptors`. Aucun n'est exécuté ; ils ne sont pas modifiés.
- **Sorties de gates à jalon** : `check-module-boundaries.py:1411,1459,1482,1489` affichent « M21 … » ; au total, quatorze scripts de `scripts/` hors `scripts/mNN/` et `scripts/history/` affichent un préfixe `M\d+` (`git grep -lE 'print\(f?"M[0-9]+ '`). Seules des preuves d'audit les citent.
- **Scopes JaCoCo à jalon** : 9 dans `check-jacoco.py:101-233` (`m19-m20-mcp-catalogue`, `m24-polyglot-provider-platform`, `m25-remote-distributed-indexing`, `m26-runtime-dynamic-intelligence`, `m27-team-hosted-control-plane`, `m29-backend-routing`, `m29-docker-runtime-bootstrap`, `m30-storage-backend-selection`, `m30-postgresql-pgvector`), cités par `pr-ci.yml:356`, `AGENTS.md:33`, `openspec/config.yaml`, `.claude/skills/minos-verify-local/SKILL.md:30` et `docs/developer/quality-gates.md:49`.
- **Livrés hors `src/main`** : `docker/scripts/configure-m30-docker-services.ps1` (cité par 15 fichiers, dont `scripts/remediation/check-post-mne.py` et la spécification `stockage-prive-et-secrets`) et `$M30Services` (`docker/scripts/configure-docker-mcp.ps1:72`) ; le workflow `.github/workflows/m0-java-ci.yml` (cité par `check-single-execution.py`).
- **Classes de test à jalon** : 21 fichiers sous `*/src/test/`, dont quatre `M24Polyglot…Test` que `check-polyglot-provider-consistency.py:259-262` exige par chemin.
- Le garde-fou de l'ADR 0043 (`scripts/quality/check-milestone-artifact-references.py`, `pr-ci.yml:155,177`) ne lit que les scripts : **aucun gate ne lit les identifiants de `src/main`**.

**Non vérifié** : aucun build, test ni gate exécuté pendant cette contre-vérification ; l'exécution réelle du runner sous Windows (D4) ; une éventuelle référence à `MinosM21Client` hors du dépôt (configuration IntelliJ d'un utilisateur).

### Écarts avec l'action proposée par l'audit

1. **Périmètre** : l'audit cite 2 méthodes du catalogue ; il y en a 6, dont deux à un seul chiffre (`qualifiedM1Descriptors`, `registerQualifiedM1`) qu'un motif « `M` + deux chiffres » ne voit pas, et une sans appelant (`qualifiedM17Descriptors`).
2. **`legacyJavaProviders()` n'est pas fondé** : le catalogue `:49` contient Java, TypeScript et Python et sert de base au catalogue courant.
3. **`DEFAULT_BUILD_DESCRIPTORS` / `POLYGLOT_BUILD_DESCRIPTORS`** : l'appelant `FileProjectFingerprintSnapshotStore` nomme déjà les deux politiques « courante » et « héritée » ; D1 suit ce vocabulaire.
4. **Un gate cite les anciens noms** (`product-facts.py:60`) : sans mise à jour dans la même tâche, `product-facts.py --check` casse.
5. **Variables `MINOS_M14_*`** absentes du constat ; internes au runner (D4).
6. **Chaînes publiques** : sortie versionnée figée par des goldens, pas un renommage (D3).

## Goals / Non-Goals

**Goals** : plus aucun segment à jalon dans un nom de fichier, un identifiant Java, un attribut `class=` ou un nom de variable d'environnement de `*/src/main/**` ; convention gardée par un gate ; aucune sortie publique ni aucun identifiant d'action modifié.

**Non-Goals** : renommer les classes de test ; réécrire les commentaires ; toucher aux chaînes publiques (D3), aux scopes JaCoCo, aux sorties des gates, aux scripts livrés hors `src/main`, aux scripts gelés ou archivés.

## Decisions

### D1. Noms cibles, tirés du rôle écrit et des appelants

| Actuel | Cible proposée | Source du rôle |
|---|---|---|
| `MinosM21Actions`, `MinosM21Client`, `MinosM21ClientTest` | `MinosAdvancedAnalysisActions`, `MinosAdvancedAnalysisClient`, `MinosAdvancedAnalysisClientTest` | groupes `Minos.Advanced.Popup` (`plugin.xml:38`) et `Minos.Semantic.Popup` (`:43`) ; Javadoc du client `:12` |
| `qualifiedM24Providers` / `qualifiedM24Descriptors` | `qualifiedProviders` / `qualifiedDescriptors` | `ScipIndexerCatalog.java:62` ; appelant `DefaultMinosApplicationComposer` |
| `qualifiedM17Providers` | `regressionReplayProviders` | `:49` |
| `qualifiedM17Descriptors` | **supprimée** (aucun appelant) | `git grep` |
| `qualifiedM1Descriptors` / `registerQualifiedM1` | `javaTypeScriptDescriptors` / `registerJavaTypeScript` (selon D2) | `:40-42` |
| `m24Defaults()` / `m17Defaults()` | `currentDefaults()` / `legacyDefaults()` | `BuildDescriptorPolicy.java:53,58` ; `FileProjectFingerprintSnapshotStore.java:74-75` |
| `M17_DEFAULT_FILE_NAMES` / `M24_ADDITIONAL_FILE_NAMES` | `LEGACY_FILE_NAMES` / `POLYGLOT_ADDITIONAL_FILE_NAMES` | idem ; contenu `:30-36` |
| `MINOS_M14_*` | `MINOS_SCIP_JAVA_*` | D4 |

Les noms définitifs sont arrêtés en tâche 1.1 ; la table est la proposition par défaut. Comportement constant : mêmes ensembles, même ordre, mêmes empreintes.

### D2. Motif du gate et sort de `qualifiedM1Descriptors` — **décision en attente du propriétaire**

**Recommandation** : le motif « segment à jalon » de la méthode d'inventaire, **un chiffre ou plus**. Mesuré au HEAD : 0 faux positif. `qualifiedM1Descriptors` et `registerQualifiedM1` sont renommés avec les autres ; la liste décroissante est **vide** à la fin du changement.

Alternative : deux chiffres seulement (lettre de l'audit) ; les deux méthodes `M1` restent, non gardées.

### D3. Chaînes publiques citant un jalon — **décision en attente du propriétaire**

**Recommandation** : **hors de ce lot**. Ce sont des sorties publiques (descriptions d'outils MCP, limitations du catalogue, messages), figées par quatre goldens et un gate ; une description changée n'est pas un ajout : elle demande une note de version et la régénération des goldens. Les valeurs `"M3"` et `"M5"` sont des **données** (version d'`Origin`) et restent exclues de tout lot. Le gate ne lit pas les littéraux.

Alternative : un second lot après décision, qui remplace le jalon par la capacité dans les messages et descriptions retenus, avec goldens, `check-runtime-dynamic-consistency.py`, `docs/user/mcp.md` et note de version dans la même tâche.

### D4. Variables d'environnement du runner

`MINOS_M14_*` deviennent `MINOS_SCIP_JAVA_*` aux quatre endroits (sauvegarde, pose, lecture par le C#, restauration), fichier conservé en CRLF. Le préfixe `MINOS_` est gardé : ces variables naissent dans le processus du runner, sans passer par `ProviderProcessEnvironment`, dont le refus des noms `MINOS_*` ne change pas. Rouge : un test (tous OS) qui lit le runner et exige que chaque nom posé soit lu et restauré, et qu'aucun ne porte de jalon. Preuve d'exécution : **manuelle sous Windows** ; la CI n'exécute aucun provider, et l'exécution de scip-java sous le bac à sable AppContainer est déclarée non qualifiée (`ManagedScipProviderRuntimeManager.java:104-105,291`).

### D5. Plugin : `class=` change, `id=` reste

Les huit actions gardent leur `id` ; la classe renommée garde son paquet. Un test du plugin compare l'ensemble des `id` d'action aux 16 attendus et vérifie que chaque `class=` désigne une classe existante.

### D6. Gate à liste décroissante

`scripts/quality/check-milestone-identifiers.py` (distinct de `check-milestone-artifact-references.py`), `--root` paramétrable, `--self-test` sur arbres temporaires, câblé dans `invariants`. Il applique la méthode d'inventaire, nomme fichier, ligne et identifiant de chaque violation, et affiche le nombre d'entrées de la liste. La liste ne peut que diminuer : chaque entrée porte sa raison ; une entrée qui ne correspond plus à rien, ou qui nomme un fichier introuvable, fait échouer le gate ; un fichier illisible aussi (fail-closed).

### D7. Surfaces publiques : renommage additif, ou non public

Règle : le renommage d'une surface publique (CLI, API Java, MCP, variable documentée dans `docs/user/`) garde l'ancien nom, déprécié, et figure dans une version. **Aucun renommage de ce lot n'en relève** : les signatures de `com.minos.api` ne nomment que `com.minos.api.*` et `MinosApplication` (`docs/user/java-api.md:334-335`) et `minos-api` ne contient aucun identifiant à jalon ; aucune variable `MINOS_M14_*` n'est documentée ; la CLI et le MCP n'émettent aucun de ces noms ; les `id` d'action IntelliJ restent. Aucun alias déprécié, comme pour les paquets internes renommés par l'ADR 0044 ; les méthodes renommées de `ScipIndexerCatalog` et `BuildDescriptorPolicy` sont signalées dans la section « Ruptures » de `docs/user/java-api.md` (tâche 6.1).

## Recouvrements avec d'autres changements actifs

- **`fixer-la-langue-des-commentaires`** : exclut aussi les commentaires qui citent un jalon (`proposal.md:33`, `design.md:149`) : aucun des deux changements ne les garde, risque assumé des deux côtés. Sa liste de fichiers `mixed` cite `ProjectFingerprintService` et `ScipProjectSnapshotLifecycle` (`design.md:125`), que ce changement modifie sans les renommer ; si elle venait à citer un fichier renommé ici (`MinosM21Actions.java`, `MinosM21Client.java`), l'entrée est renommée dans la même PR.
- **`garder-les-fonctions-de-scripts-jumelles`** : ne touche ni aux scripts de jalon gelés ni à `scripts/history/` ; aucun fichier commun.
- **`decouper-les-classes-de-plus-de-800-lignes`** : modifie `DefaultMinosApplicationComposer` (construction de `ManagedScipProviderRuntimeManager`), près de `:65,71` : conflit textuel possible, sans conflit de fond. Aucune classe renommée ici ne dépasse 800 lignes (`FileProjectFingerprintSnapshotStore` : 766).
- **`faire-consommer-l-indexation-aux-surfaces`** : modifie `MinosMcpTools` et `LocalMinosMultiRepositoryApi`, qui portent des chaînes à jalon ; recouvrement seulement si D3 retient un second lot, à ordonner après lui.
- **`lire-le-registre-sans-ecrire`** (`ScipProjectSnapshotLifecycle.java:51-64`) et **`executer-scip-java-sur-classpath-verifie`** (runner) : fichiers communs, zones distinctes ; rebaser avant la tâche concernée.

## Direction des dépendances

Aucune arête créée ni retirée : renommages internes à `minos-engine`, `minos-provider-scip`, `minos-storage-local` et à leurs appelants de `minos-bootstrap`, plus les tests de `minos-application` et `minos-app` (ADR 0022). Le plugin reste sans dépendance `com.minos:*`.

## Qualification des capacités

Sans objet : aucune capacité produit n'est ajoutée, retirée ni requalifiée. Les dispositions des providers portées par le catalogue ne changent pas.

## Windows et Linux

Le renommage Java et le gate valent sur les deux (le gate lit le texte des fichiers sans dépendre de l'OS). Le runner ne s'exécute que sous Windows : son test de cohérence tourne partout, sa preuve d'exécution est la tâche manuelle 4.2.

## Risks / Trade-offs

- **Runner sans exécution en CI** → test de cohérence des noms (rouge prouvé) et tâche manuelle 4.2 ; sans 4.2, le changement n'est pas archivé.
- **Faux positif futur du motif** (sigle `M` + chiffres) → entrée de liste avec raison, visible en revue.
- **Service IntelliJ renommé** → `MinosM21Client` est un service léger sans état : rien de persistant n'est perdu.
- **Conflits textuels** avec les changements listés ci-dessus → rebaser avant chaque tâche.
- **Historique** : `git log --follow` garde la trace des anciens noms ; `scripts/history/` n'est pas réécrit.
