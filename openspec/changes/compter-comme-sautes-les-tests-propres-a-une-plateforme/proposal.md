# Proposal

## Why

Constat **AUD-TST-04** (audit du 2026-11-15, sprint 1, sévérité faible ; réserve résiduelle de AUD-TST-06 du 10/10). Des tests propres à un système se terminent encore par un retour anticipé (`if (!windows) { return; }`) : sur l'autre système ils sont comptés **réussis** sans rien exécuter. La règle du dépôt (`docs/developer/testing.md:121`) et deux exigences existantes demandent `@EnabledOnOs`, qui les compte comme **sautés**. Le contrôle écrit au suivi du 10/10 est une recherche sur une seule ligne, limitée à `minos-runtime-local` et à deux noms de méthode : il ne voit ni la forme sur deux lignes, ni les autres modules, ni une variable locale.

## What Changes

- Les cinq tests concernés (quatre cités par l'audit, un cinquième relevé au HEAD) sont restreints par `@EnabledOnOs` et perdent leur retour anticipé.
- Un gate `scripts/quality/check-test-platform-guards.py` refuse, dans les sources de test Java de tous les modules (plugin IntelliJ compris), un retour anticipé gardé par une condition de système d'exploitation, sur une ou plusieurs lignes ; les sites légitimes hors méthode de test (extensions et outillage de test) sont dans une liste fermée, chacun avec sa raison. Le gate a un auto-test et les deux sont exécutés par le job `invariants`.

Aucun code de production ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `controles-de-fusion` : deux exigences ajoutées (aucun test ne passe à vide derrière un retour anticipé de plateforme, dans tout le dépôt ; les exceptions sont une liste fermée et motivée). Les exigences par module déjà écrites (`confinement-code-non-fiable › Un test propre à une plateforme est compté comme sauté sur l'autre`, `installation-des-providers › Un test du provider propre à Windows est compté comme sauté ailleurs`) ne sont pas modifiées : le code les rejoint.

## Hors périmètre

- Les 137 appels `assumeTrue` / `assumeFalse` des tests : un saut par hypothèse est compté et nommé ; leur recensement n'est pas l'objet de ce constat. Le cas particulier du test inter-JVM est traité par `faire-echouer-les-preuves-de-verrou-muettes` (AUD-TST-05).
- Les délais fixes et fenêtres d'horloge des tests : AUD-TST-06, sprint 6.
- Les retours anticipés de **capacité** : `if (!createSymbolicLink(...)) return;` apparaît 34 fois dans 14 fichiers de test de `minos-engine` et `minos-storage` ; ces tests passent à vide quand le système refuse de créer un lien symbolique. Même défaut, autre garde : il est inscrit comme constat de suivi (conversion des 34 sites en hypothèses nommées, changement à part) et n'est ni corrigé ni reconnu par le gate ici.
- Les scripts PowerShell et Python de test.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Quatre tests à retour anticipé sur deux lignes | [Existant vérifié] | `minos-runtime-local/src/test/java/com/minos/runtime/local/process/CommandLocatorTest.java:155-157`, `:179-181` ; `minos-provider-scip/src/test/java/com/minos/adapter/scip/runtime/install/ManagedPolyglotScipRuntimeManagerTest.java:62-64` ; `minos-app/src/test/java/com/minos/app/adapter/scip/M24PolyglotProviderTest.java:120-122` |
| Un cinquième, sur une ligne, par variable locale | [Existant vérifié] | `CommandLocatorTest.java:89-90` (`if (!os.contains("linux")) return;`) |
| 34 retours anticipés sur l'échec de création d'un lien symbolique, dans 14 fichiers | [Existant vérifié] | recherche de `if (!createSymbolicLink(` … `) return;` dans `minos-*/src/test` ; exemples : `minos-engine/src/test/java/com/minos/storage/MinosRuntimeSettingsConfigurationFileTest.java:52`, `:63` |
| Quatre retours anticipés légitimes hors méthode de test | [Existant vérifié] | `minos-engine/src/test/java/com/minos/testsupport/IcaclsTestAccessControl.java:36`, `:42` ; `minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/AppContainerGrantLeakCheck.java:33`, `:39` |
| Le contrôle du suivi est une recherche monoligne limitée à un module | [Existant vérifié] | `docs/project/audits/2026-10-10/SUIVI.md:9` ; scénario « Plus de passage à vide » de `openspec/specs/confinement-code-non-fiable/spec.md:179-191` |
| Un gate Python généralise le contrôle | [Décision] | design, D2 |

## Impact

- **Modules du reactor** : sources de test de `minos-runtime-local`, `minos-provider-scip`, `minos-app`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-test-platform-guards.py` et `--self-test`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py` ; `./mvnw -B -ntp -pl minos-runtime-local,minos-provider-scip -am test` puis `-pl minos-app -am test` (jamais deux builds en parallèle sur le poste) ; scopes JaCoCo `provider-execution-trust-boundary` (porte `CommandLocator`) et `m24-polyglot-provider-platform` (porte `ManagedPolyglotScipRuntimeManager`, rouge sur poste Windows hors régression : comparer à `main`).
- **Fusion** : quatre changements du sprint 1 ajoutent des étapes au job `invariants` de `.github/workflows/pr-ci.yml` (`executer-le-front-de-l-explorateur-en-test`, `compter-comme-sautes-les-tests-propres-a-une-plateforme`, `doter-chaque-gate-d-un-auto-test`, `epingler-les-fins-de-ligne-des-scripts-posix`), et `borner-la-couverture-des-zones-nouvelles` y modifie l'artefact de validation : les fusionner en série, chaque PR remise à jour sur `main` avant la suivante.
- **Plateformes** : le décompte des tests sautés augmente de trois sous Linux et de un sous Windows ; aucun test ne disparaît.
- **Documentation durable et diagrammes** : `docs/developer/testing.md`, `docs/developer/quality-gates.md`, `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

Aucune bloquante. Non bloquante : ouvrir le constat de suivi des 34 gardes de lien symbolique ; recommandation : un changement à part, qui convertit les 14 fichiers et étend alors le gate à ce motif précis.
