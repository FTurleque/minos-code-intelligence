# M2 — Commande `find-symbol`

Statut : **incrément validé localement**

Date : **23 juillet 2026**

## Objectif

Cet incrément expose la recherche normalisée de symboles derrière une commande
CLI déterministe, sans faire dépendre la syntaxe du registre, du stockage ou de
SCIP.

## Syntaxe

```text
minos find-symbol <project> <symbol> [options]
```

Options :

```text
--qualified-name <name>  filtre exact sur le nom qualifié
--kind <kind>            filtre sur le type de symbole
--module <module>        filtre sur l'identifiant de module
--limit <count>          maximum de résultats, 20 par défaut, 1000 au plus
--format <text|json>     format de sortie, text par défaut
-h, --help               aide de la commande
```

Les noms de kinds et de formats sont insensibles à la casse. Les options
dupliquées, inconnues, incomplètes ou invalides sont rejetées avant toute
interrogation du projet.

## Codes de sortie

| Code | Signification |
|---:|---|
| `0` | commande réussie ou aide affichée |
| `1` | échec de résolution ou d'interrogation du projet/index actif |
| `2` | erreur de syntaxe ou commande inconnue |

Les données réussies sont écrites sur la sortie standard. Les erreurs et aides
associées à une syntaxe invalide sont écrites sur la sortie d'erreur.

## Frontière de bootstrap

```text
MinosCli
    → FindSymbolCommand
        → ProjectSymbolQuery
            → résolution projet + snapshot actif
            → SymbolQueryService
        → SymbolResultRenderer
```

`ProjectSymbolQuery` est le port du bootstrap produit. Son implémentation locale
`LocalProjectSymbolQuery` résout `<project>` par UUID ou par nom unique, charge
le snapshot actif puis appelle le service de requête. La commande ne connaît ni
fichier SCIP, ni Protobuf, ni chemin interne du registre.

`MinosLauncher` sélectionne le répertoire local dans cet ordre : propriété Java
`minos.home`, variable `MINOS_HOME`, puis `%USERPROFILE%\.minos`. Le chargement
est paresseux : l'aide et les erreurs de syntaxe n'écrivent rien sur disque.

Après construction du JAR :

```powershell
.\mvnw.cmd clean package
$env:MINOS_HOME = 'D:\minos-data'
.\minos.cmd find-symbol <project> <symbol> --format json
```

Le registre et le snapshot actif doivent avoir été publiés par le pipeline
d'indexation. `FileSymbolSnapshotStore` et `ScipSymbolSnapshotImporter`
fournissent les API locales nécessaires à cette publication.

## Couverture

Les tests vérifient :

- le dispatch `find-symbol` depuis `MinosCli` ;
- la transmission de tous les critères structurés ;
- les valeurs par défaut bornées ;
- un résultat JSON non vide rendu via `SymbolResultRenderer` ;
- l'aide racine et l'aide de commande ;
- les commandes, options, kinds, formats et limites invalides ;
- l'absence d'appel du port en cas d'erreur de syntaxe ;
- la conversion d'un snapshot indisponible en code de sortie `1` ;
- la réouverture du registre et du snapshot dans un nouveau processus Java ;
- l'absence d'écriture disque pour `--help` ;
- l'absence de types fournisseur dans le package `cli`.

## Validation locale

```text
.\mvnw.cmd clean verify
69 sources main compilées
29 sources test compilées
86 tests réussis
0 échec
0 erreur
BUILD SUCCESS
```

Le wrapper Windows `minos.cmd --help` a été exécuté avec un code de sortie `0`.
Le workflow `find-symbol` avec données est couvert dans un nouveau processus par
`MinosLauncherTest`.
