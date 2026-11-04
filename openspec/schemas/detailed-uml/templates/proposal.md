# Proposal

<!-- Rédiger en français. Garder les titres anglais marqués « technique » : les outils
     OpenSpec les lisent. Une section sans objet porte « Non applicable : <raison> ». -->

## Why

<!-- technique. Problème ou opportunité, avec sa source vérifiable : constat d'audit
     (identifiant), incident, demande, ADR. Pourquoi maintenant. -->

## What Changes

<!-- technique. Liste précise : comportement ajouté, modifié ou retiré. Marquer une rupture
     de contrat par **RUPTURE**. Une fonctionnalité future n'est jamais présentée comme
     disponible. -->

## Capabilities

### New Capabilities
<!-- technique. Comportement durable du système, nom kebab-case, organisation à plat.
     Chaque entrée crée specs/<capacité>/spec.md. Dire pourquoi aucune capacité existante
     ne convient. -->
- `<capacité>` : <ce que la capacité couvre> — <pourquoi elle est nouvelle>

### Modified Capabilities
<!-- technique. Chemin exact sous openspec/specs/ (openspec list --specs). Seulement si une
     exigence change. Aucun delta : fixer skip_specs: true dans .openspec.yaml. -->
- `<capacité-existante>` : <exigence qui change ou scénario ajouté>

## Hors périmètre

<!-- Toujours renseigné : ce que ce changement ne fait pas, et ce qui reste à un autre
     changement (nommé s'il existe). -->

## Statut des informations

<!-- Classer les affirmations clés. Le code prouve l'implémentation, pas l'intention métier.
     Lexique commun à tous les artefacts :
     [Exigence]          comportement attendu, issu d'une spec ou d'une demande
     [Existant vérifié]  comportement observé au HEAD (chemin:ligne, test, commande)
     [Décision]          choix acté (par qui, où : ADR, design, propriétaire)
     [Proposition]       choix à valider
     [Question]          inconnue ; bloquante ou non -->

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| <affirmation> | [Existant vérifié] | `<chemin:ligne>` |

## Impact

<!-- technique (titre). Renseigner chaque ligne ou écrire « aucun ». -->
- **Modules du reactor** :
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : <additif ou RUPTURE>
- **ADR** : <aucun / à écrire / à amender (numéro)>
- **Gates à rejouer** : <check-*.py, scopes JaCoCo>
- **Plateformes** : Windows et Linux — <comportement propre à une plateforme>
- **Documentation durable et diagrammes** : <dossier de capacité, pages docs/, vues arc42>

## Questions ouvertes

<!-- Questions bloquantes (à trancher avant les specs) puis non bloquantes. « Aucune » sinon. -->
