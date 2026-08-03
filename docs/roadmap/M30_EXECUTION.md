# M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector Storage

Statut : **EN COURS — branche d'implémentation empilée sur M29**  
Issue : **#109**  
PR : **#110 (DRAFT)**  
Branche : **`m30-advanced-installer-postgres-ollama`**  
Baseline : **M29 / PR #108, HEAD initial `fc1243d74b20d1198cf32c0ee380142c6aa6848b`**

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
| M30-S1 | Storage ports, backend SPI, configuration durable | 🚧 implémenté, qualification en cours |
| M30-S2 | PostgreSQL registry/snapshots/state/fingerprint/runtime stores | 🚧 implémenté, qualification en cours |
| M30-S3 | pgvector semantic storage + SQL cosine ranking | 🚧 implémenté, qualification en cours |
| M30-S4 | Ollama Docker internal-only | 🚧 compose + provider policy + provisioning en cours |
| M30-S5 | Wizard Standard/Avancé | ⏳ |
| M30-S6 | MCP server name / config ownership / upgrade | ⏳ |
| M30-S7 | Managed Docker PostgreSQL lifecycle + external native PostgreSQL | ⏳ |
| M30-S8 | Full qualification matrix + migration + uninstall | ⏳ |

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
