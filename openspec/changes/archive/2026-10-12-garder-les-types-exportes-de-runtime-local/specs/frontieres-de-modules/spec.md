## ADDED Requirements

### Requirement: Les types de confinement de l'exécution locale ne sont nommés que par liste fermée
Hors de `minos-runtime-local`, les sources de production et de test SHALL ne nommer, parmi les types de ses packages `process`, `sandbox` et `workspace`, que ceux d'une liste fermée tenue par `check-module-boundaries.py`. L'import par joker d'un de ces packages et l'usage des membres réservés SHALL être refusés. La liste SHALL être un cliquet : une entrée sans fichier ou sans utilisateur échoue.

#### Scenario: Un type exporté est accepté
- **GIVEN** un module qui importe `com.minos.runtime.local.process.CommandLocator`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté
- **THEN** il réussit

#### Scenario: Une primitive non exportée est refusée
- **GIVEN** un module, ou l'un de ses tests, qui importe `com.minos.runtime.local.process.ProviderProcessEnvironment`
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en nommant le fichier et le type

#### Scenario: Une entrée devenue inutile est refusée
- **GIVEN** un type de la liste qu'aucun autre module ne nomme plus
- **WHEN** `check-module-boundaries.py` est exécuté
- **THEN** il échoue en demandant de retirer l'entrée
