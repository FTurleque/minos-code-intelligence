# Conception fonctionnelle — explorateur vectoriel MINOS

Complète l'[étude](README.md). Tout ce qui suit est une **proposition** ; les champs cités sont ceux vérifiés au § 1 de l'étude. Maquette : [maquette/index.html](maquette/index.html).

## 1. Disposition

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│ MINOS · Explorateur   Projet ▾   index : READY · modèle · dims · snapshot     │
├────────────┬─────────────────────────────────────────────┬───────────────────┤
│ Filtres    │ Recherche  [ question en langage naturel ] [mode ▾] [Chercher]  │
│            ├─────────────────────────────────────────────┤ Détail            │
│ Nature     │                                             │ fichier, lignes   │
│ Module     │              Carte 2D                       │ symbole, nature   │
│ Langage    │   12 480 points affichés sur 25 210         │ contenu indexé    │
│ Fichier    │   (échantillon — PCA, variance 31 %)        │ source actuelle   │
│            │                                             │ provenance        │
│ Couleur ▾  ├─────────────────────────────────────────────┤ [Similaires]      │
│ Légende    │ Résultats / Documents (table paginée)       │                   │
└────────────┴─────────────────────────────────────────────┴───────────────────┘
```

Les trois vues (table, carte, détail) partagent **une sélection** et **un jeu de filtres**. L'en-tête rappelle en permanence l'identité de l'index : état, provider, modèle, dimensions, snapshot indexé, snapshot actif.

## 2. Table des documents

| Colonne | Source | Disponibilité |
|---|---|---|
| Projet | nom d'affichage du projet | toujours |
| Fichier | `file_id` (référence relative) | absent si le document n'a pas de fichier |
| Symbole | nom qualifié résolu depuis `source_id` | « non résolu » si l'index n'est pas aligné sur le snapshot actif |
| Nature | `kind` : `SYMBOL`, `CHUNK`, `FILE` | toujours |
| Module, langage | snapshot structurel | « non résolu » dans le même cas ; vides pour un fichier sans symbole résolu |
| Lignes | `start_line`–`end_line` | `0–0` affiché « — » (nature `FILE`, symbole sans emplacement) |
| Snapshot | `snapshot_id` abrégé | toujours |
| Aperçu | premières lignes de `content` | toujours, rendu comme texte |
| Modèle, dimensions | métadonnées de l'index | identiques pour tout le projet |

Pagination par curseur (`stable_key` croissant), 50 lignes par défaut, 200 au plus. Le total affiché est celui de l'index, avec le nombre de lignes après filtrage. Tri unique dans le MVP : `stable_key`.

## 3. Contrat des filtres

| Filtre | Liste | Recherche | Carte |
|---|---|---|---|
| Projet | sélectionne l'index | idem | idem |
| Nature | appliqué par le serveur | appliqué par le serveur **après** classement, sur les résultats rendus | masque les points, sans recalcul de projection |
| Module, langage | appliqués par le serveur quand la résolution est disponible | idem | masque les points |
| Préfixe de fichier | appliqué par le serveur | idem | masque les points |
| Score minimal | sans objet | transmis au service (`minimumScore`) | atténue les points sous le seuil |

Règles :

- Un filtre ne modifie **jamais** un score ni un rang : il retire des lignes. Quand un filtre réduit les résultats d'une recherche, l'interface indique « n résultats affichés sur m classés ».
- Un filtre indisponible (module ou langage non résolus) est grisé avec sa raison ; il n'est pas ignoré en silence.
- La carte ne recalcule rien : la projection est celle qui a été préparée, les filtres n'agissent que sur l'affichage. La mention « filtré » apparaît à côté du compteur.

## 4. Carte vectorielle

- **Un point = un document sémantique.** Par défaut, seule la nature `CHUNK` est affichée : `SYMBOL` et `CHUNK` décrivent le même symbole et se superposeraient. Le choix est visible et modifiable.
- **Interactions** : molette pour le zoom, glisser pour déplacer, survol pour une infobulle (fichier, symbole, nature), clic pour sélectionner et ouvrir le détail, double-clic pour recentrer.
- **Couleur** : par nature, module ou langage. La légende liste les valeurs réelles et leur effectif. Au-delà de douze valeurs, les moins fréquentes sont regroupées sous « autres (n) ».
- **Compteur permanent** : « *x* points affichés sur *y* documents » ; s'il y a échantillonnage : « échantillon de *x* sur *y* (graine *g*) ».
- **Méthode affichée** : méthode, graine, date de préparation, part de variance expliquée pour la PCA.
- **Résultats de recherche** : les documents classés sont mis en évidence, les autres atténués ; un résultat absent de la carte (hors échantillon ou nature masquée) est signalé dans la table par « hors carte ».
- **Aucun lien** n'est tracé entre les points : la proximité vectorielle n'est ni un appel ni une dépendance. Une vue ProgramGraph serait une capacité distincte.
- **Aucun nom de groupe calculé** : la carte ne nomme pas de « clusters ». Les seuls libellés sont des métadonnées réelles (module, langage, nature).
- **Avertissement fixe** : « La position est une projection. Le classement est calculé sur les vecteurs d'origine. »

## 5. Recherche et scores

Modes proposés, repris des services existants :

| Mode | Service | Score affiché | Libellé |
|---|---|---|---|
| Sémantique | `SemanticSearchService` | similarité cosinus, de −1 à 1 | « similarité cosinus » |
| Hybride | `HybridSearchService` | score combiné de 0 à 1, plus les trois signaux | « score hybride (sémantique 50 %, lexical 35 %, graphe 15 %) » |

Règles d'affichage :

- Un score s'affiche avec son **nom** et son **intervalle**, en valeur décimale, jamais en pourcentage ni comme « pertinence » ou « confiance ».
- La **distance** cosinus (`1 − similarité`) n'est affichée que dans le détail, pour faire le lien avec pgAdmin où l'opérateur `<=>` rend une distance.
- Aucun **score de reranking** n'est affiché : MINOS n'a pas de reranker. La colonne n'existe pas.
- Chaque résultat porte provider, modèle, snapshot, et la mention « signal heuristique » (limite `VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT`).
- Les limitations rendues par le service sont listées telles quelles sous la barre de recherche.

**Documents similaires.** Depuis le détail, « Trouver les documents similaires » classe les documents du même index par similarité cosinus avec le vecteur **stocké** du document choisi. Aucun appel au provider : l'action reste disponible quand le provider est indisponible. Le document source est exclu des résultats. L'action est refusée quand l'index a changé depuis l'affichage.

## 6. Panneau de détail

1. Identité : fichier, lignes, symbole, nature, `stable_key`, `document_id`, empreinte du contenu.
2. **Contenu indexé** : le texte vectorisé, avec son origine selon la nature — « texte de synthèse du symbole », « extrait lu à l'indexation », « résumé du fichier ».
3. **Source actuelle** (nature `CHUNK` ou `SYMBOL` avec emplacement) : extrait relu sur le disque aux mêmes lignes, présenté dans un bloc distinct. Avertissement si l'extrait relu diffère du contenu indexé : « La source a changé depuis l'indexation (snapshot *s*). »
4. Provenance : provider, modèle, dimensions, snapshot indexé, date de construction de l'index.
5. Actions : « Trouver les documents similaires », « Copier la référence » (`fichier:lignes`).

Le code est toujours inséré comme texte ; aucune coloration ne passe par du HTML produit à partir du contenu.

## 7. États

| État | Déclencheur | Affichage | Action proposée |
|---|---|---|---|
| Aucun projet | registre vide | page d'accueil vide | commande d'inscription d'un projet |
| Aucun document | index présent, zéro document | table vide, carte vide | vérifier l'indexation du projet |
| Sémantique désactivée | état `DISABLED` | bandeau ; liste et carte indisponibles | clés `minos.semantic.*` à renseigner |
| Index absent | état `MISSING` | bandeau | commande `semantic-index-sync` |
| Aucun snapshot actif | état `NO_ACTIVE_SNAPSHOT` | bandeau | indexer le projet |
| Index obsolète | état `STALE` (snapshot ou modèle) | bandeau nommant la cause ; **liste, détail et voisins restent consultables**, la recherche est refusée (comportement du service) ; module et langage « non résolus » | commande `semantic-index-sync` |
| Provider indisponible | échec de vectorisation de la requête | recherche en erreur ; liste, carte et voisins disponibles | vérifier le provider |
| Projection absente | aucune projection pour cet index | carte remplacée par un message | commande `semantic projection prepare` |
| Projection en préparation | préparation en cours | progression si connue | attendre |
| Projection échouée | dernière préparation en échec | message et cause publique | relancer |
| Projection périmée | identité de l'index ≠ identité de la projection | carte **non affichée par défaut**, avec option « afficher la carte périmée » marquée comme telle | relancer la préparation |
| Aucun résultat | recherche sans résultat | message, filtres actifs rappelés | élargir les filtres |
| Snapshot changé pendant l'opération | limite `SEMANTIC_INDEX_CHANGED_DURING_QUERY` ou identité différente entre deux pages | résultats écartés, bandeau | relancer |
| Source introuvable | fichier absent ou illisible | bloc « source actuelle » remplacé par un message ; contenu indexé conservé | — |
| Accès refusé | jeton absent ou invalide, origine refusée | page d'erreur sans détail | relancer `minos explorer serve` |
| Corpus trop volumineux | documents au-delà du plafond de points | carte échantillonnée et annoncée ; liste inchangée (paginée) | préparer avec un autre plafond |

**Mode `local-hash`.** Quand le provider est `minos-local-hash`, un badge permanent indique : « Référence déterministe (hachage de tokens) — pas un modèle de langage ; la proximité ne reflète pas le sens. » La limite `LOCAL_HASH_EMBEDDING_NOT_LANGUAGE_MODEL` est reprise telle quelle.

## 8. Accessibilité et rendu

- Toute information portée par la couleur l'est aussi par un libellé (légende, infobulle, colonne de la table).
- La table est la voie d'accès complète au clavier ; la carte est une vue complémentaire.
- Aucune ressource externe : polices système, pas de CDN.
