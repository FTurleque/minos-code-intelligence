# Proposal

## Why

**AUD-DEP-07** (moyenne, sprint 5 de l'audit du 10 octobre 2026). Le workflow qui construit et attache le zip du plugin IntelliJ à une release (`intellij-plugin-release.yml`) ne se déclenche que sur l'événement `release: published` (`:3-5`). Or la release est créée par `release-windows.yml` avec le jeton du workflow (`GH_TOKEN: ${{ github.token }}`, `:23` ; `gh release create` dans `publish-windows-release.ps1:345-349`), et GitHub ne déclenche **aucun** workflow pour un événement produit par ce jeton.

Le constat est confirmé par les faits : les releases **v1.1.0 et v1.2.0**, créées par `github-actions[bot]`, n'ont aucun `minos-intellij-*.zip` ; **v1.0.1** l'a. Le dernier run du workflow du plugin date du 4 août 2026. Le chemin nominal de publication ne livre donc plus le plugin, et personne n'en est averti.

## What Changes

- **La publication du plugin devient une étape de la release** : `release-windows.yml` appelle le workflow du plugin après la publication, avec le tag et le commit en entrées.
- **Le workflow du plugin accepte d'être appelé** (`workflow_call`) et relancé à la main (`workflow_dispatch`) pour une release existante, en gardant ses contrôles (tag résolu vers le commit attendu, tag non déplacé).
- **Une release sans plugin est un échec visible** : la release n'est déclarée publiée que si l'asset du plugin est présent.
- **Un gate** refuse qu'un workflow crée une release par le jeton du workflow sans appeler la publication du plugin.

## Capabilities

### New Capabilities

(aucune : `chaine-de-livraison` est créée par `conserver-les-notices-tierces` ; ce changement y ajoute des exigences et peut être fusionné avant ou après)

### Modified Capabilities

- `chaine-de-livraison` : deux exigences ajoutées (une release publiée par le chemin nominal contient le plugin ; la publication du plugin peut être rejouée pour une release existante sans la modifier autrement).

## Hors périmètre

- **Rattraper v1.1.0 et v1.2.0** : les releases sont immuables par politique ; y ajouter un asset est une décision du propriétaire (tâche manuelle 3.3, non exécutée par défaut).
- Créer la release avec un jeton d'application GitHub (autre façon de déclencher l'événement) : écarté, voir `design.md` (D1).
- Publier le plugin sur JetBrains Marketplace.
- La signature et l'attestation de la release (AUD-DEP-06) ; le wrapper Gradle et le runner du workflow du plugin (AUD-DEP-05, changement `durcir-les-workflows-github`) ; le verrou des dépendances Gradle (AUD-DEP-04).

## Impact

- **Modules du reactor** : aucun. Le plugin `minos-intellij` n'est pas modifié.
- **Fichiers** : `.github/workflows/intellij-plugin-release.yml` (déclencheurs, 8 lectures de `github.event.release.*` aux lignes `:15,35,36,73,99,134,153,158`), `.github/workflows/release-windows.yml` (job appelant après `publish`), `scripts/quality/check-ci-wiring.py` ou `check-workflow-pins.py` (gate), documentation de release.
- **Surfaces publiques** : aucune. Le contenu d'une release gagne de nouveau le zip du plugin.
- **ADR** : aucun.
- **Gates à rejouer** : `python scripts/docs/check-current-docs.py` (`:207-208` exige `workflow_dispatch` et interdit `pull_request:` dans `release-windows.yml`), `python scripts/quality/check-workflow-pins.py` (il ignore les `uses: ./…`), `python scripts/quality/check-single-execution.py` (il exclut les workflows manuels), `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-toolchain-baseline.py`.
- **Plateformes** : runners GitHub ; le plugin est construit sous Linux.
