---
paths:
  - "docs/**/*.md"
  - "README.md"
  - "*.md"
---

# Documentation

- **Français**, concise, factuelle, sans promesse de capacité non qualifiée (principe capability-honest).
- **État courant** : `docs/STATUS.md` (livré) et `docs/ROADMAP.md` (progression) ; leurs affirmations sont gardées par `scripts/docs/check-current-docs.py` et `scripts/docs/product-facts.py --check`. Un fait chiffré (nombre de modules, version, seuil) s'écrit une seule fois puis se référence. Rejouer les deux scripts après toute modification de docs courantes.
- **Historique figé** : `docs/history/`, `docs/audit/archive/`, `openspec/changes/archive/` conservent l'état d'époque (anciens SHA, « à valider », anciens nombres). Ne pas les « mettre à jour » ; un lien cassé ou une erreur factuelle se corrige, avec confirmation.
- **ADR** (`docs/adr/`) : Contexte / Décision / Conséquences, statut `Proposed | Accepted | Superseded | Rejected`, ajouté à l'index de `docs/adr/README.md`. Une décision Accepted ne se réécrit pas : compléter « Mise en œuvre » ou écrire l'ADR qui la remplace (commande `/minos:adr`).
- **Audits** : seul l'audit en cours reste à la racine de `docs/audit/` ; à la clôture, déplacement sous `archive/<date>/` par `git mv`, liens relatifs corrigés (commande `/minos:audit-archive`). Aucun lien d'un document suivi vers un dossier non suivi.
- **Liens** : relatifs, vérifiés (aucun lien cassé dans les fichiers modifiés). Références de code sous la forme `chemin:ligne`, relues au HEAD avant d'être citées.
- **Documentation utilisateur** (`docs/user/`) et **développeur** (`docs/developer/`) : une fonctionnalité qui change une sortie, une option ou un diagnostic met à jour la page correspondante dans la même PR.
- Mermaid pour les diagrammes ; `docs/architecture/diagrams/module-dependencies.md` est **généré** par `check-module-boundaries.py --write-doc` (ne pas l'éditer à la main).
