# Proposal

## Why

Constat **AUD-TST-08** (Faible, Tests) de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`,
sprint 6), réserve non levée d'AUD-TST-05 de l'audit du 2026-10-10 : huit classes du plugin IntelliJ (1 023 lignes)
ne sont nommées par aucun test — `MinosToolWindowPanel` (400), `MinosAdvancedAnalysisActions` (227),
`MinosSettingsConfigurable` (123), `MinosProjectService` (108), `MinosUiController` (77), `MinosNavigation` (43),
`MinosRegistryNotice` (24), `MinosToolWindowFactory` (21) — et le plancher de couverture du plugin est resté à 0,33
depuis sa mesure du 2026-10-15 (`minos-intellij/build.gradle.kts:140-148`).

Une partie de ces classes est de la logique sans interface (lecture de champs JSON, résumés, collecte des
emplacements, analyse des réglages saisis) logée dans des classes Swing : une régression d'affichage des résultats du CLI
n'y est détectée qu'à la main. Le changement ne touche que `minos-intellij` (Gradle, hors reactor) : il se fusionne
seul.

## What Changes

- La logique sans interface de `MinosToolWindowPanel` (présentation des réponses) et de `MinosSettingsConfigurable`
  (analyse de la chaîne saisie) est extraite dans des classes sans type `com.intellij` ni Swing, testées en JUnit
  simple et gardées par `PlatformFreeLogicTest`. Le champ requis des actions avancées est déjà délégué à
  `MinosJsonFields.required`, déjà testé : rien n'y est extrait.
- Les textes et listes affichés pour une réponse donnée du CLI restent **identiques**.
- Les planchers de couverture du plugin sont relevés à la nouvelle mesure Linux moins deux points.
- La documentation du plugin met à jour la liste des classes d'interface laissées sans test unitaire.

Aucune **RUPTURE** : ni action, ni réglage, ni commande CLI appelée ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `client-intellij` : deux exigences **ajoutées** (présentation des réponses du CLI testée hors des classes
  d'interface ; planchers du plugin tenus à la mesure moins deux points). L'exigence « La logique du plugin est
  testable sans la plateforme IntelliJ », qui interdit aux tests d'exiger une fixture de plateforme, est **inchangée**.

## Hors périmètre

- Des tests de plateforme (`BasePlatformTestCase`), proposés par l'audit : ils contrediraient l'exigence existante ;
  voir `design.md`, D1 (décision du propriétaire).
- Les classes qui restent de l'interface pure (`MinosToolWindowFactory`, `MinosUiController`, `MinosNavigation`,
  `MinosRegistryNotice`, le reste de `MinosProjectService` lié au PSI) : elles restent nommées dans la documentation.
- La mutation du plugin : jamais sur un poste ; elle se rejoue par `code-audit.yml` (changement
  `actualiser-le-document-de-mutation`).
- Le test de cadence du superviseur (changement `prouver-les-tests-temporises-par-un-signal`).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Huit classes sans référence dans `src/test`, 1 023 lignes | [Existant vérifié] | recherche du nom de chaque classe dans `minos-intellij/src/test` : 0 ; `wc -l` des huit fichiers |
| Planchers 0,33 / 0,33 (plugin) et 0,47 / 0,39 (`protocol`), mesure du 2026-10-15 | [Existant vérifié] | `minos-intellij/build.gradle.kts:140-155` |
| Ces classes sont déclarées non couvertes « par construction » | [Existant vérifié] | `docs/user/intellij-plugin.md:335` |
| Les tests du plugin ne doivent pas exiger de fixture de plateforme | [Exigence] | `openspec/specs/client-intellij/spec.md:274-275` |
| Logique statique sans interface dans le panneau | [Existant vérifié] | `minos-intellij/src/main/java/com/minos/intellij/ui/MinosToolWindowPanel.java:293-318`, `:333-386` |
| Analyse de la chaîne saisie (entier, repli hors bornes ou non numérique) logée dans l'écran de réglages | [Existant vérifié] | `settings/MinosSettingsConfigurable.java:111-122` |
| Le champ requis d'une action avancée délègue à une classe sans plateforme, testée | [Existant vérifié] | `actions/MinosAdvancedAnalysisActions.java:219-221` ; `protocol/MinosJsonFieldsTest.java:30-36` |
| Nouvelle mesure de couverture après extraction | [Question] non bloquante | à mesurer sur le job Linux ; tâche 2.1 |

## Impact

- **Modules du reactor** : aucun. Hors reactor : `minos-intellij` (Gradle 9.9.0, Java 21).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : IntelliJ — aucun changement visible ; protocole CLI
  inchangé.
- **ADR** : aucun.
- **Gates à rejouer** : check exigé `IntelliJ plugin (gate)` (`./gradlew --no-daemon test
  jacocoTestCoverageVerification`), garde de dépendances du plugin (aucun `com.minos:*`),
  `scripts/quality/check-comment-language.py`, `scripts/docs/check-current-docs.py`.
- **Plateformes** : planchers jugés sous Linux ; le job Windows publie son rapport. **Aucun `pitest` du plugin sur le
  poste du mainteneur.**
- **Documentation durable et diagrammes** : `docs/user/intellij-plugin.md` (classes non couvertes, planchers),
  `docs/architecture/diagrams/capacites/client-intellij.md`.

## Questions ouvertes

- D1 : tests de plateforme ou non (recommandation : non). Si le propriétaire les retient, l'exigence existante est à
  modifier par un autre delta avant l'implémentation.
