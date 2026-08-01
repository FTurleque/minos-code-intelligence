# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours utilisateur de **MINOS Code Intelligence 1.x** sous Windows.

Le parcours normal ne nécessite **ni clone Git de MINOS, ni Maven, ni JDK pour exécuter MINOS** : la distribution Windows embarque son propre runtime Java. Le JDK/Maven/Node/etc. peuvent en revanche être nécessaires au projet analysé et à son provider.

> État au 1er août 2026 : `v1.0.0` est publiée. Un défaut du runtime Java embarqué affectant le MCP natif a été identifié après publication. Le correctif est préparé pour `1.0.1`, qui reste **non publiée** tant que le setup final n'a pas été vérifié manuellement.

## 1. Assets d'une release Windows complète

Une release 1.x complète publie huit assets :

```text
MINOS-<version>-windows-x64-setup.exe
MINOS-<version>-windows-x64-setup.exe.sha256

minos-<version>-windows-x64.zip
minos-<version>-windows-x64.zip.sha256

minos-<version>.cdx.json
minos-<version>.cdx.json.sha256

MINOS-<version>-THIRD-PARTY-NOTICES.txt
MINOS-<version>-THIRD-PARTY-NOTICES.txt.sha256
```

Le `setup.exe` est le canal recommandé pour un poste utilisateur. Le ZIP sert aux usages portables, automatisés et de diagnostic.

## 2. Vérifier le setup avant installation

Exemple pour 1.0.1 une fois publiée :

```powershell
$Version = '1.0.1'
Get-FileHash ".\MINOS-$Version-windows-x64-setup.exe" -Algorithm SHA256
Get-Content ".\MINOS-$Version-windows-x64-setup.exe.sha256"
```

Les empreintes SHA-256 doivent être identiques. Ne pas exécuter le setup en cas de différence.

## 3. Installation

Lancer :

```text
MINOS-<version>-windows-x64-setup.exe
```

Installation par défaut :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Le setup installe notamment :

```text
MINOS
├── app\
│   ├── minos.exe
│   └── runtime\                  runtime Java embarqué
├── lib\minos.jar
├── integration\
│   ├── detect-mcp-clients.ps1
│   ├── configure-mcp-clients.ps1
│   ├── configure-mcp-clients-setup.ps1
│   ├── configure-codex-mcp.ps1
│   └── uninstall-mcp-clients.ps1
├── docker\
├── minos.cmd
├── minos-mcp.cmd
├── VERSION
├── RUNTIME-MODULES.txt
├── RELEASE-MANIFEST.json
└── supply-chain\
```

`RUNTIME-MODULES.txt` enregistre les modules présents dans le runtime Java produit. À partir de 1.0.1, la liste est dérivée du JAR final avec `jdeps` puis vérifiée sur l'image `jpackage` créée.

## 4. Page « Intégrations MCP natives »

À partir du candidat 1.0.1, les clients MCP ne sont plus de simples cases statiques dans **Additional Tasks**.

Le Wizard affiche une page dédiée :

```text
Intégrations MCP natives
Connecter le MCP natif MINOS à vos clients IA détectés
```

Chaque client reçoit :

- une case disponible uniquement si le mode d'intégration attendu est réellement détecté ;
- un diagnostic visible ;
- aucune sélection par défaut ;
- aucune écriture dans un client tiers tant que l'utilisateur ne coche pas explicitement la case.

Exemple :

```text
☐ GitHub Copilot — JetBrains / IntelliJ
  Détecté

☐ GitHub Copilot CLI
  Non disponible — launcher VS Code détecté, vrai CLI absent

☐ Claude Code
  Non disponible — commande introuvable

☐ Claude Desktop
  Détecté

☐ OpenAI Codex
  Détecté — Codex Desktop (configuration via fichier utilisateur)
```

Le préflight est borné et non interactif. Pour les CLI, la présence d'un nom de commande ne suffit pas : MINOS vérifie la capacité MCP attendue.

### 4.1 GitHub Copilot — JetBrains / IntelliJ

MINOS configure l'entrée `minos` dans :

```text
%LOCALAPPDATA%\github-copilot\intellij\mcp.json
```

Configuration cible :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Après installation, redémarrer/recharger Copilot si nécessaire puis vérifier que le serveur `minos` est `Connected` et que les tools MINOS sont visibles.

### 4.2 GitHub Copilot CLI

MINOS ne considère plus `Get-Command copilot` comme une preuve suffisante. Le setup effectue un capability probe de l'interface MCP.

Un launcher provenant de VS Code ou un shim qui ne supporte pas l'interface MCP est marqué indisponible au lieu d'être proposé comme « Copilot CLI ».

### 4.3 Claude Code

La commande `claude` doit être présente et sa surface MCP doit répondre au probe attendu. Si ce n'est pas le cas, la case est désactivée avec une raison explicite.

### 4.4 Claude Desktop

MINOS fusionne uniquement son serveur `minos` dans la configuration Claude Desktop, sans effacer les autres serveurs/propriétés.

Par défaut :

```text
%APPDATA%\Claude\claude_desktop_config.json
```

### 4.5 OpenAI Codex

Deux modes sont pris en charge :

- **Codex CLI** lorsque sa capability MCP est réellement disponible ;
- **Codex Desktop/config utilisateur** lorsque le préflight sélectionne ce mode.

Le mode Desktop utilise :

