# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-03**. HEAD analysé : `2d1cc214` (`develop`). `ARCHI-SUIVI.md` est archivé sous `docs/audit/archive/2026-09/` (§ A6.8 en `:989`).

## État vérifié au HEAD

### AUD-PERF-03 — confirmé

- `minos-storage-local/src/main/java/com/minos/storage/local/store/FileSymbolSnapshotStore.java` : `:44` `QUERY_VIEW_PERSISTED_AMPLIFICATION = 8L` ; `:41` plafond de 512 Mio ; `:247` admission `weight <= maxQueryCacheWeightBytes` ; `:367` `safeMultiply(Files.size(snapshot), 8)` ; `:274` `verifyChecksum` puis `:275` `codec.read` (deux parcours).
- `minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/snapshot/PostgresCodeKnowledgeSnapshotStore.java` (déplacé par le rangement en sous-packages, lignes décalées de 3) : constante `:47`, plafond `:49`, usage `:452`. Après copie du contenu dans un fichier temporaire (`:366-369`) : `:414` empreinte, `:418` `formatVersion`, `:419` `decode`, soit **trois** parcours. Même double lecture à la publication (`:287-290`).
- Le poids retenu est `max(estimation par compteurs, taille persistée × facteur)`.
- Les formats V1 et V2 restent lisibles (`:352-358`).

**Non mesuré pendant cette analyse** : aucune durée ni aucun tas (aucun build ni benchmark lancé). Le rapport 1,4 est une mesure de septembre, sur le format V3 et sur ce dépôt.

### Écarts avec l'action proposée par l'audit

1. **« Poids calculé depuis les compteurs seuls » casse un gate.** `check-mne.py:85-88` exige les chaînes `QUERY_VIEW_PERSISTED_AMPLIFICATION`, `Files.size(snapshot)`, `safeMultiply` et `estimateWeight(metadata, payloadBytes)`. La constante est conservée ; seule sa valeur change, et le gate est mis à jour s'il fige la valeur.
2. **Un facteur 1,5 n'est pas un entier.** `safeMultiply` opère sur des `long` : il faut un numérateur et un dénominateur (par exemple 3/2), avec le même garde de dépassement.
3. **Le terme par compteurs peut dominer.** Réduire le facteur ne suffit pas si l'autre branche du `max` refuse encore le snapshot : la mesure doit porter sur le poids **final**.
4. **1,4 ne vaut que pour V3.** Les chaînes de V1/V2 se décodent autrement : le test de poids se fait par format lisible, ou le facteur d'un format non mesuré reste prudent.
5. **La fusion empreinte + décodage change une garantie.** Aujourd'hui, aucun octet non authentifié n'est décodé. Après fusion, le décodeur traite des octets non vérifiés : il faut lire jusqu'à la fin du fichier (octets de queue compris), comparer, et **seulement alors** publier, mettre en cache et compter. Le type d'erreur d'un fichier corrompu peut changer (erreur de décodage avant erreur d'empreinte).
6. **Sous PostgreSQL, la fusion est plus simple que ne le dit l'audit.** L'empreinte peut être calculée **pendant la copie** vers le fichier temporaire (`:366-369`), sans toucher au codec : trois parcours deviennent deux, sans décoder d'octet non authentifié.

## Goals / Non-Goals

**Goals** : un snapshot de la taille de ce dépôt tient dans le cache ; le poids estimé ne sous-évalue pas le tas retenu ; moins de parcours du fichier ; intégrité fail-closed inchangée pour l'appelant.

**Non-Goals** : supprimer le rechargement après invalidation ; changer le plafond ; refondre le décodage (ADR 0047, décisions suivantes).

## Decisions

### D1. Facteur rationnel mesuré, par format

Le facteur devient un couple numérateur/dénominateur. Sa valeur est fixée par un test qui construit une vue depuis un snapshot réel et compare l'estimation au tas retenu (même méthode que `HybridCorpusWeightTest`), avec une marge. Un format sans mesure garde le facteur 8. Le test échoue si l'estimation est **inférieure** au tas mesuré : le cache ne doit jamais sous-peser.

### D2. Parcours unique sous le store fichier — **décision en attente du propriétaire**

Option A (recommandée) : décoder au travers d'un flux qui calcule l'empreinte, vider le flux jusqu'à sa fin, comparer, puis publier. Toute exception de décodage est **requalifiée** après comparaison : si l'empreinte diffère, l'erreur rendue est celle d'intégrité, comme aujourd'hui. Gain : un parcours au lieu de deux sur le chemin le plus fréquent.

Option B : garder les deux parcours sous le store fichier et ne faire que D1 et D3. Aucun octet non authentifié n'est décodé ; le gain de D1 (plus de rechargement à chaque requête) est de loin le plus grand.

Le décodeur est déjà exposé à des fichiers que MINOS n'a pas écrits (le contrôle d'empreinte protège de la corruption, pas d'un attaquant qui réécrit fichier et empreinte) : l'option A n'ouvre pas de surface nouvelle, mais elle doit être prouvée par des tests de fichiers tronqués, altérés et prolongés.

### D3. Sous PostgreSQL : empreinte pendant la copie

L'empreinte est calculée par le flux qui écrit le fichier temporaire ; la passe `:414` disparaît. La lecture de `formatVersion` (`:418`) est fusionnée avec le décodage si le codec le permet sans changer son contrat ; sinon elle reste (quelques octets).

### D4. Rien n'est publié avant la comparaison

Invariant commun aux deux stores, écrit en test : sur un contenu altéré, `loadActiveQueryView` échoue, le cache reste vide, `fullSnapshotLoads` n'est pas incrémenté, et un second appel échoue de la même façon (pas d'entrée empoisonnée).

## Direction des dépendances

Changements internes aux deux adaptateurs de stockage ; aucun import nouveau entre adaptateurs (le couplage existant PostgreSQL → codecs du backend local, AUD-ARC-08, n'est ni aggravé ni traité).

## Qualification des capacités

- Mise en cache d'un snapshot V3 de la taille de ce dépôt : **non qualifiée** au HEAD ; qualifiée par D1.
- Poids des formats V1/V2 : **partielle** (facteur prudent conservé si non mesuré).
- Intégrité à la lecture : qualifiée, inchangée pour l'appelant.

## Windows et Linux

Le store fichier se teste sur les deux systèmes (verrous et fichiers ouverts diffèrent sous Windows : le flux doit être fermé avant toute publication). Le store PostgreSQL se teste avec une base réelle ; en CI, sous Linux.

## Risks / Trade-offs

- Un facteur trop bas ferait dépasser le tas : D1 échoue dans ce sens.
- Admettre de gros snapshots au cache augmente le tas résident du serveur MCP (c'est le but) : le plafond de 512 Mio reste la borne ; à dire dans la documentation d'exploitation.
- Le message d'erreur d'un fichier corrompu doit rester celui d'intégrité (D2-A) ; des tests existants encodent peut-être l'ordre actuel, à relever en tâche 1.1.
