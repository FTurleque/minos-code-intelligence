# M0 — Rapport d'expérience SCIP Java A4/A5

Date : **22 juillet 2026**

Statut : **A4 multi-module validée ; A5 partiellement compilable mesurée**

## 1. Objectif

Les expériences complètent A1/A2/A3 avec deux risques Maven encore non
qualifiés :

- A4 : références entre modules d'un même reactor ;
- A5 : dépendance absente et échec de compilation après un module sain.

Environnement commun :

```text
Windows                     10
JDK                         24.0.1
Maven                       3.9.16
scip-java                   0.13.1
SCIP CLI                    0.7.1
bindings Java MINOS         0.9.0
```

Les vérités terrain ont été écrites avant l'indexation :

```text
fixtures/java/java-multi-module/expected.json
fixtures/java/java-partial-compile/expected.json
```

## 2. A4 — Fixture Maven multi-module

Le reactor contient :

```text
java-multi-module
├── api   : UserProfile, GreetingPort
└── app   : DefaultGreetingPort, GreetingService, GreetingServiceTest
```

La fixture couvre un record et une interface déclarés dans `api`, une
implémentation et des appels dans `app`, puis un test qui traverse les deux
modules.

### 2.1 Validation Maven indépendante

Commande :

```powershell
.\mvnw.cmd -f .\fixtures\java\java-multi-module\pom.xml clean verify
```

Résultat :

```text
reactor modules             3 / 3 SUCCESS
sources main                4
sources test                1
tests                       1 réussi, 0 échec
release Java                17
BUILD SUCCESS
```

### 2.2 Indexation scip-java

```text
indexExitCode               0
indexProduced               true
indexDurationMs             9 723
indexBytes                  10 987
shardCount                  5
shardBytes                  8 393
```

Deux exécutions consécutives ont produit le même SHA-256 :

```text
CDC7B55FFC8749C69976F1299C93B5D3A45524C28DAC28A8B61B70B3AD180155
```

Le déterminisme byte-à-byte est donc confirmé sur un rerun contrôlé de cette
fixture. Il ne constitue pas encore une campagne p50/p95.

Post-traitements :

| Commande | Code | Résultat |
|---|---:|---|
| `scip lint` | 2 | panic connu sur les plages typées |
| `scip stats` | 0 | statistiques produites |
| `scip snapshot` | 2 | panic connu sur les plages typées |

### 2.3 Mesures fournisseur et MINOS

```text
documents                               5
documents main                          4
documents test                          1
linesOfCode                            39
providerSymbolEntries                  21
providerOccurrences                    94
providerDefinitions                    21
providerRelationships                   4
providerExternalSymbolEntries           0
providerDuplicateCatalogEntries         0
providerTypedRanges                     94
providerLegacyRanges                     0
catalogSymbols                         21
normalizedSymbols                      21
resolvedOccurrences                    42
unresolvedOccurrences                  52
unresolvedOccurrenceRate          55,32 %
```

Le taux brut de non-résolution se décompose ainsi :

| Catégorie | Occurrences |
|---|---:|
| Segments de package | 38 |
| JDK et JUnit | 13 |
| accessor synthétique `UserProfile#name()` | 1 |
| **Total** | **52** |

Les références workspace attendues entre `api` et `app` sont résolues. Le
taux brut élevé provient surtout de la petite taille de la fixture et des
segments de package absents du catalogue.

### 2.4 Comparaison à `expected.json`

```text
symboles obligatoires présents        10 / 10
kinds communs exacts                   9 / 10
doublons de catalogue                   0
implémentation attendue                 1 / 1
cibles d'appels observables             2 / 2
relations CALLS explicites              0 / 2
```

Le seul écart de kind est le record `UserProfile`, publié avec
`UnspecifiedKind` puis conservé comme `OTHER` par MINOS. Il confirme A1 et A3.

La relation :

```text
DefaultGreetingPort IMPLEMENTS GreetingPort
```

est explicite et traverse les modules. Les appels :

