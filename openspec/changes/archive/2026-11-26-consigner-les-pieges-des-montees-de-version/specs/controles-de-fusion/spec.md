## ADDED Requirements

### Requirement: Un fichier à fin de ligne épinglée est enregistré sans retour chariot
Tout fichier suivi par Git dont l'attribut résolu porte `eol=lf` ou `eol=crlf` SHALL être enregistré sans retour chariot (`i/lf`), quelles que soient ses fins de ligne à l'extraction. `check-posix-script-eol.py` SHALL échouer en nommant tout fichier enregistré `i/crlf` ou `i/mixed` sous un tel attribut, avec la commande `git add --renormalize` qui le corrige. Les fichiers batch du wrapper Maven et du wrapper Gradle SHALL être couverts.

#### Scenario: Wrapper batch réécrit en CRLF par une PR de dépendance
- **GIVEN** un dépôt temporaire avec `*.cmd text eol=crlf` où `mvnw.cmd` est enregistré avec des retours chariot
- **WHEN** `python scripts/quality/check-posix-script-eol.py --self-test` évalue ce cas
- **THEN** le cas constate le code 1 et un message qui nomme `mvnw.cmd` et `git add --renormalize -- mvnw.cmd`

#### Scenario: Lanceur PowerShell enregistré en CRLF
- **GIVEN** un dépôt temporaire avec `*.ps1 text eol=crlf` où un `x.ps1` est enregistré avec des retours chariot
- **WHEN** l'auto-test du gate évalue ce cas
- **THEN** le cas constate le code 1

#### Scenario: Clone du dépôt avec un blob CRLF
- **GIVEN** un clone du dépôt où `git update-index --cacheinfo` enregistre pour `mvnw.cmd` un blob en CRLF, de sorte que `git ls-files --eol mvnw.cmd` affiche `i/crlf`
- **WHEN** `python scripts/quality/check-posix-script-eol.py --root <clone>` est exécuté
- **THEN** le gate se termine avec le code 1 et nomme `mvnw.cmd`

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/quality/check-posix-script-eol.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0, et `mvnw.cmd`, `minos-intellij/gradlew.bat` et `minos.cmd` comptent parmi les fichiers contrôlés
