# Design

## Context

[Décision] Le propriétaire aligne la baseline Java du produit sur 21 (2026-11-06) : MORPHEUS embarque un runtime Java 21 et lance MINOS comme pair MCP en STDIO. `GEN-0067` imposait Java 25 ; `GEN-0077` le remplace.

## État vérifié au HEAD

[Existant vérifié] Relevés sur la branche `build/aligner-java-21`, issue de `develop` (cc38f352).

- La seule construction postérieure à Java 21 est la variable sans nom `_` (JEP 456, finale en Java 22) : 62 occurrences dans 32 fichiers (`catch (X _)`, `try (var _ = …)`), remplacées par `ignored`. Aucune API postérieure à 21 n'est utilisée : le reactor compile avec `--release 21`.
- Sites qui énoncent Java 25 et que `check-toolchain-baseline.py` garde : `pom.xml:43` et `:214`, 16 étapes `java-version:` de 14 workflows, `docker/Dockerfile.mcp:12,22` et `docker/Dockerfile.mcp.release:26,37,69`, `docs/developer/TOOLCHAIN_POLICY.md:9-10`, `AGENTS.md:15`, `openspec/config.yaml:12`.
- Sites que le gate ne garde pas : scripts Windows et de release (`scripts/windows/MinosWindows.ps1`, `scripts/release/build-windows-distribution.ps1`, `publish-windows-release.ps1`, `build-local-windows-candidate.ps1`), scripts de jalons `scripts/m24` à `m27`, `docker/scripts/verify-run-configurations.ps1`, `docker/scripts/prod-mcp.ps1`, `scripts/remediation/check-audit-remediation-v2.py`, `scripts/docs/check-current-docs.py`, `ScipIndexerCatalog.java:332`, le golden `cli-json.golden`, la documentation courante.
- Qualifications de montée A → B : `release-promotion-gate.yml` et `docker-upgrade-qualification.yml` construisent le candidat A avec le JDK que nomme son POM (`qualify-docker-upgrade.ps1:56-71`, variable `JAVA_HOME_<majeur>_X64`) ; `main` est en Java 25.
- Support : Temurin 21 « at least Dec 2029 » (page Adoptium lue le 2026-11-06).

## Écarts

- [Existant vérifié] `PLUGIN_JAVA_STEPS` de `check-toolchain-baseline.py` n'a de sens que si le produit n'est pas en Java 21 ; le code du gate (`stated == PLUGIN_JAVA != version`) l'ignore déjà dans ce cas.
- [Existant vérifié] `scripts/ci/qualify-docker-upgrade.ps1:339` écrit un POM de fixture en Java 24 : une donnée de test, sans lien avec la baseline.
- [Existant vérifié] Le prérequis « Java 25 JDK » de `scip-java` décrit le JDK qui exécute l'indexation Windows qualifiée en M17/M22 ; la CI n'exécute aucun provider, donc rien ne le requalifie sous Java 21.

## Décisions

### D1 — Une seule PR, un seul ADR de remplacement
[Décision] La baseline s'écrit dans la même PR que chaque site que le gate nomme (règle de `GEN-0077`). `GEN-0067` part dans `docs/architecture/adr/archive/` ; la règle Archgate compagnon suit sous le nom `GEN-0077-…rules.ts`.

### D2 — Variable sans nom remplacée par `ignored`
[Décision] Le nom `ignored` reprend la convention Java des exceptions volontairement ignorées ; aucun bloc `catch` ne change de comportement. Le superviseur de code signale ces blocs vides : c'est un effet du renommage, pas une régression.

### D3 — Qualifications de montée : Java 25 pour le candidat A
[Décision] Tant que `main` est en Java 25, les deux workflows installent Java 25 (`PREVIOUS_JAVA = "25"` dans le gate) puis Java 21, dans cet ordre, pour que la dernière sélection soit celle du candidat B. La liste `PREVIOUS_JAVA_STEPS` se vide quand `main` est construit en Java 21.

### D4 — Images Docker : tag et digest relevés, pas devinés
[Décision] `eclipse-temurin:21.0.12.1_1-jdk-noble` et `-jre-noble` : digests d'index relevés par `docker buildx imagetools inspect` le 2026-11-06.

### D5 — Prérequis `scip-java` aligné sur la baseline, sans requalification
[Décision] La chaîne devient « Java 21 JDK » parce que le runtime de MINOS est le seul JDK qu'il fournit ; la qualification d'une indexation réelle sous Java 21 reste hors périmètre et est dite telle (proposal, ADR « Limites »).

## Plateformes

- Linux et Windows : la baseline vaut sur les deux (`Verify (ubuntu-24.04)`, `Verify (windows-2022)`) ; les scripts Windows cherchent `jdk-21*` / `temurin-21*` sous `%USERPROFILE%\.jdks`.
- Direction des dépendances (ARCH-0068) : inchangée, aucune dépendance ajoutée.

## Vérification

- Locale : `./mvnw -B -ntp verify` sous JDK 21 (compilation, tests, JaCoCo, Enforcer) ; `python .claude/scripts/run_gates.py` ; `archgate check` ; `openspec validate --all --strict`.
- Jar sous Java 21 réel : `initialize`, `tools/list`, `minos_index_status` avec un `MINOS_HOME` vide.
- CI (à la PR) : `Verify` sous Linux et Windows ; images, installateur et promotion hors de ce changement.

## Traçabilité

| Exigence ou décision | Preuve |
|---|---|
| Compilation pour Java 21, refus d'un autre JDK | `pom.xml` (`maven.compiler.release`, Enforcer) ; `check-toolchain-baseline.py` ; règles Archgate de `GEN-0077` |
| Étapes de workflow en Java 21 | `check-toolchain-baseline.py`, `test_check_toolchain_baseline.py` |
| Images en Java 21 | `check-toolchain-baseline.py` (règle 6), `check-image-pins.py`, `check-audit-remediation-v2.py` |
| Exécution du jar sous Java 21 | sonde MCP STDIO (tasks 4.2) |
| Runtime embarqué en Java 21 | `docker-release-validation` (run 42215327960), `windows-installer` (run 42215328008), `windows-in-place-upgrade` (run 42215328011) sur la PR #665 ; `minos doctor` du candidat : `Java runtime: 21.0.12.1+1-LTS` |
