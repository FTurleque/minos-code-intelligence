# Design

## Context

Lecture du 8 octobre 2026 (niveau A, non réexécutée) : `FileSymbolSnapshotStoreTest:132` (fichier publié corrompu refusé), `SnapshotRetentionOrphanTest:21-60` (résidu `.snapshot-*.tmp` balayé après 24 h, `.active-*.tmp` jamais supprimé, ligne 38), `DurableAtomicFileTest` (pannes injectées), `ActiveSnapshotRepository:113` (message de pointeur illisible jamais vérifié), `PostgresSchemaMigrator` (`CURRENT_VERSION = 4`, étapes `applyV1` à `applyV4`, verrou consultatif, transaction unique), `PostgresSchemaMigratorTest` (base neuve, idempotence, version future refusée, concurrence). La ligne de base exécute `ResumeAfterHardKillIntegrationTest` (moteur) et 78 tests PostgreSQL réels.

## Decisions

1. **Arrêt brutal réel par processus fils.** Une JVM fille (classe de test lancée par `ProcessBuilder` avec le classpath de test) publie un snapshot et se bloque juste après l'écriture temporaire (point d'arrêt par fichier signal) ; le test la tue (`destroyForcibly`), rouvre le magasin dans la JVM de test et vérifie que le snapshot actif est l'ancien, intact. Plus fidèle qu'une exception injectée, qui exécute les blocs `finally`.
2. **Pointeur tronqué** : fichiers `active.pointer` vide, à moitié écrit et à version inconnue ; l'ouverture échoue fermée avec un message qui nomme le fichier, sans retour silencieux à un autre snapshot.
3. **Migration peuplée** : la base est créée en v1 par les étapes `applyV1` seules (ou un script v1 figé dans les ressources de test), peuplée, puis migrée par l'ouverture normale ; les données sont relues par l'API du magasin.
4. **Pointeurs orphelins** : décision de l'utilisateur. Option proposée : balayer `.active-*.tmp` plus vieux que la borne des résidus (24 h) **sous le verrou de mutation du projet**, pour ne jamais toucher un pointeur en cours d'écriture ; inverser l'assertion de `SnapshotRetentionOrphanTest:38` dans la même tâche.

## Risks / Trade-offs

- Test multi-processus plus lent et sensible aux plateformes → délai borné, nettoyage garanti, exécution sous Windows **et** Linux (CI `windows-2022` et Ubuntu).
- Migration v1 : reconstituer le schéma v1 exactement → le figer depuis l'historique Git du migrateur.
- Windows : un fichier ouvert par le processus tué peut rester verrouillé brièvement → attente bornée de la libération avant réouverture.

## Direction des dépendances

Tests seulement dans les modules existants ; aucune nouvelle dépendance de production.
