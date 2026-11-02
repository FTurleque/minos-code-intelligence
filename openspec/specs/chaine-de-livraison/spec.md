# chaine-de-livraison Specification

## Purpose
Définit ce que les livrables de MINOS doivent porter pour les composants tiers qu'ils redistribuent : le jar ombré conserve la notice de chaque dépendance et un texte de licence par dépendance, sans suppression ni écrasement, et les images Docker livrent ces mêmes fichiers, extraits du jar. Les outils non Maven de l'image sont hors de cette capacité.

## Requirements

### Requirement: Le jar ombré conserve la notice de chaque dépendance redistribuée
Le jar ombré SHALL contenir un `META-INF/NOTICE` qui agrège le contenu des fichiers de notice de toutes les dépendances qu'il embarque. Aucune notice SHALL NOT être écrasée par une autre. Le fichier SHALL NOT contenir d'en-tête qui attribue MINOS à un tiers.

#### Scenario: Notices de deux dépendances homonymes
- **GIVEN** le jar ombré produit par `./mvnw -B -ntp -pl minos-app -am verify`
- **WHEN** le test d'intégration lit `META-INF/NOTICE`
- **THEN** le fichier contient le texte de notice de chaque dépendance qui en fournit une, dont les deux lignes de Jackson

#### Scenario: En-tête d'attribution absent
- **GIVEN** le même jar
- **WHEN** le test recherche un en-tête « developed at The Apache Software Foundation » en tête de `META-INF/NOTICE`
- **THEN** il n'en trouve pas

#### Scenario: Dépendance ajoutée sans notice conservée
- **GIVEN** une dépendance d'exécution Apache-2.0 qui fournit un fichier de notice
- **WHEN** elle est ajoutée au jar et le test d'intégration est rejoué
- **THEN** le test échoue si son texte de notice est absent du jar

### Requirement: Les textes de licence des dépendances ne sont ni supprimés ni écrasés
L'ombrage SHALL NOT retirer les fichiers de licence des dépendances. Quand plusieurs dépendances fournissent un fichier de licence de même nom, le jar SHALL conserver chacun sous un chemin qui identifie la dépendance.

#### Scenario: Licences présentes
- **GIVEN** le jar ombré
- **WHEN** le test d'intégration énumère les fichiers de licence
- **THEN** il en trouve au moins un par dépendance qui en fournit un, chacun non vide

### Requirement: Les images Docker livrent les notices et licences du jar
Chaque image Docker qui embarque le jar SHALL contenir les notices et licences du jar dans un répertoire documenté, lisible sans exécuter MINOS. La construction SHALL échouer si l'extraction ne produit aucun fichier.

#### Scenario: Notices présentes dans l'image
- **GIVEN** l'image de release construite par la qualification
- **WHEN** le répertoire des licences est listé dans le conteneur
- **THEN** il contient un fichier de notices non vide, identique à celui du jar

#### Scenario: Extraction vide
- **GIVEN** une construction dont le jar ne contient pas de notice
- **WHEN** l'étape d'extraction s'exécute
- **THEN** la construction échoue

### Requirement: Le jar distribué est reproductible à partir du commit
Deux constructions du jar ombré à partir du même commit, sur la même plateforme et dans des répertoires de travail propres, SHALL produire des fichiers de même SHA-256. L'horodatage des archives SHALL dériver du commit et non de l'heure de construction. Les scripts qui construisent un livrable SHALL transmettre cette date.

#### Scenario: Double construction
- **GIVEN** deux copies propres du dépôt au même commit, sur la même plateforme
- **WHEN** le jar ombré est construit dans chacune puis comparé par le contrôle de reproductibilité
- **THEN** les deux SHA-256 sont égaux

#### Scenario: Écart détecté
- **GIVEN** deux archives dont une entrée diffère
- **WHEN** le contrôle de reproductibilité les compare
- **THEN** il échoue et nomme les entrées qui diffèrent

#### Scenario: Horodatage des entrées
- **GIVEN** le jar ombré construit par le script de distribution
- **WHEN** les dates de ses entrées sont lues
- **THEN** elles sont égales à la date du commit, pas à celle de la construction

#### Scenario: Construction sans dépôt Git
- **GIVEN** une archive source sans métadonnées Git
- **WHEN** le jar est construit
- **THEN** la construction réussit avec l'horodatage par défaut fixe du POM

### Requirement: Tout plugin de build a une version déclarée
Le POM racine SHALL déclarer une version pour chaque plugin Maven utilisé par le reactor, plugins du cycle par défaut compris. Maven Enforcer SHALL échouer quand un plugin est résolu sans version déclarée.

#### Scenario: Plugin sans version
- **GIVEN** une copie du dépôt où la version d'un plugin du cycle par défaut est retirée de la gestion des plugins
- **WHEN** `./mvnw -B -ntp -N validate` est exécuté sur cette copie
- **THEN** Maven Enforcer échoue en nommant le plugin

#### Scenario: Dépôt réel
- **GIVEN** le dépôt
- **WHEN** `./mvnw -B -ntp validate` est exécuté sur tout le reactor
- **THEN** la règle réussit pour les quatorze modules

