## ADDED Requirements

### Requirement: Une synchronisation sans changement n'écrit rien
Quand l'index sémantique en place est prêt et que son snapshot, son provider, son modèle, ses dimensions et sa version de documents sont ceux de la cible, la synchronisation SHALL se terminer sans construire de document, sans demander d'embedding et sans écrire dans le store.

#### Scenario: Indexation sans changement
- **GIVEN** un index sémantique prêt pour le snapshot actif et un store à compteurs
- **WHEN** `minos index` se termine en `NO_CHANGES` et déclenche la synchronisation
- **THEN** les compteurs de documents construits, d'embeddings demandés et d'écritures valent 0

#### Scenario: Modèle changé
- **GIVEN** un index prêt pour le même snapshot mais un autre modèle d'embeddings
- **WHEN** la synchronisation est exécutée
- **THEN** elle suit le chemin complet et l'index final porte le nouveau modèle

### Requirement: L'écriture PostgreSQL est proportionnelle aux documents modifiés
La synchronisation sous PostgreSQL SHALL n'écrire d'embedding que pour les documents nouveaux ou dont le contenu a changé, et SHALL décider de la réutilisation sans lire les vecteurs en place. Un document réutilisé SHALL recevoir son rattachement au nouveau snapshot et ses positions à jour.

#### Scenario: Un document modifié sur N
- **GIVEN** un index de N documents et une cible où un seul document a changé de contenu
- **WHEN** la synchronisation est exécutée
- **THEN** un seul embedding est demandé et un seul vecteur est écrit

#### Scenario: Document déplacé sans changement de contenu
- **GIVEN** un document dont seules les lignes de début et de fin ont changé
- **WHEN** la synchronisation est exécutée
- **THEN** aucun vecteur n'est écrit et la ligne porte les nouvelles positions et le nouveau snapshot

#### Scenario: Document sans vecteur non marqué comme réutilisé
- **GIVEN** un document transmis au store sans vecteur et sans marque de réutilisation
- **WHEN** la synchronisation est exécutée
- **THEN** elle échoue et l'index précédent est inchangé

### Requirement: Le différentiel est équivalent à un remplacement complet
Après une synchronisation différentielle, le contenu de l'index SHALL être identique, ligne à ligne, à celui qu'aurait produit un remplacement complet de la même cible. Un échec en cours d'écriture SHALL laisser l'index précédent intact.

#### Scenario: Équivalence sur les cas de mutation
- **GIVEN** les cas ajout, modification, suppression, déplacement, changement de modèle, cible vide et tout réutilisé
- **WHEN** chaque cas est synchronisé par différentiel puis, sur une base témoin, par remplacement complet
- **THEN** les deux tables sont égales ligne à ligne

#### Scenario: Échec en cours d'écriture
- **GIVEN** une écriture qui échoue après la suppression des documents retirés
- **WHEN** la transaction est annulée
- **THEN** la lecture de l'index rend l'état d'avant la synchronisation
