# 0042 — Racine de composition : où câbler les adaptateurs une fois `minos-application` réduite à ses ports

Status: Accepted (2026-09-27) — option (c) retenue par l'utilisateur : la racine de composition vit dans un
module dédié `minos-bootstrap`, découvert par `ServiceLoader` derrière `MinosApplication.open(home)` /
`Builder.build()`, et non dans `minos-app`.

**Raison de l'écart à la lettre de l'audit** (qui plaçait la racine de composition « dans `minos-app` ») :
une contrainte du graphe de dépendances Maven, pas une question de simplicité. Les surfaces (`minos-cli`,
`minos-api`, `minos-mcp`, `minos-nexus`) ouvrent l'application elles-mêmes, en production
(`MinosCliRunner`, `LocalAutonomousIndexOperations`, `MinosApiSupport`, `MinosMcpServer`, `MinosMcpTools`)
comme en test, et `minos-app` dépend d'elles : tout ce qu'elles doivent atteindre, même en portée `test`,
ne peut pas vivre dans `minos-app` sans cycle. `minos-app` reste l'assemblage final distribué (lanceur, JAR
ombré, backends optionnels comme PostgreSQL). La mise à jour du critère A2 de
[`../audit/AUDIT-2026-09.md`](../audit/AUDIT-2026-09.md) revient à l'utilisateur, à la clôture.

Conditions attachées à la décision (chacune prouvée par un test ou par le script de frontières) :

1. `minos-bootstrap` est le seul module non adaptateur qui connaît une classe concrète d'adaptateur
   (hors `minos-app`, assemblage final) ; `scripts/architecture/check-module-boundaries.py` l'écrit
   explicitement : `application ↛ adaptateurs`, `adaptateurs ↛ application`, `surfaces ↛ adaptateurs`, et
   `surfaces → minos-bootstrap` en portée `runtime` ou `test` seulement, jamais `compile`.
2. Découverte en échec rapide et déterministe : aucune implémentation → message qui nomme le module
   manquant ; plusieurs → refus, jamais un choix arbitraire ; aucun chemin dans les messages
   (`MinosApplicationComposersTest`).
3. Le JAR distribué est couvert par un test de contrat de packaging (`ShadedJarCompositionRootIT`). La
   distribution `jpackage` l'est par transitivité : elle empaquette ce même `minos-code-intelligence-*-all.jar`,
   copié tel quel en `minos.jar` puis passé à `jpackage --main-jar minos.jar`
   (`scripts/release/build-windows-distribution.ps1:221,253,264`) ; aucun test existant n'exécute le
   runtime `jpackage` lui-même.
4. `docs/user/java-api.md` indique que `minos-bootstrap` est requis au classpath de l'API Java embarquée.

Relu sur la branche `hautes/impl-hexagone` au commit `00b2236a`.

## 1. Contexte

La cible A2 est : `minos-application` n'expose que des ports et ne dépend d'aucun adaptateur
(`minos-runtime-local`, `minos-storage-local`, `minos-storage-postgresql`, `minos-provider-scip`,
`minos-integration-git`) ; aucun adaptateur ne dépend de `minos-application` ; **la racine de
composition vit dans `minos-app`** ; `scripts/architecture/check-module-boundaries.py` verrouille ces
deux interdits.

### 1.1 Ce qui est déjà fait (sans changement de comportement)

Caractérisation préalable (17 tests, 12 golden : sorties CLI texte/JSON, `doctor`, sémantique, les 31
outils MCP, API Java et team, graphe de programme Java, observations runtime, git, rétention, ordre de
composition), puis déplacements par petits commits :

