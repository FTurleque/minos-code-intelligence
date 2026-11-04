---
name: reference-review-context-sans-texte
description: archgate review-context (0.18) ne renvoie que id/titre/domaine des ADR, pas Decision ni Do's and Don'ts
metadata:
  type: reference
---

Avec Archgate 0.18, `archgate review-context --run-checks` renvoie pour chaque ADR seulement `id`, `title`, `domain`, `rules` : aucune clé de décision ni de Do's and Don'ts. Les ADR couvrent `**/*`, donc chaque domaine reçoit tous les fichiers modifiés.

**How to apply:** pour la revue, lire soi-même les sections `## Decision` et `## Do's and Don'ts` des ADR pertinents dans `docs/architecture/adr/` (ou `archgate adr show <id>`) et les fournir aux relecteurs ; ne pas leur transmettre un briefing construit depuis le JSON, il serait vide.
