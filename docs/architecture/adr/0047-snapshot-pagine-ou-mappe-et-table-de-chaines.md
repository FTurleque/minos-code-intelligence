# 0047 — Snapshot en mémoire : dédoublonnage des chaînes, table de chaînes, pagination ou mappage

Status: Accepted (2026-10-13) pour l'option A, mise en œuvre ; options B et C toujours conditionnelles ; mesures dans [`ARCHI-SUIVI.md`](../../project/audits/archive/2026-09/ARCHI-SUIVI.md) (lot 3, A6, § A6.3 et § A6.11).

Complète l'audit [`AUDIT-2026-09.md`](../../project/audits/archive/2026-09/AUDIT-2026-09.md) (constat A6, point 5). S'appuie sur l'[ADR 0046](0046-format-de-snapshot-v3-chaines-utf8.md) (format V3) et l'[ADR 0024](0024-active-snapshot-query-view-and-rebuildable-indexes.md) (vue de requête en mémoire, index reconstruits, cache borné).

## Contexte

Chaque requête travaille sur un `CodeKnowledgeSnapshot` entièrement décodé en tas, complété par les index d'`InMemoryCodeKnowledgeStore`. Mesures sur ce dépôt (883 fichiers, 40 106 symboles, 281 342 occurrences, 14 299 relations ; JDK 24, G1) :

| Mesure | Valeur |
|---|---:|
| Fichier V3 | 194,6 Mo |
| Tas du snapshot relu depuis le disque | 413,2 Mo |
| Tas de la vue (snapshot + index) | 494,7 Mo |
| Tas du **même** snapshot juste après ingestion | 163,3 Mo |
| Lecture à froid (vérification, décodage, index) | 1,65 s |
| Poids estimé par le cache de vues (8 fois le fichier) | 1,56 Go : jamais mis en cache |
| Chaînes distinctes / champs chaîne | 439 400 / 5 985 691 |
| Taille avec une table de chaînes par snapshot, mesurée par recensement | 0,19 fois V2, soit ≈ 0,35 fois V3 |

Trois constats en découlent :

1. **La lecture recrée les doublons.** Le décodage crée une instance `String` par champ. Le `projectId`, les noms d'énumérations, les quatre chaînes de l'`Origin` et les `fileId` sont ainsi dupliqués sur chaque entité, alors que l'ingestion partage ces instances. Même contenu, 2,5 fois plus de tas : 413 Mo contre 163.
2. **La redondance, plus que l'encodage, fait la taille.** Moins d'une chaîne sur treize est distincte. V3 divise le fichier par 1,8 ; une table de chaînes le diviserait encore par près de 3.
3. **Le cache ne protège pas les gros projets.** Au-delà de 64 Mio persistés, la vue n'est pas mise en cache et chaque requête relit tout le snapshot, deux fois pour la recherche hybride. Les causes directes (poids estimé 8 fois pour 1,4 mesuré, double chargement par `SemanticIndexService.status`) sont des corrections bornées, hors de cet ADR (A6 § A6.8).

## Options

- **A. Dédoublonner les chaînes au décodage.** Pas de changement de format. Le lecteur interne chaque chaîne dans une table locale à la lecture, qui est jetée ensuite. Gain attendu : le tas relu rejoint celui de l'ingestion, de l'ordre de 2,5 fois moins. Coût : une recherche dans une table de hachage par champ chaîne (6 millions pour ce dépôt). Risque faible : le modèle décodé reste égal, seule l'identité des instances change.
- **B. Table de chaînes sur disque (format V4).** Chaque chaîne distincte est stockée une fois, un index de 4 octets par champ. Gain : fichier ≈ 0,35 fois V3, lecture plus rapide, et le dédoublonnage de A devient gratuit. Coût : nouveau format, avec les mêmes obligations que V3 (lecture des formats antérieurs, pointeur, PostgreSQL, ré-import, fixtures de la base, preuve sur les golden qui listent des noms de fichiers).
- **C. Snapshot paginé ou mappé en mémoire.** Les entités restent sur disque, accédées par décalage à travers un fichier mappé, avec des index construits sur les décalages. Gain : le tas ne porte plus le snapshot. Coût : une refonte, pas une correction. `CodeKnowledgeSnapshot` expose des `List` complètes, consommées par 25 classes de production hors stockage (impact, architecture, hybride, program graph, NEXUS…). Il faudrait introduire un accès paresseux ou par curseur dans tous, avec leurs contrats de déterminisme et d'ordre, et régler la durée de vie du mappage face à la rétention et à la compaction.

## Décision proposée

1. D'abord, **hors de cet ADR**, les corrections bornées du § A6.8 : poids estimé des vues et du corpus hybride ramené à la mesure, et suppression du double chargement. Elles font que les gros projets sont mis en cache au lieu d'être relus à chaque requête.
2. Ensuite l'**option A**, sous preuve d'égalité du modèle décodé sur les corpus réels et les fixtures de la base, avec une mesure avant/après du tas relu.
3. L'**option B** seulement si la taille sur disque ou le temps de lecture restent un problème après A, pour des tailles de projet cibles à fixer.
4. L'**option C** seulement si, après 1 et 2, le tas d'un projet cible dépasse encore le budget mémoire accepté. Ce serait alors un chantier propre, précédé d'un inventaire des consommateurs de `CodeKnowledgeSnapshot`.

