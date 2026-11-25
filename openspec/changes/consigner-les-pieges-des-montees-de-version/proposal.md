# Proposal

## Why

Deux constats de l'audit `docs/project/audits/2026-11-15/` (sprint 11, sévérité faible, ouverts le 2026-11-25 au commit `75e10c68`).

- **AUD-DEP-11** : deux pièges des PR Dependabot ne sont écrits que dans la mémoire d'agent versionnée (`.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:46-49`). Le skill `minos-dependency-update` ne les nomme pas, et aucun gate ne les voit. Premier piège : l'instantané apt `UBUNTU_APT_SNAPSHOT` doit être au moins aussi récent que l'image Temurin. Second piège : `mvnw.cmd` doit rester en LF dans l'index ; Dependabot l'a réécrit en CRLF (commit `b2d29c6af`, « l'arbre extrait en CI apparaissait modifié et le candidat Windows refusait de se construire »). Revérifié le 2026-11-25 : un `mvnw.cmd` enregistré `i/crlf` laisse `check-posix-script-eol.py` vert, et un instantané ramené au 2026-10-03 laisse verts `check-image-pins.py`, `check-tools-manifest.py` et `check-current-docs.py`.
- **AUD-DEP-12** : la documentation courante écrit « Maven 3.10.0 » pour le wrapper, qui télécharge Maven 3.10.2 (`.mvn/wrapper/maven-wrapper.properties:3`). Aucun gate ne lie ces mentions au wrapper.

## What Changes

- **Skill** `.claude/skills/minos-dependency-update/SKILL.md` : l'étape 4 (images) dit de relever `UBUNTU_APT_SNAPSHOT` avec chaque image Temurin, à une date au moins égale à celle de l'image, et de déplacer dans la même PR la date figée par `M29DockerAdministrationContractTest` et par `docs/user/docker-runtime.md`. L'étape 5 (wrappers) dit que `mvnw.cmd` et `gradlew.bat` restent `i/lf` dans l'index (`git add --renormalize`), et que la règle du gate les garde.
- **Gate des fins de ligne** : `scripts/quality/check-posix-script-eol.py` gagne une règle. Tout fichier suivi dont l'attribut résolu porte `eol=lf` ou `eol=crlf` SHALL être enregistré `i/lf`. Elle couvre `mvnw.cmd`, `gradlew.bat`, `minos.cmd`, les `*.ps1` et les références épinglées en LF. Son `--self-test` reçoit le cas rouge `mvnw.cmd` en `i/crlf` (design, D1).
- **Versions de Maven dans la documentation courante** : `check-toolchain-baseline.py` gagne une règle. Dans une liste fermée de documents courants, toute version de Maven écrite (`Maven X.Y.Z`, `wrapper … X.Y.Z`) SHALL être celle du wrapper ou celle de l'image (`MAVEN_VERSION` du Dockerfile de release) (design, D2). Les documents ne recopient plus le correctif du wrapper : STATUS.md, ROADMAP.md, `docs/developer/quality/code-audit.md` et `docs/user/docker-runtime.md` renvoient au fichier de propriétés.
- **ADR GEN-0077** : son « Écart constaté » qui cite « Maven 3.10.0 » est complété par une note datée (3.10.2 au 2026-11-25, mentions gardées par la règle), sans réécrire la décision. C'est une tâche, pas une modification pendant la spécification.

