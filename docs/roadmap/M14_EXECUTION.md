# M14 — Exécution : indexation autonome et installation PROD

Statut : **EN COURS — implémentation 7/7 ; qualification finale en attente**

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

- ✅ = implémenté **et validé** sur le SHA courant ;
- 🟡 = implémenté mais qualification locale/replay encore nécessaire ;
- ⬜ = non implémenté.

| Étape | Fonction | Implémentation | Validation attendue |
|---|---|---:|---|
| M14-S1 | Runtime providers + processus | 🟡 | tests + vrai processus enfant |
| M14-S2 | scip-typescript autonome | 🟡 | installation + replay fixture TS |
| M14-S3 | scip-java autonome | 🟡 | replay Maven Windows réel |
| M14-S4 | Staging multi-provider | 🟡 | collision/échec/promotion atomique |
| M14-S5 | CLI autonome | 🟡 | `dry-run`, `NONE`, `FULL`, erreur provider |
| M14-S6 | Installation native Windows | 🟡 | build `jpackage`, ZIP, install, `doctor` |
| M14-S7 | Release + Docker + docs | 🟡 | même JAR release + Docker smoke |

**Aucune étape n'est marquée ✅ avant validation exacte du head final.**

---

## M14-S1 — Runtime providers

Implémenté :

- `ProcessIndexerExecutor` derrière le port historique `IndexerExecutor` ;
- `IndexerProcessPlan` / factory provider ;
- timeout ;
- capture stdout/stderr ;
- destruction de l'arbre de processus ;
- préservation d'un `index.scip` préexistant ;
- artefact final copié sous `<MINOS_HOME>/runs/<runId>/<provider>/` ;
- masquage des arguments manifestement sensibles ;
- `FileIndexStateStore` persistant pour les états projet/run.

À valider : build/tests et cas timeout/process tree.

---

## M14-S2 — TypeScript

Implémenté :

```text
MINOS_HOME/tools/scip-typescript/0.4.0/
```

- installation npm locale transactionnelle ;
- aucun `npm -g` ;
- `node` / `npm` diagnostiqués ;
- `tsconfig.json` ou package compatible requis ;
- aucune installation silencieuse des dépendances métier ;
- `scip-typescript index` exécuté dans la racine projet ;
- incrémental explicitement refusé tant qu'il n'est pas qualifié.

À valider : replay TypeScript réel et second run `NONE`.

---

## M14-S3 — Java

Implémenté :

- runtime `scip-java` verrouillé ;
- résolution via Coursier géré/PATH ;
- téléchargement Coursier Windows dans `MINOS_HOME/tools` ;
- `JAVA_HOME` doit désigner un JDK avec `javac` ;
- portée actuelle limitée aux projets Maven qualifiés ;
- exécution dans la racine du projet ;
- incrémental non revendiqué.

À valider impérativement sous Windows : Maven réel, projet multi-module, échec de build et conservation du snapshot précédent.

---

## M14-S4 — Staging projet

Le chemin actif devient :

```text
provider artifact
  ↓
temporary provider normalization
  ↓
normalized provider snapshot
  ↓
Project assembly
  ↓
staged project snapshot
  ↓
atomic active publication
```

Implémenté :

- chaque provider normalisé dans un store temporaire ;
- aucun provider ne publie directement dans le store actif ;
- assemblage projet ;
- collision d'identifiant = échec explicite ;
- promotion finale via `FileSymbolSnapshotStore`.

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
```

Le mode historique `index --scip` reste temporairement accepté avec warning.

### Planification

Le service réutilise :

- `ProjectDiscoveryService` ;
- `IndexerRegistry` ;
- `ProjectFingerprintService` ;
- `ProjectInvalidationService` ;
- `IncrementalIndexingPlanner` ;
- `IndexingLifecycleService`.

Donc M14 ne réimplémente pas M1/M7.

### Politique actuelle

```text
aucun changement -> NONE
changement       -> FULL
```

car les versions providers gérées ne déclarent pas `INCREMENTAL_INDEXING`.

---

## M14-S6 — Installation native Windows

Implémenté :

```powershell
.\scripts\release\build-windows-distribution.ps1 -Version 0.2.0
```

Produit :

```text
target/dist/minos-0.2.0-windows-x64.zip
target/dist/minos-0.2.0-windows-x64.zip.sha256
```

Le ZIP contient :

```text
app/              app-image jpackage + runtime Java
minos.cmd
minos-mcp.cmd
install.ps1
VERSION
README.txt
```

Installation par défaut sans élévation :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Données :

```text
%LOCALAPPDATA%\MINOS\data
```

Le launcher utilisateur n'exige pas de `JAVA_HOME` pour exécuter MINOS.

---

## M14-S7 — Release et Docker

Implémenté :

- version de développement portée à `0.2.0-SNAPSHOT` ;
- version de release injectée dans un POM temporaire lors du packaging ;
- `Implementation-Version` dans le manifest ;
- `minos --version` lit la version packagée ;
- ZIP + SHA-256 ;
- Dockerfile release consommant un `minos.jar` déjà construit ;
- workflow Docker packagé séparé du build source ;
- `docker-data` distinct du home natif ;
- documentation PROD/native/Docker mise à jour.

Le Docker garde :

```text
network_mode: none
read_only: true
projects: read-only
cap_drop: ALL
no-new-privileges
```

---

# Portes de qualification finale

M14 ne devient **TERMINÉ** que lorsque le même SHA passe :

1. `java -version` = Java 24 ;
2. `git rev-parse HEAD` capturé ;
3. `.\mvnw.cmd clean verify` vert ;
4. tests nouveaux M14 verts ;
5. installation provider TypeScript réelle ;
6. indexation TypeScript réelle ;
7. second run TypeScript inchangé → `NONE` ;
8. indexation Java/Maven réelle sous Windows ;
9. échec Java de refresh → état `STALE` et ancien snapshot lisible ;
10. construction `minos-<version>-windows-x64.zip` ;
11. vérification SHA-256 ;
12. installation dans un répertoire vierge ;
13. `minos --version` = version de release ;
14. `minos doctor` fonctionnel sans JDK système requis pour la CLI ;
15. `minos mcp` handshake STDIO ;
16. image Docker construite depuis le même shaded JAR ;
17. Docker `network=none`, projets/read-only confirmés.

## Hors périmètre

- HTTP distant ;
- authentification réseau ;
- daemon/watch permanent ;
- indexation distribuée ;
- installation automatique des dépendances métier d'un projet ;
- vrai `INCREMENTAL` avant qualification provider dédiée.
