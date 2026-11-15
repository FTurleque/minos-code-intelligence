# Proposal

## Why

Constat **AUD-PERF-02** de l'audit `docs/project/audits/2026-11-15/` (sévérité faible, sprint 4). `minos semantic projection prepare` relit tout l'index à chaque étape du calcul : une passe de sélection, une passe de centrage, jusqu'à 40 passes d'ajustement et une passe de projection, soit jusqu'à 43 lectures complètes. Les passes d'ajustement ne retiennent pourtant que les vecteurs de l'échantillon et ignorent les autres.

La borne de 43 est **théorique** : le nombre réel de passes n'a pas été relevé par l'audit, alors que la commande le rend déjà (`passes`). Aucune durée n'a été mesurée sur un index réel ni à 768 dimensions. Ce changement n'annonce donc aucun gain : il exige une mesure avant et après sur le banc de AUD-PERF-13 (sprint 1, non livré), sans lequel il ne démarre pas.

## What Changes

- Quand les vecteurs de l'échantillon tiennent dans le budget de mémoire de la préparation, ils sont copiés une fois (en `float`, comptés sur ce budget) pendant une seule lecture de l'index après la sélection ; centrage, ajustement et projection itèrent ensuite en mémoire. L'index est alors lu **deux fois** quel que soit le nombre d'itérations.
- Pour tout index que la synchronisation peut construire, l'échantillon pèse au plus 96 Mio en `float` et tient dans le budget par défaut de 256 Mio : le chemin en mémoire est le cas courant.
- Quand ils n'y tiennent pas (budget abaissé par `--max-memory-mb`), la préparation garde le parcours en flux actuel et le dit : le rapport de la commande porte la stratégie suivie et le nombre d'itérations d'ajustement (champs additifs) et nomme `--max-memory-mb`.
- `passes` garde son sens, le nombre de parcours de l'index : 2 en mémoire, 4 à 43 en flux.
- Les deux stratégies rendent les mêmes coordonnées pour un même index et une même graine.
- Mesure avant et après sur le banc de AUD-PERF-13 : durée, `passes` et pic de mémoire de travail, stockage local et PostgreSQL, sur un index que la synchronisation peut construire (32 768 documents à 768 dimensions au plus).
- Aucune rupture : la méthode (`pca`), ses paramètres, la graine et le format de la projection publiée ne changent pas.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : trois exigences **ajoutées** (aucune exigence existante modifiée) — lecture de l'index bornée à deux parcours quand l'échantillon tient en mémoire, repli annoncé sinon, coordonnées identiques entre les deux stratégies.

## Hors périmètre

- Le coût d'une page PostgreSQL (AUD-PERF-01, changement `retirer-le-comptage-par-page-des-lectures-postgresql`).
- Toute modification de la méthode : sous-échantillonnage supplémentaire, projection aléatoire préalable, itération par blocs. L'audit les cite comme replis ; elles changeraient les coordonnées publiées et la version de la méthode.
- Le budget par défaut (256 Mio) et le plafond de points par défaut (100 000) : inchangés, et sans raison de changer (design D3).
- Le compte de la projection dans le budget de mémoire des vues (AUD-PERF-04, sprint 5).
- L'extension du banc (AUD-PERF-13, sprint 1) : prérequis.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Chaque étape relit tout l'index par `walk` | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/ProjectionPreparation.java:73-81`, `:102`, `:148-162` |
| `walkSample` écarte les vecteurs hors échantillon après les avoir lus | [Existant vérifié] | même fichier `:135-145` |
| Au plus 40 itérations, tolérance `1e-9` | [Existant vérifié] | `SemanticProjectionService.java:24` ; `PrincipalAxes.java:19` |
| La commande rend déjà le nombre de passes et le pic de mémoire | [Existant vérifié] | `SemanticProjectionService.java:85-89` ; `minos-cli/src/main/java/com/minos/cli/command/SemanticProjectionCommand.java:109` |
| Défauts : 100 000 points, 256 Mio | [Existant vérifié] | `SemanticProjectionService.java:25`, `:28` |
| La synchronisation ne construit pas d'index dont les vecteurs dépassent 192 Mio comptés à 8 octets par dimension (32 768 documents à 768 dimensions), ni de plus de 250 000 documents | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/semantic/SemanticIndexBudget.java:15-19`, `:59-75` ; `SemanticIndexService.java:137-138` |
| L'échantillon d'un index constructible pèse donc au plus 96 Mio en `float`, sous le budget par défaut | [Existant vérifié] par calcul : documents × dimensions × 4 octets ≤ 192 Mio / 2 | idem |
| Un test existant encode le comportement en flux (`passes >= 3`, phase `fit` attendue) | [Existant vérifié] | `minos-application/src/test/java/com/minos/application/semantic/SemanticProjectionServiceTest.java:90-107` |
| L'échantillon en mémoire est stocké en `float` | [Proposition] | design D1 |
| Le repli est le parcours en flux actuel | [Proposition] | design D2 |
| Le gain en durée et le nombre réel de passes | [Question] | mesure sur le banc de AUD-PERF-13 |

## Impact

- **Modules du reactor** : `minos-application` (package `com.minos.application.semantic`) ; `minos-cli` pour le champ additif du rapport.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : CLI — deux champs **additifs** dans le rapport JSON de `semantic projection prepare` (stratégie suivie, itérations d'ajustement) et, en repli, une limitation nommée. API Java — le `record` public `SemanticProjectionService.PreparationReport` (`SemanticProjectionService.java:224-226`) gagne deux composants : additif pour qui le lit, à adapter pour qui le construit (tests). MCP, plugin et NEXUS : aucun.
- **ADR** : aucun (pas de choix durable entre modules ni de format persisté ; la méthode ne change pas).
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast` ; `scripts/architecture/check-module-boundaries.py` ; `scripts/quality/check-comment-language.py` ; goldens de `minos-app/src/test/resources/characterization/` si l'un d'eux porte le rapport de préparation ; scope JaCoCo nominatif de la projection s'il existe à la date de l'implémentation (AUD-TST-01).
- **Plateformes** : Windows et Linux — calcul en Java pur, résultat déterministe attendu identique.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/exploration-semantique.md` ; `docs/user/semantic-explorer.md` (stratégie, repli, repère de durée seulement s'il est mesuré) ; `docs/developer/semantic-explorer.md:108-116` (étapes de la préparation, aujourd'hui « chaque étape est un parcours de l'index »).

## Questions ouvertes

Non bloquantes pour la rédaction :

- D4 — les coordonnées d'un index PostgreSQL peuvent différer au dernier chiffre de celles préparées au HEAD (arrondi en `float` avant calcul) : sans effet sur le format publié ; à confirmer que la version de la méthode ne change pas (recommandation : inchangée).
- Seuil d'acceptation du gain : à fixer sur la mesure « avant ».
