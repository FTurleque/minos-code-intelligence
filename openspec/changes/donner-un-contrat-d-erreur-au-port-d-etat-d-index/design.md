# Design

## Context

Sprint 9 de l'audit du 10 octobre 2026 : **AUD-QUA-03**. HEAD analysé : `949e1569` (`develop`). `PORT` = `minos-engine/src/main/java/com/minos/orchestration/IndexStateStore.java` ; `FILE` = `minos-storage-local/src/main/java/com/minos/storage/local/orchestration/FileIndexStateStore.java` ; `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/project`.

## État vérifié au HEAD

### AUD-QUA-03 — confirmé, périmètre plus large que le constat

- `PORT:18-26` : cinq méthodes sans `throws` ; `PORT:38` : `acquireProjectLease`, sixième méthode, absente de l'action de l'audit alors qu'elle lève les mêmes types ; `PORT:44-48` : `ProjectLease.close()` peut aussi échouer.
- **4 implémentations de production** : `minos-engine/src/main/java/com/minos/orchestration/InMemoryIndexStateStore.java:16`, `FILE:40`, `PG/PostgresIndexStateStore.java:20`, `PG/ProjectMutationIndexStateStore.java:24`. 6 fichiers de test déclarent un double du port.
- `FILE` : `UncheckedIOException` en `:106`, `:159` (bail), `:173`, `:222`, `:240`, `:269`, `:636`, `:644` ; `IllegalStateException` en `:401`, `:404`, `:559`, `:610`, `:615`, `:621`, `:673` (contenu invalide). **Chemin absolu dans le message** : `:173`, `:636`, `:644`, `:401`, `:404`, `:610`, `:615`, `:621`, `:673`.
- `PG/PostgresIndexStateStore.java:109-110` : `IllegalStateException("PostgreSQL index state store failed to " + action, cause)`. `PG/ProjectMutationIndexStateStore.java:312` : même forme ; `:82`, `:204` pour le bail.
- Requalifications : `minos-application/src/main/java/com/minos/application/ProjectIndexStateReconciler.java:154`, `:183`, `:191`, `:199`, `:207` (5 `catch (RuntimeException)` ; `:183` entoure le store de snapshots, pas le port) ; `minos-engine/src/main/java/com/minos/orchestration/AuthoritativeProjectStateReconciler.java:469` ; `minos-engine/src/main/java/com/minos/orchestration/IndexingRunExecutor.java:824` ; `minos-application/src/main/java/com/minos/application/LocalProjectOperations.java:168`.
- `IndexingRunExecutor.java:563-569` : la détection d'interruption parcourt la **chaîne des causes** (`InterruptedException`, `ClosedByInterruptException`).
- Le SPI `minos-engine/src/main/java/com/minos/storage/StorageBackend.java:19` rend le port.
- Décompte : 53 `catch` de `RuntimeException`, `UncheckedIOException` ou `IllegalStateException` en production avec une recherche mono-type ; le « 43 » de l'audit n'est pas reproduit.

**Non vérifié** : aucun build ni test exécuté ; le nombre exact d'appels du port en production (43 dans 11 fichiers d'après la contre-vérification) est à recompter en tâche 1.1 ; le contenu du changement du sprint 7 sur les chemins absolus, en cours de rédaction, n'a pas été lu.

### Écarts avec l'action proposée par l'audit

1. **Quatre implémentations, pas trois** : `ProjectMutationIndexStateStore` enveloppe le store PostgreSQL et porte le bail.
2. **Le bail est hors de l'action.** `acquireProjectLease` et `ProjectLease.close()` lèvent `UncheckedIOException` ou `IllegalStateException` selon le store ; `ProjectIndexStateReconciler.java:154` les requalifie de la même façon. Le contrat doit les couvrir.
3. **`throws IOException` sur le port casse en source** une quarantaine d'appels dans une dizaine de fichiers de production, le SPI `StorageBackend.indexStateStore()` par ricochet sur ses appelants, les lambdas (`IndexingRunExecutor.java:818-824` passe un `Runnable`) et le `try`-avec-ressources du bail. D'où D1.
4. **Toutes les `IllegalStateException` d'un store ne sont pas des pannes.** « lease must be released by its owner thread » (`FILE:128`) est une violation d'invariant et doit le rester ; « invalid execution count in … » (`FILE:401`) est un contenu illisible, donc une panne de stockage.
5. **Les messages du store fichier portent le chemin absolu** du fichier d'état : à corriger ici, puisque le type et le message de ces erreurs sont réécrits.
6. **La cause doit être conservée** : sans elle, une interruption pendant une écriture ne serait plus reconnue (`:563-569`) et le run serait marqué en échec au lieu d'interrompu.

