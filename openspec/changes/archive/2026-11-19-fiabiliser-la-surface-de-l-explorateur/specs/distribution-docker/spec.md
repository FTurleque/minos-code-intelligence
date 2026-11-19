## ADDED Requirements

### Requirement: Le journal des services Docker publiés sur la boucle locale est borné
Les services `minos-explorer` et `minos-pgadmin` du fichier Compose connecté SHALL déclarer le pilote de journal `json-file` avec une taille maximale par fichier et un nombre maximal de fichiers. Le gate des plafonds Compose SHALL échouer si l'un de ces services ne borne pas son journal.

#### Scenario: Services bornés
- **GIVEN** le fichier `docker/compose-mcp.connected.yaml` du dépôt
- **WHEN** `python scripts/quality/check-compose-limits.py` est exécuté
- **THEN** il réussit, et les deux services portent `max-size` et `max-file`

#### Scenario: Borne retirée
- **GIVEN** un arbre où le service `minos-explorer` n'a plus de clé `logging`, ou a `max-size` sans `max-file`
- **WHEN** le gate est exécuté sur cet arbre (`python scripts/quality/test_check_compose_limits.py`)
- **THEN** il échoue en nommant le service et la clé manquante

#### Scenario: Pilote sans borne
- **GIVEN** un arbre où le service déclare un pilote de journal autre que `json-file` sans option de taille
- **WHEN** le gate est exécuté
- **THEN** il échoue
