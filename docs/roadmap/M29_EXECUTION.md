# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — S1/S2 qualifiés ; S3/S4 PASS exact-head `3df1b40...` ; S5 PASS exact-head `0959fb9...` ; S6 Backend-agnostic MCP client integration implémenté sur un HEAD plus récent, qualification `run-s6.ps1` requise**  
Issue : **#107 — M29 — Autonomous Docker Runtime & Native Parity**  
Branche : **`m29-autonomous-docker-runtime`**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**

## Objectif produit

Faire du runtime Docker MINOS un **backend autonome de premier rang**, fonctionnellement équivalent au runtime natif pour administration, discovery, providers/indexation, snapshots, persistance, vector store, recherche structurée/sémantique/hybride, architecture/impact/ProgramGraph, MCP, clients IA et lifecycle d'installation.

À la fin de M29, choisir **Natif Windows** ou **Docker isolé** doit changer le lieu d'exécution, pas les capacités métier ni les résultats attendus.

## Baseline autoritative au démarrage

Audit du **2 août 2026** :

```text
main                             1adbc45339efe37cd26d1937025bfa69d7b57811
develop                          1adbc45339efe37cd26d1937025bfa69d7b57811
fix/v1.0.1-release-hardening     db33cae87b37f9c2c36e536c96a4ccb6e24df3e5
#106                             OPEN
#107                             OPEN
v1.0.1 tag/release               ABSENT / NON PUBLIÉE
branche M29 avant démarrage      ABSENTE
PR M29                           ABSENTE
```

M29 dépend des prérequis 1.0.1 installer/MCP/ownership mais **ne peut pas être intégré en contournant la résolution de 1.0.1**. Aucun workflow/PR/merge M29 ne doit être déclenché sans autorisation explicite.

## Invariants non négociables

1. Docker autonome : aucun index natif préalable.
2. Identités projet/workspace stables entre runtimes.
3. Chemins physiques non utilisés comme identité portable.
4. Snapshots structurés autoritatifs.
5. Vector store existant conservé : `index-v2.bin`, composants `float32`, scan exact ; résultats vectoriels `HEURISTIC`.
6. M29 **ne crée pas une nouvelle base vectorielle externe** et n'introduit ni ANN/HNSW/Lucene/vector DB sans nouvelle mesure.
7. Providers/toolchains MINOS préparés au BUILD/install ; aucun téléchargement implicite de provider en RUN. Les dépendances déclarées par le projet peuvent être résolues par le plan admin/indexation éphémère et sont mises en cache uniquement sous `/var/lib/minos`.
8. `network_mode: none` reste obligatoire pour le plan MCP query persistant, `minos-bootstrap`, `minos-tools-bootstrap` et `minos-provider-probe`. L'admin peut avoir un egress de dépendances projet ; cet egress n'est jamais exposé au MCP query.
9. Projets read-only dans le query plane **et** dans l'admin/indexing plane.
10. Rootfs read-only, `cap_drop: ALL`, `no-new-privileges: true`, tmpfs borné.
11. MCP query-only séparé du plan admin/indexation.
12. Copilot / Claude / Codex restent backend-agnostic via `minos.exe mcp`.
13. Aucun claim de parité sans rapport comparatif exact-head S8.
14. #98 reste indépendante et OPEN.

## Architecture cible

```text
Copilot / Claude / Codex
          |
          v
     minos.exe mcp
          |
    backend selection
       /       \
      /         \
 native         docker
   |              |
MCP Java     docker exec -i
                 |
             MCP Java
```

Administration Docker :

```text
host operator
     |
     v
prod-mcp-release.ps1 -Action Admin
     |
     v
minos-admin (ephemeral)
     |
MinosLauncher
```

## Avancement

