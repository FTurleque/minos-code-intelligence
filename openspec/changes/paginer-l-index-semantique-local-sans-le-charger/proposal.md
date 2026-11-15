# Proposal

## Why

Constat **AUD-PERF-03** de l'audit `docs/project/audits/2026-11-15/` (sévérité moyenne, sprint 4). En stockage local, la liste, le détail et les voisins de l'explorateur passent par les implémentations par défaut du port `SemanticVectorStore` : chacune charge et décode l'index vectoriel entier (`load`), trie tous les documents pour en rendre une page, ou les parcourt pour en trouver un. Quand l'index pèse plus que le plafond du cache (256 Mio), ce décodage se refait à chaque requête, et deux requêtes froides simultanées le font deux fois. Le store PostgreSQL redéfinit ces lectures ; le store local ne redéfinit que `vectorPage` et `checksums`.

La relecture au HEAD borne le domaine du constat. La synchronisation ne construit pas d'index dont les vecteurs dépassent 192 Mio comptés à 8 octets par dimension (32 768 documents à 768 dimensions), ni de plus de 250 000 documents (`SemanticIndexBudget`) ; `load` applique le même seuil à la lecture. Le défaut porte donc sur un index **constructible** dont le poids en cache (vecteurs en `double`, chaînes, surcoût par document) dépasse 256 Mio : il est décodé en entier à chaque requête. Un index au-delà du budget de décodage n'existe que si son fichier a été écrit hors de la synchronisation ; `load` le refuse, et ce cas n'est pas un objectif de ce changement.

Les coûts de l'audit sont des estimations. Ce changement exige une mesure avant et après sur le banc de AUD-PERF-13 (sprint 1, non livré) et ne présente aucun gain comme acquis.

## What Changes

- Le store local rend une page de documents en parcourant le fichier d'index trié, avec point de reprise, sans décoder de vecteur ni les documents hors de la page, et sans charger l'index.
- Il rend un document et son vecteur en lisant ce seul document.
- Il classe les voisins d'un vecteur, quand l'index ne peut pas être tenu par le cache, par un parcours qui ne retient ni l'index ni les contenus hors résultat, avec le classement et les scores du chemin chargé.
- Un décodage complet du même index par plusieurs requêtes simultanées n'a lieu qu'une fois.
- Un index à l'ancien format (version 1), dont l'ordre n'est pas garanti, garde le chemin chargé, comme `vectorPage` aujourd'hui.
- Mesure avant et après sur le banc de AUD-PERF-13 : latence des routes liste, détail et voisins, tas retenu, de part et d'autre du plafond du cache, sur des index que la synchronisation peut construire.
- Aucune rupture : contrat du port, format du fichier et réponses de l'explorateur inchangés.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : quatre exigences **ajoutées** (aucune exigence existante modifiée) — page de documents locale sans chargement de l'index, détail local d'un seul document, voisins locaux sans rétention de l'index, décodage complet unique sous requêtes simultanées.

## Hors périmètre

- La représentation des vecteurs en mémoire en `float[]` (troisième action de l'audit) : `SemanticVector` est un type du domaine en `double[]`, utilisé par tout le chemin sémantique ; le changer dépasse le store local et relève du budget mémoire (AUD-PERF-04, sprint 5). Voir design D4.
- Les plafonds du store local (fichier 384 Mio, décodage 192 Mio de chaînes et de vecteurs) et le plafond du cache : inchangés ; leur compte dans le budget des vues est AUD-PERF-04.
- La recherche sémantique et hybride hors explorateur : leur résultat ne change pas ; aucune taille d'index supplémentaire n'est annoncée comme supportée.
- La lecture d'un fichier d'index plus gros que ce que la synchronisation construit (écrit hors du produit) : non visée, non testée.
- Le délai de 30 s du serveur de l'explorateur et le travail poursuivi après un délai : sprint 3 (`fiabiliser-la-surface-de-l-explorateur`).
- PostgreSQL (AUD-PERF-01) et la préparation de la projection (AUD-PERF-02).
- L'extension du banc (AUD-PERF-13) : prérequis.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le store local ne redéfinit ni `documents`, ni `documentPage`, ni `storedDocument`, ni `search` | [Existant vérifié] | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java` (surcharges : `load:84`, `metadata:152`, `checksums:187`, `vectorPage:220`) |
| Les défauts du port chargent l'index entier et trient | [Existant vérifié] | `minos-domain/src/main/java/com/minos/semantic/SemanticVectorStore.java:60-62`, `:86-105`, `:115-128`, `:190-223` |
| Cache de 256 Mio, décodage complet hors verrou | [Existant vérifié] | `FileSemanticVectorStore.java:60`, `:93-144` |
| `load` refuse au-delà de 192 Mio de vecteurs comptés à 8 octets par dimension | [Existant vérifié] | même fichier `:59`, `:134`, `:691-699` |
| La synchronisation refuse de construire un index au-delà du même seuil, et au-delà de 250 000 documents | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` ; `SemanticIndexService.java:137-138` |
| Le fichier d'index est refusé au-delà de 384 Mio, à l'écriture et à la lecture | [Existant vérifié] | même fichier `:57`, `:355`, `:537-541` |
| Le fichier version 2 est écrit en ordre de clé stable | [Existant vérifié] | même fichier `:322-324` |
| La liste filtrée lit jusqu'à 2 000 documents par lots de 200 | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticCorpusQueryService.java:40`, `:47`, `:148` |
| Un index constructible peut peser plus de 256 Mio en cache et être décodé à chaque requête | [Existant vérifié] par lecture (poids : `FileSemanticVectorStore.java:712-724`) ; non reproduit par exécution | design, « État vérifié au HEAD » |
| Gain en latence et en tas | [Question] | mesure sur le banc de AUD-PERF-13 |

## Impact

- **Modules du reactor** : `minos-storage` (package `com.minos.storage.local.store`). `minos-domain` et `minos-application` ne changent pas.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune modification de contrat ; l'explorateur rend les mêmes réponses.
- **ADR** : aucun (pas de format persisté ni de contrat modifié).
- **Gates à rejouer** : `scripts/remediation/check-mne.py` et `scripts/quality/check-semantic-retrieval-consistency.py` (ils affirment des chaînes littérales de `FileSemanticVectorStore.java`), `scripts/architecture/check-private-io.py` (lectures par les primitives confinées), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py --fast` ; scope JaCoCo `semantic-vector-store`.
- **Plateformes** : Windows et Linux — lecture séquentielle par `ConfinedFileOpener`, même comportement attendu ; test de concurrence à rejouer sur les deux.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/exploration-semantique.md` ; `docs/user/semantic-explorer.md` (limites de taille en stockage local, seulement mesurées) ; `docs/developer/indexing-and-storage.md` si la lecture de l'index sémantique y est décrite.

## Questions ouvertes

Non bloquantes pour la rédaction :

- D3 — critère qui fait passer les voisins au parcours sans rétention (recommandation : index que le cache ne peut pas tenir).
- D4 — vecteurs en `float[]` en mémoire : reporté au sprint 5 (recommandation).
- La taille de contenu qui fait passer un index de 32 768 documents à 768 dimensions au-dessus du plafond du cache : à relever par le banc de AUD-PERF-13.
- Seuil d'acceptation du gain : à fixer sur la mesure « avant ».
