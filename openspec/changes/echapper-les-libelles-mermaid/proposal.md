# Proposal

## Why

**AUD-SEC-08** (faible, confiance « plausible », sprint 7 de l'audit du 10 octobre 2026). Le rendu Mermaid du graphe d'architecture place dans un libellé de nœud le nom et le chemin relatif d'un module (`ArchitectureResultRenderer.java:168-173`). Ces textes viennent des fichiers de build du dépôt indexé, qui n'est pas de confiance. La fonction d'échappement `mermaidText` (`:274-280`) traite `&`, `"`, `|` et les fins de ligne, **pas `<` ni `>`** ; et le saut de ligne `<br/>` est concaténé **avant** l'échappement, ce qui interdit aujourd'hui d'échapper les chevrons sans casser ce saut.

Un nom de module comme `<img src=x onerror=…>` atteint donc tel quel le consommateur du diagramme (`minos_architecture_graph` en `format=mermaid`, et la commande CLI d'architecture qui partage ce rendu). Mermaid l'assainit dans son mode par défaut ; un consommateur en mode permissif ou un rendu HTML maison l'exécuterait. La règle de sécurité du dépôt (point 7) interdit le HTML non échappé dans une sortie publique.

## What Changes

- `<` et `>` échappés dans tout texte issu du dépôt ; le saut de ligne `<br/>` est inséré par MINOS **après** l'échappement.
- Les lignes de commentaire `%% …` (nom du projet, identifiant de snapshot, limitations) ne peuvent plus porter l'ouverture d'une directive Mermaid `%%{` — site voisin, **hypothèse non vérifiée**, traitée par une vérification puis une neutralisation qui ne change aucune sortie nominale.
- Le rendu réel des entités par Mermaid est vérifié avant de figer la forme de l'échappement.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `program-graph-et-architecture` : deux exigences ajoutées (chevrons échappés dans les libellés Mermaid, seul `<br/>` de MINOS subsiste ; aucune directive Mermaid dans une ligne de commentaire).

## Hors périmètre

- Le rendu Graphviz DOT (`dotText`) : libellés entre guillemets, sans forme HTML ; non relevé par l'audit.
- Le rendu JSON du graphe : l'échappement y relève de `DeterministicJson`.
- Refuser à l'indexation un nom de module contenant des chevrons : changerait la découverte ; non demandé.
- Les diagrammes de la documentation du dépôt (`docs/architecture/diagrams/`), générés par un autre outil.
- Les autres constats du sprint 7.

## Impact

- **Modules du reactor** : `minos-application` (`com.minos.output.ArchitectureResultRenderer`).
- **Surfaces publiques** : MCP (`minos_architecture_graph`, `format=mermaid`) et CLI (commande d'architecture, format Mermaid) — la sortie ne change que pour un texte contenant `<`, `>` ou `%%{` ; les sorties nominales et les goldens sont inchangés. API Java, IntelliJ, NEXUS : aucun changement.
- **ADR** : aucun.
- **Gates à rejouer** : aucun script Python de `scripts/` ne cite `mermaidText` ni `ArchitectureResultRenderer` (recherche au HEAD) ; rejouer `python .claude/scripts/run_gates.py --fast` et les goldens de `minos-app` (`minos-app/src/test/resources/characterization/mcp-all-tools.golden` contient `<br/>`). Aucun scope JaCoCo dédié à cette classe n'a été relevé dans `scripts/quality/check-jacoco.py`.
- **Plateformes** : Windows et Linux, sans différence.
