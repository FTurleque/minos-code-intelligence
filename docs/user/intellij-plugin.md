# Plugin IntelliJ — MINOS Code Intelligence

Le plugin **MINOS Code Intelligence** ajoute une surface IDE native au moteur MINOS local. Il ne remplace ni l'index IntelliJ ni le MCP : il consomme les faits MINOS déjà présents dans le snapshot actif.

## Prérequis

1. installer MINOS normalement ;
2. vérifier `minos.cmd doctor` sous Windows ou `minos doctor` sous Linux/macOS ;
3. disposer d'un projet local ouvrable par IntelliJ ;
4. installer le ZIP du plugin M18.

Le plugin et le moteur sont volontairement séparés : le plugin s'exécute avec la JVM IntelliJ, tandis que MINOS reste un processus local indépendant.

## Installation du plugin

À partir de l'artefact produit par le build M18 :

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

Champs :

| Champ | Rôle | Défaut |
|---|---|---|
| MINOS executable | launcher CLI local | `minos.cmd` sous Windows, `minos` ailleurs |
| MINOS_HOME | home de données explicite ; vide = résolution MINOS habituelle | vide |
| Command timeout | délai maximum d'une commande IDE | 30 s |
| Maximum architecture nodes | borne du graphe dans l'IDE | 120 |

Si MINOS n'est pas dans le `PATH`, renseigner le chemin absolu du launcher, par exemple :

```text
C:\Users\<user>\AppData\Local\Programs\MINOS\bin\minos.cmd
```

## Premier démarrage

Ouvrir la Tool Window **MINOS** à droite.

Le plugin commence par :

```text
minos ide handshake --format json
```

Le protocole M18 attendu est :

```text
protocol        = minos-ide
protocolVersion = 1
```

Une version incompatible est refusée avant toute requête métier ; mettre alors à jour MINOS ou le plugin pour aligner leurs versions.

### Projet non enregistré

Cliquer **Register** dans la Tool Window ou exécuter :

```powershell
minos.cmd project add N:\workspace-dev\mon-projet --name mon-projet
```

Le plugin associe le projet IntelliJ au projet MINOS par son chemin racine normalisé.

## Tool Window MINOS

### Project

Affiche :

- protocole IDE ;
- projet MINOS résolu ;
- état d'indexation ;
- snapshot actif ;
- provider et version ;
- dernière indexation réussie.

Actions :

- **Refresh** — recharge le statut ;
- **Register** — enregistre le projet IntelliJ dans MINOS ;
- **Index** — lance l'indexation négociée ;
- **Reindex Full** — force une indexation complète ;
- **Plan** — exécute `index --dry-run` sans publier de snapshot ;
- **Doctor** — lance le diagnostic MINOS.

L'IDE n'écrit jamais directement les snapshots. Les boutons d'indexation passent par le lifecycle MINOS normal de staging/promotion atomique.

### Architecture

**Architecture** charge exactement :

```text
minos architecture <project> --format json
```

Le graphe affiche les modules et `moduleDependencies` fournis par MINOS. Il ne déduit pas de nouvelles arêtes.

- le graphe est borné par `Maximum architecture nodes` ;
- le champ **Module filter** filtre les nœuds ;
- cliquer un nœud affiche ses détails ;
- l'action contextuelle **Show in MINOS architecture** filtre directement sur le module du symbole courant.

### Results

Les actions symboles affichent le JSON MINOS complet dans **Results**. Toute position source contenant `fileId`, ligne et colonne apparaît dans la liste supérieure.

Double-cliquer une position ouvre le fichier dans IntelliJ à l'emplacement exact.

MINOS normalise :

```text
ligne   : base 1
colonne : base 0
encodage: UTF8_CODE_UNITS | UTF16_CODE_UNITS | UTF32_CODE_UNITS | UNKNOWN
```

Le plugin convertit ces colonnes en offset UTF-16 IntelliJ avant navigation.

### Git Activity

