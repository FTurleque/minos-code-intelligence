# Installation PROD de MINOS sous Windows

Ce guide décrit le parcours utilisateur de MINOS sous Windows.

Le parcours normal ne nécessite **ni clone Git, ni Maven, ni JDK pour exécuter MINOS** : la distribution Windows contient son propre runtime Java. Les toolchains d'un projet analysé peuvent en revanche rester nécessaires à ses providers.

> État au **8 août 2026** : `v1.0.0` est publiée et immuable. `1.0.1` reste **NON PUBLIÉE** ; M29 et M30 sont intégrés, puis le hardening release/installer est qualifié sur PR #113 avant construction d'un nouveau candidat.

## 1. Parcours recommandé

Utiliser le setup Windows :

```text
MINOS-<version>-windows-x64-setup.exe
        ↓
vérifier SHA-256
        ↓
lancer setup.exe
        ↓
Type d'installation
  ├── Standard — recommandé
  └── Avancée
        ↓
Mode MCP — un seul choix
  ├── MCP natif Windows — recommandé
  ├── MCP Docker — isolation renforcée
  └── Ne pas configurer maintenant
        ↓
[Avancée] Stockage
  ├── Local intégré
  └── PostgreSQL + pgvector
        ↓
[Avancée] Recherche sémantique
  ├── Désactivée
  ├── Hash local
  └── Ollama
        ↓
Clients IA détectés
        ↓
[Docker] racine projets / données / instance
        ↓
Résumé de l'installation
        ↓
installation + validation + handshake
```

Le ZIP reste une distribution portable/automatisation/diagnostic. Le checkout source est réservé au développement.

## 2. Assets d'une release Windows

Une release complète publie :

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

Exemple de vérification :

```powershell
$Version = '1.0.1'
Get-FileHash ".\MINOS-$Version-windows-x64-setup.exe" -Algorithm SHA256
Get-Content ".\MINOS-$Version-windows-x64-setup.exe.sha256"
```

Les empreintes doivent être identiques. Ne pas exécuter un setup dont le hash ne correspond pas.

## 3. Programme et données

Programme par défaut :

```text
%LOCALAPPDATA%\Programs\MINOS
```

Données par défaut :

```text
%LOCALAPPDATA%\MINOS\data
```

État global des intégrations et backups :

```text
%LOCALAPPDATA%\MINOS\mcp-client-integrations.json
%LOCALAPPDATA%\MINOS\codex-mcp-integration.json
%LOCALAPPDATA%\MINOS\mcp-clients.log
%LOCALAPPDATA%\MINOS\backups\mcp-clients\...
```

Le programme et les données sont séparés afin qu'un upgrade ou une désinstallation standard ne détruise pas les index/snapshots de l'utilisateur.

## 4. Type d'installation

### Standard — recommandé

Standard conserve les defaults sûrs et limite les choix avancés. Le point d'entrée MCP reste sélectionnable et MINOS utilise ses defaults de stockage/sémantique.

### Avancée

Avancée expose :

- `MINOS_HOME` / data root ;
- nom du serveur MCP ;
- backend MCP ;
- stockage local ou PostgreSQL/pgvector ;
- provider sémantique ;
- configuration PostgreSQL externe si applicable ;
- configuration Ollama ;
- racines et identité du runtime Docker.

Les trois axes principaux sont indépendants :

```text
runtime MCP   native | docker | none
storage       local | postgresql
semantic      disabled | local-hash | ollama
```

## 5. Mode MCP

Le wizard propose exactement :

```text
( ) MCP natif Windows — recommandé
( ) MCP Docker — isolation renforcée
( ) Ne pas configurer maintenant
```

Un seul choix est possible.

Lors d'un upgrade, le backend reconnu dans :

```text
%LOCALAPPDATA%\MINOS\data\runtime\backend.properties
```

ou dans l'état durable de l'installateur est présélectionné.

