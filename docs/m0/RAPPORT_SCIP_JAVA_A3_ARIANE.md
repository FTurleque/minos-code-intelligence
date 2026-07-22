# M0 — Rapport d'expérience SCIP Java A3 sur Ariane

Date : **22 juillet 2026**

Statut : **A3 exécutée — dépôt Maven/Quarkus réel indexé et ingéré dans MINOS**

## 1. Périmètre

Dépôt analysé :

```text
GitHub       FTurleque/ariane-chatbot
branche      develop
commit       b3ff8adfa458923fda1c39c0f5ff74db67d75716
état initial propre
```

Caractéristiques réellement rencontrées :

```text
Java projet                 release 17
JDK d'exécution             24.0.1
Maven                       3.9.16
Quarkus                     3.35.2
sources main Java           160
sources test Java            60
frontend                    Vue / Vite via Quinoa
scip-java                   0.13.1
SCIP CLI                    0.7.1
bindings Java MINOS         0.9.0
```

Commande :

```powershell
.\scripts\m0\run-scip-java.ps1 `
  -ProjectPath N:\workspace-dev\ariane-chatbot `
  -OutputDirectory .\.minos-m0\experiments\ariane-chatbot\scip-java
```

Les artefacts sont conservés dans MINOS. L'`index.scip` temporaire produit à
la racine d'Ariane a été comparé à sa copie par SHA-256 puis retiré. Le dépôt
Ariane est resté propre.

## 2. Build et génération de l'index

Le build déclenché par scip-java a réellement exécuté :

- génération de code Quarkus main et test ;
- compilation des 160 sources principales en `release 17` ;
- compilation des 60 sources de test en `release 17` ;
- packaging JAR ;
- installation NPM Quinoa ;
- type-check Vue ;
- build Vite ;
- augmentation Quarkus.

Résultat Maven :

```text
BUILD SUCCESS
temps Maven affiché       01:06 min
tests                     skipped par la commande scip-java -DskipTests
shards SCIP agrégés       220 / 220
taille index.scip         2 489 722 octets
```

Cette expérience qualifie la compilation nécessaire à l'indexation. Elle ne
constitue pas une nouvelle exécution de la suite de tests Ariane.

## 3. Post-traitements SCIP CLI

| Commande | Code | Résultat |
|---|---:|---|
| `scip lint` | 2 | panic sur l'ancien tableau `range` vide |
| `scip stats` | 0 | statistiques produites |
| `scip snapshot` | 2 | panic sur le tri des anciennes ranges |

Les 25 956 occurrences utilisent toutes une plage typée ; aucune n'utilise
l'ancien tableau `range`. A3 reproduit donc à l'échelle d'un dépôt réel
l'incompatibilité déjà mesurée sur A1/A2. Les diagnostics sont conservés dans
`lint.txt` et `snapshot.txt`.

## 4. Mesures fournisseur

```text
documents                              220
documents src/main                     160
documents src/test                      60
linesOfCode                          12 070
document bytes                    2 488 948
provider symbol entries               4 587
provider catalog symbol facts         4 587
provider occurrences                 25 956
provider definitions                  4 587
provider relationships                  353
typed ranges                         25 956
legacy ranges                             0
external symbol entries                   0
```

Les 2 295 réutilisations d'identifiants bruts `local n` entre documents sont
correctement séparées par la clé documentaire introduite après A1. Résultat :
4 587 faits distincts et **0 doublon de catalogue**.

Les 353 relations portent `isImplementation=true`; 157 portent également
`isReference=true`, notamment pour les méthodes qui implémentent ou redéfinissent
une autre méthode. Parmi leurs cibles, 168 sont présentes dans le catalogue et
185 ne le sont pas, principalement les super-types JDK/dépendances externes.

Exemples confirmés :

- `AllowAllDocumentAccessPolicy IMPLEMENTS DocumentAccessPolicy` ;
- `LocalKService IMPLEMENTS KRetriever` ;
- `LocalAnswerGenerator`, `ConfigurableAnswerGenerator` et
  `LangChain4jAnswerGenerator` implémentent `AnswerGenerator` ;
- `ConfigurableKSearchEngine`, `LocalKSearchEngine` et `KHybridSearchEngine`
  implémentent `KSearchEngine`.

