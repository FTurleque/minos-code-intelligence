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
