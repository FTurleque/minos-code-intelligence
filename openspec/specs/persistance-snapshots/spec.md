# persistance-snapshots Specification

## Purpose
Garantit qu'un snapshot de connaissance, une empreinte de projet et le schéma PostgreSQL restent dans un état
valide après un arrêt brutal ou une migration : le snapshot actif est toujours l'ancien ou le nouveau, entier ;
un pointeur actif illisible fait échouer l'ouverture de façon explicite au lieu de basculer en silence ; une
migration qui échoue n'est jamais appliquée à moitié ; les fichiers temporaires laissés par un arrêt sont
récupérés sans toucher une écriture en cours.

## Requirements

### Requirement: Un arrêt brutal pendant une publication laisse le snapshot actif précédent intact
Si le processus qui publie un snapshot local s'arrête brutalement entre l'écriture du fichier temporaire et son renommage, la réouverture du magasin SHALL exposer le snapshot actif précédent, intact et vérifiable, et SHALL NOT exposer le snapshot partiellement publié.

#### Scenario: Processus tué après l'écriture temporaire
- **WHEN** un processus publie un snapshot, s'arrête après l'écriture du fichier temporaire, et est tué
- **THEN** un nouveau processus qui ouvre le magasin lit le snapshot actif précédent, dont la somme de contrôle est valide

#### Scenario: Processus tué après le renommage du snapshot mais avant celui du pointeur
- **WHEN** le processus est tué après la publication du fichier de snapshot et avant la mise à jour du pointeur actif
- **THEN** la réouverture expose toujours le snapshot actif précédent

### Requirement: Un pointeur actif illisible fait échouer l'ouverture de façon explicite
Un fichier de pointeur actif vide, tronqué ou de version inconnue SHALL faire échouer l'ouverture avec un message qui nomme le fichier, et SHALL NOT basculer silencieusement vers un autre snapshot.

#### Scenario: Pointeur vide
- **WHEN** `active.pointer` existe et est vide
- **THEN** l'ouverture échoue avec un message qui nomme le pointeur

#### Scenario: Pointeur tronqué
- **WHEN** `active.pointer` est coupé au milieu de son contenu
- **THEN** l'ouverture échoue fermée, sans retour à un snapshot plus ancien

### Requirement: Une base PostgreSQL peuplée migre jusqu'à la version courante sans perte
L'ouverture du stockage PostgreSQL sur une base peuplée à une version de schéma antérieure SHALL la migrer jusqu'à la version courante en une transaction, et les données existantes SHALL rester lisibles par l'API du magasin.

#### Scenario: Base v1 peuplée
- **WHEN** le stockage s'ouvre sur une base au schéma v1 contenant un projet et un snapshot
- **THEN** `schema_version` vaut la version courante et le projet et son snapshot se relisent à l'identique

#### Scenario: Échec pendant la migration
- **WHEN** une étape de migration échoue
- **THEN** la transaction est annulée, `schema_version` est inchangé et l'ouverture échoue avec un message qui nomme l'étape

### Requirement: Les pointeurs temporaires orphelins sont récupérés sans toucher une écriture en cours
Si la récupération est décidée, un fichier `.active-*.tmp` plus ancien que la borne de rétention des résidus SHALL être supprimé sous le verrou de mutation du projet, et un fichier plus récent ou en cours d'écriture SHALL NOT l'être.

#### Scenario: Orphelin ancien
- **WHEN** la rétention s'exécute et qu'un `.active-*.tmp` a plus de 24 heures
- **THEN** il est supprimé et la suppression est comptée dans le résultat de la rétention

#### Scenario: Écriture en cours
- **WHEN** un `.active-*.tmp` a moins de 24 heures
- **THEN** il est conservé

### Requirement: La validation réseau des connexions du pool PostgreSQL est bornée
Le pool SHALL NOT valider une connexion par un aller-retour réseau à sa restitution. À l'emprunt, il SHALL ne la valider que si elle est restée au repos au-delà du seuil d'inactivité ; une connexion invalide SHALL être fermée et remplacée.

#### Scenario: Emprunt puis restitution immédiats
- **GIVEN** un pool dont la connexion compte ses appels de validation
- **WHEN** une connexion est empruntée, utilisée et restituée deux fois de suite sans attente
- **THEN** le compteur de validations réseau vaut 0

#### Scenario: Emprunt après inactivité
- **GIVEN** une connexion au repos depuis plus longtemps que le seuil, selon l'horloge injectée
- **WHEN** elle est empruntée
- **THEN** elle est validée une fois avant d'être remise à l'appelant

#### Scenario: Connexion invalide après inactivité
- **GIVEN** une connexion au repos au-delà du seuil dont la validation échoue
- **WHEN** elle est empruntée
- **THEN** elle est fermée et l'appelant reçoit une connexion neuve

### Requirement: Une connexion rendue dans un état incertain n'est pas réutilisée
Une connexion restituée fermée, hors de son mode d'auto-commit attendu ou après une exception de l'appelant SHALL être fermée et retirée du pool. Une rupture de connexion constatée à la première instruction d'un appel SHALL produire le comportement défini par la décision du design, sans jamais rejouer une écriture.

#### Scenario: Restitution après une exception
- **GIVEN** un appel qui se termine par une exception SQL
- **WHEN** sa connexion est restituée
- **THEN** elle est fermée et l'emprunt suivant obtient une autre connexion

#### Scenario: Transaction laissée ouverte
- **GIVEN** une connexion restituée avec l'auto-commit désactivé
- **WHEN** le pool la reçoit
- **THEN** il la ferme et ne la remet pas au repos

#### Scenario: Connexion rompue sous le seuil
- **GIVEN** une connexion au repos depuis moins que le seuil, rompue côté serveur
- **WHEN** la première instruction d'un appel en lecture échoue par une erreur de connexion
- **THEN** la connexion est écartée et l'appel suit la décision retenue (nouvel essai unique ou erreur), avec une erreur publique sans URL ni chemin

#### Scenario: Aucune écriture rejouée
- **GIVEN** un appel qui a déjà exécuté une écriture puis subit une erreur de connexion
- **WHEN** l'erreur survient
- **THEN** l'appel échoue et aucune instruction n'est rejouée

### Requirement: Une recherche de symbole par identifiant utilise l'index de la vue
Un service de requête qui cherche un symbole par son identifiant SHALL utiliser l'index de la vue de requête active et SHALL NOT parcourir la liste des symboles du snapshot. Un identifiant inconnu SHALL produire la même réponse qu'auparavant.

#### Scenario: Rapport runtime d'un symbole
- **GIVEN** un store dont le chargement du snapshot brut échoue et dont la vue de requête est disponible
- **WHEN** le rapport runtime d'un symbole existant est demandé
- **THEN** le rapport est rendu

#### Scenario: Identifiant inconnu
- **GIVEN** une vue de requête active
- **WHEN** le rapport runtime d'un identifiant absent est demandé
- **THEN** la réponse est celle d'avant ce changement

### Requirement: Le poids d'une vue en cache compte ses index dérivés
Le poids d'une vue de requête et celui d'une entrée du cache de graphe de programme SHALL inclure les index dérivés qu'elles retiennent. Le poids estimé SHALL rester supérieur ou égal au tas retenu une fois ces index construits.

#### Scenario: Vue avec index d'arêtes construit
- **GIVEN** une vue de requête dont l'index des arêtes entrantes a été construit
- **WHEN** le test de poids compare l'estimation au tas retenu
- **THEN** l'estimation est supérieure ou égale à la mesure
