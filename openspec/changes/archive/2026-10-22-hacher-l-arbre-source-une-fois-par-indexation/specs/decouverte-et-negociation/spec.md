## ADDED Requirements

### Requirement: Une indexation à cible racine hache l'arbre source au plus deux fois
Une indexation réussie dont une cible est à la racine du projet SHALL capturer au plus deux empreintes complètes de l'arbre source : celle de référence et celle de fin de run. L'empreinte du scope racine enregistrée au point de reprise SHALL être celle de référence, réutilisée. Si l'égalité des politiques d'empreinte n'est pas établie, l'empreinte de scope SHALL être recalculée.

#### Scenario: Cible racine
- **GIVEN** un projet à cible racine et un service d'empreinte qui compte ses captures
- **WHEN** une indexation complète réussit
- **THEN** le compteur vaut au plus 2

#### Scenario: Politique d'empreinte non prouvée égale
- **GIVEN** une orchestration dont le service d'empreinte n'est pas celui de l'exécuteur de run
- **WHEN** une indexation à cible racine s'exécute
- **THEN** l'empreinte du scope racine est recalculée et n'est pas reprise de l'empreinte de référence

#### Scenario: Scope non racine
- **GIVEN** un projet dont une cible est dans un sous-répertoire
- **WHEN** l'indexation s'exécute
- **THEN** l'empreinte de ce scope est capturée une fois pour ce scope, comme avant

### Requirement: La réutilisation ne change ni l'empreinte persistée ni la preuve de stabilité
L'empreinte de scope persistée au point de reprise SHALL être identique, à l'octet, à celle que produit la capture du scope. L'empreinte de fin de run SHALL rester un hachage complet du contenu des fichiers et SHALL NOT être déduite de la taille ou de la date de modification.

#### Scenario: Égalité avec la capture du scope
- **GIVEN** un arbre source figé
- **WHEN** l'empreinte réutilisée pour le scope racine est comparée à la capture directe de ce scope
- **THEN** les deux valeurs sont égales

#### Scenario: Reprise d'un run antérieur au changement
- **GIVEN** un run interrompu dont le point de reprise a été écrit avant le changement, sur un arbre inchangé
- **WHEN** l'indexation est relancée avec reprise
- **THEN** la cible est réutilisée et la reprise n'est pas refusée pour empreinte différente

#### Scenario: Fichier modifié sans changement de taille ni de date
- **GIVEN** un fichier source dont le contenu change pendant le run, taille et date de modification restaurées
- **WHEN** le run se termine
- **THEN** l'empreinte de référence n'est pas promue et un diagnostic signale que l'espace de travail a changé
