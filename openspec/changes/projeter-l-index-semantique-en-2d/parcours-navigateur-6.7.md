# Tâche 6.7 — parcours des états de projection dans un navigateur (relevé à remplir)

Partie **manuelle** de la tâche 6.7, à faire par le propriétaire. La tâche ne se coche qu'une fois ce relevé
rempli. Les états sont ceux de `docs/user/semantic-explorer.md`, « Carte 2D ».

Un essai partiel a été fait par l'agent le 2026-11-14 dans le navigateur intégré de son poste (Windows, index
`local-hash` de 42 documents de la fixture `fixtures/typescript/typescript-modules`) : ouverture de la carte en
état `PRETE`, compteur, méthode, légende, recherche (résultats mis en évidence, colonne « Carte »), clic sur un
point (ouverture du détail). Non essayés : zoom, déplacement, survol, états `ABSENTE`, `PERIMEE` et `ECHOUEE`
dans la page, filtre de nature, changement de projet. Les manipulations des lignes 9 à 12 et 14 ont été rejouées
par les commandes seules (échantillon de 10 sur 42, fichier identique après deux préparations, `PERIMEE` après
`--snapshot demo-2`, `ECHOUEE` après troncature, refus sur index `DISABLED`). Cet essai ne remplace pas le
parcours.

## Préparer un `MINOS_HOME` jetable

Depuis la racine du dépôt, sans installation. Le `MINOS_HOME` est créé sous `target/`, ignoré par Git ; l'index
est produit par le provider `local-hash` à partir d'un index SCIP versionné (aucun outil de provider n'est
nécessaire).

```powershell
.\mvnw.cmd -B -ntp -DskipTests "-Djacoco.skip=true" package
$env:MINOS_HOME = "$PWD\target\projection-demo\home"
$env:MINOS_SEMANTIC_PROVIDER = 'local-hash'
$minos = 'java', '-jar', 'target\minos-code-intelligence-1.3.0-SNAPSHOT-all.jar'
& $minos[0] $minos[1..2] project add fixtures\typescript\typescript-modules
& $minos[0] $minos[1..2] import-scip typescript-modules --file fixtures\typescript\typescript-modules\.minos-m0\scip-typescript\index.scip --provider scip-typescript
& $minos[0] $minos[1..2] ide semantic-index-sync typescript-modules
& $minos[0] $minos[1..2] explorer serve
```

Linux (bash) : mêmes commandes avec `./mvnw`, `export MINOS_HOME="$PWD/target/projection-demo/home"`,
`export MINOS_SEMANTIC_PROVIDER=local-hash` et `java -jar target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar`.

`explorer serve` écrit sur la sortie d'erreur une adresse `http://127.0.0.1:<port>/#token=…` : l'ouvrir dans un
navigateur de la même machine. Les commandes de projection se lancent dans un **second** terminal, avec les deux
mêmes variables d'environnement :

```bash
minos semantic projection status typescript-modules
minos semantic projection prepare typescript-modules --kinds CHUNK,SYMBOL,FILE
```

(`minos` = `java -jar target/minos-code-intelligence-1.3.0-SNAPSHOT-all.jar` depuis les sources.)

## Relevé

Plateforme : ☐ Windows ☐ Linux · Navigateur et version : ………… · Date : ………… · Commit : …………

| # | État | Comment l'obtenir | Attendu | Constaté | OK |
|---|---|---|---|---|---|
| 1 | Projection absente | avant tout `prepare`, cliquer « Carte 2D » | message « Aucune projection n'est préparée… » qui nomme `minos semantic projection prepare <projet>` ; aucune carte ; liste, recherche et détail utilisables | | ☐ |
| 2 | Statut en ligne de commande | `minos semantic projection status typescript-modules` | `state: ABSENTE`, la commande de préparation, code de sortie 0 | | ☐ |
| 3 | Préparation | `minos semantic projection prepare typescript-modules --kinds CHUNK,SYMBOL,FILE` | progression par passes sur la sortie d'erreur, rapport `state: PRETE`, 42 points, code 0 ; aucun chemin absolu | | ☐ |
| 4 | Projection prête | refermer puis rouvrir « Carte 2D » | points colorés par nature, légende avec effectifs, compteur « 42 points affichés sur 42 documents », méthode PCA, graine 42, variance expliquée | | ☐ |
| 5 | Zoom et déplacement | molette, glisser, `+`, `−`, flèches, double-clic | la carte suit ; double-clic ou `0` recentre | | ☐ |
| 6 | Survol et sélection | survoler un point, puis le cliquer | nature et clé stable sous la carte ; le clic ouvre le détail du document et l'encadre sur la carte | | ☐ |
| 7 | Filtre de nature | filtre « Nature » = `CHUNK` | seuls les points `CHUNK` restent, compteur « (filtré) », aucune requête de préparation | | ☐ |
| 8 | Résultats de recherche | saisir une question, « Chercher » | les documents classés sont mis en évidence, les autres atténués ; colonne « Carte » : « sur la carte » ou « hors carte » | | ☐ |
| 9 | Échantillonnage | `minos semantic projection prepare typescript-modules --kinds CHUNK,SYMBOL,FILE --max-points 10 --seed 7`, rouvrir la carte | « échantillon de 10 sur 42 … (graine 7) » ; une recherche montre des résultats « hors carte » | | ☐ |
| 10 | Reproductibilité | relancer la même commande, comparer l'empreinte du fichier `target/projection-demo/home/semantic-projections/<id>/projection-v1.bin` avant et après | fichier identique octet pour octet | | ☐ |
| 11 | Projection périmée | réimporter l'index sous un autre identifiant de snapshot (la commande `import-scip` ci-dessus avec `--snapshot demo-2`), puis `minos ide semantic-index-sync typescript-modules`, rouvrir la carte | message « La projection est périmée… », carte non affichée, bouton « Afficher la carte périmée » ; après clic, carte marquée « CARTE PÉRIMÉE » ; `status` rend `PERIMEE` | | ☐ |
| 12 | Projection illisible | tronquer `projection-v1.bin` (par exemple n'en garder que les 10 premiers octets), rouvrir la carte | message « La projection enregistrée est illisible… » ; `status` rend `ECHOUEE`, cause `PROJECTION_UNREADABLE` ; un nouveau `prepare` rétablit `PRETE` | | ☐ |
| 13 | Aucune préparation par HTTP | dans la console du navigateur ou par `curl -X POST` avec le jeton sur `/api/v1/projects/<id>/projection` | `405`, en-tête `Allow: GET`, fichier de projection inchangé | | ☐ |
| 14 | Index non prêt | désactiver le provider (`MINOS_SEMANTIC_PROVIDER` non défini) et relancer `prepare` | échec, code 1, message qui nomme `minos ide semantic-index-sync` | | ☐ |
| 15 | Premier rendu et fluidité (mesure de la tâche 6.8) | sur le plus grand index disponible | délai entre le clic sur « Carte 2D » et l'affichage : ………… ms ; zoom et déplacement fluides : ☐ oui ☐ non | | ☐ |

Après le parcours : `Ctrl+C` dans le terminal de `explorer serve`, puis supprimer `target/projection-demo/`.
