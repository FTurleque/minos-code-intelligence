---
name: superviseur-imbrication-python
description: "Constats « imbrique N niveaux de blocs » du superviseur de code sur les .py : mesurés sur l'indentation, lignes de continuation comprises ; vérifier à l'AST avant de modifier"
metadata:
  type: reference
---

Le superviseur de code (plugin `code-supervisor`, `resources/supervisor/rules_quality.py`, `max_nesting`, version 1.4.0)
mesure l'imbrication d'un fichier Python par l'indentation de chaque ligne du corps, lignes de continuation alignées sous
une parenthèse comprises. Un tuple de variantes ou un message continué donne « 5 à 10 niveaux » pour 0 à 2 blocs réels.

**Why:** reformater le code pour faire taire cette mesure n'améliore rien et revient à contourner un contrôle.

**How to apply:** compter les blocs avec `ast` avant de corriger un constat d'imbrication Python ; la correction de la
mesure appartient au dépôt du plugin (`ai-toolkit-registry`), pas à MINOS. Relecture manuelle d'un fichier :
`python <plugin>/resources/supervisor.py --check <chemins>`.
