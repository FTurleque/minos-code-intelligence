## ADDED Requirements

### Requirement: La façade de l'équipe ne prend aucune décision de sécurité
`TeamControlPlane` SHALL déléguer chaque opération à `HostedControlPlaneService` et propager ses exceptions telles quelles, sans contrôle, rattrapage ni journal propre. Ses entrées SHALL être validées par les règles du moteur, avec les mêmes messages. Ses résultats porteurs d'un jeton SHALL masquer ce jeton dans `toString()`.

#### Scenario: Un jeton invalide reste un refus
- **GIVEN** une façade sur un service réel et un jeton forgé
- **WHEN** `TeamControlPlaneTest` appelle `tenant` avec ce jeton
- **THEN** l'exception levée est celle du service, de même type et de même message

#### Scenario: Une politique hors bornes est refusée par le moteur
- **GIVEN** une politique de rétention à 99 événements d'audit
- **WHEN** `TeamRetentionPolicy` est construite dans `TeamControlPlaneTest`
- **THEN** elle lève l'`IllegalArgumentException` de `HostedRetentionPolicy`, au même message

#### Scenario: Jetons masqués et rôles exhaustifs
- **GIVEN** un amorçage et une rotation de clé réussis
- **WHEN** `TeamControlPlaneTest` lit `toString()` des résultats et compare `TeamRole` à `HostedRole`
- **THEN** aucun jeton n'apparaît, et chaque valeur de l'une a son pendant dans l'autre
