# Plugin IntelliJ — MINOS Code Intelligence

Le plugin **MINOS Code Intelligence** ajoute une surface IDE native au moteur MINOS local. Il ne remplace ni l'index IntelliJ ni le MCP : il consomme les faits et services MINOS via le protocole externe versionné `minos-ide`.

M21-S6 étend la surface M18 avec Program Graph, Impact v2, chemins sécurité et recherche sémantique/hybride, **sans embarquer ni réimplémenter le moteur Java 24 dans le plugin**.

## Prérequis

1. installer MINOS normalement ;
2. vérifier `minos.cmd doctor` sous Windows ou `minos doctor` sous Linux/macOS ;
3. disposer d'un projet local ouvrable par IntelliJ ;
4. installer le ZIP du plugin produit par le build MINOS.

Le plugin et le moteur sont volontairement séparés : le plugin s'exécute en Java 21 avec la JVM IntelliJ, tandis que MINOS reste un processus local Java 24 indépendant.

### Ownership OS des commandes CLI

Le plugin ne lance plus le CLI sous une simple supervision PID. La frontière d'ownership doit être disponible **avant** l'exécution du CLI :

- **Windows** : Job Object `KILL_ON_JOB_CLOSE`, processus CLI créé suspendu, assigné/vérifié dans le job puis repris ;
- **Linux** : scope utilisateur systemd/cgroup transitoire ; un user manager systemd opérationnel est requis ;
- **autres plateformes** : aucune garantie forte n'est annoncée et les commandes du plugin restent fail-closed tant qu'une primitive équivalente n'est pas qualifiée.

Le polling `ProcessHandle` reste actif uniquement comme défense en profondeur et protection PID-reuse.

## Installation du plugin

Artefact :

```text
minos-intellij-<version>.zip
```

Dans IntelliJ IDEA :

```text
Settings
  → Plugins
  → ⚙
  → Install Plugin from Disk…
  → sélectionner le ZIP
  → redémarrer l'IDE si demandé
```

Ne pas extraire le ZIP avant l'installation.

## Configuration

Ouvrir :

```text
Settings → Tools / MINOS
```

ou rechercher `MINOS` dans Settings.

| Champ | Rôle | Défaut |
|---|---|---|
| MINOS executable | launcher CLI local | `minos.cmd` sous Windows, `minos` ailleurs |
| MINOS_HOME | home de données explicite ; vide = résolution MINOS habituelle | vide |
| Command timeout | délai maximum d'une commande IDE | 30 s |
| Maximum architecture nodes | borne des graphes chargés dans l'IDE | 120 |

Si MINOS n'est pas dans le `PATH`, renseigner le chemin absolu du launcher, par exemple :

```text
C:\Users\<user>\AppData\Local\Programs\MINOS\bin\minos.cmd
```

## Protocole IDE

Le plugin commence par :

```text
minos ide handshake --format json
```

Contrat courant :

```text
protocol         = minos-ide
protocolVersion  = 1
transport        = cli-json-process
```

S6 reste **additif** : le numéro de protocole n'est pas modifié, mais chaque nouvelle surface est annoncée par capability. Le client vérifie la capability avant l'appel ; un moteur trop ancien est refusé explicitement pour l'action concernée au lieu de dégrader silencieusement le résultat.

Capabilities M21-S6 :

```text
program-graph
impact-v2
security-paths
semantic-index-status
semantic-index-sync
semantic-search
hybrid-search
hybrid-context
```

## Premier démarrage

Ouvrir la Tool Window **MINOS** à droite.

### Projet non enregistré

Cliquer **Register** dans la Tool Window ou exécuter :

```powershell
minos.cmd project add N:\workspace-dev\mon-projet --name mon-projet
```

Le plugin associe le projet IntelliJ au projet MINOS par son chemin racine normalisé.

## Tool Window MINOS

### Project

Affiche notamment : protocole IDE, projet MINOS résolu, état d'indexation, snapshot actif et provider courant.

Actions historiques conservées :

- **Refresh** — recharge le statut ;
- **Register** — enregistre le projet IntelliJ dans MINOS ;
- **Index** — lance l'indexation négociée ;
- **Reindex Full** — force une indexation complète ;
- **Plan** — exécute `index --dry-run` sans publier de snapshot ;
- **Doctor** — lance le diagnostic MINOS ;
- **Architecture** — charge l'architecture module-level ;
- **Git** — charge l'activité Git factuelle.

