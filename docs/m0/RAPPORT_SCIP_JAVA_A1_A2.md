# M0 — Rapport d'expérience SCIP Java A1/A2

Date : **22 juillet 2026**

Statut : **A1 et A2 exécutées — scip-java qualifié avec contraintes sur Windows**

## 1. Périmètre et environnement

Les mesures ont été exécutées sur Windows 10 avec :

```text
JDK système                 24.0.1
Maven Wrapper              3.3.4
Maven                      3.9.16
scip-java                  0.13.1
Bindings Java SCIP         0.9.0
SCIP CLI                   0.7.1
```

Commandes principales :

```powershell
.\scripts\m0\install-scip-tools.ps1 -Force
.\.minos-m0\tools\bin\cs.exe --help
.\.minos-m0\tools\bin\scip.exe --version
.\scripts\m0\run-scip-java.ps1 -ProjectPath .\fixtures\java\java-simple
.\scripts\m0\run-scip-java.ps1 -ProjectPath .\fixtures\java\java-24-smoke
```

Le warning Protobuf relatif à `sun.misc.Unsafe` est présent avec Java 24, mais
n'a bloqué ni la lecture des index ni l'ingestion MINOS.

## 2. Installation locale de SCIP CLI 0.7.1

La release officielle `scip-code/scip` `v0.7.1` est toujours la dernière
release publiée au moment de l'expérience. Elle ne contient **aucun asset
Windows**. Les seuls binaires publiés sont :

```text
scip-darwin-amd64.tar.gz
scip-darwin-arm64.tar.gz
scip-linux-amd64.tar.gz
scip-linux-arm64.tar.gz
```

avec leurs fichiers SHA-256. L'URL précédente
`scip-windows-amd64.tar.gz` ne pouvait donc que répondre `404` : ce nom
d'asset n'existe pas dans la release officielle.

La solution M0 retenue est de construire localement le tag officiel `v0.7.1`
pour `windows/amd64`, avec les mêmes options que le workflow de release amont :

```text
CGO_ENABLED=0
GOWORK=off
go build -ldflags "-X main.Reproducible=true" ./cmd/scip
```

Le `go.mod` de ce tag exige Go 1.25.0. Le script télécharge donc l'archive
portable officielle `go1.25.0.windows-amd64.zip` dans son espace temporaire,
sans installation système.

Résultat réel :

```text
.minos-m0/tools/bin/cs.exe       présent
.minos-m0/tools/bin/scip.exe     présent
scip --version                   scip version v0.7.1
```

`-Force` réinstalle les deux exécutables. Une seconde exécution sans `-Force`
réutilise les exécutables et réussit. Les téléchargements et l'installation de
`scip.exe` passent par des fichiers `.partial`. Aucun `PATH` utilisateur, JDK,
Scala ou sbt n'est installé ou modifié ; `cs setup` n'est jamais exécuté.

Sources officielles vérifiées :

- https://github.com/scip-code/scip/releases/tag/v0.7.1
- https://github.com/scip-code/scip/blob/v0.7.1/.github/workflows/release.yml
- https://github.com/scip-code/scip/blob/v0.7.1/go.mod
- https://go.dev/dl/

## 3. Contraintes scip-java 0.13.1 sur Windows

La release officielle 0.13.1 indique explicitement le retrait du launcher
Windows. L'exécution réelle a confirmé trois incompatibilités opérationnelles
distinctes, indépendantes de Java 24 :

1. `scip-java` invoque `mvn`, alors que Windows fournit `mvn.cmd` ;
2. le launcher temporaire `javac` généré est un script Bash ;
3. `ScipWriter` crée un fichier temporaire avec des permissions POSIX non
   supportées par le provider Windows.

Le runner M0 fournit le minimum expérimental suivant :

- un shim local `mvn.exe` vers la distribution Maven Wrapper 3.9.16 exacte ;
- un shim local `javac.exe` qui exécute le launcher fournisseur inchangé via
  Git Bash ;
