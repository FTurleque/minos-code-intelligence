# Dérivation des tests liés

## Contrat

M5 matérialise un test lié par une relation normalisée :

```text
test symbol --RELATED_TEST--> production symbol
```

Cette orientation suit le modèle de domaine. La requête
`get_related_tests(productionSymbol)` est donc une recherche entrante sur le
symbole de production.

La dérivation reste indépendante de SCIP. L'adaptateur lui fournit uniquement
des `Symbol`, `SymbolOccurrence` et `Relationship` MINOS déjà normalisés.

## Détection des fichiers et ancrage

Un fichier est considéré comme test lorsqu'un des motifs suivants est présent :

- `src/test/`, `test/`, `tests/` ou `__tests__/` ;
- suffixe de fichier `.test.<extension>` ou `.spec.<extension>`.

MINOS choisit ensuite un symbole de test adressable :

1. conteneur de type classe, record, struct, trait ou enum ;
2. fonction ;
3. symbole unique `OTHER`, repli nécessaire pour les fonctions publiées avec
   `UnspecifiedKind` par `scip-typescript 0.4.0`.

Lorsqu'un fichier contient plusieurs conteneurs au même niveau de priorité, les
occurrences du fichier ne leur sont pas attribuées arbitrairement. Une relation
reste possible si un fait fournisseur identifie directement sa source ou si
une convention de nommage distingue un conteneur.

## Signaux et preuves

| Signal | Type de preuve | Poids | Effet |
|---|---|---:|---|
| emplacement de test | `TEST_LOCATION` | 0,10 | prérequis commun |
| même package/namespace | `PACKAGE_PROXIMITY` | 0,20 | renforcement uniquement |
| suffixe/nom de fichier correspondant | `NAMING_CONVENTION` | 0,55 | candidat heuristique |
| occurrence résolue non-définition | `DIRECT_REFERENCE` | 0,65 | relation dérivée |
| fait fournisseur `CALLS` | `DIRECT_CALL` | 0,80 | relation dérivée |

Le nommage reconnaît notamment `ServiceTest`, `ServiceSpec` et
`service.spec.ts` face à `Service`. La proximité de package ou namespace ne
crée jamais une relation à elle seule.

MINOS n'invente pas un appel à partir d'une simple occurrence. `DIRECT_CALL`
n'est ajouté que lorsqu'une relation factuelle `CALLS` est disponible. Une
occurrence résolue reste une `DIRECT_REFERENCE`.

## Score de confiance

Chaque type de signal ne contribue qu'une fois au score, même si plusieurs
occurrences du même type sont conservées comme preuves. Le score agrège les
poids par complément d'incertitude :

```text
confidence = 1 - product(1 - signalWeight)
```

Le résultat est arrondi à trois décimales. Exemples :

| Signaux | Confiance |
|---|---:|
| nommage + package + emplacement | 0,676 |
| référence + nommage + emplacement | 0,858 |
| référence + nommage + package + emplacement | 0,887 |
| appel + package + emplacement | 0,856 |

Une référence ou un appel direct donne une relation `DERIVED` et `RESOLVED`.
Un lien fondé uniquement sur le nommage reste `HEURISTIC` avec le statut
`HEURISTIC`, même si les deux symboles existent dans le catalogue.

## Explicabilité et déterminisme

Chaque relation produite conserve :

- une origine `minos / RELATED_TEST_DERIVATION / M5` ;
- son statut et sa nature ;
- sa confiance ;
- toutes les preuves structurées avec source, cible, emplacement et poids.

Les symboles, occurrences, faits et preuves sont triés avant dérivation. L'ID
de relation est un SHA-256 stable du projet, du symbole de test, du symbole de
production et de `RELATED_TEST`. Une même entrée produit donc le même snapshot.

## Persistance

`ScipIngestionAdapter` exécute M5 après la normalisation des occurrences et des
faits fournisseur, puis publie les relations avec les dépendances M3. Le format
de snapshot v2 existant stocke déjà nature, résolution, confiance, origine et
preuves ; aucune fuite d'un type fournisseur n'est nécessaire.

Le rapport d'import expose `relatedTestRelationshipCount`. Le champ
`derivedRelationshipCount` couvre désormais toutes les relations ajoutées par
MINOS pendant l'ingestion, dont `DEPENDS_ON` et `RELATED_TEST`.

## Limites assumées

- une référence de fichier n'est attribuée automatiquement qu'à un ancrage de
  test unique ;
- un appel absent de l'index reste absent de MINOS ;
- la proximité de namespace est stricte, sans distance floue entre packages ;
- les tests paramétrés ou générés sans symbole adressable ne sont pas inventés ;
- la relation indique qu'un test est lié, pas qu'il couvre exhaustivement le
  comportement du symbole.
