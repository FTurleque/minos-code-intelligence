# M4 — Politique de tokens et benchmark

Statut : **validé localement**

Date : **23 juillet 2026**

## Estimateur

MINOS utilise une estimation locale indépendante des modèles :

```text
estimatedTokens = ceil(octets UTF-8 / 4)
```

Cette mesure n'est pas présentée comme le tokenizer exact d'un fournisseur de
LLM. Elle fournit une unité stable pour les limites, les comparaisons et les
tests hors ligne.

`estimatedTokensAvoided` additionne, pour chaque plage source incluse, la
différence entre le fichier complet et la plage retournée. La métrique ne
valorise pas artificiellement un fichier dont la source n'est pas disponible.

## Preuve réelle ciblée

Le snapshot M4 de `typescript-simple`, produit depuis
`scip-typescript 0.4.0`, contient 24 symboles, 100 occurrences et 5 relations.
La recherche exacte de `InMemoryUserRepository.findById` avec profondeur 2 a
retourné :

| Mesure | Résultat |
|---|---:|
| symboles racine | 1 |
| relations | 3 |
| usages | 0 |
| plage source | lignes 9–11 |
| tokens estimés de la plage | 19 |
| tokens estimés du fichier | 101 |
| tokens source évités | 82 |
| tokens estimés de la réponse | 540 |
| troncature | non |

Une seconde recherche de `UserRepository` a retrouvé 4 usages, 2 relations et
la source locale. `get-source` a relu explicitement le fichier complet dans un
nouveau processus CLI.

## Benchmark

Le harness `CodeSearchBenchmark` réutilise une instance de
`LocalProjectSymbolQuery`, le snapshot persistant et les fichiers réels. Après
20 warmups, 200 recherches lexicales `findById` ont donné :

| Mesure | Résultat |
|---|---:|
| p50 | 3,232 ms |
| p95 | 5,421 ms |
| p99 | 5,969 ms |
| résultats racine | 4 |
| tokens estimés | 1 417 |
| tokens source évités | 180 |
| troncature | non |

Ces nombres caractérisent cette machine et cette petite fixture ; ils ne sont
pas extrapolés à un grand dépôt. Ils sont néanmoins très inférieurs à la cible
locale p95 de 250 ms prévue pour une recherche de profondeur faible.
