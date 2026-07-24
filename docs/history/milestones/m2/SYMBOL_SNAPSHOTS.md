# M2 — Snapshots persistants de symboles

Statut : **validé localement**

Date : **23 juillet 2026**

## Objectif

`FileSymbolSnapshotStore` rend les symboles normalisés rechargeables dans un
nouveau processus sans conserver de type SCIP ou Protobuf. Un snapshot est
immuable ; seul un petit pointeur indique la version active d'un projet.

## Publication transactionnelle

La publication suit cet ordre :

1. validation du projet, des identifiants et de l'unicité des symboles ;
2. tri déterministe des symboles et références fournisseur ;
3. écriture d'un fichier temporaire versionné ;
4. calcul SHA-256 et déplacement atomique du snapshot final ;
5. écriture puis déplacement atomique du pointeur actif.

Le nom du fichier final contient le hash de l'identifiant logique et le
checksum du contenu. Republier le même `snapshotId` ne peut donc pas écraser le
fichier encore référencé par l'ancien pointeur. Les anciens snapshots restent
présents ; aucune suppression implicite n'est réalisée.

À la lecture, MINOS vérifie le magic, la version, les bornes de taille, le
checksum, le projet, l'identifiant du snapshot et le nombre de symboles avant
d'exposer les données.

## Fidélité

Le format binaire M2 conserve :

- identité, qualité et clé logique ;
- projet, module, fichier et parent ;
- kind, noms, signature et langage ;
- emplacement et encodage des positions ;
- statut de résolution et provenance ;
- indicateurs externe/généré ;
- références fournisseur opaques, sans les exposer dans `SymbolResult`.

Les chaînes sont stockées comme unités UTF-16 afin de préserver aussi les
surrogates isolés issus de métadonnées malformées. Deux publications contenant
les mêmes symboles produisent les mêmes octets, indépendamment de l'ordre de la
collection d'entrée.

## Bootstrap local

`LocalProjectSymbolQuery` résout un projet par UUID ou par nom d'affichage
unique, recharge son snapshot actif puis applique les mêmes contrats
`findSymbols` et `getFileSymbols` que le backend mémoire.

`ScipSymbolSnapshotImporter` constitue le pont fournisseur : il lit l'artefact
SCIP dans `adapter.scip`, normalise les faits et publie uniquement le modèle
MINOS. Aucun type SCIP ne traverse le store, le registre, la requête ou la CLI.

## Périmètre de version

Le format `1` persiste les symboles nécessaires à M2. Les occurrences et
relations restent traitées par les contrats existants mais ne sont pas incluses
dans ce fichier : leur persistance sera ajoutée par une nouvelle version de
snapshot lors de M3, sans modifier le format M2 déjà publié.

Cette évolution est désormais livrée : le format v2 M3 persiste symboles,
occurrences et relations, tandis que le lecteur reste compatible avec v1. Voir
[`../m3/CODE_KNOWLEDGE_SNAPSHOTS.md`](../m3/CODE_KNOWLEDGE_SNAPSHOTS.md).

## Couverture

Les tests prouvent :

- réouverture dans une nouvelle instance et dans un nouveau processus Java ;
- promotion du pointeur actif et conservation des anciennes versions ;
- republication sûre d'un même identifiant logique ;
- octets déterministes ;
- détection d'une corruption par checksum ;
- refus des doublons et des symboles d'un autre projet ;
- surcharges, symboles externes et statut `UNRESOLVED` ;
- fidélité d'une référence contenant un surrogate isolé ;
- ingestion SCIP → snapshot → recherche qualifiée persistante.

Validation cumulée : `86` tests réussis, `0` échec, `0` erreur,
`BUILD SUCCESS`.
