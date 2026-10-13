# Design

## Context

Sprint 11 « Hygiène de fond » de l'audit du 10 octobre 2026 : **AUD-QUA-08**. HEAD analysé : `949e1569` (`develop`). Chemins complets des fichiers cités par leur nom :

- `minos-intellij/src/main/java/com/minos/intellij/actions/MinosM21Actions.java`, `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosM21Client.java`, `minos-intellij/src/test/java/com/minos/intellij/protocol/MinosM21ClientTest.java` ;
- `minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java` ;
- `minos-engine/src/main/java/com/minos/incremental/BuildDescriptorPolicy.java`.

## État vérifié au HEAD

### AUD-QUA-08 — confirmé, périmètre plus large

- **Plugin** : les trois fichiers ci-dessus ; `minos-intellij/src/main/resources/META-INF/plugin.xml:39-41` et `:44-49` : 8 actions dont l'attribut `class` vaut `com.minos.intellij.actions.MinosM21Actions$…`, d'identifiants `Minos.ProgramGraph`, `Minos.ImpactV2`, `Minos.SecurityPaths`, `Minos.SemanticIndexStatus`, `Minos.SemanticIndexSync`, `Minos.SemanticSearch`, `Minos.HybridSearch`, `Minos.HybridContext`.
- **Catalogue** : `ScipIndexerCatalog.java:50` `qualifiedM17Providers`, `:58` `qualifiedM17Descriptors`, `:63` `qualifiedM24Providers`, `:72` `qualifiedM24Descriptors`, et `:41` `qualifiedM1Descriptors`. Les commentaires `:49` et `:62` disent ce que chacun est : catalogue historique gardé pour des rejeux de régression, et catalogue courant. Appelants de production : `minos-bootstrap/src/main/java/com/minos/bootstrap/DefaultMinosApplicationComposer.java:65,71` ; `minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java:42`.
- **Descripteurs de build** : `BuildDescriptorPolicy.java:15` et `:29` (constantes), `:54` `m17Defaults()` (« politique historique », `:53`), `:59` `m24Defaults()` (« défaut courant », `:58`). Appelants : `minos-engine/src/main/java/com/minos/incremental/ProjectFingerprintService.java:44`, `minos-engine/src/main/java/com/minos/orchestration/ProjectInvalidationService.java:36`, `minos-storage-local/src/main/java/com/minos/storage/local/incremental/FileProjectFingerprintSnapshotStore.java:74-75`.
- **Tests appelants** (recherche dans les fichiers suivis) : `M17ProviderPlatformTest`, `M24PolyglotProviderTest`, `ProviderCatalogPortTest` (`minos-app`), `ProviderPlatformDiagnosticRedactionTest` (`minos-bootstrap`), `ScipResumableArtifactCapabilityTest`, `EmbeddedToolsCatalogTest` (`minos-provider-scip`), `FileProjectFingerprintSnapshotStoreTest` (`minos-storage-local`).
- **Variables d'environnement** : `MINOS_M14_MAVEN_CMD`, `MINOS_M14_SCIP_JAVAC_EXE`, `MINOS_M14_BASH_EXE`, `MINOS_M14_SCIP_JAVAC_SCRIPT` dans `minos-provider-scip/src/main/resources/com/minos/adapter/scip/runtime/scip-java-windows-runner.ps1` (posées en `:129` et `:399-401`, relues par le code embarqué entre `:101` et `:173`, restaurées en `:442-444`). Aucune autre occurrence dans les fichiers suivis.
- **Chaînes publiques citant un jalon** : `minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java:119,125,131` (descriptions d'outils) ; `ScipIndexerCatalog.java:92-395` (limitations) ; `minos-engine/src/main/java/com/minos/dynamic/RuntimeObservationSession.java:43`, `minos-api/src/main/java/com/minos/api/LocalMinosMultiRepositoryApi.java:327`, `minos-application/src/main/java/com/minos/program/analysis/JavaTaintAnalyzer.java:257` (messages).
- **Ces chaînes sont figées** (relevé fait pendant la spécification) : la limitation « not claimed by M24 » figure dans les quatre goldens de `minos-app/src/test/resources/characterization/` (`api.golden`, `cli-json.golden`, `mcp-all-tools.golden`, `mcp.golden`) ; le message de `RuntimeObservationSession.java:43` est cité par `scripts/quality/check-runtime-dynamic-consistency.py`.
- **Gate littéral** : `scripts/docs/product-facts.py:60` cherche `qualifiedM17Providers` et `qualifiedM24Providers` pour dériver l'ordre des providers.

**Non vérifié** : aucun build, test ni gate exécuté ; le périmètre d'analyse SonarCloud du plugin ; l'effet d'un renommage de méthode sur la comparaison à la baseline SpotBugs (les fichiers de `quality/spotbugs-baseline/` portent des classes et des plages de lignes) ; le relevé des goldens ci-dessus ne couvre que les sept chaînes cherchées.

### Écarts avec l'action proposée par l'audit

1. **Deux méthodes omises** (`qualifiedM17Descriptors`, `qualifiedM24Descriptors`) et une troisième à un seul chiffre (`qualifiedM1Descriptors`) : un motif « `M` suivi de deux chiffres » ne la verrait pas.
2. **`legacyJavaProviders()` proposé par l'audit n'est pas établi** : le contenu exact du catalogue historique n'a pas été relu, et son commentaire (`:49`) parle de rejeux de régression, pas de Java. Le nom se déduit du rôle écrit, pas du jalon.
3. **Un gate cite les anciens noms** : `product-facts.py:60`. Sans mise à jour dans la même tâche, `product-facts.py --check` casse.
4. **Les variables `MINOS_M14_*`** portent un jalon et ne sont pas dans le constat. Elles sont internes au runner : renommage sans contrat, mais non vérifiable sous Linux.
5. **Les chaînes publiques** sont une sortie versionnée : les changer n'est pas un renommage, et des goldens les figent.
6. **`scripts/history/m21/check-m21-parity.py:56-58`** lit les trois fichiers du plugin par leur chemin. Ce script est de l'historique : son seul appelant (`scripts/history/m21/run-s6.ps1:40,48`) le cherche déjà à un chemin qui n'existe plus. Il n'est pas modifié.

## Goals / Non-Goals

**Goals** : plus aucun jalon dans un nom de fichier ni une déclaration de `src/main` ; convention gardée ; aucune sortie ni aucun identifiant d'action modifié.

**Non-Goals** : renommer les classes de test ; réécrire les commentaires ; toucher aux scripts gelés ou archivés.

## Decisions

### D1. Noms cibles, tirés du rôle écrit dans le code

| Actuel | Cible proposée | Source du rôle |
|---|---|---|
| `MinosM21Actions`, `MinosM21Client`, `MinosM21ClientTest` | `MinosAdvancedAnalysisActions`, `MinosAdvancedAnalysisClient`, `MinosAdvancedAnalysisClientTest` | groupes `Minos.Advanced.Popup` et `Minos.Semantic.Popup` de `plugin.xml` |
| `qualifiedM24Providers` / `…Descriptors` | `qualifiedProviders` / `qualifiedDescriptors` | `ScipIndexerCatalog.java:62` |
| `qualifiedM17Providers` / `…Descriptors` | `historicalReplayProviders` / `historicalReplayDescriptors` | `:49` |
| `m24Defaults()`, `m17Defaults()` | `defaults()`, `historicalDefaults()` | `BuildDescriptorPolicy.java:58`, `:53` |
| `M17_DEFAULT_FILE_NAMES`, `M24_ADDITIONAL_FILE_NAMES` | `HISTORICAL_DEFAULT_FILE_NAMES`, `ADDITIONAL_FILE_NAMES` | idem |

Les noms définitifs sont arrêtés en tâche 1.1 après relecture des corps ; la table est la proposition par défaut. Aucun alias déprécié n'est gardé : aucune de ces classes n'est une API publique.

### D2. Périmètre du gate et `qualifiedM1Descriptors` — **décision en attente du propriétaire**

Recommandation : le gate refuse un jalon de **un ou deux** chiffres (`M` ou `m` en début de mot d'identifiant, suivi de chiffres) dans les noms de fichiers et les déclarations de `*/src/main/**`, et `qualifiedM1Descriptors` est renommé avec les autres. Les faux positifs (sigles techniques) sont comptés en tâche 1.1 ; s'ils sont nombreux, le motif est resserré à deux chiffres et `qualifiedM1Descriptors` entre dans la liste décroissante avec sa raison.

Alternative : s'en tenir à deux chiffres (lettre de l'audit) et laisser `qualifiedM1Descriptors`.

### D3. Chaînes publiques citant un jalon — **décision en attente du propriétaire**

Recommandation : **hors de ce lot**. Elles sont une sortie publique (descriptions d'outils MCP, limitations, messages), figées par quatre goldens et par un gate ; les changer demande une note de version et la régénération des goldens. Le gate ne lit que les noms de fichiers et les déclarations, pas les littéraux.

Alternative : un second lot, après décision, qui remplace le jalon par la capacité dans chaque chaîne, met à jour goldens, gate et documentation utilisateur dans la même tâche.

### D4. Variables d'environnement du runner

`MINOS_M14_*` deviennent `MINOS_SCIP_JAVA_*` dans le seul runner (pose, lecture par le code embarqué, restauration). **Non relu** : la façon dont ces variables traversent les processus fils du runner ; la tâche 4.1 le relève avant de renommer. Validation par une indexation Java réelle sous Windows.

### D5. Plugin : `class=` change, `id=` reste

Les huit actions gardent leur `id`. Un test du plugin compare l'ensemble des identifiants d'action avant et après. La classe renommée garde son package.

### D6. Gate à liste décroissante

`scripts/quality/check-milestone-identifiers.py` (nouveau, `--self-test`, câblé dans `invariants`). La liste ne peut que diminuer : une entrée qui ne correspond plus à rien fait échouer le gate ; un fichier listé introuvable aussi.

## Direction des dépendances

Aucune arête créée ni retirée : renommages dans `minos-engine`, `minos-provider-scip`, `minos-storage-local`, `minos-bootstrap` (ADR 0022). Le plugin reste sans dépendance `com.minos:*`.

## Qualification des capacités

Sans objet : aucune capacité produit n'est ajoutée, retirée ni requalifiée. Les dispositions des providers (qualifié, partiel, non supporté) que porte le catalogue ne changent pas.

## Windows et Linux

Le renommage Java vaut sur les deux. Le runner `scip-java-windows-runner.ps1` ne s'exécute que sous Windows : sa preuve est une tâche manuelle sur poste Windows et le check `Verify (windows-2022)`. Le fichier reste en CRLF.

## Risks / Trade-offs

- **SonarCloud** : une classe renommée compte comme code neuf dans son module. Pour le plugin, le périmètre Sonar est non vérifié : à relever avant la PR ; si le plugin est analysé, ses tests doivent couvrir les deux classes renommées.
- **Baseline SpotBugs** : indexée par classe ; un renommage de méthode peut faire réapparaître une alerte comme nouvelle. À rejouer, sans masquer d'alerte.
- **Utilisateurs du plugin** : aucun effet attendu, les identifiants d'action étant conservés (c'est à eux que se lient raccourcis et personnalisations de menus).
- **Historique** : `git log --follow` reste la trace des anciens noms ; `scripts/history/` n'est pas réécrit.
