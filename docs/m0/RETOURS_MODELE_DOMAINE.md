# M0 — Retours sur le modèle de domaine

Date : **22 juillet 2026**

Statut : **retours expérimentaux à réintégrer dans le modèle de référence avant sortie M0**

## Objet

L'implémentation de la première baseline SCIP a révélé plusieurs précisions nécessaires au modèle de domaine validé pendant C0.

Ces changements ne remettent pas en cause les principes C0. Ils les rendent suffisamment précis pour représenter les faits réellement produits par un indexeur sémantique.

## 1. Une occurrence possède plusieurs rôles

Le modèle initial prévoyait des rôles d'occurrence, mais l'ingestion SCIP confirme qu'ils doivent être représentés comme un **ensemble** et non comme une valeur unique.

SCIP représente `symbol_roles` comme un bitset.

Exemple possible :

```text
REFERENCE + IMPORT + READ
```

ou :

```text
DEFINITION + TEST
```

Décision M0 :

```text
SymbolOccurrence.roles : Set<OccurrenceRole>
```

## 2. Les colonnes ont un encodage explicite

Les ranges SCIP peuvent exprimer les colonnes en :

```text
UTF-8 code units
UTF-16 code units
UTF-32 code units
```

Cet encodage est porté par le `Document` SCIP.

Le perdre rendrait les colonnes ambiguës pour des fichiers contenant des caractères non ASCII.

Décision M0 :

```text
SymbolLocation
- fileId
- startLine
- startColumn
- endLine
- endColumn
- positionEncoding
```

Les lignes MINOS sont normalisées en base 1 ; les colonnes restent des offsets base 0 dans l'unité déclarée.

L'index réel Ariane confirme qu'un fournisseur peut laisser cet encodage non
spécifié : les 220 documents scip-java portent
`UnspecifiedPositionEncoding`. MINOS doit alors conserver `UNKNOWN` et ne pas
inférer silencieusement UTF-16.

## 3. Les identités fournisseur doivent être conservées séparément

L'identifiant SCIP brut ne doit devenir ni :

```text
Symbol.id
Symbol.symbolKey
Symbol.qualifiedName
```

sans normalisation explicite.

Décision M0 :

```text
ProviderReference
- providerId
- externalId
```

Un symbole ou une occurrence peut conserver plusieurs références fournisseur pour la traçabilité.

## 4. Qualité de l'identité logique

La première ingestion ne dispose pas encore d'un parseur Java validé de la grammaire complète des symboles SCIP.

MINOS doit donc dire explicitement avec quelle force un `symbolKey` a été construit.

```text
CANONICAL
STRUCTURAL_FALLBACK
PROVIDER_SCOPED_FALLBACK
```

### `CANONICAL`

Identité logique reconstruite indépendamment du fournisseur.

### `STRUCTURAL_FALLBACK`

Identité déterministe calculée à partir des données structurelles disponibles, par exemple :

```text
project
language
kind
relativePath
displayName
signature
declarationLocation
```

Elle est exploitable pendant M0 mais peut changer si le fichier ou la déclaration se déplace.

### `PROVIDER_SCOPED_FALLBACK`

Repli nécessaire pour certains symboles externes tant qu'une identité canonique ne peut pas être reconstruite.

Cette qualité **ne doit jamais être utilisée pour réconcilier automatiquement deux fournisseurs**.

## 5. Une occurrence peut viser une cible non résolue

Le modèle initial du code imposait un `symbolId` à chaque `SymbolOccurrence`.

Cela obligeait soit à supprimer les références non résolues, soit à inventer un faux symbole.

Les deux comportements sont interdits par le cahier des charges.

Décision M0 :

```text
SymbolReference
  ├── ResolvedSymbolReference
  └── UnresolvedSymbolReference
```

Une cible non résolue peut conserver :

- un nom d'affichage éventuel ;
- un candidat de nom qualifié éventuel ;
- le langage ;
- la raison de non-résolution ;
- les `ProviderReference` disponibles.

Cela permet de mesurer :

```text
unresolvedOccurrenceRate
```

sans fausser les résultats.

A3 confirme cette nécessité à l'échelle réelle : sur 25 956 occurrences,
11 956 visent un identifiant absent du catalogue fournisseur. Parmi elles, 935
concernent des membres workspace synthétiques, principalement des accessors de
records, et 10 269 des symboles JDK ou de dépendances. Les transformer en
symboles résolus aurait produit des faits non justifiés.

## 6. Les relations doivent pouvoir viser un workspace

Le document de domaine C0 autorisait conceptuellement :

```text
WORKSPACE
PROJECT
MODULE
SOURCE_FILE
SYMBOL
```

mais la première implémentation de `CodeEntityType` avait omis `WORKSPACE`.

