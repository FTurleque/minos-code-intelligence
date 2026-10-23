# Tasks

Ordre : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Gates littéraux à rejouer pour la fabrique : `scripts/remediation/check-p0-p2.py` (chaînes de la copie de travail, plancher JaCoCo `ScipJavaProcessPlanFactory` 0,35 / 0,15), `scripts/remediation/check-post-mne.py`, `scripts/architecture/check-private-io.py`, `scripts/architecture/check-module-boundaries.py`.

## 1. Classpath vérifié rangé et revérifié (AUD-SEC-02, résidu D2)

- [x] 1.1 [AUD-SEC-02] Rouge : les tests de plan Linux de `ScipJavaProcessPlanFactoryToolchainTest` attendent `java -jar <lanceur>` et des refus (jar altéré, lanceur altéré, classpath absent) ; les tests Windows attendent un refus sans classpath et sur jar altéré ; `ManagedScipProviderRuntimeManagerToolchainTest` attend « non installé » avec Coursier seul. **Preuve du rouge au HEAD : le plan Linux est `coursier launch …` (test `onLinuxWithoutAStandaloneLauncherCoursierLaunchesScipJavaByItsMainClass`, vert sur `develop`) et le plan Windows sans liste réussit (`onWindowsAClasspathFileThatIsNotThereIsNotPassedToTheRunner`, vert sur `develop`) ; ces deux tests sont remplacés.**
- [x] 1.2 [AUD-SEC-02] `ScipJavaClasspath` (liste, lanceur déterministe, vérification), fabrique à constructeur unique et vérificateur obligatoire, `ClasspathLock implements ScipJavaClasspath.Verifier`, installation par copie vérifiée et état « non installé » sans classpath. Tests en JVM : `ScipJavaClasspathTest` (dont l'exécution réelle d'une classe principale par `java -jar` du lanceur), `ScipJavaClasspathInstallationTest`.
- [x] 1.3 [AUD-SEC-02] Preuve : tests ciblés du module sous Windows (poste, Temurin 25) et sous Linux (conteneur `eclipse-temurin:25-jdk-noble`, utilisateur non root) ; `./mvnw -B -ntp -pl minos-provider-scip -am verify` sous Windows ; gates ci-dessus et `python .claude/scripts/run_gates.py --fast`.
- [x] 1.4 **(manuelle)** [AUD-SEC-02] À la PR : `Verify (ubuntu-24.04)` et `Verify (windows-2022)` verts ; scope JaCoCo `m24-polyglot-provider-platform` et plancher de `ScipJavaProcessPlanFactory` relevés sur Linux ; noter les run ids dans `SUIVI.md`. — PR #449 : run 38572983303, `m24-polyglot-provider-platform` PASS 0,853 / 0,667 (Linux) et 0,870 / 0,657 (Windows) ; fusionnée le 2026-10-14.
- [ ] 1.5 **(manuelle)** [AUD-SEC-02] Installation et indexation réelles sous Linux hors Docker (WSL 2, comme la preuve de la tâche 4.1 du changement archivé) : `minos tools install scip-java`, puis indexation d'un projet Maven ; relever que le processus lancé est `java -jar …/scip-java-launcher.jar` et qu'un jar copié modifié refuse l'indexation.

## 2. Clôture

- [x] 2.1 `docs/architecture/adr/archive/0040-distribution-auto-portante-indexeurs-embarques.md` (limite (1) levée), `docs/user/polyglot-providers.md`, `docs/project/governance/STATUS.md` ; rejouer `python scripts/docs/check-current-docs.py` et `python scripts/docs/product-facts.py --check`.
- [x] 2.2 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (AUD-SEC-02 : résidu D2 traité, preuves).
- [ ] 2.3 `openspec validate --all --strict` ; archiver après 1.4 et 1.5 ; docs à relire avant archivage : `docs/project/governance/STATUS.md`, `docs/user/polyglot-providers.md`, ADR 0040.
