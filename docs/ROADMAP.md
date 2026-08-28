# Feuille de route — MINOS

Statut au **28 août 2026** : **C0 → M30 terminés et intégrés ; MINOS 1.0.1 publiée ; hardening #113–#260 intégré dans la ligne auditée ; 1.1.0-SNAPSHOT ouverte.**

Les versions historiques détaillées restent archivées sous [`history/reconciliations/`](history/reconciliations/). L'état opérationnel courant est dans [`STATUS.md`](STATUS.md).

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
| #98 Real OS worker sandbox | bubblewrap/cgroup + AppContainer/Job Object | ✅ implémenté et qualifié |
| #224–#248 | confinement provider/filesystem, provenance, egress, installateur, Windows non-admin | ✅ intégrés |
| #258 | politique sécurité, maintenance dépendances, CODEOWNERS futur, toolchain, couverture, séparation CI historique | ✅ intégrée dans `develop` |
| #260 | confinement fingerprint/secrets, quotas/couverture ciblée, simplification Post-228 et durcissements restants | ✅ intégrée dans `develop` |
| Réconciliation audit 28/08 | Docker A→B réel, I/O snapshot bornée, UTF-8 secrets strict, crypto hygiene, Gradle Dependabot, docs/topologie | 🔄 qualifiée par la PR qui contient ce document avant merge |

## Ligne de sécurité courante

### Sandbox et providers

Le worker distant/hostile conserve le niveau fort : process/memory/CPU/descendants et filesystem bytes+entries doivent satisfaire le contrat hostile, notamment un quota filesystem `OS_ENFORCED`. Tant que ce hard quota disque n'existe pas, ce chemin reste fail-closed.

Le provider local géré possède un contrat distinct : réseau OS-enforced, job boundary agrégé OS, timeout, quotas filesystem supervisés et reclamation scratch. `SUPERVISED_HARD_KILL` reste accepté uniquement pour ce niveau local et ne modifie jamais `supportsUntrustedCode()`.

Sous Windows, la JVM hôte n'est jamais traitée comme runtime provider ; toute racine non accordable sans élévation échoue avant l'exécution. Les mutations ACL AppContainer restent additives et ciblées sur une identité, jamais un remplacement intégral de DACL sous concurrence.

### Secrets, formats persistés et hosted control plane

Les secrets relatifs restent physiquement confinés à `MINOS_HOME`. Tous les fichiers secrets utilisent désormais un lecteur UTF-8 borné et strict. Les snapshots structurés v1/v2 imposent leur plafond **256 MiB pendant l'I/O** au moyen de flux d'entrée/sortie bornés, en complément des cardinalités déjà limitées.

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

**Post-228 Hardening Invariants** n'est plus une deuxième matrice Maven/Windows/JaCoCo. Il exécute les invariants statiques post-#228 sur Ubuntu ; les tests/builds/couvertures sont autoritairement dans PR Validation.

### Qualifications historiques

Les replays M15/M28 sont disponibles par `workflow_dispatch` dans `historical-qualification.yml`, séparés du chemin de PR courant.

### IntelliJ

Le plugin reste qualifié séparément sous **Java 21 / Gradle 9.6.1 / IntelliJ Platform 2026.1**, avec `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure`, **Plugin Verifier** et les tests Windows ownership. Dependabot couvre désormais `/minos-intellij` en plus de Maven et GitHub Actions.

### Docker release et upgrade réel

`docker-release-validation.yml` valide l'image provider-complete exacte sur Linux/amd64.

La transition réelle d'une version Docker MCP à une autre possède maintenant une qualification dédiée :

- workflow : `.github/workflows/docker-upgrade-qualification.yml` ;
- runner requis : Windows x64 auto-hébergé + Docker Desktop Linux containers, label `minos-docker` ;
- script : `scripts/ci/qualify-docker-upgrade.ps1` ;
- candidat A et candidat B construits depuis **deux commits/JAR distincts** ;
- vrai `prod-mcp-release.ps1`, vraies images provider-complete, vrai Compose et vrais providers ;
- projet Maven fixture enregistré et indexé avant l'upgrade ;
- handshake MCP avant et après ;
- persistance du `MINOS_HOME`, du projet et de l'index vérifiée ;
- un candidat suivant volontairement invalide doit échouer sans remplacer B, qui est re-handshaké après l'échec.

Le blocage historique `docker compose` interactif de #246 est ainsi couvert par un chemin de qualification reproductible au lieu d'une note de vérification manuelle ouverte.

## Supply-chain et toolchains

- cœur : Java 24 / Maven 3.9.x, wrapper Maven 3.9.16 avec checksum ;
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

La ligne de développement courante est **1.1.0-SNAPSHOT**. Aucune release 1.1.0 n'est publiée à ce jour.

## Suite

Aucun nouveau jalon fonctionnel n'est ouvert. La dette durable de sécurité reste le hard filesystem quota pour une exécution réellement hostile : une primitive qui refuse l'écriture avant dépassement reste nécessaire avant de pouvoir renforcer cette claim. Les autres travaux doivent préserver les gates exact-head et la topologie `main ⊆ develop`.
