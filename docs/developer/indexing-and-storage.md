# Indexation, lifecycle et stockage

M14 relie désormais le lifecycle interne historique à une exécution réelle de providers.

## Parcours autonome

```mermaid
sequenceDiagram
    participant C as ProjectIndexingUseCase
    participant D as ProjectDiscoveryService
    participant N as IndexerRegistry
    participant P as IncrementalIndexingPlanner
    participant L as IndexingLifecycleService
    participant E as IndexerExecutor
    participant ST as ScipProjectSnapshotLifecycle
    participant W as StagingSnapshotStores
    participant S as CodeKnowledgeSnapshotStore (actif)

    C->>D: discover(projectRoot)
    C->>N: negotiate(discovery, requirements)
    C->>P: plan(invalidation, negotiation)
    alt NONE
        P-->>C: no run
    else FULL / INCREMENTAL qualifié
        C->>L: executePlanned(...)
        loop providers
            L->>E: execute(request)
            E-->>L: stable IndexingArtifact
        end
        L->>ST: stage(artifacts)
        ST->>W: open(staged-snapshots/{run}/providers/…, puis …/project)
        ST-->>L: stagedSnapshotId
        L->>ST: promote(project, run, stagedSnapshot)
        ST->>W: open(staged-snapshots/{run}/project)
        ST->>S: publish active project snapshot
    end
```

Le chemin manuel M9 reste disponible séparément via `import-scip`.

## Discovery et négociation

`ProjectDiscoveryService` détecte langages, build systems et modules.

`IndexerRegistry` reste fournisseur-indépendant et choisit un provider uniquement si :

- le langage est supporté ;
- le build est compatible avec la qualification ;
- les capacités demandées sont présentes ;
- le niveau de qualification est admissible ;
- la priorité déterministe le place devant les autres candidats compatibles.

Un override CLI `--provider` réduit le catalogue considéré ; il ne contourne pas les contraintes de qualification.

## Runtime providers M14

Le runtime est séparé de l'orchestration :

```text
IndexerRegistry        = sait quel provider choisir
ProviderRuntimeManager = sait si son runtime est disponible/installable
IndexerExecutor        = sait exécuter un provider choisi
```

`ProcessIndexerExecutor` ne connaît aucun type SCIP. Il reçoit un `IndexerProcessPlan` construit par l'adapter provider.

Responsabilités génériques :

- working directory explicite ;
- arguments sans shell implicite ;
- adaptation `.cmd/.bat` Windows via `cmd.exe` lorsque nécessaire ;
- environnement contrôlé ;
- timeout ;
- stdout/stderr capturés ;
- destruction du process tree en cas de timeout ;
- préservation d'un artefact préexistant dans le projet ;
- copie du nouvel artefact sous `<MINOS_HOME>/runs/<runId>/<provider>/` ;
- métadonnées d'exécution et masquage minimal des arguments sensibles.

### Racines de lecture et fichiers masqués du plan

Un `IndexerProcessPlan` peut déclarer des **racines de lecture** (`readOnlyRoots`, répertoires que le provider lit sans jamais les écrire) et des **fichiers masqués** (`maskedFiles`, présentés vides). Le constructeur à cinq composantes n'en déclare aucun ; `withCommand` et `withEnvironment` les conservent, et chaque transformateur de plan passe par eux. `ProcessIndexerExecutor` refuse un plan dont un transformateur a perdu ces déclarations.

- `LinuxBubblewrapWorkerSandboxBackend` revérifie chaque racine juste avant de construire sa commande (chemin absolu, répertoire existant, aucun lien, composants appartenant à l'utilisateur ou à root et non inscriptibles par d'autres comptes, aucun chevauchement avec l'espace de travail, l'artefact, le run ou `MINOS_HOME`), la monte par `--ro-bind` vers elle-même après les montages inscriptibles, puis lie `/dev/null` sur chaque fichier masqué existant. Le réseau reste celui de la politique du plan.
- `WindowsAppContainerWorkerSandboxBackend`, `WindowsJobObjectProcessOwnership` et la propriété cgroup Linux sans bac à sable OS refusent un plan qui en déclare, avant tout fichier de plan, octroi ou processus.

Seul scip-java en déclare aujourd'hui (ADR SEC-0076) ; un autre usage (Gradle, NuGet) demande sa propre décision.

## Providers SCIP gérés

### TypeScript

Le runtime géré installe `@sourcegraph/scip-typescript` sous `MINOS_HOME/tools` sans modifier le PATH global.

MINOS exige `node`/`npm` mais ne lance jamais automatiquement l'installation des dépendances métier du projet.

### Java

Le runtime géré utilise Coursier pour lancer la version de `scip-java` verrouillée par M14.

Le projet doit fournir son JDK via `JAVA_HOME` et appartenir au périmètre de build qualifié. M14 ne transforme pas le runtime Java embarqué de MINOS en JDK du projet.

