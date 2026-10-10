---
paths:
  - "scripts/**/*.py"
  - ".claude/scripts/**/*.py"
  - ".claude/hooks/**/*.py"
---

# Gates et scripts Python

- Python 3, **bibliothèque standard seule** (la CI n'installe rien pour les gates ; `yaml` n'est qu'optionnel pour les aides locales).
- Un gate **ancre sur la structure, pas sur des phrases** (ADR 0043) ; les gates `remediation` existants affirment des chaînes littérales : ne pas en ajouter de nouveaux de ce type sans raison.
- Tout gate a un **auto-test** : `test_<nom>.py` ou option `--self-test`. `check-ci-wiring.py` exige que chaque `scripts/**/test_*.py` (hors `history`) et chaque script à `--self-test` soit exécuté par le job `invariants` de `pr-ci.yml` ; un auto-test ajouté à `invariants` peut casser `check-single-execution.py` et `check-audit-remediation-v2.py` (vérifier).
- Squelette commun de `scripts/quality` : `gate_cli.py` (option `--root`, rapport `<LABEL> FAILED: …` sur stderr) ; un nouveau gate de ce dossier l'utilise plutôt que de recopier `main()`. Ce module n'est pas un gate (pas de `check-` dans son nom) ; ses auto-tests sont ceux des gates qui l'importent.
- Un gate qui lit le disque prend une **racine** en paramètre (défaut : racine du dépôt) pour être testable sur arbres temporaires. L'auto-test prouve le **refus** (un cas refusé et un accepté par règle, mutations sur une copie des vrais fichiers), pas seulement le nominal.
- Une liste de tolérance (`*-allowlist.json`, `KNOWN_PACKAGE_CYCLES`) est un **cliquet** : elle ne fait que se resserrer, chaque entrée est nominative avec maximum et justification, une entrée obsolète échoue.
- Séparateurs de chemins normalisés (`/`) pour une sortie identique sous Windows et Linux ; lecture en UTF-8 explicite ; sortie en ASCII ou `PYTHONUTF8=1`.
- Aucun nouveau script nommé d'après un jalon ; les scripts retirés vont dans `scripts/history/` (exclu du garde de références).
- Après modification : exécuter le gate, son auto-test, `check-ci-wiring.py` et `check-single-execution.py`.