### Contrat backend-agnostic

Tous les clients utilisent :

```text
command = %LOCALAPPDATA%\Programs\MINOS\app\minos.exe
args    = mcp
env     = MINOS_HOME=%LOCALAPPDATA%\MINOS\data
```

Les configurations clientes ne contiennent ni `docker exec`, ni nom de conteneur, ni fichier Compose. Le routage se fait derrière `minos.exe mcp`.

Changer `native ↔ docker` ne nécessite donc pas de réécrire Copilot, Claude ou Codex.

### Docker fail-closed

Si **MCP Docker** est explicitement choisi, Docker Desktop et son daemon Linux doivent être disponibles.

Si Docker ne répond pas, le wizard bloque et indique de démarrer/corriger Docker ou de revenir choisir un autre mode. MINOS ne bascule jamais silencieusement vers le natif.

## 6. Stockage

### Local intégré

Le backend local reste le choix par défaut et conserve les stores MINOS historiques.

### PostgreSQL + pgvector

Le backend PostgreSQL est réel et utilise pgvector pour le stockage/retrieval vectoriel applicable.

- avec runtime natif : le wizard demande une URL JDBC, un utilisateur, un secret et un schéma ; la base est externe au setup ;
- avec runtime Docker : MINOS peut gérer PostgreSQL/pgvector dans le stack Docker et un volume persistant dédié.

Il n'existe pas de fallback silencieux PostgreSQL→local.

Une base PostgreSQL externe n'est jamais supprimée par l'uninstaller MINOS.

## 7. Recherche sémantique

Le wizard avancé propose :

```text
Désactivée — recommandé
Hash local — déterministe, sans modèle IA
Ollama — modèle local
```

La recherche structurée reste autoritative ; la recherche sémantique/hybride reste explicitement heuristique.

### Ollama avec runtime natif

MINOS se connecte à une instance Ollama locale existante via un endpoint loopback autorisé.

**Le setup MINOS n'installe pas de binaire Ollama natif.**

### Ollama avec runtime Docker

MINOS peut gérer un sidecar Ollama sur le réseau interne du stack. Aucun port public n'est nécessaire au query plane.

Le wizard peut demander le téléchargement/provisionnement d'un modèle. Dans ce cas, l'egress nécessaire au pull est temporaire et retiré ensuite.

## 8. Clients IA

La page **Clients IA** apparaît lorsqu'un backend MCP est sélectionné.

Une intégration indisponible :

- reste désactivée ;
- affiche une raison ;
- n'est jamais écrite automatiquement.

Une intégration disponible n'est configurée que si l'utilisateur coche explicitement la case.

Le wizard expose séparément :

```text
[ ] GitHub Copilot — JetBrains / IntelliJ
[ ] GitHub Copilot CLI
[ ] Claude CLI / Claude Code
[ ] Claude Desktop
[ ] OpenAI Codex CLI
[ ] OpenAI Codex Desktop
```

### GitHub Copilot — JetBrains / IntelliJ

MINOS gère l'entrée MCP dans la configuration JetBrains/Copilot détectée en conservant les autres serveurs.

### GitHub Copilot CLI

Le préflight ne se contente pas de `Get-Command`. Il vérifie une capability MCP compatible. Un launcher `copilot` provenant de VS Code est rejeté explicitement.

### Claude CLI / Claude Code

Le préflight vérifie la commande `claude` (PATH ou launcher embarqué connu) et sa capability MCP.

Après configuration :

```powershell
claude mcp get minos
claude mcp list
```

### Claude Desktop

MINOS fusionne `mcpServers.<nom>` dans la configuration Claude Desktop détectée, avec backup/ownership. Relancer complètement Claude Desktop après modification.

### OpenAI Codex CLI

Lorsque la capability MCP du CLI est prouvée, MINOS peut utiliser le mode `cli` pour enregistrer l'intégration.

### OpenAI Codex Desktop