Hors Windows, le build Maven est hors ligne (ADR SEC-0076) : `ScipJavaMavenOffline` valide la désignation `minos.scip.java.maven-repository` (lue par `minos-bootstrap` dans les réglages de MINOS) et le Maven 3.9.x du `PATH`, écrit dans le run un `settings.xml` constant par `PrivateLocalStorage` et un dépôt de tête vide, puis le plan passe à scip-java, après `--`, `--batch-mode --offline -s … -gs … -Dmaven.repo.local=… -Dmaven.repo.local.tail=… clean verify -DskipTests`. Le dépôt désigné est la seule racine de lecture du plan ; `conf/settings.xml` et `conf/toolchains.xml` du Maven résolu sont masqués. Tout refus survient avant la copie du projet.

## Lifecycle d'un projet

```mermaid
stateDiagram-v2
    [*] --> NEVER_INDEXED
    NEVER_INDEXED --> INDEXING
    INDEXING --> READY: premier snapshot promu
    INDEXING --> FAILED: échec initial
    READY --> REFRESHING: refresh
    REFRESHING --> READY: nouveau snapshot promu
    REFRESHING --> STALE: refresh échoué
    STALE --> REFRESHING
    FAILED --> INDEXING
```

- `NEVER_INDEXED` : aucune connaissance active ;
- `INDEXING` : premier run en cours ;
- `REFRESHING` : refresh avec ancien snapshot encore actif ;
- `READY` : snapshot actif valide ;
- `STALE` : refresh échoué, ancien snapshot conservé ;
- `FAILED` : aucun snapshot utilisable après échec initial.

`FileIndexStateStore` rend ces états et les runs persistants entre processus CLI.

## États d'un run

```text
Status: RUNNING | SUCCEEDED | FAILED
Phase : PROVIDER_EXECUTION | STAGING | PROMOTION | COMPLETED
```

Un run `SUCCEEDED` est `COMPLETED` et possède un `activeSnapshotAfter`.

## Staging M14

Le chemin d'import direct historique :

```text
SCIP -> normalize -> active store
```

n'est plus utilisé par le parcours autonome.

Le parcours M14 est :

```text
artifact provider A ─┐
artifact provider B ─┼→ normalisation temporaire
                     ↓
            faits providers normalisés
                     ↓
            assemblage snapshot projet
                     ↓
                 staging
                     ↓
             promotion atomique
                     ↓
               active store
```

`ScipProjectSnapshotLifecycle` normalise chaque artefact dans un magasin de travail, puis publie le snapshot fusionné dans le magasin de mise en place ; la promotion le relit et le publie dans le magasin actif. Le cycle ne crée aucun magasin : il reçoit le magasin actif du backend choisi et ouvre ses magasins de travail par le port `StagingSnapshotStores` du moteur, sous `staged-snapshots/<run>` seulement. `minos-bootstrap` y passe `FileSymbolSnapshotStore::new` pour les deux backends : le travail de mise en place reste sur fichiers sous `MINOS_HOME`, et une promotion reprise par un autre processus relit le même arbre. Les collisions d'identifiants sont rejetées explicitement ; aucune fusion implicite contradictoire n'est effectuée.

## Promotion atomique

La règle ADR-0006 reste inchangée : **aucun nouveau snapshot n'est annoncé actif tant que tous les providers, le staging et la promotion n'ont pas réussi**.

En cas d'échec de refresh, l'ancien snapshot reste actif et l'état devient `STALE`.

## Fingerprints et indexation incrémentale

Le planner M7 reste source de vérité :

```text
NONE
FULL
INCREMENTAL
```

`INCREMENTAL` n'est permis que si tous les providers sélectionnés déclarent explicitement la capacité qualifiée.

Pour les runtimes M14 initiaux, cette capacité n'est pas revendiquée. Le comportement conservateur est donc :

```text
aucun changement -> NONE
changement       -> FULL
```

Le fingerprint n'est promu qu'après succès du run et si le workspace n'a pas changé pendant l'indexation.

## CLI

Parcours normal :

```text
minos index <project>
minos index <project> --dry-run
minos index <project> --force-full
```

Parcours de diagnostic/compatibilité :

```text
minos import-scip <project> --file <index.scip> --provider <id>
```

`index --scip` reste temporairement accepté avec warning de dépréciation.

## Stockage M14

Selon les fonctionnalités utilisées :

```text
<MINOS_HOME>/
├── registry/
├── symbol-snapshots/
├── fingerprint-snapshots/
├── index-state/
├── staged-snapshots/
├── runs/
└── tools/
```

Le code de requête dépend uniquement du snapshot MINOS actif, jamais du fichier SCIP brut.

## Cohérence concurrente

`IndexingLifecycleService` sérialise les runs par projet dans une instance : un projet déjà `INDEXING` ou `REFRESHING` ne peut pas démarrer un second run concurrent.

Le store persistant rend l'état observable entre processus. Une protection inter-processus stricte par verrou OS n'est pas ajoutée par M14 ; l'exploitation normale doit éviter deux commandes d'indexation concurrentes sur le même projet.

## Ajouter un provider

1. déclarer un `IndexerDescriptor` et ses capacités réellement prouvées ;
2. adapter/normaliser son format sans faire fuiter ses types dans `orchestration` ;
3. fournir un diagnostic/runtime géré ou externe ;
4. construire son `IndexerProcessPlan` ;
5. qualifier FULL et INCREMENTAL séparément ;
6. produire une fixture et un replay réel ;
7. conserver staging et promotion projet atomique.
