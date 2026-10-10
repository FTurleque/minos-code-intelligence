---
description: Préparer en OpenSpec un sprint de l'audit en cours (preuves revérifiées au HEAD, écarts, découpage, validation, suivi)
argument-hint: "<numéro de sprint>"
---

Prépare le sprint `$ARGUMENTS` de l'audit `docs/audit/2026-10-10/` (produit par le skill général `audit-application` ; cette commande en est la suite : de l'audit aux changements OpenSpec). Les skills `openspec-*` fournissent la mécanique ; ce qui suit est ce que MINOS y ajoute.

**Cadre** : tout en français ; **aucun** code, test, workflow ni gate modifié ; ni push, ni PR, ni CI sans demande ; aucun ADR créé (nommé dans le design, planifié en tâche) ; ne pas traiter les constats d'autres sprints (les citer comme bénéficiaires) ; respecter `.claude/rules/openspec.md`. Le dossier de l'audit ne se modifie pas, sauf `SUIVI.md`.

1. **Extraire** les constats du sprint : `sprints.md` (contenu, critère de sortie) et `findings.json` (`id`, `titre`, `preuve`, `impact`, `action`, `confiance`, `connu`, `bloque`, `dep`), lus en entier.
2. **Revérifier chaque preuve au HEAD** : relire les `fichier:ligne` (ils dérivent), refaire les décomptes, reproduire quand c'est possible, lire la version **réellement embarquée** d'une dépendance. Noter ce qui n'a pas été reproduit. Agent `minos-spec-verifier` pour la contre-vérification.
3. **Relever les écarts avec l'action proposée** : action qui casse un usage, inversée, inapplicable sur une plateforme, ou plus étroite que le défaut réel (sites oubliés). Les écrire dans le design avec la preuve.
4. **Vérifier les recouvrements** : capacités (`openspec/specs/`), changements actifs (`openspec list`), ADR, gates littéraux qui lisent les fichiers à modifier, scopes JaCoCo, listes de fichiers livrés.
5. **Découper** en changements fusionnables seuls (un thème, un module ou une plateforme par changement) et justifier le découpage.
6. **Rédiger** (`openspec new change <nom-à-l'infinitif>`) : proposal, design (« État vérifié au HEAD », écarts, décisions D1…Dn avec recommandation, Windows et Linux, qualification), specs, tasks (rouge → correction → preuve, `AUD-…` par tâche, tâches **(manuelles)**, gates à rejouer, « Clôture »).
7. `openspec validate --all --strict`, puis ajouter la section du sprint à `SUIVI.md` (statut, écarts, décisions en attente, ordre d'implémentation).
8. **Rapport final court** : statut par constat, découpage, décisions en attente, tâches manuelles, commande du premier changement (`/opsx:apply <nom>`).

Pièges déjà rencontrés : `git add -A docs` indexe des dossiers non suivis ; une exigence > 500 caractères échoue en `--strict` ; `openspec archive` écrit un « Purpose » provisoire à réécrire pour une capacité nouvelle ; une case cochée sans preuve est une erreur ; un check exigé qui n'a jamais tourné bloque le ruleset.
