## ADDED Requirements

### Requirement: Aucun identifiant de production ne porte un numéro de jalon
Un nom de fichier, un identifiant de code, une référence de classe d'un descripteur ou un nom de variable d'environnement du code de production SHALL NOT contenir de segment à jalon (`M` ou `m` suivi de chiffres en début de segment). Un gate du job des invariants SHALL échouer sur un tel nom hors d'une liste décroissante dont chaque entrée porte sa raison, et SHALL échouer sur une entrée périmée ou un fichier illisible. Littéraux et commentaires ne sont pas lus.

#### Scenario: Nouvelle méthode à jalon
- **GIVEN** une copie du dépôt où un fichier de `src/main` déclare une méthode `qualifiedM31Providers`
- **WHEN** `python scripts/quality/check-milestone-identifiers.py --root <copie>` est exécuté
- **THEN** le gate échoue en nommant le fichier, la ligne et l'identifiant

#### Scenario: Jalon à un seul chiffre
- **GIVEN** une copie où un fichier de `src/main` déclare une méthode `registerQualifiedM1`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant l'identifiant

#### Scenario: Fichier nommé par un jalon
- **GIVEN** une copie du dépôt où un fichier `MinosM21Actions.java` existe sous `src/main`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier

#### Scenario: Variable d'environnement à jalon dans un script embarqué
- **GIVEN** une copie où un script `.ps1` de `src/main` pose `MINOS_M14_MAVEN_CMD`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue en nommant le fichier, la ligne et la variable

#### Scenario: Sigles techniques et littéraux acceptés
- **GIVEN** une copie dont `src/main` contient les identifiants `arm64`, `MACOS_ARM64`, `windowsSystem32` et le littéral `"not claimed by M24"`
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il réussit

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste décroissante qui nomme un identifiant disparu ou un fichier introuvable
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Dépôt réel après renommage
- **GIVEN** le dépôt après le renommage
- **WHEN** `python scripts/quality/check-milestone-identifiers.py` est exécuté
- **THEN** il réussit et affiche le nombre d'entrées restantes de la liste

### Requirement: Retirer un jalon d'un identifiant ne change aucune surface publique
Le renommage d'un identifiant à jalon SHALL NOT modifier une sortie de la CLI, de l'API Java ou du serveur MCP, ni un identifiant d'action du plugin IntelliJ. Une chaîne publique qui cite un jalon SHALL rester inchangée tant qu'une décision écrite ne l'a pas modifiée, goldens mis à jour et version notée dans le même changement.

#### Scenario: Goldens de caractérisation
- **GIVEN** le dépôt après le renommage
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` puis `git diff --exit-code minos-app/src/test/resources/characterization/` sont exécutés
- **THEN** les tests réussissent et aucun golden n'a changé

#### Scenario: Identifiants d'action du plugin
- **GIVEN** la liste attendue des 16 identifiants d'action de `plugin.xml`
- **WHEN** le test du plugin lit `plugin.xml` après le renommage
- **THEN** l'ensemble lu est égal à la liste attendue et chaque attribut `class` désigne une classe existante

#### Scenario: Identifiant d'action retiré par erreur
- **GIVEN** une copie de `plugin.xml` où une action a perdu ou changé son `id`
- **WHEN** le test du plugin est exécuté sur cette copie
- **THEN** il échoue en nommant l'identifiant manquant

#### Scenario: Classe d'action introuvable
- **GIVEN** une copie de `plugin.xml` dont un attribut `class` désigne encore `MinosM21Actions` après le renommage
- **WHEN** le test du plugin est exécuté sur cette copie
- **THEN** il échoue en nommant la classe introuvable
