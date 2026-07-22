# Scripts M0 — Expériences SCIP Java et TypeScript

Ces scripts exécutent la chaîne de qualification `scip-java` sur un projet Maven Java puis conservent les artefacts nécessaires aux mesures M0.

## Versions de référence

```text
scip-java                 0.13.1
SCIP CLI                  0.7.1
```

Coursier n'est pas une composante fonctionnelle qualifiée par M0 : il sert uniquement de **bootstrapper** pour lancer `scip-java`. Sous Windows, l'installateur MINOS utilise le launcher officiel publié dans `coursier/launchers` plutôt que de figer un asset de release Coursier.

Le runner fournit explicitement la classe principale officielle
`org.scip_code.scip_java.ScipJava` à Coursier. Le POM Maven 0.13.1 ne permet
pas au launcher Windows courant de la déduire automatiquement.

Sous Windows, il exécute directement le classpath avec la commande `java` du
poste, soit l'équivalent vérifié de Coursier `--jvm system`, et rend la
distribution Maven 3.9.16 déjà installée par le Wrapper visible via un petit shim `mvn.exe`
strictement local. Ce shim est nécessaire car `scip-java` invoque `mvn` avec
`ProcessBuilder`, qui ne résout pas directement `mvn.cmd` sous Windows. Le
`PATH` du processus est restauré après le run ; le `PATH` utilisateur et
`JAVA_HOME` restent inchangés.

`scip-java` 0.13.1 génère également son launcher temporaire `javac` sous
forme de script Bash, car son support de launcher Windows a été retiré. Le
runner PowerShell conserve le script fournisseur inchangé et l'exécute via
Git Bash derrière un second shim local `javac.exe`. Cette compatibilité est
une contrainte fournisseur de l'expérience, pas un support Windows natif à
attribuer à `scip-java`.

Enfin, l'agrégateur 0.13.1 crée son fichier temporaire avec un attribut de
permissions POSIX que le provider Windows refuse. Le runner compile donc une
substitution locale de la seule classe `ScipWriter`, identique à l'amont sauf
que cet attribut initial est omis. Coursier résout toujours les artefacts
officiels 0.13.1 ; le patch est placé en premier dans le classpath du JDK
système. Aucune réécriture de symbole ni logique d'agrégation n'est réimplémentée.

Les bindings Java SCIP utilisés par MINOS sont versionnés séparément dans le build principal :

```text
org.scip-code:scip-java-bindings:0.9.0
```

## Prérequis

Le poste utilise le **JDK 24 de référence déjà installé**.

MINOS ne demande pas l'installation d'un JDK supplémentaire uniquement pour l'expérience SCIP.

Sous Windows, Coursier et SCIP CLI peuvent être installés **localement pour MINOS** avec :

```powershell
.\scripts\m0\install-scip-tools.ps1
```

Le script télécharge uniquement :

```text
Coursier launcher Windows officiel
sources officielles taguées SCIP CLI 0.7.1
SDK Go 1.25.0 portable requis par ce tag
```

vers :

```text
.minos-m0\tools\bin\cs.exe
.minos-m0\tools\bin\scip.exe
```

Le launcher Coursier Windows provient de l'URL officielle :

```text
https://github.com/coursier/launchers/raw/master/cs-x86_64-pc-win32.zip
```

La release officielle SCIP `v0.7.1` ne publie aucun asset Windows : elle ne
contient que les archives binaires Linux et macOS. L'installateur télécharge
donc le tag source officiel et le compile pour `windows/amd64` avec le SDK Go
portable, en reprenant les options du workflow de release amont. Go et ses
caches de build restent dans `.minos-m0/tools/tmp` puis sont supprimés ; aucune
installation système n'est créée.

L'installateur utilise `curl.exe` en priorité avec plusieurs tentatives, puis un repli Windows PowerShell TLS 1.2. Les téléchargements sont écrits dans un fichier temporaire avant installation afin d'éviter de conserver un exécutable partiel.

Il ne lance pas `cs setup`, n'installe pas Scala/sbt, ne modifie pas le `PATH` utilisateur et ne modifie pas le JDK.

Le runner PowerShell utilise ces outils locaux automatiquement. Il peut aussi utiliser des commandes explicitement fournies ou, en dernier recours, des commandes présentes dans le `PATH`.

## Windows / PowerShell

Depuis la racine de MINOS :

```powershell
.\scripts\m0\install-scip-tools.ps1
.\scripts\m0\run-scip-java.ps1 -ProjectPath .\fixtures\java\java-simple
```

