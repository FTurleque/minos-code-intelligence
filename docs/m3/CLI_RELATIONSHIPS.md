# M3 — CLI usages et relations

Statut : **validé localement**
Date : **23 juillet 2026**

## Commandes

La CLI locale sert le snapshot actif d'un projet par UUID ou nom d'affichage :

```text
minos find-usages <project> <symbol-id> [--limit N] [--format text|json]
minos find-implementations <project> <symbol-id> [options]
minos find-callers <project> <symbol-id> [options]
minos find-callees <project> <symbol-id> [options]
minos dependencies <project> <symbol-id> [options]
minos dependents <project> <symbol-id> [options]
```

La limite vaut 20 par défaut, doit être comprise entre 1 et 1000 et est
appliquée après filtres et tri. Les sorties `text` et `json` sont déterministes.
Les erreurs de syntaxe retournent `2`, les erreurs d'exécution `1`, un résultat
vide réussi retourne `0`.

## Sémantique

| Commande | Direction | Kind |
|---|---|---|
| `find-implementations` | entrante | `IMPLEMENTS` |
| `find-callers` | entrante | `CALLS` |
| `find-callees` | sortante | `CALLS` |
| `dependencies` | sortante | `DEPENDS_ON` |
| `dependents` | entrante | `DEPENDS_ON` |

`find-usages` ne retourne que les occurrences résolues qui ne sont pas des
définitions. Les résultats relationnels conservent résolution, nature,
confiance, origine et preuves ; une dépendance dérivée reste donc distinguable
d'un fait fournisseur.

`CALLS` est une capacité conditionnelle. Quand le snapshot n'en contient pas,
`find-callers` et `find-callees` retournent une collection vide réussie. MINOS
ne transforme pas une occurrence de référence en appel sans fait qualifié.

## Preuve en processus distincts

Un vrai index `scip-typescript 0.4.0` de `typescript-simple` a été importé dans
un home temporaire, puis interrogé via le JAR produit, donc sans état mémoire de
l'importeur :

| Probe | Résultat |
|---|---:|
| recherche exacte de `UserRepository` | symbole retrouvé |
| `find-usages UserRepository` | 4 |
| `find-implementations UserRepository` | 1 |
| `dependencies InMemoryUserRepository` | 1 |
| `dependents UserRepository` | 1 |
| `find-callers InMemoryUserRepository` | 0, succès |
| `find-callees InMemoryUserRepository` | 0, succès |

La relation d'implémentation est factuelle et d'origine SCIP. La dépendance est
`DERIVED`, de confiance `1.0`, d'origine `DERIVED_BY_MINOS`, avec une preuve
`DERIVATION_PATH` pointant vers le fait `IMPLEMENTS`.
