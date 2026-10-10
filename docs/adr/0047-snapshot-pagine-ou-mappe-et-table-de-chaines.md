# 0047 — Snapshot en mémoire : dédoublonnage des chaînes, table de chaînes, pagination ou mappage

Status: Proposed (2026-09-29) — aucune implémentation ; mesures dans [`ARCHI-SUIVI.md`](../audit/archive/2026-09/ARCHI-SUIVI.md) (lot 3, A6, § A6.3 et § A6.11).

Complète l'audit [`AUDIT-2026-09.md`](../audit/archive/2026-09/AUDIT-2026-09.md) (constat A6, point 5). S'appuie sur l'[ADR 0046](0046-format-de-snapshot-v3-chaines-utf8.md) (format V3) et l'[ADR 0024](0024-active-snapshot-query-view-and-rebuildable-indexes.md) (vue de requête en mémoire, index reconstruits, cache borné).

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

- Rien ne change tant que cet ADR est `Proposed`.
- A ne touche aucun format persisté. B et C en créent un, et relèvent de la même discipline que l'ADR 0046.
- Critère de passage d'une étape à la suivante : une mesure sur le corpus réel (banc `benchmarks/scalability/`), jamais une estimation.
