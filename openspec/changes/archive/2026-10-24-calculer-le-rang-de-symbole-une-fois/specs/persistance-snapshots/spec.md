## ADDED Requirements

### Requirement: Le rang de correspondance d'un symbole est calculé une fois par candidat
La recherche de symboles par texte de la vue en mémoire SHALL calculer le rang de correspondance au plus une fois par candidat resté après les filtres de projet, de nature, de module et de nom qualifié, et SHALL réutiliser ce rang pour le filtre et pour le tri. Une recherche sans texte SHALL NOT calculer de rang. Un critère absent SHALL être refusé avant tout calcul.

#### Scenario: Recherche par texte sur tout un projet
- **GIVEN** une vue de N symboles d'un même projet, une fonction de rang qui compte ses appels, et un texte dont les correspondances exactes n'atteignent pas la limite
- **WHEN** le test de comptage de `minos-engine` est exécuté par `./mvnw -B -ntp -pl minos-engine -am test`
- **THEN** la fonction de rang a été appelée au plus N fois

#### Scenario: Recherche sans texte
- **GIVEN** la même vue et le même compteur, et un critère par nature seule
- **WHEN** la recherche est exécutée par le même test
- **THEN** la fonction de rang n'a pas été appelée

#### Scenario: Critère absent
- **GIVEN** une vue quelconque et le même compteur
- **WHEN** la recherche est appelée avec un critère nul
- **THEN** elle échoue par une `IllegalArgumentException` et la fonction de rang n'a pas été appelée

### Requirement: Le calcul unique du rang ne change ni le contenu ni l'ordre des résultats
Calculer le rang une seule fois SHALL NOT modifier le contenu ni l'ordre des résultats d'une recherche de symboles. Les sept rangs de correspondance, dont la correspondance sur la clé de symbole classée après toutes les autres, et le départage des ex æquo SHALL rester ceux d'avant la modification. Un symbole sans correspondance SHALL rester absent.

#### Scenario: Caractérisation de l'ordre
- **GIVEN** un jeu de symboles qui couvre chaque rang de correspondance et plusieurs ex æquo, et la liste de résultats capturée avant la modification
- **WHEN** la même recherche est exécutée après la modification par `./mvnw -B -ntp -pl minos-engine -am test`
- **THEN** la liste obtenue est identique à la liste capturée, élément par élément

#### Scenario: Correspondance sur la seule clé de symbole
- **GIVEN** un symbole dont seule la clé contient le texte cherché
- **WHEN** la recherche est exécutée
- **THEN** ce symbole est rendu, après tous les symboles qui correspondent par leur nom ou leur nom qualifié

#### Scenario: Symbole sans correspondance
- **GIVEN** un symbole dont ni le nom, ni le nom qualifié, ni la clé ne contiennent le texte cherché
- **WHEN** la recherche est exécutée
- **THEN** ce symbole n'est pas rendu

#### Scenario: Sorties des surfaces inchangées
- **GIVEN** les goldens de `minos-app/src/test/resources/characterization/` du HEAD
- **WHEN** `./mvnw -B -ntp -pl minos-app -am verify` est exécuté après la modification
- **THEN** `A2SurfaceCharacterizationTest` passe sans qu'aucun fichier de référence ait été modifié
