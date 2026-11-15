# Proposal

## Why

Le constat **AUD-PERF-09** (Faible, Performance, confiance « probable », non reproduit par l'audit) de l'audit du
2026-11-15 (`docs/project/audits/2026-11-15/findings.json`, sprint 5) relève que, dans le plan de requête Docker
connecté à PostgreSQL, la zone de travail du store de snapshots est un tmpfs de 256 Mio
(`docker/compose-mcp.connected.yaml:96`, `:154`) alors que chaque JVM y copie la charge utile entière avant de la
décoder (`PostgresCodeKnowledgeSnapshotStore.java:446-459`). Le budget de mémoire des vues vaut par JVM (ADR
DATA-0081, Risques) : il ne borne pas la somme des copies de plusieurs clients MCP, chacun une JVM du même conteneur.
Une zone pleine se manifeste par une erreur d'E/S du système, rendue comme erreur interne.

**Statut des chiffres.** Les valeurs de l'audit (deux JVM à 150 Mio, snapshot de plus de 256 Mio sous un tas relevé)
et celles de ce changement sont **calculées sur les constantes du code et des fichiers compose**, non mesurées ; le
fait que le tmpfs soit compté dans `mem_limit` n'a pas été observé ici. Ce changement exige une mesure (tâche 3.1).

## What Changes

- **Dimensionnement déclaré** : la taille de la zone de travail PostgreSQL des services du plan de requête
  (`minos-mcp`, `minos-explorer`) couvre le nombre déclaré de JVM simultanées multiplié par la plus grande charge
  utile PostgreSQL admissible sous le budget par défaut ; ce nombre de JVM est écrit dans la documentation
  utilisateur ; un test de contrat recalcule la borne et vérifie que tas et zone tiennent sous la limite mémoire.
- **Réglage repris** : la taille de la zone est une variable du fichier d'environnement du runtime ; les scripts
  d'installation la reportent dans le fichier régénéré et refusent une valeur invalide, comme pour les plafonds de
  ressources.
- **Refus explicite** : avant de demander une charge utile, le store PostgreSQL vérifie que la zone de travail peut
  la recevoir ; une zone insuffisante, avant ou pendant la copie, donne une erreur publique sans chemin (« insufficient
  scratch space to load this snapshot »), rendue par la CLI (code 1 existant) et par le MCP avec son message, au lieu
  d'une erreur interne.
- **ADR** : le point 17 et les risques de DATA-0081 sont complétés par l'ADR consolidé du sprint 5 (voir `design.md`,
  D4).

Additif : nouveau message d'erreur, aucun code de sortie nouveau, aucun champ retiré.

## Capabilities

### New Capabilities

Aucune : le dimensionnement des services relève de `distribution-docker`, la lecture d'un snapshot PostgreSQL de
`persistance-snapshots`.

### Modified Capabilities

- `distribution-docker` : exigences ajoutées — la zone de travail PostgreSQL du plan de requête est dimensionnée pour
  le nombre déclaré de JVM ; le réglage de sa taille survit à la régénération du fichier d'environnement.
- `persistance-snapshots` : exigences ajoutées — une zone de travail insuffisante donne un refus explicite ; ce refus
  est rendu par la CLI et le MCP avec son message.

## Hors périmètre

- Le décodage depuis le flux du pilote sans fichier intermédiaire (seconde branche de l'action de l'audit) : écarté
  pour ce lot (`design.md`, D3) ; il changerait le pic de tas réservé à 310 % et demande une mesure propre.
- Un bornage commun à plusieurs JVM (budget inter-processus) : hors de DATA-0081, non proposé.
- Le rendu de ce refus par la surface de l'explorateur : `minos-explorer` monte la même zone
  (`docker/compose-mcp.connected.yaml:154`) mais n'y fait écrire qu'une JVM, et la zone dépasse la charge utile
  admissible au défaut ; son rendu des refus appartient aux changements de sa surface. Le refus y reste une erreur
  d'E/S au message public.
