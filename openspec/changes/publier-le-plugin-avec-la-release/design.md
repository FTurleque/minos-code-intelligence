# Design

## Context

Sprint 5 de l'audit du 10 octobre 2026 : **AUD-DEP-07**. HEAD analysé : `2d1cc214` (`develop`).

## État vérifié au HEAD

### AUD-DEP-07 — confirmé, y compris par les faits

- `release-windows.yml` ne se déclenche que par `workflow_dispatch`. `:23` : `GH_TOKEN: ${{ github.token }}`. Le job `publish` (`:227-251`, `contents: write`) lance `publish-windows-release.ps1 -PublishOnly`, qui exécute `gh release create … --target $TargetCommit` (`scripts/release/publish-windows-release.ps1:345-349`).
- `intellij-plugin-release.yml:3-5` : `on: release: types: [published]` seul ; ni `workflow_dispatch` ni `workflow_call`.
- **Preuve empirique** (`gh release view`, `gh run list`) : v1.2.0 et v1.1.0, d'auteur `github-actions[bot]`, n'ont aucun `minos-intellij-*.zip` ; v1.0.1 l'a. Dernier run du workflow du plugin : 4 août 2026 (v1.0.1) ; aucun depuis.
- Le workflow du plugin lit `github.event.release.*` à **8 endroits** (`:15`, `:35`, `:36`, `:73`, `:99`, `:134`, `:153`, `:158`).
- Le job `build` de `release-windows.yml` construit déjà le plugin (`:107`), sans `-PminosVersion` (version `1.0.1-SNAPSHOT`), pour le vérifier ; il ne le publie pas.
- Les releases immuables de GitHub sont **désactivées** sur le dépôt (`gh api …/immutable-releases` → `enabled: false`).

**Non vérifié** : pourquoi le run du plugin de v1.0.1 (4 août) précède la date de publication affichée (9 août).

### Écarts avec l'action proposée par l'audit

1. **`workflow_call` ne reçoit pas `github.event.release`.** Les huit lectures seraient vides : le workflow doit recevoir le **tag** et le **commit attendu** en entrées, et dériver le reste.
2. **L'appelant doit accorder les permissions.** Le job qui appelle le workflow réutilisable déclare `contents: write`, sinon l'appel est refusé.
3. **Garder `release: published` créerait une double exécution** le jour où un jeton d'application serait adopté. L'envoi de l'asset sans écrasement échouerait alors (fail-closed) ; plus simple : retirer ce déclencheur (D2).
4. **Releases immuables.** Si GitHub les active sur le dépôt, l'ajout d'un asset **après** publication sera refusé. La conception doit pouvoir évoluer vers « plugin attaché avant publication » (D3).
5. **Le contrôle « commit cible en 40 caractères hexadécimaux »** du workflow du plugin reste satisfait : la release est créée avec `--target <sha>`.
6. **Deux releases sont déjà sans plugin** : l'audit demande de « vérifier la dernière release » ; il faut dire quoi en faire (hors périmètre, décision du propriétaire).

## Goals / Non-Goals

**Goals** : le chemin nominal de release livre le plugin ; son absence fait échouer la release au lieu de passer inaperçue ; la publication du plugin peut être rejouée ; les contrôles de tag sont conservés.

**Non-Goals** : changer la façon dont le plugin est construit ; publier ailleurs que sur la release GitHub ; modifier d'anciennes releases sans décision.

## Decisions

### D1. Appel direct par `workflow_call`, pas de jeton d'application

`release-windows.yml` gagne un job `plugin` (`needs: publish`, `uses: ./.github/workflows/intellij-plugin-release.yml`, entrées `tag` et `commit`, `permissions: contents: write`). Le lien est **dans le dépôt**, lisible et gardé par un gate.

Alternative écartée : créer la release avec un jeton d'application GitHub pour que l'événement se propage. Elle ajoute un secret à gérer et laisse le lien implicite (rien dans le dépôt ne dit que la release dépend du plugin).

### D2. Déclencheurs du workflow du plugin : `workflow_call` et `workflow_dispatch`

`release: published` est retiré. `workflow_dispatch` (entrée `tag`) permet de rejouer la publication du plugin pour une release existante ; le commit attendu est alors lu sur la release (`gh release view --json targetCommitish`) et vérifié comme aujourd'hui. Les huit lectures de `github.event.release.*` passent par des variables calculées une fois à partir des entrées.

### D3. La release n'est annoncée publiée qu'avec le plugin — **décision en attente du propriétaire**

Recommandation (option A) : le job `plugin` s'exécute après `publish` ; un dernier job de contrôle vérifie par `gh release view` que l'asset `minos-intellij-*.zip` et sa somme sont présents, et **échoue** sinon. La release existe déjà à ce moment : l'échec est visible (workflow rouge) et se répare par `workflow_dispatch` du plugin.

Option B : construire le plugin **avant** `publish` et le passer à `gh release create` avec les autres assets, pour qu'une release n'existe jamais sans lui (et pour rester compatible avec des releases immuables). Plus robuste, mais exige de déplacer la construction versionnée du plugin dans `release-windows.yml` ou de transmettre un artefact entre workflows, et de retoucher `publish-windows-release.ps1`.

L'option A est le plus petit correctif ; l'option B devient nécessaire si les releases immuables sont activées. Le design de A ne l'empêche pas.

### D4. Un gate sur le lien

Contrôle statique (dans `check-ci-wiring.py`, qui lit déjà les workflows) : un workflow qui exécute `publish-windows-release.ps1` (ou `gh release create`) avec `github.token` doit contenir un job `uses: ./.github/workflows/intellij-plugin-release.yml` ; et `intellij-plugin-release.yml` ne doit pas avoir `release:` pour seul déclencheur. Un cas refusé et un cas accepté dans son auto-test.

## Direction des dépendances

Sans objet. Le plugin reste un client externe, hors reactor.

## Qualification des capacités

- Publication du plugin par le chemin nominal : **non qualifiée** au HEAD (prouvé par deux releases) ; qualifiée seulement par une release réelle ou un candidat (tâche manuelle 3.2).
- Rejeu par `workflow_dispatch` : à prouver sur un tag de test ou sur une release existante, selon la décision du propriétaire.

## Windows et Linux

`release-windows.yml` tourne sous Windows pour l'installateur ; le job appelé s'exécute sur le runner que déclare le workflow du plugin (Linux). Aucun script PowerShell du plugin.

## Risks / Trade-offs

- Les workflows de release ne s'exécutent pas sur une PR : la preuve finale est une release. Le gate statique et la relecture YAML (`yaml.safe_load`) sont les seules preuves avant fusion ; le dire.
- Une release rouge après publication (option A) laisse temporairement une release sans plugin : réparée par le rejeu, et bien plus visible que le silence actuel.
- Changement des déclencheurs : la documentation de release doit dire qu'une release créée **à la main** ne déclenche plus le plugin automatiquement (rejeu par `workflow_dispatch`).
