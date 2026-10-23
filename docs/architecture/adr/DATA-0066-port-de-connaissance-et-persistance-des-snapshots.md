---
id: DATA-0066
title: "Port de connaissance et persistance des snapshots : format V3, compatibilité, dédoublonnage"
domain: data
rules: false
---

# Port de connaissance et persistance des snapshots : format V3, compatibilité, dédoublonnage

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0003](archive/0003-glean-behind-code-knowledge-store.md), [ADR-0023](archive/0023-decomposed-local-snapshot-persistence.md), [ADR-0046](archive/0046-format-de-snapshot-v3-chaines-utf8.md) ; reprend les règles durables de l'[ADR-0047](archive/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) (archivé sans successeur direct)
- **Remplacé par** : —

## Context

MINOS est local-first et vise Windows, Linux et macOS ; aucun backend spécialisé ne peut être imposé sans validation opérationnelle (ADR 0003). La connaissance normalisée se stocke et s'interroge derrière une abstraction définie par les cas d'usage MINOS (ADR 0003 § 1).

La persistance locale publie des snapshots binaires versionnés et un pointeur actif ; le snapshot persisté est la source de vérité, la vue de requête et ses index sont reconstruits en mémoire ([ADR 0024](DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md)). Les formats publiés doivent rester lisibles, et leur publication ne doit jamais promouvoir un fichier partiellement écrit (ADR 0023).

Les chaînes font 97 % d'un snapshot. Le format V2 les écrit en UTF-16 : sur ce dépôt, un SCIP de 23,8 Mo donnait un snapshot V2 de 355 Mo, au-dessus du plafond de persistance de 256 Mio ; V3 (UTF-8) divise le fichier par environ 1,8 sans changer le tas d'un snapshot chargé (ADR 0046). À la lecture, le décodage recréait une instance par champ chaîne, d'où un tas 2,5 fois supérieur à celui de l'ingestion pour le même contenu (ADR 0047). Mesures et journal : [`ARCHI-SUIVI.md`](../../project/audits/archive/2026-09/ARCHI-SUIVI.md) (lot 3, A6).

## Decision

### Port de connaissance (ADR 0003 § 1)

1. `CodeKnowledgeStore` est une frontière architecturale de MINOS, définie à partir des cas d'usage MINOS et non à partir de l'API d'un backend. Aucun type de backend ne la traverse : ni type Glean, ni prédicat Angle, ni identifiant de fait, ni type Thrift (ADR 0003 § 1).
2. Le chemin par défaut est un backend MINOS léger ; un `InMemoryCodeKnowledgeStore` sert aux tests. Le convertisseur SQLite expérimental de la CLI SCIP peut servir à l'inspection ou à la comparaison, pas de dépendance de production sans étude supplémentaire (ADR 0003).

### Persistance locale des snapshots (ADR 0023, format mis à jour par ADR 0046)

