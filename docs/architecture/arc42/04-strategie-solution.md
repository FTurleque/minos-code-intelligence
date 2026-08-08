# Section 4 — Stratégie de solution

> Preuves : ADR-0001 à ADR-0037, `pom.xml` (reactor), `minos-domain/pom.xml`,
> `minos-engine/pom.xml`, ADR-0022, ADR-0008, ADR-0029.

---

## 4.1 Principes architecturaux

| # | Principe | ADR |
|---|---------|-----|
| PA-1 | **Agnosticisme du langage et de l'indexeur** : le domaine et le moteur ignorent toute technologie d'indexation spécifique | ADR-0001 |
| PA-2 | **Négociation par capacités explicites** : un provider déclare ses `IndexerCapability`s ; le moteur sélectionne selon les capacités requises, jamais par nom de langage | ADR-0008 |
| PA-3 | **Honnêteté des relations** : toute relation produite porte une `Evidence`, un `ResolutionStatus` et une `ProviderReference` | ADR-0010 |
| PA-4 | **Local-first** : aucune communication réseau implicite en mode standard | ADR-0001, ADR-0029 |
| PA-5 | **Surfaces read-only** : MCP ne mute ni projet, ni code | ADR-0017 |
| PA-6 | **Promotion atomique des index** : un snapshot est promu atomiquement ou pas du tout | ADR-0006 |
| PA-7 | **Frontières Maven comme garde-fous** : les dépendances interdites ne compilent pas | ADR-0022 |
| PA-8 | **Fail-closed** : une configuration inconnue ou invalide produit une erreur visible, jamais un fallback silencieux | ADR-0037 |
| PA-9 | **Couche sémantique optionnelle et reconstruisible** : MINOS reste pleinement utilisable sans embeddings | ADR-0029 |
| PA-10 | **Analyse d'impact conservative** : toute limitation est déclarée explicitement dans le rapport | ADR-0015 |

---

## 4.2 Style de décomposition

MINOS applique une architecture **hexagonale (ports & adapters)** organisée en couches Maven :

```
minos-domain          ← modèle de domaine pur, zéro dépendance externe
minos-engine          ← ports (interfaces) et services de requête provider-indépendants
minos-runtime-local   ← infrastructure d'exécution locale de processus providers
minos-storage-local   ← implémentations de persistance locale (fichiers, mémoire)
minos-provider-scip   ← adapter SCIP : ingestion et lifecycle providers Java/TS/polyglot
minos-integration-git ← adapter Git (JGit)
minos-application     ← services applicatifs partagés (architecture, impact, recherche…)
minos-nexus           ← adapter NEXUS (contrat JSON export)
minos-cli             ← surface CLI stable
minos-api             ← surface API Java publique versionnée
minos-mcp             ← surface MCP STDIO (SDK Java MCP)
minos-app             ← composition root et artefact distribué (shaded JAR)
```

La direction des dépendances est strictement ascendante : les couches basses ignorent les couches hautes.

---

## 4.3 Technologies structurantes

| Technologie | Rôle | Module |
|------------|------|--------|
| Java 24 | Langage et runtime | Tous |
| Maven 3.9 | Build, frontières et releases CI-friendly | `pom.xml` racine |
| SCIP (protobuf) | Protocole d'indexation sémantique | `minos-provider-scip` |
| SDK Java MCP 2.0 | Transport MCP STDIO | `minos-mcp` |
| JGit 7.6 | Intégration Git locale | `minos-integration-git` |
| PostgreSQL / pgvector | Stockage avancé optionnel | `minos-storage-postgresql` |
| Jackson | Sérialisation JSON (NEXUS, MCP, output) | `minos-storage-postgresql`, `minos-application` |
| JUnit 5 / JaCoCo | Tests et couverture | Tous |
| Testcontainers | Tests d'intégration PostgreSQL | `minos-storage-postgresql` |
| Advanced Installer / JPackage | Distribution Windows | `minos-app` build |

---

## 4.4 Mécanismes atteignant les objectifs qualité

| Objectif qualité | Mécanisme | ADR |
|-----------------|----------|-----|
| Exactitude | `Evidence`, `ResolutionStatus`, `ProviderCapabilityProfile` sur chaque résultat | ADR-0010, ADR-0015 |
| Isolement local | Aucun SDK réseau dans `minos-domain`/`minos-engine` | ADR-0001 |
| Extensibilité | `IndexerRegistry`, `IndexerProvider` SPI, `ProviderCapabilityProfile` | ADR-0008, ADR-0026 |
| Stabilité des surfaces | `minos-api` versionné, contrat CLI JSON négocié, MCP read-only | ADR-0016, ADR-0018, ADR-0027 |
| Performance de requête | `SnapshotQueryView` en mémoire, index rebuil déclenchés explicitement | ADR-0024 |
| Résilience | Promotion atomique des snapshots, intégrité vérifiée, fail-closed | ADR-0006, ADR-0037 |
| Sécurité tenant | Chiffrement AES-256-GCM, HMAC-SHA-256 audit, clés externes | ADR-0035 |

---

## 4.5 Liens vers les ADR principaux

- [ADR-0001](../../adr/0001-language-and-indexer-agnostic-core.md) — Agnosticisme du langage et de l'indexeur
- [ADR-0008](../../adr/0008-negocier-indexeurs-par-capacites-explicites.md) — Négociation par capacités
- [ADR-0017](../../adr/0017-mcp-stdio-read-only.md) — MCP STDIO read-only
- [ADR-0022](../../adr/0022-maven-reactor-and-module-boundaries.md) — Reactor Maven et frontières
- [ADR-0029](../../adr/0029-optional-rebuildable-semantic-layer-and-hybrid-ranking.md) — Couche sémantique optionnelle
- [ADR-0037](../../adr/0037-first-class-native-and-docker-runtime-backends.md) — Backends natif et Docker
