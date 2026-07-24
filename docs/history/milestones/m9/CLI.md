# M9 — CLI stabilisée

Date : **23 juillet 2026**

Statut : **IMPLÉMENTÉ — VALIDATION LOCALE FINALE EN ATTENTE**

Suivi : issue #29.

## Objectif

M9 stabilise la CLI destinée aux développeurs et aux automatisations sans déplacer de logique métier dans les commandes. Les commandes délèguent aux services MINOS M1 à M8 et conservent la frontière fournisseur.

## Bootstrap local

Le home MINOS est résolu dans cet ordre :

```text
-Dminos.home=<path>
MINOS_HOME=<path>
${user.home}/.minos
```

L'aide (`--help`) est lazy : elle ne crée aucun répertoire local.

## Codes de sortie

```text
0  succès
1  erreur d'exécution
2  erreur d'usage
```

Les erreurs sont écrites sur `stderr`. Une sortie JSON réussie ne contient aucun texte parasite.

## Formats

Les commandes publiques stabilisées supportent :

```text
--format text
--format json
```

Le JSON est produit par un encodeur minimal partagé, sans dépendance de sérialisation ajoutée au cœur.

## Registre projet

### Ajouter

```powershell
minos project add N:\workspace-dev\my-project --name my-project --format json
```

Sans `--name`, le nom du dernier segment du chemin est utilisé.

### Lister

```powershell
minos project list --format json
```

### Inspecter

```powershell
minos project inspect my-project --format json
minos inspect my-project --format json
```

L'inspection expose :

- UUID projet ;
- nom ;
- chemin local ;
- disponibilité du chemin ;
- langages détectés ;
- builds détectés ;
- nombre de modules ;
- état observable de l'index ;
- snapshot actif ;
- dernière date/provider d'import CLI M9 lorsqu'ils sont connus.

## Indexation / import

Le dépôt possède le contrat `IndexerExecutor`, mais **aucun runner de processus d'indexeur de production n'est encore implémenté**. M9 ne prétend donc pas lancer automatiquement `scip-java` ou `scip-typescript`.

La commande stable importe explicitement un artefact SCIP existant :

```powershell
minos index my-project `
  --scip .\.minos\index.scip `
  --provider scip-typescript `
  --provider-version 0.4.0 `
  --format json
```

Options :

```text
--scip <file>              obligatoire
--provider <id>            obligatoire
--provider-version <ver>   optionnel
--module <module>           optionnel
--snapshot <id>             optionnel
--format <text|json>
```

Sans `--snapshot`, MINOS dérive un identifiant stable du SHA-256 de l'artefact :

```text
scip-<24 premiers caractères hex du SHA-256>
```

L'import réutilise `ScipSymbolSnapshotImporter` et publie le snapshot normalisé M2/M3/M5 dans `FileSymbolSnapshotStore`.

### Statut

```powershell
minos index-status my-project --format json
```

`READY` signifie qu'un snapshot actif est réellement relisible. Les métadonnées `lastSuccessfulIndexAt`, provider et version ne sont renseignées que lorsqu'un import CLI M9 correspondant au snapshot actif a été observé. Un snapshot historique antérieur à M9 reste `READY` mais ces champs peuvent être `null` : aucune date n'est inventée.

## Recherche et symboles

Les commandes existantes deviennent partie de la surface stable M9 :

```text
minos search
minos find-symbol
minos get-source
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
```

Leur logique métier et leurs renderers M2 à M5 ne sont pas réécrits par M9.

## Architecture

```powershell
minos architecture my-project --format json
minos architecture my-project --module packages/api --format json
```

Sans `--module`, la commande expose la vue composée M6 : topologie, dépendances, centralité directionnelle, technologies et modules.

Avec `--module`, elle expose `ArchitectureModuleContext`.

## Impact

```powershell
minos impact my-project <symbol-id> --depth 4 --limit 200 --format json
```

Bornes conservées depuis M8 :

```text
1 <= depth <= 32
1 <= limit <= 10 000
```

Le JSON conserve :

- nature `DERIVED` ;
- symboles impactés ;
- profondeur ;
- confiance ;
- chemin explicatif complet ;
- tests potentiellement impactés ;
- limitations M8.

## Déterminisme

M9 ne modifie pas l'ordre métier des résultats. Les `Map` JSON sont construites en ordre explicite et les listes réutilisent l'ordre déterministe des services sous-jacents.

## Tests M9

### `StableCliIntegrationTest`

Replay de bout en bout sur :

```text
fixtures/typescript/typescript-modules
```

Chaîne qualifiée :

```text
project add
  -> project list
  -> index (artefact scip-typescript 0.4.0)
  -> index-status
  -> inspect
  -> architecture
  -> impact GreetingPort
```

### `StableCliHelpTest`

Vérifie que les aides M9 :

- retournent `0` ;
- écrivent sur stdout ;
- n'écrivent rien sur stderr ;
- ne créent pas le home MINOS.

### `CliJsonTest`

Vérifie l'ordre, `null`, les listes et l'échappement JSON.

## Limites explicites

M9 ne fournit pas encore :

- lancement automatique d'un binaire d'indexeur ;
- téléchargement/installation d'indexeurs ;
- serveur MCP ;
- API réseau ;
- auto-complétion shell ;
- format JSON versionné par protocole externe.

Les trois premiers appartiennent à des couches ou jalons distincts ; M9 ne les simule pas.

## Porte finale

```powershell
.\mvnw.cmd clean verify
```

La PR M9 reste Draft jusqu'à validation locale réussie de son **head exact**.
