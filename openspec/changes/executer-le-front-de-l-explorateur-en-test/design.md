# Design

Changement d'outillage de test, avec une retouche de deux lignes d'une ressource : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies ; s'y ajoute « Ce que les sprints 4 et 8 pourront mesurer », demandée par le rôle de prérequis du constat. Les autres sections du modèle sont non applicables : aucun module, contrat, format persisté ni traitement serveur ne change.

## Contexte

Motivation : `proposal.md`. La page de l'explorateur est servie par `minos-explorer` depuis trois ressources (`index.html`, `app.css`, `app.js`). `app.js` est un script classique en mode strict : pas de module, pas d'export, un état global (`token`, `projects`, `project`, `view`, `nextCursor`, `selectedKey`, `requestNumber`, `tabStorageRefused`, objet `map`), 45 déclarations `function` de premier niveau et un appel `start()` en dernière ligne. Le dépôt n'a aucun `package.json` hors `fixtures/` ; le job `invariants` exécute des scripts Python avec le Python du runner.

## État vérifié au HEAD

HEAD `89be3fb4`.

| Fait | Preuve |
|---|---|
| 784 lignes, 45 déclarations `function` / `async function` de premier niveau | `minos-explorer/src/main/resources/com/minos/explorer/web/app.js` (décompte) |
| Script classique : `"use strict"` ligne 1, aucun `export`, `start()` appelé au chargement | `app.js:1`, `:758`, `:784` |
| État global mutable : huit `let` et l'objet `map` | `app.js:38-45`, `:469-472` |
| Fabrique unique d'élément qui écrit du texte | `app.js:6-11` |
| Sélection du point : parcours linéaire de `visiblePoints()`, rayon de capture 8 px (`bestDistance = 64`) | `app.js:638-652` |
| Filtre de nature : `map.points.filter(...)` réalloué à chaque appel quand un filtre est actif | `app.js:483-486` |
| Zoom borné à [0,5 ; 200], point sous le curseur conservé | `app.js:654-662` |
| Lecture des points : boucle séquentielle de pages, arrêt sur changement d'état ou page vide | `app.js:553-564` |
| Survol : `pointAt` à chaque événement, sans limitation ; `drawMap` demandé quand le point change, déjà coalescé à un tracé par image (`map.frame`) | `app.js:690-698` ; `app.js:581-584` |
| Erreur d'API : le bandeau affiche `error.message` | `app.js:117-125` |
| Presse-papiers : `navigator.clipboard.writeText(...)` sans traitement du rejet | `app.js:411-413` |
| Le test Java cherche douze motifs de puits par `contains` | `minos-explorer/src/test/java/com/minos/explorer/WebResourcesContentTest.java:25-27`, `:40-42` |
| Autres contrôles du front : sous-chaînes et comptages | `WebResourcesContentTest.java:44-46`, `:77-110` |
| Aucun appel de Node dans les workflows ; `invariants` tourne sur `ubuntu-24.04` sans `setup-python` | `.github/workflows/pr-ci.yml:49-52` ; recherche `node`, `setup-node` : 0 ligne |
| `run_gates.py` ne rejoue que les lignes `python scripts/…` ou `./scripts/…` du job | `.claude/scripts/run_gates.py:28` |
| Tout script dont la source contient `add_argument("--self-test"` est découvert et doit être lancé avec cette option par `invariants` ; il n'y a pas de liste d'attendus à tenir | `scripts/quality/check-ci-wiring.py:55` (expression), `:268-283` (découverte), `:285-298` (contrôle) |
| Node 24.18.0 est installé sur le poste | `node --version` |

