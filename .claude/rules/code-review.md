---
paths:
  - "**/*.java"
  - "scripts/**/*.py"
  - "**/*.ps1"
  - "**/*.cmd"
---

# Revue de code (relecteur automatique et humain)

Points propres à MINOS, à vérifier en priorité. Le détail des conventions vit dans `.claude/rules/` : avant de juger un fichier, lire la règle de son type (`tests-java`, `gates-python`, `scripts-windows`, `plugin-intellij`) ; `java-production` et `securite` sont déjà fournies avec ce fichier.

- **Fail-closed d'abord** : un `catch`, un `Optional.empty()`, un `return` par défaut ou un repli « au mieux » autour d'un confinement, d'une empreinte SHA-256, d'un contrôle de propriétaire ou d'un verrou est un défaut probable (le contrôle doit échouer, pas se dégrader). Vérifier l'**ordre** vérification → exécution, pas seulement la présence de la vérification.
- **Tout nouveau lancement de processus** (`ProcessBuilder`, script, `git`, installateur) : environnement issu d'une liste d'autorisation, exécutable résolu par chemin absolu (jamais depuis le répertoire courant ni le dossier du projet ouvert), arguments non réinterprétés par un shell ou `cmd.exe`.
- **Dépôt analysé = hostile** : tout appel JGit qui lit l'index ou le contenu d'un dépôt analysé hors de l'ouverture contrôlée (propriétaire vérifié, `filter.*` neutralisés) est critique.
- **Suivre la valeur jusqu'à la sortie** : une chaîne, une exception ou un `Path` qui atteint la CLI, l'API, le MCP, un JSON ou un journal ne doit révéler ni chemin absolu, ni e-mail d'auteur, ni cause interne, ni secret. Lire le point d'émission, pas seulement le message construit.
- **Surfaces publiques additives** : champ de sortie supprimé, renommé ou changé de type = rupture ; ordre de clés et formats restent déterministes. Un changement de rendu JSON met le plugin IntelliJ dans la portée de la CI et peut exiger un golden.
- **MCP lecture seule** : stdout est réservé au protocole ; `System.out` ou `print` en Java de production est un défaut, et aucun outil MCP n'expose d'écriture. (Dans `scripts/`, `print` est la sortie normale d'un gate.)
- **Parité Windows et Linux** : une branche `os.name` / `isWindows` sans équivalent de l'autre côté, ou un test qui se saute en silence (`if (plateforme) return;` au lieu de `@EnabledOnOs` et `SandboxTestSupport`), affaiblit une garantie. PowerShell : compatible Windows PowerShell 5.1 (pas de `&&`, pas d'API .NET 5+), `$LASTEXITCODE` testé après chaque exécutable natif.
- **Données persistées** (stockage local, PostgreSQL) : un format de fichier, de snapshot ou de schéma modifié doit rester lisible depuis l'existant (migration rejouable) ; écriture atomique sans écraser une cible ; requêtes paramétrées. Concaténer des littéraux SQL constants n'est pas une injection.
- **Architecture** : direction `domain → engine → runtime/storage → adapters → application → surfaces` ; `application` et surfaces ne nomment aucune classe d'adaptateur (câblage dans `minos-bootstrap`) ; pas de nouveau cycle de packages ; un package appartient à un seul module. Une dépendance Maven ajoutée ou une classe déplacée se confronte à `scripts/architecture/check-module-boundaries.py`.
- **Capacité honnête** : une capacité n'est annoncée que si le provider produit les faits correspondants, et une limite se déclare dans la sortie. Signaler un message, un enum ou une valeur par défaut qui promet plus que le code ne fait.
- **Gates littéraux** : renommer ou déplacer une méthode, une classe, un script ou un chemin de documentation sans chercher l'ancien nom dans `scripts/` casse un `check-*.py`. Tout nouveau `test_*.py` ou script à `--self-test` doit être exécuté par le job `invariants`. Python : bibliothèque standard seule.
- **Tests** : un correctif de bug sans test qui échouait avant ; `Thread.sleep` de synchronisation ; dépendance au `PATH`, à `JAVA_HOME`, à la langue ou au fuseau de l'hôte ; test de concurrence non rejoué 50 fois ; classe déplacée sans ses tests dans son propre module (SonarCloud juge la couverture module par module) ; golden régénéré sans justification.
- **Plugin IntelliJ** (`minos-intellij`, Java 21, hors reactor) : aucune dépendance `com.minos:*` ; tâches de fond qui relancent `ProcessCanceledException` et les `Error` ; pas de `catch (Throwable)` dans une action.
- **Dépendances et outils embarqués** : toute entrée ajoutée à `embedded-tools.json` ou à un verrou porte son empreinte SHA-256 épinglée ; une nouvelle dépendance exige une raison écrite.
