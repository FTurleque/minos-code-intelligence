# Proposal

## Why

Constat **AUD-ARC-04** de l'audit du 2026-11-15 (sprint 8) : la politique de frontières est écrite deux fois. `scripts/architecture/check-module-boundaries.py` (1 912 lignes, 82 commits) porte les tables ; `ModuleArchitectureTest` en recopie quatre à la main (liste des modules, dépendances autorisées, adaptateurs, surfaces) et tient la vue bytecode de la dette A11 à côté de sa vue source. Chaque mouvement de cliquet se fait deux fois. La règle A17 et une règle Archgate ne comparent que la table des dépendances autorisées : les trois autres copies peuvent diverger sans qu'aucun contrôle ne le voie.

Le changement vient après le sprint 7 (AUD-ARC-01, AUD-ARC-02), qui modifie ces tables, et après `reclasser-minos-nexus-en-module-de-contrat` (AUD-ARC-08), qui en change les familles.

## What Changes

- Un fichier de données versionné, `scripts/architecture/module-boundaries.json`, devient la source unique de la politique partagée : modules du reactor dans l'ordre, famille de chaque module, dépendances internes autorisées, couples admis en portée test seulement, et les deux vues de la dette A11 (source et bytecode).
- Le garde Python charge ce fichier ; les noms `MODULES`, `ADAPTER_MODULES`, `SURFACE_MODULES`, `ALLOWED_DEPENDENCIES` et `ENGINE_TYPES_NAMED_BY_SURFACE` restent des attributs du script, chargés et non plus littéraux.
- `ModuleArchitectureTest` charge le même fichier et ne déclare plus aucune table littérale de politique.
- Les deux lecteurs échouent si le fichier est absent, illisible, s'il porte une clé inconnue ou en double, ou s'il nomme un module inconnu.
- La règle A17 change d'objet : elle vérifie que le test ArchUnit lit le fichier et ne redéclare aucune table. Deux jeux de règles Archgate lisent aujourd'hui les littéraux du script et sont adaptés dans la tâche qui retire ces littéraux : `allowed-dependency-tables-identical` (ARCH-0058) et les règles d'ARCH-0068, qui lisent le tuple `MODULES`.
- Les trois tables que le sprint 7 ajoute au garde en annonçant leur lecture depuis la source unique y entrent aussi : classes d'adaptateur embarquées, utilisateurs d'API d'infrastructure, classes de `minos-app` (noms proposés par le sprint 7 : `EMBEDDED_ADAPTER_CLASSES`, `INFRASTRUCTURE_API_USERS`, `ASSEMBLY_CLASSES`).
- Aucune règle n'est retirée ni assouplie ; les autres tables propres au garde Python (A10, accesseurs A11, A12, A13, A14, A15, A18, cycles) restent dans le script.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : trois exigences ajoutées (source unique, échec fail-closed des deux lecteurs, absence de table recopiée dans le test) ; une exigence modifiée, « Les types du moteur utilisés par le bytecode d'une surface le sont par liste fermée », qui parle d'une table tenue par le test (`openspec/specs/frontieres-de-modules/spec.md:622`, `:630`) : la liste est lue dans la source unique, par surface et par module de contrat. L'exigence « Les adaptateurs de stockage forment un seul module » (`:817`, `:822`) n'est pas modifiée ici : `ModuleArchitectureTest.ALLOWED` reste un champ du test, chargé ; son scénario est réécrit par `aligner-la-prose-d-architecture-sur-le-code`, seul changement à la modifier.

## Hors périmètre

- Le découpage du script de 1 912 lignes en plusieurs fichiers.
- Le déplacement des tables propres au garde Python vers le fichier de données (design D2).
- Toute modification du contenu de la politique : les valeurs du fichier sont celles des tables au moment de la bascule.
- La portée des dépendances dans le document généré et l'extension d'A9 (AUD-ARC-07).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Le test recopie la liste des modules, les dépendances autorisées, les adaptateurs et les surfaces | [Existant vérifié] | `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:48-51`, `:58-79`, `:81-84` |
| A17 ne compare que la table `ALLOWED` | [Existant vérifié] | `scripts/architecture/check-module-boundaries.py:1652-1682` |
| La règle Archgate d'ARCH-0058 lit les deux tables littérales | [Existant vérifié] | `docs/architecture/adr/ARCH-0058-declarer-les-usages-de-modules.rules.ts:7-8`, `:21-48` |
| Les règles Archgate d'ARCH-0068 lisent le tuple littéral `MODULES` et signalent une violation s'il est introuvable | [Existant vérifié] | `docs/architecture/adr/ARCH-0068-modules-dependances-et-propriete-des-packages.rules.ts:12`, `:60-70`, `:80-90` |
| Le sprint 7 annonce que ses tables seront lues depuis la source unique | [Existant vérifié] | `openspec/changes/cabler-les-adaptateurs-embarques-par-la-racine-de-composition/design.md:181`, `openspec/changes/centraliser-la-politique-de-lancement-de-l-explorateur/design.md:143` |
| Un autre gate affirme le nom `ALLOWED_DEPENDENCIES` dans le script | [Existant vérifié] | `scripts/remediation/check-p0-p2.py:252` |
| Jackson est déclaré par `minos-storage`, dont `minos-app` dépend | [Existant vérifié] | `minos-storage/pom.xml:42-43` ; présence sur le classpath de test de `minos-app` à confirmer à l'implémentation |
| Le répertoire de travail du test est la racine du reactor | [Existant vérifié] | `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:38-40` |
| Format JSON et emplacement `scripts/architecture/` | [Proposition] | design D1 |

## Impact

- **Modules du reactor** : `minos-app` (test seulement). Aucun code de production.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : note datée de « Mise en œuvre » à ajouter à ARCH-0058 (une politique, un fichier ; sa consigne de garder identiques les deux tables, `:37`, est tenue par l'absence de copie) ; ses règles Archgate et celles d'ARCH-0068 sont adaptées. Aucun ADR nouveau.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py`, `scripts/architecture/test_check_module_boundaries.py`, `scripts/remediation/check-p0-p2.py`, `scripts/quality/check-ci-wiring.py`, `archgate check`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux — le chemin du fichier se construit par segments, sans séparateur écrit en dur ; lecture en UTF-8.
- **Documentation durable et diagrammes** : `docs/developer/quality-gates.md` (A11, A17), `docs/developer/archgate.md:64`, dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md`.

## Questions ouvertes

Aucune bloquante. D2 (périmètre du fichier) porte une recommandation dans le design.
