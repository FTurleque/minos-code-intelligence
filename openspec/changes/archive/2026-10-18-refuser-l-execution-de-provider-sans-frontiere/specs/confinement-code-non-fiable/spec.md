## ADDED Requirements

### Requirement: L'exécuteur de processus refuse de lancer un provider sans frontière
L'exécuteur de processus des providers SHALL refuser toute exécution demandée sans transformateur de frontière (propriété forte ou bac à sable OS) : il SHALL lever une erreur au message fixe, sans chemin, et SHALL NOT démarrer de processus ni créer de répertoire de run. Il SHALL NOT se replier sur une exécution non confinée.

#### Scenario: Exécution demandée sans frontière
- **GIVEN** un exécuteur de processus construit avec un plan valide et un répertoire de données vide
- **WHEN** son entrée d'exécution sans transformateur est appelée
- **THEN** une erreur est levée, aucun processus n'a été démarré et le répertoire des runs n'existe pas

#### Scenario: Backend natif devant un exécuteur de processus nu
- **GIVEN** le backend natif sans confinement et un exécuteur de processus nu comme délégué
- **WHEN** le backend exécute une requête avec une politique réseau autre que le refus
- **THEN** l'exécution échoue par le refus de l'exécuteur et aucun processus n'a été démarré

#### Scenario: Exécution sous frontière
- **GIVEN** un exécuteur de propriété forte sur un hôte dont la frontière est qualifiée
- **WHEN** une requête est exécutée
- **THEN** le provider s'exécute sous la frontière et l'artefact est rendu

### Requirement: Aucune entrée publique ne construit un exécuteur sans politique réseau
Tout constructeur public de l'exécuteur de propriété forte de `minos-runtime-local` SHALL exiger une politique réseau explicite. La variante limitée à la frontière de propriété SHALL NOT être accessible aux autres modules du reactor. Un test de structure SHALL échouer si une entrée publique sans politique réseau réapparaît.

#### Scenario: Constructeurs publics énumérés
- **GIVEN** la classe de l'exécuteur de propriété forte chargée par un test de `minos-runtime-local`
- **WHEN** ses constructeurs publics sont énumérés par réflexion
- **THEN** chacun déclare un paramètre de politique réseau

#### Scenario: Entrée publique réintroduite
- **GIVEN** une copie de travail où le constructeur limité à la propriété redevient public
- **WHEN** `./mvnw -B -ntp -pl minos-runtime-local -am test` est exécuté
- **THEN** le test de structure échoue et nomme le constructeur

#### Scenario: Tests de confinement toujours exécutés
- **GIVEN** un job de CI en mode de confinement requis
- **WHEN** les tests de propriété forte Linux et Windows sont exécutés sur leur système
- **THEN** leurs rapports Surefire ne comptent aucun test sauté