Fixture Java 24 :

```powershell
.\scripts\m0\run-scip-java.ps1 -ProjectPath .\fixtures\java\java-24-smoke
```

Dépôt réel Ariane, exemple si les dépôts sont voisins :

```powershell
.\scripts\m0\run-scip-java.ps1 -ProjectPath ..\ariane-chatbot
```

Paramètres facultatifs :

```powershell
-OutputDirectory <chemin>
-ScipJavaVersion 0.13.1
-CoursierCommand <commande-ou-chemin>
-ScipCommand <commande-ou-chemin>
```

Réinstallation forcée des outils locaux :

```powershell
.\scripts\m0\install-scip-tools.ps1 -Force
```

## Bash / Git Bash / Linux / macOS

Le runner Bash attend actuellement `cs` et `scip` dans le `PATH` ou via les variables d'environnement indiquées ci-dessous.

Le fichier peut être lancé explicitement avec Bash même si le bit exécutable n'est pas préservé par une copie Git :

```bash
bash scripts/m0/run-scip-java.sh fixtures/java/java-simple
```

Fixture Java 24 :

```bash
bash scripts/m0/run-scip-java.sh fixtures/java/java-24-smoke
```

Dépôt réel Ariane :

```bash
bash scripts/m0/run-scip-java.sh ../ariane-chatbot
```

Variables facultatives :

```bash
SCIP_JAVA_VERSION=0.13.1
COURSIER_COMMAND=cs
SCIP_COMMAND=scip
```

## Chaîne exécutée

Le runner lance conceptuellement :

```text
Coursier
   │
   ▼
org.scip-code:scip-java:0.13.1
   │
   ▼
scip-java index
   │
   ▼
index.scip
   │
   ├── scip lint
   ├── scip stats
   └── scip snapshot
```

Les commandes SCIP correspondent à la CLI officielle :

```text
scip lint <index.scip>
scip stats --from <index.scip>
scip snapshot --from <index.scip> --to <directory>
```

## Sorties

Par défaut :

```text
<projet>/.minos-m0/scip-java/
```

avec :

```text
index.scip
index.txt
shards/
lint.txt
stats.txt
environment.txt
snapshot/
snapshot.txt
```

`index.scip`, `lint.txt`, `stats.txt` et `snapshot*` sont absents lorsqu'une
erreur de build empêche l'agrégation. `index.txt`, `environment.txt` et les
shards éventuellement produits restent conservés.

Le dossier `.minos-m0/` est ignoré par Git dans le dépôt MINOS.

## Rôle des artefacts

### `index.scip`

Entrée binaire utilisée par la baseline `ScipIndexReader` / `ScipIngestionAdapter`.

### `index.txt`

Sortie complète de `scip-java index`, y compris les erreurs Maven. Le fichier
est produit transactionnellement via `index.partial.txt`.

### `shards/`

Copie transactionnelle des shards bruts laissés dans
`target/scip-targetroot/META-INF/scip`. Ils servent au diagnostic d'un échec et
ne sont pas présentés comme un index final.

### `lint.txt`

Anomalies structurelles signalées par SCIP CLI.

### `stats.txt`

Statistiques de l'index utilisées pour qualifier le fournisseur et comparer les runs.

### `snapshot/`

Vue humaine des occurrences et symboles destinée à la vérification contre `expected.json`.

Avec SCIP CLI 0.7.1 et les plages typées de scip-java 0.13.1, `snapshot`
termine actuellement par un panic amont. Le runner préserve un dossier vide et
le diagnostic complet dans `snapshot.txt`, puis retourne un échec après avoir
tenté tous les post-traitements.

### `environment.txt`

Conserve au minimum :

- date ;
- chemin du projet ;
- version `scip-java` ;
- version Java ;
- commande Coursier utilisée ;
- version SCIP CLI.

Le fichier enregistre aussi `indexExitCode`, `indexProduced`, la durée et la
taille de l'index, le nombre/volume des shards et les codes des
post-traitements. Une phase non atteinte vaut explicitement `not-run`.

## Ordre d'exécution de l'Expérience A

### A1 — `java-simple`

Objectif : comparer précisément le résultat à la vérité terrain contrôlée.

```text
fixtures/java/java-simple/expected.json
```

### A2 — `java-24-smoke`

Objectif : vérifier explicitement que `scip-java` sait indexer correctement un projet Maven `release=24` **avec le même JDK 24 que celui utilisé par MINOS**.

```text
fixtures/java/java-24-smoke/expected.json
```

