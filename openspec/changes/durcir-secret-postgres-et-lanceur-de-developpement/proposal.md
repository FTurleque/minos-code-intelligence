# Proposal

## Why

Deux constats de faible sévérité du sprint 2 de l'audit du 10 octobre 2026, tous deux propres à Windows et à des scripts de moins de cinquante lignes, réunis parce qu'ils se corrigent et se prouvent de la même façon (un script, un test Windows) :

- **AUD-SEC-12** : `scripts/install/configure-runtime-settings.ps1` (`:236-238`) et `docker/scripts/configure-m30-docker-services.ps1` (`:127-130`) écrivent le mot de passe PostgreSQL avec l'ACL **héritée** du dossier (`[System.IO.File]::WriteAllText`), puis la restreignent par `icacls` (`Restrict-FileToCurrentUser`, `:71-75`). Pendant la fenêtre d'écriture, ou durablement si `icacls` échoue (l'exception est levée mais le fichier reste), le mot de passe est lisible par les comptes que l'ACL du dossier admet, par exemple avec un `DataRoot` personnalisé sur `D:\` où le groupe Utilisateurs hérite de la lecture. Le script du service Docker accepte aussi un fichier **déjà présent** sans en contrôler l'ACL (`:113-116`).
- **AUD-SEC-13** : le lanceur de développement `minos.cmd` (racine du dépôt) appelle `java` sans chemin. `cmd.exe` cherche d'abord dans le répertoire courant, qui est le **projet ouvert** quand le plugin IntelliJ le lance (`MinosCliClient.java:142`, `builder.directory(...)`). Un projet qui contient `java.bat` ou `java.exe` s'exécute alors hors bac à sable. Le constat était « plausible » : la mesure faite pour cette analyse le **reproduit** (voir `design.md`).

## What Changes

- **Secret PostgreSQL** (deux scripts) : le fichier est créé **vide**, restreint à l'utilisateur courant et vérifié **avant** que le secret n'y soit écrit ; si la restriction ou la vérification échoue, le fichier est supprimé et l'installation échoue, sans que le secret ait touché le disque ; un fichier **existant** est restreint de nouveau et vérifié (jamais accepté sur parole).
- **Lanceur de développement** : `minos.cmd` utilise `%JAVA_HOME%\bin\java.exe` quand il existe et, sinon, interdit la recherche dans le répertoire courant (`NoDefaultCurrentDirectoryInExePath=1`, limité au lanceur par `setlocal`).
- **Tests Windows** : des tests sur les définitions **réelles** des fonctions (analyseur PowerShell, comme `scripts/lib/test_minos_exit_code.py`) et sur le vrai `minos.cmd` copié dans un arbre temporaire, exécutés dans un nouveau pas Windows du job `verify` (et listés dans `invariants`, où ils se déclarent sautés avec leur raison).
- Le distribué `minos.cmd` (`"%~dp0app\minos.exe" %*`, `build-windows-distribution.ps1:352`) n'est pas concerné et reste inchangé.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences (un secret n'est jamais écrit avec une ACL héritée ; un secret existant est revérifié).
- `client-intellij` : une exigence (le lanceur de développement ne résout pas `java` depuis le répertoire courant du projet).

## Hors périmètre

- **Les autres constats du sprint 2** (AUD-SEC-01, SEC-02, DEP-08, QUA-04, QUA-06 : autres changements).
- **Le fichier source du mot de passe de l'installateur** (`packaging/windows/minos-installer.iss.template:529-531` écrit `{tmp}\minos-postgres-password.txt` avec l'ACL du dossier temporaire de l'utilisateur, supprimé en `:561`) : le dossier temporaire d'Inno Setup est privé à l'utilisateur (`PrivilegesRequired=lowest`) ; observation consignée dans `design.md`, non traitée ici.
- Mutualiser la logique dans un fichier PowerShell partagé : exigerait de modifier la liste des fichiers livrés (`build-windows-distribution.ps1:327-345`, `update-installation.ps1:265`, le contrôle de transaction) ; décision D2.
- La lecture du fichier de mot de passe côté Java (`StorageBackendConfiguration`) : déjà couverte par `stockage-prive-et-secrets`.
- Un contrôle d'ACL à l'**usage** du secret : non demandé par le constat.

## Impact

- **Modules du reactor touchés** : aucun. Scripts `scripts/install/configure-runtime-settings.ps1`, `docker/scripts/configure-m30-docker-services.ps1`, `minos.cmd` ; nouveaux tests `scripts/install/test_secret_file_acl.py` et `scripts/install/test_dev_launcher.py` ; `.github/workflows/pr-ci.yml` (pas Windows du job `verify`, lignes dans `invariants`).
- **Surfaces publiques impactées** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés). L'installateur Windows et la mise à jour transactionnelle livrent les mêmes fichiers.
- **ADR** : aucun nouvel ADR, aucun amendement.
- **Gates à rejouer** : `python scripts/remediation/check-post-mne.py` (exige `Read-BoundedUtf8` et `'minos.postgres.managed'] = 'true'|'false'` dans les deux scripts), `python scripts/quality/check-ci-wiring.py` (tout `test_*.py` doit être câblé dans `invariants`), `python scripts/remediation/check-single-execution.py`, `python scripts/docs/check-current-docs.py`, `scripts/install/verify-windows-upgrade-transaction.ps1` (référence `configure-m30-docker-services.ps1` et la liste de `update-installation.ps1:265`). `.gitattributes` impose `*.ps1 text eol=crlf` : les scripts modifiés doivent rester en CRLF.
- **Plateformes** : Windows uniquement pour le comportement ; Linux les voit comme sautés avec leur raison. Les deux runtimes PowerShell (Windows PowerShell 5.1 de l'installateur, `pwsh` du poste de développement) sont exercés.
