# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — S1/S2 qualifiés ; S3/S4 PASS exact-head `3df1b40...` ; S5 PASS exact-head `0959fb9...` ; S6 PASS exact-head `f7ef0e3...` ; S7 PASS exact-head `50b462f...` ; S8 à qualifier**  
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
8. `network_mode: none` reste obligatoire pour le plan MCP query persistant, `minos-bootstrap`, `minos-tools-bootstrap` et `minos-provider-probe`.
9. Projets read-only dans le query plane **et** dans l'admin/indexing plane.
10. Rootfs read-only, `cap_drop: ALL`, `no-new-privileges: true`, tmpfs borné.
11. MCP query-only séparé du plan admin/indexation.
12. Copilot / Claude / Codex restent backend-agnostic via `minos.exe mcp`.
13. Aucun fallback Docker→native silencieux.
14. Aucun claim de parité sans rapport comparatif exact-head S8.
15. #98 reste indépendante et OPEN.

## Architecture cible

```text
Copilot / Claude / Codex
          |
          v
     minos.exe mcp
          |
    backend.properties
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
| M29-S6 | Backend-agnostic MCP client integration | ✅ PASS exact-head `f7ef0e3...` |
| M29-S7 | Installer, switching & lifecycle | ✅ PASS exact-head `50b462f...` |
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

### Qualification finale S3

Exact head : `3df1b40ca0daf50779596f6e955d966ed5eb4973`.

```text
fixture                    minos-code-intelligence/fixtures/java/java-multi-module
project registry fresh     count=0
project add/inspect        JAVA / MAVEN / moduleCount=3
provider                   scip-java READY
index                      SUCCEEDED
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

Inventaire :

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

L'image exporte `provider-inventory.json` et `provider-binary-sha256.txt`. La CLI impose `minos tools verify --all`.

Qualification sur le même exact head que S3 : Maven 13/13 SUCCESS, Docker image 31/31, provider probe offline SUCCESS, 7/7 providers READY, `doctor.ready=true`.

Marqueur :

```text
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

---

## M29-S5 — Autonomous Indexing & Vector Lifecycle — ✅ PASS

### Routage provider → module/build root

`IndexerExecutionScopeResolver` transforme la négociation projet en scopes d'exécution :

```text
registeredProjectRoot
        |
        +-- provider A -> module/build root A -> projectRelativeRoot A
        +-- provider B -> module/build root B -> projectRelativeRoot B
        +-- provider B -> module/build root C -> projectRelativeRoot C
```

`IndexingExecutionRequest` distingue `registeredProjectRoot`, `projectRoot` et `projectRelativeRoot`. `IndexingLifecycleScopedExecutionTest` prouve qu'un échec multi-scope conserve le snapshot actif précédent.

Fixture qualifiée :

```text
fixtures/polyglot/m29-scoped-modules
root       Maven / Java
ui/app     NPM / TypeScript
ui/lib     NPM / TypeScript
```

Aucun `package.json` ni `tsconfig.json` n'existe à la racine globale.

### Vector lifecycle Docker

Le workflow persiste le provider sémantique dans `.env` et `installation.json` **format 5**. Modes S5 :

```text
disabled
local-hash
```

`local-hash` correspond à `minos-local-hash`, **384 dimensions**. Le store reste :

```text
/var/lib/minos/semantic-index/<projectId>/index-v2.bin
format v2
float32
exact scan
HEURISTIC result signal
```

Preuve exact-head `0959fb9f64e2ecf61e20281f29c694e86d67c62b` :

```text
Maven                               13/13 SUCCESS
S4 provider-complete                SUCCESS
provider scopes                     scip-java=root ; scip-typescript=ui/app,ui/lib
first index                         FULL / SUCCEEDED / READY
semantic status                     READY / minos-local-hash / 384 dimensions / 19 documents
vector store                        index-v2.bin / non vide
hybrid status                       READY_WITH_SEMANTIC / HEURISTIC limitation
unchanged index                     NONE / NO_CHANGES / same snapshot
forced FULL                         SUCCEEDED / fresh snapshot
query-plane recreate                semantic READY / hybrid READY_WITH_SEMANTIC
```

Marqueur :

```text
M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS
```

---

## M29-S6 — Backend-agnostic MCP client integration — ✅ PASS

### Contrat client stable

Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop et Codex CLI/Desktop utilisent :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=<dataRoot>
```

Le client ne connaît ni le backend sélectionné, ni `docker exec`, ni le nom du conteneur, ni Compose. `McpBackendRouter` charge `<MINOS_HOME>/runtime/backend.properties` et choisit `native|docker`.

Verifier :

```text
scripts/install/verify-mcp-client-backend-routing.ps1
```

Il bascule `backend=native` vers `backend=docker` et exige des configurations clientes **byte-identical**. `M29McpClientBackendAgnosticContractTest` verrouille ce contrat côté Maven.

Qualification exact-head :

```text
f7ef0e3dbe820253decd83a1dc27bf2651ef6de9
```

