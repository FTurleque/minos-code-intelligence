# Guide utilisateur MINOS

Ce guide s’adresse à une personne qui veut **installer, alimenter et interroger MINOS** sans modifier son code source.

## Parcours recommandé M14

```text
installer MINOS
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

L’utilisateur normal ne prépare plus `index.scip` manuellement. `minos index <project>` découvre le projet, sélectionne le provider qualifié, calcule la portée d’indexation, exécute le provider puis promeut le nouveau snapshot de manière atomique.

## Démarrage rapide Windows

Après installation d’une distribution :

```powershell
minos.cmd --version
minos.cmd doctor
minos.cmd tools list
```

Installer le provider requis, par exemple Java :

```powershell
$env:JAVA_HOME = 'C:\path\to\project-jdk'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
minos.cmd tools install scip-java
```

Puis :

```powershell
minos.cmd project add N:\workspace-dev\my-project --name my-project
minos.cmd index my-project --dry-run
minos.cmd index my-project
minos.cmd index-status my-project
minos.cmd search my-project GreetingPort --format json
```

## Import SCIP manuel

Le chemin manuel est conservé pour le diagnostic :

```powershell
minos.cmd import-scip my-project `
  --file N:\temp\index.scip `
  --provider external-provider
```

`minos index --scip ...` reste temporairement accepté pour compatibilité mais est déprécié.

## Fonctionnalités principales

| Besoin | Commande / surface |
|---|---|
| Diagnostiquer l’installation | `doctor` |
| Gérer les providers | `tools list/install/verify` |
| Enregistrer un projet | `project add` |
| Voir les projets | `project list` |
| Inspecter l’état | `inspect`, `index-status` |
| Indexer automatiquement | `index` |
| Importer un SCIP explicitement | `import-scip` |
| Rechercher du contexte | `search` |
| Trouver un symbole | `find-symbol` |
| Lire un fichier source | `get-source` |
| Trouver les usages | `find-usages` |
| Implémentations/appels/dépendances | commandes relationnelles |
| Trouver les tests liés | `related-tests` |
| Lire l’architecture | `architecture` |
| Estimer un impact | `impact` |
| Consommer depuis Java | `MinosApi`, `MinosMultiRepositoryApi` |
| Exposer MINOS à un agent | `minos mcp` |
| Exporter vers NEXUS | `nexus-export` |

## Documentation

- [Installation PROD Windows](production-installation.md)
- [Indexation autonome](autonomous-indexing.md)
- [Installation depuis les sources / développement](installation.md)
- [Référence CLI](cli.md)
- [API Java locale](java-api.md)
- [Serveur MCP](mcp.md)
- [Intégration NEXUS](nexus.md)
- [Dépannage](troubleshooting.md)
