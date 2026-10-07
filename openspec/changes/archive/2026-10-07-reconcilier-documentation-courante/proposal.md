# Proposal

## Why

L'audit 2026-10 (annexe G, `docs/audit/annexes/G-adr-documentation.md`) relève des documents
courants qui contredisent le code, le POM ou la CI : version du Maven Wrapper annoncée 3.9.16
alors que `.mvn/wrapper/maven-wrapper.properties` pointe sur Maven 3.10.0, `minos-app` donné pour
composition root alors que l'ADR-0042 la place dans `minos-bootstrap`, ligne de développement
« 1.1.0-SNAPSHOT » alors que le POM est en 1.3.0-SNAPSHOT, index d'ADR arrêté à 0037, liens
morts. Les gates documentaires actuels passent malgré ces écarts (vérifié au HEAD : ils
n'affirment pas ces faits). Ce changement les corrige en prenant le code, le POM et le workflow
comme critère de vérité, jamais l'inverse.

Le comportement du logiciel ne change pas : c'est un changement de documentation seule, sans
delta de spécification (`skip_specs: true` dans `.openspec.yaml`) et sans aucune exigence.

## What Changes

- Correction de faits périmés dans `docs/STATUS.md`, `docs/ROADMAP.md`, `README.md`,
  `docs/user/docker-runtime.md`, `docs/architecture/**` (README, SYNTHESE, arc42 02, 04, 05, 09,
  11), `docs/adr/README.md` (notes en prose uniquement) et dans les liens morts de la
  documentation courante.
- Regroupement en neuf tâches par fichier ou par thème ; chaque affirmation corrigée est relue
  contre sa source (fichier ou commande) et les trois gates documentaires sont rejoués.
- Les constats qui exigent de modifier un ADR (statut, bandeau, amendement) ou de rédiger un
  nouvel ADR ne sont **pas** exécutés : ils sont posés comme questions ouvertes dans
  `design.md`, car seul l'utilisateur change un statut d'ADR.

### Hors périmètre

- Tout fichier `docs/adr/00NN-*.md` (statuts, bandeaux, amendements, corrections de modules
  cités) : questions ouvertes.
- Rédaction de nouveaux ADR (G-08 PostgreSQL/pgvector, G-07 endpoint `minos-ollama`) : à
  proposer avec le statut Proposed, non rédigés ici.
- ~~Création des sept diagrammes absents de `docs/architecture/diagrams/`~~ : **ajoutée au périmètre le 2026-10-07 sur décision de l'utilisateur (Q7)**, tâche 8.2.
- Annexes d'audit, `docs/audit/CAPACITES.md` et autres fichiers d'audit non versionnés.
- `openspec/config.yaml` (qui reprend lui aussi « minos-app est le composition root ») :
  appartient à l'utilisateur, signalé en question ouverte.
- Code, tests, scripts, workflows, POM ; `docs/history/**`.
- G-21 (tag local `v1.0.1` obsolète) : état de la machine locale, pas un défaut du dépôt.

## Capabilities

### New Capabilities

(aucune : documentation seule, `skip_specs: true`)

### Modified Capabilities

(aucune)

## Constats d'audit couverts

Chaque constat G-NN (`MINOS-AUD-GNN`) a été relu au HEAD (`develop` @ bc1d3421) pour ce
changement. Les gates `check-current-docs.py`, `product-facts.py --check` et
`check-milestone-artifact-references.py` passent au HEAD avant toute modification.

| Constat | Qualification | Prio | Vérifié au HEAD | Traitement |
|---|---|---|---|---|
| MINOS-AUD-G01 | incohérence interne (dates d'en-tête) | P2 | oui : `STATUS.md:3` et `ROADMAP.md:3` datent d'août alors que des sections d'octobre existent ; `docs/architecture/README.md:5` | tâches 2, 3, 5 |
| MINOS-AUD-G02 | statut incohérent ADR-0036 | P2 | oui : index, arc42/09 et arc42/11 « Proposed », fichier ADR « Accepted » depuis le commit `cd87f402` | question ouverte Q1 |
| MINOS-AUD-G03 | statut non propagé ADR-0021 | P2 | oui (en-tête sans renvoi à 0037 ; `arc42/11:4` cite 0021 pour « Docker autonomy ») | bandeau : Q2 ; citation arc42/11 : tâche 7 |
| MINOS-AUD-G04 | statut potentiellement obsolète ADR-0037 | P3 | divergence confirmée ; parité non établissable depuis le code | question ouverte Q3 |
| MINOS-AUD-G05 | ADR-0022 amendé sans bandeau | P3 | oui (13 projets cités, 14 `<module>` dans le POM) | question ouverte Q2 |
| MINOS-AUD-G06 | ADR-0033/0038 : amendement par 0041 non reflété | P3 | oui | question ouverte Q2 |
| MINOS-AUD-G07 | ADR-0031 §2 « loopback only » contredit par `minos-ollama` | P2 | oui (`OllamaEmbeddingProvider.MANAGED_DOCKER_ENDPOINT`) | question ouverte Q4 (ADR) |
| MINOS-AUD-G08 | décision structurante sans ADR (PostgreSQL/pgvector) | P2 | oui (aucun ADR dédié) | question ouverte Q4, nouvel ADR à proposer (Proposed) |
| MINOS-AUD-G09 | index arc42/09 arrêté à 0037, dates divergentes, « 37 ADR » | P2 | oui (48 lignes ; `SYNTHESE.md:21,38`) | tâches 5 et 6 |
| MINOS-AUD-G10 | note « PR #333 non fusionnée » périmée | P3 | oui (fusionnée, `bc1d3421`) | tâche 7 |
| MINOS-AUD-G11 | wrapper Maven 3.10.0 vs « 3.9.16 » annoncé | P2 | oui, et incomplet : `docs/user/docker-runtime.md:79` affirme que le SHA-256 Maven de l'image est « identique au checksum du Maven Wrapper » (faux : 3.9.16 vs 3.10.0) | faits : tâche 1 ; politique/ADR : Q5 |
| MINOS-AUD-G12 | ROADMAP « 1.1.0-SNAPSHOT » | P2 | oui (`pom.xml:42` : 1.3.0-SNAPSHOT) | tâche 3 |
| MINOS-AUD-G13 | ROADMAP : ADR-0039 « conception proposée » | P2 | oui | tâche 3 |
| MINOS-AUD-G14 | ROADMAP : workflow Post-228 supprimé décrit comme actif | P2 | oui (aucun `post-228-hardening.yml`) | tâche 3 |
| MINOS-AUD-G15 | STATUS « deux jobs » alors que `pr-ci.yml` en compte trois | P3 | oui | tâche 2 |
| MINOS-AUD-G16 | README racine : état 1.0.1 et composition root | P2 | oui ; ADR-0042 lu en entier : racine dans `minos-bootstrap`, `minos-app` = assemblage distribué | tâche 4 |
| MINOS-AUD-G17 | arc42/SYNTHESE : 12 modules, composition root | P2 | oui (arc42 02 CT-3, 04, 05 sans `minos-bootstrap`, SYNTHESE 11 et 19) | tâche 5 ; module cité dans l'ADR-0039 : Q6 |
| MINOS-AUD-G18 | liens morts | P3 | oui, et incomplet : en plus de la fiche, 6 liens de `docs/audit/CI-HYGIENE-SUIVI.md` vers des workflows supprimés | tâche 8 ; diagrammes manquants : Q7 |
| MINOS-AUD-G19 | « snapshots v1/v2 » sans V3 | P3 | oui (`KnowledgeSnapshotCodecs`, V3 écrit par défaut ; plafond commun) | tâches 2 et 3 |
| MINOS-AUD-G20 | index ADR : titres et vocabulaire | P3 | oui (29 titres sur 57 diffèrent du H1) | question ouverte Q8 |
| MINOS-AUD-G21 | tag local `v1.0.1` | P3 | non retenu | environnement local, aucun fichier du dépôt concerné |
| MINOS-AUD-G22 | `CAPACITES.md` « 50 ADR » | P3 | non retenu | fichier non versionné (brouillon d'audit), hors périmètre ; 58 fichiers `.md` dans `docs/adr/` dont l'index |

## Impact

- Modules du reactor Maven touchés : **aucun**. Surfaces publiques (CLI, API Java, MCP,
  IntelliJ, NEXUS) : **aucun changement de comportement**.
- Fichiers de documentation seulement ; aucun fichier ADR modifié ; `docs/generated/product-facts.md`
  doit rester inchangé (généré, `product-facts.py --check`).
- ADR : **aucun nouvel ADR ni amendement par ce changement**. ADR à proposer, non rédigés :
  un ADR pour le backend PostgreSQL/pgvector optionnel (G-08, lié aux ADR-0025 et 0031) et un
  amendement ou ADR pour l'endpoint Docker managé d'embeddings (G-07, ADR-0031) ; statut
  Proposed, décision de l'utilisateur.
- Gates à rejouer (chaînes littérales) : `scripts/docs/check-current-docs.py`,
  `scripts/docs/product-facts.py --check`, `scripts/quality/check-milestone-artifact-references.py`.
  Aucun scope JaCoCo concerné (aucun code déplacé).
