# Spécifier avec OpenSpec : le schéma `detailed-uml`

Ce guide dit comment produire, maintenir et faire évoluer les spécifications de MINOS avec le schéma OpenSpec
du projet. La règle courte pour les agents est `.claude/rules/openspec.md` ; la configuration est
`openspec/config.yaml`.

## Ce que contient le schéma

| Fichier | Rôle |
|---|---|
| `openspec/schemas/detailed-uml/schema.yaml` | Artefacts, dépendances et consignes envoyées à l'agent |
| `openspec/schemas/detailed-uml/templates/proposal.md` | Pourquoi, périmètre, statut des informations, impact |
| `openspec/schemas/detailed-uml/templates/spec.md` | Delta d'exigences et scénarios GIVEN/WHEN/THEN/AND |
| `openspec/schemas/detailed-uml/templates/design.md` | État vérifié, composants, contrats, diagrammes, décisions, traçabilité |
| `openspec/schemas/detailed-uml/templates/tasks.md` | Tâches rouge → correction → preuve, documentation par groupe, Clôture |

Ordre des artefacts : `proposal` → `specs` → `design` (requiert la proposition et les specs, pour référencer
les exigences) → `tasks` (requiert specs et design) → `apply` (requiert tasks, suit `tasks.md`).
`design.md` est toujours produit ; pour un changement purement documentaire, d'outillage ou de configuration,
il se réduit à Contexte, Décisions, Vérification et Traçabilité, les autres sections portant
« Non applicable : <raison> ».

## Qui porte quoi

| Support | Contenu | Ne contient pas |
|---|---|---|
| `openspec/changes/<change>/` | L'évolution proposée : delta d'exigences, conception propre au changement, tâches | La vue consolidée du système |
| `openspec/specs/<capacité>/spec.md` | Les exigences en vigueur (fusionnées par `openspec archive`) | Diagrammes, preuves, conception |
| `docs/architecture/diagrams/capacites/<capacité>.md` | Dossier durable : vues Mermaid, traçabilité exigence → preuve, écarts constatés | Exigences nouvelles (elles passent par un changement) |
| `docs/architecture/` (arc42, `diagrams/`) | Vue d'ensemble consolidée et diagrammes transverses | Le détail d'une capacité |
| `docs/architecture/adr/` | Décisions structurantes, leurs alternatives et conséquences | Spécifications de comportement, plans, preuves datées |

Un ADR ne s'écrit que pour un choix entre options réelles qui contraint durablement plusieurs modules, une
surface publique, un format persisté, un modèle de confiance ou la toolchain (voir
[Quand écrire un ADR](../architecture/adr/README.md)). Le design le nomme ; une tâche l'écrit.

## Statut d'une information

Chaque artefact distingue, avec ces étiquettes :

| Étiquette | Sens | Preuve attendue |
|---|---|---|
| `[Exigence]` | Comportement attendu, issu d'une spec ou d'une demande | Titre de l'exigence ou source de la demande |
| `[Existant vérifié]` | Comportement observé au HEAD | `chemin:ligne`, test ou commande exécutée |
| `[Décision]` | Choix acté | ADR, décision du propriétaire, design validé |
| `[Proposition]` | Choix à valider | Recommandation et alternatives |
| `[Question]` | Inconnue | Bloquante ou non |

Le code prouve l'implémentation, pas l'intention métier. Une divergence entre code, tests et spec se consigne
comme **écart** ; on ne réécrit pas une exigence pour rendre le code conforme.

## Conventions des specs

- Marqueurs OpenSpec conservés tels quels : `## ADDED/MODIFIED/REMOVED/RENAMED Requirements`,
  `### Requirement:`, `#### Scenario:` (quatre `#`), `**GIVEN**`/`**WHEN**`/`**THEN**`/`**AND**`, SHALL/MUST.
  Le reste est en français. Dans la proposition, les titres `## Why`, `## What Changes`, `## Capabilities`,
  `### New Capabilities`, `### Modified Capabilities` et `## Impact` restent en anglais : le validateur et les
  skills les lisent.
- Une exigence = un comportement, description de 500 caractères au plus (`openspec validate --strict`).
- **Identifiant** : le titre de l'exigence est la clé de synchronisation d'OpenSpec ; il sert aussi de clé de
  traçabilité, sous la forme `capacité › titre exact`. Il est unique dans sa capacité et ne change que par une
  section `## RENAMED Requirements`. Aucun préfixe numérique n'est ajouté aux titres existants : ce serait un
  renommage de chaque exigence.
- Scénario : situation initiale (GIVEN), action unique (WHEN), résultat observable (THEN), effets persistés ou
  leur absence (AND), erreur attendue pour un échec. Un scénario est vérifiable par un test ou une commande
  locale. Aucun seuil, règle métier ou performance inventé.
