# Design

## Context

Suite de la PR #416 (rangement partiel de `minos-runtime-local`) et de sa relecture de sécurité. HEAD analysé : `f3a03c19` (`develop`).

## État vérifié au HEAD

- Types de `com.minos.runtime.local.{process,sandbox,workspace}` nommés hors du module (`git grep` des imports et des noms qualifiés, production et tests) : `process.BoundedProcessOutput`, `process.CommandLocator`, `process.IndexerProcessPlan`, `process.IndexerProcessPlanFactory`, `process.InstallationProcessEnvironment`, `process.ProcessIndexerExecutor`, `sandbox.WorkerResourceContainment`, `sandbox.WorkerSandboxBackend`, `sandbox.WorkerSandboxQualification`, `sandbox.WorkerSandboxSelection`, `workspace.FileResumableRunMarkers` — 11 types, depuis `minos-bootstrap`, `minos-provider-scip` et les tests de `minos-cli`. Tous étaient publics avant le rangement.
- Aucun fichier hors du module ne contient `ProcessPlanTransformer` ni `executeSandboxed`.
- `check-module-boundaries.py` garde déjà les dépendances entre modules (A2), les classes d'adaptateur nommées par les sources (A2, `check_source_boundaries`), la propriété des packages (A3) et les cycles (A8) ; aucune règle ne regarde quels **types** d'un module un autre module nomme.
- La règle A2 des sources ne lit que `src/main` ; les tests de `minos-cli` importent des types de `minos-runtime-local` (portée `test`).

## Goals / Non-Goals

**Goals** : rendre impossible, sans édition visible d'une liste, l'usage hors du module des primitives ouvertes par le rangement.

**Non-Goals** : une encapsulation par le compilateur ; une restriction des membres d'un type exporté autre que les deux membres réservés.

## Decisions

### D1. Liste fermée d'export, par type

`RUNTIME_LOCAL_EXPORTED_TYPES` contient les 11 types ci-dessus, écrits `package.Type`. Un type imbriqué suit son type englobant (`WorkerResourceContainment.Disposition`). La règle s'applique à tous les modules du reactor sauf `minos-runtime-local`, sources de production **et de test** : un test qui appelle une primitive de confinement d'un autre module est le même contournement.

Alternative écartée : une liste de refus (les types sensibles). Elle laisserait passer tout type ajouté plus tard.

### D2. Ce qui est reconnu

Import simple, import statique (le type est celui qui porte le membre), import par joker d'un package gardé (toujours refusé), nom qualifié dans le code. Commentaires et littéraux ignorés, comme pour la règle A2. Les noms simples sans import ne peuvent pas désigner un type gardé hors de son package (A3 interdit de partager un package).

### D3. Membres réservés

`ProcessIndexerExecutor` est exporté, mais son interface imbriquée `ProcessPlanTransformer` et la méthode `executeSandboxed` décident du quota d'écriture et de la confiance accordée au lanceur. Un fichier hors du module qui nomme `minos-runtime-local` et contient l'un de ces deux identifiants est refusé. Un fichier qui n'utilise pas le module n'est pas concerné.

### D4. Cliquet

Chaque entrée doit correspondre à un fichier du module, et être nommée par au moins un autre module ; sinon le gate échoue et demande de la retirer (règle des listes de tolérance du dépôt).

### D5. Direction des dépendances (ADR 0022, ADR 0042)

Aucune dépendance ne change. La règle resserre ce que les dépendances existantes permettent.

## Risks / Trade-offs

- Un contournement par réflexion ou par un type intermédiaire public du module reste possible : le gate garde les noms, pas les appels. Les deux membres réservés couvrent le chemin relevé par la relecture.
- Un développeur qui a besoin d'un type nouveau doit éditer la liste : c'est l'effet recherché, la revue voit l'ouverture.

## Windows et Linux

Aucune différence : script Python, bibliothèque standard, chemins normalisés en `/`.

## Qualification de la capacité

`frontieres-de-modules` : **qualifiée** quand le gate et son auto-test passent dans le job `invariants`.

## Migration

Aucune. Retour arrière : revert du commit.
