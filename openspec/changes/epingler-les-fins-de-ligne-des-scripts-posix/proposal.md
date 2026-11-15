# Proposal

## Why

Constat **AUD-DEP-02** (audit du 2026-11-15, sprint 1, sévérité faible, effort S). `.gitattributes` n'épingle la fin de ligne ni des treize scripts `*.sh` ni de `mvnw`. Sur un poste Windows réglé avec `core.autocrlf=true` — celui du mainteneur — ils sont extraits en CRLF, et bash échoue dès leur première ligne (`set: pipefail\r: invalid option name`). La CI Linux extrait en LF et ne voit rien : c'est le poste qui casse, quand `start-test-postgres.sh`, `provision-linux-sandbox-cgroup.sh`, `qualify-linux-offline-maven-index.sh` ou `mvnw` sont lancés sous WSL2 ou montés dans un conteneur depuis la copie Windows, et quand `run_gates.py` rejoue les auto-tests qui exécutent ces scripts (faux rouge local). Le dépôt a déjà corrigé ce défaut pour un seul fichier, `minos-intellij/gradlew` (`.gitattributes:23-26`).

## What Changes

- `.gitattributes` épingle `eol=lf` pour `*.sh`, `mvnw` et le fichier de propriétés du wrapper Maven, et `eol=crlf` pour les lanceurs `*.cmd`.
- Le seul fichier dont le contenu indexé change est `mvnw.cmd` (indexé en CRLF au HEAD, normalisé par la renormalisation de ce seul chemin) ; les scripts POSIX sont déjà en LF dans l'index.
- Un gate `scripts/quality/check-posix-script-eol.py` refuse tout script POSIX suivi par Git (extension `.sh` ou première ligne `#!` d'un interpréteur de commandes POSIX) qui n'a pas l'attribut `eol=lf` ou dont le contenu indexé contient un retour chariot. Il a un auto-test ; les deux sont exécutés par le job `invariants`.
- La documentation développeur dit comment remettre à niveau un clone existant (les fichiers déjà extraits ne changent pas d'eux-mêmes).

Aucun code du produit ne change ; aucun script ne change de contenu.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities
- `controles-de-fusion` : deux exigences ajoutées (un script POSIX est extrait en LF quel que soit le réglage du clone ; le gate échoue fermé quand il ne peut pas lire les attributs). Aucune exigence existante n'est modifiée.

## Hors périmètre

- Les scripts PowerShell (`*.ps1`, déjà épinglés en CRLF) ; ceux qui portent une ligne `#!` (`scripts/release/test-iscc-provenance.ps1`, `test-release-signature.ps1`) restent sous leur règle CRLF.
- Les scripts Python : invoqués par `python <script>`, insensibles à la fin de ligne.
- Les `Dockerfile`, fichiers Compose et `.env.example` extraits en CRLF sur le poste : aucun échec constaté ; à traiter à part si un défaut se montre.
- Une normalisation générale du dépôt (`* text=auto`) : elle toucherait tous les fichiers et les goldens comparés octet pour octet.
- `docs/project/history/milestones/m0/RETOURS_MODELE_DOMAINE.md`, indexé avec des fins de ligne mêlées : relevé figé, non modifié.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Aucune règle pour `*.sh` ni `mvnw` | [Existant vérifié] | `.gitattributes:1-27` (27 lignes ; règles `*.ps1`, goldens, `minos-intellij/gradlew`, `*.jar`) |
| Treize `*.sh` et `mvnw` sont en LF dans l'index et en CRLF dans la copie de travail du poste | [Existant vérifié] | `git ls-files --eol '*.sh' mvnw` au HEAD : `i/lf w/crlf attr/` pour les quatorze ; `git config core.autocrlf` : `true` |
| `mvnw.cmd` est indexé en CRLF, sans attribut ; c'est le seul fichier `i/crlf` du dépôt | [Existant vérifié] | `git ls-files --eol` : une ligne `i/crlf` (`mvnw.cmd`) |
| `minos.cmd` et `.mvn/wrapper/maven-wrapper.properties` sont en LF dans l'index, CRLF sur le poste | [Existant vérifié] | `git ls-files --eol minos.cmd '.mvn/*'` |
| Hors `*.sh`, `*.py` et `fixtures/`, les fichiers à ligne `#!` sont `mvnw`, `minos-intellij/gradlew` et deux `*.ps1` | [Existant vérifié] | `git grep -lI -e '^#!'` au HEAD |
| L'échec sous bash et les deux auto-tests rouges | [Existant vérifié par l'audit] | journal de l'audit (`gates.log`), non rejoué ici |
| `mvnw` tolère-t-il un fichier de propriétés en CRLF ? | [Question] | non vérifié ; épinglé en LF par précaution (design, D2) |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : aucune.
- **ADR** : aucun (convention de dépôt).
- **Gates à rejouer** : `python scripts/quality/check-posix-script-eol.py` et `--self-test`, `python scripts/quality/check-ci-wiring.py`, `python scripts/quality/test_check_ci_wiring.py`, `python scripts/deploy/test_cgroup_root_validation.py`, `python scripts/dev/test_start_test_postgres.py`, `python scripts/quality/check-toolchain-baseline.py` (lit le wrapper Maven), `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Fusion** : quatre changements du sprint 1 ajoutent des étapes au job `invariants` de `.github/workflows/pr-ci.yml` (`executer-le-front-de-l-explorateur-en-test`, `compter-comme-sautes-les-tests-propres-a-une-plateforme`, `doter-chaque-gate-d-un-auto-test`, `epingler-les-fins-de-ligne-des-scripts-posix`), et `borner-la-couverture-des-zones-nouvelles` y modifie l'artefact de validation : les fusionner en série, chaque PR remise à jour sur `main` avant la suivante.
- **Plateformes** : effet sur les clones Windows à `core.autocrlf=true` ; sous Linux et en CI, seul `minos.cmd` change d'octets à l'extraction (il passe en CRLF ; aucun consommateur).
- **Documentation durable et diagrammes** : `docs/developer/testing.md` ou `CONTRIBUTING.md` (remise à niveau d'un clone), `docs/developer/quality-gates.md`, `docs/architecture/diagrams/capacites/controles-de-fusion.md`.

## Questions ouvertes

Aucune bloquante. Non bloquante : D2 du design (propriétés du wrapper), recommandation donnée.
