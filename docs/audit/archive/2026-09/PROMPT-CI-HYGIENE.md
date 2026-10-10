# Prompt — C1 et G3 : consolider la CI et retirer les artefacts de jalon (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **hygiène CI et dépôt** de l'audit `docs/audit/archive/2026-09/AUDIT-2026-09.md` sur `minos-code-intelligence`. Deux constats, liés : **C1** (douze workflows qui se chevauchent sur chaque PR) et **G3** (les scripts et workflows de jalon s'accumulent sans politique de retrait).

Tu travailles avec **un agent d'implémentation** et **un agent qui inspecte son travail en continu, en parallèle** — ici l'inspection est plus critique que d'habitude : supprimer un gate qui est la seule application d'un invariant ne se voit pas, et ne se rattrape qu'en production.

## Règles non négociables

1. **Aucun déclenchement de CI pendant le travail.** Tu ne pousses rien tant que le chantier n'est pas complet et relu. À la fin, tu me demandes l'autorisation pour **une** exécution de validation sur une PR en brouillon. Pas d'aller-retour « je pousse pour voir ».
2. **Branche** `ci-hygiene/audit-remediation` depuis `develop`, un commit par étape réversible, identifiant du constat (C1, G3) dans le corps du message.
3. **Rien n'est supprimé sans preuve de remplacement.** Avant de retirer un workflow, un job ou un script, tu établis quel invariant il applique et où cet invariant est désormais appliqué. Cette table est le livrable principal ; le diff n'en est que la conséquence.
4. **Aucun changement de comportement produit.** Ce chantier ne touche ni le code Java, ni les seuils de couverture, ni les versions épinglées.
5. **Réponds en français.**

## État actuel à connaître

