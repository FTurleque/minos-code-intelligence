---
name: garde-shell-verification
description: Vérifier un garde qui lit des commandes shell (.claude/hooks/_shell.py) : analyseur du shell, fins de ligne CRLF, entrées aléatoires
metadata:
  type: reference
---

Un garde qui lit une commande shell se vérifie au-delà de son auto-test, qui ne contient que les formes imaginées par son auteur :

- comparer ses commandes `git`/`gh` à celles de l'analyseur de PowerShell (`[System.Management.Automation.Language.Parser]::ParseInput`, puis `FindAll` des `CommandAst`) sur un corpus d'écritures courantes ;
- rejouer chaque forme à plusieurs lignes avec `\r\n` en plus de `\n` ;
- lui donner des entrées aléatoires : une exception dans un hook laisse passer la commande.

Le hook exécuté est celui du dossier principal (`$CLAUDE_PROJECT_DIR`) : le tester dans un arbre de travail en le lançant à la main avec un événement JSON.