Codex Desktop utilise le bloc géré dans :

```text
%USERPROFILE%\.codex\config.toml
```

Une section `mcp_servers.<nom>` non détenue par MINOS n'est jamais écrasée.

Codex CLI et Codex Desktop ciblent la même intégration MINOS nommée : le wizard les détecte séparément mais interdit de cocher les deux simultanément.

## 9. Ownership et backups

Règles :

- ne jamais écraser une entrée MCP tierce ;
- sauvegarder les fichiers utilisateur avant modification ;
- conserver les autres serveurs/propriétés ;
- enregistrer l'ownership MINOS ;
- préserver une entrée gérée si l'utilisateur l'a modifiée ;
- supprimer sélectivement uniquement ce qui appartient encore à MINOS.

## 10. Résumé avant installation

La page **Résumé de l'installation** est la dernière revue fonctionnelle avant installation.

Elle affiche :

- Standard / Avancée ;
- répertoire programme ;
- data root ;
- backend MCP ;
- nom serveur MCP ;
- stockage ;
- provider sémantique ;
- clients IA sélectionnés ;
- racine projets Docker et instance, si applicable ;
- PostgreSQL/pgvector géré par MINOS, si applicable ;
- Ollama Docker géré, si applicable ;
- téléchargement/provisionnement du modèle Ollama lorsque demandé.

Le but est de montrer **ce qui sera réellement installé/configuré** avant le lancement.

## 11. Installation transactionnelle

Le backend MCP est activé selon :

```text
prepare
→ validate
→ handshake MCP
→ commit backend.properties
→ retire ancien backend
```

Le handshake exige au minimum :

```text
initialize
notifications/initialized
tools/list
```

Un échec conserve ou restaure le backend précédent ; aucun fallback silencieux n'est utilisé.

Journal principal :

```text
%LOCALAPPDATA%\MINOS\data\runtime\backend-switch.log
```

## 12. Vérifier l'installation

Dans un nouveau terminal :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools list
```

Pour les intégrations :

```powershell
Get-Content "$env:LOCALAPPDATA\MINOS\mcp-clients.log" -Tail 100
```

La preuve MCP réelle reste un handshake `initialize → tools/list` depuis le runtime packagé et, pour l'usage quotidien, un état `Connected` dans le client IA utilisé.

## 13. Upgrade

L'upgrade préserve :

- `MINOS_HOME` ;
- backend MCP reconnu ;
- choix durable storage/semantic ;
- ownership des intégrations ;
- données locales ;
- volumes Docker gérés sauf purge explicitement demandée.

Le switch backend est rollback-safe et un runtime Docker compatible peut être réutilisé sans reconstruction inutile.

## 14. Désinstallation

Par défaut, l'uninstaller supprime le programme et les intégrations toujours détenues par MINOS mais **conserve les données**.

En désinstallation interactive, MINOS demande explicitement si l'utilisateur souhaite également purger les données :

```text
Supprimer également toutes les données MINOS locales ?

Oui  → purge explicite
Non / conserver → choix par défaut
```

Le bouton par défaut est **Non / conserver**.

La purge complète peut supprimer :

- `%LOCALAPPDATA%\MINOS` ;
- snapshots/index/logs/backups ;
- runtime/data Docker MINOS ;
- volumes PostgreSQL/Ollama gérés par MINOS.

Elle ne supprime jamais une base PostgreSQL externe.

Les smoke/silent uninstall automatisés ne posent pas ce prompt et ne purgent pas les vraies données utilisateur.

## 15. Release 1.0.1

1.0.1 reste **NON PUBLIÉE** jusqu'à qualification exact-head complète : Windows/Linux, PostgreSQL/pgvector, scan de vulnérabilités, JaCoCo, MCP, IntelliJ et compilation/smoke du setup.

Voir [`../releases/1.0.1.md`](../releases/1.0.1.md).