- une substitution de la seule classe `ScipWriter`, identique à l'amont sauf
  l'attribut POSIX lors de la création du fichier temporaire.

La logique d'agrégation, de réécriture des symboles et de création des relations
reste celle de `scip-java` 0.13.1. Le classpath officiel est résolu par Coursier
et exécuté avec le JDK système. Cette adaptation ne constitue pas un support
Windows natif de l'indexeur.

Sources officielles vérifiées :

- https://github.com/scip-code/scip-java/releases/tag/v0.13.1
- https://github.com/scip-code/scip-java/blob/v0.13.1/docs/getting-started.md

## 4. A1 — `java-simple`

### 4.1 Génération et post-traitements

`scip-java index` a compilé cinq sources principales et une source de test en
`release 17`, puis produit un vrai `index.scip` de **13 196 octets**.

Les artefacts sont conservés sous :

```text
fixtures/java/java-simple/.minos-m0/scip-java/
├── index.scip
├── lint.txt
├── stats.txt
├── environment.txt
├── snapshot/
├── snapshot.txt
└── minos-baseline.txt
```

Résultat des commandes SCIP :

| Commande | Code | Résultat |
|---|---:|---|
| `scip lint` | 2 | panic sur une plage typée |
| `scip stats` | 0 | statistiques produites |
| `scip snapshot` | 2 | panic sur une plage typée ; dossier vide et log conservés |

L'index contient 128 occurrences avec une plage typée et **aucune** ancienne
valeur `range`. SCIP CLI 0.7.1 appelle encore ses utilitaires basés sur cet
ancien tableau dans `lint` et `snapshot`, d'où respectivement les accès hors
limites dans `NewRangeUnchecked` et `isSCIPRangeLess`. Ce défaut de compatibilité
entre versions n'est pas un échec de compilation Java.

### 4.2 Statistiques fournisseur

```text
documents                         6
linesOfCode                      59
document bytes               13 076
provider symbol entries          32
provider catalog symbol facts    32
provider occurrences            128
provider definitions             32
provider relationships            4
typed ranges                     128
legacy ranges                      0
```

Les 32 entrées représentent 32 faits distincts. Les huit identifiants bruts
`local n` réutilisés dans plusieurs documents ne sont pas des doublons : SCIP
les porte dans la portée du document. La première baseline les confondait ; le
catalogue et l'ingestion MINOS utilisent désormais `document + rawSymbol` pour
les symboles locaux. Après correction : **0 doublon de catalogue**.

### 4.3 Comparaison à `expected.json`

Symboles obligatoires :

```text
présents par identité fournisseur / nom     13 / 13
absents                                      0 / 13
kind exact                                  12 / 13
```

L'unique écart de kind est le record `User` : scip-java le publie comme
`UnspecifiedKind`, donc MINOS le conserve comme `OTHER` au lieu d'inventer
`RECORD`. Les interfaces, classes, méthodes et constructeurs attendus sont
présents. Cinq constructeurs sont observés, dont les constructeurs obligatoires
de `UserService` et `UserResource` et trois constructeurs implicites admis par
la fixture.

Relations et références attendues :

- `InMemoryUserRepository IMPLEMENTS UserRepository` est une relation fournisseur
  explicite ;
- l'appel `UserService.findUser -> UserRepository.findById` est observé comme
  référence cible dans `UserService.java:15` ;
- l'appel `UserResource.getUserName -> UserService.findUser` est observé dans
  `UserResource.java:14` ;
- l'appel `UserServiceTest.findsExistingUser -> UserService.findUser` est observé
  dans `UserServiceTest.java:15`.

scip-java n'émet pas ces trois appels comme objets `Relationship`. Les trois
cibles sont donc retrouvées par `find_usages`, mais la baseline M0 ne possède
pas encore le contexte englobant nécessaire pour matérialiser des arêtes
`CALLS`. La précision d'arêtes d'appel explicites ne doit pas être déclarée :
sur cette fixture, leur rappel comme `Relationship` est **0/3**, tandis que les
références cibles attendues sont observées **3/3**.

