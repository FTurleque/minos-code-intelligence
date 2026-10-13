# Design

## Context

Sprint 7 de l'audit du 10 octobre 2026 : **AUD-SEC-08**. HEAD analysé : `949e1569` (`develop`). `REN` = `minos-application/src/main/java/com/minos/output/ArchitectureResultRenderer.java`. Règle de référence : `.claude/rules/securite.md`, point 7 (aucun HTML non échappé dans une sortie publique).

## État vérifié au HEAD

### AUD-SEC-08 — confirmé à la lecture

- `REN:168-173` : `label = module.name() + "<br/>" + module.relativePath()` (ou le nom seul), puis `mermaidText(label)` dans `alias["…"]`.
- `REN:274-280` : `mermaidText` remplace `&`, `"`, `|`, `\r`, `\n` ; ni `<` ni `>`.
- `REN:163-166` : lignes `%% MINOS project: … | snapshot: …` et `%% limitation: …`, alimentées par `view.projectName()`, `view.snapshotId()` et les limitations, à travers le même `mermaidText`.
- Le rendu est partagé : `minos-cli/src/main/java/com/minos/cli/command/ArchitectureCommand.java:49` (`ArchitectureResultRenderer.renderGraph`) et le backend MCP.
- `minos-app/src/test/resources/characterization/mcp-all-tools.golden` contient `<br/>` : c'est le seul golden qui le contient.
- Test existant de la classe : `minos-application/src/test/java/com/minos/output/ArchitectureResultRendererLimitationsTest.java`.

**Non vérifié** : aucun build ni test lancé. Le **rendu réel de `&lt;` par Mermaid** dans un libellé entre guillemets n'a pas été observé (Mermaid documente aussi une syntaxe d'entités en `#…;`). L'**exploitabilité de `%%{`** dans une ligne de commentaire n'a pas été vérifiée, ni l'effet d'un code d'entité `#…;` fourni par le dépôt. La confiance du constat reste « plausible » : l'exécution dépend du consommateur.

### Écarts avec l'action proposée par l'audit

1. **L'ordre compte.** `<br/>` est concaténé avant l'échappement (`REN:171`). Échapper les chevrons dans `mermaidText` sans déplacer le saut de ligne produirait `&lt;br/&gt;` et casserait le golden. Le saut est donc inséré après l'échappement de chaque partie.
2. **Un site voisin non relevé** : les lignes de commentaire (`REN:163-166`). Le nom du projet peut dériver d'un nom de répertoire ; s'il contient `%%{…}%%`, il pourrait être lu comme une directive Mermaid. Hypothèse à vérifier (tâche 1.2).
3. **La forme de l'entité n'est pas prouvée.** L'audit propose `&lt;` et `&gt;` ; le code utilise déjà `&amp;`, `&quot;` et `&#124;`, mais aucun test du dépôt ne rend un diagramme. La forme est confirmée avant d'être figée (tâche 1.1).

## Goals / Non-Goals

**Goals** : aucun chevron issu du dépôt dans un libellé Mermaid ; le saut de ligne de MINOS conservé ; aucune directive injectable par un texte du dépôt ; sorties nominales identiques.

**Non-Goals** : assainir à la place du consommateur ; changer le rendu DOT ou JSON ; filtrer les noms de modules à l'indexation.

## Decisions

### D1. Échapper chaque partie, puis assembler

Le nom et le chemin relatif sont échappés séparément ; `<br/>` est ajouté entre les deux par MINOS. `&` reste échappé en premier (sinon `&lt;` deviendrait `&amp;lt;`).

### D2. Forme de l'entité fixée par la vérification

`&lt;` / `&gt;` par défaut, cohérent avec `&amp;` et `&quot;` déjà émis. Si la tâche 1.1 montre qu'un moteur Mermaid courant affiche l'entité littéralement, la forme numérique ou la syntaxe `#…;` est retenue, et la décision est consignée ici. L'exigence porte sur le résultat (aucun `<` ni `>` issu du dépôt), pas sur la forme.

### D3. Commentaires : neutraliser sans attendre la preuve d'exploitabilité

Dans une ligne `%% …`, un texte issu de la vue ne peut plus produire la séquence `%%{`. La neutralisation ne vise que les textes de la vue, pas le préfixe `%%` écrit par MINOS : aucune sortie nominale n'est censée changer (à confirmer par les goldens en tâche 3.2), ce qui la rend moins coûteuse que la preuve du contraire ; la tâche 1.2 qualifie quand même le risque pour le suivi.

### D4. Aucun gate littéral

Garde par tests de comportement dans le module de la classe. Aucun script de `scripts/` ne cite ces noms.

## Direction des dépendances

Changement interne à `minos-application` (`com.minos.output`). Aucune arête nouvelle ; `minos-cli` et `minos-mcp` consomment déjà ce rendu.

## Qualification des capacités

- Rendu Mermaid du graphe d'architecture : qualifié pour les noms sans caractère actif, inchangé.
- Rendu d'un nom contenant `<` ou `>` : aujourd'hui émis brut ; devient **qualifié en sortie** (échappé) ; l'affichage chez le consommateur reste **non qualifié** tant que 1.1 n'a pas été faite.

## Windows et Linux

Aucune différence de comportement. Sous Windows, `<`, `>` et `|` sont interdits dans un nom de fichier mais pas dans un nom de module déclaré par un fichier de build ; `%` et `{` sont permis dans un nom de répertoire sur les deux systèmes.

## Risks / Trade-offs

- Un consommateur qui attendait un chevron littéral dans un nom de module verra une entité : cas limite, conforme à la règle de sécurité.
- Si l'entité choisie s'affiche littéralement dans un moteur donné, le libellé est moins lisible mais reste inoffensif.
- SonarCloud juge le code neuf du module à 80 % : les deux fonctions touchées sont couvertes par les tests de la tâche 2.1.