L'IDE n'écrit jamais directement les snapshots. Les opérations d'indexation passent par le lifecycle MINOS normal de staging/promotion atomique.

### Architecture

**Architecture** charge :

```text
minos architecture <project> --format json
```

Le graphe affiche uniquement les modules et `moduleDependencies` fournis par MINOS. Il ne déduit pas de nouvelles arêtes.

### Results

Les actions affichent le JSON MINOS complet dans **Results**. Toute position source contenant `fileId`, ligne et colonne apparaît dans la liste de navigation.

Double-cliquer une position ouvre le fichier dans IntelliJ à l'emplacement exact.

MINOS normalise :

```text
ligne   : base 1
colonne : base 0
encodage: UTF8_CODE_UNITS | UTF16_CODE_UNITS | UTF32_CODE_UNITS | UNKNOWN
```

Le plugin convertit les colonnes en offset UTF-16 IntelliJ avant navigation.

Les documents sémantiques exposent une plage de lignes mais pas une colonne exacte ; le plugin **n'invente donc pas de position précise** pour ces résultats.

### Git Activity

**Git** charge l'activité factuelle via `GitIntelligenceService` exposé par `minos git-activity`.

**Activité ≠ importance architecturale ou métier.** Le plugin n'attribue aucun score d'importance à partir de la fréquence des commits.

## Menu contextuel éditeur

Clic droit dans l'éditeur → **MINOS**.

Actions M18 conservées :

| Action | Résultat |
|---|---|
| Open MINOS definition | résout le symbole sous le caret et ouvre sa définition MINOS |
| Find MINOS usages | usages résolus et positions navigables |
| Find MINOS dependents | relations `DEPENDS_ON` entrantes |
| Find MINOS implementations | relations `IMPLEMENTS` entrantes |
| MINOS related tests | tests liés avec nature/confiance/preuves |
| Analyze MINOS impact (baseline) | impact M8 potentiel et borné |
| Show in MINOS architecture | ouvre le graphe d'architecture sur le module du symbole |
| Copy MINOS symbol identity | copie l'id stable et, si disponible, le nom qualifié |

Le symbole sous le caret est d'abord résolu par le PSI IntelliJ pour obtenir son nom, puis rapproché des symboles MINOS par fichier et ligne. La vérité d'identité finale reste l'id du snapshot MINOS.

## Advanced Intelligence — M19

Sous-menu **MINOS → Advanced Intelligence** :

### Show MINOS Program Graph

Exécute via le protocole IDE l'équivalent de :

```text
minos ide program-graph <project> --max-nodes <borne> --max-edges <borne> --format json
```

Le résultat conserve :

```text
capabilities
nodes / edges
nature
confidence
providerId
evidence
limitations
```

Le plugin ne fabrique ni CFG, ni def-use, ni data-flow. Une capability absente reste absente et apparaît dans les limitations du moteur.

### Analyze MINOS Impact v2

Résout le symbole sous le caret puis appelle :

```text
minos ide impact-v2 <project> <symbolId> --format json
```

**Impact v2 ne remplace pas silencieusement M8.** L'action baseline reste disponible séparément ; M19 conserve le résultat M8 et ajoute les impacts Program Graph non déjà présents.

L'impact reste potentiel : dynamique, réflexion et configuration runtime ne sont pas prouvées exhaustivement.

### Analyze MINOS security paths

Appelle l'analyse source→sink bornée M19 :

```text
minos ide security-paths <project> --format json
```

Le résultat représente des **chemins statiques observés**, avec leurs limitations. En particulier :

```text
absence de chemin observé ≠ preuve de sûreté
```

Sans annotations/capabilities SOURCE, SINK ou flux nécessaires, le résultat reste vide et explique la limitation ; le plugin ne transforme jamais cette absence en diagnostic « sûr ».

## Semantic & Hybrid — M20

Sous-menu **MINOS → Semantic & Hybrid**.

### Semantic index status

```text
minos ide semantic-index-status <project> --format json
```

États possibles : `DISABLED`, `NO_ACTIVE_SNAPSHOT`, `MISSING`, `STALE`, `READY`.

Le provider d'embeddings est **désactivé par défaut**. La distribution native peut opter explicitement pour le provider de référence avec :

```text
MINOS_SEMANTIC_PROVIDER=local-hash
```

`local-hash` est un mécanisme local de feature hashing de référence, **pas un language model**.

### Synchronize semantic index

```text
minos ide semantic-index-sync <project> --format json
```

