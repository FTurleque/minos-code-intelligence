# Archgate

[Archgate](https://cli.archgate.dev) lit les ADR et exécute des règles TypeScript (`*.rules.ts`) qui leur sont rattachées. Dans MINOS, il lit **le registre lui-même**, `docs/architecture/adr/` ([ADR 0063](../architecture/adr/archive/0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)), et ses règles sont un **second exécutant** d'invariants déjà gardés par des gates Python.

## Ce qui fait foi

| Sujet | Source de vérité |
|---|---|
| Décisions d'architecture | [`docs/architecture/adr/`](../architecture/adr/README.md), registre unique, lu par Archgate |
| Contrôle exécuté par la CI | `scripts/**/check-*.py`, joués par le job `invariants` (`python .claude/scripts/run_gates.py --fast`) |
| Contrôle Archgate | `docs/architecture/adr/{ID}-*.rules.ts`, joué à la demande, hors CI |

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
    {ID}-slug.md               ADR, {ID} = préfixe du domaine + numéro (ARCH-0068) : en-tête YAML puis sections Archgate
    {ID}-slug.rules.ts         règle compagnon, quand rules: true
    README.md                  index ; sans en-tête, Archgate le signale comme non lu
    gabarit/template.md        gabarit ; un sous-dossier n'est pas lu par Archgate
    tsconfig.json              vérification de type des règles par l'éditeur et par tsc (ES2022, strict) ; Archgate ne s'en sert pas
```

Le format d'un ADR (en-tête, sections `Context`, `Decision`, `Do's and Don'ts`, `Consequences`, `Compliance and Enforcement`, `References`) est décrit dans le [gabarit](../architecture/adr/gabarit/template.md). L'en-tête doit être en ligne 1 ; un `id` en double est une erreur bloquante ; les sous-dossiers ne sont pas lus.

## Commandes

```bash
archgate --version                       # CLI installée (installation : cli.archgate.dev)
archgate adr list                        # ADR reconnus : id, title, domain, rules
archgate adr show ARCH-0068               # un ADR
archgate review-context                  # ADR concernés par les fichiers modifiés : sections Decision et Do's and Don'ts
archgate check --verbose                 # exécute les règles et montre les règles passantes
archgate check --staged                  # sur les fichiers indexés (crochet de pré-commit)
archgate check --adr ARCH-0068            # les règles d'un seul ADR
archgate check --output json             # sortie lisible par un outil
```

Code de sortie de `check` : 0 conforme, 1 violation, 2 erreur interne. Sans `--strict`, seules les violations de sévérité `error` échouent. `--strict` (ou `"strict": true` dans `config.json`) fait aussi échouer les avertissements, dont le `README.md` non lu : il n'est pas utilisable tant que l'index vit dans le dossier.

Un ADR dont l'en-tête porte `files:` n'est exécuté que si un fichier de cette portée a changé ; sans `files`, ses règles s'exécutent à chaque appel. `total: 0` dans la sortie JSON signifie « aucune règle exécutée », pas « conforme ».

## Correspondance ADR → gate → Archgate

Chaque règle lit la source de vérité du gate (script, table, POM, configuration) et ne recopie aucune liste. « Second exécutant » : le gate Python fait le même contrôle et reste celui de la CI. « Propre à Archgate » : aucun gate Python ne le fait.

| ADR | Règle Archgate (`docs/architecture/adr/{ID}-*.rules.ts`) | Ce qu'elle refuse | Gate Python |
|---|---|---|---|
| ARCH-0016 | `exit-codes-match-cli-reference` | codes de `CliExitCodes.java` absents ou en trop dans `docs/user/cli.md` | propre à Archgate |
| ARCH-0017 | `mcp-no-process-or-file-write`, `mcp-stdout-reserved-for-transport` | processus, écriture de fichier ou `System.out` hors transport dans `minos-mcp` | propre à Archgate |
| DATA-0019 | `mcp-exposes-no-author-email` | e-mail d'auteur exposé par `minos-mcp` | propre à Archgate |
| ARCH-0027 | `plugin-declares-no-minos-artifact`, `plugin-imports-only-its-own-package` | dépendance `com.minos:*` ou import `com.minos.` hors `com.minos.intellij` dans le plugin | grep de `intellij-plugin.yml` (partiel) |
| GEN-0043 | `permanent-gates-carry-no-milestone-number`, `milestone-workflows-are-manual-replays` | gate permanent nommé par un jalon ; workflow de jalon déclenché autrement qu'à la main | `check-milestone-artifact-references.py` (complémentaire) |
| ARCH-0057 | `no-jpms-module-descriptor` | `module-info.java` dans un module du reactor | propre à Archgate |
| ARCH-0058 | `allowed-dependency-tables-identical` | tables `ALLOWED_DEPENDENCIES` et `ModuleArchitectureTest.ALLOWED` différentes | `check-module-boundaries.py` A17 |
| SEC-0061 | `private-local-storage-keeps-its-entries`, `three-stream-openings-exist` | entrée de `PrivateLocalStorage` ajoutée à la liste de tolérance ; ouverture en flux retirée | `check-private-io.py` (complémentaire) |
| GEN-0073 | `adr-id-matches-file-name`, `adr-number-is-unique`, `adr-listed-in-index`, `adr-heading-matches-title`, `adr-has-archgate-sections` (avertissement), `archgate-adrs-holds-only-gitkeep` | nom ou id hors convention, préfixe étranger au domaine, numéro réutilisé, ADR absent de l'index, titre différent du title ; fichier dans `.archgate/adrs/` | propre à Archgate |
| GEN-0067 | `enforcer-matches-java-release`, `workflow-java-steps-match-release`, `temurin-images-match-release`, `documents-state-java-release`, `no-server-framework-in-poms` | version Java divergente du POM ; framework serveur dans un POM | `check-toolchain-baseline.py` (second exécutant, sauf la dernière) |
| ARCH-0068 | `governed-modules-match-reactor` (A7), `one-package-one-module` (A3), `package-roots-owned-by-module` (A14) | module non gouverné ; package éclaté ; nouvelle racine de packages | `check-module-boundaries.py` |
| SEC-0070 | `decision-limitation-in-code`, `product-facts-requires-limitation` | disparition de la limitation publique `WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041` | `product-facts.py --check` |

Non portés : A2 (règles hexagonales POM et sources), A8 (cycles de packages), A9 (document arc42), A10 à A16 hors A12/A14, E/S privées au grain de l'appel, et les autres gates `scripts/**/check-*.py`.

Les invariants non portés demandent d'analyser du XML Maven à portées multiples, des sources Java et des graphes de packages (`ctx.ast` ne lit pas Java). Les réécrire en TypeScript créerait deux implémentations à garder synchrones ; ils n'entrent dans Archgate que si une raison écrite le justifie.

## Ajouter une règle

1. Vérifier que l'invariant est déjà gardé par un gate Python et qu'un test de ce gate existe.
2. Créer `docs/architecture/adr/{ID}-slug.rules.ts` à côté de l'ADR canonique (même nom de base, identifiants de règle en kebab-case anglais ; `files` dans l'en-tête si la règle ne regarde qu'une partie du dépôt) et passer son en-tête à `rules: true`.
3. La règle lit la source de vérité du gate ; elle ne recopie aucune liste.
4. Vérifier les types : `npx --package typescript tsc -p docs/architecture/adr/tsconfig.json` après un `archgate check` (qui génère `docs/architecture/rules.d.ts`) ; aucune erreur attendue.
5. Prouver la règle au rouge : muter la source (par exemple retirer un module de `MODULES`), constater l'échec de `archgate check`, rétablir.
6. Compléter la section `Compliance and Enforcement` de l'ADR et la table ci-dessus, puis rejouer `python .claude/scripts/run_gates.py --fast` et `python scripts/docs/check-current-docs.py`.

## Limites

- Archgate n'est pas dans la CI : un invariant n'est exigé que par son gate Python.
- `archgate adr create` n'est pas utilisé (il numérote par domaine et écrit un gabarit anglais) ; un ADR s'écrit à partir du gabarit (`/minos:adr`).
- Les résumés de `review-context` ne reprennent que les sections `Decision` et `Do's and Don'ts`.
- `adr import` et `adr sync` servent aux ADR importés d'un autre dépôt, pas à ce registre.
- Version éprouvée : Archgate 0.60.0.
