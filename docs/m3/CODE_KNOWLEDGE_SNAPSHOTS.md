# M3 — Snapshots persistants de connaissance du code

Statut : **validé localement**
Date : **23 juillet 2026**

## Objectif

Le format de snapshot `2` persiste dans un même artefact immuable les symboles,
les occurrences et les relations normalisés. Un processus distinct peut ainsi
servir `find-usages`, les implémentations, les appels disponibles et les
dépendances sans relire l'index SCIP.

Le fichier ne contient aucun type SCIP ou Protobuf. Il ne dépend que du modèle
MINOS et reste isolé par UUID de projet.

## Compatibilité

`FileSymbolSnapshotStore` conserve volontairement son API M2 :

- l'ancien `publish(project, snapshot, symbols)` écrit toujours un snapshot v1
  strictement compatible ;
- `loadActive` sait lire un pointeur v1 ou v2 et retourne la vue symboles M2 ;
- `loadActiveKnowledge` lit les deux versions, un v1 donnant des listes
  d'occurrences et de relations vides ;
- le nouvel overload de publication écrit un fichier v2 `.knowledge`.

La promotion v1 → v2 ne modifie ni ne supprime l'ancien artefact. Le pointeur
actif est remplacé atomiquement après l'écriture et la validation du nouveau
snapshot.

## Fidélité du format v2

En plus des symboles M2, le format conserve :

- les références de symbole résolues ou non résolues des occurrences ;
- emplacement, rôles cumulés, résolution, origine et références fournisseur ;
- source, cible résolue ou cible textuelle d'une relation ;
- kind, emplacement, résolution et nature factuelle/dérivée/heuristique ;
- confiance, origine et liste complète de preuves structurées ;
- sources/cibles/emplacements/poids de chaque preuve.

Les collections et sous-collections sont triées avant écriture. À contenu
identique, l'ordre d'entrée des symboles, occurrences, relations, rôles,
références fournisseur ou preuves ne change pas les octets produits.

## Intégrité et activation

La publication vérifie les doublons d'identifiants et l'appartenance de chaque
fait au projet. La lecture vérifie le magic, la version, les tailles, les
compteurs, le projet, l'identifiant logique et le SHA-256 enregistré dans le
pointeur actif. Une corruption est refusée avant exposition des données.

Les fichiers historiques ne sont pas supprimés automatiquement. Cette
propriété garde la promotion récupérable et évite qu'un nouvel import invalide
le snapshot encore actif.

## Validation

Les tests couvrent notamment :

- round-trip v2 de références résolues/non résolues et relations
  factuelles/dérivées ;
- fidélité des preuves et de la provenance ;
- octets déterministes ;
- lecture d'un v1 par l'API complète puis promotion v2 ;
- compatibilité de l'ancienne API après activation d'un v2 ;
- checksum, doublons et isolation projet ;
- ingestion SCIP → snapshot v2 → requêtes persistantes ;
- lecture d'une relation depuis une nouvelle JVM.

Sur `typescript-simple`, le snapshot réel publié contient 24 symboles,
100 occurrences, 3 relations factuelles SCIP et 2 dépendances dérivées, soit
5 relations persistées.
