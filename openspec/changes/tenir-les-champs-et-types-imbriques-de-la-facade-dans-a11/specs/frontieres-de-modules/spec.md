## ADDED Requirements

### Requirement: La façade n'a aucun champ public typé moteur
`MinosApplication` SHALL NOT déclarer de champ public, statique ou non, dont le type déclaré nomme un type de `minos-engine` ou `MinosApplicationComposer`. A11 SHALL échouer en nommant le champ et le type.

#### Scenario: Champ typé moteur refusé
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry registry;`, un champ `static final`, `java.util.List<ProjectRegistry>`, puis `public ProjectRegistry[] a, b;`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le champ et `registry.ProjectRegistry`

#### Scenario: Champ typé racine de composition refusé
- **GIVEN** l'auto-test du garde, avec `public MinosApplicationComposer root;`
- **WHEN** le même auto-test est exécuté
- **THEN** le champ est refusé en A11, message nommant `root` et `MinosApplicationComposer`

#### Scenario: Champ sans type du moteur ou non public
- **GIVEN** l'auto-test du garde, avec `public static final String NAME = "x";` et `private ProjectRegistry registry;`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

#### Scenario: Champs de la façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0

### Requirement: Le `Builder` est le seul type imbriqué public de la façade
Un type imbriqué public de `MinosApplication` autre que `Builder` (classe, `record`, interface, enum ou annotation) SHALL faire échouer A11 en le nommant, qu'il nomme ou non un type du moteur. Un type imbriqué non public SHALL rester accepté.

#### Scenario: Type imbriqué public refusé
- **GIVEN** l'auto-test du garde, avec tour à tour un `public record` générique ou non, une `public static final class`, une `public interface` dont les méthodes sont implicitement publiques, un `public enum` et une `public @interface`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant le type imbriqué

#### Scenario: Builder et types non publics acceptés
- **GIVEN** l'auto-test du garde, avec `public static final class Builder` portant une méthode typée moteur, un `record` privé au package et un `private record`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle réussit

## MODIFIED Requirements

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
- **GIVEN** l'auto-test du garde, avec des membres annotés, un champ initialisé par un appel et un `record` imbriqué non public
- **WHEN** le même auto-test est exécuté
- **THEN** les règles de la façade réussissent

#### Scenario: Déclaration illisible
- **GIVEN** l'auto-test du garde, avec `public ProjectRegistry (`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate l'échec du garde, message `A11 unreadable MinosApplication member`

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0
