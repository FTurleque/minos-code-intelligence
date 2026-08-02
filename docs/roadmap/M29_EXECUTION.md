# M29 — Autonomous Docker Runtime & Native Parity

Statut : **EN COURS — S1/S2 qualifiés exact-head ; S4 PASS exact-head `0f5668f...` ; S3 atteint le vrai plan Docker puis révèle que la fixture historique, le monorepo MINOS entier, déclenche un défaut de routage provider→module root ; runner S3 corrigé sur fixture Java Maven contrôlée, nouvelle qualification exact-head S4→S3 requise**  
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
7. Providers/toolchains MINOS préparés au BUILD/install ; aucun téléchargement implicite de provider en RUN. Les dépendances déclarées par un projet compilé peuvent être résolues par le plan admin/indexation éphémère et sont mises en cache uniquement sous `/var/lib/minos`.
8. `network_mode: none` est obligatoire pour le plan MCP query persistant, le bootstrap mapping, le bootstrap tools et le probe provider offline. Le plan admin/indexation éphémère peut disposer d'un egress de dépendances projet ; cet egress n'est jamais exposé au MCP query.
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
| M29-S3 | Autonomous Docker administration plane | 🟨 plan réel atteint ; fixture de qualification corrigée, requalification requise |
| M29-S4 | Provider-complete Docker image | ✅ PASS exact-head `0f5668f...` ; HEAD courant modifié par runner/docs, rerun requis |
| M29-S5 | Autonomous indexing & vector lifecycle | ⬜ ; inclut désormais le routage provider→module root des monorepos polyglottes |
| M29-S6 | Backend-agnostic MCP client integration | ⬜ |
| M29-S7 | Installer, switching & lifecycle | ⬜ |
| M29-S8 | Native/Docker parity qualification | ⬜ |

`🟨` ne signifie pas PASS. Un PASS sur un ancien HEAD reste une preuve historique mais ne qualifie pas automatiquement un HEAD modifié.

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
minos-mcp             persistent query plane
minos-admin           ephemeral administration/indexing plane
minos-bootstrap       ephemeral path-mapping bootstrap
minos-tools-bootstrap provider payload bootstrap
minos-provider-probe  offline provider probe
```

`minos-mcp` : `/var/lib/minos` RO, projets RO, filesystem RO, `network_mode: none`, `cap_drop: ALL`, `no-new-privileges:true`.

`minos-admin` : état MINOS writable, projets toujours RO, filesystem conteneur RO + tmpfs, `cap_drop: ALL`, `no-new-privileges:true`, invocation éphémère. L'egress n'est disponible que sur ce plan pour résoudre les dépendances du build du projet ; providers et toolchains restent prépackagés.

`minos-bootstrap` : crée/idempotence le mapping `project-paths.properties` et refuse un remplacement conflictuel ; réseau none.

Surfaces disponibles : `doctor`, `tools list`, `tools verify`, `project add/list/inspect`, `index`, `index-status`, `semantic status`, `hybrid status`, `mcp`.

### Historique des défauts réels atteints

Première preuve lifecycle :

```text
HEAD b780feb7d27bd34952d1952f8d80b06755980684
mvnw.cmd clean verify                 BUILD SUCCESS — 13/13
check-current-docs.py                 SUCCESS
Docker server / Compose               29.6.2 / 5.3.1
Install / Validate                    PASS
mapping host/container                PASS / idempotent
project list sur home neuf            count=0
project add                           PASS
project inspect                       PASS / NEVER_INDEXED
index réel                            FAIL provider runtime
```

Erreur :

```text
provider runtime is not ready: rust-analyzer-scip
missing Rust runtime requirements: cargo, rustc, rust-analyzer
```

Après image provider-complete, le S3 sur `f39802e966370f0934436163eecc180e4d76a271` a atteint `scip-java` puis :

```text
java.nio.file.FileSystemException:
/workspace/projects/minos-code-intelligence/target/scip-targetroot: Read-only file system
```

Le staging writable a corrigé ce défaut sans rendre les sources écrivable.

### Qualification `45536e2...` — staging atteint, wrapper host invalide

Le checkpoint précédent était libellé **PASS QUALIFIÉ `45536e2...` / REQUALIFICATION COURANTE REQUISE** pour S4.

Preuve S4 :

```text
HEAD 45536e2fc7d32ed67932e2715e458fa26a8239b1
Maven                              13/13 SUCCESS
suite unitaires                    433 PASS
ShadedJarSmokeIT                   1 PASS
check-current-docs.py              SUCCESS
Docker image                       31/31 FINISHED
Apache Maven                       3.9.16
provider probe offline             SUCCESS
providers READY                    7/7
doctor.ready                       true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