L'index est reconstruisible depuis le snapshot actif. La synchronisation réutilise les vecteurs dont la `stableKey`/checksum reste inchangée et ne modifie jamais l'autorité des facts structurés.

### Semantic search…

Demande une requête puis appelle une recherche bornée.

Le score vectoriel est :

```text
HEURISTIC
ranking/rappel uniquement
jamais une preuve de relation de code
```

Le backend courant effectue un scan vectoriel linéaire ; son évolution relève de M21-S8/M23 et doit être justifiée par mesures.

### Hybrid search…

Combine les signaux :

```text
LEXICAL   DERIVED
GRAPH     DERIVED
SEMANTIC  HEURISTIC, uniquement si disponible
```

Si le sémantique est indisponible, MINOS utilise son fallback structuré lexical+graph et expose explicitement cette limitation.

Le score hybride est une **sélection/ranking dérivé**, pas un nouveau fact de Code Intelligence.

### Build hybrid context…

Construit un contexte borné en documents/tokens à partir du ranking hybride. Le plugin transmet uniquement la requête et les bornes ; la sélection et la troncature restent exécutées dans MINOS Java 24.

## Frontière d'architecture

S6 conserve strictement :

```text
IntelliJ Java 21
    │
    ├── UI / PSI / navigation
    ├── ProcessBuilder
    └── JSON minos-ide v1
            │
            ▼
MINOS Java 24
    ├── ProgramGraphService
    ├── AdvancedImpactService
    ├── SecurityAnalysisService
    ├── SemanticIndexService
    ├── SemanticSearchService
    ├── HybridSearchService
    └── HybridContextBuilder
```

Le build du plugin ne dépend d'aucun artefact Maven `com.minos:*`.

## Compatibilité IntelliJ

Le plugin cible Java 21 et `sinceBuild=261`, soit la branche IntelliJ Platform 2026.1. La qualification M21-S6 utilise le Plugin Verifier sur la distribution courante et les releases stables `261` résolues par le plugin Gradle JetBrains.

Cette politique ne prétend pas supporter les branches 2025.x : élargir `sinceBuild` nécessite une qualification distincte.

## Exécution en arrière-plan

Les commandes MINOS sont exécutées hors de l'Event Dispatch Thread IntelliJ.

Le client :

- transmet les arguments séparément à `ProcessBuilder` ;
- adapte explicitement les launchers `.cmd/.bat` sous Windows ;
- lit stdout/stderr sans bloquer le processus ;
- applique le timeout configuré ;
- tue un processus dépassant ce délai ;
- affiche les erreurs dans la Tool Window sans modifier le snapshot actif.

## Dépannage

### `Cannot start MINOS executable`

Tester :

```powershell
minos.cmd ide handshake --format json
```

puis vérifier **MINOS executable**.

### `Connected MINOS runtime does not advertise IDE capability ...`

Le plugin est plus récent que le moteur pour l'action demandée. Mettre MINOS à jour ; ne pas contourner la negotiation de capability.

### `Incompatible MINOS IDE protocol`

Le plugin et le moteur n'utilisent pas une version compatible du protocole. Mettre à niveau l'un des deux ; ne pas contourner le contrôle.

### `This IntelliJ project is not registered in MINOS`

Cliquer **Register** ou exécuter `minos project add` sur la racine exacte ouverte par IntelliJ.

### Projet enregistré mais état `STALE`

Cliquer **Plan** pour comprendre la raison, puis **Index** ou **Reindex Full** si nécessaire.

### Semantic index `DISABLED`

C'est l'état attendu tant qu'aucun provider d'embeddings n'est explicitement configuré. Les recherches hybrides structurées peuvent continuer sans signal sémantique.

### Pas de résultat sur le symbole courant

1. vérifier `index-status` ;
2. rafraîchir/indexer le projet ;
3. placer le caret sur un élément nommé ou sélectionner précisément le symbole ;
4. vérifier que le provider qualifié expose la relation/capability demandée.

Une capability provider absente n'est jamais simulée par le plugin.

### Navigation refusée : fichier hors racine

Le plugin bloque volontairement une location MINOS qui sortirait de la racine du projet enregistré.

## Différence avec l'intégration MCP dans Copilot

Deux intégrations IntelliJ peuvent coexister :

```text
Plugin MINOS
  → UI native / navigation / architecture / M19-M20 / index lifecycle

GitHub Copilot + MCP MINOS
  → agent IA consommant les tools MCP read-only
```

Le plugin MINOS ne nécessite ni Copilot, ni Claude, ni Codex, ni aucun LLM.
