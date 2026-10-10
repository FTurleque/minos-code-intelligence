# Tasks

Ordre dans chaque section : d'abord le test qui échoue (rouge attendu), puis la correction, puis la preuve. Chaque tâche porte l'identifiant du constat qu'elle ferme. Les deux lots (1 : AUD-SEC-12, 2 : AUD-SEC-13) sont indépendants. Les `.ps1` restent en CRLF (`.gitattributes`). Commande de référence des tests : `python scripts/install/test_secret_file_acl.py -v` et `python scripts/install/test_dev_launcher.py -v` (poste Windows ; sautés ailleurs).

## 1. Secret PostgreSQL : créer restreint, vérifier, écrire (AUD-SEC-12)

- [ ] 1.1 [AUD-SEC-12] **Décision à obtenir avant d'écrire** : D2 (corriger en place, recommandé, ou mutualiser dans un fichier livré). Consigner la réponse dans `design.md`.
- [ ] 1.2 [AUD-SEC-12] Rouge : `scripts/install/test_secret_file_acl.py` (modèle : `scripts/lib/test_minos_exit_code.py`, extraction des **vraies** définitions par l'analyseur PowerShell) pour les deux scripts, sous Windows PowerShell 5.1 **et** `pwsh` : écriture dans un dossier d'ACL large (héritage coupé, une entrée), sonde « ACL déjà restreinte au moment où le contenu arrive », échec injecté de la restriction (fichier supprimé, secret jamais écrit), fichier existant d'ACL héritée (restreint avant lecture), fichier existant non restreignable (échec, contenu inchangé, message d'exposition possible). **Preuve du rouge : sur le code actuel, la sonde observe une ACL héritée et l'échec injecté laisse le fichier en place.**
- [ ] 1.3 [AUD-SEC-12] Mesurer dans les deux runtimes le mode de vérification d'ACL (`Get-Acl`, sortie d'`icacls`, `GetAccessControl`) avant de le figer (D1) ; consigner dans `SUIVI.md` celui qui est retenu et pourquoi.
- [ ] 1.4 [AUD-SEC-12] `configure-runtime-settings.ps1:236-238` : séquence D1 (supprimer l'existant, créer vide, restreindre, vérifier, écrire, supprimer en cas d'échec). Conserver les chaînes exigées par `check-post-mne.py` (`Read-BoundedUtf8`, `'minos.postgres.managed'] = 'false'`, messages d'URL).
- [ ] 1.5 [AUD-SEC-12] `docker/scripts/configure-m30-docker-services.ps1:110-131` (`New-ManagedPassword`) : séquence D1 pour le fichier neuf, restriction et vérification pour le fichier existant (D3). Conserver `Read-BoundedUtf8` et `'minos.postgres.managed'] = 'true'`.
- [ ] 1.6 [AUD-SEC-12] Preuve sur un dossier d'ACL volontairement large (le poste n'a pas de `D:\` : créer un dossier de test sous `%TEMP%` et lui accorder la lecture aux Utilisateurs, puis exécuter les fonctions) : ACL finale conforme ; rejouer `python scripts/remediation/check-post-mne.py`, `scripts/install/verify-windows-upgrade-transaction.ps1`, `python scripts/docs/check-current-docs.py`.

## 2. Lanceur de développement (AUD-SEC-13)

- [ ] 2.1 [AUD-SEC-13] Rouge : `scripts/install/test_dev_launcher.py` (arbre temporaire avec copie de `minos.cmd`, faux `target\minos-code-intelligence-9-all.jar`, `java.bat` piégé dans le répertoire courant, `NoDefaultCurrentDirectoryInExePath` **retiré** de l'environnement transmis, faux `java` dans un dossier du `PATH` ; cas `JAVA_HOME` défini ; cas « aucune fuite vers l'appelant »). **Preuve du rouge : le marqueur du piège est écrit** avant correction (reproduit le 2026-10-10 à la main).
- [ ] 2.2 [AUD-SEC-13] `minos.cmd` : `%JAVA_HOME%\bin\java.exe` s'il existe, sinon `set "NoDefaultCurrentDirectoryInExePath=1"` avant `java` ; commentaire qui nomme le risque. Le lanceur distribué (`build-windows-distribution.ps1`) n'est pas modifié. Preuve : les tests de 2.1 verts ; `python scripts/docs/check-current-docs.py` vert.
- [ ] 2.3 [AUD-SEC-13] Documenter dans `docs/user/intellij-plugin.md` (ou le fichier qui décrit le lanceur de développement) que `minos.cmd` de la racine est un lanceur de développement et ce que la variable change. Rejouer `check-current-docs.py`.

## 3. Câblage CI et preuve sur Windows

- [ ] 3.1 [AUD-SEC-12, AUD-SEC-13] Câbler les deux `test_*.py` dans le job `invariants` (où ils se déclarent sautés avec leur raison : `python scripts/install/test_secret_file_acl.py` et `python scripts/install/test_dev_launcher.py`), comme l'exige `python scripts/quality/check-ci-wiring.py` ; ajouter un pas `runner.os == 'Windows'` au job `verify` qui les exécute réellement (Windows PowerShell 5.1 pour la fonction d'installation). Valider le YAML des workflows par `yaml.safe_load` avant de pousser ; rejouer `check-ci-wiring.py` et `python scripts/remediation/check-single-execution.py`.
- [ ] 3.2 **(manuelle)** [AUD-SEC-12, AUD-SEC-13] À la PR, relever sur `Verify (windows-2022)` que les deux tests s'exécutent (0 sauté) et passent, et sur `invariants` qu'ils sont déclarés sautés avec leur raison. Noter les run ids dans `SUIVI.md`.

## 4. Clôture

- [ ] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` : AUD-SEC-12 (statut, commit, preuve, décision D2, observation de l'installateur Inno), AUD-SEC-13 (statut passé de « plausible » à « reproduit », piège de la variable déjà définie sur le poste, commande de preuve).
- [ ] 4.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage (`docs/STATUS.md`, `docs/user/intellij-plugin.md`).
