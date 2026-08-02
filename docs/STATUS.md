# État courant — MINOS

Dernière mise à jour : **3 août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié ; M29 S1–S7 PASS ; S8 à qualifier.**

Ce fichier est la synthèse autoritative de l'état courant. Les preuves détaillées restent dans [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md), [`history/milestones/`](history/milestones/) et [`adr/`](adr/README.md).

## Synthèse

```text
C0 → M28                         TERMINÉS / INTÉGRÉS sur main
M21 #73                          CLOSED / completed
M28 #93                          CLOSED / completed
PR de promotion #102             MERGED
main / develop                   1adbc45339efe37cd26d1937025bfa69d7b57811
tag v1.0.0                       1adbc45339efe37cd26d1937025bfa69d7b57811
GitHub Release v1.0.0            PUBLIÉE
#98 sandbox OS réelle            OPEN — travail futur explicite
v1.0.1 Windows                   EN PRÉPARATION — NON PUBLIÉE
fix/v1.0.1-release-hardening     db33cae87b37f9c2c36e536c96a4ccb6e24df3e5 au démarrage M29
M29 #107                         EN COURS — Docker autonome & Native Parity
branche M29                      m29-autonomous-docker-runtime
baseline M29                     db33cae87b37f9c2c36e536c96a4ccb6e24df3e5
M29-S1                           ✅ PASS exact-head c7a4e944...
M29-S2                           ✅ PASS exact-head c7a4e944...
M29-S3                           ✅ PASS exact-head 3df1b40...
M29-S4                           ✅ PASS exact-head 3df1b40...
M29-S5                           ✅ PASS exact-head 0959fb9...
M29-S6                           ✅ PASS exact-head f7ef0e3...
M29-S7                           ✅ PASS exact-head 50b462f...
M29-S8                           non démarré / non qualifié
PR / CI M29                      AUCUNE — autorisation explicite requise
```

`main` et `develop` représentent encore la ligne produit 1.0.0 publiée. La branche de maintenance `fix/v1.0.1-release-hardening` porte le candidat 1.0.1 ; aucun tag `v1.0.1` n'est publié.

M29 a été démarré le **2 août 2026** depuis la branche 1.0.1 afin de réutiliser les prérequis installer/MCP. Cette dépendance ne permet pas de contourner #106.

## Release 1.0.0

MINOS 1.0.0 est la première release stable après convergence C0→M28 et PR de promotion #102.

```text
main/tag v1.0.0 : 1adbc45339efe37cd26d1937025bfa69d7b57811
M21 #73          : CLOSED / completed
M28 #93          : CLOSED / completed
```

La release est immuable. Le défaut Windows `NoClassDefFoundError: org/w3c/dom/Node` est corrigé uniquement par 1.0.1 ; `v1.0.0` ne doit jamais être retaggé.

## Candidat 1.0.1

État : **EN PRÉPARATION — NON PUBLIÉE**.

Le candidat 1.0.1 porte notamment : runtime Windows dérivé du JAR final avec `jdeps`, contrôle `java.xml`, vrais handshakes MCP, setup smoke isolé, détection Copilot/Claude/Codex, ownership/backups/désinstallation sélective et `slf4j-nop` pour stderr MCP propre.

Tant que M29 n'a pas passé S8, le natif reste le parcours MCP recommandé ; Docker ne doit pas être présenté comme équivalent fonctionnel.

## État des jalons

| Jalons | État |
|---|---|
| C0 → M20 | terminés, validés et livrés |
| M21 — Production Integrity | terminé ; #73 CLOSED / completed |
| M22 — Advanced Provider Intelligence | terminé |
| M23 — Semantic Retrieval 2.0 | terminé |
| M24 — Polyglot Expansion | terminé |
| M25 — Remote & Distributed Indexing | terminé avec contraintes sandbox explicites |
| M26 — Runtime & Dynamic Intelligence | terminé |
| M27 — Team / Hosted Mode | terminé |
| M28 — Production Convergence | terminé ; #93 CLOSED / completed ; PR #102 merged |
| M29 — Autonomous Docker Runtime & Native Parity | **EN COURS ; #107 OPEN** |

## M29 — Docker autonome & Native Parity

### S1 / S2 — ✅ PASS

Preuve fondatrice :

```text
HEAD                         c7a4e94414f4e2b6e3a2a23beacd303ca740387e
mvnw.cmd clean verify        BUILD SUCCESS
13/13 modules                SUCCESS
suite totale                 417 PASS
McpBackendRouterTest         6/6 PASS
ProjectPathMappingTest       4/4 PASS
```

Le contrat reste : backend `native|docker`, fail-closed, `minos.exe mcp` stable, mapping portable host/container et aucun fallback Docker→native.

