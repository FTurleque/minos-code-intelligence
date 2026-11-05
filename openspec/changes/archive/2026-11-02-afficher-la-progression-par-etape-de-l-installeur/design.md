# Design

## Context

Demande produit : une barre par étape de la configuration post-installation de l'installeur Windows, pour que l'utilisateur voie chaque étape passer au vert. HEAD analysé : `b908b249` (`develop`). `ISS` = `packaging/windows/minos-installer.iss.template`. Inno Setup 6.7.1 est épinglé par `.github/workflows/release-windows.yml:38`. Le poste de mesure a Inno Setup 6.7.3 (moteur rapporté par `ISCC.exe`, alors que les métadonnées Chocolatey indiquent 6.7.1) et Inno Setup 7.

## État vérifié au HEAD

- `ISS:1306-1318` : `CurStepChanged(ssPostInstall)` enchaîne `AddMinosToUserPath`, `ConfigureRuntimeSettings`, `PersistInstallerRegistry`, `ConfigureSelectedMcpBackend`, puis `ConfigureMcpClients` si le backend a réussi, sinon `RevertRuntimeSettingsToLocal`.
- Les trois étapes longues sont lancées par **`Exec`** (et non `ExecAndLogOutput`, absent du modèle) avec `SW_HIDE` et `ewWaitUntilTerminated` : `ISS:553`, `ISS:659`, `ISS:701`. Le seul retour visuel est `WizardForm.StatusLabel.Caption` (`ISS:508`, `ISS:632-634`, `ISS:688`), exigé par `scripts/install/verify-installer-template.ps1` (`Require 'WizardForm.StatusLabel.Caption :='`).
- Toutes ces étapes sortent immédiatement en mode smoke (`IsSmokeBuild`, `ISS:507`, `ISS:630`, `ISS:687`).
- Backend : `scripts/install/switch-mcp-backend.ps1` réutilise un runtime Docker de même version (`:307-311`, `Start` puis `Validate`) ou appelle le configurateur dans le même processus (`& $DockerConfiguratorPath @DockerParameters`, `:334`), puis exécute le handshake (`:355`). Le configurateur est un point d'injection de test (`-DockerConfiguratorPath`, `:41`).
- `docker/scripts/configure-docker-mcp.ps1` : flux de base (`Invoke-DockerWorkflow -Action Install`, `:155-161`, qui construit les images par `docker build`, `docker/scripts/mcp-lifecycle.ps1:546`), puis `configure-m30-docker-services.ps1` dans le même processus si PostgreSQL ou Ollama est choisi (`:163-179`), sinon `Start` (`:203`).
- `docker/scripts/configure-m30-docker-services.ps1` : PostgreSQL (`:444-448`, `up --wait` puis `Sync-ManagedPostgresPassword`), Ollama (`:449-451`), téléchargement du modèle (`:475`, sortie de `ollama pull` capturée entière par `Out-String`, utilisée seulement dans le message d'échec `:479`).
- `scripts/install/configure-mcp-clients-setup.ps1` : les quatre clients gérés sont configurés en un appel (`:41-43`), Codex en un second (`:44-53`) ; le résultat par client est vérifié ensuite dans l'état (`:69-75`, `:77-89`).
- `M29InstallerBackendLifecycleContractTest.shippedPowerShellScriptsAreAsciiOnly` impose l'ASCII aux scripts livrés ; `check-post-mne.py` affirme des littéraux de `configure-m30-docker-services.ps1`.

### Écarts avec la conception envisagée par la demande

1. **`ExecAndLogOutput` n'est pas employé au HEAD** : les étapes passent par `Exec`.
2. **`ExecAndLogOutput` ne convient pas aux étapes longues.** Mesures faites le 31 octobre 2026 avec Inno Setup 6.7.3, sur des installeurs d'essai compilés par `ISCC.exe` hors dépôt ; non reproduites en 6.7.1 :
   - les lignes arrivent bien au rappel `OnLog` pendant l'exécution (trois lignes espacées de 2 s reçues à 2 s d'intervalle) ;
   - mais la sortie est drainée lentement : 3 000 lignes de 100 octets en 7 s, 30 000 en 48 s (environ 64 Kio/s) ; un processus qui écrit 15 Mio reste bloqué sur son écriture, sans consommation CPU, au-delà de 90 s.
   - Or `ExecAndLogOutput` redirige stdout **et** stderr du processus et de tous ses fils, et `docker build` écrit sa progression sur stderr pendant toute la préparation de l'image. La construction serait ralentie, voire bloquée, et une sortie trop longue couperait le flux (limite documentée de 10 millions d'octets).
