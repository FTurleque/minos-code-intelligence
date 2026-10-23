# Archgate

[Archgate](https://cli.archgate.dev) lit les ADR et exécute des règles TypeScript (`*.rules.ts`) qui leur sont rattachées. Dans MINOS, il lit **le registre lui-même**, `docs/architecture/adr/` ([ADR 0063](../architecture/adr/0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)), et ses règles sont un **second exécutant** d'invariants déjà gardés par des gates Python.

## Ce qui fait foi

| Sujet | Source de vérité |
|---|---|
| Décisions d'architecture | [`docs/architecture/adr/`](../architecture/adr/README.md), registre unique, lu par Archgate |
| Contrôle exécuté par la CI | `scripts/**/check-*.py`, joués par le job `invariants` (`python .claude/scripts/run_gates.py --fast`) |
| Contrôle Archgate | `docs/architecture/adr/NNNN-*.rules.ts`, joué à la demande, hors CI |

Quand une règle Archgate et un gate Python divergent, le gate Python a raison et la règle Archgate se corrige.

## Disposition

```text
.archgate/
  config.json                  paths.adrs = docs/architecture/adr, domaine personnalisé security (SEC), branche de base
  adrs/.gitkeep                exigé par la CLI pour reconnaître le projet ; rien d'autre n'y vit
  lint/                        réservé par Archgate aux règles de linter (vide)
  rules.d.ts                   généré, ignoré par Git
docs/architecture/
  rules.d.ts                   généré dans le parent du dossier d'ADR, ignoré par Git
  adr/
    NNNN-slug.md               ADR : en-tête YAML (id: ADR-NNNN, title, domain, rules) puis sections Archgate
    NNNN-slug.rules.ts         règle compagnon, quand rules: true (aujourd'hui 0068)
    README.md                  index ; sans en-tête, Archgate le signale comme non lu
    gabarit/template.md        gabarit ; un sous-dossier n'est pas lu par Archgate
```

Le format d'un ADR (en-tête, sections `Context`, `Decision`, `Do's and Don'ts`, `Consequences`, `Compliance and Enforcement`, `References`) est décrit dans le [gabarit](../architecture/adr/gabarit/template.md). L'en-tête doit être en ligne 1 ; un `id` en double est une erreur bloquante ; les sous-dossiers ne sont pas lus.

## Commandes

```bash
archgate --version                       # CLI installée (installation : cli.archgate.dev)
archgate adr list                        # ADR reconnus : id, title, domain, rules
archgate adr show ADR-0068               # un ADR
archgate review-context                  # ADR concernés par les fichiers modifiés : sections Decision et Do's and Don'ts
archgate check --verbose                 # exécute les règles et montre les règles passantes
archgate check --staged                  # sur les fichiers indexés (crochet de pré-commit)
archgate check --adr ADR-0068            # les règles d'un seul ADR
archgate check --output json             # sortie lisible par un outil
```

Code de sortie de `check` : 0 conforme, 1 violation, 2 erreur interne. Sans `--strict`, seules les violations de sévérité `error` échouent. `--strict` (ou `"strict": true` dans `config.json`) fait aussi échouer les avertissements, dont le `README.md` non lu : il n'est pas utilisable tant que l'index vit dans le dossier.

Un ADR dont l'en-tête porte `files:` n'est exécuté que si un fichier de cette portée a changé ; sans `files`, ses règles s'exécutent à chaque appel. `total: 0` dans la sortie JSON signifie « aucune règle exécutée », pas « conforme ».

## Correspondance ADR → gate → Archgate

| Invariant | ADR | Gate Python | Archgate |
|---|---|---|---|
| A7 — `MODULES` du gate = `<modules>` du `pom.xml` racine | 0068 | `check-module-boundaries.py` (`check_reactor_modules`) | **porté** : `0068-modules-dependances-et-propriete-des-packages.rules.ts` / `governed-modules-match-reactor` |
| A2 — règles hexagonales POM et sources, politique de dépendances | 0068, 0042 | `check-module-boundaries.py` | non porté |
| A3 — un package, un module | 0068 | `check-module-boundaries.py` | non porté |
| A8 — aucun cycle de packages | 0068, 0057 | `check-module-boundaries.py` | non porté |
| A9 — document d'architecture arc42 aligné sur les POM | 0068 | `check-module-boundaries.py` | non porté |
| A10 — exports de `minos-runtime-local` | 0042 | `check-module-boundaries.py` | non porté |
| A11 à A14 — dette d'architecture en cliquet (types du moteur par surface, lanceurs de processus, types entre adaptateurs, racines de packages) et arêtes d'exécution par service | 0068, 0058 | `check-module-boundaries.py` | non porté |
| E/S privées confinées | 0061 | `check-private-io.py` | non porté |
| Autres gates `scripts/**/check-*.py` | divers | job `invariants` | non portés |

Les invariants non portés demandent d'analyser du XML Maven à portées multiples, des sources Java et des graphes de packages (`ctx.ast` ne lit pas Java). Les réécrire en TypeScript créerait deux implémentations à garder synchrones ; ils n'entrent dans Archgate que si une raison écrite le justifie.

## Ajouter une règle

1. Vérifier que l'invariant est déjà gardé par un gate Python et qu'un test de ce gate existe.
2. Créer `docs/architecture/adr/NNNN-slug.rules.ts` à côté de l'ADR canonique (même nom de base) et passer son en-tête à `rules: true`.
3. La règle lit la source de vérité du gate ; elle ne recopie aucune liste.
4. Prouver la règle au rouge : muter la source (par exemple retirer un module de `MODULES`), constater l'échec de `archgate check`, rétablir.
5. Compléter la section `Compliance and Enforcement` de l'ADR et la table ci-dessus, puis rejouer `python .claude/scripts/run_gates.py --fast` et `python scripts/docs/check-current-docs.py`.

## Limites

- Archgate n'est pas dans la CI : un invariant n'est exigé que par son gate Python.
- `archgate adr create` n'est pas utilisé (il numérote par domaine et écrit un gabarit anglais) ; un ADR s'écrit à partir du gabarit (`/minos:adr`).
- Les résumés de `review-context` ne reprennent que les sections `Decision` et `Do's and Don'ts`.
- `adr import` et `adr sync` servent aux ADR importés d'un autre dépôt, pas à ce registre.
- Version éprouvée : Archgate 0.60.0.
