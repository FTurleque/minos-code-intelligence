# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-06**, après AUD-PERF-01 (ordre interne du sprint). HEAD analysé : `2d1cc214` (`develop`). `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql`, `APP` = `minos-application/src/main/java/com/minos`.

## État vérifié au HEAD

### AUD-PERF-06 — confirmé

- `PG/semantic/PostgresSemanticWriteQueries.java:20-24` : `replace` = `deleteDocuments` + `upsertMetadata` + `insertDocuments` ; `:35-42` `vectorLiteral` textuel ; `:69-86` insertion par lots de 500 avec `CAST(? AS vector)`.
- `PG/semantic/PostgresSemanticVectorStore.java:62-90` : remplacement conditionnel dans une transaction.
- `APP/application/semantic/SemanticIndexService.java:132-133` : `store.load` de l'index précédent complet ; `:172-173` `replaceConditionally` (et un `loadActiveKnowledge` pour le seul identifiant, traité par AUD-PERF-01).
- `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:155` : synchronisation appelée en `NO_CHANGES` ; `:276-288` après une indexation réussie.
- **Schéma** : `PG/support/PostgresSchemaMigrator.java:96`, `PRIMARY KEY(project_id, stable_key)` sur les documents sémantiques ; `CURRENT_VERSION = 4`. L'index déclaré en `:97` double la clé primaire.

**Non vérifié** : les volumes (texte par vecteur, taille du journal), le mode de stockage des colonnes `vector`, la faisabilité d'un envoi binaire par le pilote. Aucune base lancée pendant l'analyse.

### Écarts avec l'action proposée par l'audit

1. **Un document « réutilisé » doit quand même être mis à jour.** `SemanticDocumentFactory.java:119-120` calcule `document_id` comme l'empreinte de (identifiant de snapshot, clé stable, empreinte du contenu), et toutes les lectures joignent sur `d.snapshot_id = m.snapshot_id`. Chaque ligne conservée reçoit donc un `UPDATE` de `document_id` et `snapshot_id` : le nombre de **lignes** touchées reste N. Le gain réel est ailleurs : les **vecteurs** ne sont ni relus, ni renvoyés, ni réanalysés.
2. **L'empreinte du contenu ne couvre pas la position.** Un document de symbole déplacé (lignes de début et de fin, identifiant de source) garde son empreinte : la mise à jour des lignes réutilisées porte aussi sur ces colonnes, sinon le différentiel livrerait des positions périmées.
3. **Sortir `snapshot_id` des lignes** (variante de l'audit) casse l'alignement vérifié par `SemanticSearchService` (`:51-52`) et imposerait une migration de schéma v5, donc l'exigence de migration sans perte. Écarté.
4. **Le port n'a pas à changer.** Le différentiel se fait **dans le store**, dans la même transaction que le contrôle conditionnel : il lit (clé, empreinte) des lignes en place et compare. `replaceConditionally` garde sa signature et sa sémantique.
5. **L'aggravant `NO_CHANGES` n'est pas dans l'action.** Il est traité ici (sortie anticipée) : c'est le cas le plus fréquent et le moins coûteux à corriger.

## Goals / Non-Goals

**Goals** : une indexation sans changement ne touche pas l'index sémantique ; une indexation incrémentale n'écrit que les vecteurs des documents modifiés ; résultat indiscernable d'un remplacement complet ; atomicité conservée.

**Non-Goals** : réduire le nombre de lignes mises à jour ; changer le schéma ; optimiser le store fichier.

## Decisions

### D1. Sortie anticipée sur identité complète

`SemanticIndexService.synchronize` compare les métadonnées en place (snapshot, provider, modèle, dimensions, version de fabrique de documents) à la cible. Égalité stricte et statut `READY` : retour immédiat, sans construire de document. Toute différence, ou un index non prêt, suit le chemin complet. L'identité du snapshot est lue par la lecture légère d'AUD-PERF-01.

### D2. Différentiel dans la transaction conditionnelle

Dans `PostgresSemanticVectorStore`, sous le même contrôle conditionnel qu'aujourd'hui :

1. lire `(stable_key, checksum)` des lignes du projet ;
2. supprimer les clés absentes de la cible ;
3. `INSERT … ON CONFLICT (project_id, stable_key) DO UPDATE` pour les documents nouveaux ou d'empreinte différente, avec leur vecteur ;
4. `UPDATE` par lots des lignes réutilisées : `document_id`, `snapshot_id`, positions et identifiant de source, **sans** la colonne `embedding` ;
5. `upsertMetadata`.

Une erreur à n'importe quelle étape annule la transaction : l'index précédent reste intact.

### D3. La réutilisation se décide sans vecteurs

`SemanticIndexService` ne charge plus l'index précédent complet : il lit les couples (clé, empreinte) et ne demande d'embedding que pour les documents nouveaux ou modifiés. Le store reçoit, pour un document réutilisé, un document **sans vecteur** marqué comme tel ; un document non marqué et sans vecteur est une erreur (fail-closed), jamais une ligne écrite avec un vecteur nul.

### D4. Équivalence par test de propriété

Pour une suite de scénarios (ajout, modification, suppression, déplacement sans changement de contenu, changement de modèle, index vide, tout réutilisé), l'état final de la table après synchronisation différentielle est **égal ligne à ligne** à celui d'un remplacement complet de la même cible.

## Direction des dépendances

Aucune arête nouvelle. Le domaine n'apprend rien de PostgreSQL ; `minos-application` ne dépend d'aucun adaptateur.

## Qualification des capacités

- Synchronisation différentielle PostgreSQL : **non qualifiée** au HEAD ; qualifiée par D4 sur base réelle.
- Store fichier : inchangé ; la sortie anticipée D1 lui profite.
- Envoi binaire des vecteurs : **non supporté**, non promis.

## Windows et Linux

La sortie anticipée est indépendante de la plateforme. Les tests du store exigent PostgreSQL avec pgvector : exécutés en CI sous Linux ; dire explicitement s'ils ont tourné sur le poste.

## Risks / Trade-offs

- Un différentiel faux est pire qu'un remplacement lent : D4 est la condition de fusion.
- Un changement de modèle ou de dimensions doit invalider toute réutilisation : couvert par un scénario dédié.
- La transaction reste longue sur un gros index (N mises à jour) ; mesurer (tâche 3.3) avant de promettre un gain.
- Partage de `SemanticDocumentFactory` avec `lire-chaque-source-une-fois-par-fichier` : fusion en série.
