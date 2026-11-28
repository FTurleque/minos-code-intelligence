## ADDED Requirements

### Requirement: Un import est lu quelle que soit sa position dans la ligne
Les règles de `check-module-boundaries.py` qui lisent les déclarations d'import SHALL lire chacune d'elles où qu'elle soit dans sa ligne : à son début, ou à la suite d'un autre import, de la déclaration de package, d'un point-virgule seul ou d'un commentaire. Un `import` écrit dans un commentaire ou dans un littéral SHALL rester ignoré.

#### Scenario: Second import d'une ligne dans la façade
- **GIVEN** l'auto-test des cliquets, avec `MinosApplication` qui écrit sur une même ligne `import java.util.List;` puis un import simple, joker ou statique qui atteint un type de `minos-engine`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Joker de package en second sur sa ligne
- **GIVEN** les auto-tests, avec ce joker dans une surface (A11), un adaptateur (A13), une source qui ne doit nommer aucun adaptateur (A2), un module hors de `minos-runtime-local` (A10), `minos-bootstrap` (R1) et un package du stockage
- **WHEN** les auto-tests des cliquets et des adaptateurs sont exécutés
- **THEN** chaque règle refuse et nomme le package ou le fichier

#### Scenario: Cycle et type référencé par un second import
- **GIVEN** l'auto-test des packages, avec un cycle fermé par un joker en second sur sa ligne, puis un type public nommé par le seul joker de son package, en second sur sa ligne
- **WHEN** `python scripts/architecture/test_check_module_boundaries_packages.py` est exécuté
- **THEN** le cycle est refusé en A8
- **AND** A15 ne déclare pas le type sans appelant

#### Scenario: Autres positions
- **GIVEN** l'auto-test des cliquets, avec un import de façade placé après la déclaration de package, après un point-virgule seul ou après un commentaire de bloc fermé sur la ligne d'un autre import
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11

#### Scenario: Import écrit dans un commentaire ou un littéral
- **GIVEN** l'auto-test des cliquets, avec le même texte d'import dans un commentaire de ligne, un commentaire de bloc, une chaîne et un bloc de texte
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Dépôt au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un joker sur un type du module visé échoue en A11 et en A13
Dans les sources de production d'une surface, d'un module de contrat (A11) ou d'un adaptateur (A13), un import joker non statique dont la cible est un type du module visé, ou un type imbriqué d'un tel type, SHALL faire échouer le garde en nommant le fichier et la cible, que ce type soit listé ou non.

#### Scenario: Joker sur un type non listé
- **GIVEN** l'auto-test des cliquets, avec `import com.minos.orchestration.IndexingRuntimePorts.*;` dans une surface puis dans `minos-nexus`, sur le type ou sur un de ses types imbriqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11, message nommant le fichier et la cible suivie de `.*`
- **AND** le type est aussi signalé absent de la table

#### Scenario: Joker sur un type listé
- **GIVEN** le même auto-test, avec la table qui liste le type pour la surface
- **WHEN** l'auto-test est exécuté
- **THEN** le joker est refusé
- **AND** le message ne demande pas de retirer l'entrée

#### Scenario: Joker sur un type d'un autre adaptateur
- **GIVEN** le même auto-test, avec ce joker entre deux adaptateurs
- **WHEN** l'auto-test est exécuté
- **THEN** il est refusé en A13, message nommant le couple, le fichier et la cible

#### Scenario: Joker hors du module visé et joker statique
- **GIVEN** le même auto-test, avec un joker sur les membres d'un type du JDK ou de `minos-domain`, puis un `import static` joker sur un type listé
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit et compte le fichier de l'import statique comme un usage du type listé

### Requirement: Un joker sur une classe d'adaptateur échoue en A2
Hors des adaptateurs et de `minos-bootstrap`, une source de production qui importe par joker les membres d'une classe d'adaptateur, ou d'un type imbriqué d'une telle classe, SHALL faire échouer A2 en nommant le fichier, la classe et son module.

#### Scenario: Joker sur les membres d'une classe d'adaptateur
- **GIVEN** l'auto-test des adaptateurs, avec ce joker dans une source de `minos-cli`, sur la classe puis sur un de ses types imbriqués
- **WHEN** `python scripts/architecture/test_check_module_boundaries_adapters.py` est exécuté
- **THEN** chaque forme est refusée en A2, message nommant le fichier, la classe et son module

#### Scenario: Joker sur les membres d'une classe hors des adaptateurs
- **GIVEN** le même auto-test, avec un joker sur les membres d'un type de `minos-domain`
- **WHEN** l'auto-test est exécuté
- **THEN** la règle réussit