3. `FileSymbolSnapshotStore` reste la façade de compatibilité locale ; ses responsabilités vivent dans des composants explicites de `minos-storage-local` : `SnapshotRepository`, `ActiveSnapshotRepository`, `SnapshotIntegrityService`, `SnapshotRetentionService` et `SnapshotCodec` (codecs V1, V2, V3) ; `SnapshotDescriptor` porte les métadonnées persistées par le pointeur actif. `minos-storage-local` reste propriétaire du format binaire local, qui ne fuit pas vers le domaine, le moteur, l'API ou les transports (ADR 0023).
4. **Compatibilité disque.** Sont invariants : le magic du snapshot `0x4D4E5359` ; le fichier `active.pointer` et son magic `0x4D4E4150` ; les extensions `.symbols` et `.knowledge` ; `logicalIdHash` et le schéma des noms `snapshot-<logicalIdHash>-<sha256 du contenu><extension>` ; le checksum SHA-256 des octets publiés, porté par le pointeur ou par la ligne PostgreSQL ; l'ordre déterministe des symboles, occurrences, relations, références de provider et preuves ; les limites défensives de lecture ; les erreurs explicites de checksum, version, projet, `snapshotId` et cardinalité incohérents. Un snapshot V1 chargé comme connaissance complète est adapté avec des occurrences et relations vides (ADR 0023 ; ADR 0046 § 3).
5. **Atomicité.** Le fichier snapshot est publié **avant** la promotion du pointeur actif, par déplacement atomique quand le système de fichiers le permet, avec repli historique `REPLACE_EXISTING` sinon ; le pointeur est écrit dans un fichier temporaire distinct puis remplacé ; un fichier snapshot partiellement écrit n'est jamais promu (ADR 0023, « Atomicité »).
6. **Format V3.** La mise en page de V2 est conservée ; seules changent la version (3) et la forme des chaînes : longueur en octets sur 32 bits (`-1` pour `null`) puis octets UTF-8. L'écrivain refuse une chaîne à surrogate isolé au lieu de l'altérer. Le décodeur est strict (`CodingErrorAction.REPORT`, toute séquence mal formée est une erreur) ; chaque longueur est bornée en octets avant lecture (3 × 8 Mi par chaîne), les octets sont lus par `readNBytes` sans pré-allocation de la longueur déclarée ; les plafonds par nombre d'entités sont ceux de V2 (ADR 0046 § 1).
7. **Politique d'écriture : V3, sinon V2, jamais de refus ni de substitution.** `KnowledgeSnapshotCodecs.select` est la règle commune au magasin fichier et à PostgreSQL : un nouveau snapshot de connaissance s'écrit en V3, ou entièrement en V2 si une de ses chaînes contient un surrogate isolé ; la publication historique « symboles seuls », sans appelant de production, écrit en V1. Le format et la taille encodée exacte sont établis avant toute écriture ; un snapshot au-dessus du plafond de 256 Mio est refusé sans rien écrire (ADR 0046 § 2). `CodeKnowledgeSnapshotBinaryCodec`, pont public vers le format V2 canonique, reste en V2 (ADR 0046, « Hors périmètre »).
8. **Lecture : tout format jamais écrit reste lisible.** Magasin fichier : le pointeur actif désigne le format du snapshot (1, 2 ou 3) ; un pointeur V1 ou V2 garde exactement ses octets, un pointeur 3 désigne un snapshot V3. PostgreSQL : le payload se décrit par son en-tête, sans nouvelle colonne, et le descripteur de la vue porte la version réelle. Ré-import sous le même identifiant : même contenu en d'autres octets n'est pas un conflit ; PostgreSQL décode les deux payloads, conserve un payload existant identique, et un contenu différent reste un conflit d'identité. Un snapshot V2 existant n'est jamais déclaré corrompu (ADR 0046 § 3). Toute évolution de codec garde une erreur explicite pour les versions non supportées (ADR 0023).
9. **Décodage.** Les chaînes sont dédoublonnées au décodage par une table locale à chaque passe, jetée avec elle (une instance par chaîne distincte, formats V2 et V3), sans changer aucun format persisté : le modèle décodé reste égal (ADR 0047, option A). La lecture est authentifiée en un parcours : rien de décodé n'est publié si l'empreinte diffère (ADR 0047, « Mise en œuvre »).
10. **Évolution du format.** Une table de chaînes sur disque (option B, format V4) n'est envisagée que si la taille sur disque ou le temps de lecture restent un problème après le dédoublonnage, pour des tailles de projet cibles à fixer ; un snapshot paginé ou mappé en mémoire (option C) seulement si le tas d'un projet cible dépasse encore le budget mémoire accepté, précédé d'un inventaire des consommateurs de `CodeKnowledgeSnapshot`. Le passage d'une étape à la suivante se fonde sur une mesure du corpus réel par le banc `benchmarks/scalability/`, jamais sur une estimation ; B et C créent un format persisté et suivent les points 4 à 8 (lecture des formats antérieurs, pointeur, PostgreSQL, ré-import, fixtures de la base, preuve sur les golden qui listent des noms de fichiers) (ADR 0047, décision et « Ordre confirmé »).
11. **Rétention : mécanisme séparé de la politique.** `SnapshotRetentionService` liste les snapshots et ne supprime que des fichiers explicitement désignés comme historiques ; il refuse de supprimer le fichier actif fourni par l'appelant. Aucune politique automatique de nombre maximal, d'âge, de compaction ou de quota n'est adoptée par cette décision ; ces seuils restent gouvernés par les mesures de M16 (ADR 0023, « Rétention » ; voir « Écarts constatés »).

### Hors de la décision

