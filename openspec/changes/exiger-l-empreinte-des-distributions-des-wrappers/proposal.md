# Proposal

## Why

Constat **AUD-DEP-09** de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouvert le 2026-11-25 au commit `75e10c68`). Les deux wrappers de build du dépôt portent la somme SHA-256 de leur distribution (`distributionSha256Sum`), mais aucun contrôle ne l'exige. Les scripts `mvnw` et `mvnw.cmd`, comme le wrapper Gradle, ne vérifient la somme que si la ligne est présente. Une montée de wrapper, par Dependabot ou à la main, qui retire ou casse la ligne n'est donc vue par aucune CI : la distribution est téléchargée puis exécutée sans vérification (fail-open). Deux montées de wrapper ont eu lieu pendant la période de l'audit (Maven 3.10.1 vers 3.10.2, Gradle 9.9.1 vers 9.9.2). Revérifié le 2026-11-25 : une copie du dépôt sans les deux lignes laisse verts `check-toolchain-baseline.py`, `check-tools-manifest.py`, `check-workflow-pins.py`, `check-image-pins.py` et `check-current-docs.py`.

## What Changes

- `scripts/quality/check-toolchain-baseline.py` gagne une règle 13. Chacun des deux fichiers de propriétés (`.mvn/wrapper/maven-wrapper.properties`, `minos-intellij/gradle/wrapper/gradle-wrapper.properties`) SHALL porter exactement une ligne `distributionSha256Sum=` suivie de 64 caractères hexadécimaux minuscules, et exactement une `distributionUrl` en `https` sous l'hôte attendu (`repo.maven.apache.org/maven2/` pour Maven, `services.gradle.org/distributions/` pour Gradle). Le wrapper Maven SHALL rester en `distributionType=only-script` : aucun `maven-wrapper.jar` n'est alors téléchargé, donc aucune somme `wrapperSha256Sum` n'est requise (design, D2).
- L'auto-test existant `scripts/quality/test_check_toolchain_baseline.py` reçoit les cas rouges : somme absente, vide, mal formée ou doublée, URL en `http`, URL hors de l'hôte attendu, `distributionType` autre que `only-script`. Sa fixture actuelle `distributionSha256Sum=00` devient une somme de 64 caractères.
- La docstring du gate, `docs/developer/TOOLCHAIN_POLICY.md` et l'étape 5 du skill `minos-dependency-update` disent que les deux sommes sont exigées et gardées par la règle 13.

Aucun code Java, aucun workflow, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. Aucune étape CI n'est ajoutée : le gate et son auto-test sont déjà lancés par le job `invariants` (`.github/workflows/pr-ci.yml:100`, `:103`).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `socle-d-execution` : une exigence ajoutée (chaque wrapper de build exige l'empreinte de sa distribution).

## Hors périmètre

- Le comportement des scripts `mvnw` et `mvnw.cmd` livrés par Apache (somme vérifiée seulement si elle est présente) : ils restent tels quels. Les modifier ferait diverger le dépôt du wrapper amont à chaque montée (design, D3).
- La variable d'exécution `MVNW_REPOURL`, qui change l'URL au lancement (`mvnw:141`, `mvnw.cmd:75-77`) : la somme reste vérifiée quelle que soit l'URL. Ce n'est pas un état versionné.
- `gradle-wrapper.jar` : il est validé dans les workflows du plugin par `gradle/actions/setup-gradle` contre les sommes publiées par Gradle (`.github/workflows/intellij-plugin.yml:72-73`). Aucun gate du dépôt ne le fige ; ce changement n'en ajoute pas (design, D2).
- L'image Docker et son archive Maven 3.9.16 : constat AUD-DEP-10, changement `verrouiller-l-empreinte-de-maven-dans-l-image`.
- La version de Maven écrite dans la documentation courante et les pièges des PR Dependabot : AUD-DEP-11 et AUD-DEP-12, changement `consigner-les-pieges-des-montees-de-version`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les deux fichiers de propriétés portent une somme de 64 caractères | [Existant vérifié] | `.mvn/wrapper/maven-wrapper.properties:4` ; `minos-intellij/gradle/wrapper/gradle-wrapper.properties:3` |
| Le wrapper Maven est en `only-script` (aucun jar téléchargé) | [Existant vérifié] | `.mvn/wrapper/maven-wrapper.properties:2` ; `.mvn/wrapper/` ne contient que le fichier de propriétés |
| `mvnw` ne vérifie la somme que si elle est présente | [Existant vérifié] | `mvnw:225-247` (`if [ -n "${distributionSha256Sum-}" ]`) |
| `mvnw.cmd` se comporte de même sous Windows | [Existant vérifié] | `mvnw.cmd:137-148` (`if ($distributionSha256Sum) {`) |
| Le gate de baseline ne lit le wrapper Gradle que pour `distributionUrl` et ne lit pas le wrapper Maven | [Existant vérifié] | `scripts/quality/check-toolchain-baseline.py:71`, `:76`, `:117-125` |
| `distributionSha256Sum` n'apparaît dans `scripts/` que dans une fixture | [Existant vérifié] | `scripts/quality/test_check_toolchain_baseline.py:22` (`distributionSha256Sum=00`) |
| Copie sans les deux lignes, ou avec une URL Maven en `http` : cinq gates verts | [Existant vérifié] | mutations rejouées le 2026-11-25 (design, « Mutations rejouées ») |
| Wrapper Gradle : somme vérifiée seulement si présente | [Existant vérifié] partiellement | documentation Gradle (vérification de la distribution) ; non exécuté sur le poste |
| Règle 13 dans `check-toolchain-baseline.py` | [Proposition] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. GEN-0077 (« Socle d'exécution Java 21 et Maven ») gagne une ligne dans sa mise en œuvre (« Compliance and Enforcement ») qui nomme la règle 13, sans changer sa décision : tâche 2.3, après la fusion.
- **Workflow** : aucun changement.
- **Gates à rejouer** : `scripts/quality/check-toolchain-baseline.py`, `scripts/quality/test_check_toolchain_baseline.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-posix-script-eol.py` (lit le fichier de propriétés Maven), `scripts/ci/test_docker_gate.py` (cite ce fichier), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux. Le gate est un script Python de la bibliothèque standard ; les deux scripts du wrapper Maven (`mvnw`, `mvnw.cmd`) ont la même règle d'activation de la vérification, qu'une ligne exigée par le gate rend effective sur les deux systèmes.
- **Documentation durable** : `docs/developer/TOOLCHAIN_POLICY.md`, `.claude/skills/minos-dependency-update/SKILL.md`, `docs/architecture/diagrams/capacites/socle-d-execution.md` (à l'archivage), ADR GEN-0077 (mise en œuvre).

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.