Le S3 sur ce même HEAD a ensuite prouvé le staging :

```text
workingDirectory=/var/lib/minos/runs/<run-id>/scip-java/workspace
```

puis échoué sur :

```text
workspace/mvnw
error=2, No such file or directory
```

Le checkout était matérialisé sur Windows. Le wrapper host ne devait donc pas gouverner une exécution Linux.

Remédiation : `mvnw` et `mvnw.cmd` exclus du staging Linux, `.mvn` conservé, Apache Maven 3.9.16 de l'image utilisé. Le tmp Java/JNA est également routé vers :

```text
JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native
```

Le tmpfs général `/tmp` reste `noexec` ; `/run/minos-native` reste borné et `exec` uniquement sur les plans providers.

### Qualification `0f5668f...` — S4 PASS, nouveau défaut de fixture S3

Preuve exacte fournie sur :

```text
HEAD                                   0f5668f8ea10303a5df4cffd0e79376a21979fbd
mvnw.cmd clean verify                  BUILD SUCCESS — 13/13 modules
unit tests                             433 PASS
ShadedJarSmokeIT                       1 PASS
check-current-docs.py                  SUCCESS
Docker image                           31/31 FINISHED
Apache Maven                           3.9.16
JAVA_TOOL_OPTIONS                      /run/minos-native
offline provider probe                 SUCCESS
providers READY                        7/7
doctor.ready                           true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

S4 est donc réellement qualifié sur `0f5668f8ea10303a5df4cffd0e79376a21979fbd`.

Le S3 lancé immédiatement sur **le même HEAD** utilise encore la fixture historique `minos-code-intelligence`, c'est-à-dire le monorepo MINOS complet. Discovery détecte :

```text
C, CPP, CSHARP, GO, JAVA, KOTLIN, PYTHON, RUST, TYPESCRIPT
CARGO, CMAKE, DOTNET, GO_MODULE, GRADLE, MAVEN, NPM, PNPM
moduleCount=40
```

La négociation sélectionne légitimement les providers nécessaires. Cependant le lifecycle transmet actuellement la racine du projet enregistré à chaque executor. `scip-typescript` valide donc la racine `/workspace/projects/minos-code-intelligence` et échoue avant son lancement :

```text
IllegalArgumentException:
scip-typescript requires tsconfig.json or package.json:
/workspace/projects/minos-code-intelligence
```

Ce défaut est **distinct** des défauts scip-java précédents. Il révèle que l'indexation autonome d'un monorepo polyglotte doit router chaque provider vers la racine de module/build appropriée. Cette capacité relève de **M29-S5** et ne doit pas être maquillée par un faux `package.json` à la racine.

### Fixture S3 corrigée

S3 qualifie le plan d'administration/indexation lui-même. Sa fixture par défaut devient donc la fixture contrôlée, autonome et Maven :

```text
minos-code-intelligence/fixtures/java/java-multi-module
```

Elle est volontairement Java/Maven et multi-module. Elle doit prouver dans Docker, sans état natif :

```text
project list = 0
→ project add
→ project inspect
→ scip-java réel
→ projet source read-only
→ staging writable sous /var/lib/minos/runs
→ Maven 3.9.16 image
→ dépendances projet via egress admin uniquement
→ index SUCCEEDED / READY
→ index-status
→ semantic status
→ hybrid status
→ MCP initialize/tools-list
→ recreate du plan query
→ registre/index state persistants
→ second handshake MCP
```

Le runner est `scripts/m29/run-s3.ps1`. Les commits de correction de fixture sont :

```text
ce9bb6bb12862385ba8179fb39c76f14fcf468cc
98ee0199990cecb538d15a3b573498f2598fb68e
```

S3 reste NON-PASS tant que cette séquence n'a pas réussi sur le même HEAD qu'un S4 courant.

---

## M29-S4 — Provider-complete Docker image — ✅ PASS `0f5668f...` / HEAD COURANT À REQUALIFIER

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

L'image prépare au BUILD : JDK 24, Apache Maven 3.9.16, Coursier + `scip-java`, Node/npm, Python/pip, `scip-clang`, .NET SDK + `scip-dotnet`, Go + `scip-go`, Rust/cargo/rustc/rust-analyzer.

Les téléchargements providers/toolchains sont build/install prepared. Le probe `minos-provider-probe` reste `network_mode: none`.

L'image et le workflow exportent :

```text
provider-inventory.json
provider-binary-sha256.txt
```

Les outils sont initialisés dans le volume géré :

```text
minos-provider-tools
```

monté read-only dans `minos-mcp` et `minos-admin`.

La CLI impose le gate capability-honest :

```text
minos tools verify --all
```

Le runner `scripts/m29/run-s4.ps1` impose exact-head, worktree propre, Maven, docs, Docker linux/amd64, Install/Validate, probe provider offline, 7 providers READY, inventory/checksums et rapport JSON.

Preuve historique complète conservée :

```text
HEAD f39802e966370f0934436163eecc180e4d76a271
13/13 modules Maven SUCCESS
433 tests PASS
check-current-docs.py SUCCESS
provider probe offline SUCCESS
7/7 providers READY
tools verify --all PASS
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

