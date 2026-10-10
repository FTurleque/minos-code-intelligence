# 0046 — Format de snapshot V3 : chaînes UTF-8, lecture de tous les formats antérieurs

Status: Accepted (2026-09-29) — mis en œuvre ; mesures, preuves et journal dans [`ARCHI-SUIVI.md`](../audit/archive/2026-09/ARCHI-SUIVI.md) (lot 3, A6, § A6.10 et § A6.11).

Complète l'audit [`AUDIT-2026-09.md`](../audit/archive/2026-09/AUDIT-2026-09.md) (constat A6) et l'[ADR 0023](0023-decomposed-local-snapshot-persistence.md) (persistance locale décomposée, codecs versionnés, pointeur actif). Ne change ni l'[ADR 0024](0024-active-snapshot-query-view-and-rebuildable-indexes.md) (vue de requête et cache) ni le plafond de 256 Mio.

## Contexte

Le format V2 écrit chaque chaîne en unités UTF-16, deux octets par caractère (`DataOutputStream.writeChar`). Or les chaînes font 97 % d'un snapshot. Sur sept corpus réels, tous en ASCII, le fichier est donc 1,8 fois plus gros que nécessaire.

La conséquence est concrète : ce dépôt, indexé par scip-java, produit un SCIP de 23,8 Mo et un snapshot V2 de 355 Mo, au-dessus du plafond de 256 Mio. MINOS ne peut pas indexer son propre dépôt. Le plafond est atteint dès un SCIP de 18 à 29 Mo selon le corpus, bien avant la limite de 512 Mio des artefacts SCIP.

En mémoire, le coût est nul. Les chaînes décodées sont compactes (Latin-1, un octet par caractère) quel que soit l'encodage sur disque, et le tas d'un snapshot chargé ne dépend pas du format.

## Décision

### 1. Format V3

La mise en page de V2 est conservée à l'identique : magic `0x4D4E5359`, version, identifiants, comptes, champs dans le même ordre. Seules changent la version, qui passe à 3, et la forme des chaînes : longueur en **octets** sur 32 bits (`-1` pour `null`), puis les octets **UTF-8**. L'extension reste `.knowledge`.

- **Écriture exacte.** Une chaîne qui contient un surrogate isolé n'a pas de forme UTF-8 exacte. `String.getBytes(UTF_8)` le remplacerait en silence par `?`. L'écrivain V3 le refuse donc au lieu de l'altérer.
- **Lecture stricte et bornée.** Le décodeur est configuré avec `CodingErrorAction.REPORT`, et une séquence mal formée (y compris un surrogate encodé en CESU-8) est une erreur. Chaque longueur est bornée en octets avant toute lecture : 3 × 8 Mi par chaîne, soit ce qu'occupe au plus une chaîne de 8 Mi caractères. Les octets sont lus par `readNBytes`, sans pré-allocation de la longueur déclarée. Les plafonds par nombre d'entités (symboles, occurrences, relations, références, preuves) sont ceux de V2.

### 2. Politique d'écriture : V3, sinon V2, jamais de refus ni de substitution

`KnowledgeSnapshotCodecs.select` est la règle commune au magasin fichier et à PostgreSQL :

- un nouveau **snapshot de connaissance** (`publish` avec occurrences et relations, seul chemin de production) est écrit en V3. La publication historique « symboles seuls » (`FileSymbolSnapshotStore.publish(UUID, String, Collection<Symbol>)`), qui n'a aucun appelant de production, écrit toujours en V1 (UTF-16) ;
- si une seule de ses chaînes contient un surrogate isolé, le snapshot entier est écrit en V2, qui le conserve exactement.

Le choix et la taille encodée exacte sont établis avant toute écriture. Un snapshot au-dessus du plafond est refusé sans rien écrire, dans le format qui aurait été écrit.

### 3. Lecture : tout format jamais écrit reste lisible

