---
id: ADR-0023
title: "Décomposer la persistance locale des snapshots sans changer le format disque"
domain: data
rules: false
---

# ADR-0023 — Décomposer la persistance locale des snapshots sans changer le format disque

Date : 26 juillet 2026

Statut : **Accepted — implémenté par M15-S6 sous qualification exact-head**

Origine : M15-S6

Relations : complété par [ADR 0046](0046-format-de-snapshot-v3-chaines-utf8.md) (format de snapshot V3).

## Context

Après M15-S5, `FileSymbolSnapshotStore` concentre encore plusieurs responsabilités indépendantes :

- ordonnancement et validation des faits avant publication ;
- encodage/décodage binaire des formats historiques v1 et v2 ;
- création et publication atomique des fichiers snapshot ;
- encodage, lecture et remplacement du pointeur `active.pointer` ;
- calcul et vérification SHA-256 ;
- validation de cohérence entre pointeur et contenu ;
- mécanisme potentiel de rétention des snapshots historiques.

Cette concentration rend difficile l'évolution du format, le test isolé d'un codec et la future introduction du cache S7 sans augmenter le risque sur l'atomicité historique.

En revanche, M15 ne dispose d'aucune mesure justifiant un nouveau backend de persistance. Les formats actuellement publiés sont qualifiés par les replays M14 et doivent rester lisibles.

## Decision

Conserver `FileSymbolSnapshotStore` comme **façade de compatibilité locale**, mais déplacer les responsabilités vers des composants explicites dans `minos-storage-local` :

```text
FileSymbolSnapshotStore
        |
        +-- SnapshotRepository
        +-- ActiveSnapshotRepository
        +-- SnapshotIntegrityService
        +-- SnapshotRetentionService
        +-- SnapshotCodec
              +-- SnapshotCodecV1
              +-- SnapshotCodecV2
```

`SnapshotDescriptor` porte les métadonnées persistées par le pointeur actif.

Le format binaire commun aux codecs reste implémenté dans un support interne de stockage et ne fuit pas vers le domaine, le moteur, l'API ou les transports.

### Compatibilité disque

S6 ne modifie volontairement aucun des éléments suivants :

- magic du snapshot `0x4D4E5359` ;
- versions v1 et v2 ;
- fichier `active.pointer` et son magic `0x4D4E4150` ;
- extensions `.symbols` et `.knowledge` ;
- algorithme historique de hash du `snapshotId` utilisé dans les noms de fichiers ;
- checksum SHA-256 du contenu publié ;
- ordre déterministe des symboles, occurrences, relations, provider references et evidence ;
- publication du fichier snapshot **avant** promotion du pointeur actif ;
- limites défensives de lecture ;
- erreurs explicites de checksum, version, projet, snapshotId et cardinalité incohérents.

Un snapshot v1 chargé comme connaissance complète continue d'être adapté avec des collections occurrences/relations vides.

## Do's and Don'ts

### Do

- Conserver `FileSymbolSnapshotStore` comme façade de compatibilité locale et répartir ses responsabilités dans des composants explicites de `minos-storage-local`.
- Publier le fichier snapshot **avant** de promouvoir le pointeur actif.
- Écrire le pointeur actif dans un fichier temporaire distinct puis le remplacer après publication du snapshot.
- Conserver une erreur explicite pour toute version de codec non supportée.

### Don't

- Ne pas laisser fuir le format binaire vers le domaine, le moteur, l’API ou les transports.
- Ne pas supprimer le fichier snapshot actif par le service de rétention.
- Ne pas adopter de politique automatique de rétention sans les mesures de M16.
- Ne jamais promouvoir un fichier snapshot partiellement écrit.

## Atomicité

Le repository publie le fichier snapshot par déplacement atomique lorsque le système de fichiers le permet, avec fallback `REPLACE_EXISTING` historique lorsque `ATOMIC_MOVE` n'est pas supporté.

Le pointeur actif est écrit dans un fichier temporaire distinct puis remplacé après publication du snapshot. Une interruption ne doit donc jamais promouvoir un fichier snapshot partiellement écrit.

## Rétention

S6 sépare le **mécanisme** de rétention de la politique.

`SnapshotRetentionService` peut lister les snapshots et supprimer uniquement des fichiers explicitement désignés comme historiques. Il refuse de supprimer le fichier actif fourni par l'appelant.

Aucune politique automatique de nombre maximal, âge, compaction ou quota n'est adoptée en S6. Ces seuils restent gouvernés par les mesures de M16.

## Consequences

### Positives

- les codecs v1/v2 deviennent testables indépendamment du repository ;
- l'intégrité et le pointeur actif ont des tests ciblés ;
- le store public local devient une façade de coordination plus petite ;
- S7 pourra raisonner sur l'identité `(projectId, snapshotId)` sans réimplémenter la lecture du pointeur ;
- une future version de codec peut être ajoutée sans mélanger format et publication ;
- aucun backend externe n'est introduit sans benchmark.

### Contraintes

- `minos-storage-local` reste propriétaire du format binaire local ;
- toute évolution de codec doit conserver une erreur explicite pour les versions non supportées ;
- la compatibilité v1/v2 et l'atomicité restent des gates obligatoires ;
- la rétention automatique reste hors périmètre tant que M16 n'a pas fourni de mesures.

## Compliance and Enforcement

- Tests `SnapshotCodecTest`, `SnapshotFormatCompatibilityTest` et `SnapshotPublicationCrashTest` (`minos-storage-local`, lancés par `mvnw verify`) — codecs testés sans repository, compatibilité des formats, publication interrompue à tout point qui laisse un snapshot actif valide.
- Revue : une évolution de codec garde la lecture des formats publiés et une erreur explicite pour les versions non supportées ; l’ordre « snapshot puis pointeur » est conservé.

## Validation

La fermeture de M15-S6 doit être rattachée à un SHA exact et démontrer au minimum :

- tests directs des codecs sans repository ;
- lecture/promotion du pointeur v1/v2 ;
- checksum et métadonnées incohérentes diagnostiqués ;
- version de pointeur non supportée diagnostiquée explicitement ;
- replays M14 et packaging Windows inchangés fonctionnellement ;
- `FileSymbolSnapshotStore` ne contient plus l'encodage binaire ni le format du pointeur.

Les nombres de tests, mesures et SHA de qualification restent enregistrés dans la PR/issue de M15 plutôt que dans cet ADR.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR) ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Un codec V3 existe en plus des versions v1 et v2 énumérées par la décision ; il relève de l’ADR 0046 — preuve : `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotCodecV3.java`.