```text
GreetingService.greet -> GreetingPort.greet
GreetingServiceTest.greetsAcrossModules -> GreetingService.greet
```

sont observables par les occurrences cibles, mais `scip-java` n'émet toujours
pas de relation `CALLS`.

L'instanciation de `DefaultGreetingPort` est portée par son symbole constructeur
`<init>` plutôt que par le symbole de classe : `find_usages` retourne un usage
du constructeur et zéro usage direct de la classe. Ce résultat est précis mais
doit être connu des consommateurs de requêtes.

Verdict A4 : **support Maven multi-module confirmé**.

## 3. A5 — Projet partiellement compilable

Le reactor contrôlé contient :

```text
java-partial-compile
├── stable : StableGreeting, compilable
└── broken : BrokenGreetingAdapter, dépend de MissingClient absent
```

### 3.1 Vérité terrain Maven

La commande Maven indépendante compile et package `stable`, puis échoue dans
`broken` avec les trois preuves attendues :

```text
package third.party.missing does not exist
cannot find symbol
MissingClient
```

L'échec n'est donc ni une erreur de toolchain, ni une régression MINOS.

### 3.2 Résultat scip-java

```text
indexExitCode               1
indexProduced               false
indexDurationMs             7 811
indexBytes                  0
shardCount                  2
shardBytes                  4 195
lint                        not-run
stats                       not-run
snapshot                    not-run
```

`scip-java` compile le module `stable`, rencontre l'erreur attendue dans
`broken`, puis n'exécute pas l'agrégation finale. Aucun `index.scip` n'est
publié, même si deux shards intermédiaires existent :

```text
StableGreeting.java.scip            967 octets
BrokenGreetingAdapter.java.scip   3 228 octets
```

Le runner conserve désormais ces shards avec `index.txt` et les codes de phase
dans `environment.txt`. Il supprime tout ancien index de sortie avant le run :
un échec ne peut donc pas être confondu avec un succès précédent.

### 3.3 Diagnostic des shards, hors chemin nominal

Les deux shards sont individuellement lisibles par `ScipIndexReader`. Cette
lecture diagnostique donne :

| Mesure | shard `stable` | shard `broken` |
|---|---:|---:|
| Documents | 1 | 1 |
| Faits catalogue | 4 | 8 |
| Occurrences | 7 | 35 |
| Résolues | 5 | 15 |
| Non résolues | 2 | 20 |

Le shard cassé conserve donc des définitions pour `BrokenGreetingAdapter` et
ses membres. `MissingClient`, `MissingClient.decorate`, ainsi que les références
cross-module vers `StableGreeting` restent occurrence-only.

Ces fichiers ne sont toutefois **pas un index fournisseur final** :

- l'étape d'agrégation n'a pas été exécutée ;
- les identifiants n'ont pas le préfixe final `scip-java maven ...` ;
- les références entre shards ne sont pas réconciliées ;
- les relations inverses et métadonnées finales peuvent manquer.

MINOS ne concatène donc pas silencieusement ces shards et ne les présente pas
comme un index sain.

Verdict A5 : **pas de support best-effort final lorsque Maven échoue**. Les
données intermédiaires sont récupérables pour diagnostic, pas encore pour le
chemin d'ingestion standard.

## 4. Conséquences pour le profil fournisseur

```text
Maven mono-module                 supporté
Maven multi-module               supporté
références cross-module          supportées après agrégation
projet partiellement compilable  aucun index final
shards sur compilation échouée   présents, format intermédiaire
best-effort index                non supporté dans la baseline
verdict                          ADOPTER_AVEC_CONTRAINTES
```

Le prochain point de décision architectural est explicite : MINOS doit-il
introduire un mode expérimental de récupération/agrégation de shards lorsque
le fournisseur échoue, ou considérer l'indexation comme atomique et conserver
le dernier index valide ?

La stratégie sûre par défaut reste atomique : signaler l'échec et ne jamais
remplacer un index valide par des données partielles présentées comme saines.