| Étape | Effet |
|---|---|
| modèles `com.minos.store` → `minos-engine` | `CodeKnowledgeSnapshotStore` et sa fermeture (6 classes) |
| ports de persistance → `minos-engine` | `StorageBackend*`, `IndexStateStore`, `ProjectRegistry`, empreintes, `MinosRuntimeSettings`… (20 classes) |
| adaptateurs fichiers → `minos-storage-local` | `FileIndexStateStore`, `LocalProjectRegistry`, `FileProjectFingerprintSnapshotStore`, `LocalStorageBackend`… (11 classes) |
| `minos-storage-postgresql` ↛ `minos-application` | dépendance retirée ; le test application × PostgreSQL rejoint `minos-bootstrap` en portée `test` (d'abord `minos-app` au tour 1, puis `minos-bootstrap` depuis `d93cfcfe`, décision C5) |
| port `IndexerProviderCatalog` | `ProviderPlatformService` ne connaît plus `ScipIndexerCatalog` |
| `EmbeddingProvider.limitations()` | plus d'`instanceof LocalHash/Ollama` dans `SemanticIndexService` |

### 1.2 Ce qui reste, et pourquoi c'est une décision

Après ces étapes, `minos-application` ne référence plus les adaptateurs qu'en **six** fichiers de
production :

| Fichier | Référence concrète | Nature |
|---|---|---|
| `MinosApplicationAssembler` | `ScipIndexerCatalog`, `Managed{Scip,ScipPython,PolyglotScip}RuntimeManager`, `CompositeProviderRuntimeManager`, `ScipProjectSnapshotLifecycle`, `GitIntelligenceService`, `FileHostedControlPlaneStore`, `new LocalStorageBackend(home)` | **câblage** (valeurs par défaut du `Builder`) |
| `StorageBackends` | raccourci `"local"` → `LocalStorageBackend`, puis `ServiceLoader` | **câblage** |
| `MinosApplicationRuntimeConfiguration` | `new EnvironmentHostedTenantKeyProvider()` | **câblage** |
| `MinosApplication` | types `ProviderRuntimeManager` (runtime-local) et `GitIntelligenceService` (integration-git) dans ses signatures publiques | **port manquant** |
| `ProviderPlatformService` | `ProviderRuntimeManager`, `ProviderRuntimeStatus` | **port manquant** |
| `LocalProjectOperations` | `ScipSymbolSnapshotImporter/Report/Request` (cas d'usage `import-scip`) | **port manquant** |

Les ports manquants se traitent comme les étapes déjà faites (descente en `minos-engine`, voir § 5).
Le **câblage**, lui, doit quitter `minos-application`, et c'est là que la cible bute sur le graphe
Maven :

```
minos-app ──► minos-cli, minos-api, minos-mcp, minos-nexus ──► minos-application
```

Les surfaces ouvrent l'application elles-mêmes par `MinosApplication.open(home)` :

| Module | Code de production | Tests |
|---|---|---|
| `minos-cli` | `MinosCliRunner:66`, `LocalAutonomousIndexOperations:67` | 6 classes |
| `minos-api` | `MinosApiSupport:80` (derrière les constructeurs publics `LocalMinosApi(Path)`, `LocalMinosMultiRepositoryApi(Path)`, `LocalProviderPlatformApi(Path)`) | 10 classes (V43 ; 8 comptaient seulement `MinosApplication.open/builder`) |
| `minos-mcp` | `MinosMcpServer:45`, `MinosMcpTools:62` (constructeur public `MinosMcpTools(Path)`) | 4 classes |
| `minos-nexus` | — | 1 classe |
| `minos-app` | `McpBackendRouter:28`, `MinosLauncher:43` | 5 classes + caractérisation |
| `minos-application` | — | 5 classes (`MinosApplication.builder(...)`) |

Si le câblage part dans `minos-app`, **aucune surface ni aucun de ses tests ne peut plus l'atteindre** :
une dépendance `cli → app`, même en portée `test`, ferme un cycle que Maven refuse. De plus
`MinosApplication.builder(home).build()` sans surcharge — utilisé par des tests de toutes les surfaces
— tire aujourd'hui ses valeurs par défaut de ce câblage.

S'y ajoutent deux dettes de tests qui suivront la décision : **45 classes de test** de
`minos-application` doivent quitter le module (V43) — 41 nomment un adaptateur (40 `minos-storage-local`,
3 `minos-runtime-local`, 1 `minos-provider-scip`), dont `ResumeCrashFixtureMain` (V27,
`FileResumableRunMarkers`), et 4 utilisent le câblage par défaut sans nommer d'adaptateur
(`ProgramGraphPerformanceQualificationTest`, `M23SemanticProviderConfigurationTest`,
`SemanticHybridIntelligenceTest`, `SemanticSyncConsistencyTest`) ; elles ne compileraient ou ne
s'exécuteraient plus sans adaptateur ni composition, et doivent être déplacées vers le module qui porte le
câblage (ou vers l'adaptateur qu'elles testent).

## 2. Contraintes communes à toutes les options

- **Ordre d'initialisation inchangé** (figé par `A2CompositionCharacterizationTest`) : `open(home)` =
  `ensurePrivateDirectory` → `MinosRuntimeSettings.load` → `StorageBackends.open` → `builder` →
  `MinosApplicationRuntimeConfiguration.apply` → `build` ; l'assembleur consulte le backend dans l'ordre
  registre, snapshots, état d'index, empreintes, sémantique, observations runtime, rétention, puis
  `id()` ; gestionnaires de runtime scip-java, python, polyglotte ; rétention `noOp` dès qu'un magasin
  est surchargé ; tout échec après la sélection referme le backend ; `close()` idempotent, ne ferme que
  le backend. Chaque option **déplace** le code de l'assembleur tel quel ; aucune ne le réécrit.
- `StorageBackends` : le raccourci `"local"` exact (après normalisation en minuscules par
  `StorageBackendConfiguration`) précède le `ServiceLoader` ; le fichier `META-INF/services` de
  PostgreSQL doit rester fusionné dans le jar ombré (`ServicesResourceTransformer`, déjà en place).
- API publique `minos-api` : les constructeurs `LocalMinosApi(Path)`, `LocalMinosMultiRepositoryApi(Path)`,
  `LocalProviderPlatformApi(Path)` et `MinosMcpTools(Path)` doivent continuer de fonctionner.
- `check-module-boundaries.py` durci (prêt, non engagé) : `minos-application` ne dépend d'aucun
  adaptateur, aucun adaptateur ne dépend de `minos-application`, toutes portées confondues, et aucune
  dépendance interne cachée dans `<profiles>` ou `<dependencyManagement>`.

## 3. Options

### (a) SPI `ServiceLoader` : port `MinosApplicationComposer` dans `minos-application`, implémentation dans `minos-app`

`MinosApplication.open(home)` et `Builder.build()` gardent leur signature ; ils délèguent les valeurs par
défaut à un `MinosApplicationComposer` découvert par `ServiceLoader`, implémenté dans `minos-app` et
déclaré dans `minos-app/src/main/resources/META-INF/services`.

- **Fichiers** : `minos-application` 3 (port, `MinosApplication`, assembleur réduit aux surcharges) ;
  `minos-app` 3 (composer, `StorageBackends` local, fichier de service) ; `MinosApplicationRuntimeConfiguration`
  scindé (≈ 8 fichiers de production).
- **Ordre d'initialisation** : préservé si le composer est appelé au même point ; une recherche
  `ServiceLoader` s'ajoute au démarrage. Échec nouveau : composer absent → erreur à l'exécution au lieu
  d'une erreur de compilation.
- **Tests des surfaces** : cli, api, mcp et nexus **n'ont pas `minos-app` sur leur classpath** — leurs
  21 classes de test qui ouvrent une application réelle (cli 6, api 10, mcp 4, nexus 1) échouent. Pour les sauver, il faut soit les
  déplacer dans `minos-app` (voir d), soit écrire dans chaque surface un composer de test qui recâble
  les adaptateurs (4 copies du câblage, et des dépendances de test vers les adaptateurs dans `minos-mcp`
  et `minos-api`, qui n'en ont pas ou pas toutes aujourd'hui).
- **API publique** : signatures inchangées, mais `new LocalMinosApi(home)` utilisé hors du jar
  `minos-app` échoue désormais (aucun composer) — rupture de comportement pour un consommateur de la
  bibliothèque `minos-api`, à lister.
- **Conformité à la cible** : littérale (câblage dans `minos-app`).

### (b) Injection explicite d'une fabrique d'application

Les surfaces reçoivent une `MinosApplicationFactory` (`Path → MinosApplication`) ; `minos-app` la fournit.

- **Fichiers** : ≈ 12 fichiers de production (7 points d'ouverture et leurs appelants, constructeurs
  publics), ≈ 40 classes de test.
- **Ordre d'initialisation** : préservé (même code, appelé par la fabrique).
- **Tests des surfaces** : même impasse qu'en (a) — la fabrique de production est dans `minos-app`,
  invisible ; chaque test doit construire sa propre fabrique avec des adaptateurs.
- **API publique** : `LocalMinosApi(Path)`, `LocalMinosMultiRepositoryApi(Path)`,
  `LocalProviderPlatformApi(Path)` et `MinosMcpTools(Path)` n'ont plus de quoi ouvrir une application :
  à supprimer (rupture) ou à garder via un SPI (on retombe sur a).
- **Conformité à la cible** : littérale, au prix de la plus grosse rupture.

### (c) Module de composition dédié, `minos-bootstrap`, entre adaptateurs et surfaces

Nouveau module `minos-bootstrap` → `minos-application` + les quatre adaptateurs locaux ; il reçoit
**tels quels** `MinosApplicationAssembler` (valeurs par défaut), le raccourci local de `StorageBackends`
et la partie « adaptateurs » de `MinosApplicationRuntimeConfiguration`. `MinosApplication.open(home)` et
`Builder.build()` gardent leur signature et délèguent à un `MinosApplicationComposer` découvert par
`ServiceLoader`, implémenté dans `minos-bootstrap`. Les surfaces (cli, api, mcp, et nexus en portée
test) déclarent `minos-bootstrap` ; `minos-app` reste l'assemblage distribuable (lanceur, jar ombré,
backends optionnels comme PostgreSQL chargés par `ServiceLoader`).

- **Fichiers** : 1 POM nouveau + POM racine (`<module>`) ; 4 POM de surfaces + `minos-app` (+1
  dépendance chacun) ; 3 classes déplacées, 1 port et 1 composer créés, 1 fichier de service ;
  `MinosApplication` (délégation) ; `check-module-boundaries.py` (module et politique) et diagramme
  généré. ≈ 15 fichiers ; **aucune classe de test des surfaces modifiée** ; les 45 tests d'application
  couplés aux adaptateurs déménagent dans `minos-bootstrap` (ou l'adaptateur testé), V27 compris.
- **Ordre d'initialisation** : préservé (code déplacé verbatim, même point d'appel) ; une recherche
  `ServiceLoader` au démarrage, déjà pratiquée pour PostgreSQL.
- **Tests des surfaces** : inchangés — `minos-bootstrap` est sur leur classpath.
- **Risque partagé avec (a) : la découverte.** (c) repose sur le même `ServiceLoader` que (a) : un
  classpath sans `minos-bootstrap` (ou avec deux racines) casserait l'ouverture. Parade exigée par
  l'utilisateur et mise en œuvre : échec explicite et immédiat pour zéro composer (message qui nomme
  `minos-bootstrap`), refus pour plusieurs (jamais un choix arbitraire), avant tout effet de bord sur le
  MINOS_HOME, et un test de contrat de packaging sur le JAR distribué.
