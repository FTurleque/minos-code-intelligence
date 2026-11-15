# Design

## Contexte

Le gate `scripts/quality/check-jacoco.py` juge des scopes nominatifs et, dans un scope, des planchers de classe
(`prefixMinimums`). Motivation : voir `proposal.md` (Why). Changement d'outillage et de tests : sections réduites.

## État vérifié au HEAD

HEAD `89be3fb4`. Lecture des fichiers ; aucun build, aucun rapport JaCoCo lu.

| Fait | Preuve |
|---|---|
| Scope `provider-execution-trust-boundary` : 0,68 / 0,48 ; planchers de classe 0,35 / 0,18 et 0,70 / 0,20 | `scripts/quality/check-jacoco.py:169-184` |
| Scope `m24-polyglot-provider-platform` : 0,50 / 0,28 ; planchers de classe 0,28 / 0,20 et 0,35 / 0,15 | `scripts/quality/check-jacoco.py:112-145` |
| Méthode de fixation déjà employée : mesure CI des deux systèmes, moins une marge, consignée en commentaire | `scripts/quality/check-jacoco.py:38-45`, `:120-128` |
| Plancher de **scope** par système possible (`floor_for`) ; absent pour le système courant → `None` | `scripts/quality/check-jacoco.py:327-331` |
| Plancher de **classe** lu par `float(minimums["line"])` et `float(minimums["branch"])` : un seul nombre, `floor_for` n'y sert pas | `scripts/quality/check-jacoco.py:647-657` |
| Une clé de `prefixMinimums` absente de `prefixes` fait échouer le scope | `scripts/quality/check-jacoco.py:648-652` |
| Règle écrite des planchers de classe : « mesure moins environ 5 points » | `docs/developer/quality-gates.md:225` |
| Un scope réservé à un système porte `"platform"` et est `SKIPPED` ailleurs | `scripts/quality/check-jacoco.py:190-197`, `:206-213`, `:568` |
| Le rapport JaCoCo d'un module est produit en phase `verify` | `pom.xml:253-256` |
| `StrongProcessOwnershipIndexerExecutor` porte des branches Linux (cgroup) et Windows (Job Object) dans la même classe | `minos-runtime-local/src/main/java/com/minos/runtime/local/StrongProcessOwnershipIndexerExecutor.java:178`, `:188`, `:202`, `:212-225` |
| L'auto-test du gate est exécuté par le job des invariants | `.github/workflows/pr-ci.yml:206` |
| Le gate tourne sur Linux (complet) et sur Windows (sans le scope PostgreSQL) | `.github/workflows/pr-ci.yml:429`, `:465` |
| Les rapports par module des deux systèmes sont publiés en artefacts | `.github/workflows/pr-ci.yml:477-482` |
| Le filtre d'environnement : préfixes refusés, exception nommée, fragments de secret | `minos-runtime-local/src/main/java/com/minos/runtime/local/process/ProviderProcessEnvironment.java:120-140` |
| Trois classes de test dédiées au filtre, plus un test de l'assistant d'identité Windows | `minos-runtime-local/src/test/java/com/minos/runtime/local/process/ProviderProcessEnvironment*Test.java` ; `…/sandbox/WindowsExecutionPathIdentityHelperEnvironmentTest.java` |

