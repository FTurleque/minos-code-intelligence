# Proposal

## Why

**AUD-TST-11** (faible, sprint 6 de l'audit du 10 octobre 2026 ; confirmé). L'état des tests de mutation est à la fois **incomplet** et **mal décrit**.

- **Le document se contredit.** `docs/quality/code-audit-couverture.md` donne trois décomptes différents : `:122` (§ 3.3) « 6 modules complets, `minos-runtime-local` partiel, `minos-storage-postgresql` interrompu, 6 modules non exécutés » ; `:229` (§ 5.2) « 11 modules sur 14 analysés » ; `:288` (§ 7) « PIT n'a analysé que 3 modules sur 14 ». Le tableau du § 5.2 fait foi : 10 modules exécutés, 1 partiel, 3 sans rapport. Le chiffre « 3 sur 14 » est repris par d'autres documents.
- **Trois modules n'ont jamais été mutés** : `minos-cli` (`:243`, rapport vide), `minos-bootstrap` et `minos-app` (pas de rapport). `minos-runtime-local` porte 560 `RUN_ERROR` qui ne sont pas des résultats (`:252`) ; le plugin IntelliJ est noté « bloqué » (`:265`) alors que `code-audit.yml` a depuis un job qui exécute son PIT.
- **Rien ne s'exécute seul** : `.github/workflows/code-audit.yml` n'a que `workflow_dispatch`, un module par exécution, et aucun seuil.

La force des tests de la CLI, de la racine de composition et de l'assemblage livré reste inconnue, et le point de reprise est brouillé.

## What Changes

- **Correction documentaire, faisable seule** : l'état de la mutation par module est écrit **une fois** (tableau du § 5.2) ; les § 3.3 et § 7 y renvoient au lieu de redonner un décompte.
- **Exécutions manquantes**, lancées par le propriétaire sur runner éphémère : `minos-cli`, `minos-bootstrap`, `minos-app`, rejeu de `minos-runtime-local`, PIT du plugin. Résultats reportés dans le tableau avec leurs identifiants d'exécution.
- **Limite déclarée** : ce que des exécutions sous Linux ne mutent pas utilement (code propre à Windows).
- **Plancher PIT** : aucune valeur fixée ici ; la décision est instruite avec une recommandation.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `audit-qualite-code` : deux exigences ajoutées (état de la mutation par module écrit une seule fois et référencé ; module sans rapport complet jamais compté comme analysé).

## Hors périmètre

- **Fixer un plancher PIT** ou rendre PIT bloquant : hors périmètre explicite du changement actif `etendre-audit-outille-a-tout-le-perimetre` ; décision en attente (`design.md`, D3).
- Tuer les mutants survivants : changements dédiés au renforcement des tests.
- Déclencher `code-audit.yml` automatiquement : la politique du dépôt l'interdit (en-tête du workflow).
- Modifier le profil `audit-mutation` ou la configuration PIT du plugin.
- Corriger les documents d'audit archivés qui citent « 3 sur 14 » : historique figé.

## Impact

- **Modules du reactor** : aucun code. Les modules `minos-cli`, `minos-bootstrap`, `minos-app` et `minos-runtime-local` sont **mutés à la demande**, pas modifiés.
- **Fichiers** : `docs/quality/code-audit-couverture.md` ; `docs/quality/code-audit.md` si la procédure de reprise y est décrite.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/docs/check-current-docs.py` ; `python scripts/docs/product-facts.py --check` ; `python scripts/quality/audit-report-summary.py pit --all` sur les rapports téléchargés.
- **Plateformes** : les exécutions de `code-audit.yml` tournent sur Ubuntu 24.04 ; les mesures du 8 octobre viennent d'un poste Windows. Les deux ne sont pas comparables ligne à ligne, et le document doit le dire.
