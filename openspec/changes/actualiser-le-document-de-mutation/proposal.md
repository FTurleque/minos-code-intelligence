# Proposal

## Why

Constat **AUD-TST-10** (Faible, Tests) de l'audit du 2026-11-15 (`docs/project/audits/2026-11-15/findings.json`,
sprint 6), suite d'AUD-TST-11 de l'audit du 2026-10-10 : le tableau « Statut PIT par module — source unique » de
`docs/developer/quality/code-audit-couverture.md` nomme `minos-storage-local` et `minos-storage-postgresql`, deux
modules fusionnés depuis dans `minos-storage`, et ne nomme ni `minos-storage` ni `minos-explorer`, qui figurent au
`pom.xml` racine (`:27`, `:36`). Aucune mesure de mutation n'existe pour ces deux modules, dont la surface HTTP de
l'explorateur.

L'exigence en vigueur audit-qualite-code › L'état de la mutation par module est écrit une seule fois (scénario
« Tous les modules nommés ») n'est donc plus tenue par le document, et rien ne l'a signalé : la dérive est
silencieuse. La mutation ne tourne que sur demande (`.github/workflows/code-audit.yml:6`, `:117`). Le changement est
documentaire et d'outillage : il se fusionne seul.

## What Changes

- Le tableau d'état de la mutation est réaligné sur les quatorze modules du reactor et le plugin : lignes
  `minos-storage` et `minos-explorer` ajoutées, lignes des deux modules disparus retirées du tableau d'état et
  conservées comme mesures historiques datées.
- Une ligne sans mesure porte « non exécuté » ; elle ne passe « exécuté » qu'avec un rapport complet produit par
  `code-audit.yml`.
- Un contrôle local compare le tableau d'état à la liste des modules du `pom.xml` racine et échoue sur un module
  absent ou disparu.
- La mutation de `minos-storage`, de `minos-explorer` et du plugin est rejouée par `code-audit.yml` (tâches
  manuelles), jamais sur un poste pour le plugin.

L'absence de plancher PIT reste la décision D3 de l'audit du 2026-10-10 : aucun seuil n'est introduit.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `audit-qualite-code` : deux exigences **ajoutées** (le tableau d'état suit la liste des modules du reactor, par un
  contrôle exécutable ; une mesure de mutation antérieure à une fusion de modules n'est pas reportée sur le module
  fusionné). Les exigences existantes sur le tableau unique et les rapports complets sont inchangées.

## Hors périmètre

- Un plancher de score de mutation (décision D3 du 2026-10-10, maintenue).
- La réécriture des sections datées du document (état de référence du 8 octobre, inventaire § 2, SpotBugs § 5.1,
  registre des exécutions § 4) : elles décrivent leur date ; seule la source unique d'état est tenue à jour.
- L'ajout d'un auto-test aux gates existants (AUD-TST-09, changement `doter-chaque-gate-d-un-auto-test`, sprint 1).
- Les tests du front de l'explorateur (AUD-TST-02, sprint 1) : la mutation de `minos-explorer` ne mesure que son
  code Java.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le tableau d'état nomme `minos-storage-local` et `minos-storage-postgresql` | [Existant vérifié] | `docs/developer/quality/code-audit-couverture.md` (tableau « Statut PIT par module — source unique », § 5.2) ; résultats `:243`, `:247` |
| Aucune occurrence de `minos-explorer` dans le document | [Existant vérifié] | recherche dans le fichier : aucune |
| Le reactor a quatorze modules, dont `minos-storage` et `minos-explorer` | [Existant vérifié] | `pom.xml:24-37` |
| La mutation ne tourne que par `workflow_dispatch`, un module à la fois | [Existant vérifié] | `.github/workflows/code-audit.yml:6`, `:117`, job « Muter » (entrée `mutation_module`) |
| Le plugin est muté par un job séparé | [Existant vérifié] | `.github/workflows/code-audit.yml:270-304` |
| `audit-report-summary.py pit --all` nomme un module sans rapport | [Existant vérifié] | `scripts/quality/audit-report-summary.py:191` ; exigence « Un module sans rapport complet n'est pas compté comme analysé » |
| Durée et faisabilité de la mutation de `minos-storage` fusionné sous le délai de 120 min | [Question] non bloquante | non exécuté ; l'ancien `minos-storage-postgresql` a pris 1 h 23 à 2 fils sur poste |

## Impact

- **Modules du reactor** : aucun code de production. `scripts/quality/audit-report-summary.py` (sous-commande de
  contrôle et son auto-test), `.github/workflows/pr-ci.yml` (job `invariants` : exécution du contrôle et de son
  auto-test).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`,
  `python scripts/docs/check-current-docs.py`, `python scripts/docs/check-capability-dossiers.py`.
- **Plateformes** : contrôle identique sous Windows et Linux (bibliothèque standard, chemins normalisés). Mutation :
  Ubuntu 24.04 par `code-audit.yml` ; **jamais de PIT du plugin sur le poste du mainteneur**.
- **Documentation durable et diagrammes** : `docs/developer/quality/code-audit-couverture.md`,
  `docs/developer/quality/code-audit.md` (commande du contrôle), `docs/architecture/diagrams/capacites/audit-qualite-code.md`.

## Questions ouvertes

- D2 : garde automatique en CI ou contrôle manuel à la clôture (recommandation : garde automatique).
