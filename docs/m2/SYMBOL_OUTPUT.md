# M2 — Rendus TEXT et JSON des symboles

Statut : **incrément validé localement**

Date : **23 juillet 2026**

## Objectif

Cet incrément rend `SymbolResult` exploitable par un humain et par une machine
sans sérialiser directement les entités du store. Il fournit deux formats
déterministes et sans dépendance JSON externe : `TEXT` et `JSON`.

## Contrats

`SymbolOutputFormat` expose :

```text
TEXT
JSON
```

`SymbolOutputFormat.parse` accepte la casse et les espaces autour de `text` ou
`json`, puis rejette explicitement toute autre valeur.

`SymbolResultRenderer.render(results, format)` :

- copie la liste d'entrée avant le rendu ;
- conserve l'ordre produit par la requête ;
- refuse une liste, un élément ou un format nul ;
- ne lit aucun fichier source ;
- ne réintroduit aucune `ProviderReference` opaque.

## Format TEXT

La première ligne porte le nombre de résultats :

```text
symbols: 1
```

Chaque symbole utilise ensuite un bloc aux champs fixes. Les chaînes sont
guillemétées et échappées, les enums et booléens restent lisibles, et une valeur
absente est toujours rendue par `null`.

`location` et `origin` utilisent des blocs imbriqués afin de conserver
l'encodage des positions et la provenance complète sans ambiguïté.

Une liste vide produit exactement :

```text
symbols: 0
```

## Format JSON

Le JSON utilise une enveloppe stable :

```json
{"count":1,"symbols":[...]}
```

Les propriétés suivent toujours le même ordre. `location` et `origin` sont des
objets imbriqués, et les champs optionnels sont présents avec la valeur `null`.
Une liste vide produit exactement :

```json
{"count":0,"symbols":[]}
```

L'échappement couvre :

- guillemets et antislashs ;
- retours ligne, tabulations et contrôles JSON ;
- séparateurs Unicode `U+2028` et `U+2029` ;
- surrogates UTF-16 isolés provenant de métadonnées malformées ;
- paires de surrogates valides et autres caractères Unicode sans perte.

## Déterminisme

Le renderer ne trie pas une seconde fois les résultats : le classement du
`CodeKnowledgeStore` et du `SymbolQueryService` reste la source de vérité. À
entrée identique, les sorties TEXT et JSON sont identiques octet pour octet au
niveau de la chaîne Java produite.

## Couverture

Les tests golden vérifient la sortie complète TEXT et JSON. Les tests de bord
couvrent également :

- symbole externe sans module, fichier, emplacement ni version fournisseur ;
- Unicode, contrôles, retours ligne et surrogate isolé ;
- liste vide et plusieurs résultats ;
- conservation de l'ordre d'entrée ;
- parsing des formats et arguments invalides ;
- chaîne complète requête → DTO → JSON, sans identifiant fournisseur opaque ;
- frontière fournisseur étendue au package `output`.

## Validation locale

```text
.\mvnw.cmd clean verify
59 sources main compilées
23 sources test compilées
64 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

## Suite

La commande `find-symbol` consomme désormais `SymbolQueryService`,
`SymbolResult` et `SymbolResultRenderer` via un port projet séparé. La prochaine
tranche doit implémenter ce port avec le registre et le backend persistant, puis
fournir le launcher système.
