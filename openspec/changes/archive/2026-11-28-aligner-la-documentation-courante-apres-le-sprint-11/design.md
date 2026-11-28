# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement documentaire : trois pages courantes, des notes de version et deux exigences. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `4f4cac814` (PR #829), le 2026-11-27.

| Fait | Preuve |
|---|---|
| Table « Accesseurs de `MinosApplication` retirés » (prochaine version) : corrigée par le sprint 11, aucun remplaçant absent | `docs/user/java-api.md:350-363` |
| Table « Cas d'usage obtenus par la façade », titrée « version 1.3.0 » : `ProjectIndexingUseCase.of(MinosApplication, UnaryOperator<IndexerExecutor>)` → `application.projectIndexing(executorDecorator)` | `docs/user/java-api.md:365`, `:372` |
| `projectIndexing(UnaryOperator<IndexerExecutor>)` sans `public` ; `decoratedProjectIndexing` relais privé au package | `minos-application/src/main/java/com/minos/application/MinosApplication.java:371`, `:376-378` |
| Relais public réservé aux tests, dans le `test-jar` | `minos-application/src/test/java/com/minos/application/MinosApplicationTestAccess.java:87-89` |
| Les autres remplaçants de la table existent publiquement : `projectIndexing()` (`:359`), `projectIndexingViaWsl(Optional)` (`:386`), `remoteIndexing()`, `knowledgeExport()` | `MinosApplication.java` (relu) |
| Au tag `v1.3.0` (2026-11-10), les fabriques `ProjectIndexingUseCase.of(…)` sont publiques et `MinosApplication` ne déclare aucune méthode `projectIndexing` | `git show v1.3.0:minos-application/src/main/java/com/minos/application/indexing/ProjectIndexingUseCase.java` (`:207`, `:234`, `:245`) |
| Le retrait des fabriques vient de `76afac5ed` (2026-11-18), contenu dans aucun tag | `git log -S"public static ProjectIndexingUseCase of(MinosApplication application, UnaryOperator"` ; `git tag --contains 76afac5ed` vide |
| Les notes non publiées décrivent le retrait des accesseurs, pas celui des fabriques | `docs/user/release-notes/unreleased.md` (section « API Java : accesseurs internes de `MinosApplication` retirés ») |
| L'exigence en vigueur ne vise que la table des accesseurs retirés | `openspec/specs/frontieres-de-modules/spec.md:1618-1629` |
| « (154 cas) » dans les deux documents d'état ; aucune autre occurrence de « N cas » | `docs/project/governance/STATUS.md:19`, `ROADMAP.md:23` ; `git grep -nE "[0-9]+ cas\b"` |
| Cas des auto-tests du garde : 77 + 86 + 50 + 38 + 81 = 332 | `grep -c "    def test_"` sur `scripts/architecture/test_check_module_boundaries{,_adapters,_documents,_packages,_ratchets}.py` |
| Ni `check-current-docs.py` ni `product-facts.py` ne lisent ce nombre | `grep -n "154\|règle par règle" scripts/docs/*.py` vide |

## Écarts

1. **Plus large que le constat (AUD-QUA-09).** Le titre « version 1.3.0 » de la seconde table est faux : en 1.3.0, les fabriques existent et aucune méthode `projectIndexing` n'est déclarée par la façade. Le retrait appartient à la prochaine version, comme celui des accesseurs. `SUIVI.md:169` le dit (« Méthode absente de v1.3.0 : aucune rupture publiée »), la page non. Le remplaçant d'une fabrique publique en 1.3.0 doit donc être cherché dans l'état en vigueur : pour la décoration des exécuteurs, il n'y en a pas. Deux autres sections portent le même titre faux : « Fournisseurs embarqués câblés par la racine de composition » (`java-api.md:380`, commit `1cfab6fba`) et « Port de l'historique d'import » (`java-api.md:390`, commit `2c9c0843b`), toutes deux du 2026-11-18, contenues dans aucun tag. Elles sont corrigées par la même tâche.
2. **Notes de version.** `unreleased.md` (sections aux lignes 5, 14, 23 et 28) n'annonce ni le retrait des fabriques, ni les méthodes ajoutées au port `MinosApplicationComposer`. Un hôte qui lit les notes ne voit pas ces ruptures.
3. **AUD-QUA-10, second recoupement.** La fiche cite aussi `SUIVI.md:169` et le design archivé de `tenir-le-contrat-de-la-facade-par-le-garde`, qui écrivent que `ProjectInspectionService.view` « s'appelle `statusView` ». Les deux méthodes existent (`ProjectInspectionService.java:140`, `:169`). Le design archivé est de l'historique, non modifié. La ligne du suivi est corrigée par le coordinateur (tâche 3.3), hors de ce changement.
4. **Décompte de l'audit.** L'audit compte 335 cas avec `test_gate_runner.py`. Ce fichier teste le lanceur partagé, pas une règle du garde : le décompte des règles est 332. Le nombre est de toute façon retiré (D2).
5. **Constructeur public de l'orchestration.** `ProjectIndexingUseCase(Dependencies, UnaryOperator<IndexerExecutor>)` et le record `Dependencies` sont publics (`ProjectIndexingUseCase.java:87`, `:181`), comme en 1.3.0. Ils ne rendent pas le service de la fabrique retirée : l'hôte doit assembler lui-même les quinze ports, que la façade construit en privé (`MinosApplication.java:417-434`). La ligne de la table borne donc l'affirmation de D1 à la façade (« sur la façade, la décoration des exécuteurs est réservée aux tests ») ; l'indexation distante de la façade décore elle aussi les exécuteurs (`MinosApplication.java:402`).
6. **Méthode du port non annoncée.** `MinosApplicationComposer` gagne depuis `v1.3.0` cinq méthodes abstraites. `java-api.md` en décrit quatre (sections des fournisseurs embarqués et de l'historique d'import) ; `semanticProjectionStore(Path)` (commit `fd876d996`) n'y figure pas. Hors de ce changement : le paragraphe des notes de version ne donne aucun décompte et ne se présente pas comme la liste complète.

