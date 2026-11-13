# Tasks

Aucun constat d'audit (`AUD-…`) n'est à l'origine de ce changement : décision du propriétaire du 2026-11-13.

## 1. Retirer `develop` des fichiers opératifs et des consignes

- [x] 1.1 Rouge : après le renommage du ruleset dans `.github/required-checks.json`, `python scripts/quality/test_verify_ruleset.py` échoue sur `test_the_declared_manifest_of_the_repository_is_readable` (ancien nom attendu) (`controles-de-fusion` › Les checks exigés pour une fusion sont déclarés dans le dépôt)
- [x] 1.2 Correction : workflows (`branches: [main]`), `.github/dependabot.yml`, `.github/required-checks.json`, `.archgate/config.json`, `scripts/ci/qualify-docker-upgrade.ps1`, `scripts/remediation/run-final.ps1` et `.sh`, littéraux de `scripts/docs/check-current-docs.py` et `scripts/remediation/check-audit-remediation-v2.py`, `scripts/quality/test_verify_ruleset.py`, `M29DockerReleaseCiGateContractTest` ; vérifier par `python .claude/scripts/run_gates.py` (tous verts)
- [x] 1.3 Consignes : hooks, commandes, skills et règles de `.claude/`, `AGENTS.md`, `openspec/config.yaml`, `README.md`, `SECURITY.md`, phrase de `docs/project/governance/ROADMAP.md` ; vérifier par `scripts/docs/check-current-docs.py` et `scripts/docs/product-facts.py --check`
- [x] 1.4 Documentation courante restante : `docs/developer/quality-gates.md`, `docs/developer/ai-configuration.md`, `docs/developer/TOOLCHAIN_POLICY.md`, `docs/architecture/diagrams/capacites/chaine-de-livraison.md`, `docs/user/production-installation.md`, `docs/user/remote-indexing.md`, section de flux de `STATUS.md` ; relire chaque mention et ne changer que celles qui décrivent le flux en vigueur — fait le 2026-11-13 : `chaine-de-livraison.md`, `controles-de-fusion.md`, `quality-gates.md`, `ai-configuration.md`, `TOOLCHAIN_POLICY.md`, deux lignes de `STATUS.md`, une de `ROADMAP.md` ; `production-installation.md`, `remote-indexing.md`, `indexing-and-storage.md` et le registre des risques citent `develop` comme un fait daté et restent inchangés

## 2. Après fusion

- [x] 2.1 **(manuelle)** `python scripts/quality/verify-ruleset.py` contre GitHub : les checks du ruleset « Protect main » correspondent à la déclaration — exécuté le 2026-11-13 : `RULESET VERIFICATION SUCCESS (2 rulesets require exactly the declared checks)`
- [x] 2.2 **(manuelle)** Supprimer `develop` : `git push origin --delete develop`, puis `git branch -D develop` sur chaque poste ; vérifier qu'aucune PR ni aucune règle de protection ne la cite encore — constaté le 2026-11-13 : la branche n'existe plus sur GitHub (seule `main` subsiste, aucune PR ne la cite) ; la suppression de la copie locale reste à faire sur chaque poste
- [x] 2.3 **(manuelle)** Vérifier la première PR Dependabot : elle cible `main` — constaté le 2026-11-13 : les PR Dependabot #722 à #725 ciblent `main`
- [x] 2.4 **(manuelle)** Décider du sort de la qualification Docker A → B sur toute PR (proposition, « Questions ouvertes ») — décision non prise : consignée comme travail ouvert dans `docs/project/governance/ROADMAP.md`, hors de ce changement

## 3. Clôture

- [x] 3.1 Mettre à jour la matrice de traçabilité du design (exigence → scénario → conception ou diagramme → tâche → test ou preuve) en nommant les preuves manquantes ; vérifier qu'aucune ligne n'affirme une couverture sans preuve
- [x] 3.2 Mettre à jour le dossier durable `docs/architecture/diagrams/capacites/controles-de-fusion.md` ; vérifier par `python scripts/docs/check-capability-dossiers.py`
- [x] 3.3 Valider les spécifications ; vérifier par `openspec validate --all --strict`
- [x] 3.4 Lister les documents à mettre à jour avant archivage (STATUS, ROADMAP, docs/user, docs/developer, arc42) ; vérifier que chacun est fait ou nommé comme reste

## Workflow follow-up

- Archiver le changement après revue (`openspec archive retirer-la-branche-develop -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
