# Design

## Contexte

Motivation : voir `proposal.md` (Why). Changement d'outillage : trois gates Python existants, leurs auto-tests et leur documentation. Les trois constats ont la même cause : le garde lit le fichier autrement que l'outil qui l'exécute (`mvnw`, `sh`, Git). Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## Découpage

Un seul changement pour les trois constats : même thème (chaîne d'approvisionnement), même famille de gates (`scripts/quality`), aucun code Java ni Dockerfile modifié, même relecteur (`minos-security-reviewer`), et une seule CI d'environ 50 minutes au lieu de trois PR qui se mettent mutuellement en retard (« BEHIND »). Chaque constat a sa section de tâches et ses fichiers propres ; la section peut être sortie dans un changement à part sans toucher aux autres. Précédent : `consigner-les-pieges-des-montees-de-version` (AUD-DEP-11, AUD-DEP-12, deux capacités).

## État vérifié au HEAD

Relu au commit `4f4cac814` (PR #829), le 2026-11-27.

| Fait | Preuve |
|---|---|
| `PROPERTY = ^\s*(?P<key>[A-Za-z0-9_.-]+)\s*[=:]\s*(?P<value>.*?)\s*$` ; `properties_of` lit les deux wrappers par cette expression | `scripts/quality/check-toolchain-baseline.py:396`, `:401-410` |
| La règle 13 exige une seule `distributionSha256Sum` de 64 hexadécimaux, une `distributionUrl` https sous l'hôte, `only-script` | `check-toolchain-baseline.py:413-437` |
| `mvnw` : `while IFS="=" read -r key value; case "${key-}" in distributionUrl) … distributionSha256Sum) …` ; valeur réduite par `trim` ; contrôle seulement si la somme est non vide ; `distributionUrl` absente → arrêt | `mvnw:105-118`, `:226-247` |
| `mvnw.cmd` : `ConvertFrom-StringData` (espaces autour de `=` tolérés ; `:` non séparateur) | `mvnw.cmd:138-139` |
| Lignes au HEAD : `distributionSha256Sum=…` en début de ligne dans les deux wrappers | `.mvn/wrapper/maven-wrapper.properties:4` ; `minos-intellij/gradle/wrapper/gradle-wrapper.properties:3` |
| `DISARMING` : `\|\|\s*(?:true\|:)`, `\bset\s+\+[A-Za-z]*e`, `\btrap\b` | `scripts/quality/check-tools-manifest.py:353-357` |
| `names(text, token)` : chemin exact suivi d'une fin de nom ; `use_before_check_failures` l'applique aux commandes qui précèdent le contrôle, `curl` et `rm -f` exceptés | `check-tools-manifest.py:389-391`, `:471-480` |
| Le fichier contrôlé est lu dans la ligne `printf … \| sha256sum -c -` ; aucune comparaison avec la cible du `curl` | `check-tools-manifest.py:360-364`, `:450-457` |
| Six contrôles ; Node : `curl … -fsSLo "/tmp/${node_archive}"`, contrôle sur `"/tmp/${node_archive}"`, `cd /tmp`, `tar -xJf "${node_archive}"` ; NuGet : variable `"$nuget_package"` ; Maven : `curl --continue-at -` sur plusieurs lignes | `docker/Dockerfile.mcp.release:168-174`, `:184-188`, `:193-194`, `:201-202`, `:210-211`, `:245-247` |
| `pinned_eol_files` retient les fichiers dont l'attribut résolu contient `eol=lf` ou `eol=crlf` ; `i/crlf` ou `i/mixed` refusé pour eux seuls | `scripts/quality/check-posix-script-eol.py:130-133`, `:156-160` |
| Scripts POSIX et `mvnw` : `eol=lf` exigé par nom (`:149-152`) ; aucune exigence d'attribut pour les fichiers batch | `check-posix-script-eol.py:136-161` |
| `.gitattributes` : `minos-intellij/gradlew.bat text eol=crlf` (`:26`), `*.cmd text eol=crlf` (`:36`) | `.gitattributes` |
| Fichiers batch suivis : `mvnw.cmd`, `minos.cmd`, `minos-intellij/gradlew.bat`, tous `i/lf`, `attr/text eol=crlf` | `git ls-files --eol` |

### Mutations rejouées

Le 2026-11-27, dépôt extrait par `git archive HEAD` dans le scratchpad, une copie par mutation, gate lancé avec `--root <copie>`.

| Mutation | Gate | rc |
|---|---|---|
| Témoins (copie intacte) | `check-toolchain-baseline.py`, `check-tools-manifest.py` | 0, 0 |
| `distributionSha256Sum =…` (Maven) | `check-toolchain-baseline.py` | 0 |
| `distributionSha256Sum:…` (Maven) | idem | 0 |
| `  distributionSha256Sum=…` (Maven) | idem | 0 |
| Ligne de contrôle de `NODE_LINUX_SHA256` déplacée après `tar -xJf` | `check-tools-manifest.py` | 0 |
| `RUN set -eux; set +o errexit; \` en tête du `RUN` Maven | idem | 0 |

Analyseur de `mvnw` rejoué sur une ligne seule (boucle `IFS="="` copiée) : `distributionSha256Sum=abc` → `abc` ; `= abc` → `abc` ; `distributionSha256Sum =abc`, `distributionSha256Sum:abc`, `  distributionSha256Sum=abc` → non définie.

Non rejoué : le retrait du pin `eol=crlf` (exige un dépôt Git temporaire ; établi par l'audit, compteur 159 → 157) ; un contrôle sur un autre fichier (limite écrite dans la docstring du gate). `mvnw` et `mvnw.cmd` ne sont pas exécutés.

## Écarts

0. **AUD-DEP-16 — seule la somme ouvre un trou.** Une clé `distributionUrl` mal formée arrête `mvnw` (`mvnw:118`, `die`) : fail-closed. La règle couvre les deux clés par cohérence ; seule la somme laisse aujourd'hui `mvnw` sans vérification.
1. **AUD-DEP-16 — forme exacte.** L'action de l'audit (« exiger `^(clé)=valeur$` sans espace ») est plus stricte que `mvnw` sur la valeur : `distributionSha256Sum= abc` est lu par `mvnw` (`trim`) et par `mvnw.cmd`. La règle porte sur la clé (début de ligne, `=` aussitôt après) ; la valeur reste réduite de ses espaces puis contrôlée comme aujourd'hui.
2. **AUD-DEP-16 — forme refusée, pas ignorée.** Une ligne comme `distributionSha256Sum =…` ne doit pas être seulement non comptée (le gate dirait « aucune somme » sans montrer la ligne) : elle échoue en nommant la ligne et la forme attendue.
3. **AUD-DEP-17 — « refuser tout `cd` avant le contrôle ».** Proposé par la fiche, ce refus casserait un `RUN` légitime qui change de répertoire avant de télécharger. La comparaison par nom de base suffit pour la forme constatée ; elle est retenue seule (D2).
4. **AUD-DEP-18 — liste de l'audit.** La fiche cite `mvnw` et `*.sh` : ils sont déjà tenus (`eol=lf` exigé par nom, `check-posix-script-eol.py:149-152`). Le trou ne concerne que les fichiers batch.

## Décisions

### D1 — Clés des wrappers : forme de `mvnw` pour le wrapper Maven seul [Proposition, recommandation]

- (a) **Recommandé.** Pour `.mvn/wrapper/maven-wrapper.properties`, une ligne non commentaire qui contient `distributionUrl` ou `distributionSha256Sum` comme clé doit commencer par la clé suivie aussitôt de `=` ; sinon échec qui nomme la ligne. `gradle-wrapper.properties` reste lu en propriétés Java (`\:` et espaces tolérés), comme le lit Gradle.
- (b) Modifier `mvnw` pour tolérer ces formes : fichier du projet Maven Wrapper, réécrit à chaque montée.
- (c) Même forme stricte pour Gradle : refuserait des formes que Gradle lit.

### D2 — Archives de l'image : nom de base, `set +o errexit`, fichier du `curl` [Proposition, recommandation]

- (a) **Recommandé.** Trois ajouts à la règle existante : (1) avant le contrôle, une commande qui nomme le nom de base du fichier contrôlé (dernier segment après `/`, variable shell comprise, par exemple `"${node_archive}"`) **sans `/` juste avant lui** compte comme un usage, `curl` et `rm -f` exceptés ; (2) `set +o errexit` est un désarmement ; (3) le fichier contrôlé doit être une cible `-o`/`-Lo`/`-fsSLo` d'un `curl` du même `RUN`, au même texte. La condition « sans `/` » écarte les URL : la boucle `for maven_url in "…/${maven_archive}" …` (`docker/Dockerfile.mcp.release:165-167`) nomme le nom de base de l'archive Maven avant le contrôle, et la contre-vérification a montré qu'une comparaison sans cette condition la signale, ce qui rendrait le Dockerfile du HEAD rouge. Les six contrôles du HEAD doivent rester verts (vérifié en tâche 2.1).
- (b) Refuser tout `cd` avant le contrôle (action de l'audit) : écart 3.
- (c) Statu quo, limites écrites : la docstring les écrit déjà, le constat demande de les fermer.

### D3 — Fichiers batch : épinglage `eol=crlf` exigé par extension [Proposition, recommandation]

- (a) **Recommandé.** Tout fichier suivi qui finit par `.cmd` ou `.bat` (insensible à la casse, hors `fixtures/`) doit avoir l'attribut résolu `eol=crlf` ; sinon échec qui nomme le fichier et la règle attendue (`*.cmd text eol=crlf`). Dérivé de l'index, pas d'une liste fermée : un nouveau fichier batch entre d'office.
- (b) Liste fermée de fichiers (action de l'audit) : un fichier batch ajouté échappe à la règle.
- (c) Étendre à `*.ps1` : PowerShell lit LF et CRLF ; le pin sert à l'égalité de l'arbre, déjà tenue pour les fichiers épinglés. Laissé hors règle.

### Pourquoi des exigences ajoutées plutôt que modifiées

Les trois exigences complétées sont en vigueur : « Chaque wrapper de build exige l'empreinte de sa distribution » (`socle-d-execution`, 440 caractères), « Chaque archive épinglée de l'image est vérifiée avant usage » (423) et « Un fichier à fin de ligne épinglée est enregistré sans retour chariot » (`controles-de-fusion`, 439). Leur ajouter ces règles en `MODIFIED` dépasserait la limite de 500 caractères de `validate --strict`. Chaque exigence ajoutée précise la forme lue par l'outil qui exécute le fichier, sans répéter l'exigence complétée.

## Vérification

- Rouge : chaque mutation du tableau donne rc=0 au HEAD ; les cas ajoutés aux trois auto-tests échouent avant correction.
- Correction : les trois gates verts sur le dépôt ; chaque mutation donne rc=1 et nomme le fichier, la ligne ou l'`ARG`.
- `python .claude/scripts/run_gates.py` vert ; `check-ci-wiring.py` et `check-single-execution.py` inchangés (aucune étape ajoutée).

## Documentation durable à mettre à jour

`docs/developer/quality-gates.md` (paragraphes des trois gates), docstrings (anglais), ADR GEN-0077 et BE-0065 (« Compliance and Enforcement », après fusion), dossiers `socle-d-execution.md` et `controles-de-fusion.md` (à l'archivage).

## Traçabilité

| Constat | Exigence | Tâches | Preuve attendue |
|---|---|---|---|
| AUD-DEP-16 | socle-d-execution › La somme du wrapper Maven est écrite sous la forme que lit `mvnw` | 1.1 à 1.4 | trois mutations rouges ; auto-test vert ; gate vert au HEAD |
| AUD-DEP-17 | socle-d-execution › Le contrôle d'une archive de l'image porte sur le fichier téléchargé avant tout usage | 2.1 à 2.4 | mutations rouges ; six contrôles reconnus au HEAD |
| AUD-DEP-18 | controles-de-fusion › Les fichiers batch suivis ont leur fin de ligne épinglée | 3.1 à 3.4 | `--self-test` rouge puis vert ; gate vert au HEAD |