| Sous-étape | Objet | État |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS exact-head `c7a4e944...` |
| M29-S2 | Project identity, path mapping & portable persistence | ✅ PASS exact-head `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | ✅ PASS exact-head `3df1b40...` |
| M29-S4 | Provider-complete Docker image | ✅ PASS exact-head `3df1b40...` |
| M29-S5 | Autonomous indexing & vector lifecycle | ✅ PASS exact-head `0959fb9...` |
| M29-S6 | Backend-agnostic MCP client integration | 🟨 implémenté ; `run-s6.ps1` requis |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

Un PASS sur un ancien HEAD reste une preuve historique mais ne qualifie pas automatiquement un HEAD modifié.

---

## M29-S1 — Backend contract & ADR — ✅ PASS

Implémenté : backend `native|docker`, `<MINOS_HOME>/runtime/backend.properties`, migration pré-M29 vers native explicite, parsing fail-closed, écriture atomique, routage `minos mcp` avant `MinosApplication.open`, probes Docker bornés, `docker exec -i`, aucun fallback Docker→native, ADR-0037.

Preuve exact-head :

```text
HEAD                         c7a4e94414f4e2b6e3a2a23beacd303ca740387e
mvnw.cmd clean verify        BUILD SUCCESS
13/13 modules                SUCCESS
McpBackendRouterTest         6/6 PASS
suite totale                 417 PASS
check-current-docs.py        SUCCESS
```

---

## M29-S2 — Project identity, path mapping & portable persistence — ✅ PASS

Mapping versionné :

```text
N:\workspace-dev ↔ /workspace/projects
```

Le registre portable persiste `rootRelativePath`, conserve projectId/workspaceId, migre les anciens `rootPath` avec backup `.m29-v1.bak` et remplacement atomique, et résout le chemin selon `MINOS_RUNTIME_LOCATION=native|docker`.

Preuve : `ProjectPathMappingTest` 4/4 PASS sur le même HEAD que S1.

---

## M29-S3 — Autonomous Docker administration plane — ✅ PASS

### Architecture

```text
minos-mcp             persistent query plane
minos-admin           ephemeral administration/indexing plane
minos-bootstrap       path-mapping bootstrap
minos-tools-bootstrap provider payload bootstrap
minos-provider-probe  offline provider probe
minos-provider-tools  isolated named volume
```

`minos-mcp` : `/var/lib/minos` RO, projets RO, provider tools RO, filesystem RO, `network_mode: none`, `cap_drop: ALL`, `no-new-privileges:true`.

`minos-admin` : état MINOS writable, projets RO, provider tools RO, rootfs RO + tmpfs, `cap_drop: ALL`, `no-new-privileges:true`, egress uniquement pour dépendances projet.

### Historique des défauts réellement atteints

```text
b780feb7d27bd34952d1952f8d80b06755980684
provider runtime is not ready: rust-analyzer-scip
missing Rust runtime requirements: cargo, rustc, rust-analyzer
```

Puis, sur l'image provider-complete `f39802e966370f0934436163eecc180e4d76a271` :

```text
/workspace/projects/minos-code-intelligence/target/scip-targetroot: Read-only file system
```

Le staging writable sous `/var/lib/minos/runs/<run-id>/scip-java/workspace` a corrigé ce défaut sans rendre le projet writable.

Checkpoint S4 historique `45536e2fc7d32ed67932e2715e458fa26a8239b1` : 7/7 providers READY, mais S3 échoue ensuite sur :

```text
workspace/mvnw
error=2, No such file or directory
```

Remédiation : `mvnw` / `mvnw.cmd` exclus du staging Linux, `.mvn` conservé, Apache Maven 3.9.16 de l'image utilisé. Le tmp Java/JNA est routé vers :

```text
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native
```

Le tmpfs général `/tmp` reste `noexec`.

Sur `0f5668f8ea10303a5df4cffd0e79376a21979fbd`, S3 a ensuite révélé le défaut monorepo : `scip-typescript` recevait la racine globale sans `tsconfig.json`/`package.json`. Ce défaut a été déplacé explicitement en S5, tandis que S3 a adopté la fixture contrôlée Java/Maven.

### Qualification finale S3

Exact head :

```text
3df1b40ca0daf50779596f6e955d966ed5eb4973
```

Preuve réelle Windows + Docker Desktop :

```text
fixture                    minos-code-intelligence/fixtures/java/java-multi-module
project registry fresh     count=0
project add/inspect        JAVA / MAVEN / moduleCount=3
provider                   scip-java READY
index                      SUCCEEDED
active snapshot            run-9d3493cd-82e1-4849-afa3-4b351464b41b
fingerprintPromoted        true
index-status               READY
semantic status            DISABLED — provider non configuré
hybrid status              READY_STRUCTURED_FALLBACK
MCP handshake #1           SUCCESS
query recreate             SUCCESS
project/snapshot persist   SAME IDs
MCP handshake #2           SUCCESS
```

Marqueur :

```text
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
```

---

## M29-S4 — Provider-complete Docker image — ✅ PASS

### Inventaire contractuel

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / release 2026-07-27 / commit 12c3381
Apache Maven         3.9.16
```

L'image prépare au BUILD JDK 24, Maven, Coursier/scip-java, Node/npm, Python/pip, scip-clang, .NET/scip-dotnet, Go/scip-go et Rust/cargo/rustc/rust-analyzer. Le probe `minos-provider-probe` reste `network_mode: none`.

L'image/workflow exportent `provider-inventory.json` et `provider-binary-sha256.txt`. La CLI impose `minos tools verify --all`.

### Qualification finale S4

