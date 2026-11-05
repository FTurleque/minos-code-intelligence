# Chantier « spécifications détaillées et traçables » (novembre 2026)

Suivi de la mise en place du schéma OpenSpec `detailed-uml` et de l'enrichissement des spécifications
existantes. Mode d'emploi du schéma : [guide développeur](../../../developer/openspec-detailed-uml.md). Dossiers
produits : [dossiers de capacité](../../../architecture/diagrams/capacites/README.md).

## Livré le 2026-11-03 (non commité à cette date)

- Schéma projet `openspec/schemas/detailed-uml/` (fork de `spec-driven` d'OpenSpec 1.14.3) et ses quatre modèles ;
  `openspec/config.yaml` le sélectionne.
- Contrôle `scripts/docs/check-capability-dossiers.py` et son auto-test, branchés dans le job `invariants`.
- Vingt dossiers de capacité, un par capacité (lots 1 à 6) et leur lien `## Documentation liée` depuis la spec de référence.
- Changement proposé `aligner-quatre-scenarios-sur-leurs-preuves` (non implémenté, non archivé).

## Inventaire des capacités

Mesure : `python scripts/docs/check-capability-dossiers.py --report` au commit `64d131e4`. « Exigences couvertes »
compte les exigences au statut `couverte` dans le dossier ; « à prouver » celles au statut `partielle` ou
`manquante`.

| Capacité | Exigences | Scénarios | Dossier | Couvertes / à prouver | Écarts relevés | Lot |
|---|---|---|---|---|---|---|
| `analyse-git` | 8 | 22 | oui | 5 / 3 | 5 | 1 |
| `distribution-docker` | 6 | 18 | oui | 2 / 4 | 6 | 1 |
| `journalisation` | 4 | 10 | oui | 2 / 2 | 4 | 1 |
| `socle-d-execution` | 5 | 15 | oui | 1 / 4 | 4 | 1 |
| `tests-lies-et-impact` | 8 | 22 | oui | 6 / 2 | 4 | 1 |
| `decouverte-et-negociation` | 11 | 28 | oui | 5 / 6 | 5 | 2 |
| `recherche-semantique` | 11 | 26 | oui | 8 / 3 | 5 | 2 |
| `program-graph-et-architecture` | 14 | 41 | oui | 11 / 3 | 4 | 2 |
| `installeur-windows` | 12 | 22 | oui | 8 / 4 | 4 | 2 |
| `chaine-de-livraison` | 10 | 28 | oui | 4 / 6 | 5 | 3 |
| `controle-tenant-heberge` | 12 | 38 | oui | 9 / 3 | 4 | 3 |
| `surfaces-publiques` | 18 | 64 | oui | 12 / 6 | 5 | 3 |
| `client-intellij` | 19 | 53 | oui | 10 / 9 | 6 | 3 |
| `controles-de-fusion` | 19 | 56 | oui | 12 / 7 | 6 | 4 |
| `audit-qualite-code` | 26 | 76 | oui | 12 / 14 | 7 | 4 |
| `installation-des-providers` | 22 | 53 | oui | 15 / 7 | 6 | 4 |
| `confinement-code-non-fiable` | 28 | 66 | oui | 17 / 11 | 6 | 5 |
| `persistance-snapshots` | 33 | 82 | oui | 24 / 9 | 6 | 5 |
| `stockage-prive-et-secrets` | 37 | 107 | oui | 28 / 9 | 6 | 5 |
| `frontieres-de-modules` | 58 | 169 | oui | 40 / 18 | 9 | 6 |

Total : 20 capacités, 361 exigences, 996 scénarios ; lot 1 : 5 capacités, 31 exigences, 16 couvertes, 15 à prouver,
23 écarts ; lot 2 : 4 capacités, 48 exigences, 32 couvertes, 16 à prouver, 18 écarts. Lot 3 : 4 capacités, 59 exigences, 35 couvertes, 24 à prouver, 20 écarts. Lot 4 : 3 capacités, 67 exigences, 39 couvertes, 28 à prouver, 19 écarts. Lot 5 : 3 capacités, 98 exigences, 69 couvertes, 29 à prouver, 18 écarts. Lot 6 : 1 capacité, 58 exigences, 40 couvertes, 18 à prouver, 9 écarts. Bilan : 20 capacités, 361
exigences tracées (100 %), 231 couvertes, 121 partielles, 9 manquantes, 107 écarts, 33 vues.

Ordre proposé des lots suivants : capacités de taille moyenne qui portent une surface utilisateur (lot 2), puis
surfaces et livraison (lot 3), puis gardes de gouvernance (lots 4 à 6). Le lot 6 (`frontieres-de-modules`) est
surtout porté par des gates Python ; sa vue utile est la vue des modules générée, à relier plutôt qu'à redessiner.

## Changements OpenSpec suivis

| Changement | Schéma | Traitement |
|---|---|---|
| `exposer-seulement-les-projets-inscrits-a-docker` | `spec-driven` (épinglé : son `.openspec.yaml` manquait et il aurait basculé sur `detailed-uml`) | Artefacts conservés ; puce `## Workflow follow-up` ajoutée (mise à jour du dossier `distribution-docker` après archivage, contrôle rouge tant qu'elle n'est pas faite, vérifié sur une copie isolée). 16/17 tâches, inchangé |
| `etendre-audit-outille-a-tout-le-perimetre` | `spec-driven` | Archivé hors de ce chantier (PR #653, 2026-11-03) : `audit-qualite-code` passe à 26 exigences, sans dossier (lot 4) |
| `aligner-quatre-scenarios-sur-leurs-preuves` | `detailed-uml` | Créé par ce chantier ; 0/8 tâches ; attend la validation du propriétaire (tâche 1.5) |

## Méthode d'un lot

1. Pour chaque exigence de la capacité, relever au HEAD les tests, gates ou étapes de CI qui vérifient ses
   scénarios, et les écarts entre spec, code et tests (`chemin:ligne`).
2. Écrire le dossier : objet et périmètre, une vue comportementale au moins (validée par un rendu Mermaid), une
   vue structurelle ou d'états si le domaine le justifie, la traçabilité, les écarts.
3. Ajouter la section `## Documentation liée` à la spec de référence.
4. Ce qui change un scénario ou une exigence passe par un changement OpenSpec ; ce qui demande une décision
   métier reste une `[Question]` du dossier.
5. `python scripts/docs/check-capability-dossiers.py`, `openspec validate --all --strict`.

## Décisions attendues du propriétaire

Rassemblées depuis les écarts des six lots.

- `analyse-git` E1 : le contrôle du propriétaire doit-il précéder l'ouverture JGit, ou l'exigence se limite-t-elle
  à « avant tout statut » ?
- `tests-lies-et-impact` E4 : une requête de tests liés doit-elle déclarer une limite quand des références ne sont
  pas projetées ?
- `socle-d-execution` E1 : exiger l'exécution réelle des providers Node en qualification, ou réécrire le scénario
  en déclarant la limite ?
- `distribution-docker` E6 : la garde « image qui n'en est pas une » peut-elle appeler `docker image inspect`
  (énoncé de l'exigence à assouplir) ?
- `recherche-semantique` E1 : la synchronisation doit-elle comparer une version de documents (la spec la cite,
  le code ne compare que snapshot et modèle) ? Défaut probable à corriger.
- `recherche-semantique` E2 : la recherche hybride annonce le signal sémantique disponible alors qu'un index
  changé pendant la requête l'a annulé (contraire au principe capability-honest) ; correction proposée.
- `recherche-semantique` E5 : un modèle d'embedding injoignable à la requête doit-il échouer ou se replier sur le
  lexical en le déclarant ?
- `installeur-windows` E2 : le silence sur « Docker absent » quand le backend n'est pas Docker est-il voulu ?
- `program-graph-et-architecture` E2 : « refus » de l'analyse Java = fragment dégradé déclaré ?
- `decouverte-et-negociation` E2 : spécifier la négociation par capacités, aujourd'hui sans exigence.
- `controle-tenant-heberge` E1 : le budget des refus chaînés suit-il la taille du tenant (spec) ou le quart de
  la limite d'octets (code) ? Décision de sécurité.
- `controle-tenant-heberge` E2 : une chaîne altérée lue depuis le magasin doit-elle rester une erreur d'E/S
  côté API, ou devenir un refus nommé ?
- `chaine-de-livraison` E1 : attester aussi les sommes `.sha256` publiées, ou restreindre l'exigence ?
- `client-intellij` E1 : remplacer la garde textuelle `implementation("com.minos:` par une garde structurelle ?
- `confinement-code-non-fiable` E1 : écart de sécurité traité selon `SECURITY.md` ; son détail n'est pas publié.
- `persistance-snapshots` E4 : un format de snapshot non mesuré doit-il garder l'amplification prudente ?
- `stockage-prive-et-secrets` E4 : déclarer la limite de la publication de répertoire (course entre deux
  écrivains) dans l'exigence, ou la corriger ?
- `frontieres-de-modules` E1 et E4 : la spec se contredit sur la table des cycles connus et fige des décomptes
  de cliquet périmés ; une mise à jour par un changement est proposée.
- `audit-qualite-code` : l'audit outillé (SpotBugs, PIT, Dependency-Check) reste-t-il à la demande seulement ? Dix
  exigences n'ont aucune preuve exécutée sur une PR : cinq n'en ont aucune, cinq ne sont prouvées que par le
  workflow manuel.
- `audit-qualite-code` E1 : mettre le scénario « Périmètre initial » de PIT au périmètre réel du POM.
- `controles-de-fusion` E1 et E6 : imposer la liste minimale des checks exigés et spécifier « branche à jour » ?
- `installation-des-providers` E1 : rendre hermétique le test qui dépend du `JAVA_HOME` de l'hôte.
- Changement `aligner-quatre-scenarios-sur-leurs-preuves` : valider que les quatre comportements décrits sont
  voulus.
