# Proposal

## Why

Le constat **AUD-PERF-15** (Moyenne, Performance), ouvert le 09/11/2026 dans l'état de l'audit 2026-10-10, relève
qu'un serveur MCP peut épuiser son tas en chargeant de gros snapshots :

- le plafond du cache de vues est une constante de 512 Mio qui ignore le tas réel de la JVM
  (`minos-storage/src/main/java/com/minos/storage/codec/SnapshotLimits.java:32`) ; une vue maximale
  (400 Mio × 125 % = 500 Mio) le remplit seule ;
- les chargements de vues non mises en cache ne sont pas bornés : un verrou par projet sérialise un même projet, rien
  ne borne deux projets en même temps ; l'ADR DATA-0078 le reconnaît comme risque (l. 102-103 : 372 Mo par vue, un tas
  de 640 Mo échoue à deux ; le tas par défaut du conteneur MCP, 25 % de 3 Gio soit 768 Mio, ne tient pas deux
  chargements) ;
- les images MCP ne fixent aucun tas : la JVM prend 25 % de `mem_limit` par défaut ;
- sous PostgreSQL, la ligne `bytea` entière est rapatriée par le pilote avant que `copyBounded` n'applique la borne
  (`PostgresCodeKnowledgeSnapshotStore.java:389` puis `:409`).

L'issue actuelle d'un dépassement est un `OutOfMemoryError`, qui peut laisser le processus dans un état incertain. Le
changement `supporter-les-gros-snapshots` (ADR DATA-0078, 2026-11-07) a relevé le plafond de persistance à 400 Mio et
laissé ce bornage « à proposer » : c'est le seul constat encore ouvert de l'audit.

## What Changes

- **Admission au poids** : un chargement de vue non mise en cache réserve son poids estimé sur un budget de mémoire
  des vues, commun à tout le processus, **avant** de lire ou décoder le snapshot. Si le budget est occupé, le
  chargement attend un temps borné, puis échoue par une erreur explicite (« insufficient memory to load this
  snapshot ») et non par un `OutOfMemoryError`.
- **Budget dérivé du tas** : le budget vaut une fraction réglable du tas maximal (`Runtime.maxMemory()`), 50 % par
  défaut ; le plafond **effectif** du cache vaut `min(512 Mio, budget)`. Une vue plus lourde que le budget est refusée
  sans attente, avec un message qui nomme le réglage à changer. Le réglage est une propriété système
  `minos.storage.viewHeapPercent` ou la variable `MINOS_VIEW_HEAP_PERCENT` ; une valeur invalide est refusée.
- **PostgreSQL** : la taille de la charge utile (`octet_length`) et son en-tête de format sont lus avec les
  métadonnées du snapshot actif ; une ligne au-delà du plafond de persistance est refusée sans que la charge utile
  soit demandée au serveur. Le transitoire du pilote pendant le rapatriement est compté dans la réservation.
- **Images MCP** : le plan de requête fixe son tas en proportion de sa limite mémoire (`-XX:MaxRAMPercentage=35`,
  soit environ 1 Gio pour `mem_limit` = 3 Gio), dans `docker/Dockerfile.mcp`, l'étape `query` de
  `docker/Dockerfile.mcp.release` et le service `minos-mcp` des deux compose.
- **Surfaces** (additif) : la CLI rend le message de refus avec le code de sortie existant `1` ; l'outil MCP rend le
  même message au lieu de l'erreur générique. Aucun nouveau code de sortie, aucun champ retiré, goldens inchangés.
- **ADR DATA-0078 amendé** : mesures, fraction retenue, invariant du point 14 précisé (plafond configuré contre
  plafond effectif), risque des chargements simultanés retiré.

## Capabilities

### New Capabilities

Aucune : le bornage de la lecture d'un snapshot relève déjà de `persistance-snapshots` (cache, poids estimé,
plafonds), le dimensionnement des services de `distribution-docker`.

### Modified Capabilities

- `persistance-snapshots` : exigences ajoutées (admission au poids avant décodage, refus d'une vue plus lourde que le
  budget, plafond du cache dérivé du tas, réglage du budget, taille PostgreSQL lue avant le rapatriement, refus de
  mémoire public sur les surfaces) ; exigences modifiées (« Le plafond du cache de vues couvre tout snapshot
  persistable », « Un snapshot de plus de 256 Mio est persisté jusqu'au plafond de persistance », « Un snapshot de la
  taille de ce dépôt est admis au cache de vues ») pour distinguer plafond configuré et plafond effectif.
- `distribution-docker` : exigence ajoutée — le plan de requête fixe le tas de sa JVM en proportion de sa limite
  mémoire. Justification : le tas est une propriété de l'image et du compose, pas du stockage ; sans elle, le budget
  dérivé du tas dépendrait d'une valeur implicite de la JVM (25 %).

