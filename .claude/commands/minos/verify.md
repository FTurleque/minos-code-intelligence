---
description: Vérifier localement le travail en cours (portée choisie selon les fichiers modifiés) et rapporter des preuves exécutées
argument-hint: "[module Maven | plugin | docs | tout]"
---

Applique le skill `minos-verify-local` au travail en cours.

Argument : `$ARGUMENTS` (vide = déduire la portée de `git status` et `git diff --stat` contre `develop`).

1. Lister les fichiers modifiés et choisir la portée minimale selon le tableau du skill.
2. Exécuter, dans l'ordre : gates (`python .claude/scripts/run_gates.py --fast`), puis build/tests ciblés (`./mvnw -B -ntp -pl <module> -am test` ; plugin : Gradle dans `minos-intellij/`), puis `openspec validate --all --strict` si `openspec/` est touché. Respecter les pièges Windows (arguments `-D…` cités, `powershell.exe` 5.1 dans le `PATH`).
3. Rapporter par commande : résultat, tests exécutés / échecs / sautés. Dire ce qui n'a **pas** été exécuté (Linux, Docker, PostgreSQL, CI) et pourquoi.
4. Ne pas pousser, ne pas ouvrir de PR.