## Conséquences

- L'option A est décidée et mise en œuvre (voir « Mise en œuvre ») ; B et C ne changent rien tant que leur critère n'est pas atteint.
- A ne touche aucun format persisté. B et C en créent un, et relèvent de la même discipline que l'ADR 0046.
- Critère de passage d'une étape à la suivante : une mesure sur le corpus réel (banc `benchmarks/scalability/`), jamais une estimation.

## Mise en œuvre

**Option A, 2026-10-13** (changement OpenSpec `ramener-le-poids-des-vues-a-la-mesure`, constat AUD-PERF-03 de l'audit du 10 octobre 2026).

- Décision du propriétaire : dédoublonner **avant** de ramener le poids estimé, à l'inverse de l'ordre proposé plus haut. La mesure a montré que le rapport « 1,4 » datait du format V2 : en V3 une vue relue retenait 2,5 fois son fichier sur ce dépôt (mesure archivée) et 3,05 à 3,32 fois sur un corpus synthétique. Aucun facteur sûr ne permettait donc de mettre un gros projet en cache sans réduire d'abord le tas.
- Mécanisme : une table locale à chaque passe de décodage (`SnapshotBinaryCodecSupport`, lecture des chaînes), jetée avec elle ; une instance par chaîne distincte, formats V2 et V3. Aucun format persisté ne change ; le modèle décodé reste égal.
- Mesure (`QueryViewWeightTest`, corpus synthétique de 3 000 symboles, 21 000 occurrences, 15 000 relations) : tas retenu par la vue de 45,4–49,0 Mo à 21,0–21,6 Mo, soit 1,41 à 1,46 fois le fichier V3 au lieu de 3,05 à 3,32.
- Conséquence : `QUERY_VIEW_PERSISTED_AMPLIFICATION` passe de 8 à 2 dans les deux stores ; tout snapshot persistable (256 Mio) est admissible au cache de 512 Mio sur ce terme.
- **Non mesuré** : le corpus réel (le banc `benchmarks/scalability/` n'a pas été rejoué, scip-java ne s'exécutant pas sur le poste de développement), le temps de décodage et le pic de tas pendant le décodage (la table retient une entrée par chaîne distincte jusqu'à la fin de la passe). Le critère « une mesure sur le corpus réel » reste donc à satisfaire avant d'envisager l'option B.
- **Lecture authentifiée en un parcours** (même changement, lot 3, décision D2 du propriétaire, 2026-10-13 ; PR #442, fusionnée le 2026-10-13) : `SnapshotCodec.readVerified` décode et calcule l'empreinte dans le même parcours pour V2 et V3 ; rien de décodé n'est publié si l'empreinte diffère (`SnapshotVerifiedReadTest`, `FileSymbolSnapshotStoreIntegrityTest`). Sous PostgreSQL, l'empreinte est calculée pendant la copie : deux parcours au lieu de trois. V1 garde deux parcours.
- **Mesure sur corpus réel (2026-10-15, tâche 2.4)** : index de ce dépôt produit sous Linux (scip-java sous bubblewrap et cgroup v2, JDK 25), sur le code de production du reactor (532 fichiers, 28 117 symboles, snapshot V3 de 114 Mo). Deux `loadActiveQueryView` successifs, store construit comme celui du serveur MCP (plafond 512 Mio) : avant la PR #442, la vue (poids estimé 912 Mo) est refusée par le cache, **2** lectures complètes et une seconde lecture de 0,88 à 0,99 s ; après, **1** lecture, seconde lecture de 0,44 à 0,53 ms. Tas retenu par une vue : ≈ 289 Mo avant (2,54 fois le fichier, comme la mesure archivée), 129,6 Mo après (1,14 fois) ; l'estimation (2 fois, plus l'index des arêtes) le couvre. Le reactor complet, tests compris, n'a pas pu être mesuré : son snapshot (300 Mo encodés) dépasse la limite de persistance de 256 Mio. Le critère « une mesure sur le corpus réel » est rempli pour l'option A. Ce dépassement de la limite de 256 Mio est une donnée pour le déclencheur de l'option B (« la taille sur disque reste un problème après A ») ; la décision reste au propriétaire.
- **Ordre confirmé (2026-10-22, audit AUD-ARC-07, changement OpenSpec `trancher-les-decisions-d-architecture-du-sprint-10`, décision D1 du propriétaire)** : l'option A et la correction du poids des vues sont livrées et mesurées ; les options B et C gardent leurs critères (taille sur disque ou temps de lecture après A pour B, avec la limite de persistance de 256 Mio comme donnée d'entrée ; tas d'un projet cible au-delà du budget mémoire pour C), toujours sur mesure du banc `benchmarks/scalability/`. La reconstruction d'un `InMemoryCodeKnowledgeStore` complet par snapshot lu relève du port de lecture de l'[ADR 0056](0056-ports-de-lecture-des-snapshots.md) (tâche SH-04), dont le prérequis de mesure est rempli.