## Hors périmètre

- Le bornage **entre plusieurs JVM** d'un même conteneur : chaque client MCP lance sa propre JVM dans `minos-mcp`
  (`docs/user/docker-runtime.md`, « Limites de ressources ») ; le budget est par processus. Le risque résiduel
  (plusieurs JVM chargeant chacune un gros snapshot sous `mem_limit`) reste inscrit au registre.
- Un rapatriement PostgreSQL **par tranches** (`substring` sur la colonne) : avec le stockage `EXTENDED` (compressé)
  de `payload`, chaque tranche décompresserait la valeur ; changer le stockage est une migration de schéma, hors lot.
- Les tas des services `minos-admin` et des tâches ponctuelles (ils lancent des providers JVM fils sous 2 Gio et
  512 Mio) ; le format de snapshot (pas de V4, pas de snapshot mappé, DATA-0078 point 10).
- Les fichiers partagés `docs/architecture/risks/register.md`, `docs/project/audits/2026-10-10/SUIVI.md`,
  `docs/developer/supply-chain.md`, `ETAT-2026-10-13.md` et `findings.json` : texte proposé dans `design.md`.
- Tout autre constat d'audit et le risque R-28.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le plafond du cache est une constante de 512 Mio | [Existant vérifié] | `SnapshotLimits.java:32`, `:42` |
| Une vue V3 maximale pèse 500 Mio estimés | [Existant vérifié] | `SnapshotLimits.java:93-96` ; DATA-0078 point 14 |
| Deux projets différents se chargent sans borne commune | [Existant vérifié] | verrou par projet : `FileSymbolSnapshotStore.java:232-233`, `PostgresCodeKnowledgeSnapshotStore.java:160-161` |
| La ligne `bytea` est rapatriée avant la borne | [Existant vérifié] | `PostgresCodeKnowledgeSnapshotStore.java:389`, `:407-409` |
| Les images MCP ne fixent pas de tas | [Existant vérifié] | `docker/Dockerfile.mcp:45`, `docker/Dockerfile.mcp.release:67` ; `docker/compose-mcp.prod.yaml:28-45` |
| Une vue de 363,4 Mio estimés se charge sous 512 Mo de tas ; deux échouent sous 640 Mo | [Existant vérifié] | DATA-0078, Context (mesures du 2026-11-07) |
| Fraction 50 %, attente 30 s, `MaxRAMPercentage=35` | [Proposition] | `design.md`, D2, D4, D6 |
| Amplification du rapatriement PostgreSQL : 300 % jusqu'à mesure | [Proposition] | `design.md`, D5 ; mesure en tâche 4.1 |
| Le pic de décodage au-delà de 512 Mo et Linux ne sont pas mesurés | [Question] non bloquante | DATA-0078 l. 106 ; mesurée en tâche 2.1 |

## Impact

- **Modules du reactor** : `minos-storage` (budget, stores fichier et PostgreSQL, `SnapshotLimits`) ; fichiers hors
  colonne requis : `minos-domain` (type d'exception publique), `minos-mcp` (rendu du message), `minos-app` (test de
  contrat des images) — voir `design.md`.
- **Surfaces publiques** : CLI et MCP, **additif** (nouveau message d'erreur, code de sortie existant) ; API Java :
  l'exception reste une `IOException` ; IntelliJ et NEXUS : aucun.
- **ADR** : amender DATA-0078 (points 13-14, Risques, Compliance) ; pas de nouvel ADR.
- **Gates à rejouer** : `scripts/remediation/check-mne.py`, `scripts/remediation/check-mnd.py` (chaînes de
  `SnapshotLimits` et des deux stores), `scripts/quality/check-compose-limits.py`,
  `scripts/architecture/check-module-boundaries.py` (A2, A8, A11), `scripts/quality/check-comment-language.py`,
  `scripts/quality/check-private-io.py` ; JaCoCo et SonarCloud : nouveau code de `minos-storage` ≥ 80 %.
- **Plateformes** : Windows et Linux, même code ; le test de simultanéité lance une JVM fille en `-Xmx` réduit sur
  les deux ; les images Docker sont Linux.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/persistance-snapshots.md`,
  `docs/architecture/diagrams/capacites/distribution-docker.md`, `docs/developer/indexing-and-storage.md`,
  `docs/user/docker-runtime.md` (« Limites de ressources »).

## Questions ouvertes

Aucune bloquante pour les specs. Décisions du propriétaire attendues (recommandations dans `design.md`, « Questions
ouvertes ») : type d'exception publique dans `minos-domain` et rendu MCP ; valeur de `MaxRAMPercentage` face aux JVM
multiples du conteneur ; réglage de l'attente.
