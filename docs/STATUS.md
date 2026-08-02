# État courant — MINOS

Dernière mise à jour : **2 août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié ; M29 S1/S2 qualifiés ; S3 et S4 PASS exact-head `3df1b40...` ; S5 Autonomous Indexing & Vector Lifecycle implémenté sur un HEAD plus récent et en attente de qualification exact-head.**

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
M29-S5                           🟨 implémenté ; qualification run-s5.ps1 requise
M29-S6 → S8                      non démarrés / non qualifiés
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

### S5 — Autonomous Indexing & Vector Lifecycle — 🟨 implémenté / non qualifié

Le défaut provider→module root est maintenant traité par une distinction explicite :

```text
registeredProjectRoot
→ provider execution/build root
→ projectRelativeRoot
→ provider artifact
→ project snapshot staging
```

Un provider est exécuté sur la racine de module/build réellement découverte. Les chemins SCIP issus d'un sous-module sont préfixés jusqu'à la racine projet pour préserver file IDs, identités structurelles et source lookup. Plusieurs scopes du même provider utilisent des répertoires de run séparés et les faits externes strictement identiques sont dédupliqués sans masquer une collision divergente.

Le lifecycle conserve la promotion projet atomique. Un échec sur un scope imbriqué conserve le snapshot actif précédent ; le test `IndexingLifecycleScopedExecutionTest` verrouille ce rollback. Le planner `NONE|FULL|INCREMENTAL` reste capability-honest : l'incrémental multi-scope n'est pas revendiqué tant qu'un provider qualifié ne le supporte pas.

La fixture live S5 est :

```text
fixtures/polyglot/m29-scoped-modules
```

Elle combine une racine Maven Java et deux modules TypeScript `ui/app` / `ui/lib`, sans `package.json` ni `tsconfig.json` à la racine globale.

Le workflow Docker persiste désormais la sélection sémantique dans `.env` et `installation.json` (format 5). Les seuls modes packagés admis à ce stade sont `disabled` et `local-hash`. `local-hash` est un provider de référence zéro-réseau destiné à qualifier le plumbing ; il ne constitue pas une preuve de qualité de modèle appris.

Le runner `scripts/m29/run-s5.ps1` doit encore être exécuté exact-head. Il enchaîne S4 sur la même image avec `local-hash`, puis exige :

```text
JAVA + TYPESCRIPT / MAVEN + NPM
scip-java root + scip-typescript ui/app + ui/lib
index SUCCEEDED / READY
semantic READY — minos-local-hash / 384 dimensions
semantic-index/<projectId>/index-v2.bin présent
hybrid READY_WITH_SEMANTIC
signal semantic HEURISTIC
second index = NONE / NO_CHANGES
forced FULL = nouveau snapshot + semantic realigné
query-plane recreate = semantic/hybrid toujours READY
worktree toujours propre
```

Le vector store reste celui existant : `index-v2.bin`, composants `float32`, scan exact. Aucune base vectorielle externe, ANN ou HNSW n'est introduite.

### S6 / S7 / S8

- S6 : clients MCP backend-agnostic Copilot/Claude/Codex ;
- S7 : installer, switching transactionnel, lifecycle ;
- S8 : qualification native/Docker machine-readable.

Gate final :

```text
native result == docker result
```

Aucun claim de parité avant S8.

## Limite explicitement ouverte — #98

#98 reste OPEN. La sandbox OS réelle du worker distant est indépendante de M29 et ne doit pas être revendiquée implicitement.

## Gate courant

```text
pull HEAD courant
→ check-current-docs.py
→ run-s5.ps1 exact-head
   → S4 exact-head sur la même installation / local-hash
   → fixture polyglotte scoped
   → structured + semantic + hybrid + NONE + forced FULL + recreate
→ seulement après SUCCESS : S5 peut passer ✅
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
