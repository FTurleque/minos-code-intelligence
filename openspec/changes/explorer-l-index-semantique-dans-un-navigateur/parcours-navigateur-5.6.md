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
minos ide semantic-index-sync <projet>
minos explorer serve
```

Linux (bash) :

```bash
export MINOS_SEMANTIC_PROVIDER=local-hash
minos project add <dossier-du-projet>
minos index <projet>
minos ide semantic-index-sync <projet>
minos explorer serve
```

`minos index` exige les outils de provider installés dans ce `MINOS_HOME`. Sans eux, un index SCIP déjà
produit s'importe à la place : `minos import-scip <projet> --file <index.scip> --provider <id>` (le dépôt en
versionne pour ses fixtures TypeScript, sous `fixtures/typescript/*/.minos-m0/scip-typescript/`).

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
| 3 | Recherche sémantique | saisir une question, mode « Sémantique », « Chercher » | rangs et « similarité cosinus (−1 à 1) », limitations listées sous la barre ; mêmes rangs et scores que `minos ide semantic-search <projet> "<question>"` | | ☐ |
| 4 | Recherche hybride | même question, mode « Hybride » | « score hybride (0 à 1) » et ses signaux | | ☐ |
| 5 | Filtre sur une recherche | recherche, puis nature `FILE`, « Appliquer » | « n résultats affichés sur m classés » ; un résultat gardé conserve son rang d'origine | | ☐ |
| 6 | Détail, source identique | cliquer un document `CHUNK` | contenu indexé et source actuelle dans deux blocs, « identique » | | ☐ |
| 7 | Détail, source modifiée | modifier le fichier du document sur le disque, recliquer le document | avertissement « La source a changé depuis l'indexation », les deux contenus affichés | | ☐ |
| 8 | Source introuvable | renommer ou supprimer ce fichier, recliquer | « Source introuvable », contenu indexé conservé, aucun chemin absolu | | ☐ |
| 9 | Documents similaires | « Trouver les documents similaires » | liste de voisins avec similarité, le document lui-même absent | | ☐ |
| 10 | Provider indisponible | avec un provider `ollama` : arrêter Ollama, puis chercher ; puis « Trouver les documents similaires » | la recherche échoue avec un message ; liste, détail et voisins restent disponibles | | ☐ |
| 11 | Aucun résultat | recherche hybride sur une chaîne absente du projet, ou filtre qui ne retient rien | message « Aucun résultat », filtres rappelés | | ☐ |
| 12 | Index obsolète | réindexer le projet (`minos index <projet>`) **sans** `minos ide semantic-index-sync`, recharger la page | bandeau `STALE` nommant la cause ; liste, détail et voisins consultables ; recherche refusée ; module et langage « non résolu » | | ☐ |
| 13 | Snapshot changé pendant l'opération | page ouverte sur la liste, lancer `minos ide semantic-index-sync <projet>` après une réindexation, puis « Page suivante » | bandeau « L'index a changé pendant la consultation », la liste reprend à la première page | | ☐ |
| 14 | Index absent | projet inscrit et indexé, jamais synchronisé (`minos ide semantic-index-sync` non lancé) | bandeau nommant `minos ide semantic-index-sync`, table vide | | ☐ |
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

## Relevé du 2026-11-14 — Windows, partiel

Fait par la session de pilotage à la demande du propriétaire. Windows 10, navigateur intégré de l'application
Claude (Chromium), JAR du commit `d25f779b`, copie d'un `MINOS_HOME` de démonstration (projets `demo-explorer`,
`READY`, 390 documents, et `demo-sans-index`), provider `local-hash`. La tâche 5.6 reste ouverte : Linux, Chrome
et six lignes manquent.

| # | Résultat | Constaté |
|---|---|---|
| 1 | conforme | 50 lignes sur 390, `READY`, provider, modèle, 384 dimensions, snapshots, « Page suivante » |
| 2 | conforme | `CHUNK` : 50 « (filtrés) » ; avec le préfixe `src/Customer` : 16 ; réinitialisation : 50, ordre inchangé |
| 3 | conforme | « Similarité cosinus (−1 à 1) », 4 limitations ; rang 1 `src/CustomerService26.java` à 0.3604, premier résultat et score de `minos ide semantic-search demo-explorer "customer service order"` |
| 4 | conforme | « Score hybride (0 à 1) », rang 1 à 0.1802, signal sémantique 0.3604 |
| 5 | conforme | « 22 résultats affichés sur 50 classés », rangs 46 et 48 conservés |
| 6 | conforme | deux blocs, « identique » |
| 7 | conforme | « La source a changé depuis l'indexation. », les deux contenus |
| 8 | conforme | « Source introuvable », contenu indexé conservé, aucun chemin absolu dans la page |
| 9 | conforme | 20 voisins, 0.8853 en tête, le document lui-même absent |
| 10 | non fait | pas de provider `ollama` |
| 11 | conforme (par filtre) | « Aucun document ne correspond aux filtres. Élargissez-les. » |
| 12, 13, 14, 18 | non faits | exigent une indexation ou une synchronisation ; aucun outil de provider dans ce `MINOS_HOME` |
| 15 | conforme | `NO_ACTIVE_SNAPSHOT`, bandeau `minos index <projet>` |
| 16 | conforme | `DISABLED`, liste consultable, recherche refusée : « La recherche exige un index prêt (READY). La liste reste consultable. » |
| 17 | conforme | « Aucun projet n'est inscrit… » |
| 19 | conforme | « Accès refusé » ; seules `/`, `app.css`, `app.js` demandées ; `GET /api/v1/projects` sans jeton : `401` |
| 20 | conforme | badge `local-hash` |
| 21 | conforme | `<script>alert(1)</script><img src=x onerror=alert(2)>` rendu en texte, aucune alerte |
| 22 | conforme | aucune erreur de console ; 24 requêtes, toutes vers `127.0.0.1:<port>` |
| 23 | conforme | écoute sur `127.0.0.1` et `[::1]` seulement (`netstat`) |
| 24 | partiel | processus arrêté par `taskkill` : la page ne répond plus ; « MINOS explorer stopped. » non observé |

## Écarts constatés pendant le parcours

| # | Ligne du relevé | Ce qui diffère de l'attendu | Capture ou message |
|---|---|---|---|
| E1 | 1 | Avec le JAR `d25f779b`, dans Chrome, l'adresse ouverte depuis une autre page ou par une extension recevait `403` avec un corps vide : le contrôle `Sec-Fetch-Site` s'appliquait à la page d'accueil. Décision du propriétaire du 2026-11-14 : le `GET` de `/`, `/app.css` et `/app.js` est servi à une navigation venue d'un autre site ; l'API garde son refus (design, « Questions ouvertes », point 3 ; ARCH-0082, « Écarts constatés »). Rejoué dans Chrome le 2026-11-14 avec le JAR du commit `b110d061` : un lien posé dans `https://example.com` et cliqué ouvre la surface (projet `demo-explorer`, `READY`, 390 documents, 50 lignes), aucun refus au journal. Non rejoué : ouverture par une extension, Firefox, Safari | journal du JAR `d25f779b` : `explorer: refused request: origin` ; `curl` avec `Sec-Fetch-Site: cross-site` ou `same-site` : `403`, 0 octet ; `none`, `same-origin` ou absent : `200` |
| E2 | 12, 13, 14, 16 | Les bandeaux `DISABLED`, `MISSING` et `STALE` du JAR `d25f779b` nomment `minos semantic-index-sync <projet>`, que la CLI refuse. Corrigé : `minos ide semantic-index-sync <projet>`, gardé par `ExplorerCitedCommandsTest` | `error: unknown command: semantic-index-sync` |
| E3 | 6 | Observation, pas un défaut : un document `CHUNK` annonce les lignes de son symbole (`3–5`) et son contenu indexé commence deux lignes plus haut (`package demo;`), la source actuelle étant affichée `1–5`. Un chunk est l'extrait du symbole avec deux lignes de contexte de part et d'autre (`SemanticDocumentFactory.java:128`) ; la source actuelle est relue avec le même contexte et affiche les lignes réellement lues (`SemanticCorpusQueryService.java:344-346`). Le guide utilisateur le dit | document `chunk:demo:CatalogService100:3:5` |
