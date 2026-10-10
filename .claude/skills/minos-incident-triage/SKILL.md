---
name: minos-incident-triage
description: Diagnostiquer un incident d'exploitation ou d'installation de MINOS (indexation qui échoue ou reste partielle, code de sortie 3, provider indisponible, outil non installé, MCP muet, plugin IntelliJ en erreur, PostgreSQL ou TLS refusé, sandbox indisponible) en lisant les diagnostics du produit avant de toucher au code. À utiliser quand un utilisateur décrit un symptôme à l'exécution plutôt qu'un changement de code.
---

# Triage d'un incident

Principe : MINOS dit déjà pourquoi. Lire ses diagnostics, ne pas deviner ; ne rien modifier avant d'avoir un symptôme reproduit ou une preuve.

## 1. Collecter (lecture seule)
- Version et plateforme : `minos --version`, OS, JDK (Java 24 requis pour le noyau).
- `minos doctor --format json` (diagnostics par provider et par sandbox ; un préfixe `machine prerequisite (not shipped by MINOS)` désigne une dépendance externe du poste) et `minos tools verify`.
- État d'un projet : `minos index-status <projet>`.
- Journaux : le serveur MCP STDIO écrit sur **stderr** (capturé par le client MCP) ; stdout est réservé au protocole. Plugin IntelliJ : `idea.log`.
- Données : `MINOS_HOME` (et `tools/`, `runs/`, quarantaine de runs) ; ne jamais copier un secret dans un rapport.

## 2. Codes de sortie et sens
Le code **3** signifie *résultat partiel* (inventaire ou index incomplet, entrées abîmées ignorées), pas un échec : lire les limitations de la sortie. Les erreurs d'usage sont actionnables ; les erreurs internes sont volontairement opaques (la cause est dans les journaux de l'opérateur).

## 3. Symptômes fréquents → première hypothèse
| Symptôme | À vérifier d'abord |
|---|---|
| Provider « indisponible » | `doctor` : outil absent, version non conforme, dépendance externe (Node, Go, SDK .NET, JDK complet, Git Bash, PowerShell 5.1, `csc.exe` pour l'indexation Java sous Windows) |
| Indexation Java/TypeScript échoue après installation | `tools verify` (empreintes), classpath scip-java (`tools/…/classpath.txt`), `MINOS_TOOLS_OFFLINE` |
| Sandbox indisponible / rétrogradé | `doctor` : bubblewrap, cgroup v2 (Linux) ; AppContainer / Job Object (Windows) ; les capacités dégradées sont déclarées, jamais silencieuses |
| Statut MCP lent ou erreur opaque | stderr du serveur ; le statut MCP ne touche pas le disque |
| `project add` / `inspect` échoue sur un registre | entrée de registre abîmée : code 3 attendu pour l'inventaire partiel |
| PostgreSQL refusé | URL : mode TLS reconnu (`sslmode=verify-full` en minuscules), mot de passe en fichier (BOM UTF-8 retiré une fois), schéma valide |
| Plugin IntelliJ : « Unknown failure » | `idea.log`, lanceur `minos.cmd` du plugin (chemin documenté `%LOCALAPPDATA%\Programs\MINOS\minos.cmd`), arguments `"` ou `%` refusés |
| Espace disque qui grossit | rétention des runs (`RunDirectoryRetention`), quarantaine, marqueurs de reprise |

Détails : `docs/user/troubleshooting.md`, `docs/user/cli.md`, `docs/user/production-installation.md`, `docs/user/docker-runtime.md`.

## 4. Conclure
Cause établie / probable / inconnue, preuve (commande + sortie), contournement, et — si c'est un défaut — constat pour un changement OpenSpec (skill `minos-audit-sprint`) avec test rouge. Un diagnostic de sécurité suit `SECURITY.md`.
