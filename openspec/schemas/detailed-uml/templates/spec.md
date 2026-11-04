# Spec Delta

<!-- Rédiger en français. Marqueurs techniques à conserver tels quels : ## Purpose,
     ## ADDED / MODIFIED / REMOVED / RENAMED Requirements, ### Requirement:, #### Scenario:,
     **GIVEN** / **WHEN** / **THEN** / **AND**, SHALL / MUST.
     Seuls les blocs d'exigences sont fusionnés à l'archivage : aucune autre section ici.
     Diagrammes métier : dossier durable docs/architecture/diagrams/capacites/<capacité>.md. -->

## Purpose
<!-- Capacité nouvelle uniquement : une ou deux phrases (50 caractères au moins) qui disent à
     quoi sert la capacité, ses acteurs et son périmètre. Supprimer cette section pour une
     capacité existante. -->

## ADDED Requirements

### Requirement: <!-- titre affirmatif, unique dans la capacité, stable : clé de traçabilité -->
<!-- Un seul comportement, 500 caractères au plus. Acteur ou déclencheur, condition,
     comportement attendu avec SHALL / MUST, interdiction avec SHALL NOT. Ni seuil ni règle
     inventés ; ni nom de classe interne. -->

#### Scenario: <!-- cas nominal -->
- **GIVEN** <!-- situation initiale et données discriminantes -->
- **WHEN** <!-- action ou événement unique -->
- **THEN** <!-- résultat observable : sortie, code, message, état -->
- **AND** <!-- effet persisté, ou son absence explicite -->

#### Scenario: <!-- refus fail-closed, entrée invalide ou cas limite qui couvre un risque réel -->
- **GIVEN** <!-- situation qui doit être refusée -->
- **WHEN** <!-- action -->
- **THEN** <!-- erreur attendue : code, message actionnable, aucune fuite de chemin ni de secret -->
- **AND** <!-- aucun état modifié -->

## MODIFIED Requirements

<!-- Copier le bloc ENTIER depuis openspec/specs/<capacité>/spec.md (titre identique), puis
     le modifier. Ajouter un scénario d'erreur à une exigence existante est un MODIFIED qui
     garde tout le texte d'origine. Supprimer cette section si elle est vide. -->

## REMOVED Requirements

<!-- ### Requirement: <titre exact>
     **Reason**: <pourquoi>
     **Migration**: <ce que l'utilisateur fait à la place>
     Supprimer cette section si elle est vide. -->

## RENAMED Requirements

<!-- - FROM: `### Requirement: <ancien titre>`
     - TO: `### Requirement: <nouveau titre>`
     Supprimer cette section si elle est vide. -->
