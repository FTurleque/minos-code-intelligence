## ADDED Requirements

### Requirement: Le service géré minos-ollama n'est joint que depuis le runtime Docker
Le fournisseur d'embeddings Ollama SHALL n'émettre une requête vers l'hôte `minos-ollama` que si l'emplacement d'exécution résolu est Docker. Hors Docker, il SHALL refuser avant toute résolution de nom ou connexion, par une erreur de configuration qui nomme le paramètre d'endpoint, et SHALL NOT se rabattre sur un autre endpoint. Une valeur d'emplacement inconnue SHALL être refusée.

#### Scenario: Runtime Docker
- **GIVEN** un fournisseur configuré sur `http://minos-ollama:11434/api/embed` avec l'emplacement Docker
- **WHEN** il est construit
- **THEN** l'endpoint est accepté

#### Scenario: Runtime natif
- **GIVEN** le même endpoint avec l'emplacement natif
- **WHEN** `embed` est appelé
- **THEN** l'appel échoue par une erreur de configuration et aucune connexion n'est tentée

#### Scenario: Emplacement inconnu
- **GIVEN** `MINOS_RUNTIME_LOCATION=podman`
- **WHEN** la configuration sémantique est résolue
- **THEN** elle échoue en nommant la valeur refusée

### Requirement: Un MINOS_HOME configuré pour Docker reste ouvrable par un lanceur natif
Quand la configuration désigne `minos-ollama` et que l'emplacement d'exécution n'est pas Docker, l'ouverture de l'application SHALL réussir et les opérations qui n'utilisent pas le fournisseur d'embeddings SHALL fonctionner. Le statut de l'index sémantique SHALL signaler que le fournisseur configuré est inutilisable depuis cet emplacement.

#### Scenario: Commande sans recherche sémantique
- **GIVEN** un `MINOS_HOME` dont `minos.properties` désigne `minos-ollama`, ouvert par un lanceur natif
- **WHEN** la liste des projets est demandée
- **THEN** la commande réussit

#### Scenario: Statut sémantique
- **GIVEN** le même `MINOS_HOME` ouvert en natif
- **WHEN** le statut de l'index sémantique est demandé
- **THEN** la réponse signale le fournisseur inutilisable hors Docker, sans tenter de connexion

### Requirement: Le corps d'une réponse d'erreur Ollama est borné et assaini
Pour un statut HTTP hors 2xx, le fournisseur SHALL lire une quantité bornée du corps, très inférieure à la limite des réponses valides. Le message de l'exception SHALL porter le statut et au plus 512 caractères du corps, sur une ligne, soumis au détecteur de message sensible ; un extrait jugé sensible SHALL être omis.

#### Scenario: Corps d'erreur volumineux
- **GIVEN** un serveur local de test qui répond 500 avec un corps de 1 Mio
- **WHEN** `embed` est appelé
- **THEN** l'exception nomme le statut 500 et son message compte moins de 600 caractères

#### Scenario: Corps d'erreur contenant un chemin absolu
- **GIVEN** un serveur local de test qui répond 500 avec un corps contenant `/home/u/models`
- **WHEN** `embed` est appelé
- **THEN** le message de l'exception nomme le statut et ne contient pas ce chemin
