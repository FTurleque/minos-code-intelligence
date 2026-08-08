# M30 — Advanced Installer, Ollama Docker & PostgreSQL/pgvector Storage

Statut : **TERMINÉ / INTÉGRÉ**  
Issue : **#109 — CLOSED / completed**  
PR d'implémentation : **#110 — MERGED vers `develop`**  
PR de promotion : **#111 — MERGED vers `main`**  
Branche historique : **`m30-advanced-installer-postgres-ollama`**

Ce document est désormais un **registre historique d'exécution**. L'état produit courant est dans [`../STATUS.md`](../STATUS.md).

## Objectif livré

M30 a rendu indépendants les trois axes de configuration :

```text
runtime MCP  : native | docker | none
storage      : local | postgresql
semantic     : disabled | local-hash | ollama
```

Le wizard Windows fournit un mode Standard rétrocompatible et un mode Avancé fonctionnel, sans faux label H2 ni placeholder PostgreSQL.

## Invariants livrés

1. Le backend historique s'appelle `local`.
2. `MINOS_HOME` est une racine de données/configuration.
3. `local` reste le défaut rétrocompatible.
4. `postgresql` est fail-closed ; aucun fallback silencieux vers `local`.
5. pgvector est utilisé lorsque PostgreSQL est sélectionné ; le store local reste disponible.
6. Les identités projet/workspace/snapshot restent stables entre backends.
7. Les secrets PostgreSQL ne sont pas placés dans les configs MCP tierces ni exposés dans les diagnostics sûrs.
8. PostgreSQL/Ollama gérés sont derrière un réseau Docker interne.
9. Le query plane n'obtient pas un egress Internet général.
10. Ollama n'accepte pas des endpoints distants arbitraires.
11. Copilot/Claude/Codex continuent d'appeler `minos.exe mcp`.
12. Upgrade/reinstall/uninstall préservent les données et configurations tierces par défaut.

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

Le provider PostgreSQL est chargé comme backend de stockage de premier rang et fournit registry, snapshots, index state, fingerprints, observations runtime et vecteurs sémantiques nécessaires.

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

Aucun port PostgreSQL/Ollama n'est requis sur l'hôte pour le runtime Docker géré.

## Sous-étapes livrées

| Étape | Objet | Résultat |
|---|---|---|
| M30-S1 | Storage SPI & configuration durable | ✅ livré |
| M30-S2 | PostgreSQL stores | ✅ livré / testé |
| M30-S3 | pgvector semantic store / ranking | ✅ livré / testé |
| M30-S4 | Ollama Docker internal-only | ✅ livré |
| M30-S5 | Wizard Standard / Avancé | ✅ livré |
| M30-S6 | Intégrations MCP nommées | ✅ livré |
| M30-S7 | PostgreSQL/pgvector Docker géré | ✅ livré |
| M30-S8 | Matrice de qualification | ✅ qualification locale + promotion CI PR #111 |

La PR #111 décrit le dernier HEAD qualifié avant promotion et les artefacts de qualification M30 de l'époque.

## Wizard livré

Le setup distingue :

```text
Standard — recommandé
Avancée
```

Le mode Avancé expose data root, nom MCP, runtime, stockage, sémantique, paramètres PostgreSQL/Ollama et identité Docker. Les clients IA restent backend-agnostic.

Le hardening post-audit PR #113 améliore encore cette surface en distinguant explicitement Claude CLI/Code, Codex CLI et Codex Desktop et en renforçant le récapitulatif des composants réellement gérés. Ces ajustements sont postérieurs à la livraison M30 et ne constituent pas une réouverture du jalon.

## PostgreSQL / pgvector

Le backend PostgreSQL dispose de migrations idempotentes et d'un store pgvector réel. Le ranking qualifié reste exact à cette étape ; les index ANN/HNSW/IVFFlat restent une optimisation future, pas un claim M30.

## Ollama

Deux modes sont supportés :

- runtime natif → instance locale/loopback existante ;
- runtime Docker → sidecar Ollama géré sur réseau interne.

Le provisioning d'un modèle géré utilise seulement l'egress temporaire nécessaire au pull puis retire cet accès.

## Intégrations MCP

Le nom du serveur MCP est configurable et propagé avec ownership/backups dans les intégrations supportées. Le point d'entrée reste :

```text
<installation>\app\minos.exe mcp
MINOS_HOME=<data-root>
```

## Qualification et hardening ultérieur

Les tests M30 initiaux autorisaient un skip local des tests PostgreSQL lorsque Docker n'était pas disponible. Le hardening PR #113 renforce ce contrat : en CI/release, PostgreSQL/pgvector devient obligatoire et une indisponibilité Docker fait échouer le gate.

PR #113 ajoute également :

- versions Jackson 2/3 corrigées ;
- OSV Scanner bloquant ;
- JaCoCo explicite M29/M30 ;
- qualification sur push des HEAD intégrés ;
- wizard harmonisé avec les patterns UX utiles du Windows deployment wizard de NEXUS Context Engine.

## Limites explicites

- PostgreSQL/pgvector géré et Ollama géré ciblent Windows/Docker pour le parcours V1 ;
- le vector ranking pgvector reste exact dans M30 ;
- le managed Ollama est CPU-first dans le scope M30 ;
- #98 reste indépendante et ouverte.
