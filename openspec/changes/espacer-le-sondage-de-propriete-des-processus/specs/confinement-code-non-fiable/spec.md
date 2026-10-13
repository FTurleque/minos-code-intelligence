## ADDED Requirements

### Requirement: Le suivi de propriété par sondage est espacé sans rien céder au nettoyage
En régime établi, le suivi de propriété d'un processus provider SHALL énumérer ses descendants au plus 15 fois par seconde. Il SHALL sonder dès sa création et à l'arrêt, et SHALL continuer de terminer tout descendant observé pendant que la racine vivait. Un descendant encore vivant après l'arrêt forcé, ou un suivi interrompu, SHALL faire échouer le nettoyage : l'espacement SHALL NOT masquer cet échec.

#### Scenario: Cadence en régime établi
- **GIVEN** un suivi attaché à un processus factice vivant dont l'énumération des descendants compte ses appels
- **WHEN** deux secondes s'écoulent après la phase initiale
- **THEN** l'énumération a été appelée au moins une fois et au plus 30 fois

#### Scenario: Enfant détaché observé puis nettoyé
- **GIVEN** un provider qui lance un enfant détaché puis sort normalement une seconde plus tard
- **WHEN** l'exécution se termine
- **THEN** l'enfant détaché a été terminé par le suivi

#### Scenario: Survivant après l'arrêt forcé
- **GIVEN** un descendant observé qui reste vivant après l'arrêt forcé
- **WHEN** le suivi est fermé
- **THEN** la fermeture échoue avec l'erreur de nettoyage incomplet
