# M2 — Noms qualifiés issus de SCIP

Statut : **incrément validé localement**

Date : **23 juillet 2026**

## Objectif

Cet incrément produit un `qualifiedName` MINOS interrogeable à partir des
symboles SCIP globaux, sans exposer l'identifiant fournisseur brut dans le
domaine et sans promouvoir prématurément l'identité à `CANONICAL`.

La [spécification officielle SCIP 0.7.1](https://github.com/scip-code/scip/blob/v0.7.1/scip.proto)
définit une chaîne de descripteurs standardisés et précise que leur ensemble
forme le nom pleinement qualifié d'un symbole dans son package.

## Périmètre du parseur

`ScipQualifiedNameExtractor` prend en charge :

- les quatre coordonnées précédant les descripteurs globaux : schéma,
  gestionnaire, package et version ;
- les espaces échappés dans les coordonnées de package ;
- les descripteurs namespace, type, terme, méthode, paramètre, paramètre de
  type, méta, macro et local ;
- les noms SCIP simples ou protégés par des backticks, y compris les backticks
  doublés ;
- les disambiguateurs de méthodes, qui ne sont pas affichés dans
  `qualifiedName` ;
- le rejet sans exception des symboles `local N` et des formes malformées.

## Adaptation Java et TypeScript

Pour Java, les descripteurs sont joints directement :

```text
com/minos/fixture/UserService#findUser(+1).
→ com.minos.fixture.UserService.findUser
```

Pour TypeScript et JavaScript, `scip-typescript` ajoute en tête le module
fichier. MINOS retire les namespaces jusqu'au premier descripteur de fichier
`.ts`, `.tsx`, `.js` ou variante :

```text
src/`user-service.ts`/UserService#findUser().
→ UserService.findUser
```

Le descripteur TypeScript `<constructor>` est rendu `constructor`, conformément
aux attentes normalisées des fixtures :

```text
UserService#`<constructor>`().
→ UserService.constructor
```

## Identité et prudence

Un nom qualifié fiable améliore la stabilité de la clé structurelle. Lorsque la
signature est disponible, la matière d'identité n'utilise plus le chemin ni la
position et reste stable après déplacement du fichier.

Cette amélioration ne suffit pas à déclarer une identité canonique
inter-fournisseurs :

- les signatures peuvent différer entre indexeurs ;
- certains index, notamment `scip-typescript 0.4.0`, ne fournissent pas de
  signature ;
- deux surcharges partagent volontairement le même `qualifiedName` visible.

MINOS conserve donc `STRUCTURAL_FALLBACK` pour les symboles projet et
`PROVIDER_SCOPED_FALLBACK` pour les symboles externes. Sans signature, la
position de déclaration continue de distinguer les surcharges éventuelles.

## Preuve sur les index TypeScript réels

Les quatre index locaux conservent leurs métriques d'ingestion :

| Fixture | Faits | Symboles | Occurrences | Résolues | Non résolues |
|---|---:|---:|---:|---:|---:|
| `typescript-simple` | 32 | 24 | 100 | 70 | 30 |
| `typescript-inheritance` | 25 | 18 | 57 | 39 | 18 |
| `typescript-modules` | 27 | 19 | 67 | 44 | 23 |
| `typescript-unresolved` | 10 | 9 | 18 | 14 | 4 |

Les recherches lexicales qualifiées retrouvent et classent en premier les
symboles attendus :

```text
UserService.findUser
UserService.constructor
getUserName
EntityBase.describe
GreetingService.greet
BrokenAdapter.execute
```

Les paramètres descendants peuvent également apparaître après le symbole exact
dans une recherche partielle. Le filtre structuré `qualifiedName` reste une
égalité exacte et retourne uniquement le symbole demandé.

## Couverture

Les tests couvrent :

- classes, méthodes, constructeurs et paramètres Java ;
- modules, fonctions, constructeurs et bibliothèques TypeScript ;
- coordonnées et noms échappés ;
- symboles locaux et entrées malformées ;
- recherche exacte par `qualifiedName` après ingestion ;
- stabilité de la clé structurelle après déplacement avec signature.

Validation locale :

```text
.\mvnw.cmd clean verify
56 sources main compilées
22 sources test compilées
56 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```
