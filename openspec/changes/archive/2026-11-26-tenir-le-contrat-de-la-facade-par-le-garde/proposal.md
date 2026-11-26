# Proposal

## Why

Constats **AUD-ARC-13** et **AUD-SEC-12** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouverts le 2026-11-25 au commit `75e10c68`).

- **AUD-ARC-13.** La règle A11 du garde de frontières ne lit que le **type de retour** des méthodes publiques de `MinosApplication`. Une méthode publique peut donc **recevoir** un type de `minos-engine` sans que le garde rougisse. C'est le cas au HEAD : `projectIndexing(UnaryOperator<IndexerExecutor>)` est publique, et `IndexerExecutor` est un port de l'orchestration du moteur. Sur une copie, une méthode publique `leakParam(ProjectRegistry)` ajoutée à la façade laisse le garde vert. ARCH-0087 § 2.2 (« rend ») est respecté à la lettre ; la promesse « façade sans port du moteur » (ARCH-0057, `docs/user/java-api.md`) ne l'est pas.
- **AUD-SEC-12.** ARCH-0087 § 2.3 fait de `MinosApplicationTestAccess` la **seule classe** du `test-jar` de `minos-application`. Rien ne le contrôle. Le garde ne lit que la portée de la dépendance ; l'IT d'empaquetage vérifie la présence de la classe et l'absence de déclaration de service, pas l'absence d'autres entrées. Sur une copie, un `<include>` élargi à `com/minos/application/**`, ajouté ou supprimé laisse le garde vert. Ce jar est lu par les tests de 7 modules (`minos-api`, `minos-app`, `minos-bootstrap`, `minos-cli`, `minos-explorer`, `minos-mcp`, `minos-nexus`).

## What Changes

- `projectIndexing(UnaryOperator<IndexerExecutor>)` devient privée au package `com.minos.application` sous le nom `decoratedProjectIndexing`, relayée par `MinosApplicationTestAccess` pour les trois tests qui l'appellent. `projectIndexing()` reste publique (design, D1).
- La règle A11 refuse aussi une méthode publique déclarée dans le corps de `MinosApplication`, ou un constructeur public, dont **un paramètre** nomme un type de `minos-engine` ou `MinosApplicationComposer`. Cliquet à zéro, sans table. Le `Builder` imbriqué reste hors de la règle (design, D2).
- Le garde lit le `<includes>` de l'exécution `test-jar` de `minos-application/pom.xml` et refuse tout autre contenu qu'une seule inclusion, `com/minos/application/MinosApplicationTestAccess.class`, ou une seconde exécution `test-jar`. L'IT `ApplicationTestAccessPackagingIT` affirme que les seules entrées du `test-jar` hors de `META-INF/` sont cette classe (design, D3).
- Le périmètre du contrat est écrit : les signatures de `MinosApplication`. Les services et cas d'usage rendus par la façade restent hors de la règle ; leur usage par une surface est déjà tenu par A11 côté consommateur (design, D4).

Aucune sortie de la CLI, de l'API Java publique (`com.minos.api`), du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences modifiées (la façade ne rend ni ne reçoit de type du moteur ; les tests lisent un port par un accès de test) et une exigence ajoutée (le `test-jar` de l'application ne publie que la classe d'accès).

## Hors périmètre

