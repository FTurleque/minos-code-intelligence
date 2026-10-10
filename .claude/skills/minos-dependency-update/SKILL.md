---
name: minos-dependency-update
description: Traiter une montée de dépendance ou d'image MINOS (PR Dependabot ou manuelle) — Maven, Gradle du plugin, GitHub Actions, images Docker, Testcontainers, SLF4J — sans changer la baseline Java/Maven/Gradle/IntelliJ, avec les vérifications supply-chain et de convergence. À utiliser quand une PR build(deps) arrive, quand l'utilisateur demande « mets à jour X », ou pour évaluer une alerte OSV/Dependency-Check.
---

# Montée de dépendance

Dependabot couvre Maven (`/`), le build Gradle du plugin (`/minos-intellij`), GitHub Actions et les images Docker (deux écosystèmes : `docker` et `docker-compose`). Il ne doit **jamais** changer en silence la baseline : Java 24, Maven `[3.9,4.0)`, Java 21 / Gradle 9.6.1 / IntelliJ Platform 2026.1 pour le plugin (`docs/TOOLCHAIN_POLICY.md`). Une montée de baseline est une décision, à qualifier sur Linux et Windows.

## Tri d'une PR de dépendance
1. Lire le diff : une seule dépendance ? un groupe ? le tag **et** le digest d'une image bougent-ils ensemble ?
2. **Maven** : la version est gérée dans `dependencyManagement` ou une propriété de `pom.xml` (`testcontainers` en BOM 2.0.5, `slf4j.version`, `junit.version`, `jacoco.version`, `jgit.version`…) ; une version déclarée en dur dans un sous-module est une divergence à corriger. Lire les notes de version pour un changement de comportement (JGit, SDK MCP, pilote PostgreSQL).
3. **GitHub Actions** : épinglées par SHA de commit avec la version en commentaire ; `python scripts/quality/check-workflow-pins.py` doit passer.
4. **Images** : `image:tag@sha256:digest`, une seule source par image gérée ; `python scripts/quality/check-image-pins.py`. pgvector : seuls les tags de même suffixe (`-pg17`).
5. **Gradle wrapper** : la somme SHA-256 de la distribution est épinglée dans `gradle-wrapper.properties` ; `gradlew.bat` est renormalisé (LF dans l'index, CRLF à l'extraction).
6. Convergence : `./mvnw -B -ntp dependency:tree -pl <module> -am` ; une seule version de `slf4j-api` dans le JAR ombré (2.0.x).

## Vérifications
`./mvnw -B -ntp -pl <modules> -am verify` ; gates `--fast` ; `ShadedJar*IT` si le JAR ombré change ; plugin : `IntelliJ plugin (gate)` ; sécurité : check OSV de la PR (`osv-scan`) et, à la demande, Dependency-Check (`code-audit.yml`, manuel).

## Décider
- Montée de correctif/sécurité sans changement d'API : fusionner après CI verte.
- Majeure ou comportement modifié : l'écrire dans la PR (ce qui change, ce qui a été testé), demander l'accord.
- Alerte sans correctif amont : consigner (justification, échéance) plutôt que supprimer l'alerte.
- Ne pas élargir une plage de versions parce qu'une plus récente existe.

Fusion : skill `minos-pr-flow` (une PR à la fois, branche à jour).
