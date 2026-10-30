## ADDED Requirements

### Requirement: Une lecture de statut ou une réconciliation ne charge pas le snapshot actif
La réconciliation de l'état d'index avec le snapshot actif SHALL lire l'identité du snapshot actif par la lecture légère du port, et SHALL NOT charger ni décoder le snapshot, en lecture de statut comme en réparation. Les refus existants SHALL être conservés : un état qui référence un snapshot absent du store, ou une lecture du store en échec, échoue en `IOException`.

#### Scenario: Statut cohérent
- **GIVEN** un store à compteurs dont le snapshot actif est celui de l'état persistant
- **WHEN** le statut du projet est lu
- **THEN** le compteur de chargements complets vaut 0 et l'identifiant rendu est celui du snapshot actif

#### Scenario: Réparation d'un état en retard
- **GIVEN** un état persistant qui référence un snapshot antérieur au snapshot actif
- **WHEN** la réconciliation répare l'état
- **THEN** l'état réparé référence le snapshot actif et le compteur de chargements complets vaut 0

#### Scenario: État qui référence un snapshot absent
- **GIVEN** un état persistant qui référence un snapshot et un store sans snapshot actif
- **WHEN** le statut du projet est lu
- **THEN** la lecture échoue en `IOException` sans rien écrire

#### Scenario: Corps de snapshot endommagé
- **GIVEN** un store fichier dont le pointeur actif est lisible et dont le corps du snapshot est altéré
- **WHEN** le statut du projet est lu, puis le snapshot est chargé
- **THEN** le statut rend l'identifiant pointé et le chargement échoue fermé sur la somme de contrôle

### Requirement: L'ancien contrat de réconciliation reste disponible et cohérent
Le record `Reconciliation` et les méthodes qui le rendent SHALL rester disponibles, dépréciés, avec la même sémantique : le snapshot rendu SHALL avoir l'identifiant de l'état réconcilié. Ils SHALL charger le snapshot au plus une fois par appel sans promotion concurrente.

#### Scenario: Lecture par l'ancien contrat
- **GIVEN** un store à compteurs avec un snapshot actif
- **WHEN** le statut est lu par la méthode dépréciée
- **THEN** le snapshot rendu porte l'identifiant actif et le compteur de chargements complets vaut au plus 1

### Requirement: Les contrôles d'identité du snapshot actif ne le chargent pas
La liaison d'un projet hébergé à un snapshot et la reprise d'un run dont le snapshot a été promu SHALL comparer l'identifiant attendu à la lecture légère de l'identité active, sans charger le snapshot. Une liaison SHALL être refusée en `IOException` si aucun snapshot n'est actif ou si l'identifiant diffère.

#### Scenario: Liaison au snapshot actif
- **GIVEN** un store à compteurs dont le snapshot actif est `s1`
- **WHEN** un projet hébergé est lié à `s1`
- **THEN** la liaison est acceptée et le compteur de chargements complets vaut 0

#### Scenario: Liaison refusée
- **GIVEN** un store dont le snapshot actif est `s1`, puis un store sans snapshot actif
- **WHEN** un projet hébergé est lié à `s2`
- **THEN** la liaison échoue en `IOException` dans les deux cas

#### Scenario: Reprise d'un run promu
- **GIVEN** un run non réussi dont le snapshot préparé est le snapshot actif
- **WHEN** la reprise est évaluée
- **THEN** le run est reconnu comme promu sans chargement du snapshot, et un run dont le snapshot préparé n'est pas actif ne l'est pas
