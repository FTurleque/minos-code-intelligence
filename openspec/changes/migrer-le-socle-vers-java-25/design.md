# Design

## Context

Sprint 3 de l'audit du 10 octobre 2026 : **AUD-DEP-01**. HEAD analysé : `2d1cc214` (`develop`). Le prérequis AUD-TST-03 (tests de confinement réellement exécutés et exigés) est fermé (`docs/audit/2026-10-10/SUIVI.md`, sprint 1) : la qualification du nouveau JDK sous bubblewrap, cgroup v2 et AppContainer est donc mesurable.

## État vérifié au HEAD

### AUD-DEP-01 — confirmé

- `pom.xml:44` : `<maven.compiler.release>24` ; `pom.xml:193` : enforcer `[24,25)` (l'audit citait `:191`).
- `docker/Dockerfile.mcp:4` : `FROM eclipse-temurin:24.0.2_12-jre@sha256:…` ; `docker/Dockerfile.mcp.release:6` : `FROM eclipse-temurin:24.0.2_12-jdk@sha256:…`.
- `.github/dependabot.yml:68-71` : `ignore` des montées `semver-major` d'`eclipse-temurin`, commentaire qui cite « (24) ».
- `scripts/release/build-windows-distribution.ps1:156`, `:268-280` : `jlink`/`jpackage` embarquent le runtime du JDK posé par `setup-java` (`.github/workflows/release-windows.yml:114-119`).
- **Aucun gate ne garde la baseline Java** : `scripts/quality/check-toolchain-baseline.py` ne lit que Gradle (wrapper, politique, `AGENTS.md`, `gradle-version:`), `scripts/docs/product-facts.py` ne cite pas la version de Java.
- `docs/TOOLCHAIN_POLICY.md` : procédure de changement en sept points (`:30-38`), critère « the current runtime is no longer appropriate » (`:52`) ; aucune mention de fin de support ; `:61` omet l'écosystème Docker de Dependabot.
- `docs/architecture/risks/register.md` : aucune ligne sur le runtime Java.

**Non revérifié pendant cette analyse** (réseau non utilisé) : la date exacte de fin de mises à jour publiques du JDK 24, le dernier correctif et le digest d'`eclipse-temurin:25` (JRE et JDK), la compatibilité Java 25 de JaCoCo, SpotBugs, PIT, ArchUnit, maven-shade-plugin et scip-java aux versions du POM. La tâche 1.1 les mesure avant toute modification.

### Écarts avec l'action proposée par l'audit

1. **« `setup-java` 25 dans les 13 workflows » est faux.** Décompte au HEAD : 13 fichiers de workflow, 12 avec `setup-java` (`secret-scan.yml` n'en a pas), 20 étapes ; **15** sont en `'24'` dans 10 workflows (`code-audit.yml:57,137,191`, `docker-release-validation.yml:65`, `docker-upgrade-qualification.yml:58`, `historical-qualification.yml:44,96,136`, `m0-java-ci.yml:29`, `pr-ci.yml:245,358`, `release-promotion-gate.yml:75`, `release-windows.yml:118`, `windows-in-place-upgrade.yml:64`, `windows-installer.yml:75`) ; **5** sont en `'21'` (plugin IntelliJ) et doivent le rester.
2. **L'action est plus étroite que le défaut.** Elle cite le POM, les workflows, les Dockerfiles et `verify-run-configurations.ps1:112`. S'y ajoutent des sites qui **refusent** un autre JDK ou l'énoncent :
   - scripts à garde dure : `scripts/release/build-windows-distribution.ps1:70` (`jdeps --multi-release 24`), `:153`, `:160`, `:180-181` ; `build-local-windows-candidate.ps1:37` ; `publish-windows-release.ps1:149` ; `scripts/windows/MinosWindows.ps1:92,98` ; `scripts/m24/check-windows-prerequisites.ps1:128-137` ; `scripts/m24/run-final.sh:50`, `scripts/m25/run-final.sh:47`, `scripts/m26/run-final.ps1:78` et `.sh:45`, `scripts/m27/run-final.ps1:68` et `.sh:33` ; `scripts/ci/qualify-docker-upgrade.ps1:244` (POM de fixture) ;
   - messages : `docker/scripts/prod-mcp.ps1:231,280`, `scripts/intellij/run-minos.ps1:30`, `scripts/release/qualify-seeding-container.ps1:7` ;
   - code produit : `minos-provider-scip/src/main/java/com/minos/adapter/scip/ScipIndexerCatalog.java:336` (`"Java 24 JDK"`), figé dans `minos-app/src/test/resources/characterization/cli-json.golden:96` ;
   - gates et tests littéraux : `scripts/remediation/check-audit-remediation-v2.py:17,129-130` (`eclipse-temurin:24\.`), `docker/scripts/verify-run-configurations.ps1:112-115` (même regex, lancé seulement par une configuration `.run/`), `scripts/quality/test_check_image_pins.py:19,271-287` (le cas « major 25 » **doit rester rouge** aujourd'hui), `scripts/docs/check-current-docs.py:211` (« Set up Java 24 for MINOS release ») ;
   - documentation courante : `docs/TOOLCHAIN_POLICY.md:9,24`, `AGENTS.md`, `openspec/config.yaml:12`, `docs/STATUS.md:138`, `docs/ROADMAP.md:131`, `README.md:146,171`, `docs/developer/README.md:126`, `quality-gates.md:13-14`, `testing.md:22`, `docs/user/installation.md:14,93`, `docker-runtime.md:57`, `troubleshooting.md:30`, `mcp.md:55`, `intellij-plugin.md:14`, `docs/architecture/README.md:4`, `SYNTHESE.md:14`, `arc42/02-contraintes.md:12`, `arc42/04-strategie-solution.md:54`, `docs/quality/code-audit.md:22`, `.claude/skills/minos-incident-triage/SKILL.md:11`.
3. **La règle `ignore` de Dependabot se garde.** L'audit la présente comme un défaut. Elle applique la politique (« un changement de baseline est une décision ») : sans elle, Dependabot proposerait Java 26 dès sa sortie. Seul son commentaire change ; ce qui manquait est le **gate** et le critère de révision.
4. **L'ADR à remplacer n'est pas le seul touché** : 0005 est remplacé ; 0004, 0021 et 0027 citent Java 24.

## Mesures préalables (tâche 1.1, 2026-10-13)

| Mesure | Valeur | Source |
|---|---|---|
| Dernier correctif de Temurin 25 | 25.0.4.1+1 (publié en août 2026) | API Adoptium `v3/assets/latest/25/hotspot` |
| Fin de correctifs de Temurin 25 (LTS) | septembre 2031 au moins | `adoptium.net/support`, recoupé par `endoflife.date` (2031-09-30) |
| Fin de correctifs du JDK 24 | dernière mise à jour 24.0.2 de juillet 2025, fin de vie en septembre 2025 | mêmes sources |
| `eclipse-temurin:25.0.4.1_1-jre` | `sha256:fcd7fd7b387f94bb2ac461478a7436ad8e349924c374ea8313919624dceae636` | `docker buildx imagetools inspect` |
| `eclipse-temurin:25.0.4.1_1-jdk` | `sha256:8c0a84ea11c8f6ed52600fc19f1040121f2a162998e9f50a5faebbbad9172dcc` | idem |

Lecture du bytecode 25 par l'outillage : mesurée par exécution et non par lecture de notes de version. Le `verify` du reactor sous Temurin 25.0.4.1 exerce JaCoCo 0.8.15, ArchUnit 1.5.1 et maven-shade-plugin sur des classes de version 69 (résultat en tâche 4.1) ; SpotBugs 4.10.4 et PIT 1.30.0 ne tournent que dans `code-audit.yml`, à lancer à la PR (tâche 4.4) ; scip-java 0.13.1 est mesuré en tâche 4.2. Un outil qui ne lit pas ce bytecode se monte dans ce changement, il ne se désactive pas (D6).

## Goals / Non-Goals

**Goals** : les livrables tournent sur un JDK LTS qui reçoit des correctifs ; la version de Java est énoncée une fois et contrôlée partout ; la qualification est rejouée sur Linux, Windows, Docker et l'installateur ; la politique dit quand réviser.

**Non-Goals** : changer la baseline du plugin ; utiliser des API de Java 25 ; suivre automatiquement les versions non-LTS.

## Decisions

### D1. Migrer vers Java 25 LTS maintenant — **décidé : option A (propriétaire, 2026-10-13)**

Recommandation : **migrer** (option A). Java 25 est la LTS courante ; la migration est un changement de constante plus une qualification, pas une réécriture.

Option B (reporter) : inscrire le risque au registre avec une échéance et **quand même** livrer le gate de baseline (D2) et la mise à jour de la politique (D5), qui ne dépendent pas de la version. Les sections 2 à 5 des tâches sont alors différées, les sections 1 et 6 restent.

Alternative écartée : élargir l'enforcer à `[24,26)`. La politique l'interdit (« ne pas élargir une plage ») et un livrable construit en 24 resterait possible.

### D2. Une source de vérité et un gate, comme pour Gradle

`maven.compiler.release` du POM racine est la référence. `check-toolchain-baseline.py` est étendu : il lit cette valeur et refuse (a) une borne d'enforcer qui n'est pas `[N,N+1)`, (b) un `java-version:` de workflow différent de `N`, sauf les étapes du plugin, reconnues par une liste fermée de workflows et de jobs en Java 21 (toute autre valeur que `N` ou 21 est refusée), (c) un `FROM eclipse-temurin:` dont le majeur n'est pas `N`, (d) une ligne de `docs/TOOLCHAIN_POLICY.md`, d'`AGENTS.md` ou d'`openspec/config.yaml` qui énonce une autre version. Les scripts à garde dure lisent la version attendue d'un seul endroit quand c'est possible (D4).

Alternative écartée : un nouveau script. `check-toolchain-baseline.py` détient déjà le modèle (source, sites, message) et son auto-test est câblé dans `invariants`.

### D3. Le plugin IntelliJ reste en Java 21

Client externe du protocole CLI (ADR 0042), sa baseline est liée à IntelliJ Platform 2026.1. Les cinq `java-version: '21'` sont conservés et **nommés** dans la liste fermée du gate.

### D4. Les scripts à garde de version

Deux familles : les scripts de release et d'exploitation vivants (ils passent à 25, et ceux qui partagent `scripts/windows/MinosWindows.ps1` lisent sa constante) ; les scripts de jalons `scripts/m24`…`m27` encore exécutés par `historical-qualification.yml` (ils passent à 25 avec le workflow, sinon ce rejeu devient rouge). La fixture `fixtures/java/java-24-smoke` est conservée telle quelle si elle prouve l'indexation d'un **projet** en Java 24 (cas d'un utilisateur), ce que la tâche 3.3 établit avant de la toucher.

### D5. La politique nomme la fin de support et un critère daté

`docs/TOOLCHAIN_POLICY.md` reçoit, pour chaque baseline, la date de fin de correctifs connue et la règle « ouvrir le changement de baseline au plus tard N mois avant ». Le texte sur Dependabot est corrigé (écosystèmes Docker). Le prérequis public du catalogue (`"Java 24 JDK"`) dérive de la même constante que l'enforcer, ou reste un littéral contrôlé par le gate, selon ce que permet le module (décision d'implémentation, tâche 3.2).

### D6. Qualification

Ordre : reactor complet Linux et Windows ; PostgreSQL requis sous Linux ; JaCoCo ciblé ; confinement en mode requis (`-Dminos.sandbox.tests.required=true`) ; SpotBugs et PIT (`code-audit.yml`) ; image Docker (`docker-release-validation.yml`) ; mise à niveau Docker A→B d'une image Java 24 vers une image Java 25 (`docker-upgrade-qualification.yml`) ; installateur Windows et mise à jour en place (`windows-installer.yml`, `windows-in-place-upgrade.yml`). Un outil d'analyse incompatible avec le bytecode 25 se **monte** dans le même changement (avec la raison écrite) ; il ne se désactive pas.

## Direction des dépendances

Aucun module, aucune dépendance ajoutés (ADR 0022 inchangé). Le changement est transversal au build.

## Qualification des capacités

- Exécution du produit sur Java 25, Linux et Windows : **non qualifiée** tant que la section 4 des tâches n'est pas faite ; **qualifiée** ensuite.
- Indexation de projets Java par scip-java sous un JDK 25 hôte : **à mesurer** (tâche 4.2) ; déclarée partielle si une version de bytecode n'est pas lue.
- Plugin IntelliJ : inchangé, qualifié en Java 21.

## Windows et Linux

Le runtime de l'installateur est produit par `jlink` : la liste de modules de `jdeps --multi-release` doit être recalculée et comparée (un module retiré ou renommé entre 24 et 25 casserait l'image d'exécution). Sous Linux, l'image `-jre` et l'image `-jdk` changent de base : `check-image-pins.py` exige une seule source par image et un digest.

## Risks / Trade-offs

- Un outil du build (JaCoCo, SpotBugs, PIT, shade) peut ne pas lire le bytecode 25 : la tâche 1.1 le mesure avant le POM.
- Le golden `cli-json.golden` change d'une valeur : régénération explicite, relue.
- Les utilisateurs d'une installation par jar doivent disposer d'un JDK 25 : à dire dans la documentation d'installation et les notes de version ; l'installateur Windows et Docker embarquent leur runtime et ne sont pas concernés.
- Report (option B) : le risque reste ouvert, mais devient visible et daté.
