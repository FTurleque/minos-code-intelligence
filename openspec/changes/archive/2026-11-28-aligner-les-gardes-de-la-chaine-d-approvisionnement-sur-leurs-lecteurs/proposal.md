# Proposal

## Why

Constats **AUD-DEP-16**, **AUD-DEP-17** et **AUD-DEP-18** de l'audit `docs/project/audits/2026-11-15/` (sprint 12, sévérité faible, ouverts le 2026-11-27 au commit `4f4cac81`). Trois gardes posés par le sprint 11 acceptent des formes voisines de ce qu'ils interdisent, parce qu'ils lisent le fichier autrement que l'outil qui l'exécute :

- **AUD-DEP-16.** La règle 13 de `scripts/quality/check-toolchain-baseline.py` lit les propriétés des wrappers avec `^\s*clé\s*[=:]\s*valeur` (`:396`). `mvnw` lit `while IFS="=" read -r key value` puis `case distributionSha256Sum)` (`mvnw:113-117`) : une clé indentée, suivie d'une espace ou de `:` n'est pas reconnue, et `mvnw:226` saute la vérification. Rejoué le 2026-11-27 : `distributionSha256Sum =…`, `distributionSha256Sum:…` et `  distributionSha256Sum=…` laissent le gate à 0 ; l'analyseur de `mvnw` rend une somme vide pour les trois.
- **AUD-DEP-17.** La règle des archives de `scripts/quality/check-tools-manifest.py` cherche, avant le contrôle d'empreinte, un usage du fichier par son chemin exact (`names()`, `:389-391`). Le `RUN` Node fait `cd /tmp` puis `tar -xJf "${node_archive}"` (`docker/Dockerfile.mcp.release:187-188`) : déplacer le contrôle après le `tar` laisse le gate à 0. `set +o errexit` n'est pas reconnu par le motif `set\s+\+[A-Za-z]*e` (`:354`). Un contrôle qui porte sur un autre fichier que celui téléchargé passe aussi. Rejoué le 2026-11-27 : contrôle Node après `tar`, et `set +o errexit` dans le `RUN` Maven, rc=0.
- **AUD-DEP-18.** `scripts/quality/check-posix-script-eol.py` exige `i/lf` pour tout fichier dont l'attribut résolu épingle `eol=` (`:130-133`, `:156-160`), mais rien n'exige l'épinglage des fichiers batch. Retirer `eol=crlf` de `.gitattributes:36` (`*.cmd text eol=crlf`) neutralise la règle pour `mvnw.cmd` et `minos.cmd` (rejoué par l'audit : rc=0, compteur 159 → 157).

## What Changes

- **Règle 13** : pour `.mvn/wrapper/maven-wrapper.properties`, les clés `distributionUrl` et `distributionSha256Sum` ne sont reconnues que sous la forme lue par `mvnw` (seule la somme ouvre un trou : une `distributionUrl` mal formée arrête `mvnw`, `mvnw:118` ; elle est couverte par cohérence) (clé en début de ligne, `=` aussitôt après, ligne terminée par un saut de ligne) ; une ligne qui nomme la clé sous une autre forme échoue en nommant le fichier et la ligne. Le wrapper Gradle garde la lecture des propriétés Java (D1).
- **Règle des archives** : un usage avant contrôle est aussi reconnu par le nom de base du fichier (variable comprise) quand aucun `/` ne le précède, ce qui laisse passer les URL de téléchargement ; `set +o errexit` désarme le contrôle comme `set +e` ; le fichier contrôlé doit être celui que le `curl` du même `RUN` écrit (D2).
- **Fins de ligne** : tout fichier suivi `*.cmd` ou `*.bat` doit avoir l'attribut résolu `eol=crlf` ; sinon le gate échoue en nommant le fichier et la règle `.gitattributes` attendue (D3).
- Les trois auto-tests reçoivent les cas rouges et un cas accepté chacun ; docstrings, `docs/developer/quality-gates.md` et la mise en œuvre des ADR GEN-0077 et BE-0065 nomment les formes refusées (après fusion).

Aucun script de wrapper, aucun Dockerfile, aucun `.gitattributes`, aucun workflow ne change : les fichiers au HEAD respectent déjà les trois règles. Aucune sortie publique ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `socle-d-execution` : deux exigences ajoutées (forme des clés de somme lue par `mvnw` ; contrôle d'empreinte de l'image non contournable par nom relatif, `set +o errexit` ou autre fichier).
- `controles-de-fusion` : une exigence ajoutée (les fichiers batch suivis ont leur fin de ligne épinglée).

