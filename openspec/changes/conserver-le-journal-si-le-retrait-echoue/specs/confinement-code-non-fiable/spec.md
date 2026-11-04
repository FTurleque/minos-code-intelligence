## MODIFIED Requirements

### Requirement: Les autorisations accordées à un conteneur sont retirées même après un arrêt brutal
Toute autorisation qu'un lanceur Windows accorde au SID d'un conteneur sur un chemin de l'hôte SHALL être journalisée dans le répertoire de récupération du run avant d'être appliquée, et SHALL être retirée par la fin normale du run ou, si le lanceur est tué, par la reprise qui prouve la mort de son propriétaire. Aucune autorisation d'un run terminé ou mort SHALL NOT subsister après cette reprise. Un retrait non prouvé SHALL laisser le journal en place.

#### Scenario: Lanceur tué après l'octroi
- **WHEN** un lanceur accorde la lecture d'un répertoire de runtime à son conteneur puis est tué avant son nettoyage
- **THEN** la reprise suivante, qui prouve la mort du propriétaire, retire l'entrée de ce SID sur le répertoire

#### Scenario: Run vivant
- **WHEN** un autre lanceur examine un run dont le propriétaire est vivant
- **THEN** il ne retire aucune autorisation de ce run

#### Scenario: Propriété non prouvable
- **WHEN** la mort du propriétaire ne peut pas être prouvée
- **THEN** aucune autorisation n'est retirée et le diagnostic signale le run

#### Scenario: Retrait en échec à la fin du run
- **GIVEN** un run dont le répertoire de travail porte l'entrée du SID de son conteneur et refuse au propriétaire la modification de sa liste d'accès
- **WHEN** le provider se termine et que le lanceur nettoie ce run
- **THEN** le lanceur rend le code de sortie du provider et un avertissement nomme le journal conservé
- **AND** le journal de reprise et son fichier de verrou restent dans le répertoire de récupération

#### Scenario: Retrait en échec pendant la reprise
- **GIVEN** un journal de reprise dont le propriétaire est prouvé mort et dont un chemin journalisé refuse au propriétaire la modification de sa liste d'accès
- **WHEN** un lanceur démarre et reprend ce journal
- **THEN** un avertissement nomme le journal conservé et le lanceur exécute son provider
- **AND** le journal et son fichier de verrou restent en place, puis un lanceur suivant, une fois le retrait possible, les supprime
