# Design

## Contexte

Motivation : voir `proposal.md` (Why). C'est un changement d'outillage et de documentation : un skill, deux gates Python existants et leurs auto-tests, quatre pages courantes et un écart d'ADR changent. Le design est réduit au contexte, à l'état vérifié, aux écarts, aux décisions, à la vérification et à la traçabilité.

## État vérifié au HEAD

Relu au commit `75e10c681` (PR #819), le 2026-11-25.

### AUD-DEP-11 — pièges des PR Dependabot

| Fait | Preuve |
|---|---|
| La mémoire d'agent, versionnée, demande de relever `UBUNTU_APT_SNAPSHOT` avec chaque image Temurin et de remettre `mvnw.cmd` en LF quand Dependabot le réécrit en CRLF | `.claude/agent-memory/archgate-developer/project_audit_2026_11_15_clos.md:46-49` |
| Le skill : étape 4 « Images » sans l'instantané apt ; étape 5 « Gradle wrapper » qui ne cite que `gradlew.bat` ; aucune mention de `mvnw.cmd` ni du wrapper Maven | `.claude/skills/minos-dependency-update/SKILL.md:14-15` |
| L'instantané est un `ARG` daté ; trois autres endroits recopient la date : le test Java, la page utilisateur, l'inventaire de l'image (par l'`ARG`) | `docker/Dockerfile.mcp.release:79`, `:123`, `:345` ; `M29DockerAdministrationContractTest.java:151` ; `docs/user/docker-runtime.md:165` |
| Les deux dernières montées de l'instantané ont accompagné une image | commits `18a0a919a` (« avancer l'instantané apt au 2026-11-20 pour les images »), `3e376c7ea` (« à la date de l'image de base ») |
| Le tag Temurin (`21.0.13.1_2-jdk-noble`) et son digest ne portent aucune date lisible hors ligne | `docker/Dockerfile.mcp.release:26`, `:37`, `:73` |
| `.gitattributes` épingle déjà `*.cmd text eol=crlf` (`:36`), `minos-intellij/gradlew.bat text eol=crlf` (`:26`), `*.ps1*` en CRLF (`:5-7`) | `.gitattributes:5-7`, `:26`, `:36` |
| Au HEAD : `mvnw.cmd`, `minos.cmd`, `gradlew.bat` sont `i/lf w/crlf attr/text eol=crlf` ; 126 fichiers ont `eol=crlf`, 33 `eol=lf` ; aucun fichier n'est `i/crlf` ; un `i/mixed` sans attribut (`docs/project/history/milestones/m0/RETOURS_MODELE_DOMAINE.md`) | `git ls-files --eol` |
| Le piège s'est produit : Dependabot a enregistré `mvnw.cmd` en CRLF, contenu identique ; le correctif remet 189 lignes en LF | commits `206b59bd0`, `b2d29c6af` |
| `check-posix-script-eol.py` contrôle les `*.sh`, les shebangs POSIX et `.mvn/wrapper/maven-wrapper.properties` : attribut `eol=lf` et `i/lf`. Il ignore les fichiers batch et PowerShell | `scripts/quality/check-posix-script-eol.py:41`, `:123-140` |
| Son auto-test construit de vrais dépôts Git temporaires et sait enregistrer un fichier avant son attribut (cas « script recorded with CRLF ») | `check-posix-script-eol.py:148-178`, `:193-219` |
| Le gate et son `--self-test` sont lancés par le job `invariants` | `.github/workflows/pr-ci.yml:156`, `:159` |

### AUD-DEP-12 — version de Maven dans la documentation

| Mention | Nature | Preuve |
|---|---|---|
| « Maven Wrapper : Maven 3.10.0 avec checksum SHA-256 » | courant | `docs/project/governance/STATUS.md:303` |
| « wrapper Maven 3.10.0 avec checksum » | courant | `docs/project/governance/ROADMAP.md:131` |
| « wrapper du dépôt (3.10.0, plage Enforcer `[3.9,4.0)`) » | courant (prérequis de la méthode d'audit) | `docs/developer/quality/code-audit.md:23` |
| « distinct du checksum du Maven Wrapper, qui pointe sur Maven 3.10.0 » | courant (page utilisateur) | `docs/user/docker-runtime.md:166` |
| « le wrapper télécharge Maven 3.10.0 », écart relevé le 2026-10-23, preuve `maven-wrapper.properties:3` | ADR `Accepted`, section « Écarts constatés » | `docs/architecture/adr/GEN-0077-socle-d-execution-java-21-et-maven.md:111-113` |
| « wrapper 3.10.0 », « doit afficher Maven 3.10.0 et Java 24.0.1 » | relevé daté du 2026-10-08 (commit `902e2bfa`) | `docs/developer/quality/code-audit-couverture.md:11-15`, `:362` |
| « le wrapper télécharge Maven 3.10.0 » | ADR archivés | `docs/architecture/adr/archive/0004-…:175`, `archive/GEN-0067-…:97` |
| « Maven 3.10, celui du wrapper » | courant, au mineur près, exact | `docs/developer/supply-chain.md:280` |
| Maven 3.9.16 de l'image : `docker-runtime.md:139`, `:142`, `:166`, `:209` ; STATUS:303 ; ROADMAP:131 | courant, exact | `docker/Dockerfile.mcp.release:82` |
| Wrapper réel : Maven 3.10.2 | — | `.mvn/wrapper/maven-wrapper.properties:3` |
| `check-current-docs.py` et `product-facts.py --check` n'ont aucune table de faits pour la version du wrapper | — | `scripts/docs/check-current-docs.py` (aucune lecture du fichier) ; `scripts/docs/product-facts.py:247-259` |
| `check-toolchain-baseline.py` compare déjà des documents à une référence (Gradle de la politique et d'`AGENTS.md`, règle 2) et lit les Dockerfiles (règle 6) | `scripts/quality/check-toolchain-baseline.py:12-14`, `:117-140` |

### Mutations rejouées

Le 2026-11-25, sur des copies du dépôt dans le scratchpad :

| Mutation | Résultat |
|---|---|
| `UBUNTU_APT_SNAPSHOT=20261003T000000Z` (copie par `git archive`) | `check-image-pins`, `check-tools-manifest`, `check-workflow-pins`, `check-toolchain-baseline`, `check-current-docs` : 0. `M29…Test:151` divergerait par lecture (test non exécuté) |
| `mvnw.cmd` enregistré en CRLF (clone local, blob posé par `git hash-object --no-filters` puis `git update-index --cacheinfo`) : `git ls-files --eol` montre `i/crlf w/crlf attr/text eol=crlf` | `check-posix-script-eol.py --root <clone>` : 0 (« POSIX scripts checked=17 ») |

## Écarts

| Écart | Source A | Source B | Suite proposée |
|---|---|---|---|
| L'audit range code-audit-couverture.md:15 parmi les mentions à corriger ; c'est un relevé daté de l'audit du 2026-10-08, exact à sa date | `findings.json`, AUD-DEP-12, preuve | `code-audit-couverture.md:11-15` (commit audité `902e2bfa`) | non modifié (hors périmètre) |
| L'action d'AUD-DEP-12 (« écrire 3.10.x ») dérivera de nouveau à Maven 3.11, et aucun gate ne la tient | `findings.json`, AUD-DEP-12, action | dérive constatée deux fois (3.10.1, puis 3.10.2) | D2 : règle de gate et renvoi au fichier de propriétés |
| L'option de l'audit « `i/lf` de `mvnw.cmd` » vise un seul fichier ; le même mécanisme vaut pour `gradlew.bat`, `minos.cmd` et les 126 fichiers en `eol=crlf`, dont les lanceurs PowerShell comparés octet pour octet | `findings.json`, AUD-DEP-11, action | `.gitattributes:1-7`, `:26`, `:36` | D1 : règle générale sur les fichiers à `eol` épinglé |
| Le skill ne nomme pas non plus le wrapper Maven ni ses deux fichiers recopiés (`docker-runtime.md:165`, `M29…Test:151`) que la montée de l'instantané doit déplacer | `.claude/skills/minos-dependency-update/SKILL.md:14-15` | `docs/user/docker-runtime.md:165` | étape 4 du skill |
| STATUS.md:303 et ROADMAP.md:131 disent « avec checksum » sans garde | `STATUS.md:303` | AUD-DEP-09 | rendu vrai par `exiger-l-empreinte-des-distributions-des-wrappers` ; ce changement ne touche que la version |

## Exigences couvertes

- controles-de-fusion › Un fichier à fin de ligne épinglée est enregistré sans retour chariot — D1.
- socle-d-execution › Les versions de Maven écrites dans la documentation courante sont celles du wrapper ou de l'image — D2.

## Décisions

### D1 — `mvnw.cmd` : règle de gate générale dans `check-posix-script-eol.py`, plus le skill [Proposition, recommandation]

Options :

- (a) skill seul (action principale de l'audit) : le piège reste découvert tard, par le candidat Windows ;
- (b) ajouter `mvnw.cmd` par son nom au gate, comme le fichier de propriétés Maven : couvre ce cas, pas `gradlew.bat` ni `minos.cmd` ;
- (c) **règle générale** (recommandée) : tout fichier suivi dont l'attribut résolu porte un jeton `eol=lf` ou `eol=crlf` doit être `i/lf`. Le message nomme le fichier et la commande `git add --renormalize -- <fichier>`. Le gate lit déjà `git ls-files --eol` (`eol_entries`, `:85-98`) : la règle ne coûte qu'une boucle. Au HEAD, elle passe (aucun `i/crlf`). Le fichier `i/mixed` de `docs/project/history/` n'a pas d'attribut et reste hors de la règle.

Le nom du gate (« POSIX script EOL ») devient plus étroit que son contenu : la docstring le dit, le nom du fichier et le libellé `POSIX SCRIPT EOL CHECK` restent, car `pr-ci.yml:156`, `:159` et le dossier de capacité `controles-de-fusion.md:80-81` les citent. Le skill, étape 5, dit la règle et la commande de correction.

### D2 — Versions de Maven de la documentation courante : règle de `check-toolchain-baseline.py`, et documents sans le correctif du wrapper [Proposition, recommandation]

Options :

- (a) écrire « 3.10.x » (action de l'audit) : dérive à 3.11, aucun garde ;
- (b) faire écrire la version exacte partout et la comparer au wrapper, comme Gradle (règle 2) : chaque montée Dependabot du wrapper resterait rouge jusqu'à la retouche de cinq documents ;
- (c) **recommandée** : les documents courants renvoient au wrapper sans recopier son correctif (« le wrapper Maven, version de `.mvn/wrapper/maven-wrapper.properties` »). Une règle de `check-toolchain-baseline.py` parcourt une liste fermée de documents courants (`STATUS.md`, `ROADMAP.md`, `TOOLCHAIN_POLICY.md`, `docs/developer/quality/code-audit.md`, `docs/user/docker-runtime.md`, `docs/developer/supply-chain.md`). Elle refuse toute version `3.N.P` écrite comme version de Maven (`Maven 3.N.P`, `apache-maven-3.N.P`, `wrapper … 3.N.P` sur la même ligne) qui n'est ni celle du wrapper ni `MAVEN_VERSION` du Dockerfile de release. Le message nomme le fichier, la ligne et les deux références. Une montée du wrapper ne casse rien tant que les documents ne recopient pas l'ancienne version ; une version périmée réintroduite échoue.

Hôte : `check-toolchain-baseline.py`, qui lit déjà le wrapper Gradle, les documents de la politique et les Dockerfiles, et que `exiger-l-empreinte-des-distributions-des-wrappers` étend au wrapper Maven. Ce changement est donc fusionné après lui. `check-current-docs.py` est écarté : il garde des affirmations littérales d'état produit, pas des versions de toolchain.

ADR GEN-0077 : la section « Écarts constatés » reçoit une note datée (« au 2026-11-25 le wrapper télécharge Maven 3.10.2 ; les mentions courantes sont gardées par la règle N de `check-toolchain-baseline.py` »). La phrase du 2026-10-23 et la décision ne sont pas réécrites.

### D3 — Instantané apt : skill seul [Proposition, recommandation]

Comparer `UBUNTU_APT_SNAPSHOT` à la date de l'image demanderait de lire la date de création de l'image, que ni le tag ni le digest ne donnent hors ligne : il faudrait un accès au registre, que le job `invariants` n'a pas et ne doit pas avoir pour un gate statique. L'installation apt de l'image échoue déjà fermée sur un instantané trop ancien (`docker/Dockerfile.mcp.release:122-141`), et `M29…Test:151` force à déplacer la date avec l'`ARG`. Le skill porte la règle, et la liste des endroits à déplacer ensemble. Option écartée : un gate qui lirait la date dans le message de commit Dependabot (non déterministe).

## Vérification

- Rouge D1 : un cas du `--self-test` enregistre `mvnw.cmd` en CRLF avec `*.cmd text eol=crlf`, puis un `x.ps1` de même ; ils échouent avant la règle et réussissent ensuite à constater le refus. Sur le clone de l'état vérifié, le gate rend 1.
- Rouge D2 : sur une copie de la documentation au HEAD, la règle échoue sur les quatre mentions « 3.10.0 » ; après réécriture des documents, elle passe.
- Windows et Linux : D1 lit Git, pas le disque ; D2 lit du texte UTF-8 avec `splitlines`. Bibliothèque standard.

## Documentation durable à mettre à jour

- Skill `minos-dependency-update`, étapes 4 et 5.
- `STATUS.md:303`, `ROADMAP.md:131`, `docs/developer/quality/code-audit.md:23`, `docs/user/docker-runtime.md:166` : renvoi au wrapper sans correctif recopié.
- `docs/developer/TOOLCHAIN_POLICY.md`, section Maven : une phrase sur la règle D2.
- ADR GEN-0077 : note datée de l'écart.
- Dossiers `docs/architecture/diagrams/capacites/controles-de-fusion.md` (`:80-81`) et `socle-d-execution.md` : traçabilité des deux exigences ajoutées, à l'archivage.

## Traçabilité

| Constat | Exigence | Décision | Tâches | Preuve exécutée (2026-11-25, poste Windows) | Preuve manquante |
|---|---|---|---|---|---|
| AUD-DEP-11 | controles-de-fusion › Un fichier à fin de ligne épinglée est enregistré sans retour chariot | D1 | 1.1-1.4 | clone avec `mvnw.cmd` `i/crlf` : gate vert (trou reproduit) ; `git ls-files --eol` : aucun `i/crlf` au HEAD | rouge et vert du nouveau cas ; CI |
| AUD-DEP-11 | — (skill) | D3 | 3.1 | instantané au 2026-10-03 : cinq gates verts | construction de l'image avec un instantané ancien (non lancée) |
| AUD-DEP-12 | socle-d-execution › Les versions de Maven écrites dans la documentation courante sont celles du wrapper ou de l'image | D2 | 2.1-2.4, 3.2-3.3 | recensement `git grep` des mentions ; aucune table de faits existante | rouge et vert de la règle |
