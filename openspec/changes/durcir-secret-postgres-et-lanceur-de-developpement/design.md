# Design

## Context

Sprint 2 de l'audit du 10 octobre 2026 : **AUD-SEC-12** et **AUD-SEC-13**. HEAD analysé : `465e970d` (`develop`). Ce sont deux défauts de scripts Windows, indépendants du reste du reactor ; ils partagent une même méthode de preuve (extraire les définitions réelles ou lancer le vrai script sous Windows).

## État vérifié au HEAD

### AUD-SEC-12 — confirmé

- `scripts/install/configure-runtime-settings.ps1:71-75` : `Restrict-FileToCurrentUser` lance `icacls.exe $Path /inheritance:r /grant:r "*${Sid}:F"` et lève si `$LASTEXITCODE -ne 0`. `:236-238` : `[System.IO.File]::WriteAllText($SecretPath, $Secret + …)` **puis** `Restrict-FileToCurrentUser $SecretPath`. Le dossier parent `secrets\` est créé par `New-Item -ItemType Directory -Force` (ACL héritée de `DataRoot`, aucune restriction).
- `docker/scripts/configure-m30-docker-services.ps1:110-131` (`New-ManagedPassword`) : si le fichier existe, il est lu (`Read-BoundedUtf8`) et accepté **sans contrôle d'ACL** (`:113-116`) ; sinon `WriteAllText` (`:127`) puis `icacls` (`:128-130`) avec message « Unable to restrict PostgreSQL secret ACL » ; le fichier reste en place si `icacls` échoue.
- Ces deux scripts ne partagent **aucun** code (la séquence est dupliquée en ligne). Les scripts sont livrés par des listes de fichiers explicites : `build-windows-distribution.ps1:327-345` (copie nominative), `update-installation.ps1:265` (charge utile de la mise à jour). Un fichier partagé exigerait de toucher les deux.
- Gates littéraux qui lisent ces scripts : `check-post-mne.py:196-201` exige `Assert-ExternalPostgresUrl`, `sslmode=verify-full`, `Read-BoundedUtf8`, `'minos.postgres.managed'] = 'false'`, « PostgresUrl contains unsupported parameter » (script d'installation) et `Read-BoundedUtf8`, `'minos.postgres.managed'] = 'true'` (script Docker). Ces chaînes doivent rester.
- Aucun test existant ne couvre ces fonctions (aucun Pester ; `scripts/lib/test_minos_exit_code.py` est le seul modèle : il extrait les vraies définitions par l'analyseur PowerShell).
- Atténuation constatée par l'audit, confirmée : le défaut `%LOCALAPPDATA%\MINOS\data` hérite d'une ACL propre à l'utilisateur.
- **Observation hors périmètre** : l'installateur écrit d'abord le mot de passe dans `{tmp}\minos-postgres-password.txt` (`minos-installer.iss.template:529-531`) pour le passer à `-PostgresPasswordSourcePath`, puis le supprime (`:561`). `{tmp}` est le dossier temporaire d'Inno Setup sous le profil de l'utilisateur (`PrivilegesRequired=lowest`), donc privé ; la fenêtre existe mais ne touche pas un `DataRoot` partagé.
- **Non mesuré ici** : le comportement des deux scripts avec un `DataRoot` réel sur `D:\` (le poste n'en a pas) ; la tâche 3.1 le prouve avec un dossier d'ACL volontairement large.

### AUD-SEC-13 — confirmé, **reproduit** (la preuve de l'audit était « plausible »)

- `minos.cmd:9` : `java -jar "%MINOS_JAR%" %*`. `MinosCliClient.java:142` : `if (Files.isDirectory(directory)) builder.directory(directory.toFile());` (le répertoire du projet).
- Mesure du 2026-10-10 sur ce poste (Windows 10, `cmd.exe`) : dans un dossier contenant `java.bat` (qui affiche `PWNED-BY-CWD`), `java -version` exécute **`java.bat`** quand la variable `NoDefaultCurrentDirectoryInExePath` est absente, et exécute le vrai `java` quand le lanceur la fixe à `1` avant l'appel. **Piège de reproduction** : cette variable est déjà définie à `1` dans l'environnement du poste de développement (c'est pourquoi le défaut ne s'y voit pas sans l'effacer) ; un test doit donc la retirer explicitement de l'environnement qu'il transmet.
- Le lanceur distribué est sain : `build-windows-distribution.ps1:345-352` écrit `"%~dp0app\minos.exe" %*` (chemin absolu).
- Gates qui citent `minos.cmd` : `scripts/docs/check-current-docs.py:237-246` (chemin documenté de l'installation), `scripts/lib/test_minos_exit_code.py` (faux exécutable) ; aucun ne lit le contenu de `minos.cmd` de la racine.
- Autre point de départ : le réglage par défaut du plugin est `minos.cmd` résolu par le `PATH` (`MinosSettingsState.java:83`), donc normalement le lanceur **distribué**. Le défaut n'apparaît que si l'utilisateur pointe le plugin vers le `minos.cmd` de la racine du dépôt MINOS (poste de développement) ; le périmètre de l'audit est confirmé.

## Goals / Non-Goals

**Goals** : le mot de passe ne touche jamais le disque avec une ACL plus large que l'utilisateur courant, et un échec de restriction ne laisse rien derrière lui ; un fichier de mot de passe existant est revérifié ; `minos.cmd` ne lance jamais un `java` pris dans le répertoire courant ; des tests Windows le prouvent.

**Non-Goals** : durcir l'installateur Inno ; restreindre le dossier `secrets\` ; centraliser la logique ; contrôler l'ACL à l'usage.

## Decisions

### D1. Créer vide, restreindre, vérifier, puis écrire

Séquence unique, valable sous Windows PowerShell 5.1 (installateur) et `pwsh` (poste de développement, CI) :

1. créer le fichier **vide** (`New-Item`), aucun secret en mémoire disque ;
2. appliquer `icacls /inheritance:r /grant:r *SID:F` et exiger `$LASTEXITCODE -eq 0` ;
3. **vérifier** : l'ACL n'a plus d'héritage et ne contient qu'une entrée d'autorisation, celle du SID courant ;
4. écrire le secret dans le fichier existant (un `WriteAllText` sur un fichier existant conserve son ACL) ;
5. à toute erreur des étapes 2 à 4 : supprimer le fichier (`Remove-Item -Force`), puis lever.

Alternative écartée : passer un `FileSecurity` à la création (`File.Create(path, size, options, security)`) : l'API est propre à .NET Framework (Windows PowerShell 5.1) et n'existe pas dans le `pwsh` du poste de développement ; deux chemins de code à tester au lieu d'un.
Alternative écartée : restreindre le dossier `secrets\` en plus (défense en profondeur) : coupe l'héritage d'un dossier que d'autres composants (mise à jour transactionnelle, désinstallation, conteneurs en liaison) manipulent ; gain faible puisque chaque fichier est déjà restreint ; à rouvrir si un second secret y est un jour déposé.

Le mode de vérification (étape 3) est mesuré dans les **deux** runtimes avant d'être figé : `Get-Acl` dépend du module `Microsoft.PowerShell.Security` (le test de `PrivateLocalStorageWindowsAclTest` a déjà échoué quand le `PSModulePath` de `pwsh` polluait Windows PowerShell) ; l'analyse de la sortie d'`icacls` (SID d'identité courante) ou `FileInfo.GetAccessControl()` sont les repli. Le critère d'acceptation, lui, est fixe : héritage coupé, une seule entrée, le SID courant.

### D2. Corriger en place, sans fichier partagé — **décision en attente du propriétaire**

La séquence D1 est écrite deux fois (`configure-runtime-settings.ps1`, `configure-m30-docker-services.ps1`) comme elle l'est déjà, et testée sur les deux. Recommandation : **en place**. Mutualiser exigerait d'ajouter un fichier aux listes de livraison (`build-windows-distribution.ps1`, `update-installation.ps1:265`, `verify-windows-upgrade-transaction.ps1`) et de prouver la mise à jour transactionnelle : un risque disproportionné pour un constat « faible ». Si le propriétaire préfère mutualiser, la tâche 1.2 devient « créer `scripts/install/PrivateSecretFile.ps1` et l'ajouter aux trois listes ».

### D3. Un fichier existant est restreint de nouveau puis vérifié, jamais accepté tel quel

`New-ManagedPassword` (Docker) : si le fichier existe, on applique de nouveau `icacls` et on vérifie (étapes 2 et 3 de D1) avant de le lire ; l'échec de la vérification lève sans modifier le contenu. Le script d'installation écrase un fichier existant (il le fait déjà) : l'étape 1 devient « supprimer puis créer vide » pour ne pas écrire dans un fichier dont l'ACL est inconnue. Le contenu d'un fichier trouvé avec une ACL large doit être considéré comme exposé : le message d'erreur le dit et conseille de changer le mot de passe.

### D4. `minos.cmd` : `JAVA_HOME` d'abord, sinon recherche dans `PATH` seulement

Deux lignes dans le lanceur, derrière `setlocal` (déjà présent) pour que rien ne fuie vers l'appelant :

- si `%JAVA_HOME%\bin\java.exe` existe, il est appelé par chemin complet ;
- sinon `set "NoDefaultCurrentDirectoryInExePath=1"` avant `java`, ce qui supprime la recherche dans le répertoire courant (mesuré ci-dessus).

L'audit propose `%JAVA_HOME%\bin\java.exe` « ou » `where` en excluant `.` ; `where` cherche **aussi** dans le répertoire courant, donc n'est pas retenu. La variable `NoDefaultCurrentDirectoryInExePath` se propage au `java` lancé (donc aux processus qu'il crée) : effet voulu ; à noter dans le commentaire du lanceur.

### D5. Tests : Python + extraction des vraies définitions, pas Pester

Deux fichiers `test_*.py` (nom imposé par `check-ci-wiring.py`, qui exige que chaque `scripts/**/test_*.py` soit câblé dans `invariants`) : ils se déclarent **sautés avec leur raison** hors Windows et s'exécutent dans un nouveau pas `runner.os == 'Windows'` du job `verify` (`Verify (windows-2022)`, exigé par le ruleset). Ce choix évite d'introduire Pester (nouvelle dépendance) et suit `scripts/lib/test_minos_exit_code.py`. Alternative écartée : tests JUnit `@EnabledOnOs(WINDOWS)` dans un module Java (précédent : `PrivateLocalStorageWindowsAclTest` dans `minos-engine`) : le code sous test est un script, pas du Java, et le seam d'injection d'échec d'`icacls` est plus simple côté PowerShell.

- `test_secret_file_acl.py` : extrait `Restrict-FileToCurrentUser` / la nouvelle fonction d'écriture des deux scripts ; cas nominal (ACL vérifiée **avant** l'écriture : une sonde lit l'ACL au moment où le contenu arrive, ce qui exige un seam `-BeforeWrite` ou un test qui observe le fichier vide), échec injecté de la restriction (fichier supprimé, secret jamais écrit), fichier existant à ACL large (restreint puis accepté), fichier existant non restreignable (échec sans changer le contenu), dossier parent d'ACL large.
- `test_dev_launcher.py` : copie `minos.cmd` dans un arbre temporaire avec un faux `target\minos-code-intelligence-9-all.jar`, un `java.bat` piégé dans le répertoire courant (et la variable retirée de l'environnement transmis), un faux `java` dans un dossier du `PATH` ; asserte que le `java` du `PATH` ou de `JAVA_HOME` s'exécute et que le piège ne s'exécute pas.

## Risks / Trade-offs

- `icacls` retire l'héritage : l'administrateur de la machine ne lit plus le fichier sans reprendre la propriété (comportement déjà actuel pour le fichier restreint ; inchangé).
- La vérification de l'ACL peut diverger entre runtimes (module de sécurité) : D1 la mesure dans les deux.
- `NoDefaultCurrentDirectoryInExePath` peut surprendre un développeur qui compte sur un `java.bat` local ; c'est précisément le comportement refusé.

## Windows et Linux

Windows seulement. Les tests se déclarent sautés sous Linux (étape `invariants`) avec la raison, et tournent sur `Verify (windows-2022)`. Les scripts `.ps1` restent en CRLF (`.gitattributes`).

## Qualification de la capacité

Écriture protégée du secret : **qualifiée** après la tâche 3 (preuve sur Windows PowerShell 5.1 et `pwsh`). Lanceur de développement : **qualifié** pour `cmd.exe` Windows ; hors Windows non applicable (le plugin utilise `minos`, non `minos.cmd`, `MinosSettingsState.java:83`).
