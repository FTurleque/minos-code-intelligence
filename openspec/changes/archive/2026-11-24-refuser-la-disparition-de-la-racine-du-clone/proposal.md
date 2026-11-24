# Proposal

## Why

Constat **AUD-SEC-11** de l'audit `docs/project/audits/2026-11-15/` (sprint 10, sévérité faible, ouvert le 2026-11-20 au HEAD `535a5017`). Depuis la PR #800, la mesure du budget d'un clone distant ignore toute `NoSuchFileException` levée pendant le parcours de l'arbre, y compris celle de la racine mesurée : quand la racine disparaît entre le contrôle d'existence et le parcours, la mesure rend 0 octet, 0 fichier, 0 répertoire au lieu d'échouer. La justification écrite (un fichier de verrou de la maintenance JGit) ne couvre que les entrées situées sous la racine. Aucun contournement atteignable n'est constaté, mais la règle de sécurité 1 (fail-closed, `.claude/rules/securite.md`) est affaiblie sur un cas que la PR n'avait pas à couvrir. Les tests actuels appellent les méthodes du visiteur avec des exceptions fabriquées ; aucun ne fait un vrai parcours où une entrée disparaît, ni ne couvre la racine absente.

## What Changes

- La mesure d'un clone distant échoue quand la racine mesurée disparaît pendant le parcours, que l'échec arrive à la lecture de la racine ou à la fin de son listage (décisions D1 et D2 du design).
- L'échec porte un message stable, sans chemin absolu, comme les autres refus du budget ; la cause d'origine reste attachée (D3).
- Une entrée située sous la racine qui disparaît pendant le parcours reste ignorée après avoir été comptée comme entrée parcourue ; toute autre erreur d'accès fait toujours échouer la mesure (inchangé).
- `RemoteCloneBudgetTest` ajoute un parcours réel dont la racine est absente (échec attendu), la racine qui disparaît en fin de listage (échec attendu) et un parcours réel où un répertoire frère disparaît pendant la marche (entrée ignorée), stable sur Windows et Linux (D4).
- Proposé au propriétaire : faire entrer `RemoteCloneBudget` dans le scope JaCoCo `m25-remote-distributed-indexing`, qui n'en couvre aujourd'hui que la sous-classe `JGitRemoteRepositoryMaterializer$CloneBudget` (D6).

Aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change pour un clone dont l'arbre est stable.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `analyse-git` : exigence ajoutée « La mesure du budget d'un clone distant échoue quand sa racine disparaît ». La capacité porte déjà la matérialisation d'un dépôt distant (clone, extraction, revalidation) et son dossier durable décrit le moniteur de budget du clone (vue V2) ; aucune exigence courante ne spécifie ce budget.

## Hors périmètre

- Le contrôle d'existence préalable de `checkpoint()` (`RemoteCloneBudget.java:38`) : une racine absente **avant** le parcours rend toujours une mesure vide, sans changement (design, écart 3 et D5).
- Une racine qui serait un lien symbolique : le parcours ne suit pas les liens et mesurerait le lien seul ; cas non atteint par les appelants actuels, nommé en question ouverte du design (écart 5).
- Les seuils du budget (`RemoteRepositoryCachePolicy`), le délai, l'éviction du cache et la publication d'une entrée.
- Les autres constats du sprint 10 (AUD-ARC-12, AUD-TST-13, AUD-PERF-15) et des autres sprints.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `visitFileFailed` compte l'entrée puis tolère toute `NoSuchFileException`, racine comprise | [Existant vérifié] | `minos-integration-git/src/main/java/com/minos/integration/git/RemoteCloneBudget.java:76-83`, `:120-122` |
| `postVisitDirectory` tolère de même une `NoSuchFileException`, racine comprise | [Existant vérifié] | `RemoteCloneBudget.java:85-89` |
| Un parcours dont la racine est absente appelle `visitFileFailed` sur la racine avec une `NoSuchFileException` | [Existant vérifié] | sonde `Files.walkFileTree` (JDK 24.0.1) sur Windows 10 et Linux (WSL Ubuntu), design « État vérifié au HEAD » |
| La justification écrite ne vise qu'une entrée (fichier de verrou) | [Existant vérifié] | `RemoteCloneBudget.java:116-119` |
| Aucun test ne fait de parcours réel où une entrée disparaît, ni ne couvre la racine absente | [Existant vérifié] | `minos-integration-git/src/test/java/com/minos/integration/git/RemoteCloneBudgetTest.java:21-81` |
| Le superviseur de quota d'écriture distingue déjà la racine (`!file.equals(root)`) | [Existant vérifié] | `minos-runtime-local/src/main/java/com/minos/runtime/local/workspace/ProviderWriteQuotaSupervisor.java:261` |
| Seule la racine est refusée ; critère d'égalité avec la racine mesurée | [Proposition] | design, D1 |
| Message stable sans chemin, cause attachée | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : `minos-integration-git` (`RemoteCloneBudget` et `RemoteCloneBudgetTest`). Aucune dépendance nouvelle.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune sortie changée pour un arbre stable ; une racine qui disparaît pendant la mesure fait échouer la matérialisation au lieu de la laisser échouer plus loin (`validateCheckout`, `readProperties`). Message public déjà assaini par `PublicErrorMessages.sanitize` (`minos-cli/.../CliCommandSupport.java:225-232`).
- **ADR** : aucun créé ni amendé. SEC-0083 décrit le moniteur de budget du clone sans en fixer la règle de disparition.
- **Gates à rejouer** : `scripts/quality/check-remote-distributed-consistency.py` (et `--self-test`), `scripts/remediation/check-post-mne.py`, `scripts/quality/check-bounded-helpers.py` (et `--self-test`, fichier témoin `RemoteCloneBudget.java`), `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py` ; scope JaCoCo `m25-remote-distributed-indexing` (`scripts/quality/check-jacoco.py:211-232`).
- **Plateformes** : Windows et Linux. Sur Windows, un **fichier** supprimé après le listage est rendu avec ses attributs en cache et n'échoue pas ; seul un répertoire supprimé produit le même événement sur les deux systèmes (design, écart 2, sonde).
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/analyse-git.md` (ligne de traçabilité de l'exigence ajoutée, vue V2 inchangée).

## Questions ouvertes

Aucune bloquante : D1 à D6 portent une recommandation.