## Décisions

### D1 — Ligne de la fabrique décorée : « aucun remplaçant public » plutôt que suppression [Proposition, recommandation]

- (a) **Recommandé.** Garder la ligne et écrire « aucun remplaçant public : la décoration des exécuteurs est réservée aux tests (`MinosApplicationTestAccess` du `test-jar`) ». Le lecteur qui cherche la signature retirée trouve la réponse.
- (b) Supprimer la ligne : la table cesse de promettre, mais la rupture devient invisible.
- (c) Rendre la méthode publique : contraire à la décision du sprint 11 (AUD-ARC-13).

Le titre de la section passe à « prochaine version », comme celui de la table des accesseurs.

### D2 — Nombre de cas retiré, non lu d'une source [Proposition, recommandation]

- (a) **Recommandé.** « garde de frontières testée règle par règle », sans parenthèse. Un fait chiffré qui n'est relu par aucun gate vieillit à chaque PR du garde (+18 cas au sprint 11) ; une précision du type « un cas refusé et un cas accepté par règle » serait une autre affirmation à prouver.
- (b) Écrire 332 et ajouter le décompte à `product-facts.py` : un fait de plus à tenir pour une information sans usage pour le lecteur.

Une exigence de `controles-de-fusion` interdit le retour d'un tel nombre. Sa preuve est la commande de son scénario, rejouée à la clôture, sans gate nouveau : même choix que la décision du propriétaire du 2026-11-24 pour AUD-ARC-12.

### D3 — Restes documentaires d'autres constats non traités ici [Proposition, recommandation]

`docs/developer/quality-gates.md:232` (« `test_check_compose_limits.py`, 27 cas », 38 au HEAD : même défaut, document hors de l'exigence), `STATUS.md:47` (Java 24, réserve d'AUD-DEP-04) et `docs/project/roadmap/storage-hexagonal-2026-10/README.md:7`, `:113`, `:123` (réserve d'AUD-DEP-06) touchent les mêmes documents. Recommandation : les laisser au passage `/minos:docs-sync` déjà nommé par l'audit, pour garder ce changement aligné sur ses deux constats. Le propriétaire peut demander de les joindre à la PR : ce sont des corrections de prose sans exigence.

## Vérification

- `git grep -n "projectIndexing(executorDecorator)" -- docs/user/java-api.md` : aucune ligne, code 1.
- `git grep -nE "[0-9]+ cas\b" -- docs/project/governance/STATUS.md docs/project/governance/ROADMAP.md` : aucune ligne, code 1.
- `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/quality/check-toolchain-baseline.py`, `python .claude/scripts/run_gates.py --fast` : verts.
- Liens de `java-api.md` et `unreleased.md` vérifiés.

## Documentation durable à mettre à jour

`docs/user/java-api.md`, `docs/user/release-notes/unreleased.md`, `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md` ; à l'archivage : `docs/architecture/diagrams/capacites/frontieres-de-modules.md` (ligne de l'exigence modifiée, inchangée de titre) et `docs/architecture/diagrams/capacites/controles-de-fusion.md` (ligne de l'exigence ajoutée).

## Traçabilité

| Constat | Exigence | Tâches | Preuve attendue | Preuve exécutée |
|---|---|---|---|---|
| AUD-QUA-09 | frontieres-de-modules › La table de rupture de la façade ne promet aucun remplaçant absent (`MODIFIED`) | 1.1 à 1.4 | `git grep` vide ; titre « prochaine version » ; paragraphe des notes | Rouge : `git grep` de `projectIndexing(executorDecorator)` rend `java-api.md:372` (code 0). Après correction : aucune ligne (code 1) ; `git grep` de `retentionService()` sans ligne (code 1) ; liens relatifs des deux pages résolus ; `check-current-docs.py`, `check-toolchain-baseline.py`, `check-module-boundaries.py` verts |
| AUD-QUA-10 | controles-de-fusion › Les documents d'état ne citent aucun décompte d'auto-test non relu | 2.1 à 2.3 | `git grep` vide ; `check-current-docs.py` et `product-facts.py --check` verts | Rouge : `git grep` rend `STATUS.md:19` et `ROADMAP.md:23` (code 0). Après correction : aucune ligne (code 1) ; `check-current-docs.py`, `product-facts.py --check`, `check-audit-remediation-v2.py`, `check-post228-hardening.py` verts ; `run_gates.py --fast` 30/30 |

Validation du 2026-11-27 sur la branche `docs/aligner-la-documentation-apres-le-sprint-11` : `openspec validate --all --strict` 27/27, `check-capability-dossiers.py` vert, `archgate check` 9/9 ; exigence `MODIFIED` de 393 caractères.
