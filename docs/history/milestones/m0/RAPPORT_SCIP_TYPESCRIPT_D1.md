# M0 — Rapport d'expérience SCIP TypeScript D1

Date : **22 juillet 2026**

Statut : **D1 exécutée — index exploitable avec limitations fournisseur**

## 1. Objectif et environnement

D1 qualifie le même chemin conceptuel que la baseline Java sur une fixture
TypeScript contrôlée :

```text
typescript-simple
  -> scip-typescript
  -> index.scip
  -> ScipIngestionAdapter
  -> CodeKnowledgeStore
  -> find_symbol / find_usages
```

La vérité terrain a été écrite dans
`fixtures/typescript/typescript-simple/expected.json` avant la mesure.

```text
Windows                     10
Node.js                     24.17.0
npm                         11.13.0
TypeScript fixture          5.6.3
scip-typescript             0.4.0
TypeScript de l'indexeur    5.9.3
SCIP CLI                    0.7.1
bindings Java MINOS         0.9.0
JDK MINOS                   24.0.1
```

La release officielle qualifiée est `scip-typescript 0.4.0`. Sa documentation
annonce Node.js 18 et 20 ; le run sous Node.js 24.17.0 est donc un résultat
expérimental positif, pas une extension de la matrice de support amont.

Sources amont vérifiées :

- release : <https://github.com/sourcegraph/scip-typescript/releases/tag/v0.4.0> ;
- dépôt et matrice Node.js : <https://github.com/sourcegraph/scip-typescript> ;
- manifeste publié : <https://raw.githubusercontent.com/sourcegraph/scip-typescript/v0.4.0/package.json>.

L'installation npm est locale à `.minos-m0/tools/scip-typescript`. Aucun
package global ni modification du `PATH` utilisateur n'est nécessaire.

## 2. Fixture indépendante

La fixture contient cinq sources principales et un test. Elle couvre :

- alias de type, enum et interface ;
- interface de repository et implémentation ;
- classe, constructeur et méthodes ;
- fonction exportée ;
- références cross-file ;
- trois appels ;
- test exécutable.

Commandes :

```powershell
npm ci --no-audit --no-fund
npm test
```

Résultat : **succès** avec TypeScript 5.6.3 et Node.js 24.17.0.

## 3. Indexation et post-traitements

```text
indexExitCode                       0
indexProduced                    true
indexDurationMs                  1 120
indexBytes                      14 546
SHA-256       58120ED6CD1A092CB4C8B592976F6E27BC6611E7FB6D0BD09983C1820A8D718B
```

Deux régénérations contrôlées ont produit le même SHA-256. Le runner restaure
également byte-à-byte un `index.scip` qui préexistait à la racine du projet.

| Phase | Code | Résultat |
|---|---:|---|
| `scip-typescript index` | 0 | index produit |
| `scip lint` | 1 | 8 symboles de bibliothèque TypeScript sans `SymbolInformation` |
| `scip stats` | 0 | statistiques produites |
| `scip snapshot` strict | 1 | erreur sur les 6 documents |
| `scip snapshot --strict=false` | 0 | 6 snapshots produits |

Le runner conserve les deux résultats de snapshot. Le succès non strict ne
masque ni l'échec du lint ni celui du mode strict.

Les huit erreurs de lint correspondent à `Map`, `Map.get` et `Error` dans les
fichiers de bibliothèque TypeScript 5.9.3. Elles sont retrouvées exactement
comme identifiants occurrence-only par la baseline MINOS.

## 4. Statistiques fournisseur

```text
documents                               6
documents main                          5
documents test                          1
linesOfCode                            49
providerSymbolEntries                  32
providerOccurrences                   100
providerDefinitions                    32
providerRelationships                   2
providerImplementationRelationships     2
providerExternalSymbolEntries           0
providerDuplicateCatalogEntries         0
providerOccurrenceOnlySymbolIds         8
providerOccurrenceOnlyOccurrences       8
positionEncoding unspecified            6
```

`scip-typescript 0.4.0` ne renseigne sur cette fixture ni le langage du
document, ni `display_name`, ni le kind des `SymbolInformation`. L'adaptateur
MINOS applique donc un repli borné :