Non mesuré : la couverture actuelle des quatre classes, sur aucun des deux systèmes.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit vise « mesure moins 2 à 5 points » ; la règle du dépôt pour un plancher de classe est « moins environ 5 points », et le sprint exige « au moins 0,50 en branches ». Si la plus basse mesure est sous 0,55, relever le plancher ne suffit pas : il faut d'abord des tests | `findings.json`, AUD-TST-03, action | `docs/developer/quality-gates.md:225` ; `sprints.md`, sprint 6, critère de sortie | Tâches : mesurer, tester, puis relever (D1) |
| Le plancher du filtre (0,20) est probablement très en dessous de sa mesure : trois classes de test le couvrent | `check-jacoco.py:182` | tests cités | À confirmer par la mesure ; ne rien affirmer avant |
| `StrongProcessOwnershipIndexerExecutor` a des branches propres à chaque système (Job Object, cgroup) ; les deux classes SCIP mesurent moins sous Windows. Un plancher de classe ne peut pas s'écrire par système | constat | `check-jacoco.py:120-128`, `:656-657` | Un seul nombre : la plus basse des deux mesures moins environ 5 points ; D2 si elle reste sous le minimum |
| D'autres planchers de classe bas existent dans les mêmes scopes | `check-jacoco.py:142`, `:162`, `:166` | constat (quatre classes) | Hors périmètre, cités |
| Le changement du sprint 1 édite la même table et le même auto-test | `openspec/changes/borner-la-couverture-des-zones-nouvelles/tasks.md` (tâches 1.2, 1.3) | ce changement | Fusion après le sprint 1 ; rebase sinon |

## Exigences couvertes

- controles-de-fusion › Les classes de la frontière de confiance des providers ont un plancher de branches minimal —
  D1, D2, D3.

## Objectifs et non-objectifs

Non applicable : voir la proposition.

## Responsabilités et frontières des composants

Non applicable : aucun module ne change de dépendance. Les tests ajoutés vivent dans le module de la classe testée.

## Modèle de domaine et invariants

Invariant vérifié par l'auto-test : pour chaque classe de la liste nominative, un plancher de classe existe et
`branch ≥ minimum` (0,80 pour le filtre, 0,50 pour les trois autres). Le plancher étant un seul nombre, il vaut sur
chaque système où le scope est jugé.

## Traitements et interactions

Non applicable.

## États et transitions

Non applicable.

## Contrats d'interface

Sortie du gate inchangée (`jacoco-gate.json`, messages `FAIL` existants). L'auto-test gagne des cas ; son code de
sortie reste 0 ou 1.

## Persistance et intégrité

Non applicable.

## Transactions, concurrence et idempotence

Non applicable.

## Sécurité et isolation

Le plancher du filtre d'environnement garde la règle de sécurité n° 3 (`.claude/rules/securite.md`) : liste
d'autorisation, motifs de secret exclus même déclarés. Les tests ajoutés affirment le **refus** (nom refusé absent
de l'environnement construit), pas seulement le nominal. Aucun secret réel dans les fixtures.

## Erreurs et reprise

Non applicable.

## Observabilité

Non applicable.

## Performance et mesure

Non applicable.

## Compatibilité, migration et retour arrière

Un plancher relevé peut rendre rouge une PR ouverte qui érode ces classes : effet voulu. Retour arrière : valeur
précédente, avec raison écrite dans le suivi de l'audit.

## Diagrammes

Non applicable : aucune structure ni interaction nouvelle.

## Décisions

### D1 — Mesurer, tester, puis relever