3. **Changer les descripteurs des scripts n'est pas neutre** : sous Windows PowerShell 5.1, la redirection de stderr d'une commande native la transforme en erreur terminale (`configure-m30-docker-services.ps1:238-242`). Le transport retenu ne touche à aucun descripteur.

## Goals / Non-Goals

**Goals** : une rangée par étape sélectionnée, visible pendant l'exécution ; un état final fidèle au code de sortie ; aucune régression du cycle transactionnel ni du mode smoke.

**Non-Goals** : estimer une durée ; afficher un pourcentage qu'aucun outil ne fournit ; modifier les messages d'erreur ou les journaux.

## Decisions

### D1 — Transport : fichier de progression relu par un minuteur

Les scripts reçoivent `-ProgressPath <fichier>` (dans `{tmp}` de l'installeur) et y **ajoutent** des lignes du protocole. Avant chaque `Exec` d'une étape, l'installeur vide le fichier et arme un minuteur Windows (`SetTimer` de `user32.dll`, rappel obtenu par `CreateCallback`, période 250 ms). `Exec` traite les messages pendant l'attente : le rappel relit le fichier, applique les lignes, et l'interface se redessine. Après `Exec`, le minuteur est arrêté (`KillTimer`) et le fichier relu une dernière fois. Mesure du 31 octobre 2026 : lignes écrites à 2 s d'intervalle vues par le rappel à 2 s d'intervalle, en `/SILENT` comme en `/VERYSILENT`.

- Les processus fils gardent leurs descripteurs : aucun effet sur `docker build`, Compose ou `ollama pull`.
- Le fichier est **relu en entier** à chaque tick et seules les lignes terminées par un saut de ligne sont appliquées : une ligne en cours d'écriture n'est jamais lue tronquée, et l'application est idempotente.
- Écriture côté script : `System.IO.File.AppendAllText` en ASCII, avec quelques nouvelles tentatives brèves si le fichier est momentanément ouvert par l'installeur. Une écriture qui échoue malgré tout n'interrompt pas l'installation : l'état final vient du code de sortie (D3).
- Le paramètre est transmis explicitement de script à script (`switch-mcp-backend.ps1` → `configure-docker-mcp.ps1` → `configure-m30-docker-services.ps1`), et seulement s'il est renseigné, pour qu'un configurateur de test injecté sans ce paramètre reste appelable. Aucune variable d'environnement n'est ajoutée.

Alternative écartée : `ExecAndLogOutput` (écarts 2 et 3).

### D2 — Protocole et étapes

Ligne : `MINOS-PROGRESS step=<id> state=<état>` suivie, facultativement, de ` percent=<n>` (entier de 0 à 100).

| Identifiant | Rangée | Émise par | Présente si |
|---|---|---|---|
| `runtime` | Paramètres d'exécution MINOS | installeur | toujours (hors smoke) |
| `docker-image` | Image Docker MINOS | `configure-docker-mcp.ps1`, `switch-mcp-backend.ps1` (réutilisation) | backend Docker |
| `postgres` | PostgreSQL géré | `configure-m30-docker-services.ps1` | Docker + PostgreSQL |
| `ollama` | Service Ollama géré | `configure-m30-docker-services.ps1` | Docker + Ollama |
| `ollama-model` | Modèle Ollama | `configure-m30-docker-services.ps1` | Docker + Ollama + provisionnement coché |
| `handshake` | Validation du backend MCP | `switch-mcp-backend.ps1` | un backend choisi |
| `client-copilot-jetbrains`, `client-copilot-cli`, `client-claude-code`, `client-claude-desktop`, `client-codex` | un client IA | `configure-mcp-clients-setup.ps1` | case cochée et active |

États : `running`, `done`, `skipped` (étape non refaite : runtime Docker existant réutilisé). `pending` et `failed` ne sont posés que par l'installeur.

Les émetteurs n'acceptent qu'un identifiant de la forme `^[a-z][a-z0-9-]{0,47}$`, un état de la liste et un entier ; toute autre valeur est ignorée sans écriture. L'installeur n'applique une ligne que si elle respecte exactement la grammaire et si l'identifiant correspond à une rangée créée.

### D3 — État final

À la fin d'un `Exec` :

- code 0 : les rangées du groupe restées `running` passent `done` ; une rangée restée `pending` reçoit « non signalée » (barre vide) plutôt qu'un vert non prouvé ;
- code non nul : les rangées `running` passent `failed` (rouge) ; si aucune ne l'est, la première rangée `pending` du groupe passe `failed`, et les suivantes restent « non exécutée » ; si toutes étaient déjà `done` (échec à la validation finale ou au retour arrière), la dernière rangée du groupe passe `failed`. Le `MsgBox` existant garde la cause.
- Étapes non lancées (backend en échec, donc clients non configurés) : « non exécutée ».

Pour les clients, le script émet `done` par client seulement après la vérification de l'état (`configure-mcp-clients-setup.ps1:69-89`) et `failed` n'est pas émis : un client manquant fait échouer le script, et D3 marque alors les rangées encore `running` en échec.

### D4 — Pourcentage honnête

Seul `ollama pull` fournit un avancement. `configure-m30-docker-services.ps1` lit sa sortie au fil de l'eau (même commande, même gestion de stderr, même code de sortie ; la sortie reste accumulée pour le message d'échec existant), découpe sur `\r` et `\n`, retire les séquences ANSI et prend le dernier `NN%` d'une ligne ; un pourcentage n'est émis que s'il change. L'avancement est celui de la couche en cours, tel qu'Ollama l'affiche. Si Ollama n'imprime aucun pourcentage lisible (sortie sans terminal), aucun n'est émis et la barre reste indéterminée : **capacité partielle**, qualifiée par la recette manuelle (tâche 5.2).

