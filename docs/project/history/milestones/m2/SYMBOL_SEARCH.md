# M2 — Recherche structurée de symboles

Statut : **premier incrément validé localement**

Date : **23 juillet 2026**

## Objectif

Ce premier incrément M2 stabilise la sémantique de recherche des symboles sans
faire dépendre le cœur MINOS d'un fournisseur ou d'un backend particulier.

Il couvre :

- la recherche lexicale par nom simple, nom qualifié ou clé logique ;
- les filtres exacts par nom qualifié, type de symbole et module ;
- un classement déterministe qui favorise les correspondances précises ;
- la récupération des symboles déclarés dans un fichier.

## Contrats

`SymbolSearchCriteria` porte les critères indépendants du backend :

```text
text?
qualifiedName?
kind?
moduleId?
limit
```

Au moins un critère est obligatoire et la limite doit être strictement
positive. Les critères fournis sont combinés.

`SymbolQueryService` expose désormais :

```text
findSymbol(projectId, text, limit)              compatibilité M0
findSymbols(projectId, criteria)                recherche structurée M2
getFileSymbols(projectId, fileId, limit)        symboles d'un fichier
```

Les variantes `findSymbolResults` et `getFileSymbolResults` retournent le DTO
compact documenté dans [`SYMBOL_RESULTS.md`](SYMBOL_RESULTS.md), sans modifier
les méthodes historiques utilisées par les preuves M0.

Le port `CodeKnowledgeStore` reste propriétaire du contrat de lecture. Aucun
type SCIP, Protobuf ou backend ne traverse cette frontière.

## Sémantique lexicale

La recherche `text` est insensible à la casse. Les résultats sont classés dans
l'ordre suivant :

1. nom qualifié exact ;
2. nom simple exact ;
3. préfixe du nom simple ;
4. préfixe du nom qualifié ;
5. sous-chaîne du nom simple ;
6. sous-chaîne du nom qualifié ;
7. sous-chaîne de `symbolKey`, pour préserver la compatibilité M0.

À rang égal, un symbole local précède un symbole externe et un symbole non
généré précède un symbole généré. Les derniers critères de tri sont stables :
nom qualifié, nom simple, signature puis identifiant.

Le filtre `qualifiedName` est une égalité exacte. MINOS ne fabrique toujours
pas de nom qualifié lorsqu'un fournisseur ne permet pas de le produire de
manière fiable. L'extraction des descripteurs SCIP globaux est documentée dans
[`SCIP_QUALIFIED_NAMES.md`](SCIP_QUALIFIED_NAMES.md).

## Symboles d'un fichier

`getFileSymbols` sélectionne les déclarations du projet et du fichier demandés,
puis les trie par ligne, colonne, nom qualifié, nom simple et identifiant. Les
symboles sans déclaration dans ce fichier, notamment les externes, sont exclus.

## Couverture

Les tests vérifient :

- la compatibilité de `findSymbol` ;
- le classement exact, local, préfixe et sous-chaîne ;
- la combinaison nom qualifié + type + module ;
- l'ordre source de `getFileSymbols` ;
- la conservation des usages M0 existants.

Validation locale :

```text
.\mvnw.cmd clean verify
55 sources main compilées
21 sources test compilées
51 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

## Clôture M2

Les contrats ont ensuite été validés sur `FileSymbolSnapshotStore`, reliés au
launcher produit et rejoués sur les quatre index TypeScript locaux. La décision
sur l'identité canonique, les surcharges fusionnées et les références non
cataloguées est consignée dans [`DECISION_M2.md`](DECISION_M2.md).