### S3 — administration Docker autonome — ✅ PASS exact-head `3df1b40...`

Le plan runtime sépare `minos-mcp`, `minos-admin`, `minos-bootstrap`, `minos-tools-bootstrap`, `minos-provider-probe` et le volume `minos-provider-tools`. Le query plane, les bootstraps et le probe sont `network_mode: none`; les projets restent read-only. L'admin éphémère peut résoudre les dépendances du projet et écrit seulement sous `/var/lib/minos`.

Historique des vrais défauts corrigés :

```text
b780feb7d27bd34952d1952f8d80b06755980684  missing Rust runtime requirements: cargo, rustc, rust-analyzer
f39802e...                                source RO target/scip-targetroot
45536e2...                                workspace/mvnw / error=2, No such file or directory
0f5668f...                                monorepo polyglotte routé à tort depuis la racine projet
```

La qualification finale S3 sur `3df1b40ca0daf50779596f6e955d966ed5eb4973` prouve : fixture Java Maven contrôlée, index réel `SUCCEEDED`, `index-status=READY`, fingerprint promu, hybrid structured fallback capability-honest, handshake MCP avant recreate, persistance project/snapshot après recreate et second handshake MCP.

Marqueur exact :

```text
M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS
```

### S4 — provider-complete image — ✅ PASS exact-head `3df1b40...`

Image préparée :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989
Apache Maven         3.9.16
```

Preuve exacte sur `3df1b40ca0daf50779596f6e955d966ed5eb4973` : 13/13 modules Maven SUCCESS, tests + shaded smoke PASS, checker docs SUCCESS, Docker 31/31, probe offline SUCCESS, `tools verify --all`, 7/7 providers READY et `doctor.ready=true`.

Marqueur exact :

```text
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

### S5 — Autonomous Indexing & Vector Lifecycle — ✅ PASS exact-head `0959fb9...`

Le défaut provider→module root est traité par une distinction explicite :

```text
registeredProjectRoot
→ provider execution/build root
→ projectRelativeRoot
→ provider artifact
→ project snapshot staging
```

Un provider est exécuté sur la racine de module/build réellement découverte. Les chemins SCIP issus d'un sous-module sont préfixés jusqu'à la racine projet pour préserver file IDs, identités structurelles et source lookup. Plusieurs scopes du même provider utilisent des répertoires de run séparés et les faits externes strictement identiques sont dédupliqués sans masquer une collision divergente.

Le lifecycle conserve la promotion projet atomique. Un échec sur un scope imbriqué conserve le snapshot actif précédent ; le test `IndexingLifecycleScopedExecutionTest` verrouille ce rollback. Le planner `NONE|FULL|INCREMENTAL` reste capability-honest : l'incrémental multi-scope n'est pas revendiqué tant qu'un provider qualifié ne le supporte pas.

Fixture qualifiée :

```text
fixtures/polyglot/m29-scoped-modules
```

Elle combine une racine Maven Java et deux modules TypeScript `ui/app` / `ui/lib`, sans `package.json` ni `tsconfig.json` à la racine globale.

Preuve exacte sur `0959fb9f64e2ecf61e20281f29c694e86d67c62b` :

```text
13/13 Maven                     SUCCESS
S4 provider-complete            SUCCESS
JAVA + TYPESCRIPT               détectés
MAVEN + NPM                     détectés
scip-java                       racine projet
scip-typescript                 ui/app + ui/lib
first FULL                      SUCCEEDED / READY
semantic                        READY / minos-local-hash / 384 dimensions / 19 documents
index-v2.bin                    persistant / non vide
hybrid                          READY_WITH_SEMANTIC / HEURISTIC
second index                    NONE / NO_CHANGES / même snapshot
forced FULL                     nouveau snapshot / semantic réaligné
query recreate                  semantic READY / hybrid READY_WITH_SEMANTIC
```

Marqueur exact :

```text
M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS
```

Le vector store reste celui existant : `index-v2.bin`, composants `float32`, scan exact. Aucune base vectorielle externe, ANN ou HNSW n'est introduite.

### S6 — Backend-agnostic MCP client integration — ✅ PASS exact-head `f7ef0e3...`

