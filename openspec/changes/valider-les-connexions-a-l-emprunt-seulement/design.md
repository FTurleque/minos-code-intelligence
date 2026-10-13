# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-12**. HEAD analysé : `2d1cc214` (`develop`). `PG` = `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql`.

## État vérifié au HEAD

### AUD-PERF-12 — confirmé

- `PG/support/PostgresConnectionFactory.java:417` : `usable(connection)` à l'emprunt ; `:434` : `usable(connection)` à la restitution ; `:474-482` : `usable`, dont `connection.isValid(...)` en `:478`. Deux sites, comme annoncé (lignes décalées par le rangement du module en sous-packages : l'audit citait `:415-419`, `:434`, `:474-479`).
- `PostgresConnectionFactoryPoolTest` existe.
- Aucun ADR ne décrit le pool ; aucun `check-*.py` ne cite `isValid` ni `usable`.

**Non vérifié** : la réutilisation d'une connexion par des `withConnection` imbriqués (lue en commentaire seulement) ; le coût réel d'un `isValid` sur une base distante (aucune mesure).

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

### D2. Connexion rompue sous le seuil : un nouvel essai, puis l'échec — **décision en attente du propriétaire**

Recommandation (option A) : quand la **première** instruction d'un `withConnection` échoue par une erreur de connexion (classe SQLState `08`) sur une connexion sortie du pool sans validation, la connexion est écartée et l'opération est rejouée **une fois** sur une connexion neuve. Uniquement si rien n'a encore été écrit dans une transaction par cet appel ; sinon l'erreur remonte.

Option B : pas de nouvel essai ; l'erreur remonte, la connexion est écartée, l'appel suivant en obtient une neuve. Plus simple, mais une requête MCP échoue après chaque redémarrage de la base.

Dans les deux cas l'erreur publique reste opaque et sans chemin ni URL (exigences de `surfaces-publiques`).

### D3. Restitution : contrôle local, et retrait sur le moindre doute

À la restitution : `isClosed()`, retour à l'état d'auto-commit attendu, et un drapeau « l'appel s'est terminé par une exception ». Une connexion fermée, laissée hors auto-commit ou rendue après une exception est **fermée et retirée**, jamais remise au repos. Aucun aller-retour.

### D4. Preuves

`Connection` espion : emprunt puis restitution immédiats → 0 `isValid` ; emprunt après le seuil → 1 ; connexion rendue après exception → retirée ; connexion rompue sous le seuil → comportement D2. Les tests PostgreSQL réels existants restent verts.

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
