# Proposal

## Why

L'audit du 10 octobre 2026 (sprint 1) relève trois défauts dans les gardes qui protègent l'architecture de MINOS :

- **AUD-ARC-10** (E02, ouvert depuis l'audit outillé) : les 13 auto-tests de `scripts/architecture/check-module-boundaries.py` ne couvrent que les règles A3 et A7. Aucun n'appelle les règles A2 (`check_hexagonal_boundaries`, `check_source_boundaries`, `check_no_hidden_internal_dependencies`, `check_dependency_policy`), alors que le sprint 10 (SH-02, SH-11) va les réécrire. La reconnaissance d'une dépendance interne repose sur le littéral `"com.minos"` (`:149`, `:182`).
- **AUD-ARC-06** (E10) : quatre cycles de packages (15 packages) existent et aucune garde ne contrôle les cycles. Ils empêchent de séparer les packages de `minos-application` (prérequis de SH-09).
- **AUD-ARC-03** : `docs/architecture/arc42/05-vue-blocs.md` donne des dépendances fausses pour 7 modules sur 14, et `docs/architecture/SYNTHESE.md` maintient que PostgreSQL dépend de `minos-application`, alors que `check-current-docs.py` passe : la correction de G16/G17, déclarée close, était incomplète.

Le point commun : la documentation et les règles décrivent une architecture qu'aucune commande ne confronte aux POM et aux sources.

## What Changes

- **Auto-tests des règles A2** (`test_check_module_boundaries.py`) : au moins un cas refusé et un cas accepté par règle, des mutations témoins sur une copie des vrais POM, et un test qui échoue si une fonction `check_*` du script n'est référencée par aucun test. Les fonctions qui lisent le disque reçoivent un paramètre `root` (comme `check_package_ownership`).
- **E02** : le groupId interne est dérivé du POM racine ; un `groupId` non résolu (`${…}`) ou voisin d'un module MINOS est refusé ; les règles de mise en page (`sourceDirectory`, `testSourceDirectory`, `includes`/`excludes` du compilateur) valent aussi dans les `<profile>`. La règle blanche « tout `<build>` dans un `<profile>` » proposée par l'audit est **écartée** : six profils légitimes en déclarent un.
- **Règle A8 « cycles de packages », en cliquet** : le garde calcule les composantes fortement connexes du graphe package → package (sources de production des 14 modules **et** de `minos-intellij`), refuse tout cycle absent de la table `KNOWN_PACKAGE_CYCLES` (les quatre cycles actuels, 15 packages) et refuse une entrée qui ne décrit plus exactement une composante (le cliquet ne se desserre pas, il ne se resserre que par une édition visible).
- **Règle A9 « documentation courante »** : pour chaque module, la ligne « Dépendances » de sa section dans `arc42/05-vue-blocs.md` doit désigner exactement les dépendances internes de son POM ; une section ou une ligne manquante échoue. Corrections de contenu : les 7 listes d'`arc42/05`, l'emplacement d'`InMemoryCodeKnowledgeStore`, le passage de `SYNTHESE.md` au statut historique, les arêtes de dette déclarée dans le diagramme conteneur.
- **Hors de ce lot, nommé** : le changement `casser-les-cycles-de-packages` (déplacements de classes), dont l'analyse est consignée dans `design.md`.

## Capabilities

### New Capabilities

- `frontieres-de-modules` : ce que les gardes d'architecture de MINOS (POM, sources, packages, documentation) garantissent et comment elles se testent elles-mêmes. Capacité nouvelle car aucune capacité existante ne couvre ce sujet : `program-graph-et-architecture` décrit la **fonctionnalité** « architecture » offerte aux projets analysés (limitations déclarées des requêtes), non l'architecture interne de MINOS ; `audit-qualite-code` (et le changement `etendre-audit-outille-a-tout-le-perimetre`) porte les règles ArchUnit du bytecode et l'audit à la demande.

### Modified Capabilities

(aucune)

## Hors périmètre

- **Aucun déplacement de classe, aucune modification de code de production** : `ProjectResolver`, `DeterministicJson`, `com.minos.output`, `discovery`, `incremental`, `orchestration`, le plugin. Les cycles restent, listés et gelés.
- AUD-ARC-01, AUD-ARC-05, AUD-ARC-08 (sprint 10), bénéficiaires d'AUD-ARC-10 ; SH-09 (découpe par capacité), bénéficiaire d'AUD-ARC-06.
- La règle ArchUnit `beFreeOfCycles()` dans `ModuleArchitectureTest` : non retenue (voir `design.md`, D3).
- Les arêtes surfaces → moteur : dette déclarée (ADR 0058), non résorbée ici.
- Contrôler les affirmations en prose de `SYNTHESE.md` : non gardé (limite assumée).

## Impact

- **Modules du reactor touchés** : aucun. Scripts `scripts/architecture/check-module-boundaries.py` et son auto-test ; documentation `docs/architecture/`.
- **Surfaces publiques impactées** : aucune (CLI, API Java, MCP, IntelliJ, NEXUS inchangés).
- **ADR** : aucun nouvel ADR, aucun amendement obligatoire. L'ADR 0057 § 7 prévoit que les gardes soient « complétées au niveau des packages/classes si nécessaire » et fait d'ArchUnit « une option de réalisation » : A8 en est une application. Une ligne de suivi dans la section « Mise en œuvre » de l'ADR 0057 est suggérée, non requise.
- **Gates à rejouer** : `check-module-boundaries.py` et son auto-test, `check-current-docs.py`, `check-milestone-artifact-references.py` (aucun nouveau script), `check-workflow-pins.py` ; `docs/architecture/diagrams/module-dependencies.md` doit rester identique (`--write-doc` ne doit produire aucun diff).
- **Plateformes** : scripts Python indépendants de l'OS ; séparateurs de chemins normalisés pour que le résultat soit identique sous Windows et Linux.
