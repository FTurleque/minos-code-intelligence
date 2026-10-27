# Section 8 — Concepts transverses

> Preuves : ADR-0001, ADR-0006, ADR-0008, ADR-0010, ADR-0014, ADR-0015, ADR-0017,
> ADR-0023, ADR-0024, ADR-0029, ADR-0035, ADR-0037,
> `minos-domain/src/main/java/com/minos/domain/Evidence.java`,
> `minos-storage/src/main/java/com/minos/storage/codec/SnapshotIntegrityService.java`,
> `minos-application/src/main/java/com/minos/application/semantic/EmbeddingProvider.java`.

---

## 8.1 Identité et accès

- **Mode local** : aucune authentification requise. MINOS s'exécute dans le contexte de l'utilisateur système.
- **Mode équipe (opt-in)** : bearer token `mht1` signé HMAC-SHA-256. Rôles : OWNER, ADMIN, CONTRIBUTOR, VIEWER, AUDITOR. Le tenant identity est dérivé du token, jamais accepté comme paramètre d'appel (ADR-0035).
- Le token MCP est fourni via la variable d'environnement `MINOS_TEAM_TOKEN`, jamais en argument de tool (ADR-0035).

---

## 8.2 Sécurité

| Mécanisme | Description | ADR |
|-----------|-------------|-----|
| Chiffrement tenant | AES-256-GCM par tenant, clé dérivée de `MINOS_TEAM_KEY_<KEY_ID>` | ADR-0035 |
| Audit chain | HMAC-SHA-256 sur chaque événement d'audit, immuable | ADR-0035 |
| Fail-closed | Configuration backend inconnue → erreur immédiate, jamais de fallback silencieux | ADR-0037 |
| MCP read-only | Aucun tool MCP ne mute le code ou le projet | ADR-0017 |
| Isolation des artefacts distants | Sources GitHub/GitLab épinglées par SHA de commit, artefacts worker vérifiés | ADR-0033 |

---

## 8.3 Données

- **Modèle de domaine** : `Symbol`, `Relationship`, `Evidence`, `SymbolLocation`, `ProgramGraph` — tous dans `minos-domain`, sans dépendance externe.
- **Snapshots** : sérialisés en binaire (codec v2), promus atomiquement, intégrité vérifiée par `SnapshotIntegrityService`.
- **Relations** : portent obligatoirement `Evidence`, `ResolutionStatus`, `RelationshipDirection`, `RelationshipKind` (ADR-0010).
- **Observations runtime** : conservées séparées des facts statiques, corrélées au snapshot exact de référence (ADR-0034).
- **Vecteurs sémantiques** : stockés avec provider/modèle/dimensions, alignés sur le snapshot actif (ADR-0029).

---

## 8.4 Interfaces et versionnement

| Surface | Contrat | Versionnement |
|---------|---------|--------------|
| CLI | `MinosCli.run(String[], Appendable, Appendable)` | Stable depuis M9, breaking changes interdits (ADR-0016) |
| MCP | SDK Java MCP 2.0, outils nommés `minos_*` | Tools stables, schémas bornés (ADR-0017) |
| API Java | `minos-api` artefact Maven | Versionné indépendamment des modèles internes (ADR-0018) |
| CLI JSON (IDE) | Protocole JSON versionné négocié par `minos ide handshake` | `formatVersion` explicite (ADR-0027) |
| NEXUS | Contrat JSON local versionné | `schemaVersion` dans chaque export (ADR-0020) |
| Backend MCP | `backend.properties` format 1 | `formatVersion=1`, fail-closed sur inconnu (ADR-0037) |

---

## 8.5 Gestion des erreurs

- Les services retournent des types portant les limitations et le `ResolutionStatus` (non des exceptions de bas niveau).
- `ImpactAnalysisReport` porte une liste de `ImpactLimitation` explicites.
- `IndexerNegotiationResult` indique les capacités absentes ou partielles.
- En CLI, les exit codes sont : 0 = succès, 1 = erreur d'exécution, 2 = erreur d'usage, 3 = résultat partiel (un seul sens pour toutes les commandes : `project list` et la résolution par nom de `inspect`, `project inspect` et `index-status`, quand des entrées du registre sont illisibles, comptées et affichées ; voir `docs/user/cli.md`).
- En MCP, les erreurs sont des réponses JSON-RPC d'erreur, pas des panics.

