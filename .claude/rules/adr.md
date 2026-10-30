---
paths:
  - "docs/architecture/adr/**"
  - "docs/architecture/arc42/09-decisions.md"
  - "openspec/changes/**/design.md"
---

# Règles — Quand écrire un ADR (et quand ne pas en écrire)

Référence humaine : section « Quand écrire un ADR » de `docs/architecture/adr/README.md` et
[GEN-0073](../../docs/architecture/adr/GEN-0073-tenir-le-registre-d-adr-au-format-archgate.md). Format, gabarit
et index : `.claude/rules/documentation.md` (section ADR) et la commande `/minos:adr`.

## Le filtre : trois questions, trois « oui »

Un ADR mémorise une **décision d'architecture durable, encore en vigueur et coûteuse à inverser**, dont les
raisons se perdraient sans lui. Avant d'en créer un, répondre aux trois questions. Un seul « non » suffit : ce
n'est pas un ADR.

1. **Décision** : fixe-t-il un choix entre plusieurs options réelles ? Un constat, une mesure, un plan ou un état
   d'avancement n'est pas une décision.
2. **Portée et durée** : contraint-il durablement plusieurs modules, une surface publique (CLI, API Java, MCP,
   NEXUS, plugin), un format persisté, un modèle de confiance ou de sécurité, la toolchain ou la livraison ?
3. **Coût du retour arrière** : l'inverser casserait-il des données, des contrats, des garanties de sécurité ou
   une part importante du code ?

## Ce n'est pas un ADR

| Contenu | Où l'écrire |
|---|---|
| état d'un jalon, preuve de qualification, SHA, mesure datée | `docs/project/history/` ou le suivi d'audit |
| plan, backlog, ordre de travail | `docs/project/roadmap/` ou un changement OpenSpec |
| exigence et scénarios d'un comportement | `openspec/specs/` par un changement OpenSpec |
| étude, comparaison, option sans décision | `docs/project/research/` |
| description d'un mécanisme, mode d'emploi | `docs/developer/` ou `docs/user/` |
| convention locale, nommage, style | `.claude/rules/` ou une règle de linter |
| correctif, fonctionnalité, classe | le commit et la PR |

## Avant d'écrire : modifier plutôt que créer

- **Un ADR existant couvre déjà le sujet** : le citer, ou clarifier son texte si son sens ne change pas. Jamais
  d'ADR jumeau.
- **La décision change en entier** : le nouvel ADR passe `Accepted`, l'ancien passe `Superseded` et part en
  archive.
- **La décision change en partie** : pas d'« amendement » qui cohabite avec l'original. Écrire un **ADR
  consolidé** qui réunit toute la décision en vigueur et remplace les deux. À tout moment, une décision se lit en
  **un seul** ADR actif.

## Cycle de vie

- `Proposed` est un état transitoire. Une proposition qui n'est portée par aucun changement OpenSpec en cours est
  archivée `Rejected`.
- Une décision exécutée une fois pour toutes (migration, renommage, déplacement) et qui n'impose plus de règle au
  code est archivée. Si elle impose encore une règle, l'ADR ne garde que cette règle.
- Un ADR retiré n'est **jamais supprimé** : il part dans `docs/architecture/adr/archive/` avec un bandeau qui dit
  ce qui le remplace. Son numéro reste unique, jamais réutilisé ni renuméroté. Archgate ne lit pas l'archive.

## Forme d'un ADR actif

- Rédigé au présent : il dit ce qui est décidé aujourd'hui, sans récit du chantier ni « auparavant »,
  « désormais » ou « mis à jour pour… ».
- Il porte des `Do's and Don'ts` vérifiables. Une contrainte qui se lit dans des fichiers a sa **règle Archgate
  compagnon** (`{ID}-{slug}.rules.ts`), qui lit les mêmes sources que le gate Python correspondant ; sinon,
  `Compliance and Enforcement` dit comment elle est tenue (test, revue, gate de CI).
- Un ADR sans contrainte vérifiable ni conséquence sur le code est suspect : c'est probablement une étude ou une
  documentation.

## Contrôles

Tenus par `GEN-0073-tenir-le-registre-d-adr-au-format-archgate.rules.ts` (`archgate check`) :

- fichier nommé `{PRÉFIXE}-{NNNN}-{slug}.md`, `id` égal au début du nom, préfixe cohérent avec `domain` ;
- numéro unique dans le registre **et** dans l'archive ;
- chaque ADR actif figure dans l'index `docs/architecture/adr/README.md` ;
- titre `#` identique au `title` ;
- sections `Decision` et `Do's and Don'ts` présentes (avertissement).

À vérifier en revue, faute de contrôle automatique : les quatre autres sections obligatoires (`Context`,
`Consequences`, `Compliance and Enforcement`, `References`), aucun ADR actif au statut `Superseded` ou `Rejected`,
aucun ADR archivé présenté comme actif dans l'index.
