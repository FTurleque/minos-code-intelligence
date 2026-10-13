# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-12**. HEAD analysé : `2d1cc214` (`develop`). `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql`.

## État vérifié au HEAD

### AUD-PERF-12 — confirmé

- `PG/support/PostgresConnectionFactory.java:417` : `usable(connection)` à l'emprunt ; `:434` : `usable(connection)` à la restitution ; `:474-482` : `usable`, dont `connection.isValid(...)` en `:478`. Deux sites, comme annoncé (lignes décalées par le rangement du module en sous-packages : l'audit citait `:415-419`, `:434`, `:474-479`).
- `PostgresConnectionFactoryPoolTest` existe.
- Aucun ADR ne décrit le pool ; aucun `check-*.py` ne cite `isValid` ni `usable`.

**Non vérifié** : le coût réel d'un `isValid` sur une base distante (aucune mesure).

### Relevé de la tâche 1.1 (2026-10-13, lecture de `PostgresConnectionFactory` et de `PostgresConnectionFactoryPoolTest` avant modification)

- **Connexions au repos** : file `ConcurrentLinkedQueue<Connection>` (FIFO), sans horodatage ; borne par un sémaphore équitable de `maxPoolSize` jetons ; compteurs atomiques (`physical`, `leased`).
- **Appels imbriqués** : vérifié dans le code. Un `ThreadLocal<LeaseContext>` porte le bail du fil ; un `withConnection` ou un `inTransaction` imbriqué réutilise la connexion du bail sans reprendre de jeton (`nestedWorkReusesTheLeasedConnectionAndReturnsItToThePool`). Trois sortes de bail posent ce contexte : l'appel de premier niveau, le bail à portée (`openScopedConnection`) et la session dédiée liée (`bindDedicatedConnection`, verrous consultatifs de cycle de vie), qui n'appartient pas au pool de requêtes.
- **Auto-commit** : `executeTransaction` le désactive puis le rétablit ; la restitution, elle, faisait `rollback()` puis `setAutoCommit(true)` sur une connexion rendue hors auto-commit, soit un aller-retour de plus, et la remettait au repos.
- **Exceptions** : seule une erreur de classe `08` marquait la connexion non réutilisable ; après toute autre exception (SQL, E/S, exécution) la connexion retournait au repos.
- **Course relevée à la restitution** : `if (closed) fermer else idle.offer(...)` ; un `close()` du pool survenant entre le test et l'`offer` laissait une connexion ouverte au repos pour toujours.
- **Ouverture** : `DriverManager.getConnection` appelé en dur, aucune horloge : ni l'un ni l'autre n'était substituable dans un test.
- **Tests** : `PostgresConnectionFactoryPoolTest` hérite de `PostgresTestSupport` (Testcontainers) : il ne tourne que si Docker est disponible. Les cas à connexion espion vont donc dans une classe distincte, sans serveur.

### Écarts avec l'action proposée par l'audit

1. **Le seuil d'inactivité suppose un horodatage par connexion inactive**, que le pool n'a pas forcément : la connexion au repos doit porter l'instant de sa restitution, lu sur une horloge monotone.
2. **Sans validation à l'emprunt, une connexion morte sous le seuil fait échouer la requête de l'appelant** au lieu d'être remplacée en silence (redémarrage de PostgreSQL, coupure réseau, délai d'inactivité côté serveur plus court que le seuil). L'audit n'en parle pas. Il faut choisir et écrire ce comportement (D2).
3. **`isClosed()` ne voit pas une connexion rompue côté serveur**, et « l'état de transaction » ne se lit pas par une API JDBC portable : la restitution contrôle `isClosed()` et l'état d'auto-commit ; toute restitution après une exception retire la connexion du pool (D3).

## Goals / Non-Goals

**Goals** : zéro aller-retour de validation pour un emprunt suivi d'une restitution immédiate ; aucune connexion douteuse remise en circulation ; échec explicite et borné sur connexion rompue.

