# Feuille de route — MINOS

État produit revérifié le **7 octobre 2026** ; planification additionnelle du **4 au 5 octobre 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.1, 1.1.0 et 1.2.0 publiées ; hardening #113–#260, remédiation d'audit `develop` (PR #259, PR #272) et rafraîchissement Dependabot intégrés dans `main` (PR #284) ; 1.3.0-SNAPSHOT ouverte.**

Les versions historiques détaillées restent archivées sous [`history/reconciliations/`](history/reconciliations/). L'état opérationnel courant est dans [`STATUS.md`](STATUS.md).

## Chantier accepté — stockage et frontières hexagonales (2026-10-05)

Décisions acceptées, code non encore migré : consolidation dans `minos-storage`, port de lecture neutre et finalisation des frontières. Le plan est basé sur `develop`, qui contient déjà le bootstrap et les corrections A2/A3. [Backlog priorisé](roadmap/storage-hexagonal-2026-10/README.md) · [12 tâches exécutables](roadmap/storage-hexagonal-2026-10/TASKS.md) · [Démarrage Claude](roadmap/storage-hexagonal-2026-10/START-WITH-CLAUDE.md). Commencer par SH-01, préparer SH-11, puis SH-02/03.

## Audit 2026-10 — remédiation préparée (2026-10-06)

