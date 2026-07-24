# M0 — Rapport d'expérience SCIP TypeScript D2

Date : **22 juillet 2026**

Statut : **D2 exécutée — multi-projet et héritage exploitables, surcharges non distinguées**

## 1. Objectif et méthode

D2 complète la fixture simple D1 par trois vérités terrain écrites avant
l'indexation :

```text
typescript-modules      références de projets, cross-module, surcharges, tests
typescript-inheritance héritage, implémentations et overrides
typescript-unresolved  module absent et références non résolues
```

Chaque fixture suit le même chemin expérimental :

```text
expected.json
  -> build TypeScript indépendant
  -> scip-typescript 0.4.0
  -> scip lint / stats / snapshot
  -> ScipIngestionAdapter
  -> CodeKnowledgeStore
  -> find_symbol / find_usages
```

La release officielle `scip-typescript 0.4.0` parcourt les références de
projets TypeScript. Elle propose des modes workspace dédiés à Yarn et pnpm,
mais pas à npm. La fixture multi-projet utilise donc des références `tsconfig` ;
npm sert uniquement à installer TypeScript et à exécuter la vérité terrain.

Sources officielles vérifiées :

- release et documentation : <https://github.com/sourcegraph/scip-typescript/tree/v0.4.0> ;
- support npm workspaces ouvert : <https://github.com/sourcegraph/scip-typescript/issues/394> ;
- métadonnées de langage et kinds ouvertes : <https://github.com/sourcegraph/scip-typescript/issues/403>.

## 2. Environnement réellement exécuté

```text
Windows                     10
Node.js                     24.17.0
npm                         11.13.0
TypeScript fixtures         5.6.3
TypeScript de l'indexeur    5.9.3
scip-typescript             0.4.0
SCIP CLI                    0.7.1
bindings Java MINOS         0.9.0
JDK MINOS                   24.0.1
```

L'installation de l'indexeur et de SCIP CLI reste strictement locale sous
`.minos-m0/tools`. Aucun package global ni modification de `PATH` n'a été
effectué.

## 3. Builds de vérité terrain

| Fixture | Commande | Résultat |
|---|---|---|
| `typescript-modules` | `npm test` | succès |
| `typescript-inheritance` | `npm test` | succès |
| `typescript-unresolved` | `npm run build` | échec attendu `TS2307` |

L'échec contrôlé est exactement :

```text
Cannot find module '@missing/client' or its corresponding type declarations.
```

Il est conservé comme donnée de qualification. Malgré cette erreur de type,
`scip-typescript` produit un index partiel exploitable pour le fichier.

## 4. Indexation et post-traitements

| Mesure | modules | inheritance | unresolved |
|---|---:|---:|---:|
| `indexExitCode` | 0 | 0 | 0 |
| durée index (ms) | 841 | 765 | 767 |
| taille index (octets) | 10 611 | 10 839 | 3 790 |
| `scip lint` | 1 | 1 | 1 |
| `scip stats` | 0 | 0 | 0 |
| snapshot strict | 1 | 1 | 1 |
| snapshot non strict | 0 | 0 | 0 |

SHA-256 des index conservés :

```text
modules      7F41649A3CDAD442A3235C0A53D6E53D9347690042B55531E0E24854ABD87610
inheritance  759302DE9DDAEAF08759ED81020BE344F4787FD3CFBB9BEBEC35259A19808837
unresolved   CB7D9AD33C8BFC543BD7962930970A38620CE4F056E7508EE87F4FDC104599F1
```

Deux exécutions de `typescript-modules` ont produit le même SHA-256.

Le lint de `typescript-modules` signale deux répétitions de
`SymbolInformation` pour la méthode surchargée et six symboles de bibliothèque
TypeScript sans catalogue. Celui de `typescript-inheritance` signale huit
cibles de relations qu'il ne retrouve pas, alors que les onze identifiants
cibles sont bien présents byte-à-byte dans le catalogue et retrouvés par le
harness MINOS ; trois symboles `Error` externes sont également absents. Le lint
de `typescript-unresolved` signale trois occurrences `local 0` sans
`SymbolInformation`.

Ces anomalies restent visibles. Le succès du snapshot non strict ne transforme
pas les lint et snapshots stricts en succès.

## 5. Statistiques fournisseur et ingestion MINOS

| Mesure | modules | inheritance | unresolved |
|---|---:|---:|---:|
| documents | 4 | 6 | 1 |
| lignes | 38 | 40 | 13 |
| faits symboles fournisseur | 29 | 25 | 10 |
| définitions | 29 | 25 | 10 |
| occurrences | 67 | 57 | 18 |
| relations fournisseur | 4 | 11 | 2 |
| doublons catalogue | 2 | 0 | 0 |
| identifiants occurrence-only | 6 | 3 | 1 |
| occurrences occurrence-only | 6 | 3 | 3 |
| symboles catalogue MINOS | 27 | 25 | 10 |
| symboles normalisés MINOS | 19 | 18 | 9 |
| symboles ignorés | 8 | 7 | 1 |
| occurrences résolues | 44 | 39 | 14 |
| occurrences non résolues | 23 | 18 | 4 |
| taux brut de non-résolution | 34,33 % | 31,58 % | 22,22 % |

