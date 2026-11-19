# Proposal

## Why

Constat **AUD-ARC-07** de l'audit du 2026-11-15 (sprint 8) : la vue des blocs de l'arc42 diverge du code sur des points que la règle A9 ne contrôle pas. A9 ne confronte aux POM que les lignes « Dépendances » ; les lignes « Interfaces » et « Sources », la portée des dépendances et le rôle de `minos-app` sont de la prose libre.

- `minos-domain` est dit sans interface, alors qu'il porte les ports `SemanticVectorStore` et `SemanticProjectionStore`.
- La section de `minos-storage` place `SemanticVectorStore` dans `minos-engine`.
- La section de `minos-engine` cite 7 de ses 14 racines de packages et omet `com.minos.io`, la plus importée.
- `minos-app` est décrit sans la route de l'explorateur.
- `minos-explorer` est présenté comme dépendant de `minos-engine`, qu'il ne déclare qu'en portée test ; le document généré ne montre aucune portée.
- La spec `frontieres-de-modules` écrit « 14 modules » trois fois et « 13 modules » une fois ; le dossier de capacité affirme l'inverse du reactor.

Le changement vient en dernier dans le sprint : après le sprint 7 (AUD-ARC-01, AUD-ARC-02), qui déplace des classes de `minos-application`, et après `reclasser-minos-nexus-en-module-de-contrat` (AUD-ARC-08).

## What Changes

- Le document généré `docs/architecture/diagrams/module-dependencies.md` affiche la portée de toute dépendance interne déclarée hors compilation ; l'arc42 fait de même sur ses lignes « Dépendances », et A9 le vérifie.
- A9 s'étend aux lignes « Sources » : les racines de packages citées pour un module sont exactement celles que la table A14 lui attribue, sauf si la ligne ne cite que la racine des sources du module.
- A9 s'étend aux lignes « Interfaces » : un type cité existe dans le reactor et appartient au module de la section ou à un module que la ligne nomme ; « aucune » est refusé pour un module qui déclare une interface publique.
- La prose de `docs/architecture/arc42/05-vue-blocs.md` est corrigée sur les six points du constat ; le document généré est régénéré par `--write-doc`, jamais édité à la main.
- Le scénario de la spec qui affirme « 13 modules » cesse d'écrire un nombre ; le dossier de capacité corrige son écart E2.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (portée affichée, racines citées, interfaces citées) ; une exigence modifiée, « Les adaptateurs de stockage forment un seul module », dont un scénario affirme un nombre de modules devenu faux.

## Hors périmètre

- Les diagrammes de couches écrits à la main (`c4-container.md`, diagramme 5.1) : ils se déclarent simplifiés et restent non gardés ; leur correction pour `minos-nexus` appartient à AUD-ARC-08.
- La description des adaptateurs logés dans `minos-application` et de sa façade : elle suit le sprint 7.
- Les trois exigences qui écrivent « 14 modules », vraies au HEAD (design D4).
- Les écarts E1, E3, E4 et E5 du dossier de capacité.
- La source unique des tables (AUD-ARC-04).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| « Interfaces : aucune (modèle passif) » pour `minos-domain` | [Existant vérifié] | `docs/architecture/arc42/05-vue-blocs.md:49` ; `minos-domain/src/main/java/com/minos/semantic/SemanticVectorStore.java:17`, `SemanticProjectionStore.java:10` |
| `SemanticVectorStore` dit défini dans `minos-engine` | [Existant vérifié] | `docs/architecture/arc42/05-vue-blocs.md:70` |
| 7 racines du moteur sur 14 citées | [Existant vérifié] | `docs/architecture/arc42/05-vue-blocs.md:58` ; `scripts/architecture/check-module-boundaries.py:680-683` |
| `minos-app` sans la route de l'explorateur | [Existant vérifié] | `docs/architecture/arc42/05-vue-blocs.md:17`, `:131` ; `minos-app/src/main/java/com/minos/app/ExplorerLaunchRouteProvider.java` |
| `minos-explorer` → `minos-engine` en portée test seulement | [Existant vérifié] | `minos-explorer/pom.xml:40-53` ; `docs/architecture/arc42/05-vue-blocs.md:121` ; `docs/architecture/diagrams/module-dependencies.md:99` |
| « 14 modules » trois fois, « 13 modules » une fois | [Existant vérifié] | `openspec/specs/frontieres-de-modules/spec.md:58`, `:114`, `:137`, `:822` |
| Le dossier de capacité dit que le reactor compte 13 modules | [Existant vérifié] | `docs/architecture/diagrams/capacites/frontieres-de-modules.md:149` ; `pom.xml:24-37` (14 modules) |
| Étendre A9 plutôt que retirer les listes | [Proposition] | design D1 |

## Impact

- **Modules du reactor** : aucun code Java. Script `scripts/architecture/check-module-boundaries.py` et son auto-test.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun. A9 est une règle du garde décrite par `docs/developer/quality-gates.md` ; aucune règle Archgate ne lit le document généré.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` (et `--write-doc`), `scripts/architecture/test_check_module_boundaries.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/docs/check-capability-dossiers.py`, `archgate check`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux — le document généré est identique à l'octet sur les deux (fins de ligne et ordre déterministes, déjà exigés).
- **Documentation durable et diagrammes** : `docs/architecture/arc42/05-vue-blocs.md`, `docs/architecture/diagrams/module-dependencies.md` (généré), `docs/developer/quality-gates.md` (A9), dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`.

## Questions ouvertes

Aucune bloquante. D1 (étendre A9 ou retirer les listes) porte une recommandation dans le design.
