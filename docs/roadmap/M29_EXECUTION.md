# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — S1/S2 qualifiés exact-head ; S3 prouvé jusqu'au vrai `index` et bloqué par l'absence de runtimes providers ; S4 provider-complete implémenté, qualification exact-head requise**  
Issue : **#107 — M29 — Autonomous Docker Runtime & Native Parity**  
Branche : **`m29-autonomous-docker-runtime`**  
Baseline : **`db33cae87b37f9c2c36e536c96a4ccb6e24df3e5` (`fix/v1.0.1-release-hardening`)**

## Objectif produit

Faire du runtime Docker MINOS un **backend autonome de premier rang**, fonctionnellement équivalent au runtime natif pour administration, discovery, providers/indexation, snapshots, persistance, vector store, recherche structurée/sémantique/hybride, architecture/impact/ProgramGraph, MCP, clients IA et lifecycle d'installation.

À la fin de M29, choisir **Natif Windows** ou **Docker isolé** doit changer le lieu d'exécution, pas les capacités métier ni les résultats attendus.

## Baseline autoritative au démarrage

Audit du 2 août 2026 :

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
7. Providers préparés au BUILD/install ; aucun téléchargement implicite en RUN.
8. `network_mode: none` en RUN.
9. Projets read-only dans le plan MCP **et** le plan admin/indexation.
10. Filesystem conteneur read-only quand possible, `cap_drop: ALL`, `no-new-privileges: true`, tmpfs borné.
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
| M29-S3 | Autonomous Docker administration plane | 🟨 vrai lifecycle atteint ; gate final bloqué par S4 |
| M29-S4 | Provider-complete Docker image | 🟨 implémenté — `run-s4.ps1` exact-head requis |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

`🟨` ne signifie pas PASS.

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

Preuve sur le même HEAD :

```text
ProjectPathMappingTest       4/4 PASS
Minos Application            161/161 PASS
Storage                       42/42 PASS
```

Les preuves process native↔Docker restent volontairement dans S3/S5/S8.

---

## M29-S3 — Autonomous Docker administration plane — 🟨

### Architecture implémentée

```text
minos-mcp        persistent query plane
minos-admin      ephemeral administration/indexing plane
minos-bootstrap  ephemeral path-mapping bootstrap
```

`minos-mcp` : `/var/lib/minos` RO, projets RO, filesystem RO, `network_mode: none`, `cap_drop: ALL`, `no-new-privileges:true`.

`minos-admin` : état MINOS writable, projets toujours RO, filesystem conteneur RO + tmpfs, même réseau/capabilities/security, invocation éphémère.

`minos-bootstrap` : crée/idempotence le mapping `project-paths.properties` et refuse un remplacement conflictuel.

Surfaces disponibles : `doctor`, `tools list`, `tools verify`, `project add/list/inspect`, `index`, `index-status`, `semantic status`, `hybrid status`, `mcp`.

### Preuve Docker réelle

Qualification sur :

```text
HEAD b780feb7d27bd34952d1952f8d80b06755980684
```

Résultats :

```text
mvnw.cmd clean verify                 BUILD SUCCESS — 13/13
check-current-docs.py                 SUCCESS
Docker server                         29.6.2
Docker Compose                        5.3.1
Install / Validate                    PASS
mapping host/container                PASS / idempotent
project list sur home neuf            count=0
project add                           PASS
project inspect                       PASS / NEVER_INDEXED
index réel                            FAIL provider runtime
```

Erreur autoritative :

```text
provider runtime is not ready: rust-analyzer-scip
missing Rust runtime requirements: cargo, rustc, rust-analyzer
```

Cette preuve démontre que le plan admin atteint le vrai `index`; elle remplace l'ancien diagnostic Docker daemon arrêté. S3 reste toutefois 🟨 car le gate exige encore `index→READY`, semantic/hybrid status post-index, MCP initialize/tools-list et restart/recreate avec état persistant.

Runner : `scripts/m29/run-s3.ps1`.

---

## M29-S4 — Provider-complete Docker image — 🟨 IMPLÉMENTÉ