Toutes les autres étapes : barre indéterminée (`npbstMarquee`) pendant `running` — **pourcentage non supporté**, déclaré.

### D5 — Présentation

Les rangées sont placées sous la barre d'Inno Setup (`WizardForm.ProgressGauge`) sur `WizardForm.InstallingPage` : libellé à gauche (nom et état), barre à droite. Hauteur de rangée : `ScaleY(20)`, réduite si nécessaire pour que toutes les rangées tiennent (page mesurée : 516 × 309 px à 96 ppp, barre d'Inno terminée à 63 px ; onze rangées au maximum). `done` = barre pleine, état normal (vert) ; `failed` = `npbsError` (rouge) ; `skipped` = pleine, `npbsPaused`. Le libellé d'état global (`StatusLabel`) est conservé.

### D6 — Secrets et mode smoke

- Une ligne de protocole ne contient qu'un identifiant de la liste, un état et un entier : ni chemin, ni nom de modèle, ni URL, ni mot de passe. Les fonctions d'émission n'ont aucun paramètre de texte libre.
- L'installeur n'écrit dans son journal que les identifiants et états appliqués ; il ne journalise ni n'affiche le reste du fichier.
- Mode smoke : aucune rangée, aucun `-ProgressPath` (les fonctions sortent avant, comme au HEAD).

## Direction des dépendances

Aucun module Java touché ; aucun package nouveau. Le test de contrat vit dans `minos-app` comme `M29InstallerBackendLifecycleContractTest`.

## Plateformes

Windows seulement pour le comportement (Inno Setup, Windows PowerShell 5.1). Les émetteurs sont testés sous `pwsh` sur Ubuntu (job `invariants`) et sous Windows PowerShell 5.1 et `pwsh` sur Windows (job `verify`).

## Risks / Trade-offs

- **Ligne perdue** (écriture refusée malgré les tentatives) : une rangée peut rester « en cours » jusqu'à la fin de l'étape ; l'état final reste juste (D3).
- **Rafraîchissement** : 250 ms entre deux lectures ; le fichier fait au plus quelques centaines de lignes (pourcentages émis seulement s'ils changent).
- **Échec avant toute ligne ou après la dernière** : la première rangée en attente, ou la dernière rangée, est marquée en échec alors que l'échec a pu survenir en préparation ou à la validation finale ; le message d'erreur détaille la cause.
- **Pourcentage par couche** : Ollama télécharge plusieurs couches et affiche l'avancement de la couche en cours ; la barre peut repartir de zéro d'une couche à l'autre.
- **Exception de `Exec`** : le minuteur est arrêté dans un `finally` ; l'exception suit son cours comme au HEAD.

## Décisions en attente du propriétaire

Aucune : le transport (D1) découle des mesures ; la recommandation est de l'adopter.