- Enrichir une exigence existante (scénario d'erreur ajouté) passe par un changement : bloc `MODIFIED` complet,
  texte d'origine conservé.

## Dossier durable d'une capacité

`docs/architecture/diagrams/capacites/<capacité>.md`, indexé par
[le README du dossier](../architecture/diagrams/capacites/README.md). Structure contrôlée par
`scripts/docs/check-capability-dossiers.py` :

1. un lien vers la spec de référence ;
2. `## Vues` : une sous-section `###` par diagramme, titrée par la question à laquelle il répond, avec une ligne
   `Type :` (type exact, portée existant ou cible, exigences illustrées), un seul bloc Mermaid, puis son
   explication ;
3. `## Traçabilité` : une ligne par exigence de la spec (titre exact), avec scénarios, vue, preuves entre
   backticks (`chemin`, `chemin:ligne` ou `chemin#méthode`) et un statut `couverte`, `partielle`, `manquante`
   ou `non vérifiée` ;
4. les écarts et questions ouvertes.

Le contrôle refuse une exigence absente ou inconnue du tableau, une preuve citée qui n'existe pas, une ligne
`couverte` ou `partielle` sans preuve existante, une preuve dont le chemin contient `..`, une méthode citée (`chemin#méthode`) absente de son fichier, une vue sans type ou
sans bloc Mermaid, un lien relatif cassé, l'absence de l'index `capacites/README.md` (une cible introuvable fait
échouer le contrôle, GEN-0043). Il ne prouve pas qu'un test vérifie réellement l'exigence : c'est la revue sémantique.

### Lien depuis la spec de référence

Seuls les blocs d'exigences d'un delta sont fusionnés par `openspec archive`. Le lien vers le dossier vit donc
dans une section finale de la spec de référence, `## Documentation liée`, placée après `## Requirements`, sans
titre `###` (il serait lu comme une exigence). L'archivage conserve cette section (vérifié avec OpenSpec 1.14.3
sur une copie isolée). C'est, avec `## Purpose`, la seule partie de `openspec/specs/` qui s'édite à la main.

## Produire la prochaine spécification

```bash
openspec new change <nom-du-changement>
openspec status --change <nom-du-changement>
openspec instructions proposal --change <nom-du-changement>
```

Puis `instructions specs`, `instructions design`, `instructions tasks` dans l'ordre que donne `status`. Avec
Claude Code : `/opsx:propose` ou `/opsx:new` puis `/opsx:continue`. Avant de commiter :

```bash
openspec validate --all --strict
python scripts/docs/check-capability-dossiers.py
```

Une fois le changement implémenté : `/opsx:verify`, puis archivage par `openspec archive <nom> -y` sur demande,
puis mise à jour du dossier durable si la Clôture ne l'a pas déjà fait.

`python scripts/docs/check-capability-dossiers.py --report` imprime, pour chaque capacité, le nombre
d'exigences et de scénarios et la présence d'un dossier.

## Changements créés avec un autre schéma

Un changement garde le schéma de son `.openspec.yaml` ; sans ce fichier, il prend celui de
`openspec/config.yaml`. Un changement créé sous `spec-driven` y reste épinglé : ses artefacts existants restent
valides et s'enrichissent sans changer de format. Le passer sous `detailed-uml` revient à changer `schema:` dans
son `.openspec.yaml`, puis à vérifier `openspec status --change <nom>` et `openspec validate <nom> --strict` ;
cela ne réécrit aucun artefact.

## Maintenir le schéma

Après toute modification du schéma ou d'un modèle :

```bash
openspec schema validate detailed-uml
openspec templates --schema detailed-uml
openspec instructions design --change <un-changement-detailed-uml>
```

La troisième commande montre ce que l'agent reçoit réellement : contexte et règles de `config.yaml`, consigne
du schéma, modèle.

### Comparer au schéma officiel

Le fork est une copie figée : `openspec update` ne touche jamais `openspec/schemas/`. Base du fork :
`spec-driven` d'OpenSpec **1.14.3**, empreintes SHA-256 :

| Fichier amont | SHA-256 |
|---|---|
| `schema.yaml` | `b6f28f305ab7f33b061949251bba82c79de66c82424cfed60582327db3f888f2` |
| `templates/design.md` | `6ccc87f5017fb190e69e8759dd4e20d5ac3320cc4dabf152708bf8f2aa5205f8` |
| `templates/proposal.md` | `0dd70e35db5c696ba8ac3cc3a53e2d6561865c1e4e7d49db5a1eaa3e4a6ad86e` |
| `templates/spec.md` | `1cccfb573f031c6352bed2a879b7035ff710bc909112fc23d10bdcfcdd8d4318` |
| `templates/tasks.md` | `fa67d99fb4a93b12d702f89ea83e1838219d1cad1c90a4192c402278001cd30e` |

Après une montée de version d'OpenSpec :

```bash
openspec --version
openspec schema which spec-driven --json
```

Calculer les empreintes des fichiers du chemin rendu (`sha256sum schema.yaml templates/*` depuis ce dossier).
Si l'une diffère, comparer l'amont au fork :

```bash
git diff --no-index "<chemin rendu>" openspec/schemas/detailed-uml
```

Reporter dans le fork les évolutions de format de l'amont (marqueurs, limites de validation, champs de
`schema.yaml`), garder les consignes propres à MINOS, rejouer `openspec schema validate detailed-uml` et le test
d'archivage ci-dessous, puis mettre à jour la version et les empreintes de ce tableau.

### Rejouer le test d'archivage

Sur une copie isolée (jamais sur un changement réel) : une spec de référence avec une section
`## Documentation liée`, un changement qui modifie une exigence (lien et bloc Mermaid dans le scénario) et en
ajoute une, puis `openspec archive <change> -y`. Attendu : la section finale, le lien et le bloc sont conservés,
l'exigence ajoutée est insérée avant la section finale, `openspec validate <capacité> --type spec --strict`
réussit.

## Limites

- `openspec validate` contrôle la structure, pas la complétude des règles métier.
- `check-capability-dossiers.py` vérifie le premier mot-clé de chaque bloc Mermaid, pas toute sa syntaxe ; le
  rendu se contrôle sur GitHub ou avec un outil Mermaid.
- Les consignes du schéma sont lues par l'agent ; elles ne garantissent pas le résultat. La revue humaine reste
  nécessaire.
