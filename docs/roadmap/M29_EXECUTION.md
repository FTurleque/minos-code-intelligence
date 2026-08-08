# M29 — Autonomous Docker Runtime & Native Parity

Statut : **TERMINÉ / INTÉGRÉ**  
Issue : **#107 — CLOSED / completed**  
PR : **#108 — MERGED**  
Branche historique : **`m29-autonomous-docker-runtime`**

Ce document est désormais un **registre historique d'exécution**. L'état produit courant est dans [`../STATUS.md`](../STATUS.md).

## Objectif livré

M29 a fait du runtime Docker MINOS un backend autonome de premier rang derrière le point d'entrée stable :

```text
Copilot / Claude / Codex
          |
          v
     minos.exe mcp
          |
     backend router
       /       \
    native     docker
```

Choisir natif ou Docker change le lieu d'exécution, sans obliger les clients IA à réécrire leur configuration.

## Invariants livrés

1. Docker autonome : aucune indexation native préalable requise.
2. Identités projet/workspace stables entre runtimes.
3. Mapping host/container séparé de l'identité métier.
4. Snapshots structurés autoritatifs.
5. Vector store local existant conservé (`index-v2.bin`, float32, scan exact).
6. Query plane Docker durci : rootfs read-only, `cap_drop: ALL`, `no-new-privileges`, projets read-only.
7. Providers/toolchains préparés hors query runtime isolé.
8. Clients IA backend-agnostic via `minos.exe mcp`.
9. Aucun fallback Docker→native silencieux.
10. #98 reste indépendante et ouverte.

## Résultats exact-head

| Étape | Objet | Résultat historique |
|---|---|---|
| M29-S1 | Backend contract & ADR | ✅ PASS `c7a4e944...` |
| M29-S2 | Project identity / portable path mapping | ✅ PASS `c7a4e944...` |
| M29-S3 | Autonomous Docker administration plane | ✅ PASS `3df1b40...` |
| M29-S4 | Provider-complete Docker image | ✅ PASS `3df1b40...` |
| M29-S5 | Autonomous indexing & vector lifecycle | ✅ PASS `0959fb9...` |
| M29-S6 | Backend-agnostic MCP client integration | ✅ PASS `f7ef0e3...` |
| M29-S7 | Installer, switching & lifecycle | ✅ PASS `50b462f...` |
| M29-S8 | Native/Docker parity qualification | ✅ PASS `da6a76f...` |

La PR #108 documente les preuves de livraison et ferme #107.

## S3/S4 — runtime Docker autonome

Le plan Docker sépare :

- MCP query plane persistant ;
- administration/indexation éphémère ;
- bootstraps providers/outils ;
- persistance MINOS sous `/var/lib/minos` ;
- projets montés en lecture seule.

L'image provider-complete qualifiée couvre les providers/langages revendiqués par MINOS et n'a pas besoin d'un état natif préalable pour indexer un projet neuf.

## S5 — indexation et vector lifecycle

Le lifecycle qualifié prouve :

```text
projet neuf
→ discovery
→ providers
→ FULL index
→ snapshot READY
→ vector state cohérent
→ second run NONE lorsque qualifié
→ restart sans perte d'état
```

Les erreurs de provider préservent le snapshot actif précédent.

## S6 — intégrations MCP backend-agnostic

Les intégrations Copilot JetBrains/IntelliJ, Copilot CLI, Claude CLI/Code, Claude Desktop et Codex utilisent toutes :

```text
command = <installation>\app\minos.exe
args    = mcp
env     = MINOS_HOME=<data-root>
```

Aucune configuration cliente n'embarque de `docker exec`, nom de conteneur ou Compose.

## S7 — installer et switching

Le switch canonique reste :

```text
prepare
→ validate
→ handshake
→ commit backend.properties
→ retire ancien backend
```

Le gate a couvert installation native, Docker, upgrades, bascules, reuse du runtime compatible et désinstallation avec conservation/purge explicite.

## S8 — parité native/Docker

S8 a fourni le gate comparatif qui autorise le claim de parité M29. Les seules différences admises restent celles explicitement liées au chemin/provenance/runtime.

Le marker historique est :

```text
M29-S8 NATIVE/DOCKER PARITY QUALIFICATION SUCCESS
```

## Relation avec 1.0.1 et M30

M29 a été intégré avant la publication 1.0.1. M30 a ensuite ajouté le stockage PostgreSQL/pgvector, Ollama Docker et le wizard Standard/Avancé.

Le hardening post-audit PR #113 requalifie ces capacités avant de construire un nouveau candidat 1.0.1 ; il ne rouvre pas M29.

## Hors périmètre

- #98 — sandbox OS réelle des workers distants : toujours ouverte ;
- aucune base ANN/HNSW externe introduite par M29 ;
- aucun SaaS implicite.