Les taux bruts incluent les symboles de module sans nom et les symboles de la
bibliothèque TypeScript absents du catalogue. Ils ne mesurent pas directement
le seul rappel workspace.

## 6. Comparaison — modules et surcharges

```text
symboles obligatoires présents               11 / 11
kinds exacts                                  0 / 11
cibles d'usage workspace présentes             7 / 7
relations extends/implements observables        2 / 2
cibles d'appels observables                     3 / 3
relations CALLS explicites                      0 / 3
ensembles de rôles attendus                     0 / 2
```

Les quatre documents des deux projets référencés sont indexés ensemble. Les
usages de `GreetingPort`, `GreetingBase.normalize` et `GreetingService.greet`
traversent correctement les limites de projets et restent résolus dans MINOS.

Les deux déclarations de surcharge et leur implémentation sont présentes, mais
elles partagent toutes l'identifiant :

```text
GreetingService#greet().
```

Mesure associée :

```text
providerMultiDefinitionSymbolIds    1
providerMaxDefinitionsPerSymbol     3
providerDuplicateCatalogEntries     2
```

Les signatures sont lisibles dans la documentation SCIP, mais ne portent pas
d'identité distincte. Les deux surcharges ne satisfont donc pas le critère M0
« symboles surchargés distingués ». MINOS ne doit ni inventer deux identités,
ni utiliser l'identifiant SCIP brut comme identité métier.

## 7. Comparaison — héritage

```text
symboles obligatoires présents               12 / 12
kinds exacts                                  0 / 12
cibles d'usage présentes                       6 / 6
relations structurelles attendues explicites   4 / 5
cible d'appel observable                       1 / 1
relations CALLS explicites                     0 / 1
```

Les relations explicites couvrent :

- `EntityBase -> Identified` ;
- `UserEntity -> EntityBase` et `UserEntity -> Named` ;
- `AdminEntity -> UserEntity` ;
- les overrides de `describe`.

Le fournisseur ajoute la fermeture vers les bases et interfaces héritées,
soit onze relations au total. Les onze cibles sont cataloguées. En revanche,
`Named extends Identified` n'est émis que comme occurrence de référence et non
comme relation. Le bit SCIP `isImplementation` ne permet pas non plus de
distinguer `extends` de `implements`.

L'appel `describeEntity -> EntityBase.describe` est une référence résolue,
mais n'est pas une relation `CALLS`.

## 8. Comparaison — dépendance non résolue

```text
symboles workspace obligatoires présents       6 / 6
relation BrokenAdapter -> Adapter               1 / 1
contextes non résolus attendus retrouvés        2 / 3
contexte supplémentaire observé                 import MissingClient
appel MissingClient.transform indexé            non
```

`MissingClient` est émis trois fois sous l'identifiant local opaque `local 0` :

- import ;
- type du constructeur ;
- instanciation.

Le type du constructeur et l'instanciation correspondent à deux des trois
contextes attendus. Aucun fait ni aucune occurrence n'est produit pour
`transform`, donc ce troisième contexte attendu ne peut pas être récupéré en
aval. MINOS conserve les trois occurrences `local 0` comme références non
résolues avec leur `ProviderReference` ; il ne leur invente ni nom, ni cible.

La quatrième occurrence non résolue MINOS est la définition du symbole de
module sans nom, ignoré par la normalisation. Elle est distincte des trois
références à la dépendance absente.

## 9. Rôles d'occurrence

Sur les 142 occurrences cumulées :

```text
providerMultiValuedRoleOccurrences    0
roles observés                        DEFINITION ou REFERENCE
IMPORT observé                        0
TEST observé                          0
```

Les imports sont de simples références et les définitions sous `test/` de
simples définitions. Le modèle multi-valué MINOS reste correct, mais ce profil
fournisseur ne produit pas les dimensions `IMPORT` et `TEST`. Elles ne doivent
pas être inférées silencieusement depuis le chemin ou la syntaxe source.

## 10. Verdict D2

```text
providerId                    scip-typescript
providerVersion               0.4.0
multiProjectReferences        SUPPORTED
workspaceReferences           SUPPORTED_AVEC_LINT
inheritanceRelationships      PARTIAL
implementationRelationships   SUPPORTED
overloadIdentity              UNSUPPORTED
occurrenceRoles               PARTIAL
unresolvedReferences          PARTIAL
callRelationships             UNSUPPORTED
kinds                         UNSUPPORTED
verdict                       ADOPTER_AVEC_CONTRAINTES
```

D2 confirme que le pipeline MINOS reste indépendant du langage et exploite
réellement un index multi-projet sans changement du domaine, du store ou des
services de requêtes. Elle ajoute trois contraintes mesurées au profil
fournisseur : surcharges fusionnées, rôles limités et membre d'une dépendance
absente non indexé.

Ces résultats ne justifient ni un parseur complet de la grammaire SCIP, ni une
inférence syntaxique dans le domaine. Ils doivent alimenter la comparaison des
backends et la décision M0.
