# Spec Delta

## ADDED Requirements

### Requirement: Le périmètre analysé par SpotBugs est prouvé par un décompte
Une conclusion de couverture SpotBugs SHALL s'appuyer sur un rapport qui énumère les classes analysées. `audit-report-summary.py spotbugs` SHALL comparer, module par module, le nombre de classes analysées au nombre de fichiers `.class` du répertoire de sortie de production, et SHALL signaler un rapport qui ne permet pas ce décompte.

#### Scenario: Relecture en ligne de commande complète
- **WHEN** la synthèse lit les rapports de la ligne de commande de SpotBugs pour les 14 modules après une compilation du réacteur
- **THEN** chaque module affiche autant de classes analysées que de classes compilées et la commande se termine avec le code 0

#### Scenario: Rapport sans statistiques de classes
- **WHEN** la synthèse lit un rapport du plugin Maven qui ne contient aucun `ClassStats`
- **THEN** le module est signalé `UNPROVEN`, et avec `--strict` la commande se termine avec le code 1

#### Scenario: Module partiellement analysé ou sans rapport
- **WHEN** un module compilé n'a pas de rapport, ou son rapport compte moins de classes analysées que de classes compilées, ou signale des classes manquantes
- **THEN** la commande se termine avec le code 1 et nomme le module

### Requirement: Les tests d'architecture importent tout le réacteur avant d'appliquer une règle
`ModuleArchitectureTest` SHALL importer le bytecode de production de chacun des 14 modules du réacteur et SHALL échouer si un répertoire de sortie est absent, vide, ou si le nombre de classes importées diffère du nombre de fichiers `.class` sur disque. Les règles d'architecture SHALL NOT s'évaluer sur un import partiel.

#### Scenario: Réacteur compilé
- **WHEN** les tests d'architecture s'exécutent après une compilation complète
- **THEN** les 14 modules sont importés en entier et les règles établies sont évaluées

#### Scenario: Module non compilé
- **WHEN** le répertoire `target/classes` d'un module est absent
- **THEN** le test échoue en nommant le module et le chemin attendu, sans évaluer de règle

### Requirement: Les règles d'architecture obligatoires reprennent uniquement des décisions établies
Les règles d'architecture exécutées par défaut SHALL correspondre chacune à une décision acceptée (ADR 0022, 0042 ou 0044) et SHALL la citer. Une règle sans décision établie SHALL rester opt-in.

#### Scenario: Règles établies évaluées par défaut
- **WHEN** `clean verify` exécute les tests de `minos-app`
- **THEN** sont vérifiés : aucun package compilé par deux modules (ADR 0044) ; aucun adaptateur vers l'application, la racine de composition, une surface ou l'assemblage (ADR 0042) ; l'application sans adaptateur, racine ni surface (ADR 0042) ; aucune surface vers la racine de composition, un adaptateur ou l'assemblage à la compilation (ADR 0042) ; le domaine sans dépendance interne et le moteur limité au domaine (ADR 0022)

#### Scenario: Violation d'une frontière établie
- **WHEN** une classe d'un adaptateur référence une classe de `minos-application`
- **THEN** `clean verify` échoue sur la règle correspondante et nomme la classe, la dépendance et l'ADR

#### Scenario: Mesure stricte non décidée
- **WHEN** `clean verify` s'exécute sans `-Dminos.audit.archunit.strict=true`
- **THEN** la mesure des usages de modules non déclarés est sautée ; avec la propriété, elle liste tous les usages de tous les modules avant d'échouer

### Requirement: Dependency-Check distingue une erreur d'un résultat
Les profils `audit-dependency-check` et `audit-dependency-check-tests` SHALL faire échouer le build sur toute erreur de mise à jour ou d'analyse (`failOnError=true`), SHALL produire un rapport agrégé sur tous les modules du réacteur, et SHALL séparer les dépendances livrées de celles des tests. La clé NVD SHALL être transmise par le nom d'une variable d'environnement, jamais par sa valeur.

#### Scenario: Clé NVD refusée
- **WHEN** la mise à jour s'exécute avec une clé que le NVD refuse
- **THEN** le build échoue avec le message du NVD, aucun rapport « sans vulnérabilité » n'est produit, et la valeur de la clé n'apparaît dans aucun rapport

#### Scenario: Analyse des dépendances livrées
- **WHEN** `aggregate` s'exécute avec `audit-dependency-check` sur une base à jour
- **THEN** `target/dependency-check/dependency-check-report.json` liste les dépendances de portées compile, runtime, provided et system de tous les modules, sans dépendance de test, et la synthèse affiche la date de la base

#### Scenario: Faux rapprochement d'un module MINOS
- **WHEN** un module `com.minos:*` est rapproché d'un CPE tiers
- **THEN** l'alerte est supprimée seulement par une règle qui cite ce purl et ce CPE, justifiée, et une règle devenue inutile fait échouer l'analyse

### Requirement: Les composants hors du réacteur Maven restent visibles
Chaque composant que les profils Maven ne couvrent pas (plugin IntelliJ, plugins Maven, images Docker, outils SCIP embarqués, historique Git) SHALL figurer dans la matrice de couverture avec son statut (exécuté par un autre moyen, bloqué, non applicable) et la raison.

#### Scenario: Plugin IntelliJ sans Gradle
- **WHEN** Gradle n'est pas disponible
- **THEN** le plugin est analysé par le harnais documenté (compilation contre l'IDE cible, tests, SpotBugs, dépendances reconstituées) ou marqué bloqué, jamais omis

### Requirement: Gitleaks couvre l'historique complet et ne publie aucun secret
L'analyse de secrets SHALL couvrir l'arbre de travail (fichiers non suivis compris), l'historique local et l'historique distant complet (branches, tags, `refs/pull/*`) par un clone miroir hors dépôt lorsque le clone de travail est superficiel. Les rapports SHALL être rédigés (`--redact`) et écrits hors du périmètre analysé.

#### Scenario: Clone de travail superficiel
- **WHEN** `git rev-parse --is-shallow-repository` répond `true`
- **THEN** l'historique est analysé sur un clone miroir, et les références indisponibles sont déclarées

#### Scenario: Qualification d'une alerte
- **WHEN** une alerte est qualifiée dans un document
- **THEN** seule sa forme (longueur, alphabet) et la ligne masquée sont citées, jamais la valeur