[Proposition] Lire les rapports `jacoco.xml` des deux systèmes sur le dernier run vert de `main` (artefacts
`jacoco-modules-Linux` et `jacoco-modules-Windows`). Pour chaque classe : prendre la plus basse des deux mesures ;
si elle atteint le minimum plus environ 5 points, relever ; sinon écrire les tests manquants (branches de refus
d'abord), remesurer sur la CI de la PR, puis relever à cette plus basse mesure moins environ 5 points. Les deux
mesures et le run sont consignés en commentaire du gate, comme `check-jacoco.py:120-128`. Même règle que le
changement `borner-la-couverture-des-zones-nouvelles` (sprint 1) pour ses planchers de classe.

### D2 — Classe qui ne peut pas atteindre 0,50 sur le système où elle mesure le moins

[Proposition — décision du propriétaire attendue] Si, après tests, la plus basse mesure d'une classe reste sous
0,55 en branches parce qu'une part de son code ne s'exécute que sur l'autre système :

- **A (recommandée)** : séparer le code. La logique indépendante du système reste dans la classe, sous son plancher
  d'au moins 0,50 ; le code propre à un système passe dans une classe de ce système, rangée dans le scope réservé
  correspondant (`provider-sandbox-linux`, `provider-sandbox-windows`, marqués `"platform"`), où elle est jugée sur
  son système et `SKIPPED` ailleurs. Conforme à controles-de-fusion › Une classe propre à une plateforme est jugée
  sur sa plateforme. Change du code de production : extraction nommée dans la PR, gates littéraux rejoués.
- **B** : faire lire les planchers de classe par système — tâche rouge puis correction dans `check-jacoco.py`
  (`floor_for` appliqué à `prefixMinimums`, cas d'auto-test, plancher absent pour le système courant = échec, comme
  controles-de-fusion › Un scope sans plancher pour le système courant échoue). Chaque système garde un plancher
  d'au moins 0,50 : l'option ne dispense d'aucun test, elle évite seulement que le système le plus bas tire l'autre.
- **C** : abaisser le minimum de cette classe. Contredit l'exigence ajoutée ; son delta est à réécrire avant
  l'implémentation.

Aucune option ne laisse une classe « non jugée » en silence sur un système.

### D3 — L'invariant vit dans l'auto-test du gate

[Proposition] Une table nominative des quatre classes et de leur minimum, vérifiée par `--self-test` sur la table
réelle et sur des copies mutées (plancher abaissé, plancher retiré). Alternative écartée : un gate séparé
(`check-ci-wiring.py` à étendre, pour un contrôle de quatre lignes).

## Qualification des capacités

`controles-de-fusion` : l'exigence ajoutée sera **qualifiée** quand les quatre planchers seront en place et verts sur
les deux systèmes ; **partielle** tant qu'une classe attend la décision D2 (elle garde alors son plancher actuel, et
le dossier le dit).

## Risques et compromis

- [Risque] Plancher fixé trop près de la mesure → marge d'environ 5 points, mesure prise sur le système le plus bas.
- [Risque] Tests de couverture sans assertion → chaque test ajouté affirme un refus ou une valeur ; relu par
  l'agent de revue de sécurité pour le filtre.
- [Risque] Rouge local du scope `m24-polyglot-provider-platform` sous Windows → comparer à `main`, juger sur la CI.

## Vérification

- `python scripts/quality/check-jacoco.py --self-test` (local, sans build).
- `python scripts/quality/check-jacoco.py` sur les rapports de la CI de la PR (Linux et Windows).
- `./mvnw -B -ntp -pl minos-runtime-local -am test`, puis `./mvnw -B -ntp -pl minos-provider-scip -am test` ; le
  rapport JaCoCo d'un module n'existe qu'après `verify` (`pom.xml:253-256`).
- Non vérifiable localement : les valeurs Linux (CI ou WSL) ; la mesure Windows de la CI diffère du poste.

## Documentation durable à mettre à jour

`docs/architecture/diagrams/capacites/controles-de-fusion.md` (exigence, traçabilité).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Les classes de la frontière de confiance des providers ont un plancher de branches minimal | Plancher abaissé sous le minimum | D3 | 1.2, 3.1 | `check-jacoco.py --self-test` — à écrire |
| idem | Plancher de classe retiré | D3 | 1.2, 3.1 | idem — à écrire |
| idem | Érosion de la couverture du filtre d'environnement | D1 | 3.1 | cas d'auto-test sur rapport synthétique — à écrire |
| idem | Table réelle | D1, D2 | 2.1, 2.2, 3.1 | auto-test + gate sur la CI des deux systèmes — manquante jusqu'à la mesure |

## Questions ouvertes

- Valeurs des planchers : issues de la tâche 1.1.
- D2 : option A, B ou C (recommandation : A), seulement si une classe reste sous 0,55 après la tâche 2.2.