Preuve Windows :

```text
PowerShell AST                            SUCCESS
Maven 13/13                               SUCCESS
check-current-docs.py                     SUCCESS
MCP client integration                    SUCCESS
MCP client preflight                      SUCCESS
Codex Desktop lifecycle                   SUCCESS
backend-routing verifier                  SUCCESS
installer template verifier               SUCCESS
```

Gate utilisé : `scripts/m29/run-s6.ps1`.

Marqueur :

```text
M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS
```

Rapport :

```text
target/m29/s6-qualification-f7ef0e3dbe820253decd83a1dc27bf2651ef6de9.json
```

---

## M29-S7 — Installer, switching & lifecycle — ✅ PASS exact-head `50b462f8253dc560fe8a35ef9d47e30fe25dbfe4`

### 1. Orchestrateur transactionnel

Nouveau point de contrôle :

```text
scripts/install/switch-mcp-backend.ps1
```

Transaction :

```text
prepare -> validate -> handshake -> commit backend.properties -> retire ancien backend
```

Le handshake candidat est exécuté par :

```text
scripts/install/probe-mcp-backend.ps1
```

Il passe par le même point d'entrée `minos.exe mcp`, avec un `MINOS_HOME` candidat isolé, puis exige :

```text
initialize
→ notifications/initialized
→ tools/list
→ minos_search_code
→ minos_impact
```

`backend.properties` n'est modifié qu'après réussite du candidat. En cas d'échec avant commit, l'ancien backend reste actif. En cas d'échec après commit, `Restore-BackendConfiguration` restaure les octets précédents.

### 2. Docker : reuse et upgrade rollback

Un runtime Docker déjà géré est réutilisable seulement si son marker concorde avec le `VERSION`, le commit, `ProjectsRoot`, `DockerInstallRoot`, `DockerDataRoot`, `containerName` et `composeProject` du package courant.

Le chemin reuse fait :

```text
Start → Validate → handshake → commit
```

sans reconstruire l'image.

Un vrai upgrade Docker→Docker commence par `New-DockerRuntimeSnapshot`. Si le nouveau runtime ou son handshake échoue :

```text
stop candidat
→ Restore-DockerRuntimeSnapshot
→ restore marker
→ restart ancien Docker
→ backend.properties précédent conservé/restauré
```

Le verifier `scripts/install/verify-mcp-backend-lifecycle.ps1` injecte les pannes avant/après commit et verrouille aussi qu'une configuration tierce reste byte-identical.

### 3. Setup Windows

La page **Mode MCP** contient exactement trois choix exclusifs :

```text
MCP natif Windows — recommandé
MCP Docker — isolation renforcée
Ne pas configurer maintenant
```

Lors d'un upgrade, le backend existant dans `%LOCALAPPDATA%\MINOS\data\runtime\backend.properties` est présélectionné.

La page **Clients IA** est commune aux deux backends. Copilot, Claude et Codex restent branchés sur `minos.exe mcp + MINOS_HOME` ; le switching ne réécrit pas les clients.

Si Docker est explicitement sélectionné mais que Docker Desktop/daemon est indisponible, le Wizard bloque : **aucun fallback silencieux** vers le natif.

### 4. ZIP / upgrade / uninstall

`install.ps1` supporte :

```text
-McpBackend none|native|docker
```

Le payload précédent est déplacé vers un backup collision-safe avant remplacement. Si le nouveau payload ou le switch backend échoue, l'ancien répertoire d'installation est restauré.

Le runtime Docker peut être désinstallé sans supprimer `DockerDataRoot`. La purge des données reste une opération séparée et explicite. Le setup interactif conserve le choix destructif avec **Non / conserver** par défaut.

### 5. Gates S7

Contrats :

```text
scripts/install/verify-mcp-backend-lifecycle.ps1
scripts/install/verify-installer-template.ps1
M29InstallerBackendLifecycleContractTest
```

Runner exact-head :

```text
scripts/m29/run-s7.ps1
```

Il exige :

```text
PowerShell AST                             SUCCESS
Maven clean verify                         13/13 SUCCESS
check-current-docs.py                      SUCCESS
fault-injected lifecycle verifier          SUCCESS
backend-agnostic client routing            SUCCESS
installer template verifier                SUCCESS
Windows distribution                       SUCCESS
Inno smoke setup compile                   SUCCESS
native-only install                        SUCCESS
native upgrade                             SUCCESS
Docker-only install                        SUCCESS
Docker -> native                           SUCCESS
native -> Docker reuse                     SUCCESS
runtime uninstall preserve                 SUCCESS
explicit isolated purge                    SUCCESS
```

Aucun PASS S7 avant :

```text
M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS
```

Rapport attendu :

```text
target/m29/s7-qualification-<exact-head>.json
```

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
pull HEAD courant
→ run-s7.ps1 exact-head
→ si et seulement si S7 SUCCESS : documenter PASS S7
→ S8
→ autorisation explicite avant PR/CI/merge
```
