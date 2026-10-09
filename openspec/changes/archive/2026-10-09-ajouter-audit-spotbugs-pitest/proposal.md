# Proposal

## Why

Le dépôt mesure la couverture (JaCoCo) mais ne dispose ni d'analyse statique du bytecode ni de mesure de la capacité des tests à détecter une régression. Une couverture élevée ne dit pas si les tests contrôlent quelque chose : une vérification supprimée dans une méthode couverte peut ne faire échouer aucun test. Il manque aussi un moyen reproductible de transformer ces constats en changements OpenSpec ciblés, sans imposer un seuil global arbitraire ni alourdir le build habituel.

## What Changes

- Ajout du profil Maven **`audit-spotbugs`** (SpotBugs 4.10.4 via `spotbugs-maven-plugin` 4.10.4.1) : rapports XML, SARIF et HTML par module, contrôle bloquant `spotbugs:check` à lancer explicitement (effort `Max`, seuil `Medium`).
- Ajout du profil Maven **`audit-mutation`** (PIT `pitest-maven` 1.30.0, `pitest-junit5-plugin` 1.2.3) : mutation d'un **périmètre restreint** (classes d'autorisation et de chaîne d'audit hébergées de `minos-engine`), rapports HTML et XML, périmètre ajustable par `-DtargetClasses` / `-DtargetTests` sans modifier de POM.
- Versions et configuration communes centralisées dans le POM parent ; aucune des deux analyses n'est liée au cycle de vie par défaut.
- Script `scripts/quality/audit-report-summary.py` : synthèse des rapports et garde-fou contre une analyse PIT vide.
- Workflow `code-audit.yml` déclenché **à la main uniquement**.
- Documentation `docs/quality/code-audit.md` et consignation séparée des constats de la première exécution (`docs/quality/code-audit-constats.md`).
- Aucune rupture : `clean verify` et `pr-ci.yml` sont inchangés.

## Capabilities

### New Capabilities

- `audit-qualite-code`: audit à la demande du code de production (SpotBugs) et de la capacité des tests à détecter une régression (PIT), borné, reproductible, sans effet sur le build par défaut.

### Modified Capabilities

(aucune)

## Hors périmètre

- Corriger les alertes SpotBugs ou les mutants survivants découverts : ils sont consignés dans `docs/quality/code-audit-constats.md` et feront l'objet de changements séparés.
- Rendre `spotbugs:check` ou un seuil PIT obligatoires en CI : décision à prendre sur une base chiffrée (le contrôle est aujourd'hui rouge sur 12 modules sur 14).
- Greffons SpotBugs supplémentaires (`find-sec-bugs`, `fb-contrib`), PIT sur tout le reactor, `minos-intellij` (Gradle).
- Exécuter PIT sur Linux/macOS ou le workflow `code-audit.yml` : non réalisé dans l'environnement de l'intégration.

## Impact

- **Modules du reactor touchés** : `pom.xml` parent seul (propriétés, `pluginManagement`, deux profils). Aucun module Java modifié, aucune dépendance ajoutée à un module ; direction ADR-0022 inchangée.
- **Surfaces publiques** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun nouvel ADR ni amendement. Un ADR serait nécessaire pour rendre un contrôle obligatoire en CI.
- **Fichiers** : `pom.xml`, `quality/spotbugs-exclude.xml`, `scripts/quality/audit-report-summary.py`, `.github/workflows/code-audit.yml`, `docs/quality/*`, liens dans `docs/README.md` et `docs/developer/testing.md`.
- **Gates à rejouer** : `scripts/quality/check-workflow-pins.py`, `check-single-execution.py`, `scripts/docs/check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `scripts/architecture/check-module-boundaries.py`. Aucun scope JaCoCo n'est touché.
