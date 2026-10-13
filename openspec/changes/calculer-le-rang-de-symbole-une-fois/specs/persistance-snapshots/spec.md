## ADDED Requirements

### Requirement: Le rang de correspondance d'un symbole est calculé une fois par candidat
La recherche de symboles par texte de la vue en mémoire SHALL calculer le rang de correspondance au plus une fois par candidat resté après les filtres de projet, de nature, de module et de nom qualifié, et SHALL réutiliser ce rang pour le filtre et pour le tri. Une recherche sans texte SHALL NOT calculer de rang. Un critère absent SHALL être refusé.

#### Scenario: Recherche par texte sur tout un projet
- **GIVEN** une vue de N symboles d'un même projet et une fonction de rang qui compte ses appels
- **WHEN** une recherche par texte dont les candidats sont tous les symboles du projet est exécutée
- **THEN** la fonction de rang a été appelée au plus N fois

#### Scenario: Recherche sans texte
- **GIVEN** la même vue et le même compteur
- **WHEN** une recherche sans texte est exécutée
- **THEN** la fonction de rang n'a pas été appelée

#### Scenario: Critère absent
- **GIVEN** une vue quelconque
- **WHEN** la recherche est appelée avec un critère nul
- **THEN** elle échoue par une `IllegalArgumentException` et aucun rang n'est calculé

### Requirement: Le calcul unique du rang ne change ni le contenu ni l'ordre des résultats
Calculer le rang une seule fois SHALL NOT modifier le contenu ni l'ordre des résultats d'une recherche de symboles. Les sept rangs de correspondance, dont la correspondance sur la clé de symbole classée après toutes les autres, et le départage des ex æquo SHALL rester ceux d'avant la modification. Un symbole sans correspondance SHALL rester absent.

#### Scenario: Caractérisation de l'ordre
- **GIVEN** un jeu de symboles qui couvre chaque rang de correspondance et plusieurs ex æquo, et la liste de résultats capturée avant la modification
- **WHEN** la même recherche est exécutée après la modification
- **THEN** la liste obtenue est identique à la liste capturée, élément par élément

#### Scenario: Correspondance sur la seule clé de symbole
- **GIVEN** un symbole dont seul la clé contient le texte cherché
- **WHEN** la recherche est exécutée
- **THEN** ce symbole est rendu, après tous les symboles qui correspondent par leur nom ou leur nom qualifié

#### Scenario: Symbole sans correspondance
- **GIVEN** un symbole dont ni le nom, ni le nom qualifié, ni la clé ne contiennent le texte cherché
- **WHEN** la recherche est exécutée
- **THEN** ce symbole n'est pas rendu