Même exact head que S3 :

```text
HEAD                                   3df1b40ca0daf50779596f6e955d966ed5eb4973
PowerShell AST run-s3                  SUCCESS
Maven                                  13/13 SUCCESS
M29S3RunnerPowerShellHostContractTest  PASS
check-current-docs.py                  SUCCESS
Docker image                           31/31 FINISHED
Apache Maven                           3.9.16
provider probe offline                 SUCCESS
providers READY                        7/7
doctor.ready                           true
```

Marqueur :

```text
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

---

## M29-S5 — Autonomous Indexing & Vector Lifecycle — ✅ PASS

### 1. Routage provider → module/build root

La cause du défaut monorepo est corrigée sans créer de faux manifests à la racine.

`IndexerExecutionScopeResolver` transforme la négociation projet en scopes d'exécution :

```text
registeredProjectRoot
        |
        +-- provider A -> module/build root A -> projectRelativeRoot A
        +-- provider B -> module/build root B -> projectRelativeRoot B
        +-- provider B -> module/build root C -> projectRelativeRoot C
```

Un provider qui déclare explicitement `MULTI_MODULE` **et** un build system compatible à la racine peut exécuter une seule fois le reactor (ex. Maven/scip-java). Un provider sans contrat de build root global n'hérite jamais arbitrairement de la racine du monorepo.

`IndexingExecutionRequest` distingue désormais :

- `registeredProjectRoot` : racine persistée dans le registre ;
- `projectRoot` : racine réelle d'exécution provider ;
- `projectRelativeRoot` : position portable dans le projet enregistré.

Les anciennes signatures mono-root restent compatibles.

### 2. Artefacts scoped et identités projet

`ProcessIndexerExecutor` isole les exécutions imbriquées sous :

```text
/var/lib/minos/runs/<run-id>/<provider>/scopes/module-<sha16>
```

Le scope est porté par `IndexingArtifact` jusqu'au staging. Pour un artifact généré dans `ui/app`, un document provider `src/app.ts` devient côté MINOS :

```text
ui/app/src/app.ts
```

avant création du file ID, de l'occurrence et de l'identité structurelle path-based.

`ScipProjectSnapshotLifecycle` conserve un store temporaire séparé par provider+scope puis fusionne dans **un seul snapshot projet** avant promotion. Les faits strictement identiques sont dédupliqués ; une même ID avec deux valeurs divergentes reste une collision bloquante.

### 3. Promotion atomique / recovery

`IndexingLifecycleService` continue à ne promouvoir qu'après réussite de toutes les exécutions et du staging. `IndexingLifecycleScopedExecutionTest` ajoute la preuve suivante :

```text
snapshot A promu
→ nouveau run multi-scope
→ scope imbriqué échoue
→ run FAILED
→ ProjectIndexState STALE
→ activeSnapshotId reste snapshot A
```

L'incrémental multi-scope est explicitement rejeté tant qu'il n'est pas qualifié. Il ne doit pas être extrapolé : les providers actuels qui ne déclarent pas `INCREMENTAL_INDEXING` forcent le planner vers FULL. Les contrats historiques `NONE|FULL|INCREMENTAL` restent inchangés.

### 4. Fixture polyglotte qualifiée

```text
fixtures/polyglot/m29-scoped-modules
├── pom.xml
├── src/main/java/.../RootGreeting.java
└── ui
    ├── app
    │   ├── package.json
    │   ├── tsconfig.json
    │   └── src/app.ts
    └── lib
        ├── package.json
        ├── tsconfig.json
        └── src/lib.ts