---

## 8.6 Résilience

- **Promotion atomique** des snapshots : un snapshot est visible seulement après promotion complète (ADR-0006).
- **Snapshot actif en mémoire** (`SnapshotReadView`) : requêtes rapides sans I/O pour chaque appel, par le port de lecture `CodeKnowledgeReader` (ADR-0024, ADR-0056).
- **Indexation incrémentale** : planifiée uniquement sous preuve explicite de capacité provider (ADR-0014).
- **Compaction et rétention** : `SnapshotCompactionService`, `SnapshotRetentionService` maintiennent la taille sous contrôle.
- **Fail-closed** sur les configurations inconnues (ADR-0037).

---

## 8.7 Configuration

| Configuration | Localisation | Description |
|-------------|-------------|-------------|
| MINOS_HOME | Variable d'environnement | Répertoire de données (snapshots, vectors, runtime obs) |
| backend.properties | `$MINOS_HOME/runtime/` | Backend MCP : `native` ou `docker`, format 1 |
| MINOS_HOSTED_MODE | Variable d'environnement | Active le mode équipe (`enabled`) |
| MINOS_TEAM_KEY_<KEY_ID> | Variable d'environnement | Clé maître tenant 256 bits |
| MINOS_TEAM_TOKEN | Variable d'environnement | Bearer token pour les tools MCP équipe |
| revision | Property Maven | Version CI-friendly (`-Drevision=<version>`) |

---

## 8.8 Observabilité

- **`minos doctor`** : diagnostic complet — providers disponibles, versions, état du snapshot.
- **`minos index-status`** : état du snapshot actif et métadonnées d'index.
- **`minos providers`** : capacités et état runtime de chaque provider.
- **Exit codes** : observables par les scripts et CI.
- **Limitations déclarées** dans chaque résultat : `ImpactLimitation`, `CapabilitySupportLevel`.
- Pas de framework d'observabilité réseau (pas de Prometheus, Jaeger) en mode local standard.

---

## 8.9 Persistance

| Store | Technologie | Module |
|-------|------------|--------|
| Snapshots | Fichiers binaires codec v2 | `minos-storage` |
| Vector store | Fichiers locaux | `minos-storage` |
| Runtime observations | Fichiers locaux | `minos-storage` |
| Control plane tenant | Fichier chiffré AES-256-GCM | `minos-storage` |
| Fingerprints | Fichiers locaux | `minos-application` |
| State d'index | Mémoire + fichier | `minos-application` |
| Backend avancé (opt-in) | PostgreSQL / pgvector | `minos-storage` |

---

## 8.10 Performance et concurrence

- La `SnapshotReadView` du snapshot actif est construite au premier besoin, mise en cache par le store et partagée en lecture seule : les cas d'usage n'y voient que les lectures de `CodeKnowledgeReader`, jamais l'index mémoire ni ses écritures (ADR-0056). L'ancienne `SnapshotQueryView`, dépréciée, partage la même vue et le même index.
- Les index en mémoire (symboles, relations) sont reconstruits depuis les facts persistés.
- **Baseline M15** (repeated query, in-memory) : first = 22,6 ms, p50 = 3,88 ms, p95 = 5,34 ms.
- La recherche sémantique linéaire est volontaire ; une structure ANN n'est introduite que sous mesures (ADR-0031).
- L'indexation des providers est un processus externe non bloquant pour la requête.

---

## 8.11 Tests

- **Surefire** : tests unitaires par module.
- **Failsafe** : tests d'intégration dans `minos-app` (cross-boundary, replay M14).
- **Testcontainers** : PostgreSQL dans `minos-storage`.
- JaCoCo produit un rapport de couverture agrégé dans `target/site/jacoco-aggregate`.
- Qualification de chaque jalon : tests + replay providers + handshake MCP + distribution.

---

## 8.12 Déploiement et rollback

- Release par `-Drevision=<version>` sur le reactor Maven.
- Artefacts : shaded JAR `minos-code-intelligence-<version>-all.jar`, ZIP Windows, installer Advanced Installer.
- Rollback : revenir au JAR précédent suffit ; les snapshots sont compatibles descendants (codec versionné).
- La bascule backend (natif → Docker) est transactionnelle : prepare → validate → handshake → écriture atomique (ADR-0037 S7).
