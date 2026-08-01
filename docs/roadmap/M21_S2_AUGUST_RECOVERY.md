# M21-S2 — Runbook de reprise août 2026

Statut : **PRÉPARÉ — NON EXÉCUTÉ EN JUILLET.**

Ce runbook prépare la reprise de M21-S2 sans contourner le gel explicite de juillet 2026. Aucune étape GitHub Actions, required checks ou branch protection ne doit être exécutée avant le **1er août 2026**.

## Préconditions bloquantes

1. le lot P0–P2 #95 est intégré dans `develop` après qualification locale exacte Windows + Linux ;
2. `develop` est propre, sans divergence documentaire ni Product Facts stale ;
3. aucun changement opportuniste de fonctionnalité n’est inclus ;
4. la disposition sandbox M25 reste honnête : backend OS réellement prouvé ou `DENY` fail-closed ;
5. l’issue #73 reste ouverte jusqu’à fermeture effective de S2.

## Séquence de reprise

### A. Établir la baseline exacte

- enregistrer le SHA exact de `develop` ;
- exécuter les gates locaux consolidés Windows + Linux sur ce même SHA ;
- exécuter `scripts/remediation/run-final.ps1` et `scripts/remediation/run-final.sh` ;
- vérifier worktrees propres et absence de diff `.github/workflows` hérité du lot de remédiation.

### B. Reprendre l’analyse CI

À partir du 1er août uniquement :

- inspecter les workflows existants et l’historique des échecs M21-S2 ;
- distinguer défaut produit, défaut workflow, indisponibilité runner et limite de permissions ;
- corriger uniquement les workflows nécessaires à la qualification du produit ;
- conserver Java/Maven/OS/pins explicites ;
- produire des diagnostics exploitables et des artefacts de preuve bornés ;
- ne jamais utiliser un rerun isolé comme unique preuve de correction.

### C. Required checks et branch protection readiness

- définir les checks réellement bloquants pour `develop` et `main` ;
- vérifier leur stabilité sur plusieurs exécutions propres ;
- documenter les checks locaux qui restent nécessaires en complément ;
- vérifier que les permissions GitHub permettent la configuration cible ;
- si une protection ne peut pas être configurée, conserver une disposition `BLOCKED` explicite au lieu d’un faux PASS.

### D. Qualification finale M21-S2

La preuve doit inclure :

- SHA exact ;
- exécutions Windows/Linux applicables ;
- résultats Maven, tests, JaCoCo, module boundaries, Product Facts, supply chain et surfaces ;
- état de chaque workflow/check requis ;
- absence de secrets dans logs/artefacts ;
- worktree propre ;
- décision `PASS`, `PASS_WITH_CONSTRAINTS` ou `BLOCKED` motivée.

### E. Convergence `develop` → `main`

Uniquement si M21-S2 et les gates du lot #95 sont fermés :

1. geler les changements fonctionnels ;
2. comparer `main...develop` et inventorier tous les jalons M21→M27 + remédiations ;
3. ouvrir une PR de promotion dédiée ;
4. exécuter les required checks sur le SHA de promotion ;
5. fusionner avec protection contre déplacement du HEAD ;
6. produire release/SBOM/notices/checksums et smoke install selon le contrat courant ;
7. réconcilier README, STATUS, ROADMAP, #73, #95, #93 et les ADR ;
8. fermer #73 seulement après preuve de promotion ou décision explicite séparant clôture S2 et release.

## Critères de refus

La promotion est interdite si :

- un check obligatoire est absent, neutralisé ou flaky sans disposition ;
- Windows et Linux ne portent pas le même exact HEAD lorsqu’ils sont requis ;
- un Product Fact est codé en dur ou divergent du runtime ;
- le provider Java avancé n’est pas prouvé depuis `MinosApplication.open()` ;
- `DENY` réseau est déclaré sans backend OS qualifié ;
- une incohérence documentaire ou une issue de gouvernance reste masquée.

## Sortie attendue

```text
M21-S2 CI RECOVERY VALIDATION SUCCESS
Required checks: PASS
Branch protection readiness: PASS or explicit BLOCKED
Promotion candidate HEAD: <sha>
```

Ce texte est un plan de reprise, pas une preuve d’exécution. Toute preuve finale doit être ajoutée à `M21_EXECUTION.md`, `STATUS.md` et l’issue #73 après le 1er août 2026.
