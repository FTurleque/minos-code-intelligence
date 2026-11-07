# Proposal

## Why

Un projet Java de la taille de `morpheus-engine` (1 488 fichiers Java dont 769 de tests, 197 653 lignes) ne peut pas être importé dans MINOS : `scip-java` réussit, puis MINOS refuse le snapshot (« knowledge snapshot is too large to persist: 358914010 encoded bytes exceed the 268435456-byte limit (256 MiB); nothing was written »). Le reactor de MINOS lui-même, tests compris, dépasse déjà ce plafond (300 Mo, ADR DATA-0066, section Risques). Deux projets réels de 1 500 fichiers donnent donc le même refus.

Une mesure du 2026-11-07, faite avec un jar jetable à plafond relevé (rien commité), montre que relever seul le plafond de persistance ne suffit pas : le snapshot de `morpheus-engine` (356 Mo) s'importe en 11 s, mais chaque requête MCP prend de 4,0 à 4,6 s, sans gain sur les appels suivants, alors qu'un snapshot de 73 Mo répond en 1,1 s puis 5 à 7 ms. La vue active n'entre pas dans le cache de requêtes : son poids estimé (679 Mio) dépasse le plafond de 512 Mio. Les deux plafonds sont couplés par construction (256 Mio × amplification 2 = 512 Mio) et se règlent ensemble.

Aucun constat d'audit ne porte sur ce besoin : il vient d'un usage réel (projets Java du propriétaire). Des utilisateurs Java de MINOS ont des projets de cette taille : la capacité « indexer du Java » est aujourd'hui honnête pour les projets de moins de 1 100 à 1 300 fichiers Java environ (estimation sur deux points de mesure) et refuse les autres.

## What Changes

