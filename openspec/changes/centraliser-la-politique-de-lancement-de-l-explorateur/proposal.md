# Proposal

## Why

Constat **AUD-ARC-05** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité faible ; suite de la réserve « rien ne surveille le contenu de minos-app » d'AUD-ARC-11 du 2026-10-10) : `minos-app`, module d'assemblage à la politique de dépendances la plus large, porte la politique de démarrage de l'explorateur (refus en mode équipe, refus sur backend Docker) en double de celle d'`ExplorerSession`. La valeur de `MINOS_HOSTED_MODE` est interprétée à trois endroits avec trois règles : une valeur non reconnue fait échouer l'ouverture de l'application (« unsupported hosted mode »), mais est annoncée par la route de l'explorateur comme « mode équipe activé ».

## What Changes

- L'interprétation du mode équipe est faite par une seule fonction de `minos-application` (nom proposé : `HostedMode.from(settings)`, dans le package `com.minos.application`), utilisée par la configuration d'exécution et par le lancement de l'explorateur.
- La décision de lancement de l'explorateur (emplacement d'exécution, backend du home, mode équipe) devient une politique de `minos-explorer`, qui détient les deux messages de refus, chacun une seule fois. `ExplorerLaunchRouteProvider` (`minos-app`) ne garde que la lecture du backend du home et le câblage.
- **Comportement modifié** : `minos explorer serve` sur un home dont le mode équipe porte une valeur non reconnue refuse avec le message d'usage « unsupported hosted mode », code 2, au lieu du message « mode équipe activé », code 2. Le refus et son code sont conservés.
- Le garde de frontières tient la liste fermée des classes de production de `minos-app` : une classe nouvelle y entre par une édition visible et justifiée.
- La Javadoc de `McpBackendConfiguration` et de `McpBackendConfigurationStore` dit que ce réglage est le backend d'exécution du home, lu par les routes MCP et explorateur. Aucune classe n'est renommée.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `exploration-semantique` : exigences ajoutées sur l'interprétation unique du mode équipe au lancement et sur l'unicité des messages de refus.
- `frontieres-de-modules` : exigence ajoutée sur la liste fermée des classes de `minos-app`.

## Hors périmètre

- Le renommage de `McpBackendConfiguration` en configuration de backend du home : écarté (design, D3).
- Le déplacement du transport MCP Docker (`DockerMcpTransport`) hors de `minos-app` : non demandé par le constat ; la liste fermée le fige.
- Les codes de sortie (AUD-ARC-06) : changement `unifier-le-contrat-des-codes-de-sortie`.
- Les réponses HTTP et la fiabilité de la surface (sprint 3, changement `fiabiliser-la-surface-de-l-explorateur`).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Deux constantes `TEAM_MODE_REFUSED` de textes différents | [Existant vérifié] | `minos-app/src/main/java/com/minos/app/ExplorerLaunchRouteProvider.java:32-34` ; `minos-explorer/src/main/java/com/minos/explorer/ExplorerSession.java:22-24` |
| Trois interprétations du mode équipe | [Existant vérifié] | `ExplorerLaunchRouteProvider.java:78-81` ; `ExplorerSession.java:71-74` ; `minos-application/src/main/java/com/minos/application/MinosApplicationRuntimeConfiguration.java:63-68` |
| `minos-app` : 8 classes de production, 635 lignes | [Existant vérifié] | décompte des fichiers de `minos-app/src/main/java` |
| La commande refuse de démarrer en mode équipe | [Exigence] | `openspec/specs/exploration-semantique/spec.md:8-24` |
| Une valeur non reconnue donne le message « unsupported hosted mode » | [Proposition] | design, D1 |
| Aucun renommage des classes de backend | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : `minos-application` (`HostedMode`, configuration d'exécution), `minos-explorer` (politique de lancement, `ExplorerSession`), `minos-app` (`ExplorerLaunchRouteProvider`).
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : CLI — texte du refus de `minos explorer serve` unifié, et message différent pour une valeur de mode équipe non reconnue ; codes de sortie inchangés. Aucun autre changement.
- **ADR** : aucun. ARCH-0082 (surface d'exploration) reste la décision ; son point sur le refus en mode équipe est appliqué, non modifié.
- **Gates à rejouer** : `check-module-boundaries.py` et `test_check_module_boundaries.py`, `check-hosted-control-plane-consistency.py` (affirme `"enabled"` et `"disabled"` dans `MinosApplicationRuntimeConfiguration.java`, `:100-102`, et `HOSTED_MODE_ENV = "MINOS_HOSTED_MODE"` dans `MinosApplication.java`, `:97-98`), `check-post-mne.py` et `check-p0-p2.py` (citent `McpBackendConfigurationStore` et `DockerMcpTransport`), `check-comment-language.py` ; scope JaCoCo de `check-jacoco.py:236-237`.
- **Plateformes** : Windows et Linux — sur un poste, le backend du home est lu ; dans le runtime Docker il ne l'est pas (comportement existant conservé).
- **Documentation durable et diagrammes** : `docs/user/semantic-explorer.md`, `docs/user/team-hosted-mode.md`, dossiers `exploration-semantique.md` et `frontieres-de-modules.md`.

## Questions ouvertes

Aucune bloquante. D3 (pas de renommage) est soumise au propriétaire avec une recommandation.