- **Magasin fichier.** Le pointeur actif désigne le format du snapshot (1, 2 ou 3). De V2 à V3, la mise en page du pointeur est la même : un pointeur V1 ou V2 garde exactement ses octets, et un pointeur 3 désigne un snapshot V3.
- **PostgreSQL.** Le payload stocké se décrit lui-même par son en-tête (magic et version), sans nouvelle colonne. Les payloads V2 existants restent lisibles, et le descripteur de la vue porte la version réelle au lieu d'un 2 écrit en dur.
- **Ré-import sous le même identifiant.** Même contenu, autres octets (un payload V2 ré-importé après V3) : ce n'est plus un conflit. PostgreSQL décode les deux payloads, chacun dans son format, et compare les contenus. Un payload V2 existant et identique est conservé, tandis qu'un contenu différent reste un conflit d'identité. Le magasin fichier n'a jamais refusé ce cas.
- **Inchangés.** `logicalIdHash`, le schéma des noms `snapshot-<logicalIdHash>-<sha256 du contenu><extension>`, `listSnapshotFiles`, la compaction et la rétention. L'intégrité reste le sha256 des octets du fichier, porté par le pointeur ou par la ligne PostgreSQL : un snapshot V2 existant n'est jamais déclaré corrompu.

## Conséquences

- **Taille.** Sur les corpus mesurés, un fichier V3 fait 0,548 à 0,550 fois le fichier V2 de même contenu. Ce dépôt passe de 355 045 885 à 194 552 030 octets et redevient indexable. Le seuil SCIP du plafond passe de 18,0 à 32,9 Mo pour ce corpus.
- **Lecture à froid** : 2,5 fois plus rapide sur une tranche de 93 Mo (≈ 0,6 s contre 1,5 à 1,7 s), puisqu'il y a moitié moins d'octets à lire et à décoder.
- **Mémoire : aucun gain.** Le tas d'un snapshot chargé est identique en V2 et en V3. Le poids que le cache de vues **estime** (8 fois la taille du fichier) baisse, et le cache accepte donc des snapshots un peu plus gros. Ce n'est pas un gain de mémoire : le tas réel ne bouge pas.
- **Noms des nouveaux snapshots.** Un snapshot publié après ce changement a d'autres octets, donc un autre sha256 en fin de nom de fichier. Les snapshots existants gardent leur nom et restent lisibles, et `logicalIdHash` ne change pas. `retention.golden`, qui liste ces noms, a été mis à jour pour ces seuls suffixes, par exception prouvée : même texte une fois les suffixes masqués, et, pour chaque snapshot du scénario, même modèle décodé que le V2 écrit par la base (`RetentionFormatEquivalenceTest`).
- **Retour arrière (mesuré par verif-archi, V-A6-06).** Un binaire antérieur refuse proprement la lecture et la compaction d'un projet dont le pointeur désigne un V3 (« unsupported active snapshot pointer version: 3 »). Il ne modifie aucun fichier, et se rétablit en republiant un import, qui réécrit un pointeur v2. Côté PostgreSQL, il refuse de décoder un payload V3 (« unsupported knowledge snapshot version: 3 »). Un home ou une base écrits uniquement en V1/V2 restent lisibles par les deux versions.
- **Asymétrie au ré-import (V-A6-07).** Dans le magasin fichier, ré-importer le même `snapshotId` après la mise à jour écrit un fichier V3 et laisse le V2 de même préfixe comme historique : il occupe un emplacement de rétention jusqu'à la prochaine compaction. PostgreSQL, lui, conserve le payload V2 existant et n'en écrit pas de nouveau. Ce comportement est documenté, pas corrigé.
- **Historique (V-A6-05).** Le commit qui introduit V3 (`a68c560e`) est volontairement rouge sur un seul test, `retention.golden`. Le commit suivant, `ebdc6969`, le met à jour, seul et avec sa preuve. Pour un `git bisect`, traiter la paire comme une seule étape.
- **Hors périmètre.** `CodeKnowledgeSnapshotBinaryCodec`, pont public vers le format V2 canonique, reste en V2. Une table de chaînes (chaque chaîne distincte stockée une fois, environ 0,19 fois V2) donnerait un gain bien supérieur, mais demande un format plus profond. Elle est instruite avec le snapshot paginé ou mappé, dans un ADR séparé.