La documentation officielle de `scip-java` indique que son lanceur accepte un **JDK 17 ou supérieur**, mais sa matrice de versions Java explicitement ciblées liste actuellement Java 17, 21 et 25. Java 24 doit donc être qualifié par mesure réelle.

Un échec de cette étape qualifiera une limitation de `scip-java` ; il ne déclenchera pas automatiquement un changement de JDK pour MINOS.

### A3 — `FTurleque/ariane-chatbot`

Objectif : tester un dépôt Maven Java réel avec :

- Java 17 ;
- Quarkus ;
- génération de code ;
- dépendances externes ;
- code applicatif réel.

Résultat confirmé : 160 sources principales et 60 sources de test compilées en
`release 17`, 220 shards agrégés et ingérés par MINOS. Le détail se trouve dans
`docs/m0/RAPPORT_SCIP_JAVA_A3_ARIANE.md`.

### A4 — `java-multi-module`

Objectif : qualifier un reactor Maven avec définitions, implémentations, appels
et tests traversant deux modules.

### A5 — `java-partial-compile`

Objectif : mesurer sans le masquer l'échec d'un module qui référence une
dépendance absente après compilation d'un premier module sain.

## Règle d'analyse

Un run réussi techniquement ne suffit pas à qualifier le fournisseur.

Les résultats doivent être confrontés à :

- la vérité terrain ;
- `docs/METRIQUES_VALIDATION.md` ;
- les capacités déclarées dans `IndexerCapabilities` ;
- les limitations observées.

Le résultat final doit alimenter un `ProviderQualityProfile` et non une simple conclusion « fonctionne / ne fonctionne pas ».

Après copie dans le dossier d'artefacts, le runner retire l'`index.scip`
généré à la racine du projet. Si un index existait avant l'expérience, il est
déplacé transactionnellement puis restauré, afin de ne pas laisser le dépôt
analysé modifié.

Les résultats mesurés de A1 à A5 sont documentés dans :

```text
docs/m0/RAPPORT_SCIP_JAVA_A1_A2.md
docs/m0/RAPPORT_SCIP_JAVA_A3_ARIANE.md
docs/m0/RAPPORT_SCIP_JAVA_A4_A5.md
```

## Baseline SCIP → MINOS

Le harness expérimental reste dans les sources de test. Il peut être rejoué sur
un index réel sans créer de CLI produit :

```powershell
.\scripts\m0\run-minos-scip-baseline.ps1 `
  -IndexPath .\fixtures\java\java-simple\.minos-m0\scip-java\index.scip `
  -ProjectId fixture-java-simple `
  -Queries User,UserRepository,findById,UserService,findUser
```

La sortie TSV `minos-baseline.txt` contient les faits fournisseur, les
relations, les métriques d'ingestion et les résultats `find_symbol` /
`find_usages` avec leurs positions source.

## Expérience D1 — `scip-typescript`

Installation locale et idempotente de la version qualifiée :

```powershell
.\scripts\m0\install-scip-typescript.ps1
```

Le package est installé sous :

```text
.minos-m0/tools/scip-typescript/
```

Il n'est ni global, ni ajouté au `PATH`. `-Force` reconstruit l'installation
via un dossier `.partial`, valide la version, puis la promeut.

Exécution sur la fixture :

```powershell
.\scripts\m0\run-scip-typescript.ps1 `
  -ProjectPath .\fixtures\typescript\typescript-simple
```

Le runner produit dans `.minos-m0/scip-typescript` :

```text
index.scip
index.txt
lint.txt
stats.txt
snapshot/
snapshot.txt
snapshot-nonstrict/
snapshot-nonstrict.txt
environment.txt
```

Le snapshot non strict est tenté uniquement après l'échec du snapshot strict.
Son succès ne masque pas l'échec strict : le runner termine en erreur si le
lint ou le snapshot strict échoue, tout en conservant tous les diagnostics.

Baseline MINOS sur le même index :

```powershell
.\scripts\m0\run-minos-scip-baseline.ps1 `
  -IndexPath .\fixtures\typescript\typescript-simple\.minos-m0\scip-typescript\index.scip `
  -ProjectId fixture-typescript-simple `
  -ProviderId scip-typescript `
  -ProviderVersion 0.4.0 `
  -IndexRunId m0-typescript-d1 `
  -Queries UserId,UserStatus,UserRepository,findById,UserService,findUser,getUserName
```

Les résultats mesurés et les limitations sont consignés dans
`docs/m0/RAPPORT_SCIP_TYPESCRIPT_D1.md`.
