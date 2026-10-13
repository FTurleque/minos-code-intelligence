# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-02**. HEAD analysé : `2d1cc214` (`develop`). `APP` = `minos-application/src/main/java/com/minos`.

## État vérifié au HEAD

### AUD-PERF-02 — confirmé

- `APP/application/semantic/SemanticDocumentFactory.java:69-70` : commentaire « a file is not reread once per symbol » ; `:74` : `sourceReader.readExcerpt(location, 2, CHUNK_MAX_TOKENS)` dans la boucle des symboles d'un fichier. Le lecteur concret est instancié en `:49`.
- `APP/context/LocalSourceReader.java:130-157` : chaque demande d'extrait ouvre le fichier par l'ouverture confinée, le lit en entier, le découpe en lignes et mesure chaque ligne ; `:133-135` explique que le cache a été retiré pour la fraîcheur et rejette l'heuristique taille + date.
- Appelants de la construction : `minos-cli/src/main/java/com/minos/cli/operations/LocalAutonomousIndexOperations.java:276-288` (après une indexation réussie) et `:155` (en `NO_CHANGES`) ; `APP/application/semantic/HybridSearchService.java:136` (reconstruction du corpus structuré).
- Le constat se réfère à « MINOS-AUD-F06 » comme connu ; l'audit lui-même note que ce renvoi est inexact (F06 est la garde de fraîcheur). La référence correcte est l'audit d'architecture de septembre, § A6.8 (`docs/audit/archive/2026-09/ARCHI-SUIVI.md`).

**Non mesuré pendant cette analyse** : les durées (6 ms par document, 112,7 s, 467 à 515 s) sont celles de septembre ; aucune mesure refaite.

### Écarts avec l'action proposée par l'audit

1. **La méthode va sur la classe concrète, pas sur le port.** L'audit propose `LocalSourceReader.load(fileId)`. `SemanticDocumentFactory` instancie déjà le lecteur concret (`:49`) : ajouter la lecture par fichier à la classe suffit et laisse le port `SourceReader` intact (aucune autre implémentation à adapter).
2. **L'ouverture reste confinée.** La lecture unique passe par la même primitive (`ConfinedFileOpener`) et les mêmes bornes de taille que `readExcerpt` ; un chemin de lecture parallèle serait refusé par `check-private-io.py`.
3. **L'aggravant `NO_CHANGES`** relevé à la relecture de l'audit n'appartient pas à ce correctif : il est traité par la sortie anticipée du changement `synchroniser-l-index-semantique-par-differentiel`.
4. **Un fichier modifié pendant la construction.** Aujourd'hui, deux symboles du même fichier peuvent recevoir des extraits de deux états différents du fichier. Avec une lecture par fichier, ils voient le **même** état : c'est plus cohérent, et cela ne retarde pas la fraîcheur (le contenu est lu au moment où le fichier est traité, puis jeté).

## Goals / Non-Goals

**Goals** : une ouverture par fichier distinct pendant une construction ; documents identiques à l'octet ; aucun état conservé après l'appel.

**Non-Goals** : accélérer les extraits demandés un par un par d'autres services ; paralléliser la construction.

## Decisions

### D1. Un objet d'extraits par fichier, de portée locale

`LocalSourceReader` expose une lecture qui rend, pour un fichier, un objet capable de produire des extraits en mémoire (lignes découpées une fois, mesures faites une fois). `readExcerpt` est réécrit **au-dessus** de cet objet (une lecture puis un extrait), pour qu'il n'existe qu'un seul code de découpage : l'identité à l'octet entre les deux chemins est alors structurelle.

`SemanticDocumentFactory.build` obtient l'objet au début du traitement d'un fichier et le laisse sortir de portée à la fin. Aucun champ, aucune table, aucun cache dans la fabrique ni dans le lecteur.

### D2. Échec de lecture : même comportement qu'aujourd'hui

Un fichier illisible, trop gros ou disparu produit pour chacun de ses symboles ce que `readExcerpt` produit aujourd'hui (extrait absent ou document sans extrait, selon le code actuel, à relever en tâche 1.1). La lecture unique ne transforme pas un échec partiel en échec de toute la construction.

### D3. La preuve : compteur d'ouvertures et identité à l'octet

- Un test compte les ouvertures (lecteur instrumenté ou système de fichiers espion) : pour une construction, le compteur égale le nombre de fichiers distincts qui ont au moins un symbole.
- Un test compare, sur les fixtures existantes, les documents produits avant et après (empreintes figées avant la modification) : identiques à l'octet, fins de ligne `\n` et `\r\n` comprises.
- Un test de portée : après `build`, le fichier modifié sur disque donne un extrait à jour à l'appel suivant.

## Direction des dépendances

Changement interne à `minos-application` ; aucun import nouveau.

## Qualification des capacités

Construction des documents sémantiques avec une lecture par fichier : **non qualifiée** au HEAD ; qualifiée par D3. Aucune capacité nouvelle pour l'utilisateur.

## Windows et Linux

Aucune différence attendue. Les fixtures couvrent les deux fins de ligne ; l'ouverture confinée a des chemins propres à chaque système, déjà testés.

## Risks / Trade-offs

- Mémoire : un fichier est tenu décodé le temps de traiter ses symboles, borné par la limite de taille existante du lecteur.
- Régression silencieuse du découpage : contrée par la réécriture de `readExcerpt` au-dessus du même code (D1) et par le test d'identité.
- Partage de `SemanticDocumentFactory` avec les deux autres changements sémantiques du sprint : fusion en série, dans l'ordre de `SUIVI.md`.