- La zone de travail en écriture (publication, plan d'administration) : elle vit sur le volume de données, pas sur
  un tmpfs.
- AUD-PERF-05, AUD-PERF-06 (changement `compter-toute-vue-admise-et-lier-le-rapatriement-postgresql`) et AUD-PERF-04
  (changement `borner-les-caches-applicatifs-par-le-tas`).
- Les constats du sprint 4 (AUD-PERF-01, -02, -03, -10) : prérequis d'ordre, non spécifiés ici. Aucune migration de
  schéma n'est prévue par ce changement (la migration 6 du sprint 4 n'est pas concurrencée).
- Les fichiers de l'audit et les index partagés des ADR : tenus par le propriétaire.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La zone de travail est un tmpfs de 256 Mio dans `minos-mcp` et `minos-explorer` | [Existant vérifié] | `docker/compose-mcp.connected.yaml:96`, `:154` |
| Chaque lecture copie la charge utile entière dans cette zone | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java:446-459`, `:461-471` |
| `minos-mcp` héberge une JVM par client MCP ; `minos-explorer` une seule | [Existant vérifié] | `docker/compose-mcp.connected.yaml:45`, `:48`, `:113-114` ; `docs/user/docker-runtime.md:419` |
| Le budget vaut par JVM | [Décision] | DATA-0081, Risques (`:117`) |
| Aucune traduction d'un manque d'espace n'existe dans le code de production | [Existant vérifié] | recherche de `No space` et `ENOSPC` sous `minos-*/src/main` : aucune occurrence |
| Charge utile PostgreSQL admissible par JVM sous le budget par défaut : environ 173 Mio ; deux JVM : environ 347 Mio | [Question] non bloquante | calculé (563 714 457 / 3,10), non mesuré ; DATA-0081 `:119` donne le même ordre de grandeur |
| Le tmpfs est compté dans `mem_limit` | [Question] non bloquante | affirmé par l'audit, non observé ; mesure en tâche 3.1 |
| Nombre déclaré de JVM simultanées : 2 | [Proposition] | `design.md`, D1 ; DATA-0081 point 17 (« deux JVM au tas plein tiennent encore sous 3 Gio ») |

## Impact

- **Modules du reactor** : `minos-storage` (store PostgreSQL de snapshots), `minos-domain` (type d'erreur publique,
  `com.minos.diagnostics`), `minos-mcp` (rendu du message), `minos-cli` (test seul), `minos-app` (test de contrat).
  Hors reactor : `docker/compose-mcp.connected.yaml`, `docker/.env.example`, `docker/scripts/mcp-lifecycle.ps1`,
  `docker/scripts/prod-mcp.ps1`.
- **Surfaces publiques** : CLI et MCP, **additif** (nouveau message d'erreur, code de sortie 1 existant) ; API Java :
  l'erreur reste une `IOException` ; IntelliJ et NEXUS : aucun.
- **ADR** : ADR consolidé du domaine `DATA` qui remplace DATA-0081 (prochain numéro libre à l'écriture, non
  réservé), commun aux changements du sprint 5.
- **Gates à rejouer** : `scripts/quality/check-compose-limits.py` et son auto-test (non-régression : ils ne lisent
  pas la taille de la zone), `scripts/install/test_mcp_lifecycle_*.py`, `scripts/install/test_docker_script_contexts.py`,
  `scripts/quality/check-twin-script-functions.py` et son auto-test,
  `scripts/remediation/check-mne.py`, `scripts/remediation/check-mnd.py`,
  `scripts/architecture/check-module-boundaries.py` (A2, A8, A11), `scripts/architecture/check-private-io.py`,
  `scripts/quality/check-comment-language.py`, `scripts/docs/check-current-docs.py` ; scope JaCoCo `mcp-mapping` ;
  SonarCloud : nouveau code ≥ 80 % par module.
- **Plateformes** : le refus explicite vaut sous Windows et Linux (zone de travail sous `MINOS_HOME`) ; le tmpfs et
  son dimensionnement sont propres à Docker (Linux).
- **Documentation durable et diagrammes** : `docs/user/docker-runtime.md` (« Limites de ressources », table de
  dépannage), dossiers de capacité `distribution-docker.md` et `persistance-snapshots.md`.

## Questions ouvertes

Non bloquantes pour la rédaction, attendues du propriétaire avant `/opsx:apply` : D1 (nombre déclaré de JVM et taille
par défaut) et D2 (type de l'erreur publique), avec recommandation dans `design.md`.
