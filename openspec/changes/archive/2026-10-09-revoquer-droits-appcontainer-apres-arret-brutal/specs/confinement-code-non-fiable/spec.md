# Spec Delta

## ADDED Requirements

### Requirement: Les autorisations accordées à un conteneur sont retirées même après un arrêt brutal
Toute autorisation qu'un lanceur Windows accorde au SID d'un conteneur sur un chemin de l'hôte SHALL être journalisée dans le répertoire de récupération du run avant d'être appliquée, et SHALL être retirée par la fin normale du run ou, si le lanceur est tué, par la reprise qui prouve la mort de son propriétaire. Aucune autorisation d'un run terminé ou mort SHALL NOT subsister après cette reprise.

#### Scenario: Lanceur tué après l'octroi
- **WHEN** un lanceur accorde la lecture d'un répertoire de runtime à son conteneur puis est tué avant son nettoyage
- **THEN** la reprise suivante, qui prouve la mort du propriétaire, retire l'entrée de ce SID sur le répertoire

#### Scenario: Run vivant
- **WHEN** un autre lanceur examine un run dont le propriétaire est vivant
- **THEN** il ne retire aucune autorisation de ce run

#### Scenario: Propriété non prouvable
- **WHEN** la mort du propriétaire ne peut pas être prouvée
- **THEN** aucune autorisation n'est retirée et le diagnostic signale le run

### Requirement: Les autorisations orphelines existantes sont recensées
Un diagnostic SHALL lister les entrées d'ACL de SID d'AppContainer présentes sur les chemins que MINOS accorde et qu'aucun run vivant ne possède, sans les retirer sans demande explicite de l'utilisateur.

#### Scenario: Poste avec entrées accumulées
- **WHEN** la racine d'un runtime porte des entrées `S-1-15-2-…` sans run propriétaire vivant
- **THEN** le diagnostic les compte et nomme le chemin, et ne modifie rien sans confirmation
