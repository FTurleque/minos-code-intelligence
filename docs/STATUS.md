# État courant — MINOS

Dernière mise à jour : **2 août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié ; M29 S1/S2 qualifiés, S3 atteint le vrai lifecycle Docker puis est bloqué par les runtimes providers ; S4 provider-complete implémenté et à qualifier exact-head.**

Ce fichier est la synthèse autoritative de l'état courant. Les preuves historiques restent dans [`roadmap/`](roadmap/), [`history/milestones/`](history/milestones/) et [`adr/`](adr/README.md).

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
M29-S3                           🟨 plan admin prouvé ; index bloqué par provider image
M29-S4                           🟨 provider-complete image implémentée ; qualification pending
M29-S5 → S8                      non démarrés / non qualifiés
PR / CI M29                      AUCUNE — autorisation explicite requise
```

`main` et `develop` représentent encore la ligne produit 1.0.0 publiée. La branche de maintenance `fix/v1.0.1-release-hardening` porte le candidat de correction Windows 1.0.1 ; elle n'est pas une release et aucun tag `v1.0.1` n'existe au démarrage M29.

M29 a été démarré le **2 août 2026** depuis la branche 1.0.1, car celle-ci contient les prérequis installer/MCP nécessaires. Cette dépendance ne permet pas de contourner #106 : M29 ne devra pas être intégré en réécrivant ou en sautant l'historique 1.0.x.

## Release 1.0.0

MINOS 1.0.0 est la première release stable publiée après la convergence C0→M28 et la promotion `develop → main` via la PR #102.

La release reste immuable. Le tag `v1.0.0` et la branche `release/v1.0.0` ne doivent jamais être réécrits ou retaggés.

Un défaut post-publication a été identifié dans la distribution Windows native : l'image Java créée par `jpackage` utilisait une liste de modules trop étroite et pouvait manquer `java.xml`, provoquant :

```text
java.lang.NoClassDefFoundError: org/w3c/dom/Node
```

La correction est portée par 1.0.1 et ne modifie pas 1.0.0.

Voir [`releases/1.0.0.md`](releases/1.0.0.md) et [`releases/1.0.1.md`](releases/1.0.1.md).

## Candidat 1.0.1

Le candidat 1.0.1 porte notamment :

- runtime Windows dérivé du JAR final avec `jdeps` ;
- vérification `java --list-modules` et non-régression `java.xml` ;
- handshake MCP réel sur distribution et setup isolé ;
- setup smoke isolé des installations réelles ;
- page MCP et capability probes Copilot/Claude/Codex ;
- faux launcher Copilot/VS Code rejeté ;
- Codex Desktop via configuration utilisateur TOML ;
- backups, ownership et désinstallation sélective des configurations tierces ;
- chemins CLI sauvegardés ;
- `slf4j-nop` pour garder stderr MCP propre ;
- runner local `scripts/release/build-local-windows-candidate.ps1` sans publication/tag.

La séquence de publication reste :

```text
code + docs
→ build local Windows
→ runtime module gate
→ MCP handshake distribution/setup
→ vérification visuelle setup
→ vérification clients réels
→ autorisation explicite
→ seulement ensuite tag v1.0.1 + GitHub Release
```

M29 n'altère pas ce contrat.

## État des jalons

| Jalons | État |
|---|---|
| C0 → M20 | terminés, validés et livrés |
| M21 — Production Integrity | terminé ; #73 CLOSED / completed |
| M22 — Advanced Provider Intelligence | terminé |
| M23 — Semantic Retrieval 2.0 | terminé |
| M24 — Polyglot Expansion | terminé |
| M25 — Remote & Distributed Indexing | terminé avec disposition sandbox honnête |
| M26 — Runtime & Dynamic Intelligence | terminé |
| M27 — Team / Hosted Mode | terminé avec frontières local-first/no-SaaS explicites |
| M28 — Production Convergence | terminé ; #93 CLOSED / completed ; PR #102 merged |
| M29 — Autonomous Docker Runtime & Native Parity | **EN COURS ; #107 OPEN ; S1/S2 PASS ; S3 runtime atteint ; S4 implémenté en attente de qualification** |

## M29 — Docker autonome & Native Parity

### Baseline et sécurité CI

Avant création de branche, les triggers GitHub Actions ont été audités. Sur la baseline M29, les workflows actifs pertinents sont `pull_request`, `workflow_dispatch` ou `release`; le one-shot `release-v1.0.0.yml` avec trigger push n'est plus présent sur la ligne 1.0.1.

La branche `m29-autonomous-docker-runtime` a été créée depuis :

```text
db33cae87b37f9c2c36e536c96a4ccb6e24df3e5
```

sans PR et sans déclencher de CI. Aucune PR/Action/merge M29 n'est autorisé implicitement.

### M29-S1 — contrat backend — ✅ PASS

Le contrat comprend :

- backend explicite `native | docker` ;
- configuration versionnée `<MINOS_HOME>/runtime/backend.properties` ;
- migration pré-M29 vers `native` explicite ;
- invalidité/version/backend inconnu = fail-closed ;
- écriture atomique ;
- `minos.exe mcp` route avant `MinosApplication.open(...)` ;
- Docker effectue probe daemon + conteneur puis `docker exec -i` ;
- Docker indisponible = erreur explicite ;
- aucun fallback Docker → natif ;
- ADR-0037 accepté pour le contrat, sans claim de parité.

Preuve Windows exact-head du 2 août 2026 :

```text
HEAD                         c7a4e94414f4e2b6e3a2a23beacd303ca740387e
mvnw.cmd clean verify        BUILD SUCCESS
13/13 modules                SUCCESS
McpBackendRouterTest         6/6 PASS
suite totale                 417 PASS
check-current-docs.py        SUCCESS
```

### M29-S2 — identité et chemins portables — ✅ PASS

Le code qualifié apporte :

- mapping typé/versionné `hostRoot ↔ containerRoot` ;
- runtime location `native|docker` ;
- registre projet portable via `rootRelativePath` ;
- UUID projet/workspace conservés ;
- migration des anciens `rootPath` absolus avec backup `.m29-v1.bak` et remplacement atomique ;
- tests Windows path ↔ Linux path, même projectId, idempotence et fail-closed hors racine.

Sur le même HEAD :

```text
ProjectPathMappingTest       4/4 PASS
Minos Application            161/161 PASS
Storage                       42/42 PASS
```

La preuve process native↔Docker réelle est volontairement reportée aux gates d'intégration S3/S5/S8 ; elle n'est pas utilisée pour revendiquer une parité prématurée.

### M29-S3 — administration Docker autonome — 🟨 PLAN PROUVÉ, GATE FINAL BLOQUÉ PAR S4

Le runtime Compose possède les plans `minos-mcp`, `minos-admin` et `minos-bootstrap`. Les projets restent read-only ; seul le plan admin peut écrire l'état métier `/var/lib/minos`. Tous conservent `network_mode: none`, filesystem read-only, `cap_drop: ALL`, `no-new-privileges:true` et tmpfs borné.

Preuve Docker réelle sur `b780feb7d27bd34952d1952f8d80b06755980684` :

```text
mvnw.cmd clean verify                   BUILD SUCCESS — 13/13
check-current-docs.py                   SUCCESS
Docker server / Compose                 29.6.2 / 5.3.1
Install + Validate                      PASS
mapping N:/workspace-dev ↔ /workspace/projects   PASS / idempotent
project list sur home neuf              0 projet
project add / inspect                   PASS
index réel                              BLOQUÉ
```

Blocage reproduit :

```text
provider runtime is not ready: rust-analyzer-scip
missing Rust runtime requirements: cargo, rustc, rust-analyzer
```

Cette preuve confirme que le plan S3 atteint bien le vrai lifecycle Docker. **S3 reste néanmoins non-PASS** tant que l'image S4 ne permet pas `index → READY`, puis handshake MCP et recreate/persistance.

### M29-S4 — provider-complete Docker image — 🟨 IMPLÉMENTÉ, QUALIFICATION EXACT-HEAD REQUISE

L'image M29 prépare maintenant pendant BUILD les runtimes/providers revendiqués par le catalogue M24 :

```text
scip-java            0.13.1
scip-typescript      0.4.0
scip-python          0.6.6
scip-clang           0.4.0
scip-dotnet          0.2.14
scip-go              0.2.7
rust-analyzer-scip   0.3.2989 / 2026-07-27 / 12c3381
```

Toolchains Docker préparées : JDK 24, Node 20.20.2, Python 3/pip, .NET SDK 10.0.302, Go 1.26.5, Rust 1.97.1, Coursier et runtimes associés. Le Node 20 est conservé explicitement pour compatibilité avec `scip-typescript 0.4.0`; cette dépendance est enregistrée comme limitation et ne doit pas être masquée.

Les téléchargements sont limités au BUILD. En RUN, `network_mode: none` reste obligatoire. Les outils providers sont copiés depuis l'image dans un volume Linux Compose `minos-provider-tools`, monté read-only dans `minos-mcp` et `minos-admin`; le business data reste le bind host séparé.

L'image produit aussi :

```text
provider-inventory.json
provider-binary-sha256.txt
```

Le workflow `Install`/`Validate` initialise le volume providers et exécute les probes MINOS. La CLI possède désormais `tools verify --all`, qui échoue si **n'importe quel provider annoncé** n'est pas `READY`, sans modifier le comportement historique de `tools verify` côté natif.

Le gate exact-head S4 est `scripts/m29/run-s4.ps1`. Il exige notamment :

```text
Maven + docs PASS
Docker linux/amd64
build image provider-complete
Install / Validate
network_mode:none
minos tools verify --all
provider inventory = 7 providers attendus
checksums non vides
```

Aucune disposition PASS S4 n'est enregistrée avant exécution réelle de ce runner.

### Sources projet read-only

M29 interdit aux providers de déposer `index.scip` dans la racine source. Les process plans Java, TypeScript, C/C++, C#, Go et Rust routent maintenant l'artefact dans le run directory MINOS ; Python le faisait déjà. Rust redirige également `CARGO_TARGET_DIR` hors du projet. Cette propriété est couverte par les tests M24 et packaging M29.

### Vector store

MINOS conserve le vector store v2 existant :

```text
index-v2.bin
float32
```

Les snapshots structurés restent autoritatifs et les résultats sémantiques restent `HEURISTIC`. M29 ne crée pas de nouvelle base vectorielle externe et n'introduit pas ANN/HNSW/Lucene/vector DB sans nouvelle mesure.

### Gate courant

Le gate courant est S4, puis retour immédiat au gate S3 sur le **même HEAD** si S4 passe :

```text
run-s4.ps1 exact-head
→ providers offline READY
→ run-s3.ps1 exact-head
→ projet neuf Docker-only
→ project add / index / READY
→ semantic + hybrid status
→ MCP initialize/tools-list
→ restart/recreate + persistance
```

S5 ne sera déclaré commencé qu'après ces preuves.

## Limite explicitement ouverte — #98

L'issue #98 reste ouverte : la sandbox OS réelle Windows/Linux du worker distant n'est pas implémentée ni qualifiée.

```text
network DENY sans backend OS prouvé  → fail-closed
code non fiable                      → non supporté
claim « sandbox OS réelle »          → interdit
```

M29 est indépendant de #98 : rendre Docker MCP autonome et paritaire ne constitue pas, à lui seul, une sandbox OS réelle pour les workers distants.

## Sources de vérité opérationnelles

- état produit : ce fichier ;
- roadmap : [`ROADMAP.md`](ROADMAP.md) ;
- convergence M28 : [`roadmap/M28_EXECUTION.md`](roadmap/M28_EXECUTION.md) ;
- exécution M29 : [`roadmap/M29_EXECUTION.md`](roadmap/M29_EXECUTION.md) / #107 ;
- ADR backend M29 : [`adr/0037-first-class-native-and-docker-runtime-backends.md`](adr/0037-first-class-native-and-docker-runtime-backends.md) ;
- runtime Docker M29 : [`user/docker-runtime.md`](user/docker-runtime.md) ;
- installation Windows : [`user/production-installation.md`](user/production-installation.md) ;
- release 1.0.0 : [`releases/1.0.0.md`](releases/1.0.0.md) ;
- candidat 1.0.1 : [`releases/1.0.1.md`](releases/1.0.1.md) ;
- publication autoritative : `scripts/release/publish-windows-release.ps1` ;
- construction locale sûre : `scripts/release/build-local-windows-candidate.ps1`.