## Goals / Non-Goals

**Goals** : un appelant sait ce qu'il attrape ; un défaut de programmation n'est plus requalifié ; le bail suit le même contrat ; les messages du port sont sans chemin absolu ; la reprise et l'interruption se comportent comme avant.

**Non-Goals** : revoir tous les `catch (RuntimeException)` ; typer les refus métier ; changer les messages publics d'une panne de stockage.

## Decisions

### D1. Exception dédiée non vérifiée ou exception vérifiée — **décidé : option A (propriétaire, 2026-10-21)**

Recommandation (option A) : une exception **non vérifiée dédiée**, déclarée à côté du port dans `com.minos.orchestration`, documentée dans la Javadoc de chaque méthode, levée par les quatre implémentations pour toute panne de stockage (E/S, SQL, contenu illisible, bail non obtenu ou non rendu). Les appelants attrapent ce type et lui seul. Raisons : aucune signature ne change (ni le SPI, ni les lambdas, ni le `try`-avec-ressources) ; le correctif est local à chaque `catch` ; le but du constat — distinguer une panne d'un bogue — est atteint.

Option B : `throws IOException` (ou un sous-type vérifié) sur les six méthodes. Le compilateur impose le traitement, ce qui est le plus sûr à long terme ; le coût est la réécriture de tous les appels, des doubles de test, des `Runnable` de persistance et de la fermeture du bail (un `close()` vérifié change chaque `try`-avec-ressources), dans des classes à seuil JaCoCo.

Écartée : garder `UncheckedIOException` partout ; elle exige une `IOException` en cause, que PostgreSQL n'a pas.

### D2. Ce qui est une panne, ce qui est un invariant

Panne de stockage (type du port) : échec d'E/S ou SQL, contenu d'état illisible ou incohérent, bail non obtenu dans le délai, bail non rendu. Violation d'invariant (`IllegalStateException`, inchangée) : libération par un autre thread, compteur de profondeur négatif, absence de bail qualifié (`PORT:40-41`). Le classement site par site est relevé en tâche 1.1.

### D3. Les appelants n'attrapent que le type du port

Les `catch (RuntimeException)` qui entourent un appel du port sont remplacés par le type du port ; les messages de l'`IOException` produite par `ProjectIndexStateReconciler` sont conservés. `IndexingRunExecutor.java:824` garde sa logique de nouvelle tentative sous interruption, restreinte au type du port, cause conservée (écart 6). Le `catch` de `:183`, qui entoure le store de snapshots, n'est pas dans le périmètre.

### D4. Messages sans chemin absolu

Les messages de `FileIndexStateStore` nomment la nature de l'état (projet, run), l'identifiant quand il est connu, et au plus le nom de fichier relatif à la racine gérée ; jamais le chemin absolu. La cause technique reste chaînée pour l'opérateur. Si le changement du sprint 7 introduit une primitive commune de message, elle est réutilisée.

### D5. Refus métier — **décidé : reporté (propriétaire, 2026-10-21)**

Recommandation : **reporter**. `resume-only indexing refused` est levé en `IllegalStateException` dans le moteur et dans la CLI ; lui donner un type touche la correspondance des codes de sortie de `minos index`. À traiter avec l'unification de l'orchestration (`unifier-l-orchestration-d-indexation`) ou par un changement propre. Alternative : l'inclure ici, avec les goldens et la référence CLI.

## Décisions du propriétaire et relevé d'implémentation (2026-10-21)