```

Il n'existe volontairement **aucun** `package.json` ni `tsconfig.json` à la racine du projet. Le live gate a prouvé que scip-typescript s'exécute réellement sur `ui/app` et `ui/lib`.

### 5. Vector lifecycle Docker

Le workflow Docker persiste la sélection sémantique dans `.env` et `installation.json` **format 5**. Les modes packagés autorisés par S5 sont :

```text
disabled
local-hash
```

`local-hash` correspond à `minos-local-hash`, 384 dimensions. C'est un provider de référence déterministe, zéro-réseau, qui prouve le provider/store/search plumbing. **Ce n'est pas un modèle appris et ce n'est pas la preuve de qualité M23.**

Le store reste :

```text
/var/lib/minos/semantic-index/<projectId>/index-v2.bin
format v2
float32
exact scan
HEURISTIC result signal
```

### 6. Qualification finale S5

Exact head :

```text
0959fb9f64e2ecf61e20281f29c694e86d67c62b
```

Preuve Windows + Docker Desktop :

```text
Maven                               13/13 SUCCESS
S4 provider-complete                SUCCESS
project discovery                   JAVA + TYPESCRIPT / MAVEN + NPM / moduleCount=3
first index                         FULL / SUCCEEDED / READY
provider scopes                     scip-java=root ; scip-typescript=ui/app,ui/lib
semantic                            READY / minos-local-hash / 384 / 19 documents
vector store                        index-v2.bin / non vide
hybrid                              READY_WITH_SEMANTIC / HEURISTIC limitation
unchanged index                     NONE / NO_CHANGES / same snapshot
forced FULL                         SUCCEEDED / fresh snapshot
semantic after recovery             READY / fresh snapshot
query-plane recreate                semantic READY / hybrid READY_WITH_SEMANTIC
```

Marqueur :

```text
M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS
```

Rapport :

```text
target/m29/s5-qualification-0959fb9f64e2ecf61e20281f29c694e86d67c62b.json
```

---

## M29-S6 — Backend-agnostic MCP client integration — 🟨 IMPLÉMENTÉ / À QUALIFIER

### 1. Contrat client stable

Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop et Codex CLI/Desktop doivent tous utiliser exclusivement :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=<dataRoot>
```

Le client ne connaît ni le backend sélectionné, ni `docker exec`, ni le nom du conteneur, ni le fichier Compose. `MinosLauncher` résout `MINOS_HOME`, puis `McpBackendRouter` charge `<MINOS_HOME>/runtime/backend.properties` et choisit `native|docker`.

### 2. Verifier backend-agnostic

Nouveau verifier :

```text
scripts/install/verify-mcp-client-backend-routing.ps1
```

Il crée un environnement Windows isolé avec faux launchers Copilot/Claude/Codex, configure :

```text
Copilot JetBrains
Copilot CLI
Claude Code
Claude Desktop
Codex CLI
Codex Desktop
```

puis exige pour chaque surface `minos.exe mcp + MINOS_HOME`. Il interdit toute fuite de `docker exec` ou de `minos-mcp-prod` dans les configurations clientes.

Le verifier écrit ensuite dans le même `MINOS_HOME` :

```text
backend=native
→ hash de toutes les configurations clientes
backend=docker
→ mêmes hashes client byte-identical / byte-for-byte
```

Seul `backend.properties` change. Le verifier est ajouté à la chaîne `verify-mcp-client-preflight.ps1`, donc les futures constructions Windows l'exécutent via le gate d'intégration existant.

### 3. Contrat Maven

`M29McpClientBackendAgnosticContractTest` verrouille statiquement :

- JSON clients : `$MinosExe`, `args=@('mcp')`, `MINOS_HOME=$DataRoot` ;
- CLI clients : `minos.exe mcp` ;
- Codex Desktop TOML : `[mcp_servers.minos]`, `args=["mcp"]`, `MINOS_HOME` ;
- absence de `docker exec` dans les managers clients ;
- présence du verifier backend-routing et du runner S6.

### 4. Gate exact-head S6

Runner :

```text
scripts/m29/run-s6.ps1
```

Séquence :

```text
exact HEAD + clean worktree
→ PowerShell AST de tous les scripts MCP/S6
→ Maven clean verify (sauf si déjà exécuté sur le même HEAD)
→ check-current-docs.py toujours obligatoire
→ verify-mcp-client-integration.ps1
→ verify-mcp-client-preflight.ps1
   → Codex Desktop lifecycle
   → backend-routing verifier
   → installer template verifier
→ backend-routing verifier direct
→ worktree toujours clean
```

Aucun PASS S6 avant :

```text
M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS
```

Rapport attendu :

```text
target/m29/s6-qualification-<exact-head>.json
```

---

## M29-S7 — Installer, switching & lifecycle — ⬜

Le switching doit être transactionnel : prepare → validate → handshake → commit config → retrait ancien backend ; rollback en cas d'échec.

---

## M29-S8 — Native/Docker parity qualification — ⬜

Même corpus, même configuration métier, rapport machine-readable. Gate final :

```text
native result == docker result
```

aux seules différences explicitement autorisées de chemin physique, provenance runtime et timestamps près.

---

## Reliquats explicites

### #98 — OS sandbox réelle

#98 reste **OPEN**. M29 ne transforme pas une isolation process/best-effort en sandbox OS prouvée.

### 1.0.1

M29 ne publie pas `v1.0.1` et ne contourne pas #106. `v1.0.0` reste immuable.

## Séquence courante

```text
check-current-docs.py
→ mvnw.cmd clean verify
→ run-s6.ps1 exact-head -SkipMavenVerify
→ si et seulement si S6 SUCCESS : documenter PASS S6
→ S7
→ S8
→ autorisation explicite avant PR/CI/merge
```
