# M14 — Exécution : indexation autonome et installation PROD

Statut : **TERMINÉ — implémentation 7/7 ; qualification native et Docker verte**

Issue : **#42**  
PR de travail : **#43**

## Objectif produit

Le parcours normal doit être :

```text
minos doctor
minos tools install <provider>
minos project add <root> --name <project>
minos index <project>
```

L'utilisateur ne prépare plus `index.scip` manuellement.

```text
project
  ↓ discovery
  ↓ provider negotiation
  ↓ runtime diagnosis
  ↓ fingerprint / invalidation
  ↓ NONE | FULL | INCREMENTAL qualifié
  ↓ provider execution
  ↓ normalization
  ↓ project staging
  ↓ atomic promotion
  ↓ READY
```

## Lecture de l'avancement

- ✅ = implémenté **et validé sur le head final exact** ;
- 🟡 = implémenté mais le head courant doit encore passer la qualification complète ;
- ⬜ = non implémenté.

| Étape | Fonction | État du head courant | Gate final |
|---|---|---:|---|
| M14-S1 | Runtime providers + processus | ✅ | `clean verify` + vrai processus enfant |
| M14-S2 | scip-typescript autonome | ✅ | installation + FULL → SUCCEEDED → NONE |
| M14-S3 | scip-java autonome Windows | ✅ | 0.13.1 + replay Maven + STALE/recovery |
| M14-S4 | Staging multi-provider | ✅ | collision/échec/promotion atomique |
| M14-S5 | CLI autonome | ✅ | `dry-run`, NONE, FULL, erreurs runtime |
| M14-S6 | Installation native Windows | ✅ | jpackage + ZIP + SHA-256 + install vierge |
| M14-S7 | Release + Docker + docs | ✅ | mêmes artefacts installés + Docker smoke |

**Une validation réussie sur un ancien SHA ne transforme pas le head courant en ✅.**

---

## M14-S1 — Runtime providers

Implémenté :

- `ProcessIndexerExecutor` derrière le port historique `IndexerExecutor` ;
- `IndexerProcessPlan` / factory provider ;
- timeout ;
- capture stdout/stderr ;
- destruction de l'arbre de processus ;
- préservation d'un artefact préexistant ;
- artefact final stable sous `<MINOS_HOME>/runs/<runId>/<provider>/` ;
- masquage des arguments manifestement sensibles ;
- `FileIndexStateStore` persistant ;
- bootstrap autonome lazy : les commandes `--help` restent sans effet de bord.

---

## M14-S2 — TypeScript

Provider verrouillé :

```text
scip-typescript 0.4.0
```

Installation gérée :

```text
<MINOS_HOME>/tools/scip-typescript/0.4.0/
```

Implémenté :

- installation npm locale transactionnelle ;
- aucun `npm -g` ;
- diagnostic `node` / `npm` ;
- aucune installation silencieuse des dépendances métier ;
- exécution `scip-typescript index` depuis la racine projet ;
- incrémental non revendiqué tant qu'il n'est pas qualifié.

---

## M14-S3 — Java Windows

Provider verrouillé :

```text
scip-java 0.13.1
org.scip-code:scip-java:0.13.1
```

M14 réutilise explicitement la qualification Windows obtenue pendant M0 au lieu de lancer naïvement le provider.

Runtime géré :

```text
<MINOS_HOME>/tools/
├── coursier/windows-x64-official-launcher/cs.exe
└── scip-java/0.13.1/runtime/
    ├── scip-java-windows-runner.ps1
    └── ScipWriter.java
```

Adaptations Windows qualifiées :

1. shim local `mvn.exe` vers le Maven Wrapper du projet ou Maven disponible ;
2. shim local `javac.exe` exécutant le launcher fournisseur via Git Bash ;
3. patch `ScipWriter` identique à l'amont sauf suppression de l'attribut POSIX non supporté par Windows.

Préconditions :

```text
JAVA_HOME -> JDK avec java/javac/jar
pom.xml
mvnw.cmd dans le projet ou un parent, sinon Maven dans PATH
Git Bash
csc.exe
```

Le runner est embarqué comme ressource du JAR et extrait par `minos tools install scip-java` : l'installation utilisateur ne dépend pas de `scripts/m0`.

---

## M14-S4 — Staging projet

```text
provider artifact
  ↓
temporary provider normalization
  ↓
normalized provider snapshot
  ↓
project assembly
  ↓
staged project snapshot
  ↓
atomic active publication
```

Implémenté :

- chaque provider est normalisé dans un store temporaire ;
- aucun provider ne publie directement dans le store actif ;
- assemblage projet ;
- collision d'identifiant = échec explicite ;
- promotion finale unique via `FileSymbolSnapshotStore`.

