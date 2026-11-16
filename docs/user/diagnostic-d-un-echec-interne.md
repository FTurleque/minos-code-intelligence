# Diagnostiquer un échec interne

Quand MINOS échoue pour une cause qu'il n'a pas prévue, il répond de façon **opaque** : l'appelant ne reçoit ni message
d'exception, ni chemin, ni cause (c'est volontaire, voir [`mcp.md`](mcp.md) et [`java-api.md`](java-api.md)). Cette page
dit comment l'opérateur retrouve ce qui s'est passé.

## Une référence relie la réponse à la trace

Chaque échec interne reçoit une **référence** de huit chiffres hexadécimaux, tirée au hasard. Elle figure :

| Où | Comment |
|---|---|
| Réponse d'un outil MCP | `error: MINOS tool execution failed (ref 3f9a01cc)` |
| Sortie d'erreur (stderr) du serveur MCP | `MCP tool execution failed (tool=…, type=…, rootCause=…, at=<Classe.méthode:ligne>, ref=3f9a01cc)` |
| API Java | une ligne du journal système `MINOS API call failed internally (code=EXECUTION_FAILURE, …, ref=…)` ; la `MinosApiException` reste sans cause, de même code et de même message |
| CLI, journal privé actif | une ligne `diagnostic reference: 3f9a01cc` après la ligne `error:` |
| Installation Windows, voie WSL2 | l'échec public nomme la distribution et la référence ; la ligne de diagnostic de l'installation porte les classes et l'origine |

La ligne de stderr ne porte jamais le message de l'exception ni une valeur d'argument : elle nomme l'outil, la classe de
l'exception, la classe de sa cause racine et la première trame de code MINOS de cette cause (`at=`, ou `unknown`).
Le serveur MCP écrit sur **stderr** (capturé par le client MCP) : stdout est réservé au protocole.

## Le journal de diagnostic privé (désactivé par défaut)

Pour garder la **pile** d'un échec, activez le journal privé :

```powershell
$env:MINOS_DIAGNOSTIC_LOG = "enabled"      # ou la propriété -Dminos.diagnostic.log=enabled (elle prime)
```

Toute autre valeur laisse le journal éteint et est signalée une fois sur stderr, sans être recopiée. Sans réglage, MINOS
n'écrit ni fichier ni dossier de journal.

Journal actif, **chaque échec interne** ajoute un fichier dans `MINOS_HOME/logs/internal-failures/` :

- un fichier par échec, nommé `<instant UTC compact>-<référence>.txt` ;
- 200 fichiers au plus : les plus anciens sont supprimés au-delà ; 60 entrées par minute au plus, la rafale au-delà
  est abandonnée et signalée une fois (`diagnostic journal is rate limited`) ;
- permissions réservées à l'utilisateur courant (ACL sous Windows, mode `0600`/`0700` sous Linux) ;
- une première ligne `<instant> surface=<mcp|cli|api|wsl-worker|explorer> operation=<nom> ref=<référence>`, puis la
  chaîne de causes (au plus 8, 64 trames chacune, l'omis est annoncé) avec classe, méthode, fichier source et ligne ;
- un message d'exception n'y figure que s'il ne ressemble ni à un chemin, ni à un mot de passe, ni à une chaîne de
  connexion ; sinon il est remplacé par un texte fixe. Le journal n'écrit lui-même aucune valeur d'argument, mais un
  message non sensible peut en reprendre une (un genre inconnu, un motif de recherche) : traitez une entrée comme une
  donnée de projet.

Le journal ne s'écrit que sur échec interne : une consultation réussie, un refus attendu (projet inconnu, registre
illisible, erreur d'usage) ne laissent rien. Pour effacer le journal, supprimez le dossier `logs/internal-failures/`.

### Limites

- **Docker** : le répertoire de données du plan de requête est monté en lecture seule ; le journal privé n'y est pas
  supporté. La ligne de stderr enrichie reste disponible par `docker compose logs`.
- **Journal indisponible** (répertoire en lecture seule, dossier non privé) : la réponse de la surface est celle d'un
  processus sans journal ; l'indisponibilité est dite une fois sur stderr (`diagnostic journal unavailable (<classe>)`)
  puis plus rien n'est tenté.
- **CLI** : seuls le cadre commun des commandes et le lanceur sont couverts ; les commandes qui rendent elles-mêmes le
  code d'erreur d'exécution (Docker, worker WSL, équipe, IDE, NEXUS, projection sémantique) ne le sont pas.
- **Distribution WSL** : aucun journal n'y est écrit. Le worker transmet une ligne de diagnostic structurée (références,
  classes, origine ; aucun message, aucun chemin) que l'installation Windows valide avant de la journaliser ; toute ligne
  non conforme est ignorée.
- **Explorateur** : sa ligne `request failed (route=…, …, ref=…)` et la référence de sa réponse `500` suivent la même
  forme ; voir [`semantic-explorer.md`](semantic-explorer.md). Le journal privé n'y existe pas en Docker.

## Retrouver la trace d'une réponse

1. Relever la référence dans la réponse ou sur stderr.
2. Chercher la ligne de stderr portant `ref=<référence>` (classes et origine).
3. Journal activé : ouvrir `MINOS_HOME/logs/internal-failures/*-<référence>.txt` pour la pile.
4. Ne joignez à un rapport que la référence, la ligne de stderr et la pile, jamais un secret ; une vulnérabilité
   s'annonce selon [`SECURITY.md`](../../SECURITY.md).
