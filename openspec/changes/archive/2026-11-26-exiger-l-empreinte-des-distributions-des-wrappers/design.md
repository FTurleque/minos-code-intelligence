# Design

## Contexte

Motivation : voir `proposal.md` (Why). C'est un changement d'outillage : seuls un gate Python existant, son auto-test, une page de politique, un skill et la mise en œuvre d'un ADR changent. Le design est donc réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819), le 2026-11-25.

| Fait | Preuve |
|---|---|
| Wrapper Maven : `wrapperVersion=3.3.4`, `distributionType=only-script`, distribution `https://repo.maven.apache.org/maven2/…/apache-maven-3.10.2-bin.zip`, `distributionSha256Sum` de 64 caractères | `.mvn/wrapper/maven-wrapper.properties:1-4` |
| `.mvn/wrapper/` ne contient aucun `maven-wrapper.jar` : en `only-script`, `mvnw` télécharge directement la distribution, sans jar intermédiaire, donc sans `wrapperUrl` ni `wrapperSha256Sum` | `ls .mvn/wrapper` ; `.mvn/wrapper/maven-wrapper.properties:2` |
| Wrapper Gradle : `distributionSha256Sum` (`:3`), `distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.2-bin.zip` (`:4`), `validateDistributionUrl=true` (`:8`, contrôle d'accès à l'URL, pas d'intégrité) | `minos-intellij/gradle/wrapper/gradle-wrapper.properties:1-10` |
| `mvnw` lit `distributionSha256Sum` s'il existe (`:115`), puis ne vérifie que dans `if [ -n "${distributionSha256Sum-}" ]` ; sans la ligne, l'archive est décompressée et exécutée | `mvnw:111-118`, `:225-247` |
| `mvnw.cmd` a la même règle sous Windows : `$distributionSha256Sum = …` puis `if ($distributionSha256Sum) {` ; sans la ligne, `Expand-Archive` suit directement | `mvnw.cmd:137-150` |
| `MVNW_REPOURL` remplace l'URL au lancement, sous les deux systèmes ; la somme, si elle est présente, s'applique à l'archive servie | `mvnw:141` ; `mvnw.cmd:75-77` |
| `check-toolchain-baseline.py` connaît le seul wrapper Gradle (`WRAPPER`, `:71`) et n'en lit que la version (`DISTRIBUTION`, `:76` ; `check_gradle`, `:117-125`) ; il ne lit pas le wrapper Maven | `scripts/quality/check-toolchain-baseline.py:71-76`, `:117-125` |
| L'auto-test écrit une fixture `distributionSha256Sum=00` qu'aucune règle ne lit | `scripts/quality/test_check_toolchain_baseline.py:20-23` |
| Le gate et son auto-test sont lancés par le job `invariants` | `.github/workflows/pr-ci.yml:99-103` |
| `check-tools-manifest.py` décrit les outils embarqués (catalogue `embedded-tools.json`, `ARG` du Dockerfile, distribution Windows) ; il ne lit aucun wrapper | `scripts/quality/check-tools-manifest.py:1-23` |
| Les autres lecteurs de `.mvn/wrapper/maven-wrapper.properties` : `check-posix-script-eol.py` (fins de ligne, `:41`), `docker-gate.py` (liste des chemins qui exigent la qualification Docker, `:60`) ; aucun ne lit son contenu | `scripts/quality/check-posix-script-eol.py:41` ; `scripts/ci/docker-gate.py:60` |
| `gradle-wrapper.jar` est validé dans la CI du plugin par `gradle/actions/setup-gradle`, d'après le commentaire des workflows | `.github/workflows/intellij-plugin.yml:72-73`, `:133-134` ; `.github/workflows/release-windows.yml:105` |
| La politique dit la somme de la distribution Gradle épinglée, sans gate ; elle ne dit rien de celle de Maven | `docs/developer/TOOLCHAIN_POLICY.md:11-12`, `:20` |
| Le skill de montée de dépendance dit la somme Gradle épinglée (étape 5) et ne nomme pas le wrapper Maven | `.claude/skills/minos-dependency-update/SKILL.md:15` |
| GEN-0077 (`Accepted`) liste `check-toolchain-baseline.py` dans sa mise en œuvre, règles 4 à 7, avec des règles Archgate qui en sont le second exécutant | `docs/architecture/adr/GEN-0077-socle-d-execution-java-21-et-maven.md:98-107` |

### Mutations rejouées

Le 2026-11-25, le dépôt est extrait par `git archive HEAD` dans le scratchpad de la session, une copie par mutation. Les cinq gates sont lancés avec la racine de la copie.

| Mutation (copie) | `check-toolchain-baseline` | `check-tools-manifest` | `check-workflow-pins` | `check-image-pins` | `check-current-docs` |
|---|---|---|---|---|---|
| Témoin (copie intacte) | 0 | 0 | 0 | 0 | 0 |
| Les deux lignes `distributionSha256Sum` supprimées | 0 | 0 | 0 | 0 | 0 |
| URL Maven passée en `http://evil.example/maven2/` | 0 | 0 | 0 | 0 | non lancé |

Non reproduit : le harnais de l'audit qui exécute `mvnw` contre un zip factice servi en HTTP (somme absente : faux Maven exécuté). La consigne de la session interdit de lancer `mvnw`. Le comportement est établi par lecture de `mvnw:225-247` et de `mvnw.cmd:137-150`, pas par exécution. Le wrapper Gradle n'a pas été exécuté non plus.

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'action de l'audit vise la seule ligne de somme. Une URL déplacée vers un autre hôte, ou passée en `http`, reste invisible : avec une somme recopiée de la nouvelle archive, rien ne la distingue d'une montée légitime | `findings.json`, AUD-DEP-09, action (URL : « optionnel ») | mutation « URL en `http` » : gates verts | D1 : l'hôte et le schéma de l'URL font partie de la règle |
| L'audit ne parle pas de `distributionType`. Un passage à `bin` (le défaut d'Apache) ferait télécharger un `maven-wrapper.jar` sans somme | `.mvn/wrapper/maven-wrapper.properties:2` | — | D2 : `only-script` exigé |
| L'audit cite `mvnw:226-247` ; `mvnw.cmd`, le chemin qu'utilisent le poste Windows et le job `Verify (windows-2022)`, a le même fail-open | `findings.json`, AUD-DEP-09, preuve | `mvnw.cmd:137-150` | l'exigence couvre les deux systèmes par le fichier de propriétés qu'ils lisent |
| La politique annonce « distribution SHA-256 pinned » pour Gradle sans qu'aucun gate le tienne | `docs/developer/TOOLCHAIN_POLICY.md:20` | mutation « sommes supprimées » | la phrase devient vraie, et elle est étendue à Maven (tâche 2.2) |
| La fixture `distributionSha256Sum=00` de l'auto-test violera la règle | `test_check_toolchain_baseline.py:22` | règle 13 | fixture remplacée par 64 caractères (tâche 1.2) |