Les 220 documents déclarent `UnspecifiedPositionEncoding`. MINOS conserve donc
`PositionEncoding.UNKNOWN` au lieu d'inférer silencieusement UTF-16.

## 5. Ingestion MINOS

Chaîne exécutée :

```text
index.scip
→ ScipIndexReader
→ ScipIngestionAdapter
→ InMemoryCodeKnowledgeStore
→ find_symbol / find_usages
```

Résultat :

```text
catalogSymbols                  4 587
normalizedSymbols               4 587
skippedSymbols                      0
occurrences                    25 956
resolvedOccurrences            14 000
unresolvedOccurrences          11 956
skippedOccurrences                  0
unresolvedOccurrenceRate      46,06 %
```

Décomposition des non-résolutions :

| Catégorie | Identifiants | Occurrences |
|---|---:|---:|
| Membres workspace présents seulement comme occurrences | 258 | 935 |
| Segments de package | 11 | 752 |
| JDK et dépendances externes | 653 | 10 269 |
| **Total** | **922** | **11 956** |

Les membres workspace concernés sont principalement des accessors synthétiques
de records, par exemple `KnowledgeSource#sourcePath()` ou
`DocChunk#content()`. scip-java émet leurs occurrences sans entrée
`SymbolInformation`. MINOS les garde donc non résolues : aucune identité métier
n'est inventée.

## 6. Requêtes représentatives

| Requête / symbole exact | Kind MINOS | Usages hors définition |
|---|---|---:|
| `ChatService` | `CLASS` | 22 |
| `AuthService` | `CLASS` | 24 |
| `ChatRequest` | `OTHER` | 19 |
| `DocumentAccessPolicy` | `INTERFACE` | 1 |
| `DocumentAccessDecision` | `OTHER` | 5 |
| `AdminDocumentIngestionService` | `CLASS` | 3 |
| `KRetriever` | `INTERFACE` | 13 |
| `AuthService.login` | `METHOD` | 3 |
| `AdminDocumentIngestionService.ingest` | `METHOD` | 1 |

Les positions `find_usages` traversent réellement les sources principales et
les tests. Par exemple, `ChatService` est retrouvé dans `ChatResource`,
`AdminSearchResource` et plusieurs tests de conversation.

Les records `ChatRequest` et `DocumentAccessDecision` sont publiés par
scip-java avec `UnspecifiedKind`; MINOS les conserve donc en `OTHER`. Cette
limitation A1 est confirmée sur le dépôt réel.

`ChatResource` ne possède aucun usage Java direct : son instanciation est gérée
par Quarkus. L'absence d'usage statique ne doit pas être transformée en faux
lien de runtime.

## 7. Profil qualité provisoire

```text
providerId                    scip-java
providerVersion               0.13.1
language                      Java
buildSystems                  Maven
validatedProjects             java-simple, java-24-smoke, ariane-chatbot
Java project releases         17, 24
Windows native support        non
Windows experimental support  oui, via adaptations locales M0
declarations                  observées sur 220/220 sources compilées
usages                        disponibles pour les symboles catalogués
implementations               relations fournisseur disponibles
call relationships            non émises explicitement
external symbol catalog       absent sur les trois index mesurés
position encoding             non spécifié par scip-java
verdict                       ADOPTER_AVEC_CONTRAINTES
```

Ce profil est provisoire : Ariane n'est pas une fixture à vérité terrain
exhaustive et ne permet donc pas d'annoncer une précision ou un rappel global.

## 8. Décision issue de A3

A3 confirme que la combinaison scip-java/MINOS est techniquement exploitable
sur un projet Maven/Quarkus réel, avec génération de code, frontend et
dépendances externes. Elle confirme aussi que les limites A1/A2 ne sont pas des
artefacts de petites fixtures :

- records sans kind précis ;
- symboles externes absents du catalogue ;
- accessors synthétiques présents seulement dans les occurrences ;
- encodage de position non déclaré ;
- absence de relations `CALLS` ;
- incompatibilité `lint`/`snapshot` de SCIP CLI 0.7.1.

La prochaine expérience Java recommandée est une fixture Maven multi-module,
suivie d'un projet partiellement compilable. Ces deux cas doivent être mesurés
avant de considérer le profil Maven suffisamment qualifié.