Non vérifié : la version de Node de l'image `ubuntu-24.04` des runners.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — « test de non-régression des puits DOM par analyse syntaxique » : Node n'expose aucun analyseur JavaScript public ; un arbre syntaxique exige une dépendance npm (acorn, espree) et son verrou | action de AUD-TST-02 | règle « pas de nouvelle dépendance sans raison écrite » (`.claude/rules/securite.md`, point 10) | prouver par **exécution** : faux DOM qui refuse l'écriture de balisage, contexte sans génération de code (D3). Plus fort qu'une recherche de nom, sans dépendance |
| E2 — « `node --check` en CI » lancé directement par une étape ne serait pas rejoué par `run_gates.py` | action de AUD-TST-02 | `.claude/scripts/run_gates.py:28` | l'envelopper dans un gate Python (D1) |
| E3 — « tests unitaires des fonctions pures » : `app.js` n'exporte rien et démarre au chargement | action de AUD-TST-02 | `app.js:784` | charger le fichier réel dans `node:vm` avec un faux DOM ; aucune modification de structure de `app.js` (D2) |
| E4 — « Node est déjà présent sur ubuntu-24.04 » : version non épinglée, alors que le comportement de `node:test` varie entre majeures. Aucune exigence n'épingle l'outillage d'un job (`controles-de-fusion › Le runner et l'outil de build d'un workflow sont épinglés` ne vise que les images `*-latest` et Gradle) ; le Python du même job ne l'est pas non plus | action de AUD-TST-02 | `openspec/specs/controles-de-fusion/spec.md:288-289` ; `.github/workflows/pr-ci.yml:49-52` | version minimale contrôlée par le gate, ou `actions/setup-node` épinglée (D4) |
| E5 — Le cadrage du sprint dit « ne touche pas au code applicatif » ; l'action du constat inclut la gestion du rejet du presse-papiers | `sprints.md`, sprint 1 | action de AUD-TST-02 | l'inclure, isolée dans un groupe de tâches et précédée de son test rouge (D6) |
| E6 — Le badge d'en-tête affiche l'état d'index brut (`index : READY`), et un état de projection inconnu de la page est affiché brut ; seuls le bandeau d'état, le message de la carte et le panneau de détail traduisent les constantes du contrat | `app.js:165`, `app.js:534` | action de AUD-TST-02 (« aucun affichage de constante brute ») | l'exigence « La carte et la traduction des états ont un comportement caractérisé par des tests » ne vise que le bandeau, le message de la carte et le détail ; le badge est caractérisé tel quel (scénario « Badge d'en-tête caractérisé », `labels.test.mjs`, « the header badge shows the raw index state… ») ; sa traduction relève d'AUD-QUA-03 (sprint 8, changement `trancher-la-langue-du-contrat-de-l-explorateur`) |

## Décisions

### D1 — Un gate Python porte le contrôle
[Décision] `scripts/quality/check-explorer-front.py` (bibliothèque standard seule) : résout `node` dans le `PATH`, contrôle sa version majeure minimale, lance `node --check` sur `app.js`, puis `node --test` sur le dossier des tests ; code 1 si Node manque, si la syntaxe est refusée, si un test échoue ou si zéro test a tourné. Il ne passe à Node qu'une liste d'arguments (aucun shell) et un environnement réduit. `--self-test` rejoue le gate sur des copies temporaires mutées (syntaxe cassée, puits introduit, dossier de tests vide, `PATH` sans Node). Le gate garde son propre `main()` au lieu de `gate_cli.run` (règle `.claude/rules/gates-python.md`) : `gate_cli.parse_root` ne connaît que `--root`, et ce gate prend aussi `--self-test` ; il rapporte par `gate_cli.report_failures`, comme `check-bounded-helpers.py`. Écarté : une étape `run: node --test` nue (non rejouée localement, sans auto-test).

