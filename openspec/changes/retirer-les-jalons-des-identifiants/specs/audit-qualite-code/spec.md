## ADDED Requirements

### Requirement: Aucun identifiant de production ne porte un numéro de jalon
Un nom de fichier ou une déclaration de classe, de méthode ou de constante du code de production SHALL NOT contenir un numéro de jalon. Un gate exécuté par le job des invariants SHALL échouer sur un tel identifiant hors d'une liste décroissante dont chaque entrée porte sa raison, et SHALL échouer si une entrée de la liste ne correspond plus à rien ou nomme un fichier introuvable.

#### Scenario: Nouveau nom à jalon
- **GIVEN** une copie du dépôt où un fichier de `src/main` déclare une méthode `qualifiedM31Providers`
- **WHEN** `python scripts/quality/check-milestone-identifiers.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le fichier, la ligne et l'identifiant

#### Scenario: Fichier nommé par un jalon
- **GIVEN** une copie du dépôt où un fichier `MinosM21Actions.java` existe sous `src/main`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste décroissante qui nomme un identifiant disparu ou un fichier introuvable
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Dépôt réel après renommage
- **GIVEN** le dépôt après le renommage
- **WHEN** le gate est exécuté
- **THEN** il réussit et affiche le nombre d'entrées restantes de la liste

### Requirement: Retirer un jalon d'un identifiant ne change aucune surface publique
Le renommage d'un identifiant à jalon SHALL NOT modifier une sortie de la CLI, de l'API Java ou du serveur MCP, ni un identifiant d'action du plugin IntelliJ. Une chaîne publique qui cite un jalon SHALL rester inchangée tant qu'une décision écrite ne l'a pas modifiée, goldens mis à jour dans le même changement.

#### Scenario: Goldens de caractérisation
- **GIVEN** le dépôt après le renommage
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté
- **THEN** les goldens de caractérisation sont inchangés et les tests réussissent

#### Scenario: Identifiants d'action du plugin
- **GIVEN** l'ensemble des attributs `id` des actions de `plugin.xml` relevé avant le renommage
- **WHEN** le test du plugin le compare à l'ensemble relevé après
- **THEN** les deux ensembles sont égaux

#### Scenario: Identifiant d'action retiré par erreur
- **GIVEN** une copie du plugin où une action a perdu ou changé son `id`
- **WHEN** le test du plugin est exécuté
- **THEN** il échoue en nommant l'identifiant manquant