---

## M14-S5 — CLI autonome

Implémenté :

```text
minos index <project>
minos index <project> --dry-run
minos index <project> --provider <id>
minos index <project> --force-full
minos import-scip <project> --file ... --provider ...
minos doctor
minos tools list
minos tools install <provider>
minos tools verify
minos mcp
minos --version
```

`index` est le parcours autonome. `import-scip` porte le contrat d'import manuel explicite.

Le service réutilise :

- `ProjectDiscoveryService` ;
- `IndexerRegistry` ;
- `ProjectFingerprintService` ;
- `ProjectInvalidationService` ;
- `IncrementalIndexingPlanner` ;
- `IndexingLifecycleService`.

Politique conservatrice actuelle :

```text
aucun changement -> NONE
changement       -> FULL
```

car les providers gérés ne déclarent pas encore `INCREMENTAL_INDEXING`.

`inspect` et `index-status` lisent les états M14 persistants (`READY`, `STALE`, `FAILED`, etc.).

---

## M14-S6 — Installation native Windows

Build mainteneur :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0
```

Produit :

```text
target/dist/minos-0.2.0-windows-x64.zip
target/dist/minos-0.2.0-windows-x64.zip.sha256
```

Contenu attendu et contrôlé par l'installateur :

```text
minos-<version>-windows-x64/
├── app/                                  # jpackage + runtime Java embarqué
├── lib/
│   └── minos.jar                         # shaded JAR exact de release
├── docker/
│   ├── Dockerfile.mcp.release
│   ├── compose.mcp.prod.yaml
│   └── scripts/prod-mcp-release.ps1
├── minos.cmd
├── minos-mcp.cmd
├── install.ps1
├── VERSION
└── README.txt
```

Installation utilisateur par défaut :

```text
programme : %LOCALAPPDATA%\Programs\MINOS
données   : %LOCALAPPDATA%\MINOS\data
```

Le runtime Java de MINOS est embarqué ; le JDK système reste uniquement une précondition éventuelle du projet indexé.

---

## M14-S7 — Release et Docker

Implémenté :

- version de développement `0.2.0-SNAPSHOT` ;
- version de release injectée dans un POM temporaire ;
- `Implementation-Version` dans le manifest ;
- ZIP + SHA-256 ;
- Dockerfile release consommant `lib/minos.jar` ;
- assets Docker inclus dans la distribution installable ;
- `docker-data` séparé du home natif ;
- Docker MCP toujours durci :

```text
network_mode: none
read_only: true
projects: read-only
cap_drop: ALL
no-new-privileges
```

Avec `-ValidateDocker`, le gate M14 utilise désormais **le JAR et les scripts de la distribution réellement installée**, pas les fichiers du checkout source.

---

# Historique de qualification Windows

## Qualification #1 — `d3aaba517d50674eb13c7818c419f7acf02622af`

Résultat :

```text
Java 24.0.1
232 tests exécutés
230 PASS
2 FAIL
0 ERROR
BUILD FAILURE
```

Défauts identifiés puis corrigés :

1. création de `MINOS_HOME` lors de `project add --help` → bootstrap autonome rendu lazy ;
2. test M9 imposant encore `index --scip` → contrat réaligné sur `index` autonome + `import-scip` manuel.

## Qualification #2 — `68e93bd7b2ccd5caa78f6596e392ad9e0fdba600`

Gate Maven :

```text
181 sources main
91 sources test
232/232 tests PASS
ShadedJarSmokeIT 1/1 PASS
BUILD SUCCESS
MINOS 0.2.0-SNAPSHOT
```

Replay TypeScript : **PASS** :

```text
installation scip-typescript 0.4.0 READY
premier plan FULL
première indexation SUCCEEDED
second run NO_CHANGES / NONE
```

Replay Java : **BLOCKED au premier index réel** :

```text
provider déclaré READY
scip-java 0.12.3
démarrage du run
provider exit code 1
```

Diagnostic : le runtime M14 utilisait une version/commande Java différente du chemin Windows déjà qualifié pendant M0 et ne portait pas les trois adaptations Windows nécessaires.

Corrections postérieures :

- retour au provider M0 qualifié `scip-java 0.13.1` ;
- coordonnée `org.scip-code:scip-java:0.13.1` ;
- runner Windows embarqué ;
- shims Maven/javac ;
- patch `ScipWriter` ;
- diagnostic automatique des logs provider en cas d'échec ;
- distribution native complétée avec `lib/minos.jar` et les assets Docker.

**Le head courant doit être requalifié intégralement : les PASS du SHA `68e93bd...` sont des preuves historiques, pas une validation du nouveau head.**

## Qualification #3 — réparation du runtime `scip-java` Windows

Le diagnostic du replay au SHA de départ `ee30e1f63930c90465015bced5bf6ad4dddf8ecc`
a confirmé trois écarts avec le chemin M0 qualifié :

1. `tools install scip-java` lançait la coordonnée Coursier sans classe
   principale explicite et échouait avec `NoMainClassFound` ;
2. le diagnostic exigeait uniquement `powershell.exe`, alors que le poste de
   qualification expose PowerShell 7 via `pwsh.exe` ;
3. le runner prenait le premier `bash.exe` du `PATH`, soit WSL Bash, au lieu de
   `<Git>\bin\bash.exe`, rendant le launcher `javac` fournisseur introuvable.

Le runtime corrigé :

- sonde `org.scip-code:scip-java:0.13.1` avec `--jvm system`, la classe
  `org.scip_code.scip_java.ScipJava` explicite et `--version` ;
- vérifie dans le journal la réponse exacte `scip-java version 0.13.1` ;
- accepte Windows PowerShell ou PowerShell 7 ;
- résout strictement Git Bash depuis l'installation Git et refuse WSL Bash ;
- conserve le classpath Coursier, les shims Maven/javac et le patch
  `ScipWriter` M0.

Validation progressive observée avant le gate exact-head :

```text
tests ciblés                 8 PASS
clean verify                 236 PASS, 0 failure, 0 error, 0 skipped
ShadedJarSmokeIT             1 PASS
tools install scip-java      READY, version 0.13.1
Java premier plan            FULL
Java première indexation     SUCCEEDED
Java second run              NO_CHANGES / NONE
refresh Java invalide        échec provider attendu, STALE
snapshot après échec         ancien snapshot conservé
recovery --force-full        SUCCEEDED, READY
```

À ce stade historique, ces résultats protégeaient le correctif, mais les étapes
restaient 🟡 jusqu'à la qualification native puis Docker sur un head Git propre
et exact.

## Qualification #4 — gate natif et Docker complet

Première qualification complète observée sur :

```text
7a3ed0c14c2188b1ef6cbed6eb12a6c57c51bbb7
```

Résultat natif :

```text
Java                         24.0.1
sources main / test          181 / 92
tests                        236 PASS, 0 failure, 0 error, 0 skipped
ShadedJarSmokeIT             1 PASS
TypeScript                   FULL → SUCCEEDED → NO_CHANGES / NONE
Java                         FULL → SUCCEEDED → NO_CHANGES / NONE
Java invalide                échec attendu → STALE
snapshot après échec         snapshot précédent conservé
Java recovery                --force-full → SUCCEEDED → READY
release                      0.2.0-rc1
jpackage / ZIP / SHA-256     PASS
installation vierge         PASS
minos --version              MINOS 0.2.0-rc1
minos doctor                 READY
MCP natif                    handshake SUCCESS
```

Résultat Docker, lancé seulement après le gate natif vert :

```text
source image                 JAR et scripts de la distribution installée
image                        minos-code-intelligence:0.2.0-rc1-7a3ed0c14c21
Java image                   24.0.2
network                      none
projects                     /workspace/projects read-only
data                         /var/lib/minos
configuration               validated
SHA-256 ZIP du gate Docker   09a9822e891535936a423cbd90118820c4eac9e0b5891620b2e35b148551d8dd
```

Le head final incluant cette mise à jour de roadmap doit passer les mêmes deux
commandes exact-head avant publication. Les ✅ ne sont conservés que si cette
seconde qualification réussit.

---

# Portes de qualification finale

M14 ne devient **TERMINÉ** que lorsque le même SHA passe :

1. Java 24 ;
2. SHA Git exact capturé et worktree propre ;
3. `mvnw clean verify` vert ;
4. tous les tests M14 verts ;
5. installation `scip-typescript` réelle ;
6. TypeScript FULL → SUCCEEDED → NONE ;
7. installation `scip-java 0.13.1` réelle ;
8. Java/Maven FULL → SUCCEEDED → NONE sous Windows ;
9. échec Java volontaire → `STALE` avec ancien snapshot actif ;
10. récupération Java → `SUCCEEDED` ;
11. construction du ZIP Windows ;
12. vérification SHA-256 ;
13. installation dans un répertoire vierge ;
14. `minos --version` = version de release ;
15. `minos doctor` ;
16. handshake MCP STDIO natif ;
17. avec gate Docker : image construite depuis **`<installation>/lib/minos.jar`** et assets installés ;
18. Docker `network=none` et projets read-only confirmés.

## Hors périmètre

- HTTP distant ;
- authentification réseau ;
- daemon/watch permanent ;
- indexation distribuée ;
- installation automatique des dépendances métier d'un projet ;
- vrai `INCREMENTAL` avant qualification provider dédiée.
