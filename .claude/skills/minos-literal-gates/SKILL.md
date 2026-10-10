---
name: minos-literal-gates
description: Renommer, déplacer ou restructurer du code, des scripts ou des workflows MINOS sans casser les gates qui affirment des chaînes littérales — retrouver les gates qui citent un fichier ou un nom, les rejouer, et corriger leur attente quand le changement est voulu. À utiliser avant et après tout renommage de méthode ou de classe, déplacement de fichier, extraction de fonction, ajout de gate ou d'auto-test, ou quand un check-*.py échoue après un refactor.
---

# Gates littéraux

Les scripts `scripts/remediation/check-*.py`, `scripts/quality/check-*.py`, `scripts/m*/check-*.py` lisent les sources et exigent des **chaînes exactes** (noms de méthodes, de classes, expressions d'appel, chemins). Un refactor correct peut les faire échouer. Ils sont dans le job `invariants` : leur échec bloque la PR.

## Avant de modifier

1. Lister les gates qui citent le fichier ou le nom :
   ```bash
   grep -rn "NomOuChemin" scripts/ --include=*.py --include=*.ps1 -l
   ```
   (le hook `post_edit.py` le fait automatiquement après une modification de fichier).
2. Lire l'assertion : `require(...)` / `forbid(...)` / `contains`. Comprendre **ce qu'elle protège** (un invariant de sécurité, un câblage) avant de la toucher.

## Après la modification

1. Rejouer **ces** gates, puis `python .claude/scripts/run_gates.py --fast`.
2. Si l'échec est une chaîne devenue fausse parce que le changement est voulu : mettre à jour l'attente **dans le même commit**, en gardant l'invariant (ancrer sur la structure plutôt que sur une phrase, ADR 0043), et prouver que le gate échoue encore sur une version fautive (copie temporaire).
3. Si le gate protège un invariant que le changement viole réellement : corriger le code, pas le gate.

## Familles à connaître

| Gate | Lit | Piège connu |
|---|---|---|
| `check-post-mne.py`, `check-minos-01.py`, `check-mnd.py`, `check-mne.py` | classes de `minos-runtime-local`, `minos-integration-git`, `minos-provider-scip`, scripts `.ps1` d'installation | noms de méthodes ; `Read-BoundedUtf8`, `'minos.postgres.managed'] = …` |
| `check-module-boundaries.py` (A2, A3, A7, A8, A9) | POM, sources, `arc42/05-vue-blocs.md` | liste de dépendances d'`arc42/05` comparée aux POM ; table `KNOWN_PACKAGE_CYCLES` en cliquet |
| `check-private-io.py` | sources de production | `Files.write`, `createDirectories`, `newInputStream`, canaux : primitives seulement, liste nominative en cliquet |
| `check-single-execution.py`, `check-audit-remediation-v2.py` | `pr-ci.yml` | un `--self-test` ajouté à `invariants` les casse s'ils ne sont pas adaptés |
| `check-ci-wiring.py` | `pr-ci.yml`, `required-checks.json` | tout `test_*.py` et tout `--self-test` doit être exécuté par `invariants` |
| `check-jacoco.py` | rapports JaCoCo, préfixes de classes par scope | une classe déplacée sort de son scope : mettre à jour le préfixe |
| `check-current-docs.py`, `product-facts.py` | `STATUS.md`, `ROADMAP.md`, docs courantes | faits chiffrés écrits une seule fois |
| `DuplicationGuardTest`, `JsonEscapeGuardTest` (tests Java) | chemins de classes | citent des chemins : à mettre à jour après un déplacement |

## Autres dépendances de nom
Baseline SpotBugs indexé par `instanceHash` (dépend du nom de classe) : le rafraîchir après un déplacement. Goldens de caractérisation : ne doivent pas bouger lors d'un déplacement.

## Ajouter un gate
Auto-test obligatoire (refus prouvé sur copie), racine paramétrable, câblage dans `invariants`, section dans `docs/developer/quality-gates.md`. Voir `.claude/rules/gates-python.md`.
