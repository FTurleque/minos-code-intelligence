# Proposal

## Why

**AUD-SEC-03** (faible, sprint 8 de l'audit du 10 octobre 2026 ; reprise de S17). Le garde des E/S privées, `scripts/architecture/check-private-io.py`, interdit quatre appels de `Files` (`createDirectories`, `write`, `writeString`, `newInputStream`), l'import statique générique, les canaux et verrous de fichier, et deux règles de journal (`FORBIDDEN`, `:54-65`). Il ne voit aucune des autres API qui créent ou écrivent un fichier.

Au HEAD `949e1569`, le gate rend `sources=527, allowlisted-occurrences=46, forbidden=10, primitives=4`. Dans `*/src/main/java` hors `minos-intellij`, on relève pourtant : `Files.newOutputStream` 21, `Files.move` 12, `Files.createTempFile` 5, `Files.createDirectory` 5, `Files.copy` 4, `Files.newBufferedWriter` 3, `Files.createTempDirectory` 1, et — non relevés par l'audit — `Files.newByteChannel` 5 hors primitives, `Files.createFile` 1 et une redirection de sortie de processus vers un fichier. Soit 43 couples (fichier, API) dans 30 fichiers, aucun compté.

L'auto-test du gate **affirme** aujourd'hui que ces appels sont permis (`scripts/architecture/test_check_private_io.py:167-170`). Rien n'empêche donc une nouvelle écriture brute sous `MINOS_HOME`, avec les droits par défaut de Java et en suivant les liens : la règle « un seul endroit décide des droits » ne tient que par la discipline des contributeurs.

## What Changes

- **Règles ajoutées au gate** : les neuf API de `Files` ci-dessus (appel, référence de méthode, import statique) et la redirection de la sortie d'un processus vers un fichier.
- **Occurrences existantes inscrites** dans `scripts/architecture/private-io-allowlist.json` (cliquet nominatif déjà en place : fichier, API, maximum, justification), chaque entrée nommant le lot de migration qui la retirera.
- **Statut de `PrivateLocalStorage`** tranché (primitive, ou entrées de liste) : il porte 6 des occurrences visées sans être une primitive du gate.
- **Auto-test** : un cas refusé et un cas accepté par règle nouvelle ; le cas `:167-170` est inversé.
- **Aucun fichier Java modifié** : ce changement pose le garde ; la migration des sites vient ensuite.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `stockage-prive-et-secrets` : deux exigences ajoutées (une écriture brute nouvelle hors des primitives est refusée par le gate ; la liste de tolérance ne fait que se resserrer et le gate échoue s'il ne peut pas la lire).

## Hors périmètre

- **La migration des sites existants** vers `PrivateLocalStorage` / `DurableAtomicFile` : lots séparés, à ouvrir après ce changement (stockage local, stockage PostgreSQL, providers SCIP, runtime local, application et registre). Chaque lot retire ses entrées de liste.
- `minos-intellij`, exclu du gate (`EXCLUDED_MODULES`) : ses écritures brutes (`MinosStrongProcessLauncher.java:88,300,316,344`) restent hors garde ; le plugin ne peut pas dépendre des primitives du noyau.
- Les sources de test et les ressources embarquées (scripts PowerShell), exclues par construction.
- Les règles de journal du même gate (capacité `journalisation`), inchangées.
- Le comportement des primitives elles-mêmes, dont les ACL Windows (AUD-SEC-06, changement `conserver-les-refus-conditionnels-windows`).

## Impact

- **Modules du reactor** : aucun code Java modifié. Fichiers touchés : `scripts/architecture/check-private-io.py`, `scripts/architecture/test_check_private_io.py`, `scripts/architecture/private-io-allowlist.json`, `.claude/rules/java-production.md`, `docs/developer/quality-gates.md`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/architecture/check-private-io.py`, `python scripts/architecture/test_check_private_io.py -v`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/check-single-execution.py`, `python scripts/docs/check-current-docs.py`, `python .claude/scripts/run_gates.py --fast`. Aucun scope JaCoCo concerné.
- **Plateformes** : le gate est un script Python qui rend la même sortie sous Windows et sous Linux (job `invariants`).
