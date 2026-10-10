---
paths:
  - "**/*.ps1"
  - "**/*.cmd"
  - "**/*.bat"
  - "packaging/windows/**"
---

# Scripts Windows (PowerShell, cmd, installateur)

- **Windows PowerShell 5.1** est le runtime de l'installateur ; `pwsh` 7 sert au poste de développement et à la CI. Écrire pour les deux : pas d'API .NET 5+ seule (`RandomNumberGenerator.Fill` n'existe pas en 5.1 : `Create()` + `GetBytes`), pas de `&&`/`||` ni d'opérateurs de pipeline de PowerShell 7.
- **Fins de ligne** : `.gitattributes` impose `*.ps1 text eol=crlf` (assemblage de lanceurs comparé octet pour octet par `WindowsContainmentScriptTest`). Garder CRLF dans l'arbre de travail.
- **Erreurs** : sous 5.1, un écrit sur stderr d'une commande native peut être terminal ; tester `$LASTEXITCODE` après chaque exécutable (`icacls`, `docker`, `dotnet`).
- **Secrets** (cible du changement OpenSpec `durcir-secret-postgres-et-lanceur-de-developpement`, à appliquer à tout nouveau script) : un fichier de secret est créé **vide**, restreint à l'utilisateur courant (`icacls /inheritance:r /grant:r *SID:F`) et vérifié **avant** d'y écrire ; il est supprimé si la restriction échoue ; un fichier existant est restreint de nouveau et vérifié.
- **Lanceurs** : `cmd.exe` cherche d'abord dans le répertoire courant ; un lanceur n'appelle jamais `java` sans chemin sans `NoDefaultCurrentDirectoryInExePath=1` ou `%JAVA_HOME%\bin\java.exe`. Les arguments qu'un `.cmd` ne peut pas recevoir (`"`, `%`) sont refusés avant le démarrage.
- **Gates** : `check-post-mne.py` exige des chaînes littérales dans `configure-runtime-settings.ps1` et `configure-m30-docker-services.ps1` (`Read-BoundedUtf8`, `'minos.postgres.managed'] = …`). Les scripts livrés sont dans des listes de fichiers explicites (`build-windows-distribution.ps1`, `update-installation.ps1`) : un nouveau fichier doit y être ajouté et la mise à jour transactionnelle re-prouvée.
- **Tests** : extraire les définitions réelles par l'analyseur PowerShell (modèle : `scripts/lib/test_minos_exit_code.py`), ne pas réimplémenter la logique dans le test. Piège de reproduction : `NoDefaultCurrentDirectoryInExePath` peut déjà valoir `1` sur le poste ; la retirer de l'environnement du test.
- Dans l'outil Bash de l'agent, les `\` des heredocs sont altérés : écrire les scripts avec le tool Write.