L'énumération a été réalignée.

## 7. Normalisation SCIP en deux temps

La baseline retient :

```text
Index SCIP
   │
   ▼
ScipSymbolCatalog
   │
   ├── ScipSymbolFact
   └── identités fournisseur opaques
   │
   ▼
ScipSymbolNormalizer
   │
   ▼
Symbol MINOS
```

Cette séparation évite d'introduire les types Protobuf ou la grammaire SCIP dans le domaine.

## 8. Conséquence sur le parseur d'identifiants SCIP

Le dépôt SCIP fournit un parseur de référence riche dans ses bindings Go, mais les bindings Java générés n'exposent pas l'équivalent comme utilitaire de haut niveau.

M0 ne porte donc pas immédiatement ce parseur complet.

La décision sur un port Java dépendra de l'observation des index réels :

- si `qualifiedName` canonique est indispensable pour les portes M0, le parseur sera porté et testé contre les fixtures SCIP ;
- sinon la baseline structurelle restera suffisante pour mesurer l'ingestion, puis le port pourra être planifié séparément.

D1 apporte le cas concret attendu : `scip-typescript 0.4.0` ne remplit pas
`display_name` pour ses 32 faits. Un extracteur limité au dernier descripteur
global est donc justifié dans l'adaptateur pour récupérer un nom interrogeable.
Il refuse les identifiants `local N` et les symboles de module sans descripteur,
ne produit pas de nom qualifié et ne transforme jamais l'identifiant SCIP brut
en identité métier. Le port de la grammaire complète reste hors de M0.

## 9. Les identifiants SCIP locaux sont portés par le document

L'index réel `java-simple` réutilise les identifiants bruts :

```text
local 0
local 1
```

dans plusieurs documents. Ils ne désignent pas le même symbole et ne sont pas
des doublons fournisseur : la portée d'un symbole SCIP local est le document.

La première version du catalogue, indexée uniquement par `rawSymbol`, réduisait
32 faits à 24 entrées et pouvait relier une occurrence locale au symbole du
mauvais fichier. La mesure réelle a conduit à corriger la clé interne :

```text
symbole global : rawSymbol
symbole local  : document.relativePath + rawSymbol
```

Cette clé reste strictement interne à l'adaptateur. Elle ne devient ni
`Symbol.id`, ni `Symbol.symbolKey`, et ne fait pas fuiter la grammaire SCIP dans
le domaine. Après correction, A1 conserve 32 faits distincts et ne produit
aucun doublon de catalogue. A3 confirme la règle avec 2 295 réutilisations
d'identifiants locaux entre 220 documents, 4 587 faits conservés et toujours
aucun doublon de catalogue.

## 10. Un shard intermédiaire n'est pas un index validé

A5 montre que `scip-java` peut produire un shard par source, y compris pour une
source contenant des erreurs, puis refuser l'agrégation finale lorsque Maven
échoue. Ces shards sont lisibles comme messages SCIP mais leurs identifiants ne
sont pas encore réécrits dans la forme finale `scip-java maven ...` et les
références cross-shard restent non réconciliées.

Décision M0 provisoire :

```text
index fournisseur final  -> admissible pour ingestion standard
shards intermédiaires    -> diagnostic uniquement
```

Le domaine ne doit pas inventer un `IndexSnapshot` sain à partir d'une phase
fournisseur échouée. Un éventuel mode best-effort devra porter explicitement
son état incomplet, son origine et ses limitations. Aucun nouveau type métier
n'est introduit avant cette décision.

## 11. Effet sur les principes C0

Aucun principe structurant n'est invalidé.

Au contraire, ces corrections renforcent :

- l'explicabilité ;
- la gestion explicite de l'incertitude ;
- l'indépendance vis-à-vis des fournisseurs ;
- la possibilité de mesurer les non-résolutions ;
- le support multi-langages ;
- la reproductibilité des positions source.

## 12. Confirmation par le second écosystème

D1 TypeScript utilise les mêmes `Symbol`, `SymbolOccurrence`,
`SymbolReference`, `ProviderReference`, `SymbolLocation`, store et services de
requêtes que Java. Aucun branchement TypeScript n'est ajouté au domaine.

L'expérience confirme aussi qu'un second fournisseur peut omettre davantage
de métadonnées : les 32 kinds et les 6 encodages sont non spécifiés. Le modèle
commun doit préserver `OTHER` et `UNKNOWN` plutôt que d'inférer une précision
depuis la syntaxe source.

## Consolidation

Les éléments confirmés par les index réels Java et TypeScript sont intégrés à
`docs/architecture/MODELE_DOMAINE.md` le 22 juillet 2026. Les choix qui restent
ouverts — identité canonique, parser SCIP complet et backend persistant —
conservent leur statut expérimental ou futur.
