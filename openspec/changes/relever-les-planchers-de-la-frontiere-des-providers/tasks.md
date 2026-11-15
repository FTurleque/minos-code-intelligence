# Tasks

## 1. Mesure et garde de l'invariant (AUD-TST-03)

- [ ] 1.1 AUD-TST-03 — **(manuelle)** télécharger les artefacts `jacoco-modules-Linux` et `jacoco-modules-Windows` du dernier run vert de `pr-ci.yml` sur `main` (`gh run download`, à la demande du propriétaire) et relever lignes et branches couvertes de `ProviderProcessEnvironment`, `StrongProcessOwnershipIndexerExecutor`, `ScipJavaProcessPlanFactory` et `ManagedPolyglotScipRuntimeManager` sur chaque système. Vérifier : tableau (classe, système, couvert / total, run) consigné dans la PR ; aucune valeur écrite sans rapport lu.
- [ ] 1.2 AUD-TST-03 — Rouge : ajouter à `check-jacoco.py --self-test` les cas « plancher abaissé sous le minimum », « plancher de classe retiré » et « table réelle » (table nominative des quatre classes : 0,80 en branches pour le filtre, 0,50 pour les autres ; un plancher de classe est un seul nombre par compteur). Exigence : controles-de-fusion › Les classes de la frontière de confiance des providers ont un plancher de branches minimal. Vérifier : `python scripts/quality/check-jacoco.py --self-test` rouge sur la table réelle (0,20, 0,18, 0,15, 0,20).

## 2. Tests manquants (AUD-TST-03)

- [ ] 2.1 AUD-TST-03 — `ProviderProcessEnvironment` : si la plus basse des deux mesures de 1.1 moins environ 5 points est sous 0,80 en branches, compléter ses tests (`minos-runtime-local`) branche par branche — préfixes refusés, exception `npm_config_cache`, fragments de secret, variable déclarée refusée, casse — chaque test affirmant le refus ; tenir compte du test ajouté par `refuser-un-nom-de-secret-herite-et-une-garde-illisible` (AUD-SEC-05) s'il est fusionné. Vérifier : `./mvnw -B -ntp -pl minos-runtime-local -am test "-Dtest=ProviderProcessEnvironment*Test" "-Dsurefire.failIfNoSpecifiedTests=false"` vert ; mesure relue dans les rapports Linux et Windows de la CI de la PR (le rapport de module est produit en phase `verify`, `pom.xml:253-256` ; localement : `./mvnw -B -ntp -pl minos-runtime-local -am verify`, long), branches couvertes ≥ 0,85.
- [ ] 2.2 AUD-TST-03 — `StrongProcessOwnershipIndexerExecutor`, `ScipJavaProcessPlanFactory`, `ManagedPolyglotScipRuntimeManager` : pour chaque classe dont la plus basse des deux mesures est sous 0,55 en branches, écrire les tests des branches de refus manquantes dans le module de la classe (`minos-runtime-local`, `minos-provider-scip`), sans dépendre du `PATH`, de `JAVA_HOME` ni de la langue de l'hôte. Si une classe reste sous 0,55 sur un système après ces tests, s'arrêter et appliquer la décision D2 du propriétaire. Vérifier : `./mvnw -B -ntp -pl minos-provider-scip -am test` puis `./mvnw -B -ntp -pl minos-runtime-local -am test` (jamais en parallèle) ; mesures relues dans les rapports Linux et Windows de la CI de la PR.

## 3. Planchers relevés (AUD-TST-03)

- [ ] 3.1 AUD-TST-03 — Correction : relever les quatre `prefixMinimums` de `scripts/quality/check-jacoco.py` à la plus basse des mesures Linux et Windows moins environ 5 points — un seul nombre par compteur, la lecture `float(minimums["line"])` / `float(minimums["branch"])` n'acceptant pas de dictionnaire par système — avec le run et les décomptes des deux systèmes en commentaire anglais ; chaque clé reste déclarée dans `prefixes` ; ajouter le cas d'auto-test « érosion du filtre » sur rapport synthétique. Vérifier : `python scripts/quality/check-jacoco.py --self-test` vert ; `python scripts/quality/check-jacoco.py` vert sur les rapports Linux et Windows de la CI de la PR (scopes `provider-execution-trust-boundary` et `m24-polyglot-provider-platform`) ; `python scripts/quality/check-ci-wiring.py` et `python scripts/quality/check-single-execution.py` verts.
- [ ] 3.2 AUD-TST-03 — Documentation : reporter l'exigence et sa qualification dans `docs/architecture/diagrams/capacites/controles-de-fusion.md`. Vérifier : `python scripts/docs/check-capability-dossiers.py`.

## 4. Clôture

- [ ] 4.1 AUD-TST-03 — Mettre à jour la matrice de traçabilité du design (mesures réelles, preuves manquantes nommées) ; vérifier qu'aucune ligne n'affirme une couverture sans preuve.
- [ ] 4.2 AUD-TST-03 — Contrôler le dossier de capacité ; vérifier par `python scripts/docs/check-capability-dossiers.py`.
- [ ] 4.3 AUD-TST-03 — Valider ; vérifier par `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`.
- [ ] 4.4 AUD-TST-03 — Lister les documents à mettre à jour avant archivage (dossier de capacité, `docs/project/audits/2026-11-15/SUIVI.md`) ; vérifier que chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après revue (`openspec archive relever-les-planchers-de-la-frontiere-des-providers -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
