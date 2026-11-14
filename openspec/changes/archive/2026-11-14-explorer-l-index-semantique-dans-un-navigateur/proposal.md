# Proposal

## Why

L'index sémantique de MINOS ne se consulte que par des commandes qui rendent des résultats de recherche : il n'existe ni liste des documents indexés, ni vue de leur contenu, ni interface pour les parcourir (étude `docs/project/research/explorateur-vectoriel-2026-11/README.md`, § 1 et § 2). Demande du propriétaire du 2026-11-12 ; recommandations de l'étude acceptées le 2026-11-13, dont la décision ARCH-0082.

Ce changement est le deuxième des trois du chantier : `inspecter-postgresql-avec-pgadmin` (indépendant), celui-ci, puis `projeter-l-index-semantique-en-2d`. **Rien n'est implémenté.**

## What Changes

- **Ajout** du module de surface `minos-explorer` : surface HTTP locale, optionnelle et en lecture seule, démarrée par `minos explorer serve`, qui sert une interface web embarquée et une API JSON versionnée (`/api/v1/`).
- **Ajout** de lectures sur l'index sémantique : liste paginée des documents, détail (contenu indexé et source actuelle), documents voisins d'un document.
- **Ajout** d'une interface Java publique additive, `SemanticExplorationApi` (contrat v1).
- **Ajout** au runtime Docker d'un service `minos-explorer` optionnel, publié sur la boucle locale (tâche 5.7).
- **Inchangé** : classement sémantique et hybride, MCP en STDIO, schéma des tables sémantiques, contrat `SemanticCodeIntelligenceApi` v1.

Aucune rupture de contrat.

## Capabilities

### New Capabilities
- `exploration-semantique` : consultation en lecture seule des documents sémantiques d'un projet (liste, détail, recherche, voisins, surface HTTP locale) — nouvelle parce que `recherche-semantique` couvre la construction de l'index et le chemin de requête, pas une surface de consultation.

### Modified Capabilities

Aucune. Le service Docker `minos-explorer` est couvert par les exigences de `exploration-semantique` (boucle locale, jeton, lecture seule) ; si sa qualification demande une exigence propre à `distribution-docker`, elle est ajoutée à ce changement avant la tâche 5.7.

## Hors périmètre

- pgAdmin : changement `inspecter-postgresql-avec-pgadmin`.
- Projection, carte 2D, lecture des vecteurs par lots : changement `projeter-l-index-semantique-en-2d`.
- Accès distant, comptes utilisateurs, TLS, autorisation par projet.
- Toute écriture depuis le navigateur ; déclenchement d'une indexation ou d'une synchronisation sémantique par HTTP.
- Index vectoriel approché et toute modification du classement exact (BE-0031).
- Outils MCP nouveaux ; outil du plugin IntelliJ.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les tables sémantiques sont `semantic_documents` et `semantic_index_meta`, une ligne de métadonnées par projet | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java:96-97` |
| Un document est de nature `SYMBOL`, `CHUNK` ou `FILE` | [Existant vérifié] | `minos-domain/src/main/java/com/minos/semantic/SemanticDocumentKind.java:4-8` |
| Le contenu indexé n'est exposé par aucun DTO public | [Existant vérifié] | `minos-api/src/main/java/com/minos/api/SemanticCodeIntelligenceApi.java:56-65` |
| Aucun serveur HTTP n'existe dans le produit | [Existant vérifié] | `git grep` au commit `63e0c4ac` : aucune occurrence |
| Aucune permission ne régit la lecture d'un projet | [Existant vérifié] | `minos-engine/src/main/java/com/minos/hosted/HostedPermission.java:4-14` |
| La surface est HTTP, locale, optionnelle et en lecture seule | [Décision] | ADR ARCH-0082, acceptée par le propriétaire le 2026-11-13 |
| La surface vit dans un module `minos-explorer` | [Décision] | propriétaire, 2026-11-13 ; ARCH-0082 § 8 |
| Le runtime Docker est servi avant la carte | [Décision] | propriétaire, 2026-11-13 |
| L'arête `minos-cli → minos-explorer` est permise par `ALLOWED_DEPENDENCIES` | [Question] | tâche 2.1 |
| `jdk.httpserver` figure dans le runtime Windows embarqué | [Question] | tâche 4.9 |

## Impact

- **Modules du reactor** : `minos-domain` (lectures additives du port vectoriel), `minos-storage` (adaptateurs local et PostgreSQL), `minos-application` (`SemanticCorpusQueryService`), `minos-api` (interface additive), `minos-cli` (commande), `minos-app` (assemblage), **nouveau module `minos-explorer`**.
- **Surfaces publiques** : CLI — additif (`explorer serve`) ; API Java — additif (`SemanticExplorationApi` v1) ; **nouvelle surface HTTP locale** ; MCP — aucun ; IntelliJ — aucun ; NEXUS — aucun.
- **ADR** : ARCH-0082 (`Accepted`) ; sa section « Mise en œuvre » est complétée à la livraison. Aucun autre ADR créé ni amendé.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, `scripts/quality/check-comment-language.py`, `scripts/quality/check-semantic-retrieval-consistency.py`, `scripts/quality/check-compose-limits.py` et `scripts/quality/check-image-pins.py` (tâche 5.7), `scripts/docs/check-capability-dossiers.py`, `scripts/docs/check-current-docs.py` ; scopes JaCoCo des modules touchés (seuil SonarCloud de 80 % sur le code neuf, par module).
- **Plateformes** : Windows et Linux. Propre à Windows : présence de `jdk.httpserver` dans le runtime embarqué (`scripts/release/build-windows-distribution.ps1:255-256`). Propre à Docker : réseau d'entrée dédié pour la publication du port.
- **Documentation durable et diagrammes** : nouveau dossier `docs/architecture/diagrams/capacites/exploration-semantique.md` ; pages `docs/user/` (explorateur) et `docs/developer/` ; `docs/architecture/diagrams/module-dependencies.md` (généré) ; `STATUS.md` et `ROADMAP.md` à la livraison.

## Questions ouvertes

Aucune décision du propriétaire en attente. Restes techniques, non bloquants pour la spécification : arête de dépendance de la CLI vers la surface (tâche 2.1) ; port par défaut et ouverture du navigateur (design, « Questions ouvertes »).
