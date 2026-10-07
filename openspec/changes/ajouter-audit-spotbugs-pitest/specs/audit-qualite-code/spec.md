# Spec Delta

## Purpose

Définit l'audit de qualité du code de MINOS à la demande : l'analyse statique du bytecode (SpotBugs) et la mesure de la capacité des tests à détecter une régression (tests de mutation PIT). Cet audit est borné, reproductible et sans effet sur le build habituel.

## ADDED Requirements

### Requirement: Le build habituel n'exécute ni SpotBugs ni PIT
`clean verify` et les workflows de PR SHALL NOT exécuter SpotBugs ni PIT. Les deux plugins SHALL n'être ajoutés au build que par les profils Maven `audit-spotbugs` et `audit-mutation`, qu'aucune condition d'activation ne déclenche.

#### Scenario: Aucun profil
- **WHEN** le POM effectif d'un module est calculé sans profil d'audit
- **THEN** ni `spotbugs-maven-plugin` ni `pitest-maven` n'est dans `build/plugins` (ils ne figurent que dans `pluginManagement`)

#### Scenario: Un seul profil
- **WHEN** le POM effectif est calculé avec `audit-spotbugs`, puis avec `audit-mutation`
- **THEN** seul le plugin du profil demandé est dans `build/plugins`

### Requirement: SpotBugs produit des rapports exploitables et un contrôle bloquant explicite
Avec `audit-spotbugs`, SpotBugs SHALL analyser le bytecode de production de chaque module Java du reactor et écrire un rapport XML, un rapport SARIF et un rapport HTML. Le contrôle `spotbugs:check` SHALL faire échouer le build lorsqu'il reste au moins une alerte de gravité égale ou supérieure à `failThreshold`, et SHALL NOT être lié à une phase du cycle de vie.

#### Scenario: Rapport
- **WHEN** `spotbugs:spotbugs` est lancé avec `-Paudit-spotbugs` sur un module
- **THEN** `target/spotbugsXml.xml`, `target/spotbugsSarif.json` et `target/reports/spotbugs.html` existent pour ce module (pour `minos-app`, dans le `target/` de la racine)

#### Scenario: Le contrôle échoue sur une alerte
- **WHEN** `spotbugs:check` est lancé sur un module qui contient une alerte de gravité `Medium` avec `failThreshold=Medium`
- **THEN** le build du module échoue et le message nomme le nombre d'alertes

#### Scenario: Le seuil se règle sans modifier de POM
- **WHEN** le même contrôle est relancé avec `-Dspotbugs.failThreshold=High` et qu'il ne reste aucune alerte `High`
- **THEN** le build réussit

### Requirement: Aucune alerte n'est masquée pour obtenir un résultat vert
Le filtre d'exclusion SpotBugs SHALL ne contenir que des exclusions ciblant une classe pour un motif, chacune justifiée par un commentaire daté. Le dépôt SHALL consigner les alertes découvertes au lieu de les exclure.

#### Scenario: Filtre initial
- **WHEN** l'intégration est livrée
- **THEN** `quality/spotbugs-exclude.xml` ne contient aucune règle `Match`

### Requirement: PIT ne mute qu'un périmètre déclaré et ajustable sans modifier de POM
Avec `audit-mutation`, PIT SHALL ne muter que les classes de `targetClasses` et SHALL n'exécuter que les tests de `targetTests`. Ces deux valeurs SHALL pouvoir être remplacées en ligne de commande. Le profil SHALL NOT lier PIT à une phase du cycle de vie.

#### Scenario: Périmètre initial
- **WHEN** PIT est lancé avec `-Paudit-mutation -pl minos-engine -am` sans autre option
- **THEN** il mute `HostedAuditChain`, `HostedAuthorizationService` et `HostedPermission` avec les tests de `com.minos.hosted.*`, et écrit `minos-engine/target/pit-reports/index.html` et `mutations.xml`

#### Scenario: Périmètre remplacé
- **WHEN** PIT est lancé avec `-DtargetClasses=<autre classe>`
- **THEN** seules les mutations de cette classe figurent dans `mutations.xml`

### Requirement: Une analyse PIT sans mutation couverte n'est pas un succès
Une exécution PIT SHALL NOT être tenue pour réussie sur la seule fin du processus. La vérification `audit-report-summary.py pit` SHALL échouer lorsque le rapport est absent, ne contient aucune mutation, ou ne contient aucune mutation couverte par un test.

#### Scenario: Rapport absent
- **WHEN** la vérification est lancée pour un module où PIT n'a pas tourné
- **THEN** elle échoue avec le code 1 et nomme le fichier manquant

#### Scenario: Analyse utile
- **WHEN** la vérification est lancée après l'exécution du périmètre initial
- **THEN** elle réussit et affiche le nombre de mutations par statut, le score de mutation et la force des tests

### Requirement: Les commandes documentées correspondent à la configuration
Chaque commande de `docs/quality/code-audit.md` SHALL être exécutable telle quelle depuis la racine du dépôt, et la documentation SHALL distinguer celles qui n'ont pas été exécutées dans l'environnement de l'intégration.

#### Scenario: Commande non exécutée
- **WHEN** une commande de la documentation n'a pas été lancée (bash sous Linux ou macOS, workflow GitHub Actions)
- **THEN** la documentation l'indique explicitement dans ses limites
