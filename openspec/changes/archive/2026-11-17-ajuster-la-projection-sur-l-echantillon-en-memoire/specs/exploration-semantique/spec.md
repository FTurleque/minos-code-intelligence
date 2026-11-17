## ADDED Requirements

### Requirement: La préparation ne relit pas l'index à chaque itération quand l'échantillon tient en mémoire
Quand les vecteurs de l'échantillon tiennent dans le budget de mémoire de la préparation, le système SHALL lire l'index au plus deux fois (sélection, puis copie de l'échantillon) et SHALL calculer centrage, ajustement et projection sans autre lecture du store. La mémoire de l'échantillon SHALL être comptée sur ce budget.

#### Scenario: Échantillon dans le budget
- **GIVEN** un index de 2 000 documents, un plafond de 500 points et un budget qui contient 500 vecteurs
- **WHEN** la préparation s'exécute sur un store qui compte ses parcours complets
- **THEN** le store a été parcouru deux fois, quel que soit le nombre d'itérations
- **AND** le rapport annonce la stratégie en mémoire et un pic de mémoire au plus égal au budget

#### Scenario: Index remplacé pendant la copie
- **GIVEN** une préparation dont la sélection est faite
- **WHEN** l'index est remplacé avant la fin de la copie de l'échantillon
- **THEN** la préparation est refusée avec le motif « index modifié pendant la préparation »
- **AND** aucune projection n'est publiée et la projection précédente est intacte

### Requirement: Un échantillon plus lourd que le budget garde le parcours en flux et l'annonce
Quand les vecteurs de l'échantillon dépassent le budget de mémoire, le système SHALL préparer la projection par le parcours en flux, sans retenir l'échantillon, et SHALL annoncer cette stratégie dans le rapport avec l'option qui relève le budget. Le système SHALL NOT dépasser le budget pour éviter des lectures.

#### Scenario: Repli annoncé
- **GIVEN** un index de 2 000 documents, un plafond de 500 points et un budget qui ne contient pas 500 vecteurs mais suffit au parcours en flux
- **WHEN** la préparation s'exécute
- **THEN** la projection est publiée et le rapport annonce la stratégie en flux et nomme `--max-memory-mb`
- **AND** le pic de mémoire rapporté est au plus égal au budget

#### Scenario: Budget insuffisant même en flux
- **GIVEN** un budget qui ne contient ni l'échantillon ni la sélection
- **WHEN** la préparation s'exécute
- **THEN** la commande échoue avec le refus de budget, un message actionnable et un code de sortie non nul
- **AND** aucune projection n'est publiée

### Requirement: Les deux stratégies de préparation rendent les mêmes coordonnées
Pour un même index, une même sélection et une même graine, le système SHALL publier les mêmes points, coordonnées et parts de variance, que l'échantillon ait été itéré en mémoire ou relu en flux.

#### Scenario: Égalité des projections
- **GIVEN** un index inchangé de 2 000 documents et une graine
- **WHEN** la préparation est lancée une fois avec un budget qui contient l'échantillon, une fois avec un budget qui force le flux
- **THEN** les deux projections ont les mêmes clés stables, les mêmes coordonnées et les mêmes parts de variance
- **AND** seuls la stratégie, le nombre de passes et le pic de mémoire diffèrent dans les rapports
