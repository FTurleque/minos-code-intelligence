# Proposal

## Why

**AUD-DEP-01** (sévérité élevée, sprint 3 de l'audit du 10 octobre 2026) : tous les livrables de MINOS (jar ombré, deux images Docker, runtime embarqué par l'installateur Windows) tournent sur le JDK **24.0.2**, version non-LTS dont la dernière mise à jour publique date de juillet 2025. Le JDK 25 LTS est disponible depuis septembre 2025. L'outillage **interdit** d'en sortir : Maven Enforcer refuse tout JDK hors `[24,25)` (`pom.xml:193`), Dependabot ignore les montées majeures d'`eclipse-temurin` (`.github/dependabot.yml:70-71`) et une douzaine de scripts refusent un `java -version` autre que 24.

Le serveur MCP, le pilote PostgreSQL (TLS) et le runtime de l'installateur ne reçoivent donc aucun correctif de sécurité du JDK. `docs/TOOLCHAIN_POLICY.md` ne dit rien de cette fin de support, et **aucun gate ne garde la baseline Java** (seule celle de Gradle l'est, par `check-toolchain-baseline.py`) : les sites se désaligneront à la prochaine montée comme ils l'ont fait pour Gradle.

## What Changes

- **Baseline Java 25 LTS** pour le noyau, la CLI, le MCP, le stockage et les providers : `maven.compiler.release`, borne de l'enforcer, `setup-java` des workflows du produit, images `eclipse-temurin` (JRE et JDK), scripts à garde de version, prérequis public affiché par le catalogue des indexeurs. Le plugin IntelliJ **reste en Java 21**.
- **Un gate de baseline Java** (extension de `scripts/quality/check-toolchain-baseline.py`) : une source de vérité (`maven.compiler.release` du POM racine) et le refus de tout site qui énonce une autre version, comme pour Gradle.
- **Qualification** selon la procédure en sept points de `docs/TOOLCHAIN_POLICY.md` (reactor Linux et Windows, PostgreSQL requis, JaCoCo ciblé, confinement, installateur Windows, images Docker, promotion `develop` avant `main`), plus la mise à niveau Docker A→B d'une image 24 vers une image 25.
- **Documentation** : politique de toolchain (fin de support nommée, critère de révision daté), documentation utilisateur et développeur, contexte OpenSpec et consignes d'agents.
- **Registre des risques** : une ligne « runtime Java hors support », fermée par la migration ou datée si le propriétaire reporte (décision D1).

## Capabilities

### New Capabilities

- `socle-d-execution` : aucune capacité existante ne porte la version des runtimes sur lesquels MINOS se construit et s'exécute. Elle reçoit ici la baseline Java (support, source de vérité unique, gate) ; le changement `aligner-node-et-l-inventaire-de-l-image-docker` y ajoute Node et l'inventaire de provenance.

### Modified Capabilities

(aucune)

## Hors périmètre

- Le plugin `minos-intellij` (Java 21, Gradle, IntelliJ Platform 2026.1) : baseline indépendante, inchangée.
- L'adoption de fonctionnalités de langage ou d'API propres à Java 25 : la migration est à comportement constant.
- Maven (3.9.x par le wrapper) : inchangé.
- Node, les toolchains de l'image et l'inventaire de provenance (AUD-DEP-02, AUD-DEP-10), la veille des outils embarqués (AUD-DEP-03), la séparation de l'image de requête (AUD-DEP-15), les notices (AUD-DEP-16) : autres changements du sprint.
- Les documents historiques (`docs/history/`, `docs/audit/archive/`, changements archivés) et les scripts de jalons sous `scripts/history/` : ils gardent « Java 24 ».
- La version du JDK qu'un provider utilise pour **analyser** un projet (scip-java sur le code de l'utilisateur) : elle dépend du projet indexé.

## Impact

- **Modules du reactor** : POM racine ; `minos-provider-scip` (`ScipIndexerCatalog.java:336`, prérequis public « Java 24 JDK ») ; `minos-app` (golden `characterization/cli-json.golden:96`). Aucun autre code Java attendu ; tout échec de compilation ou de test sous 25 est un écart à consigner.
- **Surfaces publiques** : le libellé de prérequis de `minos providers` (CLI, JSON) change de valeur, pas de forme ; MCP, API Java, IntelliJ, NEXUS inchangés.
- **ADR** : un nouvel ADR (prochain numéro libre de l'index, 0059 au HEAD analysé) **remplace l'ADR 0005** « Align MINOS on Java 24 » ; les ADR 0004, 0021 et 0027, qui citent Java 24, reçoivent une note de mise en œuvre. Planifié en tâche, non rédigé ici.
- **Workflows** : 15 étapes `java-version: '24'` dans 10 workflows ; `.github/dependabot.yml` (commentaire de la règle).
- **Scripts** : `scripts/release/build-windows-distribution.ps1`, `build-local-windows-candidate.ps1`, `publish-windows-release.ps1`, `scripts/windows/MinosWindows.ps1`, `scripts/ci/qualify-docker-upgrade.ps1`, `docker/scripts/verify-run-configurations.ps1`, `docker/scripts/prod-mcp.ps1`, `scripts/intellij/run-minos.ps1`, `scripts/release/qualify-seeding-container.ps1`, scripts de jalons vivants `scripts/m24`…`m27`.
- **Gates à rejouer** : `scripts/quality/check-toolchain-baseline.py` (étendu) et son auto-test, `scripts/remediation/check-audit-remediation-v2.py` (regex `eclipse-temurin:24\.`), `scripts/quality/test_check_image_pins.py` (le cas « major 25 » y est aujourd'hui **refusé**), `scripts/quality/check-image-pins.py`, `scripts/docs/check-current-docs.py` (littéral « Set up Java 24 for MINOS release »), `scripts/docs/product-facts.py --check`, `scripts/quality/check-workflow-pins.py`, `scripts/quality/check-single-execution.py`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-jacoco.py`, goldens de caractérisation.
- **Plateformes** : Ubuntu 24.04 et Windows Server 2022, images Docker Linux, installateur Windows (`jlink`/`jpackage`).