Couverture des cibles d'usage obligatoires :

| Cible | Usages MINOS hors définition |
|---|---:|
| `User` | 5 |
| `UserRepository` | 4 |
| `UserRepository.findById` | 1 |
| `UserService` | 3 |
| `UserService.findUser` | 2 |

Les cinq cibles attendues possèdent donc au moins un usage résolu.

### 4.4 Ingestion MINOS réelle

Chaîne exécutée :

```text
index.scip
→ ScipIndexReader
→ ScipIngestionAdapter
→ InMemoryCodeKnowledgeStore
→ find_symbol / find_usages
```

Mesures :

```text
catalogSymbols                 32
normalizedSymbols              32
skippedSymbols                  0
occurrences                   128
resolvedOccurrences            64
unresolvedOccurrences          64
skippedOccurrences              0
unresolvedOccurrenceRate     0,50
```

Les 64 occurrences non résolues correspondent exactement à 24 identifiants
présents uniquement dans les occurrences et absents des catalogues de symboles :

- 62 occurrences de symboles externes JDK/JUnit ou de segments de package ;
- 2 occurrences de l'accesseur synthétique workspace `User#name()`.

Les références externes ne sont pas transformées en faux symboles résolus.
Toutes les références aux symboles workspace obligatoires de `expected.json`
sont résolues. L'accesseur synthétique, admis comme symbole fournisseur
optionnel par la fixture, reste une limitation mesurée.

## 5. A2 — `java-24-smoke`

`scip-java 0.13.1` a réellement lancé Maven 3.9.16 avec le JDK 24.0.1, compilé
deux sources en **`release 24`** et produit un `index.scip` de **3 049 octets**.
Aucune erreur liée aux APIs internes `javac` ou au bytecode Java 24 n'est
apparue.

Comparaison à `expected.json` :

```text
symboles obligatoires présents        5 / 5
kinds obligatoires exacts             5 / 5
référence GreetingApp.run -> greet    observée à GreetingApp.java:12
relation CALLS explicite              non émise par le fournisseur
```

Statistiques :

```text
documents                          2
linesOfCode                       16
index bytes                    3 049
provider catalog symbol facts     10
provider occurrences              22
provider definitions              10
typed ranges                      22
legacy ranges                      0
catalogSymbols                    10
normalizedSymbols                 10
resolvedOccurrences               18
unresolvedOccurrences              4
unresolvedOccurrenceRate      0,1818
```

Les quatre non-résolutions visent uniquement `java/lang/String#`, absent du
catalogue fournisseur. `lint` et `snapshot` reproduisent les mêmes panics de
SCIP CLI 0.7.1 que sur A1 ; `stats` réussit.

Verdict A2 :

> **Oui, scip-java 0.13.1 indexe réellement ce projet Maven `release=24` avec
> le JDK 24.0.1 de MINOS.** Ce verdict est `ADOPTER_AVEC_CONTRAINTES` sur
> Windows : l'exécution nécessite les adaptations locales documentées et la
> chaîne SCIP CLI 0.7.1 reste partielle pour les plages typées.

Ce résultat ne justifie aucun changement de JDK pour MINOS.

## 6. Conclusion A1/A2

A1 et A2 suffisent pour poursuivre les expériences locales sans attendre la CI
GitHub Actions. Elles ne valident ni la CI, ni `scip lint`, ni la production du
snapshot humain avec la combinaison de versions actuelle.

A3 a ensuite été exécutée sur le dépôt réel `FTurleque/ariane-chatbot`. Ses
résultats sont consignés dans `docs/m0/RAPPORT_SCIP_JAVA_A3_ARIANE.md`.

L'évaluation d'une CLI SCIP compatible avec les plages typées reste séparée.
Les versions CLI, bindings et indexeur doivent continuer à être suivies
indépendamment.
