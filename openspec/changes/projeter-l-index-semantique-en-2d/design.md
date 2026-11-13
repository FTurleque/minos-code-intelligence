# Design

## Contexte

Motivation : `proposal.md`. Comparaison des méthodes, budgets et protocole de mesure : `docs/project/research/explorateur-vectoriel-2026-11/README.md` § 5. Conception commune du chantier : `openspec/changes/explorer-l-index-semantique-dans-un-navigateur/design.md` (ou son archive), dont ce design reprend le modèle, les états et les contrats de projection sans les recopier. Modules touchés : domaine, stockage, application, API, CLI, surface d'exploration.

## État vérifié au HEAD

Commit `63e0c4ac5288ebd6d52b3462e40e7067d06b36ce` (`develop`). **À relire au HEAD du démarrage** : ce changement suit un autre changement qui modifie les mêmes ports.

| Fait | Preuve |
|---|---|
| `load()` matérialise tout l'index, vecteurs compris, en texte puis en `double[]` | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticReadQueries.java:121-147`, `:199-213` |
| Les lectures additives du port ont un défaut dérivé de `load()` | `minos-domain/src/main/java/com/minos/semantic/SemanticVectorStore.java:51-71` |
| Identité d'un document : `stable_key`, `checksum` | `minos-application/src/main/java/com/minos/application/semantic/SemanticDocumentFactory.java:146-147` |
| Format local `index-v2.bin`, vecteurs `float32`, plafond de 192 Mio décodés | `minos-storage/src/main/java/com/minos/storage/local/store/FileSemanticVectorStore.java:48-56` |
| Remplacement conditionnel de l'index sous verrou de projet | `minos-storage/src/main/java/com/minos/storage/postgresql/semantic/PostgresSemanticVectorStore.java:82-110` |
| Index réel de 25 210 documents mesuré le 2026-10-15 | `docs/project/governance/STATUS.md:61` |

Non mesuré : tout ce qui concerne la PCA, le cache et le rendu.

## Écarts

Aucun au HEAD. Écart à surveiller : si le changement précédent livre des noms de ports différents de ceux de la conception commune, ce design est corrigé avant la première tâche.

## Exigences couvertes

| Exigence | Section |
|---|---|
| `exploration-semantique` › La préparation d'une projection est une commande explicite et bornée | Traitements ; D1, D2 |
| `exploration-semantique` › Une projection est publiée atomiquement et liée à l'identité de son index | Persistance ; Transactions |
| `exploration-semantique` › Les points d'une projection annoncent méthode, sélection et échantillonnage | Contrats d'interface |
| `exploration-semantique` › La lecture d'une projection ne calcule rien | Responsabilités ; Contrats d'interface |

## Objectifs et non-objectifs

Objectifs : calcul reproductible, borné en mémoire, hors du chemin de recherche ; carte jamais silencieusement fausse. Non-objectif : la meilleure séparation visuelle possible.

## Responsabilités et frontières des composants

| Composant | Module | Responsabilité |
|---|---|---|
| `SemanticVectorStore` (lecture par lots, additive) | `minos-domain` | vecteurs après une clé stable, par lots bornés |
| `SemanticProjectionStore` (port) | `minos-domain` | lire et publier une projection |
| Adaptateurs de lots ; cache fichier | `minos-storage` | lecture sans `load()` ; E/S privées |
| `SemanticProjectionService` | `minos-application` | sélection, PCA, identité, statut, points |
| Commandes `semantic projection prepare\|status` | `minos-cli` | lancement, progression sur `stderr`, codes de sortie |
| Routes de statut et de points ; carte | `minos-explorer` | lecture seule, rendu Canvas 2D |

Direction ARCH-0068 respectée, sans exception. Le calcul ne lance aucun processus.

## Modèle de domaine et invariants

Repris de la conception commune (« Modèle de domaine et invariants ») : identité d'index, sélection par rang `sha256(graine ␟ stableKey)`, projection = identité + méthode + paramètres + graine + sélection. Invariants ajoutés : la PCA centre les données ; le signe de chaque composante est fixé (première coordonnée non nulle positive) pour que deux préparations donnent les mêmes coordonnées ; les coordonnées sont normalisées dans `[-1, 1]`.

## Traitements et interactions

Séquence : conception commune, « Comment une projection est-elle préparée sans toucher au chemin de recherche ? ». PCA : moyenne en un passage ; deux composantes par itération de puissance avec déflation, chaque itération étant un passage par lots sur l'échantillon d'ajustement ; projection de tous les points retenus en un dernier passage. Mémoire : proportionnelle à la dimension et à la taille d'un lot, pas au corpus.

## États et transitions

`ABSENTE`, `EN_PREPARATION`, `PRETE`, `ECHOUEE`, `PERIMEE` : conception commune, « Par quels états passe une projection ? ».

## Contrats d'interface

Routes `GET /api/v1/projects/{p}/projection` et `…/projection/points` : conception commune, « Contrats d'interface ». Toute autre méthode sur ces routes rend `405`.