Glean comme backend avancé optionnel (ADR 0003 § 2 et 3, critères d'adoption, scénarios de décision, validation M0 du 22 juillet 2026 qui retient « ADOPTER_AVEC_CONTRAINTES ») est sans suite : aucune documentation courante, roadmap, spécification ni changement actif ne le porte. Cette option ne fait pas partie de la décision ; sa trace reste dans l'[ADR 0003](archive/0003-glean-behind-code-knowledge-store.md), [`RAPPORT_GLEAN_C1.md`](../../project/history/milestones/m0/RAPPORT_GLEAN_C1.md) et [`COMPARATIF_BACKENDS.md`](../../project/history/milestones/m0/COMPARATIF_BACKENDS.md). Seule la frontière du point 1 en est retenue.

## Do's and Don'ts

### Do

- Définir `CodeKnowledgeStore` par les cas d'usage MINOS et normaliser derrière lui tout backend.
- Publier le fichier snapshot avant de promouvoir le pointeur actif, et écrire le pointeur dans un fichier temporaire distinct.
- Écrire un nouveau snapshot de connaissance en V3, ou entièrement en V2 si une de ses chaînes contient un surrogate isolé, après avoir établi format et taille exacte.
- Décoder strictement et borner chaque longueur avant toute lecture.
- Garder lisible tout format jamais écrit, dans le magasin fichier comme dans PostgreSQL, et une erreur explicite pour toute version non supportée.
- Dédoublonner les chaînes au décodage sans changer le modèle décodé.
- Fonder tout passage à un format V4 ou à un snapshot mappé sur une mesure du banc `benchmarks/scalability/`.

### Don't

- Ne laisser traverser `CodeKnowledgeStore` aucun type d'un backend particulier.
- Ne pas laisser fuir le format binaire vers le domaine, le moteur, l'API ou les transports.
- Ne jamais promouvoir un fichier snapshot partiellement écrit.
- Ne pas altérer silencieusement une chaîne à surrogate isolé, ni pré-allouer une longueur déclarée.
- Ne jamais refuser ni substituer à l'écriture une chaîne : V3, sinon V2.
- Ne pas déclarer corrompu un snapshot V2 existant.
- Ne publier aucune donnée décodée dont l'empreinte diffère.
- Ne pas supprimer le fichier snapshot actif par le service de rétention.
- Ne pas décider un format V4 ou un snapshot mappé sur une estimation.

## Consequences

### Positives

- Un backend nouveau se branche derrière le port sans toucher au domaine (ADR 0003).
- Codecs, intégrité et pointeur actif se testent séparément ; une nouvelle version de codec s'ajoute sans mélanger format et publication (ADR 0023).
- Un fichier V3 fait 0,548 à 0,550 fois le V2 de même contenu ; ce dépôt redevient indexable sous le plafond de 256 Mio ; la lecture à froid est environ 2,5 fois plus rapide (ADR 0046, mesures archivées).
- Le dédoublonnage ramène le tas retenu par une vue relue à 1,14 fois le fichier V3 sur le code de production de ce dépôt, ce qui le rend admissible au cache de vues (ADR 0047, mesure du 2026-10-15).

### Négatives

- Un snapshot publié en V3 a d'autres octets, donc un autre sha256 en fin de nom ; les snapshots existants gardent leur nom (ADR 0046).
- Un binaire antérieur à V3 refuse proprement la lecture et la compaction d'un projet dont le pointeur désigne un V3, sans modifier aucun fichier (ADR 0046, retour arrière mesuré V-A6-06).
- Dans le magasin fichier, ré-importer un même `snapshotId` après le passage à V3 laisse le V2 de même préfixe comme historique jusqu'à la prochaine compaction ; PostgreSQL conserve le payload V2 existant (ADR 0046, V-A6-07).

### Risques

- Le reactor complet, tests compris, produit un snapshot de 300 Mo encodés, au-delà du plafond de 256 Mio : donnée d'entrée du déclencheur de l'option B (ADR 0047, « Mise en œuvre »).
- Le temps de décodage et le pic de tas pendant le décodage (la table retient une entrée par chaîne distincte jusqu'à la fin de la passe) ne sont pas mesurés (ADR 0047, « Mise en œuvre »).

## Compliance and Enforcement

- Test `ProviderBoundaryTest` (`minos-application`) — aucun import `glean.`, `com.facebook.glean`, `org.scip_code.scip` ou `com.google.protobuf` dans les packages du cœur présents dans `minos-application`.
- Règle A2 de `scripts/architecture/check-module-boundaries.py` (job `invariants`) — `minos-storage-local` et `minos-storage-postgresql` sont des adaptateurs : l'application ne connaît que les ports, aucun adaptateur ne remonte vers elle ; test `ModuleArchitectureTest` (`applicationOnlyKnowsPorts`, `adaptersDoNotReachBackIntoUpperLayers`, `minos-app`).
- Tests de `minos-storage-local`, lancés par `mvnw verify` : `SnapshotCodecTest` (codecs sans repository), `SnapshotFormatCompatibilityTest` (lecture des formats publiés), `SnapshotPublicationCrashTest` (publication interrompue à tout point : le snapshot actif reste valide), `SnapshotCodecV3Test` (aller-retour exact, longueurs bornées, refus croisé des versions), `PersistedSnapshotLimitTest` (refus au-dessus du plafond sans rien écrire, y compris sur la taille V2), `SnapshotPersistenceComponentsTest` (`retentionDeletesOnlyExplicitHistoricalFiles`, `boundedRetentionKeepsActiveAndNewestHistoricalFiles`), `SnapshotDecodeStringSharingTest` (chaînes partagées, table locale à une passe), `SnapshotVerifiedReadTest` et `FileSymbolSnapshotStoreIntegrityTest` (lecture authentifiée, contenu altéré refusé), `QueryViewWeightTest` (poids estimé d'une vue au moins égal au tas retenu).
- Tests `PostgresSnapshotFormatCompatibilityTest` (`minos-storage-postgresql` : payload V2 lisible, ré-import conservé, surrogate isolé republié en V2) et `RetentionFormatEquivalenceTest` (`minos-app` : même modèle décodé en V2 et en V3).
- Aucun gate `scripts/**/check-*.py` dédié. Aucune règle Archgate compagnon (`rules: false`) : la compatibilité d'un format et l'ordre de publication sont des comportements tenus par des tests ; seule la non-fuite du format hors des modules de stockage se lirait dans les sources, règle non écrite.
- Revue : une évolution de codec garde la lecture des formats publiés, l'ordre « snapshot puis pointeur » et une erreur explicite pour les versions non supportées ; tout passage à l'option B ou C cite une mesure du banc `benchmarks/scalability/`.

## Écarts constatés

Relevés le 2026-10-23 (audit des ADR), preuves relues au HEAD `2f773e57` ; la décision ci-dessus reste celle en vigueur tant qu'un amendement ou une correction du code n'a pas tranché.

- Point 11 : une rétention automatique est appliquée par défaut — au plus 2 snapshots historiques plus l'actif par projet, compaction à chaque chemin terminal d'indexation — alors que l'ADR 0023 et l'[ADR 0024](DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md) (« Limites ») disent qu'aucune politique automatique de rétention n'est adoptée. La roadmap M16 donne cette rétention comme livrée par M16-S9 dans le profil par défaut. Le propriétaire n'a pas tranché — preuves : `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotRetentionPolicy.java:10`, `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotRetentionService.java:174`, `minos-engine/src/main/java/com/minos/storage/PersistentRetentionPolicy.java:9`, `minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java:366` et `:441` ; [`M16_EXECUTION.md`](../../project/roadmap/M16_EXECUTION.md) lignes 114 et 140.

Écart d'un ADR source levé par la consolidation : le codec V3, relevé hors de la décision de l'ADR 0023, relève des points 6 à 8 (ADR 0046) — `minos-storage-local/src/main/java/com/minos/storage/local/store/SnapshotCodecV3.java`.

## References

- ADR actifs : [ADR 0024](DATA-0024-active-snapshot-query-view-and-rebuildable-indexes.md) (vue de requête en cache, index reconstruits), [ADR 0056](ARCH-0056-ports-de-lecture-des-snapshots.md) (port de lecture des snapshots), [ADR 0039](BE-0039-reprise-indexation-apres-interruption.md) (reprise de l'indexation, rétention des snapshots préparés orphelins), [ADR 0061](SEC-0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) (E/S privées), [ADR 0064](ARCH-0064-coeur-agnostique-du-langage-et-de-l-indexeur.md) (cœur agnostique de l'indexeur).
- ADR archivés : [ADR 0003](archive/0003-glean-behind-code-knowledge-store.md) (option Glean, validation M0), [ADR 0023](archive/0023-decomposed-local-snapshot-persistence.md) (validation M15-S6), [ADR 0046](archive/0046-format-de-snapshot-v3-chaines-utf8.md) (mesures, historique des commits `a68c560e`/`ebdc6969`), [ADR 0047](archive/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md) (options et mesures détaillées), [ADR 0025](archive/0025-measurement-gated-storage-backend-evolution.md) (promotion de backend sur mesure).
- Spécification : [`openspec/specs/persistance-snapshots/spec.md`](../../../openspec/specs/persistance-snapshots/spec.md) ; développeur : [`indexing-and-storage.md`](../../developer/indexing-and-storage.md) ; banc : [`benchmarks/scalability/README.md`](../../../benchmarks/scalability/README.md) ; audit : [`AUDIT-2026-09.md`](../../project/audits/archive/2026-09/AUDIT-2026-09.md) (constat A6).
