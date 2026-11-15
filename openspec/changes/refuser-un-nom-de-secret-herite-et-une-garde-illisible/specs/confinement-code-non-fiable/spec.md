## ADDED Requirements

### Requirement: Un emplacement large illisible fait refuser l'octroi d'une racine d'outillage sous Windows
Sous Windows, quand une variable d'emplacement large (profil, données d'application, programmes, lecteur système, racine système) est définie mais n'est pas analysable comme chemin, le bac à sable SHALL juger trop large la racine d'outillage examinée et SHALL NOT l'accorder. Seule l'erreur d'analyse de chemin SHALL être interceptée. Aucun message ni journal SHALL NOT reprendre la valeur de la variable.

#### Scenario: Variable de profil non analysable
- **GIVEN** un lecteur d'environnement de test où `USERPROFILE` vaut une chaîne qui n'est pas un chemin valide, et une racine d'outillage par ailleurs acceptable
- **WHEN** `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test "-Dtest=WindowsOverBroadGrantGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false"` évalue la garde
- **THEN** la garde répond « trop large »

#### Scenario: Racine système non analysable
- **GIVEN** un lecteur d'environnement de test où `SystemRoot` vaut une chaîne qui n'est pas un chemin valide
- **WHEN** la garde évalue une racine d'outillage
- **THEN** le contrôle de la racine système répond « à ne pas accorder » sans lever d'exception

#### Scenario: Variables analysables
- **GIVEN** un lecteur d'environnement de test dont toutes les variables d'emplacement large sont des chemins valides, et une racine d'outillage qui n'en contient aucune
- **WHEN** la garde est évaluée
- **THEN** la garde répond « pas trop large », comme avant le changement

#### Scenario: Variable absente ou vide
- **GIVEN** un lecteur d'environnement de test où une variable d'emplacement large n'est pas définie
- **WHEN** la garde est évaluée
- **THEN** cette variable ne contraint rien et les autres restent contrôlées