**Git** charge l'activité factuelle des 30 derniers jours via `GitIntelligenceService` exposé par `minos git-activity`.

La vue montre notamment :

- branche et HEAD ;
- commits observés ;
- fichiers touchés ;
- zones de répertoire ;
- limitations éventuelles.

**Activité ≠ importance architecturale ou métier.** Le plugin conserve explicitement cette séparation et n'attribue aucun score d'importance à partir de la fréquence des commits.

## Menu contextuel éditeur

Clic droit dans l'éditeur → **MINOS** :

| Action | Résultat |
|---|---|
| Open MINOS definition | résout le symbole sous le caret et ouvre sa définition MINOS |
| Find MINOS usages | usages résolus et positions navigables |
| Find MINOS dependents | relations `DEPENDS_ON` entrantes |
| Find MINOS implementations | relations `IMPLEMENTS` entrantes |
| MINOS related tests | tests liés avec nature/confiance/preuves |
| Analyze MINOS impact | impact potentiel borné et chemins explicatifs |
| Show in MINOS architecture | ouvre le graphe sur le module du symbole |
| Copy MINOS symbol identity | copie l'id stable et, si disponible, le nom qualifié |

Le symbole sous le caret est d'abord résolu par le PSI IntelliJ pour obtenir son nom, puis rapproché des symboles MINOS par fichier et ligne. La vérité d'identité finale reste l'id du snapshot MINOS.

## Impact et tests liés

Le plugin n'aplatit pas les réponses en un simple score. Il conserve les champs MINOS :

```text
nature
confidence
limitations
path / evidence
resolutionStatus
```

L'analyse d'impact reste une **estimation potentielle du graphe observé**, jamais une preuve exhaustive de comportement runtime.

## Exécution en arrière-plan

Les commandes MINOS sont exécutées hors de l'Event Dispatch Thread (EDT) IntelliJ.

Le client :

- transmet les arguments séparément à `ProcessBuilder` ;
- adapte explicitement les launchers `.cmd/.bat` sous Windows ;
- lit stdout/stderr sans bloquer le processus ;
- applique le timeout configuré ;
- tue un processus dépassant ce délai ;
- affiche les erreurs dans la Tool Window sans modifier le snapshot actif.

## Dépannage

### `Cannot start MINOS executable`

Tester dans un terminal :

```powershell
minos.cmd ide handshake --format json
```

Puis vérifier le champ **MINOS executable**.

### `Incompatible MINOS IDE protocol`

Le plugin et le moteur n'utilisent pas la même version de protocole. Mettre à niveau l'un des deux ; ne pas contourner le contrôle de compatibilité.

### `This IntelliJ project is not registered in MINOS`

Cliquer **Register** ou exécuter `minos project add` sur la racine exacte ouverte par IntelliJ.

### Projet enregistré mais état `STALE`

Cliquer **Plan** pour comprendre la raison, puis **Index** ou **Reindex Full** si nécessaire.

### Pas de résultat sur le symbole courant

1. vérifier `index-status` ;
2. rafraîchir/indexer le projet ;
3. placer le caret sur un élément nommé ou sélectionner précisément le symbole ;
4. vérifier que le provider qualifié expose la relation demandée.

Une capability provider `UNSUPPORTED` n'est jamais simulée par le plugin.

### Navigation refusée : fichier hors racine

Le plugin bloque volontairement une location MINOS qui sortirait de la racine du projet enregistré. Cette protection évite qu'un résultat corrompu ou obsolète ouvre arbitrairement un autre fichier local.

## Différence avec l'intégration MCP dans Copilot

Deux intégrations IntelliJ peuvent coexister :

```text
Plugin MINOS M18
  → UI native / navigation / graphe / index lifecycle

GitHub Copilot + MCP MINOS
  → agent IA consommant les tools MCP read-only
```

Le plugin M18 ne nécessite ni Copilot, ni Claude, ni Codex, ni aucun LLM.