| Commande | Options | Sortie | Codes |
|---|---|---|---|
| `minos semantic projection prepare <projet>` | `--max-points N` (défaut à fixer), `--seed N` (défaut 42), `--kinds CHUNK[,SYMBOL,FILE]` (défaut `CHUNK`) | rapport : points, documents, variance expliquée, durée | 0 en cas de succès ; non nul sinon (usage, index non `READY`, index modifié pendant la préparation, erreur interne), valeurs à aligner sur la convention de `docs/user/cli.md` |
| `minos semantic projection status <projet>` | — | état, identité, méthode, sélection | 0 ; non nul pour une erreur d'usage |

La préparation exige un index `READY` : projeter un index `STALE` produirait une carte périmée dès sa publication.

## Persistance et intégrité

Fichier `semantic-projections/<projet>/projection-v1.bin` sous `MINOS_HOME`, pour les deux backends : magie, version de format, identité complète, méthode et paramètres, sélection, variance expliquée, nombre de points, clés stables, coordonnées `float32`, empreinte SHA-256 finale vérifiée à la lecture. Une version de format inconnue est refusée.

## Transactions, concurrence et idempotence

Une préparation à la fois par projet (verrou de fichier) ; identité relue avant publication ; remplacement atomique d'un fichier temporaire du même dossier ; fichier temporaire supprimé en cas d'échec ou d'interruption. Deux préparations identiques produisent des fichiers identiques octet pour octet.

## Sécurité et isolation

E/S par les primitives privées (`scripts/architecture/check-private-io.py`) ; aucune route d'écriture (ARCH-0082) ; aucun chemin absolu dans le rapport ni les erreurs ; les points ne portent que des clés stables, le contenu se lit par le détail.

## Erreurs et reprise

Budget dépassé, index non prêt, index modifié pendant la préparation : erreurs actionnables, projection précédente intacte. Fichier corrompu : état `ECHOUEE` à la lecture, cause publique « projection illisible », relance possible.

## Observabilité

Progression par lots, durée et tas maximal sur `stderr` ; rapport final sur `stdout`.

## Performance et mesure

Aucun seuil : [Question]. Protocole : étude § 5.5 (1 000 et 10 000 documents synthétiques `local-hash`, index réel d'environ 25 000 documents, 100 000 documents sur PostgreSQL). Relevés : durée et tas de la préparation, taille du cache, taille de la charge des points, délai du premier rendu. Windows et Linux.

## Compatibilité, migration et retour arrière

Additif ; aucune migration. Retour arrière : supprimer le dossier `semantic-projections`.

## Diagrammes

Séquence de préparation, états et modèle d'identités : conception commune. À reporter dans le dossier de capacité par la tâche 6.9.

## Décisions

### D1 — PCA à deux composantes, en Java, sans dépendance
[Décision — propriétaire, 2026-11-13] Écartés à ce stade : UMAP (dépendance et licence), t-SNE (pas de projection de nouveaux points). Compromis : lisibilité non garantie, variance expliquée affichée.

### D2 — Préparation par commande, jamais par consultation
[Décision — propriétaire, 2026-11-13] Aligné sur BE-0029 et ARCH-0082.

### D3 — Cache dérivé en fichier pour les deux backends
[Décision — propriétaire, 2026-11-13] Écarté : tables PostgreSQL (migration de schéma sans mesure qui la justifie).

### D4 — Rendu Canvas 2D
[Proposition] Écarté à ce stade : WebGL (dépendance embarquée à justifier par une mesure, tâche 7.1).

## Qualification des capacités

| Capacité | Qualification |
|---|---|
| Carte 2D, stockage local ou PostgreSQL, natif | non supportée tant que le groupe 6 n'est pas vert |
| Carte 2D, runtime Docker | non supportée ; dépend de la qualification du service `minos-explorer` |
| UMAP, WebGL, 3D | non supportées, sans engagement |

## Risques et compromis

- [Risque] Carte PCA peu lisible → variance affichée, décision 7.1.
- [Risque] Itération de puissance lente à converger quand deux valeurs propres sont proches → nombre d'itérations borné, résultat déterministe quand même.
- [Risque] Remplacement atomique différent sous Windows → test sur les deux plateformes.

## Vérification

Tests de reproductibilité, de budget, d'atomicité et de péremption ; test de contrat de la lecture par lots sur les deux adaptateurs ; test « aucun appel au provider ni à la recherche » ; gates cités dans la proposition. Manuel : rendu de la carte dans un navigateur.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/exploration-semantique.md`, `docs/user/` (explorateur, CLI), `docs/developer/`, `STATUS.md`, `ROADMAP.md`.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| exploration › préparation explicite et bornée | Préparation reproductible | Traitements | 6.1–6.2 | à écrire |
| idem | Budget dépassé | Erreurs | 6.1–6.2 | à écrire |
| exploration › publication atomique | Index resynchronisé | États | 6.3–6.4 | à écrire |
| idem | Préparation interrompue | Transactions | 6.3–6.4 | à écrire |
| exploration › points annoncés | Corpus échantillonné | Contrats | 6.5–6.6 | à écrire |
| exploration › lecture sans calcul | Projection absente | Contrats | 6.5–6.6 | à écrire |
| idem | Préparation demandée par HTTP | Contrats | 6.5–6.6 | à écrire |

## Questions ouvertes

| # | Question | Recommandation |
|---|---|---|
| 1 | Plafond de points par défaut | 100 000, à confirmer par la mesure |
| 2 | Seuils de performance | à fixer après la tâche 6.8 |