- **D1 = A** : `IndexStateStoreException` (non vérifiée, `com.minos.orchestration`, à côté du port), cause conservée. Elle n'étend pas `IllegalStateException`, pour qu'un `catch` d'invariant ne la confonde pas. **D5 reporté** : `resume-only indexing refused` reste une `IllegalStateException` (moteur et cas d'usage d'indexation).
- **Relevé 1.1 (relu au HEAD de la branche)** : les lignes du design ont dérivé. `FileIndexStateStore` : pannes `UncheckedIOException` aux sites bail (acquisition, libération), lecture d'état, `findRun`, `listRuns`, locator de run, `load`, `store` (8 sites) ; contenus illisibles en `IllegalStateException` (compteur invalide ou hors borne, locator invalide, nom de fichier inattendu ou non UUID, identité, propriété manquante : 7 sites) ; s'y ajoutent les `IllegalArgumentException` / `DateTimeException` de `UUID.fromString`, `valueOf`, `Instant.parse` sur un contenu corrompu, désormais enveloppées. **Écart** : les messages « cannot read/write MINOS … state » utilisaient déjà `getFileName()` ; les chemins absolus venaient des 6 `IllegalStateException` de contenu. Invariants conservés : propriétaire du bail (2), profondeur négative. `PostgresIndexStateStore` : `failure()` (5 opérations). `ProjectMutationIndexStateStore` : pannes = délai du bail, connexion dédiée, délai et interruption de la porte locale, interruption de l'attente, SQL d'acquisition et de libération, bail non possédé à la libération, mutation (9 sites) ; invariants = second bail d'un autre projet sur le thread, propriétaire, profondeur, sous-dépassement de la porte. `InMemoryIndexStateStore` : invariants seulement.
- **Appelants restreints** : `ProjectIndexStateReconciler` (bail, lecture d'état, liste des runs, écriture : 4 `catch`), `IndexingRunExecutor.persist`, `LocalProjectOperations.saveCommittedState`. **Hors périmètre, conservés** : `ProjectIndexStateReconciler` sur le store de snapshots (2 `catch`), `AuthoritativeProjectStateReconciler.observe` (entoure le promoteur, pas le port), les nettoyages internes de `ProjectMutationIndexStateStore` et la migration des runs hérités de `FileIndexStateStore` (internes à une implémentation).
- **Doubles de test** : aucun double n'a eu à changer pour compiler (type non vérifié) ; les tests qui affirmaient `UncheckedIOException` / `IllegalStateException` pour une panne sont repointés (stores fichier et PostgreSQL, journal de migration).
- **Primitive de message du sprint 7** (`ne-plus-laisser-fuir-de-chemin-absolu-dans-les-erreurs`) : changement absent de `openspec/changes/` au HEAD, aucune primitive réutilisée.
- **PostgreSQL** : les tests à base réelle (Testcontainers, `pgvector/pgvector:0.8.7-pg17`) ont tourné **localement** sous Windows avec Docker : 357 tests, 0 sauté.

## Direction des dépendances

`domain → engine → runtime/storage → adapters → application → surfaces`. L'exception vit avec le port dans `minos-engine` ; les adaptateurs de stockage la lèvent, `minos-application` et `minos-cli` l'attrapent. Aucune arête nouvelle ; `application ↛ adaptateurs` inchangé.

## Qualification des capacités

- Distinction entre panne de stockage d'état et défaut de programmation : **non qualifiée** au HEAD ; qualifiée par les tests de contrat de ce changement, pour les quatre implémentations.
- Refus métier typés : non supportés (D5).

## Windows et Linux

Le store fichier se teste sur les deux systèmes (les verrous de fichier et les erreurs d'accès diffèrent sous Windows). Les stores PostgreSQL se testent avec une base réelle, en CI sous Linux ; dire ce qui n'a été exécuté qu'en CI.

## Risks / Trade-offs

- Un `catch` restreint laisse remonter des `RuntimeException` jusque-là absorbées : c'est le but, mais un chemin de tolérance (statut, réconciliation) peut échouer là où il dégradait. Les tests existants de tolérance sont la preuve ; tout cas nouveau est nommé.
- Un site mal classé (panne prise pour invariant) ferait échouer une reprise au lieu de la réessayer : D2 et un test de contrat commun aux quatre implémentations.
- Ce changement et `unifier-l-orchestration-d-indexation` touchent les mêmes fichiers : l'implémenter de préférence après.
- SonarCloud : l'exception et les `catch` réécrits sont du code neuf à 80 % dans chaque module touché.