Les intégrations Copilot JetBrains/IntelliJ, Copilot CLI, Claude Code, Claude Desktop et Codex CLI/Desktop ciblent toutes :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=<dataRoot>
```

Aucun client ne possède de logique `docker exec`, de nom de conteneur ou de configuration Compose. Le choix `native|docker` reste exclusivement dans `<MINOS_HOME>/runtime/backend.properties`, lu par `McpBackendRouter`.

Qualification exacte sur `f7ef0e3dbe820253decd83a1dc27bf2651ef6de9` :

```text
PowerShell parse preflight                   SUCCESS
Maven 13/13                                  SUCCESS
check-current-docs.py                        SUCCESS
MCP client integration                       SUCCESS
MCP client preflight                         SUCCESS
Codex Desktop lifecycle                      SUCCESS
backend-routing verifier                     SUCCESS
installer template verifier                  SUCCESS
client configs native -> docker              byte-identical
```

Marqueur exact :

```text
M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS
```

Rapport :

```text
target/m29/s6-qualification-f7ef0e3dbe820253decd83a1dc27bf2651ef6de9.json
```

### S7 — Installer, switching & lifecycle — 🟨 implémenté / à qualifier

S7 introduit un orchestrateur unique :

```text
scripts/install/switch-mcp-backend.ps1
```

La transaction est :

```text
prepare -> validate -> handshake -> commit backend.properties -> retire ancien backend
```

Le handshake candidat utilise `scripts/install/probe-mcp-backend.ps1` et le point d'entrée stable `minos.exe mcp` dans un `MINOS_HOME` isolé. `backend.properties` n'est committé qu'après `initialize` + `tools/list` réussis. En cas d'échec, la configuration précédente est restaurée ; un upgrade Docker→Docker sauvegarde/restaure aussi le runtime Docker précédent puis le redémarre.

Un runtime Docker déjà géré avec le même `VERSION`, commit, `ProjectsRoot`, racines Docker et identité container/Compose est réutilisé par `Start + Validate + handshake` au lieu de reconstruire l'image.

Le setup Windows propose exactement trois choix exclusifs :

```text
MCP natif Windows — recommandé
MCP Docker — isolation renforcée
Ne pas configurer maintenant
```

Lors d'un upgrade, le backend déjà persisté dans `%LOCALAPPDATA%\MINOS\data\runtime\backend.properties` est présélectionné. Les clients IA sont communs aux deux backends et restent configurés uniquement sur `minos.exe mcp + MINOS_HOME`. Docker explicitement sélectionné mais indisponible bloque le wizard : aucun fallback natif silencieux.

Le ZIP `install.ps1` accepte aussi `none|native|docker`, sauvegarde l'installation précédente avant remplacement et restaure ce backup si la validation du nouveau payload/backend échoue. Les racines data/Docker sont surchargeables pour qualification isolée.

Verifiers :

```text
scripts/install/verify-mcp-backend-lifecycle.ps1
scripts/install/verify-installer-template.ps1
M29InstallerBackendLifecycleContractTest
```

Le verifier transactionnel injecte des échecs avant et après commit, prouve le rollback, la réutilisation du runtime Docker, l'upgrade et le rollback d'upgrade, et vérifie qu'une configuration tierce reste byte-identical.

Gate exact-head :

```text
scripts/m29/run-s7.ps1
```

Le gate construit une vraie distribution Windows et compile un setup Inno smoke, puis utilise des racines temporaires pour qualifier : native-only, upgrade native, Docker-only, Docker→native, native→Docker reuse, uninstall avec conservation des données Docker puis purge explicite.

Marqueur requis :

```text
M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS
```

### S8 — Native/Docker parity qualification — ⬜

Même corpus, même configuration métier, rapport machine-readable. Gate final :

```text
native result == docker result
```

Aucun claim de parité avant S8.

## Limite explicitement ouverte — #98

#98 reste OPEN. La sandbox OS réelle du worker distant est indépendante de M29 et ne doit pas être revendiquée implicitement.

## Gate courant

```text
pull HEAD courant
→ run-s7.ps1 exact-head
   → PowerShell parser preflight
   → Maven clean verify
   → check-current-docs.py
   → lifecycle transactionnel avec fault injection
   → backend-agnostic client routing
   → installer template contract
   → vraie distribution Windows + compilation setup Inno smoke
   → native-only + upgrade
   → Docker-only
   → Docker -> native -> Docker(reuse) -> native
   → uninstall preserve
   → purge explicite isolée
→ seulement après SUCCESS : S7 peut passer ✅
```

Aucune PR, GitHub Actions ou merge M29 sans autorisation explicite.

## Sources de vérité

- état produit : `docs/STATUS.md` ;
- roadmap : `docs/ROADMAP.md` ;
- exécution M29 : `docs/roadmap/M29_EXECUTION.md` et issue #107 ;
- ADR : `docs/adr/0037-first-class-native-and-docker-runtime-backends.md` ;
- guide Docker : `docs/user/docker-runtime.md` ;
- release 1.0.0 : `docs/releases/1.0.0.md` ;
- candidat 1.0.1 : `docs/releases/1.0.1.md`.
