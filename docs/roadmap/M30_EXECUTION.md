# M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector Storage

Statut : **QUALIFIÉ LOCAL — en attente d'autorisation push**  
Issue : **#109**  
PR : **#110 (DRAFT)**  
Branche : **`m30-advanced-installer-postgres-ollama`**  
Baseline : **M29 / PR #108, HEAD initial `fc1243d74b20d1198cf32c0ee380142c6aa6848b`**  
HEAD local : **`49ab40d`** (docs(m30): record qualification results and artifact SHA-256)

## Objectif produit

Rendre indépendants les trois axes de configuration MINOS :

```text
runtime   : native | docker
storage   : local | postgresql
semantic  : disabled | local-hash | ollama
```

Le wizard Windows doit offrir un mode Standard rétrocompatible et un mode Avancé réellement fonctionnel. Aucun placeholder PostgreSQL/H2 n'est présenté comme une fonctionnalité livrée.

## Invariants

1. Le backend historique est nommé `local`, jamais `H2`.
2. `MINOS_HOME` est une racine de données/configuration, pas une base de données.
3. `local` reste le défaut sans régression.
4. `postgresql` est fail-closed : aucun fallback silencieux vers `local`.
5. pgvector est utilisé comme moteur vectoriel réel quand PostgreSQL est sélectionné ; le backend local conserve `index-v2.bin` et son scan exact historique.
6. Les UUID projet/workspace et les snapshot IDs restent les identités autoritatives entre backends.
7. Les mots de passe ne sont ni journalisés, ni stockés dans les configs MCP tierces ; un fichier secret ACL-isolé est utilisé pour le PostgreSQL géré.
8. Le query plane Docker connecté n'obtient aucun egress Internet : PostgreSQL/Ollama sont accessibles via un réseau Docker `internal` dédié.
9. L'admin plane conserve seul l'egress requis pour les dépendances projet.
10. Ollama Docker n'autorise que le service géré `minos-ollama`; les endpoints réseau arbitraires restent refusés par le provider Java.
11. Copilot/Claude/Codex continuent d'appeler le point d'entrée stable `minos.exe mcp`.
12. Upgrade, reinstall, ownership et uninstall doivent préserver les données/configurations tierces par défaut.

## Architecture storage

```text
MinosApplication
      |
StorageBackend
   /        \
local      postgresql
 |            |
File*       JDBC stores
 |            |
index-v2   pgvector
```

Le provider PostgreSQL est chargé via `ServiceLoader`, ce qui maintient `minos-application` indépendant du driver JDBC.

## Architecture Docker connectée

```text
                     +------------------+
                     | minos-postgres   |
                     | pgvector         |
                     +--------+---------+
                              |
                    internal-only network
                              |
+-------------+       +-------+--------+       +-------------+
| AI clients  | stdio |   minos-mcp    |       | minos-ollama|
| host        +------>+   query plane   +------>+ embeddings  |
+-------------+       +----------------+       +-------------+

minos-admin -> internal network + dependency-egress network
```

Aucun port PostgreSQL/Ollama n'est publié pour le runtime Docker géré.

## Sous-étapes

| Étape | Objet | État |
|---|---|---|
| M30-S1 | Storage ports, backend SPI, configuration durable | ✅ PASS — reactor + qualification locale |
| M30-S2 | PostgreSQL registry/snapshots/state/fingerprint/runtime stores | ✅ PASS — tests intégration (Docker skip gracieux), codec sans round-trip temp |
| M30-S3 | pgvector semantic storage + SQL cosine ranking | ✅ PASS — scan exact qualifié (limitation ANN connue, V2) |
| M30-S4 | Ollama Docker internal-only | ✅ PASS — depends_on `required:false`, validation modèle présent, endpoint allowlist |
| M30-S5 | Wizard Standard/Avancé | ✅ PASS — build installateur EXE succès, verifiers passent |
| M30-S6 | MCP server name / config ownership / upgrade | ✅ PASS — uninstall ciblé, ownership par serveur |
| M30-S7 | Managed Docker PostgreSQL lifecycle + external native PostgreSQL | ✅ PASS — volumes persistants, fail-closed, passwd ACL |
| M30-S8 | Full qualification matrix | ✅ PASS — 7/7 combinaisons (2026-08-04) |

## Qualification cible

Matrice minimale :

```text
Native / Local      / local-hash
Native / Local      / Ollama
Native / PostgreSQL / Ollama
Docker / Local      / local-hash
Docker / Local      / Ollama
Docker / PostgreSQL / local-hash
Docker / PostgreSQL / Ollama
```

Gates supplémentaires : migration local→PostgreSQL, exact identities, pgvector ranking, setup Standard/Avancé, upgrade/reinstall, uninstall keep/purge, MCP clients et parité Native/Docker.

## Qualification locale — 2026-08-04

### Matrice MCP handshake (7/7 PASS)

| # | Runtime | Storage | Semantic | initialize | tools/list |
|---|---|---|---|---|---|
| N1 | Native | Local | local-hash | ✅ | minos_search_code ✅ minos_impact ✅ |
| N2 | Native | Local | Ollama | ✅ | minos_search_code ✅ minos_impact ✅ |
| N3 | Native | PostgreSQL | Ollama | ✅ | minos_search_code ✅ minos_impact ✅ |
| D4 | Docker | Local | local-hash | ✅ | minos_search_code ✅ minos_impact ✅ |
| D5 | Docker | Local | Ollama | ✅ | minos_search_code ✅ minos_impact ✅ |
| D6 | Docker | PostgreSQL | local-hash | ✅ | minos_search_code ✅ minos_impact ✅ |
| D7 | Docker | PostgreSQL | Ollama | ✅ | minos_search_code ✅ minos_impact ✅ |

### Artefacts distribution (version 1.0.1)

| Artefact | SHA-256 |
|---|---|
| `MINOS-1.0.1-windows-x64-setup.exe` | `e69187e6b6dde3d9577e086eff2ef55a9556de354dd8d5b54d527dd48e68aff0` |
| `minos-1.0.1-windows-x64.zip` | `ce817ec2353544ecd37f0394b703fa104c4fc0c425665b04fdcdc1a567bfaaa2` |
| `minos-1.0.1.cdx.json` (SBOM) | `c1bed3923c029958b4a15dc55dde1d09ef4944d565e28e78b872ddcda16e9fa5` |

### Limitations connues (scope V1)

- **Scan exact pgvector** : l'index vectoriel V1 utilise `<=> ORDER BY LIMIT` sans index HNSW/IVFFlat. Un index ANN sera ajouté en V2 une fois la dimension standard choisie par projet.
- **Managed Docker Windows-only** : `configure-m30-docker-services.ps1` cible Windows (`$env:OS -eq 'Windows_NT'`). Linux headless sera adressé en V2.
- **CPU-only Ollama** : pas de support GPU dans la configuration Docker gérée V1.
- **Docker Desktop 4.30+ / Testcontainers** : les tests d'intégration PostgreSQL sont ignorés gracieusement quand docker-java ne peut pas se connecter (pipe HTTP 400). Ils passent sur CI Linux où Docker est directement accessible depuis la JVM.