**Non-Goals** : nouvelle dépendance ; changer la taille du pool ; reconnexion transparente illimitée.

## Decisions

### D1. Validation à l'emprunt après inactivité seulement

Une connexion au repos depuis moins que le seuil est remise sans `isValid`. Au-delà, elle est validée ; invalide, elle est fermée et remplacée. Seuil par défaut : 30 s, constante nommée (pas de nouveau réglage utilisateur). L'instant de restitution vient d'une horloge monotone injectable pour les tests.

### D2. Connexion rompue sous le seuil : un nouvel essai, puis l'échec — **décidé : option A (propriétaire, 2026-10-13)**

Option A (retenue) : quand la **première** instruction d'un `withConnection` échoue par une erreur de connexion (classe SQLState `08`) sur une connexion sortie du pool sans validation, la connexion est écartée et l'opération est rejouée **une fois** sur une connexion neuve. Uniquement si rien n'a encore été écrit dans une transaction par cet appel ; sinon l'erreur remonte.

Option B : pas de nouvel essai ; l'erreur remonte, la connexion est écartée, l'appel suivant en obtient une neuve. Plus simple, mais une requête MCP échoue après chaque redémarrage de la base.

Dans les deux cas l'erreur publique reste opaque et sans chemin ni URL (exigences de `surfaces-publiques`).

#### Mise en œuvre de l'option A

Le nouvel essai se déclenche si **toutes** ces conditions sont vraies :

