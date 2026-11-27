# Proposal

## Why

Constats **AUD-QUA-09** et **AUD-QUA-10** de l'audit `docs/project/audits/2026-11-15/` (sprint 12, sévérité faible, ouverts le 2026-11-27 au commit `4f4cac81`).

- **AUD-QUA-09.** La table « Cas d'usage obtenus par la façade » de `docs/user/java-api.md` donne `application.projectIndexing(executorDecorator)` comme remplaçant de la fabrique `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` (`:372`). Cette méthode de `MinosApplication` est privée au package (`MinosApplication.java:371`) ; son seul relais public est l'accès de test du `test-jar` (`MinosApplicationTestAccess.java:87-89`). Un hôte qui décorait les exécuteurs est renvoyé vers une méthode qui ne compile pas. L'exigence « La table de rupture de la façade ne promet aucun remplaçant absent » ne vise que la table des accesseurs retirés (`:355-361`).
- **AUD-QUA-10.** `docs/project/governance/STATUS.md:19` et `ROADMAP.md:23` écrivent « garde de frontières testée règle par règle (154 cas) ». Les cinq auto-tests du garde comptent 332 cas au HEAD. Aucun gate ne lit ce nombre.

## What Changes

- `docs/user/java-api.md` : la ligne de `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` dit qu'il n'existe aucun remplaçant public (la décoration des exécuteurs est réservée aux tests, par `MinosApplicationTestAccess`). Les titres des trois sections datées « version 1.3.0 » (`:365`, `:380`, `:390`) passent à « prochaine version » : leurs ruptures viennent de commits du 2026-11-18, contenus dans aucun tag (design, écart 1).
- `docs/user/release-notes/unreleased.md` : un paragraphe annonce le retrait des fabriques statiques des cas d'usage et les méthodes ajoutées au port interne `MinosApplicationComposer`, et renvoie à `java-api.md`.
- `STATUS.md:19` et `ROADMAP.md:23` : le nombre de cas est retiré, « testée règle par règle » reste (D2).
- L'exigence « La table de rupture de la façade ne promet aucun remplaçant absent » est étendue à toutes les tables de rupture de `MinosApplication` dans `java-api.md` (`MODIFIED`).
- Une exigence ajoutée interdit dans `STATUS.md` et `ROADMAP.md` un nombre de cas d'auto-test que rien ne relit.

Aucun code, test, gate ni workflow ne change. Aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : une exigence modifiée (tables de rupture de la façade).
- `controles-de-fusion` : une exigence ajoutée (aucun décompte d'auto-test non relu dans les documents d'état).

## Hors périmètre

- `docs/developer/quality-gates.md:232` (« `test_check_compose_limits.py`, 27 cas », 38 au HEAD) : même défaut, relevé par la contre-vérification, hors des deux documents d'état que vise l'exigence ; à joindre au passage `/minos:docs-sync`.
- Les autres phrases fausses des documents courants relevées le 2026-11-27 en réserve de constats fermés : `STATUS.md:47` (retrait de Java 24, réserve d'AUD-DEP-04) et `docs/project/roadmap/storage-hexagonal-2026-10/README.md:7`, `:113`, `:123` (réserve d'AUD-DEP-06). Elles bénéficieraient du même passage de `/minos:docs-sync`, mais ne relèvent pas du sprint 12 (design, D3).
- La mise à jour d'ARCH-0087 (tâches 4.2 et 3.3 du sprint 11, décision D5) : tâche manuelle du sprint 11.
- Rendre `projectIndexing(UnaryOperator)` de nouveau public : contraire à la décision du sprint 11 (AUD-ARC-13, relais package-private).
- Les changements archivés (`openspec/changes/archive/`), dont le design de `tenir-le-contrat-de-la-facade-par-le-garde` : de l'historique.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `java-api.md:372` promet `application.projectIndexing(executorDecorator)` | [Existant vérifié] | relu au HEAD `4f4cac81` |
| Sections `:380` et `:390`, titrées « version 1.3.0 », viennent de `1cfab6fba` et `2c9c0843b` (2026-11-18), contenus dans aucun tag | [Existant vérifié] | contre-vérification du 2026-11-27 (`git tag --contains`) |
| `projectIndexing(UnaryOperator<IndexerExecutor>)` est privée au package ; relais public seulement dans le `test-jar` | [Existant vérifié] | `MinosApplication.java:371`, `:376` ; `MinosApplicationTestAccess.java:87-89` |
| Dans `v1.3.0`, `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator)` est publique et `MinosApplication` n'a aucune méthode `projectIndexing` | [Existant vérifié] | `git show v1.3.0:…/indexing/ProjectIndexingUseCase.java:207` ; `git show v1.3.0:…/MinosApplication.java` sans `projectIndexing` |
| Fabriques retirées par `76afac5ed` (2026-11-18), postérieur à `v1.3.0` (2026-11-10) | [Existant vérifié] | `git log -S`, `git tag --contains 76afac5ed` vide |
| `unreleased.md` ne mentionne pas le retrait des fabriques | [Existant vérifié] | `docs/user/release-notes/unreleased.md` relu en entier |
| 332 cas dans les cinq auto-tests du garde (77, 86, 50, 38, 81) | [Existant vérifié] | `grep -c "    def test_" scripts/architecture/test_check_module_boundaries*.py` |
| Aucun gate ne lit « 154 » ni « règle par règle » | [Existant vérifié] | `grep` sur `scripts/docs/*.py` |
| Nombre retiré plutôt que lu d'une source | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune signature ni sortie ; la documentation de l'API Java est corrigée.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/docs/check-capability-dossiers.py`, `scripts/quality/check-toolchain-baseline.py` (liste fermée de documents, dont des pages de `docs/`), `scripts/quality/check-audit-remediation-v2.py` et `scripts/quality/check-post228-hardening.py` (ils lisent `STATUS.md` et `ROADMAP.md`), `python .claude/scripts/run_gates.py --fast`. Aucun scope JaCoCo.
- **Plateformes** : sans objet (documentation).
- **Documentation durable** : `docs/user/java-api.md`, `docs/user/release-notes/unreleased.md`, `STATUS.md`, `ROADMAP.md`, dossiers `frontieres-de-modules.md` et `controles-de-fusion.md` (traçabilité à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.