- Les signatures publiques des services et cas d'usage de la façade : `ProjectInspectionService.statusView(RegisteredProject)` (l'audit écrit `view`), `ProjectIndexingUseCase.execute(…, IndexingResumePolicy)`, les records `Preparation` et `Outcome`. Décision D4, recommandation : hors périmètre.
- Les méthodes du `Builder` public, qui reçoivent des ports du moteur par conception (D2).
- L'injection des ports de `LocalProjectOperations`, `ProviderPlatformService` et `LocalProjectGitQuery` : AUD-ARC-14, changement `injecter-les-ports-des-operations-locales`.
- La prose de la façade (Javadoc de classe, ARCH-0042, README des ADR, rétention dans `java-api.md`) : AUD-ARC-15 et AUD-QUA-08, changement `aligner-la-prose-de-la-facade-apres-le-sprint-10`.
- Le `test-jar` de `minos-engine` (doublure du contrôle d'accès) : aucun constat ne le vise.
- La rédaction d'ARCH-0087 : aucun ADR n'est écrit pendant la spécification ; la mise à jour de ses sections « Compliance and Enforcement » et « Mise en œuvre » est planifiée en tâche (4.2).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `projectIndexing(UnaryOperator<IndexerExecutor>)` est publique ; `IndexerExecutor` est un type de `minos-engine` | [Existant vérifié] | `MinosApplication.java:35`, `:358-361` ; `minos-engine/src/main/java/com/minos/orchestration/IndexingRuntimePorts.java` |
| Appelants : `MinosApplication.java:385` en production ; trois tests (`RemoteIndexingUseCases.java:21`, `IncrementalIndexingRealFixtureTest.java:67`, `UseCaseFacadeTest.java:66`) | [Existant vérifié] | recherche de `projectIndexing(` hors `target/` |
| La méthode n'existait pas en 1.3.0 | [Existant vérifié] | `git show v1.3.0:…/MinosApplication.java` : aucune occurrence ; introduite par `76afac5ed` |
| A11 ne lit que le type de retour | [Existant vérifié] | `scripts/architecture/module_boundary_ratchets.py:125-143`, `:166-199` |
| Méthode publique à paramètre moteur (nom qualifié ou importé) : garde vert ; méthode publique qui rend le même type : garde rouge | [Existant vérifié] | mutations rejouées sur une copie le 2026-11-25 (design) |
| `<includes>` élargi, complété ou supprimé : garde vert | [Existant vérifié] | mêmes mutations ; `check-module-boundaries.py:148-162` |
| L'IT ne vérifie que la présence de la classe et l'absence de service | [Existant vérifié] | `minos-app/src/test/java/com/minos/packaging/ApplicationTestAccessPackagingIT.java:28-38` |
| Contenu réel du `test-jar` (entrées `META-INF/maven/…`) | [Question] | non mesuré : aucun build Maven lancé |
| Renommage en `decoratedProjectIndexing` | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : `minos-application` (`MinosApplication`, `MinosApplicationTestAccess`, `MinosApplicationTestAccessTest`, `UseCaseFacadeTest`), `minos-cli` (test `RemoteIndexingUseCases`), `minos-app` (tests `IncrementalIndexingRealFixtureTest`, `ApplicationTestAccessPackagingIT`).
- **Surfaces publiques** : aucune sortie ne change. `MinosApplication` (classe interne, documentée dans `docs/user/java-api.md`) perd une méthode publique ajoutée après 1.3.0 : aucune rupture par rapport à une version publiée.
- **ADR** : aucun créé. ARCH-0087 : sections « Compliance and Enforcement » et « Mise en œuvre » à compléter (tâche 4.2), décision inchangée.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, `test_check_module_boundaries.py`, `test_check_module_boundaries_ratchets.py` (et les trois autres auto-tests du garde), `scripts/quality/check-remote-distributed-consistency.py`, `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-semantic-retrieval-consistency.py`, `scripts/remediation/check-p0-p2.py` (lisent `MinosApplication.java`), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo ne nomme `MinosApplication` ni `MinosApplicationTestAccess` ; SonarCloud juge le nouveau code de `minos-application`.
- **Plateformes** : Windows et Linux ; garde en Python standard, IT exécuté par les deux jobs `Verify`.
- **Documentation durable** : `docs/developer/quality-gates.md` (A11, `test-jar`), `docs/user/java-api.md` (ligne de remplacement qui cite `projectIndexing(executorDecorator)`), dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, ARCH-0087.

## Questions ouvertes

Aucune bloquante : D1 à D4 ont une recommandation.
