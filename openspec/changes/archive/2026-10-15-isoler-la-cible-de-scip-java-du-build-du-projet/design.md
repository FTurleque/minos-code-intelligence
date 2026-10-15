# Design

## État vérifié au HEAD (`74d85e4f`, 2026-10-15)

- `ScipJavaProcessPlanFactory.java:97-98` : `normalizedRunDirectory` et `output = normalizedRunDirectory.resolve("index.scip")`.
- `ScipJavaProcessPlanFactory.java:149-152` : la copie de travail est `<run>/workspace` ; la commande est `standaloneCommand(launcher, output)` ou `classpathCommand(java, launcher, output)`.
- `ScipJavaProcessPlanFactory.java:182-196` : les deux commandes se terminent par `index --output <output>` et ne passent aucun `--targetroot`.
- `LinuxBubblewrapWorkerSandboxBackend.sandboxPlan` lie en écriture la copie de travail, le parent de l'artefact (`<run>`) et le répertoire du run.
- `scip-java-windows-runner.ps1:417-424` : la branche Kotlin passe `--targetroot (Join-Path $output 'scip-java-target')` ; la branche Java appelle `ScipJava index` sans argument, dans le projet lui-même.
- `scip-java index --help` (lanceur de l'image 1.2.0) : « `--targetroot` … the default value … for Maven it's `target/scip-targetroot` ».
- Gates littéraux : aucun gate ne cite les arguments de `index` ni la branche Java du runner (`check-p0-p2.py:334-336` et `check-post-mne.py:171` citent d'autres chaînes de la fabrique, inchangées).

## Décisions

- **D1 : la cible est un frère de l'artefact.** `targetRoot = output.resolveSibling("scip-java-targetroot")`, soit `<run>/scip-java-targetroot`. Ce répertoire est hors de la copie de travail, déjà lié en écriture, et nettoyé avec le run. Aucun nouveau point de montage n'est nécessaire.
- **D2 : les deux commandes prennent la cible en paramètre.** `classpathCommand(java, launcher, output, targetRoot)` et `standaloneCommand(launcher, output, targetRoot)` rendent `… index --output <output> --targetroot <targetRoot>`, avec des chemins absolus normalisés. Les anciennes signatures, privées au paquet et sans autre appelant que la fabrique et ses tests, disparaissent.
- **D3 : runner Windows aligné.** La branche Java passe `--targetroot (Join-Path $output 'scip-java-target')` après avoir supprimé ce répertoire, comme la branche Kotlin.

## Risques

- Un projet qui lit lui-même `target/scip-targetroot` après l'indexation (cas non connu) ne le trouve plus. MINOS ne le lit pas : il ne consomme que `index.scip`.
- La version de scip-java épinglée au catalogue (0.13.1) doit accepter `--targetroot` pour Maven. Preuve : indexation réelle (tâche 2.1).
