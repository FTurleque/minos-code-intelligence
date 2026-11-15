## ADDED Requirements

### Requirement: Les valeurs d'énumération du contrat v1 de l'explorateur sont figées
Les valeurs que l'API HTTP v1 de l'explorateur, l'API Java d'exploration et la sortie JSON de `minos semantic projection` sérialisent pour l'état d'index, l'état de projection, la divergence, le refus de préparation, le mode de recherche, la nature de score et la nature de document SHALL être tenues, champ par champ, par un test de contrat. Les huit valeurs françaises de v1 SHALL former une liste close : aucune valeur n'y est ajoutée, renommée ni retirée dans v1.

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** le test de contrat des valeurs de l'explorateur est exécuté
- **THEN** il réussit, et la réponse de l'état de projection porte `PRETE`, `PERIMEE`, `ABSENTE` ou `ECHOUEE` comme avant

#### Scenario: Constante renommée dans v1
- **GIVEN** une copie du dépôt où `PRETE` est renommée `READY` dans l'énumération de l'état de projection, le renommage étant propagé à tous ses usages pour que le dépôt compile
- **WHEN** le test de contrat est exécuté
- **THEN** il échoue en nommant le champ, la valeur attendue `PRETE` et la valeur trouvée

#### Scenario: Valeur française ajoutée
- **GIVEN** une copie du dépôt où une neuvième valeur est ajoutée à la liste close des valeurs françaises
- **WHEN** le test de contrat est exécuté
- **THEN** il échoue : la liste close compte exactement huit valeurs

### Requirement: Toute erreur de l'API de l'explorateur porte un code d'une liste fermée
Toute réponse d'erreur de l'API de l'explorateur SHALL porter un champ `error.code` pris dans une liste fermée, écrite en majuscules et en anglais, tenue par un test de contrat. Le champ `error.message` SHALL rester présent comme diagnostic, sans chemin absolu ni cause interne. Un code SHALL NOT être renommé ni retiré dans v1 ; un code nouveau s'ajoute à la liste par une édition visible.

#### Scenario: Route inconnue
- **GIVEN** la surface démarrée et un jeton valide
- **WHEN** une requête vise une route absente de `/api/v1/`
- **THEN** la réponse est une erreur dont `error.code` vaut `NOT_FOUND` et dont `error.message` est une chaîne non vide

#### Scenario: Code renommé
- **GIVEN** une copie du dépôt où le code `BUSY` est renommé dans la liste fermée, le renommage étant propagé à tous ses usages pour que le dépôt compile
- **WHEN** le test de contrat des codes est exécuté
- **THEN** il échoue en nommant `BUSY`, attendu, et le code trouvé à sa place

#### Scenario: Code de résolution de projet non repris
- **GIVEN** une copie du dépôt où le résolveur de projets reçoit un code d'erreur que la liste fermée ne reprend pas
- **WHEN** le test de contrat des codes est exécuté
- **THEN** il échoue en nommant ce code

### Requirement: La page de l'explorateur traduit le code d'erreur
La page SHALL afficher, pour une erreur de l'API, un libellé français choisi d'après `error.code` et SHALL NOT afficher `error.message`. Pour un code sans libellé, ou une réponse sans code, elle SHALL afficher un message français générique qui cite le code reçu. Chaque code de la liste fermée SHALL avoir un libellé.

#### Scenario: Serveur occupé
- **GIVEN** une réponse 503 dont `error.code` vaut `BUSY` et dont `error.message` est une phrase anglaise
- **WHEN** la page traite la réponse
- **THEN** le bandeau affiche le libellé français du code, et la phrase anglaise n'apparaît nulle part dans la page

#### Scenario: Code inconnu de la page
- **GIVEN** une réponse d'erreur dont le code n'a pas de libellé
- **WHEN** la page traite la réponse
- **THEN** le bandeau affiche le message générique français suivi du code, inséré comme texte et jamais comme balisage

#### Scenario: Code de la liste sans libellé
- **GIVEN** la liste fermée des codes augmentée d'un code que la page ne traduit pas
- **WHEN** les tests du front sont exécutés
- **THEN** ils échouent en nommant le code sans libellé
