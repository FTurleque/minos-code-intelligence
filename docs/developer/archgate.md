# Archgate

[Archgate](https://cli.archgate.dev) exécute des règles TypeScript (`*.rules.ts`) rattachées à des ADR. Dans MINOS il est un **second exécutant** d'invariants déjà gardés par des gates Python ; il n'est ni un registre de décisions ni une source de vérité.

## Ce qui fait foi

| Sujet | Source de vérité |
|---|---|
| Décisions d'architecture | [`docs/architecture/adr/`](../architecture/adr/README.md), registre unique |
| Contrôle exécuté par la CI | `scripts/**/check-*.py`, joués par le job `invariants` (`python .claude/scripts/run_gates.py --fast`) |
| Contrôle Archgate | `.archgate/adrs/*.rules.ts`, joué à la demande, hors CI |

Quand une règle Archgate et un gate Python divergent, le gate Python a raison et la règle Archgate se corrige.

## Disposition

```text
.archgate/
  config.json                  domaines personnalisés et branche de base (origin/develop)
  adrs/
    ARCH-044-….md              pont : frontmatter + lien vers l'ADR canonique
    ARCH-044-….rules.ts        règle(s) du pont
  lint/                        réservé par Archgate aux règles de linter (vide)
  rules.d.ts                   généré par archgate init, ignoré par Git
```

Archgate lit ses ADR dans `.archgate/adrs/` (emplacement non configurable) et exige un frontmatter YAML (`id`, `title`, `domain`, `rules`). Les ADR de `docs/architecture/adr/` n'en ont pas : `archgate check` les signale `unparsedAdrs` s'ils sont copiés là. Ils ne sont donc ni copiés ni modifiés.

Un **pont** est un fichier minimal de `.archgate/adrs/` qui :

- déclare en tête qu'il n'est pas une décision ;
- renvoie à l'ADR canonique et au gate Python de référence ;
- porte une ou plusieurs règles `.rules.ts` qui **lisent** les mêmes sources que le gate (jamais une liste recopiée) ;
- reçoit l'identifiant `ARCH-<numéro de l'ADR canonique sur trois chiffres>`.

Un pont n'a pas de statut, de date ni d'index : il n'apparaît ni dans `docs/architecture/adr/README.md` ni dans `docs/architecture/arc42/09-decisions.md`.

## Commandes

```bash
archgate --version                 # CLI installée (installation : cli.archgate.dev)
archgate adr list                  # ponts déclarés
archgate check --verbose           # exécute les règles et montre les règles passantes
archgate check --adr ARCH-044      # un seul pont
archgate check --output json       # sortie lisible par un outil
```

Code de sortie : 0 conforme, 1 violation, 2 erreur interne. Sans `--strict`, seules les violations de sévérité `error` échouent.

Un pont dont le frontmatter porte `files:` n'est exécuté que si un fichier de cette portée a changé (et `archgate check` sans argument ne regarde que les fichiers modifiés pour ces ponts). Les ponts de MINOS n'en déclarent pas, pour que `archgate check` rejoue l'invariant à chaque appel ; Archgate le signale alors par un avertissement de performance en mode `--verbose`, sans conséquence.

## Correspondance ADR → gate → Archgate

| Invariant | ADR | Gate Python | Archgate |
|---|---|---|---|
| A7 — `MODULES` du gate = `<modules>` du `pom.xml` racine | 0044 | `check-module-boundaries.py` (`check_reactor_modules`) | **porté** : `ARCH-044` / `governed-modules-match-reactor` |
| A2 — règles hexagonales POM et sources, politique de dépendances | 0022, 0042 | `check-module-boundaries.py` | non porté |
| A3 — un package, un module | 0044 | `check-module-boundaries.py` | non porté |
| A8 — aucun cycle de packages | 0044 | `check-module-boundaries.py` | non porté |
| A9 — document d'architecture arc42 aligné sur les POM | 0022 | `check-module-boundaries.py` | non porté |
| A10 — exports de `minos-runtime-local` | 0042 | `check-module-boundaries.py` | non porté |
| A11 à A14 — dette d'architecture en cliquet (types du moteur par surface, lanceurs de processus, types entre adaptateurs, racines de packages) et arêtes d'exécution par service | 0022, 0044, 0055, 0058, 0062 | `check-module-boundaries.py` | non porté |
| E/S privées confinées | — | `check-private-io.py` | non porté |
| Autres gates `scripts/**/check-*.py` | divers | job `invariants` | non portés |

Les invariants non portés demandent d'analyser du XML Maven à portées multiples, des sources Java et des graphes de packages. Les réécrire en TypeScript créerait deux implémentations à garder synchrones ; ils n'entrent dans Archgate que si une raison écrite le justifie.

## Ajouter un pont

1. Vérifier que l'invariant est déjà gardé par un gate Python et qu'un test de ce gate existe.
2. Créer `.archgate/adrs/ARCH-<nnn>-<slug>.md` (frontmatter, renvoi à l'ADR canonique et au gate, sections Context, Decision, Do's and Don'ts, Consequences) et son `.rules.ts`.
3. La règle lit la source de vérité du gate ; elle ne recopie aucune liste.
4. Prouver la règle au rouge : muter la source (par exemple retirer un module de `MODULES`), constater l'échec de `archgate check`, rétablir.
5. Compléter la table ci-dessus et rejouer `python .claude/scripts/run_gates.py --fast` et `python scripts/docs/check-current-docs.py`.

## Limites

- Archgate n'est pas dans la CI : un invariant n'est exigé que par son gate Python.
- Aucune décision n'est rédigée ou modifiée par Archgate. Une décision structurante suit le flux ADR habituel ([`docs/architecture/adr/README.md`](../architecture/adr/README.md)).
- `rules.d.ts` est régénéré par `archgate init` ; ne pas le versionner.
- Version éprouvée : Archgate 0.59.0.
