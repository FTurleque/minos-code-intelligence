## ADDED Requirements

### Requirement: Les racines déclarées en lecture seule par un plan ne sont accordées qu'en lecture
Quand un plan d'indexation déclare des racines en lecture seule, le bac à sable Linux SHALL monter la racine réelle de chacune en `--ro-bind` et le bac à sable Windows SHALL n'en accorder que la lecture. Aucun des deux MUST NOT monter ni accorder le répertoire parent d'une racine, ni l'accorder en écriture. Un plan sans racine déclarée garde ses montages inchangés.

#### Scenario: Racine montée, parent absent (Linux)
- **GIVEN** sous Linux, un plan qui déclare `<tmp>/m2/repository` et un fichier `<tmp>/m2/settings.xml`
- **WHEN** le plan du bac à sable est construit
- **THEN** la commande contient `--ro-bind` de `<tmp>/m2/repository` et ne cite ni `<tmp>/m2` ni `settings.xml`

#### Scenario: Écriture refusée dans la racine (Linux)
- **GIVEN** sous Linux, un plan qui déclare `<tmp>/m2/repository` et une commande qui y crée un fichier
- **WHEN** le plan est exécuté dans le bac à sable avec un egress `DENY`
- **THEN** la commande échoue et aucun fichier n'est créé sous `<tmp>/m2/repository`

#### Scenario: Octroi en lecture seule (Windows)
- **GIVEN** sous Windows, un plan qui déclare `<tmp>\m2\repository`
- **WHEN** la liste d'octroi du conteneur est calculée
- **THEN** `<tmp>\m2\repository` figure dans les lectures, ni lui ni `<tmp>\m2` dans les écritures

### Requirement: Une racine déclarée trop large ou inexploitable est refusée avant le lancement
Le bac à sable SHALL refuser le plan avant tout lancement quand une racine déclarée en lecture seule est relative, absente, n'est pas un répertoire, contient un blanc, est de profondeur inférieure à 2, est égale ou ancêtre du répertoire personnel ou de `MINOS_HOME`, se nomme `.m2`, ou est sous `/etc`, `/proc`, `/sys`, `/dev` ou `/run`. Le message MUST NOT contenir de chemin absolu.

#### Scenario: Répertoire personnel refusé
- **GIVEN** un plan qui déclare le répertoire personnel comme racine en lecture seule
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue avant tout processus fils, par un message sans chemin absolu

#### Scenario: Dossier .m2 refusé
- **GIVEN** un plan qui déclare `<tmp>/.m2` comme racine en lecture seule
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue avant tout processus fils et `<tmp>/.m2` n'est pas monté

#### Scenario: Chemin avec un blanc refusé
- **GIVEN** un plan qui déclare `<tmp>/mon depot` comme racine en lecture seule
- **WHEN** le plan du bac à sable est construit
- **THEN** il échoue avant tout processus fils, par un message sans chemin absolu