1. l'appel est un `withConnection` ou un `inTransaction` **de premier niveau** (ni imbriqué, ni sous un bail à portée, ni sur une session dédiée liée : une session que l'appelant détient ne peut pas être remplacée sous lui) ;
2. la connexion sort du repos **sans validation** (ni ouverte pour cet appel, ni validée à l'emprunt) ;
3. l'appel se termine par une `SQLException` de classe `08` ;
4. l'appel n'a **terminé aucune instruction** et en a **commencé au plus une** ;
5. cette instruction n'est pas une écriture hors transaction : une instruction envoyée autrement que par `executeQuery` alors qu'aucune transaction du pool n'est ouverte a pu être appliquée sans que son accusé revienne ; elle n'est pas rejouée ;
6. aucun `commit` n'a été tenté (un accusé de `commit` perdu reste une `CommitUncertainException`, jamais rejouée) ;
7. l'appel n'a pas piloté la connexion lui-même (`setAutoCommit`, `commit`, `rollback`, `close`, `unwrap`, `getMetaData`…) : le pool ne sait plus en rendre compte.

Alors la connexion est fermée et retirée, une connexion **neuve** est ouverte sous le même jeton (pas de second emprunt : un pool d'une seule place ne se bloque pas) et le travail est rejoué **une fois**. Un second échec remonte tel quel, le premier attaché en `suppressed` ; il n'y a jamais de troisième essai. Si la connexion neuve ne s'ouvre pas, c'est l'erreur d'ouverture qui remonte ; le pool n'ajoute aucun texte.

Pour savoir quelle instruction échoue, l'appelant reçoit une **poignée** (`java.lang.reflect.Proxy` sur `Connection` et sur ses instructions, classe `PooledConnection`) qui transmet chaque appel au pilote et note ce que le bail a fait. Le pool pilote lui-même la transaction (`setAutoCommit`, `commit`, `rollback`) sur la connexion du pilote, pas par la poignée.

**Limites assumées, à connaître** :

- Le travail (la lambda) est exécuté deux fois jusqu'à sa première instruction : il ne doit rien modifier hors base avant celle-ci. Relevé au HEAD sur les 37 appels `withConnection` / `inTransaction` du module : aucun ne modifie d'état partagé avant sa première instruction (paramètres calculés hors de la lambda, collections créées dans la lambda).
- Une écriture émise par `executeQuery` (`INSERT … RETURNING`) hors transaction est traitée comme une lecture. Un seul site au HEAD : `PostgresProjectRegistry.registerProjectWithResult`, un `INSERT … ON CONFLICT … DO UPDATE` idempotent dont l'identifiant candidat est calculé hors de la lambda.
- Dans une transaction, la première instruction peut être une écriture : rien n'est validé avant le `commit`, et la session perdue est annulée par le serveur.

**Point resserré par rapport au texte de la décision** (à confirmer par le propriétaire) : la condition 5. Le texte de l'option A rejoue toute première instruction ; la mise en œuvre exclut la première instruction quand c'est une écriture en auto-commit, pour tenir « aucune écriture déjà partie n'est rejouée ». Dans ce cas l'erreur remonte comme dans l'option B.

#### Courses examinées

- **Emprunt concurrent** : `poll()` de la file est atomique, une connexion au repos n'est remise qu'à un seul fil ; l'instant de restitution et le drapeau « sortie sans validation » sont écrits par le fil qui détient la connexion, avant sa publication dans la file ou après son retrait, et déclarés `volatile` (deux motifs SpotBugs `AT_*` sinon). Testé (8 fils, pool de 3, 50 répétitions).
- **Restitution pendant la fermeture du pool** : la restitution fait `offer` **puis** relit `closed` ; `close()` lève `closed` **puis** vide la file. Soit `close()` vide la connexion, soit la restitution la reprend (`remove`) et la ferme : elle ne peut pas rester au repos. La course du HEAD (test avant `offer`) est fermée. Testé de façon déterministe (fermeture déclenchée depuis la restitution) et sous charge (50 répétitions).
- **Épuisement** : le nouvel essai garde le jeton du premier essai ; il ne peut ni dépasser `maxPoolSize` ni s'interbloquer. Testé sur un pool d'une place, y compris quand la connexion neuve ne s'ouvre pas (le jeton est rendu).
- **Fermeture du pool pendant un nouvel essai** : refusé (« pool is closed »), aucune connexion ouverte après la fermeture.
- **Connexion empruntée juste avant la fermeture** : inchangé, elle sert son appel puis est fermée à la restitution.

### D3. Restitution : contrôle local, et retrait sur le moindre doute

À la restitution : `isClosed()`, retour à l'état d'auto-commit attendu, et un drapeau « l'appel s'est terminé par une exception ». Une connexion fermée, laissée hors auto-commit ou rendue après une exception est **fermée et retirée**, jamais remise au repos. Aucun aller-retour.

### D4. Preuves

`Connection` espion : emprunt puis restitution immédiats → 0 `isValid` ; emprunt après le seuil → 1 ; connexion rendue après exception → retirée ; connexion rompue sous le seuil → comportement D2. Les tests PostgreSQL réels existants restent verts.

Les cas à espion vivent dans `PostgresConnectionFactoryValidationTest` (aucun serveur, horloge injectée), pas dans `PostgresConnectionFactoryPoolTest` qui exige Docker : la couverture du code neuf ne doit pas dépendre de la plateforme. Deux attentes de `PostgresConnectionFactoryPoolTest` changent avec D3 (une erreur SQL ordinaire ne conserve plus la connexion).

## Direction des dépendances

Interne à `minos-storage-postgresql`. Aucune dépendance ajoutée.

## Qualification des capacités

Aucune capacité nouvelle. Le pool reste qualifié par ses tests ; le comportement sur connexion rompue devient explicite.

## Windows et Linux

Indépendant de la plateforme. L'horloge monotone (`System.nanoTime`) évite toute sensibilité aux changements d'heure.

## Risks / Trade-offs

- Une connexion morte n'est plus détectée avant usage sous le seuil : couvert par D2.
- Un nouvel essai automatique (D2-A) ne doit jamais rejouer une écriture : limité à la première instruction d'un appel sans écriture préalable, et testé.
- Gain proportionnel à la latence réseau : nul en local, à dire dans le suivi plutôt que de promettre un chiffre.
