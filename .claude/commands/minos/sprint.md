---
description: Préparer en OpenSpec un sprint de l'audit en cours (analyse, preuves au HEAD, changements, validation, suivi)
argument-hint: "<numéro de sprint>"
---

Prépare le sprint `$ARGUMENTS` de l'audit `docs/audit/2026-10-10/` en suivant le skill `minos-audit-sprint`.

Cadre (comme pour les sprints 1 et 2) : tout en français ; **aucun** code, test, workflow ni gate modifié ; ni push, ni PR, ni CI sans demande ; aucun ADR créé (nommé et planifié) ; ne pas traiter les constats d'autres sprints.

1. Lire `sprints.md` (section du sprint, critère de sortie) et extraire les constats de `findings.json`.
2. Revérifier chaque preuve au HEAD et relever les écarts avec l'action proposée (agent `minos-spec-verifier` pour la contre-vérification).
3. Découper, scaffolder (`openspec new change`), rédiger, `openspec validate --all --strict`.
4. Mettre à jour `docs/audit/2026-10-10/SUIVI.md`.
5. Rapport final court : statut par constat, découpage, décisions en attente, tâches manuelles, commande du premier changement (`/opsx:apply <nom>`).