### Requirement: Chaque artefact publié d'une release a une provenance attestée
Le workflow de release SHALL émettre une attestation de provenance pour chaque artefact qu'il publie. Les permissions nécessaires SHALL être limitées au job de publication. La documentation SHALL donner la commande qui vérifie un artefact téléchargé.

#### Scenario: Workflow de release sans attestation
- **GIVEN** une copie du dépôt où le job de publication ne contient pas l'étape d'attestation
- **WHEN** le gate des workflows est exécuté sur cette copie
- **THEN** il échoue en nommant le job

#### Scenario: Permissions hors du job de publication
- **GIVEN** une copie du dépôt où `id-token: write` est déclaré au niveau du workflow
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue

#### Scenario: Vérification d'un artefact publié
- **GIVEN** un installateur téléchargé depuis une release produite par le workflow
- **WHEN** `gh attestation verify` est exécuté sur ce fichier avec le dépôt
- **THEN** la vérification réussit et nomme le workflow de release et le commit

### Requirement: Une signature exigée ne peut pas être sautée et n'est jamais présumée
Quand la signature de la release est exigée, le workflow SHALL échouer si un exécutable publié n'a pas une signature Authenticode valide et horodatée, ou si ce contrôle ne peut pas être fait. Quand elle ne l'est pas, le manifeste de release SHALL indiquer que les artefacts ne sont pas signés.

#### Scenario: Exigence active, exécutable non signé
- **GIVEN** la signature exigée et un installateur sans signature
- **WHEN** le contrôle de signature s'exécute
- **THEN** il échoue en nommant le fichier

#### Scenario: Exigence active, signature sans horodatage
- **GIVEN** la signature exigée et un exécutable signé sans horodatage
- **WHEN** le contrôle de signature s'exécute
- **THEN** il échoue

#### Scenario: Exigence active, mode de signature absent
- **GIVEN** la signature exigée et aucun mode de signature configuré
- **WHEN** le workflow de release démarre la construction
- **THEN** il échoue avant de publier quoi que ce soit

#### Scenario: Exigence absente
- **GIVEN** la signature non exigée
- **WHEN** la release est construite
- **THEN** le manifeste de release indique que les artefacts ne sont pas signés

### Requirement: Les sommes publiées portent sur les artefacts finals
Les sommes de contrôle et le manifeste de release SHALL être calculés après toute signature. La qualification SHALL recalculer la somme de chaque fichier publié et SHALL échouer si elle diffère de la somme publiée.

#### Scenario: Somme calculée avant signature
- **GIVEN** un artefact modifié après le calcul de sa somme
- **WHEN** la qualification compare les sommes
- **THEN** elle échoue en nommant l'artefact

#### Scenario: Artefacts finals
- **GIVEN** les artefacts d'un candidat de release
- **WHEN** la qualification recalcule leurs sommes
- **THEN** chacune égale la somme publiée

### Requirement: Une release publiée par le chemin nominal contient le plugin IntelliJ
Le workflow qui crée une release SHALL appeler explicitement la publication du plugin IntelliJ pour le même tag et le même commit, et SHALL échouer si l'archive du plugin ou sa somme de contrôle est absente de la release. La publication du plugin SHALL NOT dépendre d'un événement de release émis par le jeton du workflow.

#### Scenario: Workflow de release sans appel du plugin
- **GIVEN** une copie du dépôt où le workflow qui crée la release n'appelle pas le workflow de publication du plugin
- **WHEN** `python scripts/quality/check-ci-wiring.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant le workflow

#### Scenario: Publication du plugin déclenchée par le seul événement de release
- **GIVEN** une copie du dépôt où le workflow du plugin n'a que `release: published` pour déclencheur
- **WHEN** le gate est exécuté sur cette copie
- **THEN** il échoue

#### Scenario: Release nominale
- **GIVEN** une release créée par le workflow de release
- **WHEN** le workflow se termine
- **THEN** la release contient l'archive du plugin et sa somme, et le workflow est vert

#### Scenario: Plugin absent après publication
- **GIVEN** une release dont l'archive du plugin n'a pas été attachée
- **WHEN** le contrôle final du workflow de release s'exécute
- **THEN** le workflow échoue en nommant l'asset manquant

### Requirement: La publication du plugin se rejoue pour une release existante
Le workflow de publication du plugin SHALL pouvoir être lancé à la demande pour un tag existant. Il SHALL vérifier que le tag désigne le commit cible de la release et SHALL refuser un tag déplacé. Il SHALL NOT remplacer un asset déjà présent.

#### Scenario: Rejeu sur une release sans plugin
- **GIVEN** une release existante sans archive du plugin
- **WHEN** le workflow du plugin est lancé à la demande avec son tag
- **THEN** l'archive et sa somme sont attachées à cette release

#### Scenario: Tag déplacé
- **GIVEN** un tag qui ne désigne plus le commit cible de la release
- **WHEN** le workflow du plugin est lancé avec ce tag
- **THEN** il échoue avant toute construction

#### Scenario: Asset déjà présent
- **GIVEN** une release qui contient déjà l'archive du plugin
- **WHEN** le workflow du plugin est relancé pour le même tag
- **THEN** il échoue sans remplacer l'asset
