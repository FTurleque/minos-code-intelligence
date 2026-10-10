---
name: minos-audit-sprint
description: Préparer en OpenSpec, puis suivre, un sprint de l'audit en cours (docs/audit/2026-10-10) — extraire les constats du sprint, revérifier chaque preuve au HEAD, relever les écarts avec l'action proposée, découper en changements, rédiger proposal/design/specs/tasks, valider en mode strict et tenir le SUIVI. À utiliser quand l'utilisateur dit « prépare le sprint N », « spécifie les constats », « avance sur l'audit », ou demande l'état d'avancement de l'audit.
---

# Sprint d'audit → OpenSpec

Sources : `docs/audit/2026-10-10/` — `sprints.md` (contenu et critère de sortie de chaque sprint), `findings.json` (champs `id`, `titre`, `preuve`, `impact`, `action`, `confiance`, `connu`, `bloque`, `dep`), `findings.md`, `SUIVI.md` (état), `plan-action.md`. Le dossier de l'audit ne se modifie pas, sauf `SUIVI.md`.

## Contraintes de la spécification
- Tout est **en français**. **Aucun** code de production, test, workflow ou gate n'est modifié pendant la spécification.
- Pas de push, de PR ni de CI sans demande. Pas d'ADR créé : il est **nommé** dans le design et planifié en tâche.
- Ne pas traiter les constats d'autres sprints (les citer comme bénéficiaires seulement).
- Respecter `openspec/config.yaml` et `.claude/rules/openspec.md`.

## Étapes
1. **Extraire** les constats du sprint (script jetable lisant `findings.json`) et lire en entier : preuve, impact, action, dépendances (`dep`, `bloque`), niveau de confiance.
2. **Revérifier chaque preuve au HEAD** : relire les `fichier:ligne` cités (les numéros dérivent), compter, reproduire quand c'est possible (commande, mesure), lire la version **réellement embarquée** d'une dépendance quand l'audit en a lu une autre. Noter ce qui **n'a pas été** reproduit.
3. **Relever les écarts avec l'action proposée** : une action qui casse un usage (ex. supprimer la remontée Git), qui est inversée (ex. mauvais binding SLF4J), inapplicable (liste Windows réutilisée sous Linux), ou plus étroite que le défaut réel (sites oubliés). Les écrire dans le design avec la preuve.
4. **Vérifier les recouvrements** : capacités existantes (`openspec/specs/`), changements actifs (`openspec list`), ADR, gates littéraux qui lisent les fichiers à modifier, scopes JaCoCo, packaging (listes de fichiers livrés).
5. **Découper** en changements fusionnables seuls (un thème, un module ou une plateforme par changement ; plusieurs lots indépendants dans un même changement si le thème est commun). Justifier le découpage.
6. **Scaffolder** : `openspec new change <nom-en-kebab-case>` (verbe à l'infinitif), puis écrire `proposal.md`, `design.md` (« État vérifié au HEAD », écarts, décisions D1…Dn **avec recommandation**, Windows et Linux, qualification), `specs/<capacité>/spec.md`, `tasks.md` (rouge → correction → preuve, `AUD-…` par tâche, tâches **(manuelles)**, gates à rejouer, section « Clôture »).
7. **Valider** : `openspec validate --all --strict` ; corriger (exigence > 500 caractères, scénario manquant…).
8. **Tenir le SUIVI** : ajouter la section du sprint dans `docs/audit/2026-10-10/SUIVI.md` (statut, écarts constatés, décisions en attente, ordre d'implémentation proposé).
9. **Rapport final court** : statut par constat, découpage choisi, décisions en attente, tâches manuelles, commande pour lancer le premier changement (`/opsx:apply <nom>`).

## Implémenter ensuite (`/opsx:apply`)
Une tâche à la fois, rouge d'abord, preuve notée dans `tasks.md` et `SUIVI.md` ; un changement = une branche = une PR ; clôture : tâche finale, `openspec validate`, archivage (`/opsx:archive`), `docs/STATUS.md` et `ROADMAP.md`.

## Pièges rencontrés
Un `git add -A docs` indexe des dossiers non suivis ; les exigences > 500 caractères échouent en `--strict` ; `openspec archive` écrit un « Purpose » provisoire à réécrire pour une capacité nouvelle ; une case de tâche cochée sans preuve est une erreur ; un check exigé qui n'a jamais tourné bloque le ruleset.
