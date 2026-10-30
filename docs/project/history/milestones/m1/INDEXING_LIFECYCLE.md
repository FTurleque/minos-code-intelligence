# M1.4 — Cycle de vie de l'indexation et état observable

Statut : **implémenté — validation locale requise**

Date : **23 juillet 2026**

Suivi : issue #6.

## Objectif

M1.4 ferme le périmètre « découverte et orchestration » en transformant un plan
d'indexeurs déjà négocié en un **run projet observable** sans faire fuiter les
API natives des fournisseurs dans le cœur MINOS.

La règle structurante reste l'ADR-0006 : un nouvel index n'est visible qu'après
succès de toutes les étapes nécessaires à un snapshot projet sain.

## Unité d'atomicité

L'unité d'atomicité est le **projet**, pas l'indexeur.

Pour un projet multi-langages :

```text
IndexerNegotiationResult complet
  -> exécuter toutes les sélections
  -> vérifier tous les artefacts finaux
  -> stage/ingestion du snapshot projet commun
  -> promotion atomique unique
```

Un échec Java ou TypeScript bloque donc la promotion de l'ensemble du run.
MINOS ne peut pas publier silencieusement un mélange de données provenant de
rafraîchissements différents.

## Ports runtime

`IndexingRuntimePorts` définit trois responsabilités indépendantes du
fournisseur :

- `IndexerExecutor` : exécuter un indexeur sélectionné et retourner son artefact final ;
- `SnapshotStager` : ingérer/stager l'ensemble des artefacts du run projet ;
- `SnapshotPromoter` : promouvoir atomiquement le snapshot stagé.

Le port de promotion porte explicitement le contrat d'atomicité de l'ADR-0006.
M1.4 n'introduit aucun type SCIP, Protobuf ou Glean dans `orchestration`.

## État projet

`ProjectIndexState.Availability` :

```text
NEVER_INDEXED
INDEXING
REFRESHING
READY
STALE
FAILED
```

Sémantique :

- `NEVER_INDEXED` : aucun snapshot actif connu ;
- `INDEXING` : première indexation en cours ;
- `REFRESHING` : nouveau run en cours mais un ancien snapshot reste actif ;
- `READY` : dernier run promu, snapshot actif courant ;
- `STALE` : dernier rafraîchissement en échec, ancien snapshot actif conservé ;
- `FAILED` : indexation en échec et aucun snapshot actif disponible.

`STALE` ne transforme donc pas un échec en succès. Il expose deux faits en même
temps : le dernier run a échoué et un snapshot antérieur reste exploitable.

## État d'un run

`IndexingRun` conserve :

- identifiant de run ;
- projet ;
- statut `RUNNING`, `SUCCEEDED` ou `FAILED` ;
- phase courante ;
- artefacts fournisseur terminés ;
- snapshot stagé éventuel ;
- snapshot actif avant/après ;
- message de diagnostic ;
- timestamps.

Phases :

```text
PROVIDER_EXECUTION
STAGING
PROMOTION
COMPLETED
```

Un run échoué conserve la phase de l'échec. Les diagnostics restent donc
exploitables sans promouvoir des données partielles.

## Stockage d'état

`IndexStateStore` est un port MINOS. M1 fournit `InMemoryIndexStateStore` comme
baseline légère.

Le choix d'une persistance durable des runs/snapshots ne doit pas être forcé
avant le choix du backend produit. Le registre projet/workspace M1.2 reste
persistant ; l'état d'index M1.4 est volontairement derrière un port remplaçable.

## Politique de concurrence M1

Une seule indexation peut être active à la fois pour un même projet dans une
instance `IndexingLifecycleService`.

Des projets différents ne partagent pas le même verrou logique. Le verrouillage
multi-processus et l'orchestration distribuée restent hors périmètre M1.

## Échecs

Les échecs d'exécution fournisseur, d'artefact absent/non lisible, de staging ou
de promotion produisent un `IndexingRun.FAILED`.

```text
aucun snapshot précédent -> ProjectIndexState.FAILED
snapshot précédent présent -> ProjectIndexState.STALE + ancien snapshot conservé
```

Le service ne met à jour l'identifiant du snapshot actif qu'après retour réussi
du `SnapshotPromoter`.

## Annulation, timeout et retry

M1.4 **ne prétend pas** fournir une annulation forcée d'un processus externe, un
timeout générique ou une politique de retry.

Ces fonctions nécessitent un contrat d'exécution asynchrone/processus plus
précis et ne sont pas requises pour la porte M1. Les ajouter maintenant
introduirait une garantie que les adaptateurs runtime réels n'ont pas encore
qualifiée.

Elles pourront être ajoutées ultérieurement sans modifier les états fondamentaux
ni l'invariant de promotion atomique.

## Tests de porte

`IndexingLifecycleServiceTest` vérifie notamment :

1. succès multi-indexeurs : les deux artefacts sont produits, un seul staging et une seule promotion ;
2. échec d'un indexeur : aucun staging et aucune promotion ;
3. échec de promotion pendant un refresh : ancien snapshot conservé et état `STALE` ;
4. négociation incomplète : aucun run n'est démarré.

Ces tests utilisent des ports factices et ne prétendent pas réexécuter les
binaires SCIP qualifiés en M0. Ils valident la logique d'orchestration propre à
MINOS.

## Validation finale M1 attendue

Depuis le head courant M1.4 :

```powershell
.\mvnw.cmd clean verify
```

La PR M1.4 reste Draft avant un résultat vert sur son SHA exact.

Après validation et fusion, M1 pourra être clôturé et M2 — Intelligence des
symboles — pourra démarrer depuis `main`.
