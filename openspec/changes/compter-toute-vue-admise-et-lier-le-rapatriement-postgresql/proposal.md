# Proposal

## Why

Deux constats de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, sprint 5) portent sur le budget
de mémoire des vues décidé par l'ADR DATA-0081 :

- **AUD-PERF-05** (Faible) reprend **AUD-PERF-16** de l'audit du 2026-10-10 : le store PostgreSQL réserve la mémoire
  d'un chargement d'après la taille lue avec les métadonnées du snapshot actif, puis rapatrie la charge utile par
  `project_id` seul. Une publication concurrente d'un snapshot plus gros fait rapatrier une autre version que celle
  dont la taille a été réservée ; l'écart n'est vu qu'après le rapatriement.
- **AUD-PERF-06** (Faible) reprend **AUD-PERF-17** : une vue dont le poids estimé dépasse le plafond effectif du cache
  sans dépasser le budget est admise, refusée par le cache, puis rendue sans être comptée et rechargée à chaque
  requête. Le test de contrat de l'image de requête compare le budget au poids d'une vue V3 sans l'index des arêtes
  entrantes.

**Ce qui a déjà été décidé.** AUD-PERF-16 et AUD-PERF-17 ont été ouverts par la revérification indépendante du
2026-11-11 (`docs/project/audits/2026-10-10/ETAT-2026-10-13.md:630-639`), qui a donné une action à chacun et les a
laissés ouverts (`:657`). `docs/project/audits/2026-10-10/SUIVI.md` ne cite aucun des deux identifiants : aucun
changement OpenSpec, aucune décision du propriétaire ni aucun report n'y est consigné. L'audit du 2026-11-15 les
constate non corrigés au HEAD `89be3fb4`.

**Statut des chiffres.** Les ordres de grandeur de l'audit (jusqu'à 3,1 × 400 Mio rapatriés hors réservation ; seuils
d'environ 130 000 et 410 000 relations) sont **calculés sur les constantes du code**, non mesurés. Ce changement exige
une mesure (tâches 2.1 et 3.1) ; les seuils publiés par la documentation sont dits calculés, avec leur hypothèse.

## What Changes

- **Rapatriement lié à la version réservée** : la requête de charge utile du store PostgreSQL filtre sur
  `(project_id, snapshot_id, sha256)`, valeurs des métadonnées qui ont servi à la réservation. Une version disparue
  ou remplacée entre les deux lectures relance la résolution (nouvelle lecture des métadonnées, nouvelle réservation)
  dans la limite de tentatives existante ; aucune charge utile d'une autre version n'est demandée sous une réservation
  calculée pour une autre taille.
- **Toute vue admise est comptée** : une vue admise par le budget dont le poids dépasse le plafond effectif du cache
  est retenue seule par son store, comptée comme poids résident, servie aux lectures suivantes et libérée par
  l'éviction du budget, une publication ou son remplacement. Il n'existe plus de vue admise rendue hors compte.
- **Poids complet de la vue la plus lourde** : `SnapshotLimits` fournit le poids estimé d'une vue V3 de la taille du
  plafond de persistance pour des nombres de symboles, d'occurrences et de relations donnés, index des arêtes
  entrantes compris, égal à l'estimation des deux stores ; le test de contrat de l'image de requête calcule avec lui,
  pour zéro symbole et zéro occurrence, les deux seuils de relations (sous le plafond du cache, sous le budget par
  défaut) et exige que la documentation utilisateur les annonce avec cette hypothèse.
- **ADR** : la décision de DATA-0081 change sur son point 14 (une vue au-dessus du plafond effectif n'est plus « non
  mise en cache et rechargée à chaque requête ») et se précise sur son point 16 : un ADR consolidé le remplace (voir
  `design.md`, D5).

Aucune rupture de contrat : aucune option, aucun code de sortie, aucun champ de sortie ne change.

## Capabilities

### New Capabilities

Aucune : le compte des vues et la lecture PostgreSQL relèvent de `persistance-snapshots`, le contrat de l'image de
requête de `distribution-docker`.

### Modified Capabilities

