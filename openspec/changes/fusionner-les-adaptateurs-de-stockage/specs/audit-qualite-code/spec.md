## MODIFIED Requirements

### Requirement: Un cache LRU pondéré n'est écrit qu'une fois
Tout cache à ordre d'accès borné en poids du code de production SHALL utiliser le cache partagé. Un gate SHALL échouer sur une carte à ordre d'accès construite hors du cache partagé, sauf pour les fichiers d'une liste fermée dont la raison est écrite, et SHALL échouer sur une entrée de liste devenue sans objet. La migration SHALL laisser inchangés les noms et les valeurs des statistiques publiques de chaque cache.

#### Scenario: Cache réécrit à la main
- **GIVEN** une copie temporaire du dépôt où un fichier de `src/main` hors liste construit `new LinkedHashMap<>(16, 0.75f, true)`
- **WHEN** `python scripts/quality/check-bounded-helpers.py --self-test` y exécute le gate
- **THEN** le gate échoue en nommant le fichier et la ligne

#### Scenario: Exception déclarée
- **GIVEN** un fichier de la liste fermée, avec sa raison
- **WHEN** le gate est exécuté
- **THEN** ce fichier est accepté et la liste est affichée dans la sortie

#### Scenario: Entrée de liste périmée
- **GIVEN** une liste fermée qui nomme un fichier absent ou sans carte à ordre d'accès
- **WHEN** le gate est exécuté
- **THEN** il échoue en nommant l'entrée périmée

#### Scenario: Statistiques inchangées après migration
- **GIVEN** les tests existants des caches migrés, sans modification de leurs assertions
- **WHEN** `./mvnw -B -ntp -pl minos-application,minos-storage,minos-bootstrap -am test` est exécuté
- **THEN** ils réussissent