```text
%USERPROFILE%\.codex\config.toml
```

MINOS ajoute un bloc marqué et géré :

```toml
# BEGIN MINOS MANAGED MCP SERVER
[mcp_servers.minos]
command = "C:\\...\\MINOS\\app\\minos.exe"
args = ["mcp"]
enabled = true

[mcp_servers.minos.env]
MINOS_HOME = "C:\\Users\\...\\AppData\\Local\\MINOS\\data"
# END MINOS MANAGED MCP SERVER
```

Un bloc `mcp_servers.minos` existant mais non géré par MINOS n'est jamais écrasé.

## 5. Protection des configurations MCP

Les intégrations natives suivent les règles suivantes :

- sauvegarde avant modification d'un fichier utilisateur existant ;
- conservation des propriétés et serveurs non MINOS ;
- refus d'écraser une entrée `minos` non détenue par MINOS ;
- état d'ownership enregistré ;
- réinstallation idempotente ;
- préservation d'une entrée modifiée manuellement après installation ;
- désinstallation uniquement de ce que MINOS peut prouver avoir géré ;
- pour les CLI, conservation du chemin du binaire détecté afin qu'un changement de `PATH` ne rende pas automatiquement le cleanup impossible.

État/diagnostics :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\codex-mcp-integration.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

## 6. Additional Tasks

Après la page MCP, la page **Select Additional Tasks** ne contient plus les cinq clients natifs.

Elle porte uniquement les choix indépendants :

```text
Intégration Windows :
  ☑ Ajouter MINOS au PATH de l'utilisateur

MCP Docker optionnel :
  ☐ Configurer et démarrer le MCP Docker (Docker Desktop requis)
```

## 7. MCP Docker optionnel

Docker est indépendant du MCP natif. Si l'option est cochée, une page supplémentaire demande la racine des projets à exposer au conteneur.

Le montage projets est read-only. La configuration de production conserve notamment :

```text
network_mode: none
read_only: true
cap_drop: ALL
no-new-privileges: true
projects mount: read_only
```

Docker Desktop doit déjà être installé et démarré. MINOS ne l'installe pas.

## 8. Vérifier une installation 1.0.1

Après installation :

```powershell
minos.cmd --version
minos.cmd doctor
```

Puis vérifier le MCP dans le client réellement sélectionné.

Le contrôle important n'est pas seulement `--version`. La release 1.0.1 impose désormais un handshake MCP réel durant ses gates de packaging :

```text
initialize
→ notifications/initialized
→ tools/list
→ minos_search_code présent
→ minos_impact présent
```

Ce gate s'exécute sur la distribution portable installée et sur une installation setup isolée.

## 9. Pourquoi 1.0.1 corrige le crash `org/w3c/dom/Node`

Le setup 1.0.0 embarquait une image Java `jpackage` trop réduite. Le MCP initialisait Jackson/networknt JSON Schema et demandait :

```text
org.w3c.dom.Node
```

fourni par le module JDK :

```text
java.xml
```

Le correctif 1.0.1 ne se contente pas d'ajouter ce module à la main. Le build :

```text
JAR final
→ jdeps --print-module-deps
→ liste de modules racines
→ jpackage --add-modules <liste calculée>
→ runtime/bin/java --list-modules
→ contrôle de toutes les racines
→ assertion explicite java.xml
→ handshake MCP réel
```

Cela protège aussi contre de futures dépendances vers d'autres modules du JDK.

## 10. Désinstallation

Le désinstalleur :

- retire les intégrations MCP détenues par MINOS lorsque leur état correspond encore ;
- préserve les entrées préexistantes ;
- préserve une entrée utilisateur modifiée après l'installation ;
- arrête/supprime le runtime Docker géré par le setup lorsqu'il existe ;
- retire l'entrée PATH gérée par MINOS ;
- supprime le programme installé.

Les données applicatives sous `%LOCALAPPDATA%\MINOS\data` restent persistantes par conception afin de ne pas effacer automatiquement projets, snapshots et providers.

## 11. Construction locale du candidat avant publication

Pour la maintenance 1.0.1, la génération locale destinée à la validation utilisateur utilise :

```text
scripts/release/build-local-windows-candidate.ps1
```

Ce runner :

- exige un worktree propre ;
- construit avec `-Drevision=1.0.1` ;
- génère le runtime `jdeps`/`jpackage` ;
- vérifie les intégrations/preflight ;
- effectue un vrai handshake MCP sur la distribution ;
- génère le ZIP et le `setup.exe` de production ;
- **n'installe pas automatiquement le setup de production sur le poste du mainteneur** ;
- **ne crée aucun tag** ;
- **ne publie aucune GitHub Release** ;
- **ne déclenche aucun GitHub Actions**.

Artefact attendu :

```text
target\dist\MINOS-1.0.1-windows-x64-setup.exe
```

La vérification visuelle du Wizard et la connexion MCP réelle dans Copilot restent des gates humains obligatoires avant autorisation de publication.

## 12. Limites produit inchangées

La correction Windows 1.0.1 ne change pas les claims remote/hosted :

- la sandbox OS réelle du worker distant reste suivie par #98 ;
- `DENY` reste fail-closed sans backend OS qualifié ;
- Team/Hosted reste embarqué/local-first et n'est pas un SaaS opéré ;
- les observations runtime restent partielles ;
- le sémantique appris reste optionnel/heuristique.