## Exigences couvertes

- socle-d-execution › Chaque wrapper de build exige l'empreinte de sa distribution — D1, D2.

## Décisions

### D1 — Gate hôte : `check-toolchain-baseline.py`, règle 13 [Proposition, recommandation]

Options :

- (a) **`check-toolchain-baseline.py`** (recommandée). Il lit déjà le wrapper Gradle, et il a pour objet la toolchain de build : Java, Gradle, verrous du plugin. Son auto-test et son exécution par le job `invariants` existent (`pr-ci.yml:100`, `:103`) : aucune étape n'est ajoutée, `check-ci-wiring.py` reste vert. La règle s'ajoute en 13, à la fin : les numéros 6 et 7 cités par GEN-0077 ne bougent pas.
- (b) `check-tools-manifest.py` : son objet est la charge d'outils que MINOS livre (catalogue, image, distribution Windows). Les wrappers ne sont pas livrés : ils construisent le produit. Y ajouter la règle mélangerait deux sources de vérité.
- (c) Un gate nouveau : une étape CI et un auto-test de plus, pour deux fichiers.

Contenu de la règle, pour chaque fichier de propriétés, avec un message qui nomme le fichier et la ligne :

1. exactement une ligne `distributionSha256Sum=` dont la valeur est 64 caractères `[0-9a-f]` ;
2. exactement une `distributionUrl`, en `https`, sous `https://repo.maven.apache.org/maven2/` (Maven) ou `https://services.gradle.org/distributions/` (Gradle ; le `\:` d'échappement du format `.properties` est accepté) ;
3. fichier absent ou illisible : échec (fail-closed), jamais un succès sur zéro fichier.

Ce que la règle ne prouve pas, et que la docstring écrira : que la somme est celle de l'archive publiée. Une somme fausse mais bien formée fait échouer `mvnw` ou Gradle au premier téléchargement, donc dès le premier job `Verify` ou le premier job du plugin. Calculer la somme hors ligne n'est pas possible sans télécharger l'archive.

### D2 — `distributionType=only-script` exigé ; aucun jar de wrapper ajouté [Proposition, recommandation]

En `only-script`, `mvnw` et `mvnw.cmd` téléchargent la distribution eux-mêmes : il n'y a ni `maven-wrapper.jar` ni `wrapperSha256Sum`. Exiger `only-script` ferme la seule voie par laquelle un jar non vérifié entrerait. L'option (b), accepter `bin` à condition d'avoir `wrapperSha256Sum`, ouvrirait une seconde configuration que rien ne qualifie aujourd'hui.

Pour Gradle, `gradle-wrapper.jar` est versionné (48 Kio) et validé par `setup-gradle` dans les jobs du plugin. Le figer par une somme dans le gate demanderait de relever la somme à chaque montée, alors que la validation existe déjà. Ce changement ne l'ajoute pas ; c'est un reste nommé (hors périmètre).

### D3 — Les scripts du wrapper ne sont pas modifiés [Proposition, recommandation]

Rendre `mvnw` et `mvnw.cmd` fail-closed (refus en l'absence de somme) corrigerait le défaut à l'exécution. Mais ces scripts sont réécrits par chaque montée du wrapper (Dependabot, `mvn wrapper:wrapper`) : la modification serait perdue en silence à la première montée, précisément le cas que le constat vise. Le gate sur le fichier de propriétés tient quel que soit le script. Option écartée.

### D4 — GEN-0077 : une ligne de mise en œuvre, pas de règle Archgate compagnon [Proposition, recommandation ; décision du propriétaire]

`.claude/rules/adr.md` demande une règle Archgate compagnon pour toute contrainte d'un ADR lisible dans des fichiers. Les `Do's and Don'ts` de GEN-0077 ne parlent pas de la somme des wrappers. La règle 13 met en œuvre « Maven par le wrapper » ; elle n'est pas une décision nouvelle. Options :

- (a) une ligne dans « Compliance and Enforcement » qui nomme la règle 13, sans règle Archgate (recommandée) : la décision ne change pas, et le gate Python fait foi ;
- (b) ajouter aussi un `Do` et une règle `wrapper-distribution-sums` au fichier `GEN-0077-…rules.ts` : cela demande de compléter la décision. Une décision `Accepted` ne se réécrit pas ; il faudrait un ADR consolidé (commande `/minos:adr`), disproportionné pour deux lignes de propriétés.

Avec (a), la ligne est écrite après la fusion du gate (tâche 2.3), jamais pendant la spécification.

## Vérification

- Rouge : sur le dépôt, la règle n'existe pas ; les cas de 1.1 sont d'abord ajoutés à l'auto-test et échouent.
- Rouge sur copie : les mutations de l'état vérifié (sommes supprimées, URL en `http`) laissent le gate actuel vert et font échouer le gate corrigé en nommant le fichier.
- Correction : `python scripts/quality/check-toolchain-baseline.py` vert sur le dépôt, avec le compte des sites augmenté de 2 ; `python scripts/quality/test_check_toolchain_baseline.py` vert.
- Windows et Linux : bibliothèque standard, `pathlib`, lecture en UTF-8, aucune branche de plateforme ; le fichier de propriétés est en LF (`.gitattributes`, gardé par `check-posix-script-eol.py`) et le fichier Gradle n'a pas d'attribut : la règle tolère `\r\n`.

## Documentation durable à mettre à jour

- `docs/developer/TOOLCHAIN_POLICY.md` : section Maven (somme de la distribution exigée, règle 13) et ligne Gradle (`:20`, « gardée par la règle 13 »).
- `.claude/skills/minos-dependency-update/SKILL.md`, étape 5 : le wrapper Maven aussi ; relever la somme avec l'URL, jamais la retirer.
- ADR GEN-0077, « Compliance and Enforcement » : une ligne qui nomme la règle 13 (mise en œuvre complétée, décision inchangée).
- `docs/architecture/diagrams/capacites/socle-d-execution.md` : ligne de traçabilité de l'exigence ajoutée, à l'archivage.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve exécutée (2026-11-25, poste Windows) | Preuve manquante |
|---|---|---|---|---|---|
| AUD-DEP-09 | socle-d-execution › Chaque wrapper de build exige l'empreinte de sa distribution | D1, D2, D3, D4 (a) | 1.1-1.4, 2.1-2.4 | mutations sur copie : sommes supprimées et URL en `http`, cinq gates verts (trou reproduit) ; implémentation (2026-11-25) : sur copie `git archive`, `check-toolchain-baseline.py --root` rend 0 avant la règle 13 pour les mutations « sommes supprimées », « URL Maven en `http://evil.example/` » et « `distributionType=bin` », puis 1 après, en nommant le fichier et la ligne ; auto-test : 12 cas en erreur avant la règle, 59 tests verts après ; dépôt : 35 puis 37 sites en accord ; `check-ci-wiring`, `check-posix-script-eol`, `test_docker_gate`, `check-comment-language`, `check-single-execution`, `archgate check` (17/17) verts | exécution de `mvnw`/`mvnw.cmd` sans somme (non rejouée, consigne) ; exécution en CI (tâche 2.4) ; dossier de capacité et ligne du suivi d'audit (à l'archivage) |

Rouge documentaire (tâche 2.1), relevé avant correction : `docs/developer/TOOLCHAIN_POLICY.md:20` affirmait la somme Gradle épinglée sans gate ; `TOOLCHAIN_POLICY.md:11-12` et `.claude/skills/minos-dependency-update/SKILL.md:15` ne nommaient pas la somme du wrapper Maven. Restes de la tâche 3.4 : dossier de capacité `socle-d-execution` (3.2) et ligne AUD-DEP-09 du suivi d'audit, faits à l'archivage.
