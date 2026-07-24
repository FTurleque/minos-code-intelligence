# Guide utilisateur MINOS

Ce guide s'adresse à une personne qui veut **installer, alimenter et interroger MINOS sans modifier son code source**.

Le parcours utilisateur normal commence par une **GitHub Release Windows**. Il ne faut pas cloner le dépôt MINOS ni lancer Maven pour installer le produit.

## Parcours recommandé

```text
GitHub Release
   ↓
ZIP + SHA-256
   ↓
Installation PROD Windows
   ↓
minos doctor
   ↓
minos tools install <provider>
   ↓
minos project add <path> --name <name>
   ↓
minos index <name>
   ↓
search / architecture / impact / MCP
```

L'utilisateur normal ne prépare plus `index.scip` manuellement. `minos index <project>` découvre le projet, sélectionne le provider qualifié, calcule la portée d'indexation, exécute le provider puis promeut le nouveau snapshot de manière atomique.

---

## 1. Installer MINOS

Commencer ici :

**[Installation PROD Windows](production-installation.md)**

Le guide couvre maintenant :

- téléchargement depuis GitHub Releases ;
- différence release stable / pre-release ;
- vérification SHA-256 ;
- installation utilisateur et Program Files ;
- emplacement du programme et de `MINOS_HOME` ;
- premier démarrage ;
- providers Java et TypeScript ;
- premier projet ;
- MCP natif et Docker optionnel ;
- mise à jour ;
- rollback ;
- désinstallation ;
- publication d'une release pour les mainteneurs.

Le parcours `git clone` / Maven est volontairement séparé dans [Installation depuis les sources](installation.md).

---

## 2. Démarrage rapide après installation

Dans un nouveau PowerShell :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools list
```

Pour un projet Java :

```powershell
$env:JAVA_HOME = 'C:\path\to\project-jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
minos.cmd tools install scip-java
```

Puis :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd inspect my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd index-status my-project
minos.cmd search my-project GreetingPort --format json
```

---

## 3. Choisir le bon document

| Besoin | Document |
|---|---|
| Télécharger, installer, mettre à jour ou désinstaller MINOS | [Installation PROD Windows](production-installation.md) |
| Comprendre l'indexation automatique | [Indexation autonome](autonomous-indexing.md) |
| Connaître toutes les commandes | [Référence CLI](cli.md) |
| Connecter un client MCP | [Serveur MCP](mcp.md) |
| Utiliser MINOS depuis Java | [API Java locale](java-api.md) |
| Exporter vers NEXUS | [Intégration NEXUS](nexus.md) |
| Diagnostiquer un problème | [Dépannage](troubleshooting.md) |
| Développer MINOS lui-même | [Installation depuis les sources](installation.md) |

---

## 4. Fonctionnalités principales

| Besoin | Commande / surface |
|---|---|
| Diagnostiquer l'installation | `doctor` |
| Gérer les providers | `tools list/install/verify` |
| Enregistrer un projet | `project add` |
| Voir les projets | `project list` |
| Inspecter l'état | `inspect`, `index-status` |
| Indexer automatiquement | `index` |
| Importer un SCIP explicitement | `import-scip` |
| Rechercher du contexte | `search` |
| Trouver un symbole | `find-symbol` |
| Lire un fichier source | `get-source` |
| Trouver les usages | `find-usages` |
| Implémentations/appels/dépendances | commandes relationnelles |
| Trouver les tests liés | `related-tests` |
| Lire l'architecture | `architecture` |
| Estimer un impact | `impact` |
| Consommer depuis Java | `MinosApi`, `MinosMultiRepositoryApi` |
| Exposer MINOS à un agent | `minos mcp` |
| Exporter vers NEXUS | `nexus-export` |

---

## 5. Import SCIP manuel

Le chemin manuel est conservé pour le diagnostic ou pour un provider non piloté par MINOS :

```powershell
minos.cmd import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

`minos index --scip ...` reste temporairement accepté pour compatibilité mais est déprécié.

---

## 6. Où MINOS stocke ses données

Par défaut :

```text
programme : %LOCALAPPDATA%\Programs\MINOS
MINOS_HOME: %LOCALAPPDATA%\MINOS\data
```

La séparation est volontaire : mettre à jour ou remplacer le programme ne doit pas supprimer les données persistantes.

Voir [Installation PROD Windows](production-installation.md#7-programme-et-données-persistantes).

---

## 7. En cas de problème

Commencer par :

```powershell
minos.cmd --version
minos.cmd doctor --format json
minos.cmd project list --format json
```

Puis consulter [Dépannage](troubleshooting.md).