L'[audit d'octobre 2026](audit/README.md) (101 constats : 7 P1, 39 P2, 55 P3, aucun P0) a donné lieu à huit changements OpenSpec **préparés** (huit implémentés, voir plus bas), validés en mode strict : `fiabiliser-chaine-audit-tenant` (premier recommandé), `diagnostiquer-statut-mcp-et-erreurs`, `declarer-limites-impact-scip`, `isoler-recuperation-appcontainer-par-proprietaire`, `corriger-lancement-plugin-intellij-windows`, `durcir-configuration-postgresql-et-secrets`, `tolerer-repertoires-illisibles-a-la-decouverte` et `reconcilier-documentation-courante`. Ordre, dépendances et décisions à clarifier : [plan de remédiation](audit/plan-de-remediation.md) ; dossiers archivés le 2026-10-07 sous [`openspec/changes/archive/`](../openspec/changes/archive/) (tâches de vérification de CI cochées sur la preuve de la CI de #352 ; tâches conditionnelles laissées ouvertes), spécifications principales sous [`openspec/specs/`](../openspec/specs/). Deux sont **implémentés en local, PR ouverte, CI de la PR à observer** : `fiabiliser-chaine-audit-tenant` (MINOS-AUD-B01 à B04) et `diagnostiquer-statut-mcp-et-erreurs` (MINOS-AUD-C01, C02, C03, C05, C06, C16), voir [constats § 6](audit/constats.md#6-suivi-des-corrections). Le troisième, `declarer-limites-impact-scip` (MINOS-AUD-F01 lot 1, F02, F04), est **implémenté en local, non commité** ; la dérivation occurrence→relation reste une décision ouverte. Le quatrième, `isoler-recuperation-appcontainer-par-proprietaire` (MINOS-AUD-A01, A02), est **implémenté en local** ; A01 est prouvé sur le vrai lanceur Windows, la CI Linux et le job `windows-2022` restent à observer. Le cinquième, `corriger-lancement-plugin-intellij-windows` (MINOS-AUD-C04), est **implémenté en local** : prouvé par un vrai `.cmd` sous Windows via un harnais local, sans le build Gradle du plugin ni sa CI. Le sixième, `durcir-configuration-postgresql-et-secrets` (MINOS-AUD-B07, B08, B09, B13), est **implémenté en local** ; la CI Linux reste à observer. Le septième, `tolerer-repertoires-illisibles-a-la-decouverte` (MINOS-AUD-D01, D06, D10), est **implémenté en local** ; les tests d'illisibilité par permissions POSIX n'ont pas encore tourné sous Linux. Le huitième, `reconcilier-documentation-courante` (MINOS-AUD-G01, G09 à G19), est **implémenté en local** : documentation seule, sans fichier ADR modifié. Les auto-tests du garde de frontières (MINOS-AUD-E02) sont à traiter avant SH-02.

## Principes durables

- une capacité n'est acquise qu'avec une preuve reproductible ;
- facts, dérivations, heuristiques et observations partielles restent distincts ;
- les snapshots structurés restent autoritatifs ;
- les providers absents ou non qualifiés ne sont jamais extrapolés ;
- CLI, API, MCP, NEXUS et IntelliJ restent des surfaces au-dessus du métier ;
- remote/hosted/sandbox restent fail-closed lorsqu'une garantie n'est pas prouvée ;
- l'accès réseau d'un provider est `DENY` par défaut ;
- les exécutables qui constituent l'autorité de sandbox ne sont pas choisis dans un PATH utilisateur ;
- une release publiée est immuable ;
- le runtime packagé doit être testé, pas seulement le JAR ;
- une publication est bloquée par les vulnérabilités connues ou l'absence de qualification exacte du candidat ;
- l'indexation Windows ne requiert pas de droits administrateur ;
- `main` doit rester ancêtre de la ligne `develop` afin que les promotions ne réintroduisent pas une divergence d'historique.

## Trajectoire livrée

| Jalon | Résultat principal | État |
|---|---|---|
| C0 | cadrage fonctionnel et architectural | ✅ terminé |
| M0–M8 | discovery, indexation, symboles, relations, recherche, architecture, incrémental, impact | ✅ terminé |
| M9–M14 | CLI, MCP, API, multi-repo/Git, export NEXUS, installation PROD Windows | ✅ terminé |
| M15–M20 | reactor, scalabilité, provider platform, IntelliJ, advanced intelligence, semantic hybrid | ✅ terminé |
| M21–M28 | Production Integrity → Production Convergence | ✅ terminé |
| M29 | Autonomous Docker Runtime & Native Parity | ✅ M29 issue #107 CLOSED / M29 PR #108 intégrée |
| M30 | Advanced Installer, Ollama Docker & PostgreSQL/pgvector | ✅ M30 PR #110 + M30 promotion PR #111 |
| Hardening release/installer | supply-chain, Windows CI, sécurité release | ✅ #113 ; M28 Windows CI PR #117 |
| #98 Real OS worker sandbox | bubblewrap/cgroup + AppContainer/Job Object | ✅ primitives implémentées ; qualification code non fiable refusée par décision (ADR 0041), `remote index` fail-closed et diagnosticable (audit 2026-09, A1) |
| PR #227 | provider egress, provenance CommandLocator, reparse private storage et fallback confinement | ✅ intégrée |
| #224–#248 | confinement provider/filesystem, provenance, egress, installateur, Windows non-admin | ✅ intégrés |
| #258 | politique sécurité, maintenance dépendances, CODEOWNERS futur, toolchain, couverture, séparation CI historique | ✅ intégrée dans `develop` |
| #260 | confinement fingerprint/secrets, quotas/couverture ciblée, simplification Post-228 et durcissements restants | ✅ intégrée dans `develop` |
| Réconciliation audit 28 août 2026 | Docker A→B réel, I/O snapshot bornée, UTF-8 secrets strict, crypto hygiene, Gradle Dependabot, docs/topologie | ✅ intégrée (PR #260) |

## Ligne de sécurité courante

### Sandbox et providers

Le worker distant/hostile conserve le niveau fort : process/memory/CPU/descendants et filesystem bytes+entries doivent satisfaire le contrat hostile, notamment un quota filesystem `OS_ENFORCED`. Tant que ce hard quota disque n'existe pas, ce chemin reste fail-closed.

Le provider local géré possède un contrat distinct : réseau OS-enforced, job boundary agrégé OS, timeout, quotas filesystem supervisés et reclamation scratch. `SUPERVISED_HARD_KILL` reste accepté uniquement pour ce niveau local et ne modifie jamais `supportsUntrustedCode()`.

Sous Windows, la JVM hôte n'est jamais traitée comme runtime provider ; toute racine non accordable sans élévation échoue avant l'exécution. Les mutations ACL AppContainer restent additives et ciblées sur une identité, jamais un remplacement intégral de DACL sous concurrence.

### Secrets, formats persistés et hosted control plane

Les secrets relatifs restent physiquement confinés à `MINOS_HOME`. Tous les fichiers secrets utilisent désormais un lecteur UTF-8 borné et strict. Les snapshots structurés v1/v2/v3 imposent leur plafond **256 MiB pendant l'I/O** au moyen de flux d'entrée/sortie bornés, en complément des cardinalités déjà limitées.

Le hosted control plane conserve AES-256-GCM + AAD ; les buffers de clé maître et de clé dérivée temporaires sont nettoyés après usage.

### Git / PostgreSQL / source

Git distant conserve HTTPS, host/ref/SHA/path validation. PostgreSQL externe conserve sa politique TLS qualifiée et les requêtes préparées. Les lectures de source passent par le confinement objet `ConfinedFileOpener` et un plafond d'octets.

## Gates actuels

### PR Validation — autorité produit courante

Le workflow **PR Validation** porte :

- OSV ;
- Maven `clean verify` Linux + Windows ;
- PostgreSQL obligatoire Linux ;
- tests sandbox réels applicables ;
- JaCoCo ciblé Linux + Windows ;
- invariants architecture/supply-chain/docs ;
- contrôle que `origin/main` est ancêtre du HEAD candidat.

### Post-228 — invariants statiques ciblés

Le workflow **Post-228 Hardening Invariants** (`post-228-hardening.yml`) est **retiré** : ses invariants statiques vivent désormais dans le job `invariants` de `.github/workflows/pr-ci.yml` (Ubuntu, sans Maven), avec ceux des anciens workflows MND, MNE et post-MNE ; les tests/builds/couvertures sont autoritairement dans le job `verify` de PR Validation.

La preuve historique reste attachée aux SHA Post-#228 : candidat exact qualifié `1a551ff72f95db4e14e8a9597d897491b9c1589a`, merge `a042e97ac5e3e2ab7207fa603d85563ea1f71712`. Ces références décrivent la qualification #228 historique et ne réintroduisent aucune duplication Maven/Windows/JaCoCo dans le workflow courant.

### Qualifications historiques

Les replays M15/M28 sont disponibles par `workflow_dispatch` dans `historical-qualification.yml`, séparés du chemin de PR courant.

### IntelliJ

Le plugin reste qualifié séparément sous **Java 21 / Gradle 9.6.1 / IntelliJ Platform 2026.1**, avec `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, **Plugin Verifier** et les tests Windows ownership. Dependabot couvre désormais `/minos-intellij` en plus de Maven et GitHub Actions.

### Docker release et upgrade réel

`docker-release-validation.yml` valide l'image provider-complete exacte sur Linux/amd64.

La transition réelle d'une version Docker MCP à une autre possède maintenant une qualification dédiée :

- workflow manuel : `.github/workflows/docker-upgrade-qualification.yml` ; workflow automatique de promotion : `.github/workflows/release-promotion-gate.yml` (job `docker-upgrade-qualification`, puis `docker-upgrade-evidence` en dépendance) ;
- runner : **GitHub-hosted `ubuntu-24.04`** — aucun runner auto-hébergé, aucune machine personnelle, aucun repository d'infrastructure privé requis (dépôt public) ;
- script : `scripts/ci/qualify-docker-upgrade.ps1`, portable (pwsh), pilote `docker/scripts/mcp-lifecycle.ps1` (cœur portable extrait de `prod-mcp-release.ps1`, qui reste l'interface produit Windows) ;
- candidat A et candidat B construits depuis **deux commits/JAR distincts**, chacun avec son propre Dockerfile/Compose (`-SourceRoot` par candidat) ;
- vraies images provider-complete, vrai Compose et vrais providers ;
- projet Maven fixture enregistré et indexé avant l'upgrade ;
- handshake MCP avant et après ;
- persistance du `MINOS_HOME`, du projet et de l'index vérifiée ;
- un candidat suivant volontairement invalide doit échouer sans remplacer B, qui est re-handshaké après l'échec.

Le blocage historique `docker compose` interactif de #246 est ainsi couvert par un chemin de qualification reproductible au lieu d'une note de vérification manuelle ouverte.

## Supply-chain et toolchains

- cœur : Java 24 / Maven 3.9.x (plage `[3.9,4.0)`), wrapper Maven 3.10.0 avec checksum ; l'image Docker et les outils embarqués restent en Maven 3.9.16 ;
- IntelliJ : Java 21 / Gradle 9.6.1 / IntelliJ Platform 2026.1 ;
- Dependabot : Maven + Gradle `minos-intellij` + GitHub Actions ;
- workflows GitHub : actions épinglées par SHA ;
- Docker release : toolchains/images/checksums immuables lorsque cette garantie est revendiquée ;
- Docker MCP local : base Temurin 24 JRE désormais épinglée par digest et non plus par tag flottant.

## Release 1.0.1 — publiée

La **Release 1.0.1 est publiée** et reste immuable :

- tag/release : `v1.0.1` ;
- commit : `f762025d66e33c40324c811079f1527d122f90f9` ;
- URL : https://github.com/FTurleque/minos-code-intelligence/releases/tag/v1.0.1 ;
- **10 assets** publiés ;
- qualification incluant OSV, packaging et **Plugin Verifier**.

Les releases 1.1.0 et 1.2.0 ont été publiées depuis (voir [`STATUS.md`](STATUS.md)) ; la ligne de développement courante est la `<revision>` du `pom.xml` (**1.3.0-SNAPSHOT**).

## Suite

### Travaux ouverts en conception

| Sujet | Décision | État |
|---|---|---|
| Distribution auto-portante : les indexeurs sont livrés dans le paquet, plus aucune étape `tools install` à la charge de l'utilisateur | [ADR 0040](adr/0040-distribution-auto-portante-indexeurs-embarques.md) | implémentée (scip-java, scip-typescript ; qualification hors ligne physique à confirmer) |

La reprise d'une indexation interrompue ([ADR 0039](adr/0039-reprise-indexation-apres-interruption.md)) n'est plus en conception : l'ADR est acceptée et implémentée au sprint 2 (`IndexingResumePlanner`, `IndexingRun.Status.INTERRUPTED` dans `minos-engine`).

Aucun nouveau jalon fonctionnel livré n'est déclaré par l'étude d'octobre ; le programme proposé ci-dessous reste en conception. La dette durable de sécurité reste le hard filesystem quota pour une exécution réellement hostile : une primitive qui refuse l'écriture avant dépassement reste nécessaire avant de pouvoir renforcer cette claim. Les autres travaux doivent préserver les gates exact-head et la topologie `main ⊆ develop`.

## Programme proposé — amélioration retrieval et intelligence IDE (4 octobre 2026)

L'[étude MINOS / Semble / Serena](research/minos-evolution-2026-10/README.md) ouvre un programme de conception distinct des jalons historiques : **35 tâches TODO, U0–U8**, sept ADR Proposed (0048–0054), mesures préalables et options conditionnelles.

- [Roadmap intégrale, charges, dépendances et critères d'acceptation](research/minos-evolution-2026-10/ROADMAP.md)
- [Protocole de qualification](research/minos-evolution-2026-10/EVALUATION.md)
- [Reprise de développement avec Claude](research/minos-evolution-2026-10/CLAUDE-HANDOFF.md)

Base de l'étude : develop `c9a339088f81b6b31c65c7ad12bc718be2a98a7b`. Priorité : recherche et contexte ; embeddings CPU et bridge IntelliJ soumis à go/no-go. Les travaux d'audit ouverts restent suivis dans leurs fichiers ; aucune implémentation, qualification ni release nouvelle n'est déclarée par cette section.
