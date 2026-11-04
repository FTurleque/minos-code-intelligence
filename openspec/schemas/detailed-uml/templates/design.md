# Design

<!-- Rédiger en français. Chaque section est remplie avec des faits propres au changement ou
     porte « Non applicable : <raison> » sur une ligne. Changement purement documentaire,
     d'outillage ou de configuration : seuls Contexte, Décisions, Vérification et Traçabilité
     sont exigés.
     Étiquettes : [Exigence] [Existant vérifié] [Décision] [Proposition] [Question].
     Référencer une exigence par « capacité › titre exact ». Ne pas recopier la proposition ni
     les specs : y renvoyer. -->

## Contexte

<!-- Ce qu'il faut savoir pour comprendre l'approche ; motivation : voir proposal.md (Why).
     Composants et modules touchés, surfaces publiques, plateformes. -->

## État vérifié au HEAD

<!-- [Existant vérifié] avec chemin:ligne relus au HEAD (commit cité), tests existants,
     commandes exécutées et leur résultat. Dire ce qui n'a pas été mesuré. -->

| Fait | Preuve |
|---|---|
| <fait> | `<chemin:ligne>` |

## Écarts

<!-- Divergences entre code, tests, specs, documentation et demande. Ne jamais réécrire une
     exigence pour rendre le code conforme : nommer l'écart et la suite proposée. « Aucun »
     sinon. -->

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|

## Exigences couvertes

<!-- Une ligne par exigence des specs delta : capacité › titre, et section du design qui la
     réalise. -->

## Objectifs et non-objectifs

<!-- Frontières de conception seulement (le périmètre est dans la proposition). -->

## Responsabilités et frontières des composants

<!-- Qui fait quoi ; direction des dépendances domain → engine → runtime/storage → adapters →
     application → surfaces (ADR 0068) ; toute exception expliquée. Vue de composants si
     plusieurs modules changent. -->

## Modèle de domaine et invariants

<!-- Types, relations, cardinalités, invariants vérifiables. classDiagram si la structure ne
     tient pas en trois phrases. -->

## Traitements et interactions

<!-- Déroulé nominal et alternatives d'erreur. sequenceDiagram avec blocs alt pour les refus. -->

## États et transitions

<!-- Cycle de vie d'un objet ou d'un processus : stateDiagram-v2, états terminaux et états
     d'erreur compris. -->

## Contrats d'interface

<!-- Adapter à l'interface réelle : commande CLI (options, codes de sortie, sortie JSON), API
     Java, outil MCP STDIO (lecture seule), protocole du plugin, script, format persisté. Pour
     chaque opération : autorisations, champs requis ou facultatifs (type, contrainte), exemple
     d'entrée et de sortie, erreurs et codes, bornes ou pagination, compatibilité (additive ou
     RUPTURE), idempotence. Référencer le contrat exécutable existant (schéma, golden, test de
     contrat) au lieu de le recopier. -->

## Persistance et intégrité

<!-- Données écrites, format, emplacement, contraintes d'intégrité, version de schéma.
     erDiagram si plusieurs entités liées. -->

## Transactions, concurrence et idempotence

<!-- Verrous, atomicité, reprise après interruption, relance sans effet double. -->

## Sécurité et isolation

<!-- Fail-closed, confinement du code non fiable, secrets, environnement des processus, E/S
     privées, absence de chemin absolu dans les sorties (.claude/rules/securite.md). -->

## Erreurs et reprise

<!-- Erreurs d'usage actionnables, erreurs internes opaques, codes, état laissé après échec. -->

## Observabilité

<!-- Journaux (stderr), diagnostics, métriques ; ce qui permet de constater le comportement. -->

## Performance et mesure

<!-- Objectif chiffré seulement avec une source. Sinon : stratégie de mesure (commande,
     jeu de données, plateforme) et seuil en [Question]. -->

## Compatibilité, migration et retour arrière

<!-- Surfaces versionnées et additives ; migration d'une installation existante ; retour
     arrière et ce qu'il laisse. -->

## Diagrammes

<!-- Un sous-titre par diagramme, sous forme de question. Pour chacun :
     - type exact (classDiagram, sequenceDiagram, stateDiagram-v2, flowchart, erDiagram,
       vue de composants ou C4) ; un flowchart n'est pas un diagramme UML ;
     - portée : existant vérifié ou cible proposée ;
     - bloc ```mermaid avec les noms réels du code ou des documents, libellés spéciaux entre
       guillemets, une quinzaine de nœuds au plus ;
     - explication en deux à cinq lignes et exigences illustrées.
     Diagramme métier durable : à reporter dans docs/architecture/diagrams/capacites/<capacité>.md
     par une tâche. -->

### <Question à laquelle répond le diagramme>

Type : <type> · Portée : <existant | cible> · Exigences : <capacité › titre>

```mermaid
```

<!-- explication -->

## Décisions

<!-- Une sous-section par décision : D<n> — titre. Statut ([Décision] ou [Proposition]),
     choix, alternatives écartées et raison, compromis. Décision structurante : ADR à écrire
     ou à amender (numéro), planifié en tâche. -->

## Qualification des capacités

<!-- Chaque capacité nouvelle ou modifiée : qualifiée, partielle ou non supportée, avec la
     preuve ou la limite. Comportement propre à Windows ou à Linux. -->

## Risques et compromis

<!-- [Risque] → atténuation. -->

## Vérification

<!-- Tests (nom de la classe ou du script), gates check-*.py, scopes JaCoCo, commandes
     locales, plateformes ; ce qui ne peut pas être vérifié localement et pourquoi. -->

## Documentation durable à mettre à jour

<!-- Dossier de capacité (docs/architecture/diagrams/capacites/<capacité>.md), pages
     docs/user/ et docs/developer/, vues arc42, ADR, STATUS et ROADMAP. La synchronisation
     des specs ne les met pas à jour. -->

## Traçabilité

<!-- Une ligne par scénario. Colonnes Tâche et Test ou preuve : identifiant réel, ou
     « à écrire » / « manquante » ; jamais une couverture affirmée sans preuve. -->

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|

## Questions ouvertes

<!-- Seulement ce qui peut être tranché plus tard sans changer specs, approche ni tâches.
     Une décision du propriétaire attendue porte une recommandation. « Aucune » sinon. -->
