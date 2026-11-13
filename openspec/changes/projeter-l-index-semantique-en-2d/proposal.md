# Proposal

## Why

Une liste et une recherche ne donnent pas de vue d'ensemble d'un index sémantique : répartition par module, zones denses, position des résultats d'une recherche. La demande du propriétaire (2026-11-12, recommandations acceptées le 2026-11-13) retient une carte 2D des embeddings, honnête sur ce qu'elle montre. Étude : `docs/project/research/explorateur-vectoriel-2026-11/README.md` § 5.

Ce changement suit `explorer-l-index-semantique-dans-un-navigateur`, qui crée la capacité `exploration-semantique`, la surface HTTP et l'interface. **Il ne démarre qu'après son archivage.**

## What Changes

- **Ajout** d'une lecture des vecteurs par lots bornés sur le port vectoriel et ses deux adaptateurs.
- **Ajout** d'une projection 2D par PCA, calculée par la commande `minos semantic projection prepare`, jamais par une consultation.
- **Ajout** d'un cache dérivé en fichier sous `MINOS_HOME`, publié atomiquement et lié à l'identité de l'index.
- **Ajout** de la commande `minos semantic projection status`, des routes HTTP de statut et de points, et de la carte dans l'interface.
- **Ajout** de mesures aux paliers de corpus disponibles.
- **Inchangé** : classement sémantique et hybride ; schéma PostgreSQL (aucune migration).

## Capabilities

### New Capabilities

Aucune : `exploration-semantique` est créée par le changement précédent.

### Modified Capabilities
- `exploration-semantique` : quatre exigences ajoutées (préparation explicite et bornée, publication atomique liée à l'identité de l'index, points annoncés, lecture sans calcul).

## Hors périmètre

- UMAP, t-SNE, rendu WebGL, vue 3D : décision mesurée par la tâche 7.1, puis changement distinct.
- Carte couvrant plusieurs projets ; liens entre points ; nommage calculé de groupes.
- Tables PostgreSQL pour le cache (schéma v6) ; index vectoriel approché.
- Préparation déclenchée par HTTP ou par le MCP.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les vecteurs ne se lisent que par chargement complet de l'index | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticReadQueries.java:121-147` |
| Le magasin local plafonne à 192 Mio de vecteurs décodés | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:56` |
| Le plan de requête Docker monte les données en lecture seule ; `minos-admin` écrit | [Existant vérifié] | `docker/compose-mcp.connected.yaml:63-67`, `:103-132` |
| Aucune construction déclenchée en silence | [Décision] | BE-0029 |
| PCA en Java, cache en fichier, préparation par commande | [Décision] | propriétaire, 2026-11-13 |
| Lisibilité d'une carte PCA sur des embeddings de code | [Question] | à mesurer, tâche 6.8 |
| Seuils de performance | [Question] | à fixer après la première mesure |

## Impact

- **Modules du reactor** : `minos-domain` (lecture par lots, port `SemanticProjectionStore`), `minos-storage` (deux adaptateurs, cache fichier), `minos-application` (`SemanticProjectionService`), `minos-api` (ajouts à `SemanticExplorationApi`), `minos-cli` (commandes), `minos-explorer` (routes et carte).
- **Surfaces publiques** : CLI — additif (`semantic projection prepare|status`) ; API Java — additif ; surface HTTP — additif (deux routes en lecture) ; MCP, IntelliJ, NEXUS — aucun.
- **ADR** : aucun nouveau. ARCH-0082 (lecture seule, préparation hors HTTP) et BE-0031 (classement exact) s'appliquent sans amendement.
- **Gates à rejouer** : `scripts/architecture/check-private-io.py`, `scripts/architecture/check-module-boundaries.py`, `scripts/quality/check-comment-language.py`, `scripts/docs/check-capability-dossiers.py` ; scopes JaCoCo des modules touchés.
- **Plateformes** : Windows et Linux ; en Docker, préparation par `minos-admin`. Le remplacement atomique de fichier est à qualifier sur les deux.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/exploration-semantique.md`, pages `docs/user/` et `docs/developer/`, `STATUS.md`, `ROADMAP.md`.

## Questions ouvertes

Non bloquantes : seuils de performance ; plafond de points par défaut (cible provisoire : 100 000).
