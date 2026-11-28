## ADDED Requirements

### Requirement: Les fichiers batch suivis ont leur fin de ligne épinglée
Tout fichier suivi par Git dont le nom finit par `.cmd` ou `.bat`, hors d'un dossier `fixtures/`, SHALL avoir l'attribut résolu `eol=crlf`. `check-posix-script-eol.py` SHALL échouer en nommant le fichier et la règle `.gitattributes` attendue, que le fichier soit enregistré avec ou sans retour chariot.

#### Scenario: Pin retiré des fichiers batch
- **GIVEN** un dépôt temporaire avec `*.cmd text` sans `eol=crlf` et un `mvnw.cmd` suivi
- **WHEN** `python scripts/quality/check-posix-script-eol.py --self-test` évalue ce cas
- **THEN** le cas constate l'échec et le message qui nomme `mvnw.cmd` et `*.cmd text eol=crlf`

#### Scenario: Fichier batch rendu binaire
- **GIVEN** un dépôt temporaire avec `*.bat binary` et un `gradlew.bat` suivi
- **WHEN** le même auto-test évalue ce cas
- **THEN** le cas constate l'échec et le message qui nomme `gradlew.bat`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/quality/check-posix-script-eol.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0
- **AND** `git ls-files --eol -- '*.cmd' '*.bat'` montre `attr/text eol=crlf` sur chaque ligne
