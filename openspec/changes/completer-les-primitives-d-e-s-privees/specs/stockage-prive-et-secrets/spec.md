## ADDED Requirements

### Requirement: Une écriture en flux dans le stockage privé ne suit jamais un lien
Le moteur SHALL offrir trois écritures en flux : créer ou durcir un fichier privé, créer exclusivement un fichier privé durci, créer un fichier nouveau dans un arbre déjà privé sans réécrire de DACL. Toutes SHALL refuser un lien et un objet spécial, les deux dernières un fichier existant, sans nommer de chemin absolu.

#### Scenario: Lien à la place du fichier
- **GIVEN** un lien symbolique à l'emplacement d'un fichier à écrire
- **WHEN** `./mvnw -B -ntp -pl minos-engine -am test` exécute `PrivateLocalStorageWritesTest`
- **THEN** l'ouverture échoue et la cible du lien est inchangée

#### Scenario: Création exclusive d'un nom déjà pris
- **GIVEN** un fichier existant au nom d'un fichier à créer exclusivement
- **WHEN** le test de `createPrivateOutputStream` s'exécute
- **THEN** la création échoue et le contenu existant est intact

#### Scenario: Fichier d'arbre déjà présent
- **GIVEN** un fichier existant à l'emplacement d'un fichier d'arbre à créer
- **WHEN** le test de l'ouverture « arbre privé » s'exécute
- **THEN** l'ouverture échoue et le contenu existant est intact

#### Scenario: Aucune entrée de liste nouvelle
- **GIVEN** le dépôt après ce changement
- **WHEN** `python scripts/architecture/check-private-io.py` est exécuté
- **THEN** il réussit sans qu'aucun maximum d'une entrée de `PrivateLocalStorage` ait augmenté

### Requirement: La lecture par canal et la publication de répertoire échouent fermées
Le moteur SHALL offrir une lecture par canal d'un fichier régulier qui refuse lien et objet spécial, et une publication de répertoire par rename atomique vers un nom inexistant. Un système de fichiers sans rename atomique SHALL faire échouer la publication, sans repli par copie.

#### Scenario: Canal sur un lien
- **GIVEN** un lien symbolique à la place du fichier à lire
- **WHEN** `ConfinedFileOpenerTest` ouvre le canal
- **THEN** une `ConfinementException` sans chemin est levée

#### Scenario: Répertoire vers une cible existante
- **GIVEN** un répertoire source et une cible qui existe déjà
- **WHEN** `DurableAtomicFileTest` publie le répertoire
- **THEN** la publication échoue et la source est intacte

#### Scenario: Rename atomique non supporté
- **GIVEN** une plateforme simulée où `ATOMIC_MOVE` est refusé
- **WHEN** le répertoire est publié
- **THEN** une `IOException` est levée et aucune copie n'est faite