Sur chaque PR vers `develop` tournent aujourd'hui : `pr-ci` (2 OS, `clean verify` complet), `mnd-remediation`, `mne-remediation`, `post-mne-remediation`, `post-228-hardening`, `docker-release-validation` (build d'image de 90 min, sans filtre de chemins), `m19-advanced-code-intelligence` et `m20-semantic-hybrid-intelligence` (qui refont un `clean verify` + JaCoCo dès qu'on touche `domain`, `application`, `api` ou `mcp`). Soit jusqu'à trois builds Maven complets sous Linux, environ douze jobs, et `check-workflow-pins.py` exécuté quatre fois. Les jobs `m19-final-windows` et `m20-final-windows` ne se déclenchent que pour des branches `agent/m19-…` et `agent/m20-…` : ils ne tournent jamais. Côté dépôt, `scripts/m0` à `scripts/m29`, `scripts/remediation` et les vérificateurs de jalon se sont accumulés sans qu'aucune règle ne dise quand un artefact de jalon terminé est archivé.

Ce qui est bon et ne doit pas régresser : toutes les actions sont épinglées par SHA et vérifiées par un gate, aucun `pull_request_target`, aucune expression `github.event` dans un `run:`, `contents: read` par défaut.

## C1 — Un pipeline de PR unique

**Cible** : une PR déclenche **un** workflow de validation, avec des jobs conditionnés par des filtres de chemins, et chaque gate s'exécute **exactement une fois**.

Étapes attendues :

1. **Inventaire d'abord.** Pour chaque workflow déclenché sur `pull_request` ou `push`, liste : ses déclencheurs, ses jobs, les gates qu'il exécute, sa durée observée, et ce qu'il est le seul à vérifier. Cite les numéros de ligne. Cet inventaire va dans `docs/audit/archive/2026-09/CI-HYGIENE-SUIVI.md` avant le premier commit.
2. **Fusionne** les workflows de remédiation et de jalon encore utiles en jobs du pipeline de PR, avec filtres de chemins, au lieu de workflows séparés. Ce qui n'a plus de raison d'être sur chaque PR part vers un déclenchement planifié ou manuel (`workflow_dispatch`), ou est retiré selon la règle 3.
3. **Supprime les jobs morts** (`m19-final-windows`, `m20-final-windows` et tout job dont la condition de branche ne peut plus être vraie), en le justifiant.
4. **Filtre `docker-release-validation`** sur les chemins qui le concernent réellement (`docker/**`, `scripts/release/**`, POM du reactor), ou déplace-le sur le déclenchement de release. Un build de 90 minutes ne doit pas partir sur une modification de documentation.
5. **Ajoute un groupe de concurrence** par référence pour annuler les exécutions rendues obsolètes par un nouveau push — sur les PR uniquement, jamais sur `develop` ni `main`.
6. **Dédoublonne les gates** : `check-workflow-pins.py`, les frontières de modules, `product-facts.py`, `check-current-docs.py` et JaCoCo s'exécutent une fois chacun, dans le job qui a le bon contexte.
7. **Uniformise les runners** (`ubuntu-24.04`, `windows-2022` partout, plus de `*-latest`) sans changer la matrice OS là où elle couvre du packaging ou de la sandbox.
8. **Mesure avant / après** : nombre de jobs, nombre de builds Maven complets, minutes cumulées pour une PR type qui ne touche que `minos-application`, et pour une PR qui ne touche que `docs/`. Ces deux chiffres sont le critère de réussite.

**Piège à éviter, à traiter explicitement** : un job requis par la protection de branche qui cesse de s'exécuter à cause d'un filtre de chemins laisse la PR bloquée en attente — ou, selon la configuration, passe pour vert sans rien avoir vérifié. Pour chaque contrôle requis, dis comment il se comporte quand son filtre ne matche pas, et propose-moi la liste exacte des noms de contrôles requis à mettre à jour dans la protection de branche. Je m'en occupe de mon côté ; tu ne touches pas aux réglages du dépôt.

## G3 — Une politique de retrait, et son application

**Cible** : une règle écrite qui dit quand un artefact de jalon est retiré, l'application de cette règle à l'existant, et un garde-fou qui empêche la réaccumulation.

1. **Inventorie** `scripts/m0` … `scripts/m29`, `scripts/remediation`, et tout vérificateur de jalon. Pour chacun : est-il référencé par un workflow vivant ? par un ADR ou une page de documentation ? quel invariant applique-t-il ? cet invariant est-il déjà couvert par un gate permanent (`check-module-boundaries`, `product-facts`, `check-current-docs`, JaCoCo, tests) ?
2. **Classe** chaque artefact en : **permanent** (il applique un invariant toujours vivant → il rejoint `scripts/quality` ou `scripts/architecture` avec un nom qui ne cite plus un numéro de jalon), **archivé** (valeur historique, plus exécuté → déplacé sous `scripts/history/` ou supprimé, l'historique Git faisant foi), ou **remplacé** (son invariant est couvert ailleurs → supprimé, en nommant le gate qui prend le relais).
3. **Écris la politique** dans un document court — un ADR ou une section de `docs/TOOLCHAIN_POLICY.md`, à toi de proposer : un artefact de jalon est retiré dès que le jalon est clos **et** que son invariant est couvert par un gate permanent ; un gate permanent ne porte jamais un numéro de jalon dans son nom ; ajouter un script de jalon impose d'écrire dès le départ sa condition de retrait.
4. **Garde-fou** : un contrôle qui échoue si un script versionné n'est référencé ni par un workflow, ni par la documentation, ni par la liste des archives assumées. Il doit tourner dans le pipeline consolidé, une seule fois, et avoir son propre auto-test.

## Consigne pour l'agent d'inspection (en parallèle, en continu)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes. Tu n'écris pas de workflow ni de script ; tu écris des constats, transmis immédiatement, et tu tiens à jour `docs/audit/archive/2026-09/CI-HYGIENE-SUIVI.md`.
>
> **Vérifie tes constats contre les fichiers réels du dépôt avant de les déclarer** — un diff lu sur une copie périmée produit de faux bloquants.
>
> Cherche en priorité :
> - **un invariant qui disparaît en silence** : pour chaque suppression, retrouve toi-même où l'invariant est désormais appliqué, sans faire confiance à la table de l'implémenteur ; rejoue une mutation qui devait être rejetée et vérifie qu'elle l'est encore (mutation dans un worktree, puis `git checkout -- .`) ;
> - **un filtre de chemins trop large ou trop étroit** : un gate qui ne s'exécute plus sur un fichier qu'il protégeait, ou un contrôle requis qui ne peut plus se déclencher ;
> - **une régression de sécurité CI** : une action désépinglée, un `pull_request_target` introduit, une expression `github.event` ou `inputs` dans un `run:`, une permission élargie, un secret exposé à un job qui ne l'avait pas, `persist-credentials` laissé par défaut là où le job pousse ;
> - **une annulation de concurrence mal placée** : un groupe qui annulerait une exécution sur `develop`, `main` ou une release ;
> - **la matrice OS** : Windows toujours couvert là où le packaging, l'installeur ou la sandbox en dépendent ;
> - **le garde-fou de G3** : est-il réellement capable d'échouer ? Ajoute un script non référencé dans un worktree et vérifie qu'il sort en erreur ;
> - **le périmètre** : aucun fichier Java touché, aucun seuil de couverture modifié, aucune version épinglée changée.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Un bloquant renvoie l'agent au travail avant qu'il n'avance.

## Validation

Local, sans CI : `python scripts/quality/check-workflow-pins.py`, une validation de syntaxe YAML de chaque workflow, les auto-tests des scripts touchés, et le garde-fou de G3 sur le dépôt réel. Si `actionlint` est disponible, passe-le ; sinon dis-le, ne l'installe pas sans me demander.

Quand tout est en place et relu, tu me le dis et **tu attends mon autorisation** pour ouvrir une PR en brouillon et lancer l'unique exécution de validation.

## Définition de terminé

- une PR déclenche un pipeline unique, chaque gate s'exécutant exactement une fois, avec les chiffres avant/après sur les deux PR types ;
- aucun invariant perdu : la table « ce qui appliquait quoi → ce qui l'applique maintenant » est complète et vérifiée indépendamment ;
- les jobs morts sont supprimés, les runners uniformisés, la concurrence limitée aux PR ;
- la politique de retrait est écrite et appliquée à l'existant, avec son garde-fou testé ;
- aucune action désépinglée, aucune permission élargie, aucun `pull_request_target` ;
- la liste des contrôles requis à mettre à jour dans la protection de branche m'est remise ;
- `docs/audit/archive/2026-09/CI-HYGIENE-SUIVI.md` à jour ; rien n'a été poussé sans mon accord.

---

*Références : `docs/audit/archive/2026-09/AUDIT-2026-09.md` (constats C1 et G3), `.github/workflows/`, `scripts/quality/check-workflow-pins.py`, `scripts/quality/check-jacoco.py`, `scripts/architecture/check-module-boundaries.py`. Méthode et format de suivi : `docs/audit/archive/2026-09/SPRINT-1-SUIVI.md` et `SPRINT-2-SUIVI.md`.*
