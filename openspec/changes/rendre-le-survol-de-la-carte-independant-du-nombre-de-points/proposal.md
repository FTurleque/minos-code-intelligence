# Proposal

## Why

Constat **AUD-PERF-10** de l'audit `docs/project/audits/2026-11-15/` (sévérité faible, sprint 4). Dans la page de l'explorateur, chaque mouvement de souris sur la carte 2D cherche le point survolé en parcourant tous les points affichés, et, quand un filtre de nature est actif, réalloue d'abord la liste filtrée. Rien ne limite la fréquence de ce traitement. L'audit juge l'effet nul au défaut (100 000 points) et sensible au plafond (1 000 000 de points) ; ces deux appréciations sont des **estimations**, aucune mesure n'existe. Le plafond de 1 000 000 de points n'est pas atteignable par le produit : la synchronisation ne construit pas d'index de plus de 250 000 documents, ni de plus de 32 768 à 768 dimensions (`SemanticIndexBudget`).

Le front n'est exécuté par aucun test au HEAD : ce changement dépend de AUD-TST-02 (sprint 1, changement `executer-le-front-de-l-explorateur-en-test`, non livré), qui apporte le harnais `node:test` et la caractérisation de la carte. Il exige une mesure avant et après et ne présente aucun gain comme acquis.

## What Changes

- Les points de la carte sont rangés, à leur réception, dans une grille spatiale ; la recherche du point survolé ou cliqué n'examine que les cellules voisines du pointeur.
- La liste des points d'une nature est calculée au changement de filtre ou de points, puis réutilisée ; elle n'est plus réallouée à chaque événement.
- Le survol est traité au plus une fois par image affichée.
- Le point rendu par la recherche est le même que celui du parcours linéaire actuel (même rayon, même départage).
- Mesure avant et après : nombre de points examinés par survol et durée d'une recherche à 100 000 et 250 000 points (la plus grande carte que le produit peut préparer), plus 1 000 000 de points comme jeu synthétique sous Node, dit tel, par le harnais de AUD-TST-02 ; parcours manuel dans un navigateur sur une projection réellement préparée.
- Aucune rupture : aucune route, aucun champ du contrat de l'explorateur, aucun texte de la page ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : trois exigences **ajoutées** (aucune exigence existante modifiée) — recherche du point sous le pointeur bornée aux cellules voisines, filtre de nature calculé une fois par changement, survol traité au plus une fois par image.

## Hors périmètre

- Le format compact des points de la route `projection/points` (tableaux parallèles) : l'audit dit « envisager » ; c'est un changement du contrat de l'explorateur, à décider sur une mesure du transfert à 1 000 000 de points (design D3).
- Le chargement en pages séquentielles de 5 000 points (200 requêtes au plafond) : inchangé.
- Le coût du dessin (`drawPlainPoints` parcourt les points une fois par nature) : relevé dans le design, non traité ; à mesurer.
- Le rendu WebGL, « non supporté, sans engagement » (`docs/user/semantic-explorer.md`).
- Le harnais de test du front et le contrôle de syntaxe en CI (AUD-TST-02) : prérequis.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `pointermove` appelle `hoverMap`, donc `pointAt`, à chaque événement hors glissement | [Existant vérifié] | `minos-explorer/src/main/resources/com/minos/explorer/web/app.js:738`, `:690-698` |
| `pointAt` parcourt `visiblePoints()` en entier | [Existant vérifié] | même fichier `:638-651` |
| `visiblePoints()` réalloue par `filter` quand une nature est choisie | [Existant vérifié] | même fichier `:483-486` |
| Seul le dessin passe par `requestAnimationFrame` | [Existant vérifié] | même fichier `:581-583` |
| Les points sont chargés par pages de 5 000, l'une après l'autre | [Existant vérifié] | même fichier `:461`, `:553-563` |
| Plafond de 1 000 000 de points, défaut de 100 000 | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticProjectionService.java:25-26` |
| Un index constructible a au plus 250 000 documents (32 768 à 768 dimensions) : aucune carte de 1 000 000 de points ne peut être préparée | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` |
| Aucun test n'exécute `app.js` | [Existant vérifié] | `minos-explorer/src/test/java/com/minos/explorer/WebResourcesContentTest.java:36-116` (contrôles par sous-chaîne) |
| « Sans effet à 100 000 points », « ~150 Mo de JSON à 1 000 000 » | [Question] | estimations de l'audit, non mesurées |
| Grille spatiale construite à la réception | [Proposition] | design D1 |

## Impact

- **Modules du reactor** : `minos-explorer` (ressource `com/minos/explorer/web/app.js` et ses tests). Aucun code Java de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; la page garde ses textes et ses requêtes.
- **ADR** : aucun.
- **Gates à rejouer** : `scripts/quality/check-explorer-front.py` (créé par AUD-TST-02) ; `WebResourcesContentTest` (chaînes littérales d'`app.js` à conserver) ; `scripts/quality/check-comment-language.py` (`minos-explorer` en anglais) ; `python .claude/scripts/run_gates.py --fast` ; scope JaCoCo nominatif de l'explorateur s'il existe (AUD-TST-01 ; `app.js` n'y entre pas).
- **Plateformes** : Windows et Linux — code de navigateur ; tests sous Node sur les deux ; parcours manuel fait dans Chrome sous Windows seulement à ce jour.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/exploration-semantique.md` ; `docs/user/semantic-explorer.md` seulement si une limite mesurée est à écrire.

## Questions ouvertes

Non bloquantes :

- D3 — format compact des points : hors périmètre, à décider sur mesure (recommandation : ne rien changer au contrat ici).
- Version de Node disponible pour `node:test` : fixée par AUD-TST-02.
- Seuil d'acceptation : aucun chiffre n'a de source ; l'exigence porte sur le nombre de points examinés, qui se compte.