### Inventaire contractuel

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / release 2026-07-27 / commit 12c3381
```

### Image préparée

L'image `docker/Dockerfile.mcp.release` prépare au BUILD :

- JDK 24 avec `javac` ;
- Coursier + cache pré-résolu pour `scip-java 0.13.1` ;
- Node 20.20.2 + npm pour `scip-typescript 0.4.0` et `scip-python 0.6.6` ;
- Python 3 + pip ;
- `scip-clang 0.4.0` ;
- .NET SDK 10.0.302 + `scip-dotnet 0.2.14` ;
- Go 1.26.5 + `scip-go 0.2.7` ;
- Rust 1.97.1 + `rust-analyzer` release 2026-07-27 / commit 12c3381.

Node 20 est conservé pour la compatibilité déclarée de `scip-typescript 0.4.0`; cette contrainte est inscrite dans l'inventaire et ne constitue pas une généralisation de support Node.

L'image génère :

```text
/opt/minos/provider-evidence/provider-inventory.json
/opt/minos/provider-evidence/binary-sha256.txt
```

Le workflow packagé exporte ensuite ces preuves dans le runtime installé sous :

```text
<InstallRoot>/runtime/provider-inventory.json
<InstallRoot>/runtime/provider-binary-sha256.txt
```

La différence de nom est volontaire : `binary-sha256.txt` est le manifeste interne à l'image ; `provider-binary-sha256.txt` est la copie d'évidence explicite conservée côté installation Windows.

### Séparation business state / provider tools

Les outils préparés par l'image sont copiés par `minos-tools-bootstrap` dans un volume Linux Compose :

```text
minos-provider-tools
```

`minos-mcp` et `minos-admin` montent ce volume sur `/var/lib/minos/tools` en read-only. Le business state reste dans le bind host `/var/lib/minos`. `minos-tools-bootstrap` est lui-même `network_mode: none`, root filesystem RO, `cap_drop: ALL`, `no-new-privileges:true`; il n'écrit que le volume providers lors de l'initialisation de version.

`Uninstall` supprime ce volume providers géré et l'image, mais préserve le business data bind par défaut.

### Gate capability-honest

La CLI ajoute :

```text
minos tools verify --all
```

Le mode historique `tools verify` continue à vérifier seulement les providers `requiredByDefault`. `--all` échoue si n'importe quel provider annoncé n'est pas `READY`.

Le runner `scripts/m29/run-s4.ps1` impose :

```text
exact HEAD + worktree clean
Maven clean verify
check-current-docs.py
Docker linux/amd64
Install / Validate
network_mode:none
tools verify --all
providers / doctor
provider inventory format=1
7 provider IDs attendus
checksums non vides
rapport JSON exact-head
```

**Aucune disposition PASS S4 avant exécution réelle de ce runner.**

### Projets read-only : artefacts provider

Les providers ne doivent jamais contourner le mount RO en produisant `index.scip` dans les sources. M29 route maintenant les sorties dans le run directory MINOS pour Java, TypeScript, C/C++, C#, Go et Rust ; Python était déjà conforme.

Rust redirige aussi :

```text
CARGO_TARGET_DIR=<runDirectory>/cargo-target
```

Tests concernés : `M24PolyglotProcessPlanFactoryTest`, `M29DockerAdministrationContractTest`, `ToolsCommandTest`.

### Après PASS S4

Relancer immédiatement `scripts/m29/run-s3.ps1` sur le **même HEAD**. S3 ne passe que si :

```text
projet neuf Docker-only
→ project add
→ index réel
→ READY
→ index-status
→ semantic/hybrid status
→ MCP initialize/tools-list
→ force recreate
→ registre/index state toujours présents
→ second handshake MCP
```

---

## M29-S5 — Autonomous indexing & vector lifecycle

À implémenter/qualifier après S3/S4 : discovery, fingerprints, invalidation, `NONE|FULL|INCREMENTAL` selon capability, staging/promotion atomique, ancien snapshot conservé sur échec, recovery, vector store v2, semantic/hybrid, restart/upgrade.

Gate : premier run FULL→SUCCEEDED/READY ; second run NONE si provider qualifié ; échec provider conserve ancien snapshot ; recovery revient READY.

---

## M29-S6 — Backend-agnostic MCP client integration

Clients : Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop, Codex CLI/Desktop.

Tous continuent à cibler `app\minos.exe mcp`. Le backend est choisi par MINOS. Les protections 1.0.1 (probe réel, faux shim Copilot rejeté, backups, ownership, CLI paths, Codex TOML géré, uninstall sélectif) sont préservées.

Gate par client et backend : initialize → initialized → tools/list → requête MINOS réelle → réponse valide → shutdown/cleanup.

---

## M29-S7 — Installer, switching & lifecycle

Wizard cible après parité prouvée :

```text
Mode MCP
( ) MCP natif Windows — recommandé
( ) MCP Docker — isolation renforcée
( ) Ne pas configurer le MCP maintenant

Clients IA
[ ] Copilot JetBrains
[ ] Copilot CLI
[ ] Claude Code
[ ] Claude Desktop
[ ] Codex
```

Le choix backend est exclusif. Docker Desktop/daemon et racine projets sont validés avant activation. Le switch est transactionnel : prepare → validate → handshake → config atomique → retrait ancien backend ; échec = configuration active inchangée/rollback.

---

## M29-S8 — Native/Docker parity qualification

Même fixture/corpus, rapport machine-readable comparant au minimum : registre/workspaces, index state/snapshot identity, symboles/occurrences/relations, implementations/references, architecture/impact/tests/ProgramGraph/source retrieval, structured/semantic/hybrid, vector store, MCP tools/list/réponses, restart/recovery, timings/mémoire/disque.

Rapport : fixture, backend, provider set, snapshot id, vector model identity, result digest, différences autorisées, mesures, PASS/FAIL.

Gate final :

```text
native result == docker result
```

aux seules différences explicitement autorisées de chemin/provenance/runtime et métriques environnementales bornées près.

Aucun claim de parité avant PASS.

---

## Relation avec 1.0.1 et #98

1.0.1 reste non publiée au démarrage M29. M29 dépend de ses prérequis installer/MCP mais ne modifie pas l'historique 1.0.x et ne peut être intégré en contournant #106.

#98 reste indépendante : autonomie/parité Docker MCP ≠ sandbox OS réelle des workers distants.

## Gate bloquant courant

```text
S4 exact-head sans preuve Docker réelle → pas de PASS S4
S4 PASS → rerun S3 même HEAD
S3 sans index READY + MCP + recreate → pas de PASS S3
```

Aucune PR/CI ne doit être créée ou déclenchée pour contourner ces gates.

## Définition de terminé

M29 est terminé uniquement lorsque :

1. Docker indexe un projet neuf sans état natif ;
2. Docker restaure son état après restart ;
3. registre/snapshots/vector store sont cohérents et portables ;
4. mapping de chemins qualifié ;
5. tous les providers revendiqués fonctionnent sans réseau en RUN ;
6. tous les clients MCP supportés peuvent utiliser Docker ;
7. le même point d'entrée client route vers les deux backends ;
8. résultats natif/Docker passent le rapport de parité ;
9. install/switch/update/uninstall qualifiés ;
10. docs/ADR/guides alignés ;
11. aucune claim de parité publiée avant preuve exact-head.