Aucun code Java, aucun workflow, aucune sortie de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change. Aucune étape CI n'est ajoutée : les deux gates et leurs auto-tests sont déjà lancés par le job `invariants` (`.github/workflows/pr-ci.yml:100`, `:103`, `:156`, `:159`).

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `controles-de-fusion` : une exigence ajoutée (un fichier à fin de ligne épinglée est enregistré sans retour chariot).
- `socle-d-execution` : une exigence ajoutée (les versions de Maven écrites dans la documentation courante sont celles du wrapper ou de l'image).

## Hors périmètre

- Un gate qui comparerait `UBUNTU_APT_SNAPSHOT` à la date de l'image Temurin : la date de l'image ne se lit ni dans le tag ni dans le digest, hors ligne (design, D3). Le skill porte la règle ; l'installation apt de l'image échoue déjà fermée quand l'instantané est trop ancien.
- Les relevés datés : `docs/developer/quality/code-audit-couverture.md:15`, `:362` décrivent l'audit outillé du 2026-10-08, où le wrapper était en 3.10.0. Les ADR archivés `archive/0004-…:175` et `archive/GEN-0067-…:97` sont de l'historique.
- `docs/developer/supply-chain.md:280` (« Maven 3.10, celui du wrapper ») reste exact.
- Les sommes des distributions des wrappers : AUD-DEP-09, changement `exiger-l-empreinte-des-distributions-des-wrappers`.
- Le contrôle d'empreinte des archives de l'image : AUD-DEP-10, changement `verrouiller-l-empreinte-de-maven-dans-l-image`.
- La mémoire d'agent `.claude/agent-memory/` : elle reste telle quelle ; le skill devient la référence.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les deux pièges ne sont écrits que dans la mémoire d'agent, versionnée | [Existant vérifié] | `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:46-49` ; `git ls-files .claude/agent-memory` |
| Le skill ne cite ni l'instantané apt ni `mvnw.cmd` ; l'étape 5 ne parle que de `gradlew.bat` | [Existant vérifié] | `.claude/skills/minos-dependency-update/SKILL.md:14-15` |
| `.gitattributes` porte déjà `*.cmd text eol=crlf` ; `mvnw.cmd` est `i/lf w/crlf attr/text eol=crlf` au HEAD | [Existant vérifié] | `.gitattributes:36` ; `git ls-files --eol mvnw.cmd` |
| Le piège `mvnw.cmd` s'est produit | [Existant vérifié] | commits `206b59bd0`, `b2d29c6af` (diff : 189 lignes CRLF vers LF) |
| `check-posix-script-eol.py` ne contrôle que `*.sh`, les shebangs POSIX et le fichier de propriétés Maven | [Existant vérifié] | `scripts/quality/check-posix-script-eol.py:123-140` |
| `mvnw.cmd` enregistré `i/crlf` : gate vert | [Existant vérifié] | clone du dépôt dans le scratchpad, blob CRLF posé par `git update-index`, le 2026-11-25 (design) |
| Aucun fichier suivi n'est `i/crlf` au HEAD ; un seul `i/mixed`, sans attribut, dans `docs/project/history/` | [Existant vérifié] | `git ls-files --eol` : 3 640 `i/lf`, 1 `i/mixed`, 14 `i/-text`, 13 `i/none` |
| Instantané ramené au 2026-10-03 : gates verts ; seul `M29…Test:151`, qui fige la date, divergerait | [Existant vérifié] partiellement | mutation rejouée le 2026-11-25 ; test Java non exécuté |
| Mentions « 3.10.0 » dans la documentation courante | [Existant vérifié] | `STATUS.md:303`, `ROADMAP.md:131`, `docs/developer/quality/code-audit.md:23`, `docs/user/docker-runtime.md:166`, ADR GEN-0077:113 |
| Aucune table de faits de `check-current-docs.py` ni de `product-facts.py` ne lit la version du wrapper | [Existant vérifié] | `scripts/docs/check-current-docs.py` (aucune lecture de `maven-wrapper.properties`) ; `scripts/docs/product-facts.py:247-259` (version du reactor seulement) |
| Règle `i/lf` pour tout fichier à `eol` épinglé | [Proposition] | design, D1 |
| Règle « versions de Maven de la documentation courante » | [Proposition] | design, D2 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun créé. GEN-0077, section « Écarts constatés » : note datée (tâche 3.3, après la fusion), décision inchangée.
- **Workflow** : aucun changement.
- **Gates à rejouer** : `scripts/quality/check-posix-script-eol.py` et `--self-test`, `scripts/quality/check-toolchain-baseline.py`, `scripts/quality/test_check_toolchain_baseline.py`, `scripts/docs/check-current-docs.py`, `scripts/docs/product-facts.py --check`, `scripts/quality/check-ci-wiring.py`, `scripts/quality/check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : Windows et Linux. La règle des fins de ligne lit Git lui-même (`git ls-files --eol`), indépendamment de `core.autocrlf` ; `mvnw.cmd` sert le poste Windows et le job `Verify (windows-2022)`.
- **Documentation durable** : skill `minos-dependency-update`, `docs/project/governance/STATUS.md`, `docs/project/governance/ROADMAP.md`, `docs/developer/quality/code-audit.md`, `docs/user/docker-runtime.md`, `docs/developer/TOOLCHAIN_POLICY.md` (règle de la documentation), ADR GEN-0077 (écart), dossiers `controles-de-fusion.md` et `socle-d-execution.md` (à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation. Le nom du changement (« consigner ») est plus étroit que son contenu, qui ajoute deux règles de gate ; il est gardé pour suivre le découpage du sprint.
