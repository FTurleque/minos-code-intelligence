# M21-S2 — Runbook de reprise août 2026

Statut : **EXÉCUTÉ — 1er août 2026.**

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

Ce texte est un plan de reprise, pas une preuve d'exécution. Toute preuve finale doit être ajoutée à `M21_EXECUTION.md`, `STATUS.md` et l'issue #73 après le 1er août 2026.

---

## Résultats d'exécution — 1er août 2026

```text
Date                    : 2026-08-01
Executor HEAD           : 96dc60af936d6df6ce8d40245039fe170554df74

A — Baseline exacte
  develop HEAD              : 96dc60af936d6df6ce8d40245039fe170554df74
  Worktree                  : clean
  diff .github/workflows    : VIDE

B — Analyse CI
  Workflows inspectés       : PR Validation, M19, M20, IntelliJ Plugin Validation
  Historique                : tous PASS sur HEAD 96dc60a (runs 30699982335, 30699982338, 30699982379, 30699982411)

C — Required checks et branch protection readiness
  API /branches/main/protection : HTTP 403 — dépôt privé plan gratuit ; branch protection non configurable
  API /rulesets                 : HTTP 403 — même cause
  API /rules/branches/main      : HTTP 403 — même cause
  mergeable_state (PR #102)     : unstable (non blocked) — aucun required check configuré ne bloque le merge
  SonarCloud                    : FAILURE (non-required ; mergeable_state=unstable confirme non-bloquant)
  Disposition branch protection : BLOCKED — contrainte de plan GitHub gratuit, documentation explicite

D — Qualification finale M21-S2
  CI Recovery             : PASS
  Required checks         : PASS (GitHub Actions gates tous verts sur 96dc60a)
  Branch protection       : BLOCKED (plateforme, API 403, plan gratuit)
  Promotion candidate     : 96dc60af936d6df6ce8d40245039fe170554df74
  mergeable               : true
  mergeable_state         : unstable (aucun required check GitHub-enforced configuré)
  PR #102                 : OPEN / candidate de production

M21-S2 CI RECOVERY VALIDATION SUCCESS
Required checks: PASS
Branch protection readiness: BLOCKED — platform constraint, free private plan, API 403
Promotion candidate HEAD: 96dc60af936d6df6ce8d40245039fe170554df74
```
