# État courant — MINOS

Dernière mise à jour : **2 août 2026 — MINOS 1.0.0 publié ; correctif Windows 1.0.1 en préparation et non publié ; M29 S1/S2 qualifiés ; S4 provider-complete PASS exact-head `0f5668f...` ; S3 atteint le vrai plan Docker mais la fixture monorepo historique révèle un défaut provider→module root désormais classé S5 ; le runner S3 utilise une fixture Java Maven contrôlée et doit être requalifié exact-head.**

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
M29-S3                           🟨 fixture contrôlée corrigée ; requalification requise
M29-S4                           ✅ PASS exact-head 0f5668f... ; HEAD courant modifié, rerun requis
M29-S5                           ⬜ inclut le routage provider→module root polyglotte
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

Le candidat 1.0.1 porte notamment :

- runtime Windows dérivé du JAR final avec `jdeps` ;
- contrôle `java.xml` ;
- vrai handshake MCP distribution/setup ;
- setup smoke isolé ;
- détection clients Copilot/Claude/Codex ;
- Codex Desktop via configuration utilisateur ;
- backups, ownership et désinstallation sélective ;
- `slf4j-nop` pour stderr MCP propre ;
- runner local de candidate sans publication.

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

### S1 — backend contract — ✅ PASS

Preuve :

```text
HEAD                         c7a4e94414f4e2b6e3a2a23beacd303ca740387e
mvnw.cmd clean verify        BUILD SUCCESS
13/13 modules                SUCCESS
suite totale                 417 PASS
McpBackendRouterTest         6/6 PASS
```

Contrat : `native|docker`, backend explicite, migration pré-M29 vers native, fail-closed, `minos.exe mcp` stable, Docker indisponible = erreur, aucun fallback Docker→native.

### S2 — identité portable — ✅ PASS

`ProjectPathMappingTest` prouve le mapping host/container, les UUID stables et `rootRelativePath` portable.

### S3 — administration Docker autonome — 🟨

Le Compose sépare :

```text
minos-mcp
minos-admin
minos-bootstrap
minos-tools-bootstrap
minos-provider-probe
minos-provider-tools
```

Le plan query persistant, les bootstraps et le probe restent `network_mode: none`. Les projets sont read-only. Le plan admin éphémère peut résoudre les dépendances du projet et écrit uniquement l'état/caches/staging MINOS.

Première preuve réelle sur :

```text
b780feb7d27bd34952d1952f8d80b06755980684
```

avec `project add/inspect` puis défaut provider Rust. Les défauts suivants ont été réellement atteints et corrigés successivement : source RO `target/scip-targetroot`, `workspace/mvnw` host-dépendant et tmp Java sous `/tmp` noexec.

Le HEAD `0f5668f8ea10303a5df4cffd0e79376a21979fbd` confirme que ces remédiations tiennent côté image : Maven 3.9.16 et `/run/minos-native` passent le probe offline.

Le S3 sur ce même HEAD a ensuite révélé un défaut différent : la fixture était le monorepo MINOS complet. Discovery sélectionne plusieurs providers, mais `scip-typescript` reçoit la racine du monorepo et échoue car aucun `tsconfig.json`/`package.json` n'existe à cette racine.

Le runner S3 utilise désormais :

```text
minos-code-intelligence/fixtures/java/java-multi-module
```

Cette fixture doit prouver : scip-java réel, Maven image, staging writable, sources RO, `index → READY`, `semantic status`, `hybrid status`, MCP et recreate/persistance.

Le défaut provider→module root du monorepo polyglotte est conservé pour S5.

### S4 — provider-complete image — ✅ PASS exact-head `0f5668f...`

La provider-complete image implémentée prépare :

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

La CLI expose `tools verify --all` et le probe offline a prouvé 7/7 providers READY sur `0f5668f8ea10303a5df4cffd0e79376a21979fbd`.

Preuve :

```text
13/13 modules Maven SUCCESS
433 unit tests + 1 smoke IT PASS
check-current-docs.py SUCCESS
Docker image 31/31 FINISHED
provider probe offline SUCCESS
7/7 providers READY
doctor.ready=true
M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS
```

Le HEAD courant a avancé avec le runner S3 et la réconciliation documentaire ; il doit être requalifié avant nouveau claim courant.

### S5 — prochain travail métier après PASS S3

S5 couvre : lifecycle autonome, fingerprint/invalidation, `NONE|FULL|INCREMENTAL`, promotion atomique, recovery, vector store et recherche sémantique/hybride.

Il inclut désormais explicitement :

```text
provider negotiation
→ module/build root appropriée
→ provider executor
```

pour les monorepos polyglottes.

Le vector store existant reste `index-v2.bin` / `float32`, scan exact, résultats `HEURISTIC`.

### S6/S7/S8

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
→ run-s4.ps1 exact-head
→ si PASS, run-s3.ps1 même HEAD
→ fixture Java Maven contrôlée
→ index READY + semantic/hybrid + MCP + recreate
→ seulement ensuite S5
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