# Tâche 6.7 — parcours des états de projection dans un navigateur (relevé à remplir)

Partie **manuelle** de la tâche 6.7. Le propriétaire en a confié l'exécution à l'agent le 2026-11-14 (relevé
ci-dessous, Windows et Chrome). Les états sont ceux de `docs/user/semantic-explorer.md`, « Carte 2D ».

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

Plateforme : ☑ Windows ☐ Linux · Navigateur et version : Chrome 157 · Date : 2026-11-14 · Commit : `5534da4f` (aucun fichier de code ne diffère entre ce commit et `e5ad8d72`, fusionné : `git diff --stat 5534da4f e5ad8d72` ne liste que des documents)

Parcours fait par l'agent **à la demande du propriétaire**, dans son Chrome piloté par l'extension Claude in
Chrome (clics, molette, glisser, clavier et saisie réels), sur un `MINOS_HOME` jetable `target/projection-walk/home`
(fixture `typescript-modules`, 42 documents `local-hash` à 384 dimensions). L'état de la page est relevé par
lecture de son DOM après chaque geste ; les commandes sont lancées dans un terminal. Linux et les autres
navigateurs ne sont pas parcourus.

| # | État | Constaté | OK |
|---|---|---|---|
| 1 | Projection absente | état `ABSENTE` ; message « Aucune projection n'est préparée pour cet index. Lancez « minos semantic projection prepare <projet> »… » ; canevas masqué ; table de 42 documents utilisable | ☑ |
| 2 | Statut en ligne de commande | `state: ABSENTE`, `prepareCommand: minos semantic projection prepare <id>`, code 0 | ☑ |
| 3 | Préparation | passes `select`, `center`, `fit`, `project` sur la sortie d'erreur (25 passes), « published in 331 ms », rapport `state: PRETE`, 42 points, code 0 ; aucun chemin absolu | ☑ |
| 4 | Projection prête | « 42 points affichés sur 42 documents — natures projetées : CHUNK, FILE, SYMBOL. » ; « Méthode : PCA (version 1) · graine 42 · variance expliquée : axe 1 36,3 %, axe 2 14,2 %. » ; légende CHUNK 19, FILE 4, SYMBOL 19 ; la table reste visible sous la carte | ☑ |
| 5 | Zoom et déplacement | molette : échelle 1 → 1,2 ; glisser : décalage (100, 36) ; touche `−` : échelle 1 ; flèche gauche : décalage ; double-clic : échelle 1, décalage (0, 0). Trois crans de molette n'ont produit qu'un pas de zoom | ☑ |
| 6 | Survol et sélection | survol : « CHUNK · chunk:minos:structural:… » sous la carte et en infobulle ; clic : détail du document ouvert (`packages/api/src/greeting-contract.ts`, lignes 2–2), ligne de la table sélectionnée | ☑ |
| 7 | Filtre de nature | nature `CHUNK` : « 19 points affichés sur 42 documents (filtré) », légende réduite à CHUNK, les 42 points restent chargés (aucune nouvelle lecture) | ☑ |
| 8 | Résultats de recherche | recherche « greeting service » : 19 résultats (filtre `CHUNK` actif), colonne « Carte » : 19 « sur la carte », 19 points mis en évidence | ☑ |
| 9 | Échantillonnage | « 10 points affichés sur 42 documents — échantillon de 10 sur 42 documents de nature CHUNK, FILE, SYMBOL (graine 7). » ; recherche sans filtre : 10 « sur la carte », 32 « hors carte » | ☑ |
| 10 | Reproductibilité | SHA-256 du fichier identique avant et après une seconde préparation (`dbd306e4…a4e548`) | ☑ |
| 11 | Projection périmée | après `import-scip … --snapshot demo-2` et `ide semantic-index-sync` : message « La projection est périmée… », canevas masqué, bouton « Afficher la carte périmée » ; après clic : carte affichée, compteur terminé par « — CARTE PÉRIMÉE. » ; `status` : `PERIMEE` | ☑ |
| 12 | Projection illisible | fichier réduit à 10 octets : message « La projection enregistrée est illisible… », canevas masqué ; `status` : `ECHOUEE`, `PROJECTION_UNREADABLE`, code 0 ; un nouveau `prepare` rétablit `PRETE` (42 points), dans la page aussi | ☑ |
| 13 | Aucune préparation par HTTP | depuis la page, avec le jeton : `POST`, `PUT`, `DELETE` sur `/projection` et `POST` sur `/projection/points` → `405`, `Allow: GET` ; fichier de projection inchangé (10 octets) | ☑ |
| 14 | Index non prêt | sans `MINOS_SEMANTIC_PROVIDER` : « …the semantic index of the project is DISABLED, not READY; run minos ide semantic-index-sync … », code 1 | ☑ |
| 15 | Premier rendu et fluidité | relevés sur 100 000 points dans `mesures-6.8.md` : première image en 961 ms, redessin médian 29 ms pendant un zoom | ☑ |

Aucune erreur dans la console du navigateur à la fin du parcours. Écarts et limites relevés :

- la carte ne se rafraîchit pas d'elle-même après une préparation : il faut refermer puis rouvrir « Carte 2D » ;
- trois crans de molette envoyés par l'outil n'ont produit qu'un pas de zoom (un seul événement reçu) : la
  sensibilité de la molette à la main n'est pas jugée ;
- le déplacement est essayé par un seul glisser ; aucun essai tactile ;
- non parcourus : Linux, Firefox, Safari, changement de projet dans la page, un index de plusieurs milliers de
  documents (seulement mesuré, ligne 15).

Après le parcours : `Ctrl+C` dans le terminal de `explorer serve`, puis supprimer `target/projection-demo/`.
