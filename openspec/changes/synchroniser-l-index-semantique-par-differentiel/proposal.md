# Proposal

## Why

**AUD-PERF-06** (moyenne, sprint 4 de l'audit du 10 octobre 2026). Sous PostgreSQL, chaque synchronisation de l'index sémantique **supprime puis réinsère tout l'index** du projet : `PostgresSemanticWriteQueries.replace` (`:20-24`) enchaîne `deleteDocuments`, `upsertMetadata` et `insertDocuments` (lots de 500, vecteurs passés en texte puis `CAST(? AS vector)`). Auparavant, `SemanticIndexService` (`:132-133`) recharge l'index précédent **complet**, vecteurs compris, pour décider de la réutilisation.

La synchronisation est déclenchée après chaque indexation réussie, **y compris quand rien n'a changé** : `LocalAutonomousIndexOperations.java:155` l'appelle en `NO_CHANGES` et `SemanticIndexService` reconstruit alors tous les documents.

Le coût croît donc avec la taille de l'index et non avec le nombre de documents modifiés : texte de vecteurs réécrit, journal de transactions équivalent, tuples morts à nettoyer, le tout sous le verrou de mutation du projet.

## What Changes

- **Sortie anticipée** : une synchronisation dont le snapshot, le provider, le modèle et les dimensions sont ceux de l'index déjà en place n'écrit rien et ne reconstruit aucun document.
- **Écriture différentielle sous PostgreSQL** : les documents retirés sont supprimés, les documents nouveaux ou modifiés sont écrits avec leur vecteur, les documents réutilisés reçoivent leurs nouvelles métadonnées de rattachement **sans** que leur vecteur soit renvoyé.
- **Décision de réutilisation sans vecteurs** : l'index précédent est lu par clé et empreinte seulement.
- **Équivalence prouvée** : le contenu final est identique à celui d'un remplacement complet.

## Capabilities

### New Capabilities

(aucune : `recherche-semantique` est créée par `lire-l-identite-du-snapshot-actif-sans-le-charger`)

### Modified Capabilities

- `recherche-semantique` : trois exigences ajoutées (une synchronisation sans changement n'écrit rien ; l'écriture est proportionnelle aux documents modifiés ; le résultat est équivalent à un remplacement complet, y compris en cas d'échec).

## Hors périmètre

- Le store fichier de l'index sémantique : il réécrit un fichier ; non concerné par ce constat.
- Envoyer les vecteurs en binaire (`float4[]` vers `vector`) : proposé par l'audit, **non vérifié** ; laissé en mesure optionnelle (tâche 3.3), pas en exigence.
- Sortir `snapshot_id` des lignes de documents vers les métadonnées : écarté, voir `design.md` (écart 3).
- Une migration de schéma : aucune n'est nécessaire (la clé primaire permet l'`ON CONFLICT`).
- La relecture du fichier source par symbole (AUD-PERF-02) et le coût d'une requête (AUD-PERF-01, prérequis).

## Impact

- **Modules du reactor** : `minos-storage-postgresql` (`semantic/PostgresSemanticWriteQueries`, `PostgresSemanticVectorStore`, `PostgresSemanticReadQueries`), `minos-application` (`application/semantic/SemanticIndexService`), `minos-cli` (`operations/LocalAutonomousIndexOperations`, appel en `NO_CHANGES`). Le port `replaceConditionally` du domaine garde sa signature.
- **Surfaces publiques** : aucune sortie ne change. Le compte rendu d'indexation peut dire qu'une synchronisation a été sautée (additif, à trancher en tâche 2.2).
- **ADR** : aucun.
- **Gates à rejouer** : scopes JaCoCo `m30-postgresql-pgvector` et `semantic-hybrid-retrieval`, `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-semantic-retrieval-consistency.py`, exigence « Une base PostgreSQL peuplée migre jusqu'à la version courante sans perte » de `persistance-snapshots` (aucune migration : à confirmer par le test de migration existant).
- **Plateformes** : PostgreSQL réel avec pgvector (CI Linux) ; la sortie anticipée se teste sur les deux systèmes.
