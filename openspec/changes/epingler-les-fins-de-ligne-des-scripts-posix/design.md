# Design

Changement de configuration du dépôt et d'outillage : les sections Contexte, État vérifié au HEAD, Écarts, Décisions, Vérification et Traçabilité sont remplies. Les autres sections du modèle sont non applicables : aucun code du produit ne change.

## Contexte

Motivation : `proposal.md`. Git convertit les fins de ligne à l'extraction selon `core.autocrlf` sauf si un attribut `eol` l'emporte. Un attribut `text eol=lf` agit sur les extractions futures ; il ne réécrit pas les fichiers déjà présents dans une copie de travail. `git add --renormalize` ne modifie que l'**index** (les fichiers enregistrés avec des retours chariot).

## État vérifié au HEAD

HEAD `89be3fb4`, poste Windows, `core.autocrlf=true`.

| Fait | Preuve |
|---|---|
| Règles existantes : `*.ps1`, `*.ps1frag`, `*.ps1.template` en CRLF ; goldens, TSV et rapports d'impact en LF ; `minos-intellij/gradlew` en LF, `gradlew.bat` en CRLF ; `*.jar` binaire | `.gitattributes:5-27` |
| Treize `*.sh` suivis : `scripts/ci/` (4), `scripts/deploy/` (1), `scripts/dev/` (1), `scripts/history/m0/` (1), `scripts/m24/` à `scripts/m28/` (5), `scripts/remediation/` (1) | `git ls-files '*.sh'` |
| Ces treize fichiers et `mvnw` : `i/lf w/crlf attr/` (aucun attribut) | `git ls-files --eol '*.sh' mvnw` |
| `minos-intellij/gradlew` : `i/lf w/lf attr/text eol=lf` | même commande |
| `mvnw.cmd` : `i/crlf w/crlf attr/` ; seul fichier `i/crlf` du dépôt | `git ls-files --eol` (une ligne `i/crlf`) |
| `minos.cmd`, `.mvn/wrapper/maven-wrapper.properties` : `i/lf w/crlf attr/` | `git ls-files --eol minos.cmd '.mvn/*'` |
| Fichiers à première ligne `#!` hors `*.sh`, `*.py`, `fixtures/` : `mvnw`, `minos-intellij/gradlew`, `scripts/release/test-iscc-provenance.ps1`, `scripts/release/test-release-signature.ps1` | `git grep -lI -e '^#!'` |
| Deux auto-tests de gates exécutent des `*.sh` avec le bash de Git pour Windows | `scripts/deploy/test_cgroup_root_validation.py:28-29`, `:61-77` ; `scripts/dev/test_start_test_postgres.py` |
| Des gates appellent déjà `git` | `scripts/quality/check-reproducible-build.py`, `scripts/quality/verify-ruleset.py` |

