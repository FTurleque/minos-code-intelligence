# Suivi du chantier « Sprints 2–3, derniers constats » (S11, D1, C2, S14)

Chantier ouvert le 2026-10-02 sur `docs/audit/AUDIT-2026-09.md`. Quatre constats, les seuls de l'échéance sprints 2–3
encore ouverts. Après ce chantier il ne reste que des constats d'échéance **Trimestre**.

| Lot | Branche | Constats | PR | État |
|---|---|---|---|---|
| 1 | `sec/s11-chaine-et-conteneurs` | S11 | — | en cours |
| 2 | `rel/d1-distribution-auto-portante` | D1 | — | à faire |
| 3 | `ci/c2-un-seul-build` | C2 | — | à faire |
| 4 | `sec/s14-assainissement` | S14 | — | à faire |

Base : `origin/develop` au 2026-10-02 (`2663bbda`, merge de #321). Une branche, un worktree (`minos-wt/…`) par lot, lots
séquentiels, chacun rebasé sur le précédent. #322 (promotion `develop`→`main`) et #323 (correctif Sonar S2259) sont
ouvertes et hors de ce chantier : rien n'est fusionné ici.

Règle de CI de ce chantier : **un seul passage de validation par lot**, un second seulement sur demande motivée au
propriétaire. Aucun `workflow_dispatch` ni push « pour voir ».

## Constats préliminaires du pilote (avant tout code)

Relevés le 2026-10-02 en relisant le dépôt réel, à confirmer ou infirmer par l'implémenteur de chaque lot.

| # | Constat | Conséquence |
|---|---|---|
| P1 | S14 est **déjà partiellement fermé** : `AutonomousIndexOperations.ResumeView` (`minos-cli`) passe `refusalReason` par `CliCommandSupport.publicDiagnostic` à la construction (introduit par R1 lot 3, `90ab9197`). Les chemins sont donc déjà remplacés par le texte de repli. **Reste à prouver** : `PublicErrorMessages.sanitize` ne retire que `\r`/`\n`, pas les autres caractères de contrôle (ESC, etc.) — la règle unique pour les caractères de contrôle est `DegradedEntry.printable` (`minos-engine`, `com.minos.registry`). | Le lot 4 doit d'abord dire ce qui fuit **réellement** aujourd'hui (texte ? JSON ?) par un test rouge, puis corriger ce qui fuit, sans écrire un troisième assainisseur. |
| P2 | Le « plan admin » est le service compose `minos-admin` de `docker/compose.mcp.{prod,connected}.yaml` : réseau `minos-admin-egress`, `MINOS_DATA_DIR` en écriture, lance `com.minos.cli.MinosLauncher`. Il faut établir **par le code** si du code venu d'un projet non fiable y est compilé sans passer par la décision d'ADR 0041, et par quel chemin exact. | Le premier travail du lot 1 est cette preuve, pas la ligne YAML. Si la prémisse de l'audit ne se vérifie pas dans le code actuel, on le dit et on ne fabrique pas de test rouge. |
| P3 | Les trois points de conteneur sont dans `docker/compose.mcp.prod.yaml` et `docker/compose.mcp.connected.yaml` ; les services `minos-postgres` (`pgvector/pgvector:0.8.2-pg17`) et `minos-ollama` (`ollama/ollama:0.32.0`) n'existent que dans le fichier *connected*. Ollama monte son volume sur `/root/.ollama`. | L'épinglage par digest et les limites portent sur deux fichiers : une seule source pour les valeurs. |
| P4 | Docker 29.7.2 (moteur Linux, 25 Go) est disponible localement ; `actionlint`, `act`, `yamllint` ne le sont pas (`actionlint` possible via l'image `rhysd/actionlint`). | La mesure d'empreinte (lot 1) et la preuve hors ligne (lot 2) sont faisables localement. |

## Constats de `verif-s23`

| Id | Lot | Fichier:ligne | Constat | Sévérité | Résolution |
|---|---|---|---|---|---|
| — | — | — | aucun à ce stade | — | — |

## À traiter plus tard

(rien pour l'instant)
