## ADDED Requirements

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