## Hors périmètre

- Modifier `mvnw`, `mvnw.cmd` ou le wrapper Gradle : fichiers du projet Maven Wrapper et Gradle, réécrits par leurs montées de version (D1).
- Prouver que les sommes sont celles des archives publiées (accès réseau) : réserve d'AUD-DEP-09, hors sprint.
- Les téléchargements de l'image non vérifiés par une empreinte du catalogue (`npm ci`, `go install`, `cs fetch`, apt) : hors du constat, comme au sprint 11.
- L'épinglage de `*.ps1`, des fichiers comparés à l'octet et des scripts POSIX : les scripts POSIX sont déjà tenus (`eol=lf` exigé) ; `*.ps1` reste épinglé sans règle sur le pin (D3).
- L'instantané apt et l'application du skill de montée de version (AUD-DEP-11, tâche manuelle du sprint 11).

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| `PROPERTY` tolère indentation, espaces et `:` ; `properties_of` sert aux deux wrappers | [Existant vérifié] | `scripts/quality/check-toolchain-baseline.py:396`, `:401-410`, `:413-437` |
| `mvnw` : `IFS="="`, `case distributionSha256Sum)` ; vérification seulement si la somme est non vide | [Existant vérifié] | `mvnw:112-117`, `:226` |
| Analyseur de `mvnw` rejoué : somme vide pour les trois formes, lue pour `=` suivi d'espaces | [Existant vérifié] | boucle de `mvnw` rejouée le 2026-11-27 (design) |
| `mvnw.cmd` lit par `ConvertFrom-StringData` (espaces autour de `=` tolérés, `:` non) | [Existant vérifié] | `mvnw.cmd:138` |
| `names()` compare le chemin exact ; Node : `cd /tmp` puis nom relatif | [Existant vérifié] | `check-tools-manifest.py:389-391`, `:471-480` ; `docker/Dockerfile.mcp.release:184-188` |
| `DISARMING` : `set\s+\+[A-Za-z]*e` ne reconnaît pas `set +o errexit` | [Existant vérifié] | `check-tools-manifest.py:353-357` |
| Mutations : quatre formes, rc=0 | [Existant vérifié] | rejouées le 2026-11-27 (design) |
| Pin non gardé : `*.cmd text` à la place de `*.cmd text eol=crlf` donne rc=0 | [Existant vérifié par l'audit, non rejoué] | fiche AUD-DEP-18 |
| Fichiers batch suivis : `mvnw.cmd`, `minos.cmd`, `minos-intellij/gradlew.bat`, tous `i/lf` et `eol=crlf` | [Existant vérifié] | `git ls-files --eol` |
| Formes refusées et lecture par extension | [Proposition] | design, D1 à D3 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé. GEN-0077 (règle 13, `check-toolchain-baseline.py`) et BE-0065 (`check-tools-manifest.py`) gagnent une précision dans « Compliance and Enforcement », décision inchangée : tâche après fusion.
- **Gates à rejouer** : `check-toolchain-baseline.py` et `test_check_toolchain_baseline.py`, `check-tools-manifest.py` et `test_check_tools_manifest.py`, `check-posix-script-eol.py` et son `--self-test`, `python scripts/release/sync-tools-manifest.py` (sans `--write`), `check-image-pins.py`, `scripts/ci/test_docker_gate.py`, `check-ci-wiring.py`, `check-single-execution.py`, `check-comment-language.py`, `python .claude/scripts/run_gates.py`. Aucun scope JaCoCo.
- **Plateformes** : trois gates Python sous Ubuntu (job `invariants`) et sur le poste Windows ; `check-posix-script-eol.py` lit Git (`git ls-files --eol`), disponible des deux côtés. Les formes refusées valent pour `mvnw` (Linux, macOS, Git Bash) et sont aussi acceptées par `mvnw.cmd`.
- **Documentation durable** : `docs/developer/quality-gates.md`, `docs/developer/TOOLCHAIN_POLICY.md` (si elle décrit la forme de la ligne), dossiers `socle-d-execution.md` et `controles-de-fusion.md` (à l'archivage).

## Questions ouvertes

Aucune bloquante : D1 à D3 ont une recommandation.
