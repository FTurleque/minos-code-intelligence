## ADDED Requirements

### Requirement: Une méthode générique ou un import joker de la façade ne contourne pas A11
A11 SHALL lire les méthodes publiques, statiques comprises, et les constructeurs publics de `MinosApplication`, y compris quand ils déclarent des paramètres de type à bornes imbriquées. Un type de `minos-engine` nommé dans une borne SHALL compter comme rendu et pris par la méthode ; une méthode statique qui rend un tel type SHALL échouer. Un import joker d'un package de `minos-engine` dans `MinosApplication` SHALL faire échouer A11 en nommant le package.

#### Scenario: Paramètre typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> void leakGeneric(com.minos.registry.ProjectRegistry r) {}`
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté dans la copie
- **THEN** le garde se termine avec le code 1 en A11 et nomme `leakGeneric` et `registry.ProjectRegistry`

#### Scenario: Retour typé moteur d'une méthode générique
- **GIVEN** une copie du dépôt où `MinosApplication` déclare `public <T> com.minos.registry.ProjectRegistry leakRet() { return null; }`
- **WHEN** le garde est exécuté dans la copie
- **THEN** il se termine avec le code 1 en A11 et nomme `leakRet`

#### Scenario: Retour typé moteur d'une méthode statique
- **GIVEN** l'auto-test du garde, avec `public static ProjectRegistry leakStatic()` puis `public static <T> ProjectRegistry leakStaticGeneric()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Type du moteur porté par une borne
- **GIVEN** l'auto-test du garde, avec `public <T extends ProjectRegistry> void leakBound(T r)` puis `public <T extends java.util.Map<String, ProjectRegistry>> T nested()`
- **WHEN** `python scripts/architecture/test_check_module_boundaries_ratchets.py` est exécuté
- **THEN** chaque variante est refusée en A11, message nommant la méthode et `registry.ProjectRegistry`

#### Scenario: Import joker d'un package du moteur
- **GIVEN** l'auto-test du garde, avec `import com.minos.registry.*;` dans `MinosApplication`
- **WHEN** le même auto-test est exécuté
- **THEN** le cas constate le refus en A11 et le message qui nomme `com.minos.registry`

#### Scenario: Méthode générique sans type du moteur
- **GIVEN** l'auto-test du garde, avec `public <T extends Comparable<T>> java.util.List<T> sorted(java.util.List<T> values)`
- **WHEN** le même auto-test est exécuté
- **THEN** la règle des paramètres et celle des retours réussissent

#### Scenario: Façade au HEAD
- **WHEN** `python scripts/architecture/check-module-boundaries.py` est exécuté sur le dépôt
- **THEN** il se termine avec le code 0