- **Plafond de persistance** : un snapshot de connaissance jusqu'au nouveau plafond (400 Mio proposé) est persisté, par les deux stores (fichier et PostgreSQL) ; au-delà, le refus actuel est conservé (rien d'écrit, message de tailles seules, aucun chemin).
- **Poids estimé d'une vue** : l'amplification forfaitaire de 2 fois la taille persistée est remplacée par une amplification mesurée (1,25 proposé) pour le format courant ; V1 et V2 gardent 2 ; le poids estimé reste supérieur ou égal au tas retenu (exigence existante).
- **Plafond du cache de vues** : inchangé à 512 Mio. Invariant testé : plafond du cache ≥ plafond de persistance × amplification (400 Mio × 1,25 = 500 Mio).
- **Mesure** : le banc `benchmarks/scalability/` couvre un corpus au-delà de 256 Mio ; la page `docs/user/` dit quelle taille de projet est indexable et ce que MINOS répond au-delà.
- **Pas de nouveau format** : le format V4 (table de chaînes, option B de l'ADR 0047) n'est pas livré ici ; il réduit le fichier, pas le tas, donc ne règle pas l'admission au cache.

## Capabilities

### New Capabilities

Aucune. Le comportement relève de la capacité de persistance et de lecture des snapshots, qui existe.

### Modified Capabilities

- `persistance-snapshots` : exigences « Un snapshot de la taille de ce dépôt est admis au cache de vues » et « Le poids estimé d'une vue ne sous-évalue pas le tas qu'elle retient » (scénarios sur un snapshot de plus de 300 Mio) ; ajout de trois exigences : persistance d'un snapshot de plus de 256 Mio, refus au-delà du plafond, plafond du cache couvrant tout snapshot persistable.

## Hors périmètre

- **Format V4** (table de chaînes) et **snapshot mappé en mémoire** (options B et C de l'ADR 0047) : décisions du propriétaire, déclenchées par la taille sur disque après dédoublonnage ; non nécessaires pour ce besoin.
- **Dimensionnement mémoire du conteneur MCP Docker** (`MINOS_MCP_MEM_LIMIT`, tas de la JVM) : question ouverte, hors des specs ; le changement mesure et recommande, il ne modifie pas les fichiers Compose.
- **Indexation Java depuis Windows** (voie WSL2, ADR SEC-0074) : autre changement.
- **Budget sémantique** (32 768 documents) et temps d'embedding : inchangés.
- **Indexation par module** d'un reactor trop gros : non retenue (le POM parent n'est pas visible d'un sous-dossier).
- **Exclusion des tests de l'index** : non retenue, la fonction des tests liés repose sur eux.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le plafond de persistance est de 256 Mio, refuse avant toute écriture et borne aussi la lecture | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/codec/SnapshotBinaryCodecSupport.java:87`, `:253-258` |
| Le cache de vues est plafonné à 512 Mio ; le poids estimé vaut le plus grand de la somme des comptes et de 2 fois la taille persistée, plus l'index des arêtes | [Existant vérifié] | `FileSymbolSnapshotStore.java:44`, `:54`, `:362-372` ; `PostgresCodeKnowledgeSnapshotStore.java:58`, `:60`, `:472-481` |
| Poids estimé de la vue de `morpheus-engine` : 679,1 Mio (amplification 2), 509,3 Mio (1,5), 424,4 Mio (1,25) | [Existant vérifié] | calcul sur 60 472 symboles, 488 477 occurrences, 6 243 relations, fichier de 356 038 984 octets (hors index des arêtes) |
| Sur un snapshot de 73 Mo, 3 requêtes MCP donnent 1 143 ms, 7 ms, 5 ms ; sur celui de 356 Mo, 4 559, 4 295, 4 374 ms (tas 1 Go) | [Existant vérifié] | sonde MCP STDIO du 2026-11-07, jar jetable, `MINOS_HOME` temporaire |
| Une amplification de 1,25 sous-évaluerait le tas du corpus synthétique (1,41 à 1,46 fois le fichier V3) ; 1,14 sur le corpus réel de MINOS | [Existant vérifié] | ADR 0047 archivé, section Mise en œuvre (mesures) |
| Aucun `-Xmx` ni pourcentage de tas n'est posé dans les fichiers Docker, de packaging ou d'installation ; le conteneur MCP a 3 Gio | [Existant vérifié] | recherche `Xmx`/`MaxRAM` dans `docker/`, `packaging/`, `scripts/install/` ; `docker/compose-mcp.prod.yaml:13` |
| Tas retenu de la vue de `morpheus-engine` : 374 738 864 octets, soit 1,05 fois le fichier ; corpus synthétique de 3 300 fichiers : 1,47 fois ; un chargement tient dans 512 Mo de tas, deux chargements simultanés échouent sous 640 Mo ; publication PostgreSQL 12,1 s, lectures de 9,5 à 13,7 s sans cache | [Existant vérifié] | `LargeSnapshotMeasurement`, `LargePostgresSnapshotMeasurement`, `docs/developer/indexing-and-storage.md` (mesures du 2026-11-07) |
| Le pic de décodage au-delà du tas minimal, l'import sous un tas de 512 Mo et l'espace disque de la rétention (par défaut 2 snapshots historiques et l'actif) ne sont pas mesurés | [Question] | tâches 1.3 et 1.5 |
| Plafond de persistance de 400 Mio, amplification de 1,25, plafond du cache inchangé à 512 Mio | [Proposition, révisée par les mesures] | design, décisions D1 et D2 ; valeurs à valider par le propriétaire |

## Impact

- **Modules du reactor** : `minos-storage` (codec, `FileSymbolSnapshotStore`, `PostgresCodeKnowledgeSnapshotStore`), `minos-engine` (`SnapshotReadView`, poids des vues), `minos-bootstrap` (banc de montée en charge, test) ; aucune dépendance ajoutée.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : additif. Aucun champ ne change ; un projet auparavant refusé devient indexable ; le message de refus au-delà du plafond garde son préfixe.
- **ADR** : `DATA-0078` consolidé (remplace `DATA-0066`), écrit au groupe 3 (plafonds, amplification mesurée, invariant) ; non créé pendant la spécification.
- **Gates à rejouer** : `scripts/remediation/check-mne.py` (chaînes du plafond, de l'amplification et de `estimateWeight`, à mettre à jour avec le code), `check-audit-remediation-v2.py`, `check-mnd.py`, `check-current-docs.py`, `product-facts.py --check`, `check-capability-dossiers.py`, `run_gates.py --fast` ; scopes JaCoCo `storage-codec-and-state` et celui de `minos-engine` ; tests `PersistedSnapshotLimitTest`, `QueryViewWeightTest`, `PostgresCodeKnowledgeSnapshotStoreTest`, `SnapshotEncodedSizeTest`, `SnapshotCodecV3Test`.
- **Plateformes** : Windows et Linux ; le comportement ne dépend pas de la plateforme. PostgreSQL géré : non mesuré pour un payload de 356 Mo.
- **Documentation durable et diagrammes** : dossier `docs/architecture/diagrams/capacites/persistance-snapshots.md` ; page `docs/user/` sur la taille indexable ; ADR DATA-0066 ; `docs/developer/` (banc de montée en charge).

## Questions ouvertes

**Bloquantes** : aucune.

**Non bloquantes**
- Valeurs finales (400 Mio de persistance, amplification 1,25, cache inchangé à 512 Mio) : à valider par le propriétaire ; les mesures du groupe 1 sont faites.
- Dimensionnement du tas du conteneur MCP (`MINOS_MCP_MEM_LIMIT`, `MaxRAMPercentage`) : un tas de 25 % de 3 Gio laisse 768 Mio ; mesuré, une vue de 372 Mo tient, deux chargements simultanés non ; décision et changement `distribution-docker` éventuel à part.
- Chargements simultanés d'une vue non mise en cache : exigence de bornage à proposer (le design la recommande), hors de ce changement.
- Format V4 : le déclencheur de l'ADR 0047 (taille sur disque) est-il atteint par ce besoin ? Recommandation : non, tant que les plafonds relevés tiennent.
