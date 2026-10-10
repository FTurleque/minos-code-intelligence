---
description: Rejouer les gates statiques du job invariants de la CI (rapide ou complet) et expliquer les échecs
argument-hint: "[rapide|complet|<texte à filtrer>]"
allowed-tools: Bash(python .claude/scripts/run_gates.py*), Bash(python scripts/*), Read, Grep
---

Rejoue les gates statiques de MINOS, ceux du job `invariants` de `pr-ci.yml`.

Argument : `$ARGUMENTS`
- vide ou `rapide` → `python .claude/scripts/run_gates.py --fast` (gates sans auto-tests, ~10 s) ;
- `complet` → `python .claude/scripts/run_gates.py` (avec auto-tests, ~1 min) ;
- autre texte → `python .claude/scripts/run_gates.py --only "<texte>"`.

Ensuite :
1. Donne le décompte `N/M` et la liste des échecs.
2. Pour chaque échec, ouvre le gate et le fichier qu'il cite et explique **quelle assertion** échoue et pourquoi (voir le skill `minos-literal-gates`). Ne corrige pas sans que l'utilisateur le demande, sauf s'il s'agit de ta propre modification en cours.
3. Dis ce que ces gates ne couvrent pas : builds Maven/Gradle, tests, JaCoCo, Sonar, jobs Linux/Docker (renvoi à `/minos:verify`).
