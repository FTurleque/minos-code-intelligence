# Proposal

## Why

`minos-bootstrap` et `minos-storage-postgresql` déclarent `org.testcontainers:testcontainers` 2.0.5 avec `junit-jupiter` et `postgresql` en 1.21.4 : Dependabot a monté le cœur le 2026-08-30 (`d763f703`), mais les modules ont changé d'identifiant en 2.x (`testcontainers-*`) et sont restés en 1.x (MINOS-AUD-H05). Les tests passent, mais deux versions majeures cohabitent sans garantie. Le transport Docker embarqué (`docker-java-transport-zerodep` 3.7.1) contient quatre CVE de `httpclient5` 5.5.1 et `httpcore5` 5.3.6, en portée test seulement (H03). `slf4j-api` ne converge pas entre modules (1.7.36, 2.0.16, 2.0.18, 2.0.20). Constats : `docs/quality/code-audit-constats.md` § 5.

## What Changes

- Testcontainers aligné sur une seule version majeure : artefacts 2.x `testcontainers-postgresql` et `testcontainers-junit-jupiter` (ou nomenclature `testcontainers-bom` dans le POM parent).
- Version de Testcontainers choisie pour embarquer un docker-java dont le transport n'a plus les CVE de H03, si une telle version existe ; sinon consigner la version attendue.
- `slf4j-api` géré par `slf4j.version` dans `dependencyManagement` ; évaluer la règle Enforcer `dependencyConvergence` (décision : elle s'appliquerait à tout le réacteur).
- Dependabot : groupe qui fait monter cœur et modules Testcontainers ensemble.
- Sous Windows, plus de conteneur PostgreSQL laissé par une JVM de test tuée (H24 : Ryuk désactivé par le profil Windows).

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

(aucune : dépendances de build et de test, sans comportement spécifié ; `skip_specs: true`)

## Hors périmètre

- Les dépendances livrées (aucune vulnérabilité au 8 octobre 2026).
- Le code des tests PostgreSQL, sauf adaptation imposée par l'API 2.x.

## Impact

- POM : parent, `minos-bootstrap`, `minos-storage-postgresql` ; `.github/dependabot.yml`.
- Modules du réacteur : tests seulement. Surfaces publiques : aucune. ADR : aucun.
