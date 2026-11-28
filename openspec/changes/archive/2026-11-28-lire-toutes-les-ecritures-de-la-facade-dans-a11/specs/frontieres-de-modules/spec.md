## ADDED Requirements

### Requirement: L'écriture d'une déclaration de la façade ne contourne pas A11
A11 SHALL lire une méthode ou un constructeur public de `MinosApplication` quels que soient les annotations placées dans sa déclaration, la casse de son nom et les retours à la ligne de son en-tête, nom qualifié compris. Une déclaration publique suivie d'une parenthèse dont A11 ne lit pas le nom SHALL faire échouer le garde.

#### Scenario: Annotation à l'intérieur de la déclaration
- **GIVEN** l'auto-test du garde, avec `public @Deprecated ProjectRegistry x()`, une annotation à arguments imbriqués, entre deux modificateurs, après les paramètres de type ou dans un nom qualifié
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Nom de méthode qui n'est pas en minuscules
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry Leak()`, `public void Leak(ProjectRegistry r)` et `public ProjectRegistry $leak()`
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme la méthode

#### Scenario: En-tête sur plusieurs lignes
- **GIVEN** l'auto-test du garde, avec un retour `Map<String,` puis `ProjectRegistry>` à la ligne suivante, puis un nom qualifié coupé avant son dernier segment
- **WHEN** le même auto-test est exécuté
- **THEN** chaque variante est refusée en A11 et nomme `registry.ProjectRegistry`

#### Scenario: Déclarations sans type du moteur
- **GIVEN** l'auto-test du garde, avec des membres annotés, un champ initialisé par un appel et un `record` imbriqué public
- **WHEN** le même auto-test est exécuté
- **THEN** les règles de la façade réussissent

#### Scenario: Déclaration illisible
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry (`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate l'échec du garde, message `A11 unreadable MinosApplication member`

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Un import qui passe par un type du moteur ne contourne pas A11
Dans `MinosApplication`, un import joker, statique ou non, dont la cible est un type de `minos-engine` ou un type imbriqué d'un tel type SHALL faire échouer A11 en nommant la cible. Un type importé par `import static` depuis un type de `minos-engine` SHALL compter comme un type du moteur quand une signature publique de la façade le nomme.

#### Scenario: Joker sur les membres d'un type du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.orchestration.IndexingRuntimePorts.*;`, puis sa forme `import static`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque forme est refusée en A11, message nommant `com.minos.orchestration.IndexingRuntimePorts.*`

#### Scenario: Import statique d'un type imbriqué
- **GIVEN** l'auto-test du garde, avec `import static com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;` et `public IndexerExecutor x()`
- **WHEN** le même auto-test est exécuté
- **THEN** la méthode est refusée en A11, message nommant `x` et `orchestration.IndexingRuntimePorts`

#### Scenario: Import statique hors du moteur
- **GIVEN** l'auto-test du garde, avec `import static` d'une méthode d'un type de `minos-domain` et un joker sur les membres d'un type du JDK
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

### Requirement: La façade ne rend pas la racine de composition par une méthode statique
Une méthode statique publique de `MinosApplication` dont le type de retour ou une borne de paramètre de type nomme `MinosApplicationComposer` SHALL faire échouer A11 en nommant la méthode, qu'elle soit générique ou non.

#### Scenario: Racine de composition rendue par une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static MinosApplicationComposer root()`, `public static <T> MinosApplicationComposer root()` et `public static <T extends MinosApplicationComposer> T root()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant `root` et la racine de composition
