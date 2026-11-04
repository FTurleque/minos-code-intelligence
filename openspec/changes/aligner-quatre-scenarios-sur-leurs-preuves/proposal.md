# Proposal

## Why

Le relevé des dossiers durables de capacité du 2026-11-03 (`docs/architecture/diagrams/capacites/`) a trouvé
quatre scénarios de spec de référence qui ne décrivent pas ce que leurs preuves vérifient : un décompte faux, une
commande qui ne contrôle pas ce que le scénario affirme, une extension implémentée et testée mais non spécifiée,
une comparaison impossible à exécuter. Un scénario qui ne peut pas échouer pour la raison qu'il énonce ne protège
rien ; les corriger maintenant évite qu'un futur changement s'appuie sur eux.

## What Changes

- `journalisation` : le scénario « Diagnostic de bac à sable listé » dit « six sites de diagnostic ou de
  refus » de bac à sable ; la liste nominative compte six entrées dont trois seulement de bac à sable. Le scénario
  est réécrit pour décrire la liste réelle (écart E2 du dossier `journalisation`).
- `distribution-docker` : le scénario « Classement des services réels » cite
  `scripts/quality/check-compose-limits.py`, qui ne contrôle aucune image, et affirme que chaque service a l'une
  des deux images MINOS alors que les services tiers de la pile connectée ont les leurs. Le scénario est restreint
  aux services MINOS et à la commande qui le prouve (écarts E3 et E4).
- `analyse-git` : la neutralisation des filtres vaut aussi depuis un worktree lié, dont la configuration est
  celle du dépôt principal ; c'est implémenté et testé, mais aucun scénario ne l'énonce. Un scénario est ajouté à
  l'exigence sur les filtres (écart E2 du dossier `analyse-git`, volet filtres).
- `tests-lies-et-impact` : le scénario « Score inchangé par le plafond » compare une dérivation « avec puis sans
  plafond », inexécutable puisque le plafond est une constante ; il est réécrit en comparaison « une occurrence
  par paire contre plus que le plafond », celle que le test exécute (écart E3).

Aucun comportement du produit ne change : seuls des scénarios sont précisés. Aucune **RUPTURE**.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `journalisation` : scénario « Diagnostic de bac à sable listé » de l'exigence « La cause d'un journal n'est
  transmise que par une liste nominative ».
- `distribution-docker` : scénario « Classement des services réels » de l'exigence « Chaque service Docker
  utilise l'image qui correspond à son rôle ».
- `analyse-git` : scénario ajouté à l'exigence « Aucun filtre déclaré par le dépôt analysé ne s'exécute ».
- `tests-lies-et-impact` : scénario « Score inchangé par le plafond » de l'exigence « Les preuves d'une relation
  de test lié sont bornées et l'omission est déclarée ».

## Hors périmètre

- Les écarts qui demandent une décision métier ou du code : ordre ouverture / contrôle du propriétaire
  (`analyse-git` E1), limite déclarée des tests liés (`tests-lies-et-impact` E4), exécution réelle des providers
  Node (`socle-d-execution` E1), énoncé « avant tout appel à Docker » (`distribution-docker` E6), contrôle du
  propriétaire sur le répertoire commun d'un worktree lié (`analyse-git` E2, volet propriétaire : aucun test de
  refus n'existe). Ils restent consignés dans leurs dossiers.
- Les deux exigences de racine des projets de `distribution-docker` : le changement
  `exposer-seulement-les-projets-inscrits-a-docker` les retire.
- Tout ajout de test : les quatre scénarios réécrits décrivent des preuves qui existent déjà.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| La liste nominative des causes de journal compte six entrées, dont trois de bac à sable | [Existant vérifié] | `scripts/architecture/private-io-allowlist.json` |
| `check-compose-limits.py` ne contrôle pas les images | [Existant vérifié] | `scripts/quality/check-compose-limits.py` |
| `minos-postgres` et `minos-ollama` utilisent leurs propres images, tolérées par le test de contrat | [Existant vérifié] | `docker/compose-mcp.connected.yaml:285`, `minos-app/src/test/java/com/minos/packaging/M29DockerQueryImageContractTest.java:39` |
| Le propriétaire est contrôlé sur le répertoire Git et sur le répertoire commun | [Existant vérifié] | `minos-integration-git/src/main/java/com/minos/integration/git/GitIntelligenceService.java:234-235` |
| La neutralisation des filtres depuis un worktree lié a un test ; le refus d'un worktree lié d'un autre propriétaire n'en a pas | [Existant vérifié] | `minos-integration-git/src/test/java/com/minos/integration/git/GitIntelligenceTrustTest.java:96` |
| Le plafond de preuves est une constante | [Existant vérifié] | `minos-engine/src/main/java/com/minos/query/RelatedTestDerivationService.java:53` |
| Préciser ces scénarios ne change aucun comportement attendu | [Proposition] | À valider par le propriétaire |

## Impact

- **Modules du reactor** : aucun code modifié.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun.
- **Gates à rejouer** : `openspec validate --all --strict`, `python scripts/docs/check-capability-dossiers.py`.
- **Plateformes** : sans objet (aucun code).
- **Documentation durable et diagrammes** : dossiers `journalisation`, `distribution-docker`, `analyse-git`,
  `tests-lies-et-impact` (écarts clos après archivage).

## Questions ouvertes

Aucune.
