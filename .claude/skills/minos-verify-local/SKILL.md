---
name: minos-verify-local
description: Vérifier localement un changement MINOS avant de le déclarer terminé — choisir et lancer les bons builds Maven/Gradle, les gates statiques et les auto-tests selon les fichiers touchés, en tenant compte des pièges du poste Windows (mvnw.cmd, PSModulePath, arguments -D, clean, JaCoCo m24). À utiliser avant toute PR, après un refactor, un déplacement de classe, ou quand l'utilisateur dit « vérifie », « lance les tests », « est-ce que ça passe ».
---

# Vérification locale MINOS

Objectif : donner une preuve **exécutée** (commande + résultat), pas « ça devrait passer ».

## 1. Choisir la portée selon les fichiers touchés

| Fichiers modifiés | Vérification minimale |
|---|---|
| `scripts/**/*.py`, `docs/**`, `openspec/**` | `python .claude/scripts/run_gates.py` (tous les gates et auto-tests, ~1 min) + `openspec validate --all --strict` |
| `minos-<module>/src/**` | `./mvnw -B -ntp -pl minos-<module> -am test` puis gates `--fast` ; si une classe a été déplacée ou renommée : skill `minos-literal-gates` |
| Rendu JSON, CLI, MCP, API | idem + `-pl minos-app -am verify` (goldens de caractérisation, `ShadedJar*IT`) ; le plugin IntelliJ entre dans la portée CI |
| `minos-runtime-local` (confinement) | `-pl minos-runtime-local -am verify` ; Windows : classes `Windows*` sans test sauté ; Linux : jobs `Verify` de la CI |
| `minos-intellij/**` | `gradlew test` dans `minos-intellij/` (voir règle plugin) + `check-module-boundaries.py` (A8) |
| `.github/workflows/**` | `yaml.safe_load` du fichier, `check-workflow-pins`, `check-single-execution`, `check-ci-wiring` |
| `*.ps1`, `*.cmd` | le test Python du script s'il existe, sinon exécution réelle sur un dossier de test ; `check-post-mne.py` |
| POM, dépendances | `-pl <module> -am verify`, `check-module-boundaries.py`, supply-chain (skill `minos-dependency-update`) |

## 2. Poste Windows (pièges mesurés)

- `mvnw.cmd` lancé depuis **PowerShell 7** échoue sans `C:\Windows\System32\WindowsPowerShell\v1.0` dans le `PATH` (`powershell` introuvable) ; et `PrivateLocalStorageWindowsAclTest` échoue sur `Set-Acl` si `PSModulePath` est celui de PowerShell 7 : le réinitialiser aux valeurs Machine + User.
- **Citer** les propriétés pointées : `"-Dminos.sandbox.tests.required=true"`, sinon `-Dx.y=z` est coupé aux points.
- `-pl <module>` exige `-am` (les modules amonts ne sont pas résolus autrement).
- `clean` à la racine efface `target/` racine (JAR ombré, 14 consommateurs) : éviter ou prévenir.
- Goldens et tests ne dépendent pas de l'hôte ; un test qui lit l'environnement pose lui-même ses variables.
- Scope JaCoCo `m24-polyglot-provider-platform` rouge sous Windows **hors régression** : comparer à `develop`. `python scripts/quality/check-jacoco.py --skip-scope m30-postgresql-pgvector` est la forme du job Windows.
- Tests PostgreSQL/pgvector : Testcontainers (Docker) ; sans Docker ils sont sautés localement, pas verts.

## 3. Rapporter

Pour chaque commande : portée, résultat (tests exécutés / échecs / sautés), gates rejoués. Dire explicitement ce qui n'a **pas** été exécuté (Linux, Docker, PostgreSQL, CI) et pourquoi. Un test sauté n'est pas un test réussi.