Non rejoué : l'échec `set: pipefail\r: invalid option name` et les deux auto-tests rouges (relevés par l'audit dans son journal) ; l'état CRLF de la copie de travail, qui en est la cause, est constaté.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| E1 — « puis `git add --renormalize .` » : les scripts POSIX sont déjà en LF dans l'index, la commande n'y change rien ; lancée sur `.`, elle réécrirait aussi le relevé figé à fins de ligne mêlées | action de AUD-DEP-02 | `git ls-files --eol` (`i/mixed` pour `docs/project/history/milestones/m0/RETOURS_MODELE_DOMAINE.md`) | renormaliser les seuls chemins nommés (D3) |
| E2 — L'attribut ne corrige pas un clone existant : les fichiers extraits en CRLF le restent jusqu'à leur ré-extraction | action de AUD-DEP-02 | comportement de Git | étape de remise à niveau documentée et tâche manuelle par clone et par worktree (D4) |
| E3 — `mvnw.cmd text eol=crlf` : le fichier est indexé en CRLF ; avec l'attribut `text`, Git attend du LF dans l'index et le signale modifié tant qu'il n'est pas renormalisé | action de AUD-DEP-02 | `git ls-files --eol mvnw.cmd` | renormaliser `mvnw.cmd` dans le même commit ; son contenu extrait reste CRLF sur tous les systèmes (D3) |
| E4 — « éventuellement un cas dans un gate existant » : aucun gate existant ne lit les attributs Git | action de AUD-DEP-02 | recherche de `gitattributes` dans `scripts/` : aucun gate | gate dédié, court, avec auto-test (D5) ; sans lui le défaut revient au prochain script ajouté |
| E5 — `mvnw` lit `.mvn/wrapper/maven-wrapper.properties`, lui aussi extrait en CRLF sur le poste | périmètre de l'action | `git ls-files --eol '.mvn/*'` | l'épingler en LF (D2) ; effet d'un CRLF sur `mvnw` non vérifié |
| E6 — `minos.cmd` n'est pas cité | action (« `mvnw.cmd text eol=crlf` ») | `git ls-files --eol minos.cmd` | règle `*.cmd text eol=crlf`, cohérente avec `gradlew.bat` (D1). Effet sous Linux : `minos.cmd`, indexé en LF et sans attribut au HEAD, y sera désormais extrait en CRLF. Sans conséquence relevée : le lanceur distribué est généré par `scripts/release/build-windows-distribution.ps1:380`, et aucun script, workflow ni test n'exécute le `minos.cmd` de la racine |

## Décisions

### D1 — Règles ajoutées à `.gitattributes`
[Décision] `*.sh text eol=lf`, `mvnw text eol=lf`, `*.cmd text eol=crlf`, avec un commentaire qui cite AUD-DEP-02 et le précédent de `minos-intellij/gradlew`. Les règles nominatives existantes de `gradlew` et `gradlew.bat` restent. Écarté : `* text=auto` (touche les goldens et tout le dépôt).

### D2 — Propriétés du wrapper Maven en LF
[Proposition, recommandée] `.mvn/wrapper/maven-wrapper.properties text eol=lf` : le fichier est lu par un script POSIX. Coût nul (déjà LF dans l'index).

### D3 — Renormalisation ciblée
[Décision] `git add --renormalize -- mvnw.cmd` seulement, après l'ajout des règles ; vérifier par `git status --porcelain` que seuls `.gitattributes` et `mvnw.cmd` sont modifiés, puis par `git ls-files --eol` qu'aucun fichier visé n'est `i/crlf`.

### D4 — Remise à niveau d'un clone existant
[Décision] Documentée, non automatisée : supprimer de la copie de travail les fichiers visés puis les ré-extraire (`git ls-files -z -- '*.sh' mvnw '.mvn/wrapper/maven-wrapper.properties'`, suppression, `git checkout -- <mêmes chemins>`), dans le dépôt principal et dans chaque worktree. Un script de plus pour une opération faite une fois par clone n'est pas justifié.

### D5 — Gate `check-posix-script-eol.py`
[Décision] Bibliothèque standard ; appelle `git ls-files --eol -z` par une liste d'arguments, environnement réduit. Est un script POSIX tout fichier suivi dont le nom finit par `.sh`, ou dont la première ligne commence par `#!` et nomme `sh`, `bash`, `dash`, `ksh` ou `zsh` (directement ou par `env`), hors `fixtures/`. Pour chacun : attribut `eol=lf` et index `i/lf`. Le fichier de propriétés du wrapper est contrôlé par nom. Échec fermé : `git` introuvable, racine hors dépôt, zéro script. Messages en chemins relatifs. `--self-test` : dépôts Git temporaires créés par le test (aucun accès réseau), cas des scénarios. Écarté : analyser `.gitattributes` à la main (réimplémente la résolution des motifs de Git).

## Vérification

- Rouge : `python scripts/quality/check-posix-script-eol.py` échoue au HEAD en nommant les quatorze fichiers sans attribut.
- Après correction : gate et `--self-test` verts ; `git ls-files --eol '*.sh' mvnw` : `attr/text eol=lf` partout.
- Clone neuf : `git -c core.autocrlf=true clone <dépôt local> <dossier du scratchpad>` puis `git ls-files --eol` : `w/lf` pour les fichiers visés ; dossier supprimé ensuite.
- Poste remis à niveau : `python scripts/dev/test_start_test_postgres.py` et `python scripts/deploy/test_cgroup_root_validation.py` passent ; `python .claude/scripts/run_gates.py` vert.
- `mvnw.cmd` : après renormalisation, `./mvnw.cmd -v` fonctionne sur le poste (le fichier extrait reste CRLF) ; `python scripts/quality/check-toolchain-baseline.py` vert.
- Linux et CI : seul `minos.cmd` change d'octets à l'extraction (CRLF, écart E6) ; le job `invariants` exécute le gate et son auto-test.
- Non vérifiable ici : `mvnw` sous WSL2 depuis la copie Windows (tâche manuelle).

## Traçabilité

| Exigence (capacité › titre) | Scénario | Conception ou diagramme | Tâche | Test ou preuve |
|---|---|---|---|---|
| controles-de-fusion › Un script POSIX est extrait en LF quel que soit le réglage du clone | État du dépôt | D1, D2, D5 | 1.1, 2.1, 2.2 | à écrire : gate |
| idem | Clone Windows avec conversion automatique | D1 | 2.3 | commande du scénario, clone jetable |
| idem | Script ajouté sans règle ; Script enregistré avec des retours chariot | D5 | 1.2 | à écrire : `--self-test` |
| idem | Auto-tests des scripts shell sur le poste | D4 | 3.1 | `test_start_test_postgres.py`, `test_cgroup_root_validation.py` (existants) |
| controles-de-fusion › Le contrôle des fins de ligne échoue fermé | Aucun script trouvé ; Racine hors dépôt | D5 | 1.2 | à écrire : `--self-test` |
| idem | Auto-test câblé | D5 | 2.4 | `check-ci-wiring.py` (règle existante) |

## Questions ouvertes

- [Question, propriétaire] D2 : épingler aussi `.mvn/wrapper/maven-wrapper.properties` (recommandé).
