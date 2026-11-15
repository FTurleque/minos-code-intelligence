# Spec Delta

## ADDED Requirements

### Requirement: Les noms comparés par la dérivation des tests liés sont normalisés par une règle stable
Pour rapprocher un test de son code de production, la dérivation SHALL comparer des noms mis en minuscules sans dépendre de la langue de l'hôte, puis réduits aux lettres `a` à `z` et aux chiffres `0` à `9`. Un nom absent ou sans aucun de ces caractères SHALL donner un nom vide, qui MUST NOT rapprocher un test d'un symbole. Cette règle SHALL rester la même quelle que soit la façon dont elle est calculée.

#### Scenario: Nom mixte
- **GIVEN** un symbole de production `Order_Service` et un test `OrderServiceTest` du même projet
- **WHEN** les tests liés sont dérivés
- **THEN** le test est lié au symbole par la convention de nom

#### Scenario: Caractère non ASCII dont la minuscule est une lettre ASCII
- **GIVEN** un symbole de production dont le nom contient le signe kelvin `K` (U+212A) à la place de `K`, et un test nommé avec `k`
- **WHEN** les tests liés sont dérivés
- **THEN** le test est lié au symbole, comme avant le changement

#### Scenario: Langue de l'hôte turque
- **GIVEN** la langue par défaut de la JVM fixée à `tr-TR`, un symbole `INDEX` et un test `IndexTest`
- **WHEN** les tests liés sont dérivés
- **THEN** le test est lié au symbole

#### Scenario: Nom sans lettre ni chiffre ASCII
- **GIVEN** un test dont ni le nom ni le nom de fichier, une fois leur suffixe de test retiré, ne contiennent de lettre ASCII ou de chiffre
- **WHEN** les tests liés sont dérivés
- **THEN** aucun lien par convention de nom n'est créé pour ce test

#### Scenario: Sorties inchangées
- **GIVEN** les goldens de caractérisation des tests liés et de l'impact
- **WHEN** la suite de `minos-app` est exécutée après le changement
- **THEN** elle réussit sans qu'aucun golden soit régénéré