### D2 — Tests `node:test` qui chargent le vrai fichier dans `node:vm`
[Décision] Les tests vivent dans `minos-explorer/src/test/js/` (fichiers `*.test.mjs` et un module d'outillage `harness.mjs`), hors des ressources livrées. `harness.mjs` lit `app.js` depuis son chemin source, crée un contexte `vm` avec un faux `document`, `window`, `sessionStorage`, `navigator`, `fetch`, `requestAnimationFrame` et un faux canevas, puis exécute le script. Les déclarations `function` deviennent des propriétés du contexte ; l'état `let` / `const` se lit par une expression évaluée dans le même contexte. Aucun export n'est ajouté à `app.js`. Écarté : convertir `app.js` en module (change le chargement de la page et la politique de contenu vérifiée par `WebResourcesContentTest`).

### D3 — Les puits sont prouvés par exécution
[Décision] Le faux DOM lève une erreur sur toute affectation de `innerHTML` / `outerHTML` / `srcdoc`, sur `insertAdjacentHTML`, `document.write`, `createContextualFragment`, `DOMParser`, sur `setAttribute` d'un attribut `on*` (toute casse) ou `srcdoc`, et consigne chaque écriture de texte ou d'attribut ; le contexte est créé avec `codeGeneration: { strings: false }`, qui refuse `eval` et `new Function`. Le test parcourt tous les rendus avec des valeurs hostiles et vérifie que chaque valeur ressort comme texte. Un accès par nom calculé (`node["inner" + "HTML"]`) est intercepté de la même façon, ce qu'aucune recherche de sous-chaîne ne voit. `WebResourcesContentTest` est conservé : il couvre `index.html` et `app.css`.

### D4 — Node du runner, version minimale contrôlée
[Proposition, recommandée] Le job `invariants` utilise le Node de l'image, comme il utilise son Python ; le gate refuse une majeure inférieure à la minimale déclarée dans le script (celle où `node --test` et `node:vm` avec `codeGeneration` sont stables ; valeur fixée en tâche après lecture de la version du runner) et affiche la version utilisée. Écarté pour l'instant : `actions/setup-node` épinglée par SHA (une action de plus dans la chaîne d'approvisionnement) ; à retenir si la première exécution montre une image sans Node ou une dérive.

Mise en œuvre : `MINIMUM_NODE_MAJOR = 20` dans `scripts/quality/check-explorer-front.py` (majeure où `node:test`, le reporter TAP et `codeGeneration` de `node:vm` sont stables ; les tests n'utilisent rien de plus récent). Valeur provisoire tant que la version du runner n'est pas lue (tâche 2.2) ; tests exécutés localement sous Node 24.18.0 seulement. Selon la majeure, `node --test` sur une liste de fichiers place les tests au premier niveau du TAP ou sous un test par fichier : le gate lit les tests en échec à toute profondeur, et son auto-test le vérifie sur un échantillon de chaque forme ; le format réel de Node 20 et 22 n'est pas observé.

### D5 — Les compteurs sont des caractérisations, pas des seuils
[Décision] Les tests de rafale (nombre de recherches de point et de tracés pour K déplacements, suite des décalages de pages) affirment les valeurs **actuelles**, nommées dans un seul tableau de constantes du test. Le sprint 4 modifie ce tableau en même temps que le code et la différence est la mesure de son gain. Aucun seuil de performance n'est inventé ici.

### D6 — Corriger le rejet du presse-papiers dans ce changement
[Proposition, recommandée] Deux lignes de `app.js` (une modifiée, une ajoutée) : traiter le rejet et afficher un message par `banner`. Le test rouge existe d'abord (rejet non géré détecté par le lanceur de test). Écarté : le reporter au sprint 8, ce qui laisserait un test connu rouge ou absent.

## Ce que les sprints 4 et 8 pourront mesurer

**Sprint 4 — AUD-PERF-10** (carte 2D côté navigateur). À l'issue de ce changement, le sprint 4 dispose de :

- un **oracle d'équivalence** de `pointAt` : jeux de points à graine fixe (tailles petites et un jeu de 100 000 points), positions du pointeur tirées de la même graine, cas d'égalité de distance, point hors rayon, filtre de nature actif ; une grille spatiale doit rendre exactement les mêmes points ;
- un **oracle du filtre** : contenu et ordre de `visiblePoints()` avec et sans filtre, avant et après changement de filtre ; un cache du filtre doit s'invalider sur ces transitions ;
- des **compteurs de rafale** : pour K événements `pointermove` consécutifs sans image intermédiaire, nombre de recherches de point et de tracés (valeur actuelle : K recherches de point et un seul tracé par image, `drawMap` étant déjà coalescé) ; « une fois par image » se prouve par la baisse de ce compteur ;
- la **suite des requêtes** de `readMapPoints` (décalages, limite, arrêt sur changement d'état, abandon quand la requête est périmée) ; un format de points compact se prouve à suite de requêtes et à points rendus égaux ;
- les invariants du zoom (bornes, point fixe sous le curseur) ;
- l'**exécution de `drawMap` et de `pointAt` sous Node** : le faux canevas enregistre les appels de tracé (points dessinés, points marqués, effacement) pour un jeu et une vue donnés ; un tracé optimisé doit dessiner les mêmes points.

Tests réels (`minos-explorer/src/test/js/map.test.mjs`, valeurs actuelles dans la table `CURRENT`) : « the point under the pointer is the nearest one inside the capture radius, on seeded sets », « on a set of 100 000 points the selection matches the reference, also with a kind filter and a zoom », « a tie keeps the first point received and a point beyond the radius is not selected », « the kind filter keeps the points of that kind in reception order, and all of them without filter », « the zoom stays within its bounds and keeps the point under the cursor in place », « the points are read page after page until the total is reached », « the reading stops when the state changes or a page is empty, and is abandoned when another one started », « a drawing clears the canvas, draws each plain point once and frames the selected one », « a burst of pointer moves without a frame looks a point up per move and requests one drawing » (50 déplacements : 50 recherches de point, 1 tracé demandé). Le cas « après changement de filtre » n'est couvert que par retour à `""` ; aucune invalidation de cache n'existe à tester aujourd'hui.

Ce que ce changement ne donne pas : une durée dans un vrai navigateur. Les latences du serveur et la préparation de la projection sont mesurées par le changement `mesurer-la-projection-et-l-explorateur-au-banc` (AUD-PERF-13).

**Sprint 8 — AUD-QUA-03** (constantes et codes du contrat v1). Le sprint 8 dispose de :

- une **table unique des constantes** du contrat dans les tests (états de projection, états d'index, divergences, causes de péremption), confrontée aux tables de libellés de la page : renommer une constante sans son libellé fait échouer le test en nommant la constante ;
- la preuve qu'**aucune constante brute** n'est affichée par le bandeau d'état, le message de la carte et le détail : ces rendus sont rejoués pour chaque état et le texte produit ne contient pas la constante (le badge d'en-tête, brut, est hors de cette preuve, écart E6) ;
- la **caractérisation de l'affichage d'une erreur d'API** : aujourd'hui le bandeau reprend `message` ; le passage à un `code` traduit se prouve en changeant cette attente, avec un cas « code inconnu ».

Tests réels (`minos-explorer/src/test/js/labels.test.mjs`, table `CONTRACT`) : « every contract constant has a label in the page, and every label a contract constant », « the state banner shows the label of each index state and stale cause, never the raw constant », « the map message shows the label of each projection state, never the raw constant », « the detail shows the label of each divergence, never the raw constant », « an API error is shown by its message whatever its code (characterised), other failures by a fixed text ». Le badge d'en-tête, qui affiche l'état brut, est caractérisé à part (« the header badge shows the raw index state (characterised: the contract language is decided later) », écart E6) : c'est la première attente que le sprint 8 change s'il le traduit.

## Vérification

- Rouge : chaque test est d'abord constaté rouge sur une copie mutée par `--self-test` (syntaxe, puits, traduction manquante) ; le test du presse-papiers est rouge sur `app.js` du HEAD.
- `python scripts/quality/check-explorer-front.py` et `--self-test` sur le poste (Node 24) et dans `invariants`. Dès que le script existe avec `add_argument("--self-test"`, `check-ci-wiring.py` et `run_gates.py` sont rouges jusqu'au câblage : celui-ci suit immédiatement l'écriture du gate (tâches 1.1 à 1.3).
- `python scripts/quality/check-ci-wiring.py` (auto-test câblé), `python scripts/quality/test_check_ci_wiring.py`, `python .claude/scripts/run_gates.py`.
- `./mvnw -B -ntp -pl minos-explorer -am test "-Dtest=com.minos.explorer.**.*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` (tests du module seuls, modules amont compilés sans leurs tests) : `WebResourcesContentTest` reste vert après la retouche de `app.js` (il compte les occurrences de `fetch(` et de certaines chaînes).
- Scope JaCoCo : aucun (JavaScript non mesuré). Les fichiers `src/test/js/` ne sont pas des ressources : vérifier qu'ils sont absents de `minos-explorer/target/classes` après la phase `test` (qui ne produit pas de JAR).
- Non vérifiable localement : la présence et la version de Node sur le runner.

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| exploration-semantique › La page de l'explorateur est exécutée par des tests et contrôlée avant toute fusion | État du dépôt ; Erreur de syntaxe ; Interpréteur absent ; Aucun test trouvé | D1, D4 | 1.1, 1.2, 1.4 | `python scripts/quality/check-explorer-front.py` (code 0, nombre de tests affiché) ; `--self-test`, cas « repository state passes », « broken syntax names the file », « PATH without Node names Node and the minimum », « installed Node under the minimum », « empty test folder » ; Node du runner non lu (2.2) |
| idem | Gate non câblé | D1 | 1.1, 1.3 | `scripts/quality/check-ci-wiring.py` (règle existante, rouge constaté avant câblage) |
| exploration-semantique › Aucun contenu indexé n'atteint un puits de balisage, prouvé par exécution | les trois scénarios | D2, D3 | 1.5 | `sinks.test.mjs` (« list, header, detail and neighbours render hostile values as text », « search results, the map texts and the hover render hostile values as text », « an API error message is shown as text in the banner », « the test DOM refuses markup and the context refuses code generation ») ; `--self-test`, cas `insertAdjacentHTML`, nom calculé, attribut `onclick`, `eval` |
| exploration-semantique › La carte et la traduction des états ont un comportement caractérisé par des tests | Point désigné ; Filtre de nature ; Lecture paginée | D2, D5 | 1.6 | `map.test.mjs` (tests nommés plus haut) ; `--self-test`, cas « capture radius changed » |
| idem | État sans libellé | D5 | 1.7 | `labels.test.mjs` ; `--self-test`, cas « contract constant without a label » (échec qui nomme `UNKNOWN_STATE`) |
| idem | Badge d'en-tête caractérisé | E6 | 1.7 | `labels.test.mjs` (« the header badge shows the raw index state (characterised: the contract language is decided later) ») ; traduction : AUD-QUA-03 |
| exploration-semantique › Un échec de copie de la référence est signalé dans la page | Autorisation refusée ; Copie réussie | D6 | 3.1, 3.2 | `clipboard.test.mjs` (« a refused copy shows a message in the banner and leaves no unhandled rejection », rouge sur `app.js` du HEAD ; « a copy that succeeds writes the file and its lines and leaves the banner unchanged ») |

## Questions ouvertes

- [Question, propriétaire] D4 : Node du runner avec version minimale (recommandé) ou `actions/setup-node` épinglée.
- [Question, propriétaire] D6 : corriger le presse-papiers ici (recommandé) ou au sprint 8.
- [Question, non bloquante] Version majeure minimale de Node : fixée en tâche 1.2 après lecture de la version du runner.
