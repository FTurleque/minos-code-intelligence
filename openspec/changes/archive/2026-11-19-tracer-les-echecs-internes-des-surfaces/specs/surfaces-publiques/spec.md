## ADDED Requirements

### Requirement: L'erreur opaque d'un outil MCP porte la référence de sa trace
Quand un outil MCP échoue pour une cause interne, la réponse SHALL commencer par l'erreur générique d'exécution d'outil et SHALL se terminer par la référence de corrélation écrite dans la ligne de diagnostic. La réponse SHALL NOT contenir d'autre information sur l'échec.

#### Scenario: Référence commune à la réponse et au journal
- **GIVEN** un outil dont le service lève une exception imprévue
- **WHEN** l'outil est appelé (`./mvnw -B -ntp -pl minos-mcp -am test "-Dtest=MinosMcpInternalFailureTraceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** le texte de la réponse commence par `error: MINOS tool execution failed` et se termine par `(ref <référence>)`
- **AND** la même référence figure dans la ligne de diagnostic de stderr

#### Scenario: Aucune fuite par la référence
- **GIVEN** une exception dont le message contient un chemin et un jeton
- **WHEN** l'outil échoue
- **THEN** la réponse ne contient que l'erreur générique et une référence faite de chiffres hexadécimaux

### Requirement: Un échec d'exécution de la CLI est tracé quand le journal privé est actif
Journal de diagnostic privé actif, un échec d'exécution rendu par le cadre commun des commandes de la CLI ou par son lanceur, et qui n'est pas une erreur publique connue (référence de projet, registre illisible, refus de mémoire), SHALL journaliser sa pile et ajouter sur stderr une ligne qui donne la référence de l'entrée. Journal inactif, la sortie SHALL rester identique à l'octet.

#### Scenario: Journal actif
- **GIVEN** `MINOS_DIAGNOSTIC_LOG=enabled` et une commande du cadre commun dont l'application lève une exception imprévue
- **WHEN** la commande s'exécute (`./mvnw -B -ntp -pl minos-cli -am test "-Dtest=CliInternalFailureTraceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** le code de sortie est `1`, stderr garde sa ligne `error:` et porte une ligne de référence, et le journal contient l'entrée de cette référence

#### Scenario: Journal inactif
- **GIVEN** aucun réglage de diagnostic
- **WHEN** la même commande échoue
- **THEN** stderr est identique à la sortie d'avant ce changement
- **AND** les goldens de caractérisation sont inchangés

#### Scenario: Erreur publique connue non tracée
- **GIVEN** le journal actif
- **WHEN** une commande échoue parce que le projet désigné est inconnu (code `1`)
- **THEN** aucune entrée n'est ajoutée au journal et stderr ne porte pas de ligne de référence

#### Scenario: Erreur d'usage non tracée
- **GIVEN** le journal actif
- **WHEN** une commande est refusée pour une option invalide (code `2`)
- **THEN** aucune entrée n'est ajoutée au journal

### Requirement: Un échec interne de l'API Java est tracé sans changer l'exception publique
Quand un appel de l'API Java échoue par un échec d'exécution ou d'E/S, le système SHALL écrire la ligne de diagnostic par le journal système et, journal privé actif, y ajouter la pile. La `MinosApiException` rendue SHALL rester sans cause, de même code et de même message qu'avant.

#### Scenario: Échec d'exécution tracé
- **GIVEN** une façade dont le service lève une exception imprévue et un enregistreur capturé
- **WHEN** l'appel échoue (`./mvnw -B -ntp -pl minos-api -am test "-Dtest=MinosApiInternalFailureTraceTest" "-Dsurefire.failIfNoSpecifiedTests=false"`)
- **THEN** l'appelant reçoit une `MinosApiException` d'échec d'exécution sans cause
- **AND** l'enregistreur a reçu une ligne avec la classe, la classe de la cause racine, l'origine et la référence, sans message

#### Scenario: Requête invalide non tracée
- **GIVEN** un appel refusé comme requête invalide
- **WHEN** il échoue
- **THEN** aucune ligne de diagnostic d'échec interne n'est écrite