Preuve courante la plus récente :

```text
HEAD 0f5668f8ea10303a5df4cffd0e79376a21979fbd
13/13 modules Maven SUCCESS
433 unit tests + 1 smoke IT PASS
check-current-docs.py SUCCESS
Docker image 31/31 FINISHED
Apache Maven 3.9.16
provider probe offline SUCCESS
7/7 providers READY
doctor.ready=true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

Les commits du runner S3 et la présente réconciliation documentaire modifient le HEAD ; le prochain S4 doit donc qualifier le nouveau HEAD exact.

---

## M29-S5 — Autonomous indexing & vector lifecycle

À implémenter/qualifier après S3/S4 : discovery, fingerprints, invalidation, `NONE|FULL|INCREMENTAL` selon capability, staging/promotion atomique, ancien snapshot conservé sur échec, recovery, vector store v2, semantic/hybrid, restart/upgrade.

Le défaut découvert par la fixture monorepo MINOS entre explicitement dans S5 :

```text
ProjectDiscovery
→ modules/build roots
→ provider negotiation par langage
→ provider selection
→ execution root appropriée par provider/module
→ staging/promotion communs au projet
```

Un provider ne doit plus supposer que le manifest qui lui correspond est nécessairement à la racine du projet enregistré.

Gate S5 : premier run FULL→SUCCEEDED/READY ; second run NONE si provider qualifié ; échec provider conserve ancien snapshot ; recovery revient READY ; fixture polyglotte/module-root qualifiée sans écrire dans les sources.

---

## M29-S6 — Backend-agnostic MCP client integration

Clients : Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop, Codex CLI/Desktop.

Tous continuent à cibler `app\minos.exe mcp`. Le backend est choisi par MINOS. Les protections 1.0.1 restent préservées.

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

1.0.1 reste non publiée. M29 dépend de ses prérequis installer/MCP mais ne modifie pas l'historique 1.0.x et ne peut être intégré en contournant #106.

#98 reste indépendante : autonomie/parité Docker MCP ≠ sandbox OS réelle des workers distants.

## Gate bloquant courant

```text
HEAD courant > 0f5668f... après correction du runner S3 + docs
→ rerun S4 exact-head obligatoire
→ si S4 PASS, rerun S3 sur exactement le même HEAD
→ fixture = minos-code-intelligence/fixtures/java/java-multi-module
→ vérifier scip-java réel + Maven image + /run/minos-native
→ S3 sans index READY + semantic/hybrid + MCP + recreate = pas de PASS S3
→ problème monorepo polyglotte provider→module root traité en S5
```

Aucune PR/CI ne doit être créée ou déclenchée pour contourner ces gates.

## Définition de terminé

M29 est terminé uniquement lorsque :

1. Docker indexe un projet neuf **sans état natif** ;
2. Docker restaure son état après restart ;
3. registre/snapshots/vector store sont cohérents et portables ;
4. mapping de chemins qualifié ;
5. tous les providers revendiqués sont packagés et passent le probe offline sans téléchargement de provider en RUN ; les dépendances propres aux projets compilés sont résolues uniquement dans le plan admin/indexation éphémère et mises en cache sous `/var/lib/minos` ;
6. les monorepos polyglottes routent chaque provider vers une racine de module/build valide ;
7. tous les clients MCP supportés peuvent utiliser Docker ;
8. le même point d'entrée client route vers les deux backends ;
9. résultats natif/Docker passent le rapport de parité ;
10. install/switch/update/uninstall qualifiés ;
11. docs/ADR/guides alignés ;
12. aucune claim de parité publiée avant preuve exact-head.