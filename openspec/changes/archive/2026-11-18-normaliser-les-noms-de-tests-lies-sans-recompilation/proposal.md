# Proposal

## Why

Constat **AUD-PERF-11** (Faible, Performance) de l'audit du 2026-11-15
(`docs/project/audits/2026-11-15/findings.json`, sprint 6) : la dérivation des tests liés normalise chaque nom par
`value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "")`
(`minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java:451-456`). `String.replaceAll`
compile une expression régulière à chaque appel ; la méthode est appelée une fois par symbole de production (`:161`)
et deux fois par symbole de test (`:169-170`), à chaque indexation. Le coût est estimé par l'audit, non mesuré.

Le changement est isolé (une méthode privée, un module) et se fusionne seul ; il n'a aucun fichier commun avec les
autres changements du sprint.

## What Changes

- La normalisation des noms de la dérivation des tests liés ne compile plus d'expression régulière par appel.
- La valeur normalisée reste **identique** à celle d'aujourd'hui pour toute entrée, noms non ASCII compris : les
  relations de tests liés, leurs preuves et leur ordre ne changent pas.
- Un test de caractérisation fixe cette identité avant la correction.

Aucune **RUPTURE**, aucune sortie modifiée.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `tests-lies-et-impact` : une exigence **ajoutée**, qui fixe la règle de normalisation des noms (comportement
  existant, aujourd'hui sans exigence ni test dédié) pour que la réécriture ne puisse pas la déplacer.

## Hors périmètre

- Les autres expressions régulières du moteur de requêtes (non relevées par l'audit).
- Toute modification des conventions de nommage reconnues (`stripTestSuffix`, `stripTestFileSuffix`).
- La mesure du gain sur un index réel : le poste ne produit pas d'index réel sans la voie WSL2 ; mesure facultative.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `replaceAll` avec un motif littéral à chaque appel | [Existant vérifié] | `RelatedTestDerivationService.java:455` |
| Appel par symbole de production et deux fois par symbole de test | [Existant vérifié] | `RelatedTestDerivationService.java:161`, `:169-170` |
| La mise en minuscules précède le filtre : un caractère non ASCII dont la minuscule est ASCII est conservé (`K` kelvin U+212A → `k`, `İ` U+0130 → `i` + diacritique retiré) | [Existant vérifié] | `RelatedTestDerivationService.java:455` ; sémantique de `String.toLowerCase(Locale.ROOT)` |
| Aucun gate littéral ne cite cette classe ni `normalizedName` | [Existant vérifié] | recherche dans `scripts/` : aucune occurrence |
| Coût « de l'ordre de la seconde » | [Question] non bloquante | estimation de l'audit, non mesurée |

## Impact

- **Modules du reactor** : `minos-engine` seul.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune ; sorties identiques à l'octet.
- **ADR** : aucun.
- **Gates à rejouer** : `python .claude/scripts/run_gates.py --fast` ; goldens de
  `minos-app/src/test/resources/characterization/` inchangés ; scope JaCoCo `global`.
- **Plateformes** : Windows et Linux, même code ; `Locale.ROOT` conservé.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/tests-lies-et-impact.md`
  (exigence ajoutée).

## Questions ouvertes

Aucune.
