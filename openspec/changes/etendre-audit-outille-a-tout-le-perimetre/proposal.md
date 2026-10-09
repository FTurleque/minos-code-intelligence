# Proposal

## Why

L'intégration initiale (`ajouter-audit-spotbugs-pitest`) limitait PIT à trois classes, n'avait ni ArchUnit, ni Dependency-Check, ni Gitleaks, et concluait à l'analyse SpotBugs de « tous les modules » sur la foi d'un build vert. L'audit outillé du 8 octobre 2026 (`docs/quality/code-audit-couverture.md`) a montré que les rapports SpotBugs du plugin Maven ne prouvent pas le périmètre analysé (MINOS-AUD-H01), que le plugin IntelliJ échappe aux contrôles locaux (H09), que la clé NVD disponible est refusée (H04), que Dependency-Check produit des faux rapprochements sur des modules MINOS (H02) et que Gitleaks n'est ni configuré ni exécuté par la CI (H06). Ce changement fixe ce que l'audit outillé doit garantir pour qu'aucune conclusion de couverture ne repose sur un périmètre supposé.

## What Changes

- PIT mute **toutes** les classes `com.minos.*` de chaque module par défaut ; `-Dpit.crossModule` disponible. *(fait pendant l'audit)*
- Ajout d'ArchUnit 1.5.1 (test de `minos-app`) : garde d'import complet des 14 modules et six règles issues des ADR 0022, 0042 et 0044 ; mesure stricte opt-in des usages de modules non déclarés (proposition, voir H08). *(fait)*
- Ajout des profils `audit-dependency-check` (dépendances livrées) et `audit-dependency-check-tests` (portée test), Dependency-Check 13.0.0, `failOnError=true`, rapport agrégé, clé NVD passée par nom de variable d'environnement. *(fait)*
- `audit-report-summary.py` : décompte des classes analysées par SpotBugs (`UNPROVEN` si le rapport ne le permet pas), PIT de tout le réacteur avec timeouts et erreurs séparés des mutants tués, synthèse Dependency-Check avec fraîcheur des données. *(fait)*
- `code-audit.yml` : PIT sur un module choisi, job Dependency-Check. *(fait, non exécuté sur GitHub)*
- **À faire** : relecture SpotBugs en ligne de commande automatisée dans `code-audit.yml` ; job plugin IntelliJ (wrapper Gradle, SpotBugs, Dependency-Check, PIT) ; fichier de suppressions Dependency-Check ciblé pour les deux faux rapprochements ; configuration Gitleaks et job (après décision) ; clé NVD valide.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` (introduite par `ajouter-audit-spotbugs-pitest`, non encore archivée) : exigences ajoutées sur la preuve du périmètre, ArchUnit, Dependency-Check et Gitleaks.

## Hors périmètre

- Corriger les alertes et faiblesses de tests trouvées : changements `renforcer-tests-revelees-par-mutation`, `couvrir-reprise-apres-interruption-du-stockage`, `aligner-dependances-de-test-testcontainers`, `corriger-documentation-plugin-et-suivi-audit`.
- Rendre bloquants SpotBugs, PIT, Dependency-Check ou Gitleaks dans `pr-ci.yml` : décision à prendre sur la base chiffrée du 8 octobre.
- Trancher l'usage transitif des modules (H08) : ADR à rédiger si une règle doit devenir obligatoire.

## Impact

- Modules : `minos-app` (dépendance de test ArchUnit et `ModuleArchitectureTest`) ; POM parent (propriétés et profils) ; aucun code de production.
- Surfaces publiques : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- ADR : aucun nouvel ADR ; s'appuie sur ADR 0022, 0042, 0044, 0057 (point 7 : ArchUnit est une option de réalisation des gardes).
- CI : `code-audit.yml` (manuel uniquement) ; `clean verify` exécute désormais les six règles ArchUnit établies.