- **API publique** : inchangée, y compris pour un consommateur de `minos-api` hors `minos-app`
  (`minos-api` tire `minos-bootstrap`).
- **Écart à la cible** : la racine de composition n'est pas dans `minos-app` mais juste en dessous des
  surfaces. Justification : `minos-app` est *au-dessus* des surfaces ; tout ce qu'elles doivent atteindre
  à l'exécution et en test ne peut pas y vivre sans cycle. L'esprit de la cible — application réduite aux
  ports, câblage concentré en un seul endroit hors de l'application, verrouillé par le script — est
  respecté ; `minos-app` garde le rôle d'assemblage final (quels adaptateurs optionnels sont livrés).

Variante (c′) sans SPI : les surfaces appellent `MinosBootstrap.open(home)` explicitement. Vérifié à la
compilation, mais `MinosApplication.open` disparaît (rupture de l'API de `minos-application`) et ≈ 7
appels de production + ≈ 30 tests + 3 sondes `scripts/m16`, `scripts/m21` changent, ainsi que
`scripts/m15/run-s3.ps1:90,154`, qui vérifie mot pour mot la signature `MinosApplication.open(Path home)`
et l'appel `MinosApplication.open(home)` du lanceur.

### (d) Autres options examinées

- **(d1) Câblage dans `minos-app` + tous les tests « application réelle » des surfaces déplacés dans
  `minos-app`** (SPI de a). Conformité littérale ; 21 classes de test déplacées hors de leur module (les
  packages `com.minos.cli/api/mcp` de test deviennent partagés), perte de la couverture par module, et la
  rupture « `minos-api` hors `minos-app` » de (a) demeure.
- **(d2) Garder le câblage dans `minos-application` avec des dépendances `runtime`/`provided`** : ne
  compile pas (l'assembleur instancie les classes), et le script durci l'interdit à juste titre.
- **(d3) Statu quo documenté** : l'application reste la racine de composition ; seuls les ports et les
  adaptateurs fichiers ont bougé. Le constat A2 resterait ouvert.

## 4. Recommandation

**(c), avec le SPI** : un module `minos-bootstrap` porte le câblage déplacé verbatim, découvert par
`ServiceLoader` derrière `MinosApplication.open(home)` / `Builder.build()`.

C'est la seule option qui réduit `minos-application` à ses ports **sans toucher aux tests des surfaces
ni à l'API publique**, avec un ordre d'initialisation préservé par construction (code déplacé, même
point d'appel) et vérifié par la caractérisation. Le prix est un module de plus et un écart assumé à la
lettre de la cible (« dans `minos-app` »), écart imposé par le graphe Maven et non par commodité. (a) et
(b) conservent la lettre mais cassent les tests de quatre surfaces ou l'API publique ; (d1) déplace 21
tests hors de leur module.

**Question de décision (tranchée : oui, option c)** : acceptez-vous que la racine de composition vive dans un nouveau module
`minos-bootstrap` (entre les adaptateurs et les surfaces, découvert par `ServiceLoader` derrière
`MinosApplication.open`) plutôt que dans `minos-app`, écart assumé à la cible de l'audit ?

## 5. Suite, quelle que soit l'option

1. Ports manquants, descendus en `minos-engine` comme les précédents : `ProviderRuntimeManager` /
   `ProviderRuntimeStatus` (crée un package `com.minos.runtime` de plus dans engine — A3), un port Git
   (`GitIntelligenceService` est une classe concrète exposée par `MinosApplication.gitIntelligence()` :
   changer le type de retour est une rupture de l'API de `minos-application`, appelants
   `MinosCliRunner`, `LocalMinosMultiRepositoryApi`), un port d'import SCIP pour `LocalProjectOperations`.
2. Déplacement du câblage selon l'option retenue ; retrait des quatre dépendances
   `application → adaptateurs` ; déménagement des 45 tests couplés (dont V27).
3. Engagement de `check-module-boundaries.py` durci, qui passe alors au vert — jamais assoupli.

## 6. Conséquences

- Positives : frontières hexagonales vérifiées par le script ; les adaptateurs deviennent
  interchangeables sans recompiler l'application.
- Négatives : un module de plus (option c) ; packages partagés supplémentaires, conséquence de la règle
  « même package » — à résorber avec A3 : `com.minos.incremental`, `com.minos.registry`,
  `com.minos.storage` (engine, application, storage-local), `com.minos.orchestration` (engine,
  application, storage-local), `com.minos.runtime` (engine, application, runtime-local), `com.minos.git`
  (engine, integration-git), `com.minos.adapter.scip` (engine, provider-scip).
- **Couplage caché désormais inter-JAR (A3, à noter, sans ajout de `public`)** : des membres
  package-private d'une classe descendue dans `minos-engine` sont appelés depuis un autre module du même
  package — `IndexingRun.portable` (engine) par `FileIndexStateStore` (storage-local) ;
  `FileFingerprint.requireSha256` (engine) par `FileProjectFingerprintSnapshotStore` (storage-local) et
  `ProjectChangeSet` (application). Cela fonctionne sur le classpath (même chargeur, JAR non scellés) mais
  interdit un passage à JPMS tant que A3 n'est pas traité.

## 7. Mise en œuvre (tour 2)

Déplacements verbatim, chacun vert sur `./mvnw -pl minos-app,minos-bootstrap -am test`, sorties de
caractérisation identiques par SHA-256 (seule exception : V41, qui a réduit le masquage de
`totalDurationNanos`, prouvé identique sur la base `5b26631c`) :

- ports descendus en `minos-engine` : `ProviderRuntimeManager`/`ProviderRuntimeStatus`, `GitIntelligence`
  (records de requête et de rapport compris ; **rupture** : `MinosApplication.gitIntelligence()` rend
  `GitIntelligence` au lieu de `GitIntelligenceService`, l'application ne pouvant plus exposer un type
  d'adaptateur), `ScipArtifactImporter` (avec `ScipSymbolSnapshotRequest/Report`) ;
- `minos-application` : port `MinosApplicationComposer` et découverte `MinosApplicationComposers`
  (0 → échec qui nomme `minos-bootstrap`, plusieurs → refus) ; `open(home)`, `Builder.build()` et
  `StorageBackends.open` gardent leur signature ; plus aucune dépendance d'adaptateur ;
- `minos-bootstrap` : `DefaultMinosApplicationComposer` et `StorageBackendSelection` (code déplacé de
  l'assembleur, de `StorageBackends` et de la configuration runtime), enregistré par `META-INF/services` ;
  45 classes de test d'application déplacées (26 ici, 19 dans `minos-storage-local`) ; test
  application × PostgreSQL (C5) en portée `test` ;
- surfaces : `minos-bootstrap` en portée `runtime` (cli, api, mcp) ou `test` (nexus) ; `minos-api`,
  `minos-nexus` et `minos-mcp` ne dépendent plus d'aucun adaptateur.

**Reste ouvert — lot `minos-cli ↛ adaptateurs`** (chiffré, en attente d'accord) : `minos-cli` garde
`minos-runtime-local` et `minos-integration-git` pour trois classes de production —
`RunDirectoryResumableRunMarkers` (marqueurs de run R1, à fournir par la composition),
`DoctorCommand` (sonde des sandboxes `WorkerSandboxBackends` et `CommandLocator`, à passer derrière un port)
et `LocalRemoteIndexOperations` (câblage de l'indexation distante M25 : `JGitRemoteRepositoryMaterializer`,
`DistributedArtifactBundleStore`, `LocalIsolatedIndexWorker`, `DistributedIndexerExecutor`,
`WorkerSandboxSelection`). Tant qu'il n'est pas fait, le script de frontières durci reste rouge sur ces
deux arêtes et n'est pas engagé.
