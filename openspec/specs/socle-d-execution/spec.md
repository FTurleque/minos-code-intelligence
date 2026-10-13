# socle-d-execution Specification

## Purpose
Définit ce que MINOS garantit sur les runtimes qu'il livre ou sur lesquels il tourne : un runtime Node maintenu et de même majeur sur chaque plateforme, et un inventaire de provenance de l'image Docker qui reflète les binaires réellement installés plutôt que des versions recopiées. La baseline Java du produit et son gate relèvent du changement de migration vers Java 25, encore actif.

## Requirements

### Requirement: Le runtime Node livré est maintenu et de même majeur sur chaque plateforme
Le catalogue `embedded-tools.json` SHALL déclarer, pour chaque plateforme livrée, un runtime Node d'une ligne LTS maintenue et de même version majeure. Les `ARG` du Dockerfile de release SHALL correspondre au catalogue. Une limitation de compatibilité SHALL NOT figurer dans l'inventaire sans avoir été mesurée.

#### Scenario: Majeurs différents entre plateformes
- **GIVEN** une copie du catalogue où `linux-x64` déclare Node 20 et `windows-x64` Node 24
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant les deux versions

#### Scenario: Dockerfile en retard sur le catalogue
- **GIVEN** une copie du dépôt où `NODE_VERSION` du Dockerfile diffère du catalogue
- **WHEN** le gate est exécuté sur cette copie
- **THEN** le gate échoue

#### Scenario: Providers Node qualifiés dans l'image
- **GIVEN** l'image de release construite avec le Node du catalogue
- **WHEN** `scripts/ci/qualify-docker-release.sh` indexe les fixtures TypeScript et Python
- **THEN** scip-typescript et scip-python produisent leur index et la qualification réussit

### Requirement: L'inventaire de provenance de l'image reflète les binaires installés
Les versions de .NET, Go, Rust et Node écrites dans `provider-inventory.json` SHALL être lues sur les binaires de l'image pendant sa construction. Le Dockerfile SHALL NOT contenir de version littérale pour ces champs. La construction SHALL échouer si une version ne peut pas être lue.

#### Scenario: Littéral de version dans l'inventaire
- **GIVEN** une copie du Dockerfile dont le bloc d'inventaire contient `"toolchain":"1.97.1"`
- **WHEN** `python scripts/quality/check-tools-manifest.py` est exécuté sur cette copie
- **THEN** le gate échoue en nommant la ligne

#### Scenario: Inventaire comparé aux binaires
- **GIVEN** l'image de release construite
- **WHEN** `scripts/ci/qualify-docker-release.sh` compare chaque version de l'inventaire à la sortie du binaire correspondant dans le conteneur
- **THEN** les quatre valeurs sont égales

#### Scenario: Version illisible à la construction
- **GIVEN** une construction où la commande de version d'une toolchain ne rend rien
- **WHEN** l'étape qui écrit l'inventaire s'exécute
- **THEN** la construction échoue et aucun inventaire n'est écrit
