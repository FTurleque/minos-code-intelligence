# Tâche 5.6 — parcours dans un navigateur (relevé à remplir)

Tâche **manuelle**, à faire par le propriétaire sous Windows **et** sous Linux. Elle couvre chaque état du
tableau de `docs/project/research/explorateur-vectoriel-2026-11/CONCEPTION-FONCTIONNELLE.md` § 7, hors projection.
La tâche 5.6 ne se coche qu'une fois ce relevé rempli sur les deux plateformes.

Un essai partiel a été fait par l'agent le 2026-11-13 dans le navigateur intégré de son poste (Windows, index
`local-hash` de 390 documents) : liste, recherche, détail, voisins, projet sans snapshot, `401` sans jeton. Il ne
remplace pas ce parcours.

## Préparer

Il faut un `MINOS_HOME` avec au moins un projet indexé et un provider d'embeddings configuré. `local-hash` suffit
pour le parcours (et il est nécessaire pour l'état « Mode local-hash »).

Windows (PowerShell) :

```powershell
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
minos project add <dossier-du-projet>
minos index <projet>
minos semantic-index-sync <projet>
minos explorer serve
```

Linux (bash) :

```bash
export MINOS_SEMANTIC_PROVIDER=local-hash
minos project add <dossier-du-projet>
minos index <projet>
minos semantic-index-sync <projet>
minos explorer serve
```

Depuis les sources, sans installation, remplacer `minos` par
`java -jar target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar` après `./mvnw -B -ntp -DskipTests package`.

La commande écrit sur la sortie d'erreur une adresse `http://127.0.0.1:<port>/#token=…` : l'ouvrir dans le
navigateur de la même machine. `Ctrl+C` arrête la surface.

## Relevé

Plateforme : ☐ Windows ☐ Linux · Navigateur et version : ………… · Date : ………… · Commit : …………

| # | État (§ 7) | Comment l'obtenir | Attendu | Constaté | OK |
|---|---|---|---|---|---|
| 1 | Nominal : liste | ouvrir l'adresse | table des documents par clé stable, 50 lignes, « Page suivante » si l'index en a plus ; en-tête : état `READY`, provider, modèle, dimensions, snapshots | | ☐ |
| 2 | Filtres | nature `CHUNK`, puis un préfixe de fichier, puis « Réinitialiser » | des lignes sont retirées, l'ordre ne change pas, le compteur dit « (filtrés) » | | ☐ |
| 3 | Recherche sémantique | saisir une question, mode « Sémantique », « Chercher » | rangs et « similarité cosinus (−1 à 1) », limitations listées sous la barre ; mêmes rangs et scores que `minos semantic-search <projet> "<question>"` | | ☐ |
| 4 | Recherche hybride | même question, mode « Hybride » | « score hybride (0 à 1) » et ses signaux | | ☐ |
| 5 | Filtre sur une recherche | recherche, puis nature `FILE`, « Appliquer » | « n résultats affichés sur m classés » ; un résultat gardé conserve son rang d'origine | | ☐ |
| 6 | Détail, source identique | cliquer un document `CHUNK` | contenu indexé et source actuelle dans deux blocs, « identique » | | ☐ |
| 7 | Détail, source modifiée | modifier le fichier du document sur le disque, recliquer le document | avertissement « La source a changé depuis l'indexation », les deux contenus affichés | | ☐ |
| 8 | Source introuvable | renommer ou supprimer ce fichier, recliquer | « Source introuvable », contenu indexé conservé, aucun chemin absolu | | ☐ |
| 9 | Documents similaires | « Trouver les documents similaires » | liste de voisins avec similarité, le document lui-même absent | | ☐ |
| 10 | Provider indisponible | avec un provider `ollama` : arrêter Ollama, puis chercher ; puis « Trouver les documents similaires » | la recherche échoue avec un message ; liste, détail et voisins restent disponibles | | ☐ |
| 11 | Aucun résultat | recherche hybride sur une chaîne absente du projet, ou filtre qui ne retient rien | message « Aucun résultat », filtres rappelés | | ☐ |
| 12 | Index obsolète | réindexer le projet (`minos index <projet>`) **sans** `semantic-index-sync`, recharger la page | bandeau `STALE` nommant la cause ; liste, détail et voisins consultables ; recherche refusée ; module et langage « non résolu » | | ☐ |
| 13 | Snapshot changé pendant l'opération | page ouverte sur la liste, lancer `minos semantic-index-sync <projet>` après une réindexation, puis « Page suivante » | bandeau « L'index a changé pendant la consultation », la liste reprend à la première page | | ☐ |
| 14 | Index absent | projet inscrit et indexé, jamais synchronisé (`semantic-index-sync` non lancé) | bandeau nommant `minos semantic-index-sync`, table vide | | ☐ |
| 15 | Aucun snapshot actif | projet inscrit, jamais indexé | bandeau « pas de snapshot actif », commande `minos index` | | ☐ |
| 16 | Sémantique désactivée | relancer `minos explorer serve` sans `MINOS_SEMANTIC_PROVIDER` | état `DISABLED`, bandeau nommant les clés `minos.semantic.*` | | ☐ |
| 17 | Aucun projet | `MINOS_HOME` neuf (variable `MINOS_HOME` vers un dossier vide), `minos explorer serve` | page vide avec la commande d'inscription d'un projet | | ☐ |
| 18 | Aucun document | index présent sans document (projet sans symbole indexable) | table vide, message invitant à vérifier l'indexation | | ☐ |
| 19 | Accès refusé | ouvrir `http://127.0.0.1:<port>/` sans le fragment `#token=…` dans une nouvelle fenêtre privée | page « Accès refusé », aucune donnée | | ☐ |
| 20 | Mode `local-hash` | provider `local-hash` | badge permanent « Référence déterministe (hachage de tokens) — pas un modèle de langage » | | ☐ |
| 21 | Contenu hostile | un fichier du projet contenant `<script>alert(1)</script>`, réindexé et synchronisé, puis son détail | le texte s'affiche tel quel, aucune boîte d'alerte | | ☐ |
| 22 | Console du navigateur | outils de développement ouverts pendant tout le parcours | aucune erreur, aucune requête vers un autre hôte que `127.0.0.1:<port>` | | ☐ |
| 23 | Écoute | Windows : `netstat -ano \| findstr :<port>` · Linux : `ss -ltn \| grep :<port>` | écoute sur `127.0.0.1` (et `[::1]`) seulement | | ☐ |
| 24 | Arrêt | `Ctrl+C` dans le terminal | « MINOS explorer stopped. », la page ne répond plus | | ☐ |

Les états de projection du § 7 (absente, en préparation, échouée, périmée, corpus trop volumineux) sont hors de ce
changement : ils relèvent de `projeter-l-index-semantique-en-2d`.

## Écarts constatés pendant le parcours

| # | Ligne du relevé | Ce qui diffère de l'attendu | Capture ou message |
|---|---|---|---|
| | | | |