- `persistance-snapshots` : exigences ajoutées (charge utile PostgreSQL rapatriée pour la version réservée ; vue
  admise au-dessus du plafond du cache comptée par le budget ; poids de la vue la plus lourde calculé index dérivés
  compris) ; exigence modifiée « Un snapshot de la taille de ce dépôt est admis au cache de vues » (son scénario « Vue
  plus lourde que le plafond » affirme l'absence de mise en cache et deux chargements, que ce changement contredit).
- `distribution-docker` : exigence ajoutée — le contrat de l'image de requête publie les seuils de relations calculés.

## Hors périmètre

- Les caches applicatifs hors budget (AUD-PERF-04) : changement `borner-les-caches-applicatifs-par-le-tas`.
- La zone de travail PostgreSQL du plan de requête (AUD-PERF-09) : changement
  `dimensionner-la-zone-de-travail-postgresql-du-plan-de-requete`.
- La vue évincée du cache mais encore tenue par une requête en cours (DATA-0081, Risques, l. 118) : elle reste hors
  compte ; la revérification du 2026-11-11 a relevé que la marge annoncée n'est pas prouvée
  (`ETAT-2026-10-13.md:622`). Elle est reportée telle quelle dans l'ADR consolidé, sans correction ici.
- Un relèvement du plafond du cache, du plafond de persistance, de `MaxRAMPercentage` ou de la fraction du budget.
- Un rapatriement PostgreSQL par tranches, le stockage de la colonne `payload`, le format de snapshot.
- Les constats du sprint 4 (AUD-PERF-01, -02, -03, -10), qui modifient avant ce sprint les stores sémantiques et
  l'explorateur : prérequis d'ordre, non spécifiés ici.
- Les fichiers de l'audit (`findings.json`, `SUIVI.md`) et les index partagés des ADR : tenus par le propriétaire.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La réservation PostgreSQL se calcule sur `metadata.payloadBytes()` | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java:196-200` |
| La charge utile est relue par `project_id` seul | [Existant vérifié] | même fichier `:403-404`, `:432-434` |
| La discordance de version n'est vue qu'après le rapatriement | [Existant vérifié] | même fichier `:202-207` |
| Le plafond effectif vaut `min(budget, plafond configuré)` | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/codec/QueryViewMemoryBudget.java:127-131` |
| `convert` libère la réservation quel que soit le résultat de la mise en cache | [Existant vérifié] | même fichier `:202-215` |
| Le cache refuse une entrée plus lourde que son plafond ; les deux stores ignorent ce refus | [Existant vérifié] | `minos-engine/src/main/java/com/minos/store/cache/WeightedLruCache.java:80` ; `FileSymbolSnapshotStore.java:314` ; `PostgresCodeKnowledgeSnapshotStore.java:258-263` |
| `largestPersistableViewWeightBytes` ignore l'index des arêtes entrantes | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/codec/SnapshotLimits.java:127-129` ; `M29DockerQueryImageContractTest.java:259` |
| AUD-PERF-16 et AUD-PERF-17 n'ont fait l'objet d'aucune décision | [Existant vérifié] | `docs/project/audits/2026-10-10/ETAT-2026-10-13.md:630-639`, `:657` ; aucune occurrence dans `SUIVI.md` |
| Jusqu'à 1,24 Gio rapatriés hors réservation ; une vue V3 de la taille du plafond tient jusqu'à 131 061 relations sous le plafond du cache et 410 681 sous le budget par défaut, pour zéro symbole et zéro occurrence | [Question] non bloquante | calculés sur les constantes (index : 1 024 octets + 96 par relation, `InMemoryCodeKnowledgeStore.java:51-52`), non mesurés ; mesure en tâches 2.1 et 3.1 |
| Une vue admise au-dessus du plafond est retenue seule et comptée | [Proposition] | `design.md`, D1 (décision du propriétaire attendue) |
| L'invariant `requireCacheCoversPersistable` reste sur l'amplification seule | [Proposition] | `design.md`, D3 |

## Impact

- **Modules du reactor** : `minos-storage` (`QueryViewMemoryBudget`, `SnapshotLimits`, `FileSymbolSnapshotStore`,
  `PostgresCodeKnowledgeSnapshotStore`), `minos-app` (test de contrat de l'image de requête). Aucune dépendance
  nouvelle, aucun package nouveau.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucun changement de contrat. Effet observable : un
  snapshot dont la vue pèse entre le plafond du cache et le budget n'est plus rechargé à chaque requête.
- **ADR** : ADR consolidé à écrire dans le domaine `DATA`, qui remplace DATA-0081 (prochain numéro libre lu dans
  `docs/architecture/adr/README.md` à l'écriture, non réservé).
- **Gates à rejouer** : `scripts/remediation/check-mne.py` (chaînes `Files.deleteIfExists(value.payload())`,
  `estimateWeight(metadata, payloadBytes, decoded.formatVersion())`, `limits.amplifiedBytes(...)`,
  `requireCacheCoversPersistable`), `scripts/remediation/check-mnd.py` (`activeMetadata(projectId)`, `queryCache`,
  `limits.maxQueryCacheWeightBytes()`, `buildLocks`), `scripts/remediation/check-p0-p2.py` (verrous de
  `FileSymbolSnapshotStore`), `scripts/architecture/check-module-boundaries.py`,
  `scripts/quality/check-comment-language.py`, `scripts/architecture/check-private-io.py`,
  `scripts/docs/check-current-docs.py` ; scope JaCoCo `persistence-cache-indexes` ; SonarCloud : nouveau code de
  `minos-storage` ≥ 80 %.
- **Plateformes** : Windows et Linux, même code. Les tests PostgreSQL s'exécutent sur le serveur de test fourni de
  l'extérieur (job Linux de la CI ; sautés et comptés sous Windows sans mode requis).
- **Documentation durable et diagrammes** : `docs/user/docker-runtime.md` (« Limites de ressources » : seuils de
  relations), `docs/developer/indexing-and-storage.md`, dossiers de capacité
  `docs/architecture/diagrams/capacites/persistance-snapshots.md` et `distribution-docker.md`, ADR consolidé.

## Questions ouvertes

Non bloquantes pour la rédaction, attendues du propriétaire avant `/opsx:apply` : D1 (sort d'une vue admise au-dessus
du plafond du cache) et D3 (portée de l'invariant), avec recommandation dans `design.md`. Un choix de D1 autre que la
recommandation change l'exigence « Une vue admise plus lourde que le plafond du cache reste comptée par le budget » et
l'exigence modifiée.
