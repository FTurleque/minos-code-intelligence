# Proposal

## Why

**AUD-DEP-11** (faible, sprint 5 de l'audit du 10 octobre 2026). Le build Maven n'est pas reproductible et ne fige pas tous ses plugins :

- aucune propriété `project.build.outputTimestamp` dans les POM : deux builds du même commit produisent des jars de SHA-256 différents, dont le jar ombré qui sert de base à l'image Docker et à l'installateur Windows ;
- l'enforcer (`pom.xml:183-198`) ne porte que `requireJavaVersion` et `requireMavenVersion` ; pas de `requirePluginVersions` ;
- les plugins du cycle par défaut `maven-clean-plugin`, `maven-resources-plugin`, `maven-install-plugin`, `maven-deploy-plugin` et `maven-site-plugin` ne sont déclarés dans aucun des 15 POM : leur version est celle que la distribution Maven utilisée choisit par défaut.

Conséquence : le manifeste de release et le SBOM ne peuvent pas être revérifiés par une reconstruction indépendante, et un Maven local résout d'autres plugins que le wrapper.

## What Changes

- **Horodatage de sortie fixé** : les archives produites par le reactor portent une date dérivée du commit, pas de l'heure du build.
- **Tous les plugins sont épinglés** et l'enforcer refuse un plugin sans version.
- **Une preuve par double construction** : deux builds du même commit donnent le même SHA-256 pour le jar ombré, et la preuve est rejouée en CI.
- **Les scripts qui construisent un livrable transmettent la date du commit** (distribution Windows, qualification de l'image Docker).

## Capabilities

### New Capabilities

(aucune : `chaine-de-livraison` est créée par `conserver-les-notices-tierces`)

### Modified Capabilities

- `chaine-de-livraison` : deux exigences ajoutées (le jar distribué est reproductible à partir du commit ; tout plugin de build a une version déclarée).

## Hors périmètre

- La reproductibilité du **zip** de distribution, de l'installateur Inno Setup, de l'image `jpackage` et des images Docker : ils embarquent des horodatages et des métadonnées propres à leurs outils ; seuls les **JAR** du reactor sont visés.
- Le plugin IntelliJ (Gradle, hors reactor).
- Monter des versions de plugins Maven : les versions épinglées sont celles **actuellement résolues** par le wrapper.
- La borne Java de l'enforcer (AUD-DEP-01, changement `migrer-le-socle-vers-java-25`, qui modifie le même bloc du POM : voir l'ordre dans `SUIVI.md`).
- La signature et l'attestation (AUD-DEP-06).

## Impact

- **Modules du reactor** : POM racine (`properties`, `pluginManagement`, enforcer) ; `minos-app` (shade, `minos-app/pom.xml:57-66`). Aucun code Java.
- **Fichiers** : `pom.xml`, `minos-app/pom.xml`, `scripts/release/build-windows-distribution.ps1` (`:196`, `:223`), `scripts/ci/qualify-docker-release.sh` (`:28`), `.github/workflows/pr-ci.yml` (preuve de reproductibilité), `docs/developer/supply-chain.md`.
- **Surfaces publiques** : aucune. `Implementation-Version` et la sortie de `minos --version` sont inchangées.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/architecture/check-module-boundaries.py` (règles de mise en page de build, profils compris), `python scripts/quality/check-jacoco.py`, `python scripts/quality/check-single-execution.py` (une seule exécution du `verify` complet par job), tous les `ShadedJar*IT`, `python scripts/release/check-supply-chain.py`, `python scripts/quality/check-ci-wiring.py`.
- **Plateformes** : Linux et Windows. La reproductibilité est exigée **sur une même plateforme** ; l'identité du jar entre Linux et Windows est mesurée et déclarée, pas présumée.