- langage déduit de l'extension `.ts` ;
- nom déduit du dernier descripteur de l'identifiant SCIP global ;
- kind conservé comme `OTHER` ;
- symboles locaux et symboles de module sans nom non inventés ;
- identifiant SCIP brut conservé uniquement comme `ProviderReference`.

Ce repli ne parse pas la grammaire SCIP complète et ne produit pas de
`qualifiedName` canonique.

## 5. Comparaison à la vérité terrain

```text
symboles obligatoires présents         12 / 12
kinds communs exacts                    0 / 12
cibles d'usage présentes                9 / 9
implémentation de classe                 1 / 1
relations d'implémentation fournisseur   2
cibles d'appels observables              3 / 3
relations CALLS explicites               0 / 3
doublons de catalogue                    0
```

Les douze déclarations attendues sont distinguables, y compris les deux
méthodes `findById` portées par l'interface et l'implémentation. Les kinds ne
peuvent pas satisfaire la vérité terrain puisque le fournisseur publie
`UnspecifiedKind` pour les 32 entrées.

La relation explicite attendue :

```text
InMemoryUserRepository IMPLEMENTS UserRepository
```

est présente. Le fournisseur ajoute une relation d'implémentation/référence
entre les deux méthodes `findById` ; ce n'est pas un doublon de symbole.

Les trois appels attendus sont observables comme références résolues aux
emplacements exacts :

```text
UserService.findUser       -> UserRepository.findById   ligne 8
getUserName                -> UserService.findUser      ligne 5
findsExistingUser          -> getUserName               ligne 7
```

Ils ne sont pas émis comme relations `CALLS`. La capacité graphe d'appels doit
donc rester `UNSUPPORTED` pour ce profil.

## 6. Ingestion MINOS

```text
catalogSymbols                         32
normalizedSymbols                      24
skippedSymbols                          8
occurrences                           100
resolvedOccurrences                    70
unresolvedOccurrences                  30
skippedOccurrences                      0
unresolvedOccurrenceRate             30 %
```

Les huit symboles ignorés sont six symboles de module/fichier sans nom et deux
symboles locaux sans nom. Leurs occurrences représentent 22 des 30
non-résolutions ; les 8 autres sont les symboles de bibliothèque absents du
catalogue fournisseur signalés par `scip lint`.

Toutes les cibles workspace demandées par `expected.json` sont résolues et
interrogeables. `find_symbol` retrouve les douze symboles métier et
`find_usages` retrouve les neuf cibles d'usage attendues. La recherche actuelle
est volontairement partielle : une requête large telle que `User` retourne
aussi les noms qui contiennent ce terme.

## 7. Impact architectural

D1 n'a nécessité aucune modification des concepts fondamentaux du domaine, du
store ou des services de requêtes. Le seul ajustement se situe dans
`adapter.scip` et absorbe une omission de métadonnées fournisseur.

Les observations TypeScript confirment les décisions déjà issues de Java :

- rôles d'occurrence multi-valués ;
- encodage de position explicite avec `UNKNOWN` possible ;
- identités fournisseur séparées ;
- qualité d'identité structurelle explicite ;
- références non résolues conservées comme telles ;
- métadonnées/capacités qualifiées par fournisseur.

Ces concepts sont consolidés dans `docs/architecture/MODELE_DOMAINE.md`.

## 8. Verdict fournisseur D1

```text
providerId             scip-typescript
providerVersion        0.4.0
language               typescript
buildSystems           npm / tsconfig
symbolDefinitions      PARTIAL
references             PARTIAL
implementations        SUPPORTED
callRelationships      UNSUPPORTED
positionEncoding       UNSPECIFIED
supportedEnvironment   Windows 10, Node.js 24.17.0 mesuré
verdict                 ADOPTER_AVEC_CONTRAINTES
```

L'indexeur est suffisant pour valider l'agnosticisme du pipeline MINOS et les
requêtes de base sur la fixture. Il ne satisfait pas encore la porte de
précision des kinds, le lint strict ni une capacité de graphe d'appels.
