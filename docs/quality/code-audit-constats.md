# Audit de code outillé : constats

Les § 1 à § 4 consignent la première exécution (7 octobre 2026, SpotBugs et PIT sur un périmètre restreint). Les § 5 à § 8 consignent l'audit outillé complet du **8 octobre 2026** (cinq outils, tout le périmètre applicable) : constats qualifiés, revérification des constats antérieurs, plan de correction et changements OpenSpec. Couverture, exécutions et reprise : [code-audit-couverture.md](code-audit-couverture.md).

Constats relevés le **7 octobre 2026** en exécutant SpotBugs 4.10.4 et PIT 1.30.0 (voir [code-audit.md](code-audit.md) pour la méthode, les commandes et l'interprétation). Ce document **consigne** : rien n'a été corrigé ni exclu. Chaque lot doit faire l'objet d'un changement OpenSpec séparé, qui qualifie d'abord chaque constat (défaut confirmé, faux positif, mutant équivalent, décision).

Tous les constats sont **à qualifier** : une alerte SpotBugs est un motif de bug fréquent, pas une preuve, et un mutant survivant peut être équivalent.

## 1. Alertes SpotBugs

Commande : `-Paudit-spotbugs`, effort `Max`, seuil `Medium`, 14 modules. Résultat : **197 alertes de priorité `Medium`, aucune `High`**. Le contrôle bloquant `spotbugs:check` est donc rouge à `failThreshold=Medium` et vert à `failThreshold=High`.

| Module | Alertes |
|---|---|
| `minos-api` | 57 |
| `minos-storage-local` | 42 |
| `minos-engine` | 39 |
| `minos-runtime-local` | 21 |
| `minos-provider-scip` | 14 |
| `minos-application` | 5 |
| `minos-cli` | 5 |
| `minos-integration-git` | 4 |
| `minos-nexus` | 4 |
| `minos-storage-postgresql` | 4 |
| `minos-domain` | 1 |
| `minos-mcp` | 1 |
| `minos-bootstrap` | 0 |
| `minos-app` | 0 |

| Motif | Nb | Lecture |
|---|---|---|
| `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` | 104 / 5 | un accesseur ou un constructeur expose un objet modifiable (liste, tableau, tampon). 57 sont dans `minos-api` (DTO de l'API Java publique), 30 dans `minos-engine`, 7 dans `minos-storage-local`. Convention de code plutôt que 109 défauts : à traiter par un choix global (copies défensives, collections immuables, ou décision d'accepter pour des enregistrements internes) |
| `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` | 70 | une valeur de retour qui peut être `null` est déréférencée. 35 dans `minos-storage-local`, 12 dans `minos-runtime-local`, 10 dans `minos-provider-scip`, 8 dans `minos-engine`. Plusieurs renvoient sans doute à des API du JDK (par exemple `Path.getFileName()`, `getParent()`) dont le `null` est impossible dans le contexte : à qualifier avant de corriger |
| `DMI_HARDCODED_ABSOLUTE_FILENAME` | 6 | chemin absolu codé en dur : `CommandLocator` (4, ligne 25), `LinuxBubblewrapWorkerSandboxBackend` (ligne 53), `LinuxCgroupJob` (ligne 39). Probablement voulu (chemins système Linux) : faux positifs plausibles, à démontrer |
| `USO_UNSAFE_METHOD_SYNCHRONIZATION` | 2 | `LazyApplication` (ligne 174), `LinuxCgroupJob` (ligne 764) |
| `IS2_INCONSISTENT_SYNC` | 1 | `LazyApplication` (ligne 83) : un champ accédé avec et sans verrou. **À regarder en premier** avec la ligne 174 de la même classe (la classe compose les deux motifs de synchronisation) |
| `OS_OPEN_STREAM` | 2 | flux possiblement non fermé : `BoundedFileDigest` (ligne 37), `ScipSymbolSnapshotImporter` (ligne 148) |
| `CT_CONSTRUCTOR_THROW` | 2 | constructeur qui peut lever après avoir pris des ressources : `RemoteCloneBudget` (ligne 25), `AbstractScipProcessPlanFactory` (ligne 28) |
| `ENV_USE_PROPERTY_INSTEAD_OF_ENV` | 2 | lecture d'une propriété système là où une variable d'environnement est attendue : `ManagedScipProviderRuntimeManager` (ligne 238), `ScipJavaProcessPlanFactory` (ligne 296) |
| `REC_CATCH_EXCEPTION` | 2 | `catch (Exception)` trop large : `DistributedArtifactBundleStore` (ligne 473), `ProcessIndexerExecutor` (ligne 161) |
| `VA_FORMAT_STRING_USES_NEWLINE` | 1 | `RelationshipCommand` (ligne 81) : `\n` dans une chaîne de format au lieu de `%n` |

Rapports : `<module>/target/spotbugsXml.xml` (`minos-app` : `target/spotbugsXml.xml`). Chaque alerte porte un `instanceHash` stable pour la citer dans un changement.

## 2. Mutants survivants du périmètre initial

Périmètre : `HostedAuditChain`, `HostedAuthorizationService`, `HostedPermission` de `minos-engine`, tests `com.minos.hosted.*`. **73 mutants : 58 tués, 12 survivants, 3 sans couverture** ; force des tests 83 %, couverture de lignes des classes mutées 93 %. `HostedPermission` (une énumération) ne génère aucun mutant.

`HostedAuditChain` (17 tués, 9 survivants, 1 sans couverture) :

| Méthode et ligne | Mutation | Ce que les tests ne contrôlent pas |
|---|---|---|
| `verify`, ligne 94 | suppression de la condition `chaining() != CHAINED` | que la vérification refuse un événement non chaîné |
| `verify`, ligne 97 | suppression de la condition `sequence() != expectedSequence` | que la vérification refuse une séquence non contiguë |
| `verify`, ligne 100 | suppression de la condition sur le tenant | que la vérification refuse un événement d'un autre tenant |
| `verify`, ligne 103 | suppression de la condition `previous.equals(previousHash)` | que la vérification refuse un lien `previousHash` rompu |
| `verify`, ligne 110 (2 mutants) | suppression de l'une des deux conditions de la garde finale | que la vérification refuse une ancre de séquence qui ne correspond pas au dernier événement |
| `verify`, ligne 90 | suppression de la condition `isEmpty()` du calcul de la séquence attendue | la séquence de départ d'une chaîne non vide |
| `verify`, ligne 91 | `MathMutator` sur `auditSequence() + 1` | aucun test n'exécute cette branche (chaîne vide ; sans couverture) |
| `append`, ligne 45 (2 mutants) | frontière de comparaison, et suppression de la condition, sur la capacité maximale d'événements | que le refus se produit exactement à `MAX_AUDIT_EVENTS`, ni avant ni après |

**À regarder en premier : les lignes 94 à 110 de `verify`, le code qui détecte la falsification de la chaîne d'audit.** `authenticate(event)` (ligne 106) pourrait rendre certaines gardes redondantes pour certaines falsifications (mutant équivalent possible) : à qualifier garde par garde.

`HostedAuthorizationService` (41 tués, 3 survivants, 2 sans couverture) :

| Méthode et ligne | Mutation | Ce que les tests ne contrôlent pas |
|---|---|---|
| `authorizeRead`, ligne 54 | suppression d'une condition | la garde `role().allows(permission)` de la voie de lecture : aucun test ne distingue sa suppression (à qualifier : le refus par rôle est une règle d'autorisation, donc prioritaire avec la chaîne d'audit) |
| `admitsChainedDenial` (lambda), ligne 171 | frontière de comparaison (`>` contre le quart de la limite d'octets) | la limite exacte d'octets au-delà de laquelle un refus n'est plus chaîné |
| `recordDenial`, ligne 147 | suppression d'un appel `void` (l'appel `LOGGER.log` du cas de conflit de version) | que l'avertissement est journalisé : survivant de faible valeur, probablement à accepter |
| lambdas des lignes 53 et 221 | valeur de retour `null` | sans couverture : branches d'erreur non exécutées |

Rapport : `minos-engine/target/pit-reports/mutations.xml` (statuts `SURVIVED` et `NO_COVERAGE`). Ces lignes sont celles de la version du dépôt au 7 octobre 2026 ; recalculez-les en relançant PIT avant de les citer.

## 3. Fragilités découvertes dans les tests et l'outillage

- **`HostedProductionBoundaryTest.facadeStaysThinAndCohesiveServicesAreRealSourceFiles`** lit `minos-engine/src/main/java/…` en chemin relatif : il ne passe que si le répertoire courant est la racine du dépôt. Surefire le garantit par `workingDirectory` ; PIT (qui n'a pas ce paramètre) fait échouer le test hors mutation et refuse de continuer. Contournement actuel : `-Duser.dir` dans la configuration PIT. Correction propre : localiser le dépôt sans dépendre du répertoire courant (même lecture de sources que d'autres tests de frontière, à rechercher avec `grep -rn 'Path.of("minos-'`). Le même défaut gênera tout autre outil qui exécute les tests hors de Surefire.
- **Propriétés du POM `minos-app`.** `<directory>` pointe vers le `target/` de la racine : les rapports de `minos-app` s'y écrivent et `clean` les efface. Sans conséquence ici (0 alerte), mais à connaître avant d'agréger des rapports.
- **`total_classes="0"`** dans le résumé du XML SpotBugs 4.10.4, alors que les alertes sont rapportées : ne pas s'en servir pour compter les classes analysées.

## 4. Pour traiter ces constats

Lots proposés, par ordre de valeur et de risque :

1. `HostedAuditChain.verify` : tests de falsification (événement non chaîné, séquence non contiguë, autre tenant, lien rompu, ancre) avant toute modification du code ; relancer PIT sur la classe et constater que les survivants passent à `KILLED` ou sont qualifiés équivalents.
2. `LazyApplication` : `IS2_INCONSISTENT_SYNC` et `USO_UNSAFE_METHOD_SYNCHRONIZATION`.
3. Ressources et exceptions : `OS_OPEN_STREAM`, `CT_CONSTRUCTOR_THROW`, `REC_CATCH_EXCEPTION`, `VA_FORMAT_STRING_USES_NEWLINE`, `ENV_USE_PROPERTY_INSTEAD_OF_ENV`.
4. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` : qualifier par module (stockage local d'abord), probablement en partie des faux positifs JDK.
5. `EI_EXPOSE_REP` : décision de conception unique pour `minos-api` et `minos-engine` (copies défensives ou immuabilité), éventuellement par ADR, avant tout correctif.
6. Rendre `HostedProductionBoundaryTest` indépendant du répertoire de travail, puis retirer `-Duser.dir` de la configuration PIT.

Avant d'imposer `spotbugs:check` ou un seuil PIT en CI, chiffrer une base après ces lots.

## 5. Audit outillé du 8 octobre 2026 : constats qualifiés

Identifiants stables `MINOS-AUD-H<nn>` (H : audit outillé), dans la continuité des familles A à G de l'[audit d'octobre](../audit/constats.md). Qualifications : **défaut confirmé**, **risque**, **faiblesse de test**, **faux positif**, **décision ouverte**. Niveau de preuve : **E** exécuté pendant l'audit, **L** lu dans le code par l'auteur, **A** relu par une analyse déléguée et non réexécuté. Priorités : P1 (avant la prochaine release), P2 (prochain lot), P3 (à planifier). Les alertes d'une même cause sont regroupées en une fiche.

### Registre

| ID | Titre | Qualification | Prio | Preuve | Changement OpenSpec |
|---|---|---|---|---|---|
| H01 | Les rapports SpotBugs du plugin Maven ne prouvent pas le périmètre analysé | Défaut confirmé (outillage) | P2 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H02 | Dependency-Check rapproche deux modules MINOS de CPE tiers (43 CVE du serveur PostgreSQL) | Faux positif | P3 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H03 | Quatre CVE dans le client HTTP embarqué par Testcontainers (tests seulement) | Risque | P3 | E | `aligner-dependances-de-test-testcontainers` |
| H04 | La clé NVD de l'environnement est refusée ; Dependency-Check 13.0.0 ne rafraîchit pas sans clé | Décision ouverte (environnement) | P2 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H05 | Testcontainers mélange deux versions majeures (cœur 2.0.5, modules 1.21.4) ; `slf4j-api` ne converge pas | Risque | P3 | E | `aligner-dependances-de-test-testcontainers` |
| H06 | Gitleaks n'est ni configuré ni exécuté par la CI ; la règle par défaut ne produit que des faux positifs | **Corrigé** le 2026-10-09 (`.gitleaks.toml`, `secret-scan.yml`) | P3 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H07 | Faiblesses de tests révélées par PIT (chaîne d'audit et autorisation en P1) | Faiblesse de test ; **P1 corrigés** (`5b9ef15b`) | P1 à P3 | E | `renforcer-tests-revelees-par-mutation` |
| H08 | Usages de modules non déclarés ; surfaces qui consomment directement des ports du moteur | **Décidé** le 2026-10-09 (ADR 0058 : arêtes déclarées, règle stricte obligatoire) | P3 | E | `etendre-audit-outille-a-tout-le-perimetre` (mesure) ; décision par ADR |
| H09 | Le plugin IntelliJ échappe aux contrôles locaux (hors réacteur, sans Gradle, sorties périmées) | Faiblesse (couverture) | P2 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H10 | La documentation du plugin indique un chemin `…\MINOS\bin\minos.cmd` qui n'existe pas | Défaut confirmé (documentation), **corrigé** (`93093b7a`) | P2 | L | `corriger-documentation-plugin-et-suivi-audit` |
| H11 | Le suivi de l'audit d'octobre décrit comme « en local / PR ouverte » des correctifs fusionnés | Dérive documentaire | P3 | L | `corriger-documentation-plugin-et-suivi-audit` |
| H12 | Aucun test d'arrêt brutal entre écriture temporaire et renommage d'un snapshot, de pointeur actif tronqué, ni de migration PostgreSQL d'une base peuplée | Faiblesse de test | P2 | A | `couvrir-reprise-apres-interruption-du-stockage` |
| H13 | Les pointeurs temporaires `.active-*.tmp` laissés par un arrêt brutal ne sont jamais récupérés | Risque | P3 | A | `couvrir-reprise-apres-interruption-du-stockage` |
| H14 | Un refus dont l'identifiant de requête est invalide est rejeté avant l'authentification, sans trace | Défaut confirmé (test rouge), **corrigé** | P3 | E | `renforcer-tests-revelees-par-mutation` (décision) |
| H15 | `HostedAuditDelivery` n'attrape que `IOException` | Défaut confirmé (test rouge), **corrigé** | P3 | E | `renforcer-tests-revelees-par-mutation` (décision) |
| H16 | Les limitations d'une requête de relations sont lues sur un second chargement du snapshot actif | Risque | P3 | A | — (lot 2 de `declarer-limites-impact-scip`) |
| H17 | Linux : `--nproc=128` limite toutes les tâches de l'utilisateur réel, pas un processus | Risque (non exécuté) | P3 | A | — (lié à A03) |
| H18 | Windows : la sonde AppContainer lit sa sortie après la fin du processus | Défaut confirmé (reproduit), **corrigé** le 2026-10-09 (sortie dans un fichier) | P3 | A | — (lié à A07) |
| H19 | `scripts/intellij/run-minos.ps1` lance `mvnw package` avant le serveur MCP sur stdio | Non reproduit | — | E | `corriger-documentation-plugin-et-suivi-audit` |
| H20 | Variables obligatoires des fichiers Compose sans garde `${VAR:?message}` | Amélioration | P3 | E | — |
| H21 | Alertes SpotBugs nouvelles du plugin IntelliJ (11) | Faux positifs (4), convention (5), **2 corrigées** le 2026-10-08 | P3 | E | `etendre-audit-outille-a-tout-le-perimetre` |
| H22 | Les droits accordés au bac à sable AppContainer ne sont pas retirés quand le lanceur est tué (460 entrées sur le JDK) | Défaut confirmé ; journal avant octroi **corrigé**, fuite des `MINOS_HOME` de test ouverte | P2 | E | `revoquer-droits-appcontainer-apres-arret-brutal` |
| H23 | Incident d'environnement pendant le PIT de `minos-runtime-local` (JDK inutilisable, profil illisible ; cause non établie) | Risque confirmé (outillage) | P1 | E | `revoquer-droits-appcontainer-apres-arret-brutal` (précautions d'exécution) |
| H24 | Sous Windows (Ryuk désactivé), une JVM de test tuée laisse son conteneur PostgreSQL en vie | Risque confirmé (outillage de test) | P2 | E | `aligner-dependances-de-test-testcontainers` |

### Fiches

#### MINOS-AUD-H01 — Les rapports SpotBugs du plugin Maven ne prouvent pas le périmètre analysé

- **Preuve (E)** : `minos-engine/target/spotbugsXml.xml` déclare `total_classes="0"`, `referenced_classes="0"`, `num_packages="6"` (les six packages qui ont une alerte, sur seize), aucun `ClassStats`. La relecture en ligne de commande avec les mêmes réglages produit 309 `ClassStats` pour 309 classes compilées et les mêmes 39 alertes (mêmes `instanceHash`). Même constat sur les 14 modules.
- **Impact** : avant cet audit, `code-audit.md` affirmait l'analyse de « tous les modules » sur la seule foi d'un build vert ; un module analysé partiellement passerait inaperçu.
- **Comportement attendu** : toute conclusion de couverture s'appuie sur un décompte des classes analysées.
- **Correction faite (configuration d'audit)** : `audit-report-summary.py spotbugs` compare classes analysées et compilées, signale `UNPROVEN` un rapport sans statistiques (`--strict` le fait échouer) et lit les rapports de la ligne de commande (`--reports-dir`) ; procédure documentée dans `code-audit.md`.
- **Reste** : automatiser la relecture en ligne de commande dans `code-audit.yml`. **Validation** : `audit-report-summary.py spotbugs --reports-dir … --strict` rc = 0 (exécution E13).

#### MINOS-AUD-H02 — Faux rapprochements CPE de modules MINOS

- **Preuve (E)** : rapport livré, `pkg:maven/com.minos/minos-storage-postgresql@1.3.0-SNAPSHOT` identifié `cpe:2.3:a:postgresql:postgresql:1.3.0:snapshot` → 43 CVE du **serveur** PostgreSQL (2007 à 2026) ; `minos-nexus` identifié `project-nexus_project:project-nexus` (0 CVE). Le vrai pilote `org.postgresql:postgresql:42.7.13` (`postgresql_jdbc_driver:42.7.13`) n'a aucune alerte.
- **Impact** : bruit qui masquerait une vraie alerte si Dependency-Check devenait un contrôle.
- **Correction proposée** : `quality/dependency-check-suppressions.xml` limité au purl `^pkg:maven/com\.minos/.*$` et aux deux CPE, avec la justification, et `failBuildOnUnusedSuppressionRule=true`. **Non appliquée** : l'outil n'est pas bloquant et l'audit ne masque aucune alerte. **Validation** : rejouer E17, 0 alerte sur `com.minos`.

#### MINOS-AUD-H03 — CVE du client HTTP embarqué par Testcontainers

- **Preuve (E)** : rapport tests, `docker-java-transport-zerodep-3.7.1.jar` embarque `httpclient5` 5.5.1 (CVE-2026-71290 CRITICAL 9.1, CVE-2026-64607 MEDIUM 5.3) et `httpcore5` 5.3.6 (CVE-2026-54399 et CVE-2026-54428, HIGH 7.5). Chemin : `minos-storage-postgresql` et `minos-bootstrap`, portée test.
- **Applicabilité** : versions dans les plages publiées. Les deux CVE de `httpcore5` (mémoire épuisée par des en-têtes excessifs) exigent un pair malveillant : ici le démon Docker local, joint par tube nommé ou socket. CVE-2026-64607 est une fuite de connexion du client classique. CVE-2026-71290 ne vise que le client **asynchrone**, que ce transport n'utilise pas (à confirmer côté docker-java). Rien n'est livré aux utilisateurs.
- **Impact** : machines de build et de CI seulement ; exploitation improbable. **Correction** : monter Testcontainers quand docker-java embarque `httpcore5` ≥ 5.4.3 et `httpclient5` ≥ 5.6.3 (après H05). **Validation** : rejouer E18.

#### MINOS-AUD-H04 — Clé NVD refusée

- **Preuve (E)** : `dependency-check-maven:13.0.0:update-only -DnvdApiKeyEnvironmentVariable=NVD_API_KEY` → `Error updating the NVD Data: Invalid API Key`, rc = 1. Repli : mise à jour anonyme par 12.2.2 (anomalie amont #8715 de 13.0.0), analyse 13.0.0 sans mise à jour.
- **Impact** : une analyse avec 13.0.0 échoue tant que la clé n'est pas valide ; le job Dependency-Check de `code-audit.yml` exige le secret `NVD_API_KEY`.
- **Action (utilisateur)** : vérifier ou régénérer la clé sur le site du NVD, l'activer, la placer dans l'environnement utilisateur et comme secret du dépôt ; jamais dans un fichier versionné ni dans une conversation. **Validation** : E04 rc = 0.

#### MINOS-AUD-H05 — Versions de test non alignées

- **Preuve (E)** : `minos-bootstrap/pom.xml:82-95` et `minos-storage-postgresql/pom.xml:53-66` déclarent `org.testcontainers:testcontainers:2.0.5` avec `junit-jupiter:1.21.4` et `postgresql:1.21.4`. Dependabot a monté le cœur le 2026-08-30 (`d763f703`) ; en 2.x, les modules ont changé d'identifiant (`testcontainers-*`), donc les anciens restent en 1.x. Dans `minos-bootstrap`, la médiation retient `slf4j-api` 1.7.36 (venu de Testcontainers) ; le réacteur résout aussi 2.0.16 (`minos-mcp`, via le SDK MCP), 2.0.18 et 2.0.20 (jar ombré livré).
- **Impact** : les tests passent aujourd'hui ; un mélange de versions majeures peut casser à la prochaine montée sans signal clair. Aucun effet sur le jar livré (2.0.20).
- **Correction** : artefacts `testcontainers-postgresql` et `testcontainers-junit-jupiter` 2.x (ou nomenclature `testcontainers-bom`), `slf4j-api` géré par `slf4j.version` dans `dependencyManagement`, règle Enforcer `dependencyConvergence` à évaluer. **Validation** : `dependency:tree` sans version mixte ; tests PostgreSQL verts avec Docker.

#### MINOS-AUD-H06 — Gitleaks hors CI, règle par défaut bruyante

- **Preuve (E)** : aucun `.gitleaks.toml`, `.gitleaksignore` ni job Gitleaks ; les 66 alertes des trois analyses se ramènent à 13 causes, toutes fausses (§ 3.6 de la couverture). Le clone de travail est superficiel.
- **Proposition** : configuration qui étend les règles par défaut avec des listes d'autorisation **par forme** (SHA après `uses: …@`, champ `version` hexadécimal de `embedded-tools.json`, HEAD de la documentation d'exécution) et un job sur `pull_request` avec `fetch-depth: 0`. C'est un nouveau contrôle de fusion : **décision de l'utilisateur**.

#### MINOS-AUD-H07 — Faiblesses de tests révélées par PIT

PIT a analysé 7 modules sur 14 (`minos-runtime-local` partiellement) : voir § 5.2 de la couverture. Les survivants ci-dessous sont **qualifiés** ; les autres restent à examiner. Rapports : `mutations.xml` archivés hors dépôt.

**Intégrité de la chaîne d'audit hébergée (P1, faiblesse de test).** Dans `minos-engine`, chacune des gardes du constructeur de `HostedTenantState` qui protègent la chaîne d'audit peut être supprimée sans qu'aucun test n'échoue : lignes 64 (événement d'un autre tenant), 65 (lien `previousHash` rompu), 66 (ordre des séquences, deux mutants), 70 (ancre de séquence), 73 (ancre de genèse d'une chaîne vide), plus les capacités et unicités des lignes 31 à 56 et `requireHash` (ligne 80). Ce constructeur est **la seule défense atteignable** : il rend inatteignables les gardes équivalentes de `HostedAuditChain.verify` (lignes 94, 100, 103 et 110, survivantes et **équivalentes**). La garde de séquence **contiguë** de `verify` (ligne 97) reste atteignable et n'est pas testée ; aucun test ne vérifie une chaîne vide (`verify:90-91`). Conséquence : retirer un événement au milieu d'une chaîne stockée, ou en réordonner deux, n'est couvert par **aucun test**, alors que c'est la promesse de l'ADR 0035 et de l'exigence « Altération toujours détectée » de `openspec/specs/controle-tenant-heberge/spec.md`. Le comportement existe (lecture du code) ; c'est sa vérification qui manque.

**Nonce du stockage chiffré des tenants (P1, faiblesse de test).** Dans `minos-storage-local`, `FileHostedControlPlaneStore.writeAtomically:197` : supprimer `random.nextBytes(nonce)` ne fait échouer aucun test. Le nonce AES-GCM serait alors nul à chaque écriture du fichier d'un tenant, et réutiliser un nonce avec la même clé détruit la confidentialité et l'authenticité de GCM. Le code est correct ; aucun test ne vérifie que deux écritures successives produisent deux nonces différents. Les gardes d'en-tête de `read` (lignes 160 à 182 : taille, magic, version, tenant, longueur de nonce et de chiffré) survivent aussi, en partie parce que l'authentification GCM rejette les mêmes altérations.

**Stockage local (P2).** `minos-storage-local` : 518 survivants, concentrés dans `FileSemanticVectorStore` (84), `FileRuntimeObservationStore` (73), `FileProjectFingerprintSnapshotStore` (70), `FileHostedControlPlaneStore` (52) et `FileSymbolSnapshotStore` (46), à qualifier avec H12.

**Mutants équivalents documentés (`renforcer-tests-revelees-par-mutation`, tâche 1.3).** Dans `HostedAuditChain.verify`, les mutants qui retirent les gardes des lignes 94 (événement non chaîné), 100 (autre tenant), 103 (lien rompu) et 110 (ancre, deux mutants) sont **équivalents** : le constructeur de `HostedTenantState` refuse déjà ces états, et tout état vérifié passe par lui. Ces gardes restent en place (défense en profondeur). Le mutant de la ligne 91 (calcul de la séquence attendue d'une chaîne vide) l'est aussi : la boucle ne s'exécute pas. `HostedAuditChainTamperingTest` couvre désormais les gardes atteignables (constructeur, et ligne 97 de `verify`).

**Autorisation (P1, faiblesse de test).** `HostedAuthorizationService.authorizeRead:54` : supprimer la garde `role().allows(permission)` de la voie de lecture ne fait échouer aucun test ; aucun test ne vérifie qu'un VIEWER ou un CONTRIBUTOR est refusé sur `audit()` (`AUDIT_READ`) ou sur le plan de rétention.

**Jetons (P2, faiblesse de test).** `HmacHostedIdentityProvider` : 19 survivants sur les bornes de décodage (`decode:116-129`, `decodeCanonical:139-143`, `readString:160-165`, `writeString:151`), l'expiration (`authenticate:58`, `:73`) et l'émission (`issue:42`). Les bornes exactes et les jetons tronqués ou surdimensionnés ne sont pas testés.

**Bornes du plan de contrôle (P2).** `HostedAuditChain.append:45` (refus exactement à `MAX_AUDIT_EVENTS`) et `HostedAuthorizationService.admitsChainedDenial:171` (quart du budget d'octets) : frontières non testées. `recordDenial:147` (journalisation d'un conflit) : faible valeur, à accepter.

**Primitives d'E/S confinées (P2, faiblesse de test ou redondance).** `ConfinedFileOpener` : les gardes de `requireConfinedRelativePath` (lignes 152, 155, 157) et les deux appels à `verifyAncestorChain` (lignes 224, 242) survivent parce que la vérification finale `toRealPath().startsWith(root)` refuse aussi les mêmes chemins : la défense globale est testée, **aucune couche ne l'est isolément**. Sous Windows, `openThroughDirectoryHandles` (voie `SecureDirectoryStream`) n'est pas couvert du tout : à mesurer sous Linux. `PrivateLocalStorage` (35 survivants), `BoundedProperties` (19), `LeaseDeadline` (19, aucun test dédié) restent à qualifier.

**Code non couvert dans le module mais testé ailleurs (limite de l'outil).** `InMemoryCodeKnowledgeStore` (112 mutants sans couverture dans `minos-engine`) et `ProjectPathMapping` (55) sont testés dans `minos-provider-scip`, `minos-storage-local` et `minos-cli` ; les types de `minos-domain` dans presque tous les modules. Ce n'est pas une absence de test ; la passe `crossModule` le mesurera.

**`minos-runtime-local` (non concluant).** 812 mutants tués par une assertion, 160 survivants, 385 sans couverture, 96 timeouts et 560 erreurs d'environnement : le module doit être rejoué (dans une machine jetable, voir H22). Les survivants déjà visibles se concentrent dans `LinuxCgroupJob` (75, code Linux exécuté partiellement sous Windows) et `DistributedArtifactBundleStore` (17).

**Correction** : tests d'abord, sans modifier le code de production sauf si un test révèle un défaut ; priorité aux deux P1. **Validation** : PIT ciblé (`-DtargetClasses=com.minos.hosted.*`) : les survivants retenus passent à `KILLED` ; les mutants des lignes 94, 100, 103 et 110 de `verify` sont documentés équivalents.

#### MINOS-AUD-H08 — Usages de modules non déclarés

- **Preuve (E)** : `ModuleArchitectureTest.everyModuleOnlyUsesItsAllowedModules` (`-Dminos.audit.archunit.strict=true`) : `minos-storage-local→minos-domain` 259 dépendances, `minos-nexus→minos-engine` 21 (`ProjectRegistry`, `RegisteredProject`, `CodeKnowledgeSnapshotStore`, `CodeKnowledgeSnapshot`, `Sha`), `minos-mcp→minos-domain` 14 et `→minos-engine` 13 (`HostedControlPlaneService`, `UnreadableRegistryException`, `PublicErrorMessages`, `ResumableRunSummary`), `minos-runtime-local→minos-domain` 5 (`Preconditions.requireText`). Toutes passent par une dépendance transitive acceptée par Maven ; `ALLOWED_DEPENDENCIES` (ADR 0022, ADR 0042) ne borne que les dépendances **déclarées**.
- **Décision à prendre** : (a) déclarer ces arêtes dans les POM et la table ; (b) tolérer l'usage transitif du domaine mais interdire aux surfaces les ports du moteur (point 6 de l'ADR 0057 : les surfaces consomment des cas d'usage) ; (c) statu quo documenté. La règle reste une **mesure opt-in** tant que la décision n'est pas prise.

#### MINOS-AUD-H09 — Le plugin IntelliJ échappe aux contrôles locaux

- **Preuve (E)** : `minos-intellij` n'est pas dans le réacteur ; Gradle n'est pas installé et le module n'a pas de wrapper ; ses classes de `build/` dataient du 12 août alors que les sources avaient changé le 7 octobre ; le projet IntelliJ ouvert ne le déclare pas. Exécuté par harnais : 71 tests (63 réussis, 8 réservés Unix), 68/68 classes SpotBugs, 10 dépendances sans vulnérabilité.
- **Correction** : wrapper Gradle pour le plugin ; job plugin dans `code-audit.yml` (SpotBugs, Dependency-Check et PIT en Gradle) ; rendre visible l'exécution de `intellij-plugin.yml` (filtre de chemins). **Validation** : `gradle test verifyPlugin` et les trois analyses dans un run.

#### MINOS-AUD-H10 — Chemin de lanceur faux dans la documentation du plugin

- **Preuve (L)** : `docs/user/intellij-plugin.md:69` indique `C:\Users\<user>\AppData\Local\Programs\MINOS\bin\minos.cmd`. La distribution écrit `minos.cmd` à sa racine (`scripts/release/build-windows-distribution.ps1:354`), l'installation par défaut est `Programs\MINOS` (`scripts/install/install-windows.ps1:66`, `packaging/windows/minos-installer.iss.template:16`) et la liste des entrées installées ne contient pas de dossier `bin` (`minos-installer.iss.template:1282`).
- **Impact** : un utilisateur qui recopie ce chemin configure un exécutable inexistant ; le plugin ne démarre pas. **Correction** : `…\Programs\MINOS\minos.cmd`. **Validation** : relecture contre l'installateur ; idéalement un contrôle documentaire qui confronte ce chemin à `build-windows-distribution.ps1`.

#### MINOS-AUD-H11 — Suivi de l'audit d'octobre périmé

- **Preuve (L)** : `docs/audit/constats.md` § 2 et § 6 et `docs/audit/README.md` § 9 décrivent B01–B04, C01–C06, C16, A01, A02, C04, B07–B09, B13, D01, D06, D10 comme « corrigés en local », « PR ouverte » ou « CI à observer », alors que les commits correspondants (`82dc354b`, `f87f2250`, `9bc1129d`, `9f3e2911`, `14596db2`, `93e10c3a`, `67872cc8`) sont dans `develop`. **Correction faite** : ligne de suivi datée dans `docs/audit/README.md` § 10 et état daté en tête du § 6 de `constats.md` (commit fusionné et statut revérifié de chaque changement).

#### MINOS-AUD-H12 et H13 — Reprise après interruption du stockage

- **Preuve (A, non réexécutée)** : les tests existants couvrent un fichier publié corrompu (`FileSymbolSnapshotStoreTest:132`), un résidu `.snapshot-*.tmp` balayé après 24 h (`SnapshotRetentionOrphanTest:21-60`), des pannes injectées dans `DurableAtomicFileTest`, la reprise du moteur sur magasins factices, et (exécutés dans la ligne de base) `ResumeAfterHardKillIntegrationTest` et `InterruptionDuringIndexingTest`. Aucun test ne tue un processus entre l'écriture temporaire et le renommage d'un snapshot puis rouvre le magasin ; aucun ne lit un `active.pointer` tronqué (message de `ActiveSnapshotRepository:113` jamais vérifié) ; aucun ne migre une base PostgreSQL peuplée de v1 à v4. `SnapshotRetentionService:96` ne récupère jamais `.active-*.tmp` (comportement figé par `SnapshotRetentionOrphanTest:38`), ni `FileProjectFingerprintSnapshotStore:183`.
- **Correction** : tests de reprise par processus fils tué puis réouverture ; test de pointeur tronqué ; test de migration v1→v4 sur base peuplée (Testcontainers) ; puis décision sur la récupération des `.active-*.tmp` orphelins (borne d'âge, comme les snapshots).

#### MINOS-AUD-H14 à H19 — Risques relevés à la revérification

Relevés par lecture déléguée (A), non réexécutés ; chaque point dit ce qui le confirmerait.

- **H14** `HostedAuthorizationService.java:68` valide `requestId` par `safeId` avant l'authentification : un appelant sans droit qui envoie un identifiant invalide reçoit `INVALID_REQUEST` sans trace. À confirmer par un test qui envoie un `requestId` invalide avec un jeton sans rôle puis inspecte la chaîne d'audit.
- **H15** `HostedAuditDelivery.java:27,52` n'attrape que `IOException` ; `recordDenial` (ligne 155) est hors du `try`. Le seul sink embarqué ne fait rien : impact nul aujourd'hui, réel pour un sink tiers. À confirmer par un test avec un sink qui lève une `RuntimeException`.
- **H16** `ProjectQueryService.java:64-70` recharge le snapshot actif pour les limitations, séparément de `findRelationships` (`:58-61`) : pendant une promotion, résultats et limitations peuvent venir de deux snapshots, et le décodage complet est payé deux fois (D03).
- **H17** `LinuxBubblewrapWorkerSandboxBackend.java:343` : `RLIMIT_NPROC` compte les tâches de l'utilisateur réel ; l'ADR 0038 et `remote-worker-sandbox-disposition.md:52` parlent d'une limite par processus. À confirmer sous Linux avec un utilisateur qui a déjà plus de 128 tâches.
- **H18** `WindowsAppContainerWorkerSandboxBackend.java` (≈ ligne 404) : `probeExitedCleanly` ne lit la sortie qu'après la fin ; le correctif A01 ajoute un `Write-Warning` par journal sans propriétaire. À confirmer sous Windows avec de nombreux journaux orphelins. **Confirmé et corrigé le 2026-10-09** : avec une sortie de 1 Mo lue seulement après la fin, le processus ne se termine pas en 15 s (bloqué sur son écriture), donc la sonde aurait conclu à un bac à sable indisponible ; `runProbe` envoie maintenant la sortie dans un fichier, lu après la fin (`AppContainerProbeOutputTest`).
- **H19** `scripts/intellij/run-minos.ps1:15-17` lance `mvnw package` avant le serveur MCP sur stdio : une ligne de Maven sur la sortie standard précéderait le flux JSON-RPC. **Non reproduit (E)** : en cas de succès, `mvnw.cmd -q -DskipTests package` n'écrit aucune ligne sur la sortie standard ; en cas d'échec, le script s'arrête avant le démarrage du serveur.

#### MINOS-AUD-H20 — Garde des variables Compose

- **Preuve (E)** : `docker compose -f docker/compose-mcp.prod.yaml config -q` sans variables échoue sur `container_name ''` ; `MINOS_DATA_DIR`, `MINOS_PROJECTS_DIR`, `MINOS_IMAGE`… n'ont ni valeur par défaut ni garde. Les scripts de lancement les fournissent toujours. **Amélioration** : `${MINOS_DATA_DIR:?MINOS_DATA_DIR is required}` pour un message explicite.

#### MINOS-AUD-H21 — Alertes SpotBugs du plugin IntelliJ

- `DMI_HARDCODED_ABSOLUTE_FILENAME` ×4, `MinosStrongProcessLauncher:47` : liste voulue des répertoires système Linux (`/usr/bin`, `/bin`…) : **faux positif**.
- `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`, `MinosProjectService:121` : `named.getName()` rappelé après un test de nullité : rien ne garantit que le second appel rende la valeur testée. **Corrigé** le 2026-10-08 (une seule lecture, deux occurrences).
- `USO_UNSAFE_METHOD_SYNCHRONIZATION`, `MinosUiController:27` : `attach` est `synchronized` sur une instance publique et exécute un rappel sous verrou. **Corrigé** le 2026-10-08 : verrou privé, rappel exécuté hors verrou comme dans `deliver`. Relecture SpotBugs : 9 alertes, 68/68 classes ; harnais : 63 tests réussis, 8 réservés Unix.
- `EI_EXPOSE_REP` ×3 et `EI_EXPOSE_REP2` ×2 : même convention que dans le réacteur (lot 5 du § 4).

#### MINOS-AUD-H22 — Les droits accordés au bac à sable AppContainer ne sont pas retirés quand le lanceur est tué

- **Preuve (E)** : la racine du JDK `C:\Users\fturl\.jdks\openjdk-24.0.1` porte 460 entrées **explicites** `(OI)(CI)RX` pour des SID `S-1-15-2-…` (`icacls`). Le script `minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-appcontainer-sandbox-v4.ps1.template` accorde ces droits par `Grant-AppContainerDirectory` (lignes 469-476, appelée ligne 525 pour chaque chemin en lecture, `(OI)(CI)RX`) et ne les retire que par `Remove-AppContainerPath` (fonction lignes 486-489, appelée ligne 558 dans le bloc de nettoyage), qui ne s'exécute pas si le processus est tué. Un lanceur tué (timeout, arrêt brutal, minion PIT abattu) laisse donc une entrée **persistante** sur un répertoire de l'hôte partagé avec d'autres programmes. **Correction de diagnostic (8 octobre, soir)** : la reprise ajoutée par A01 retire bien les entrées **journalisées** d'un run mort au lancement suivant dans le même `MINOS_HOME`. Les fuites viennent (1) de l'ordre « octroi puis journal », corrigé depuis (journal d'abord), et (2) des répertoires de reprise jamais réutilisés : `MINOS_HOME` temporaire des tests, minions PIT tués. En production, le risque résiduel est donc faible ; sur un poste de développement ou un runner qui tue des tests, les entrées s'accumulent.
- **Impact** : accumulation sans borne d'entrées d'ACL sur le JDK et les répertoires d'outils (chaque entrée héritée par tous les fichiers en dessous), coût croissant des contrôles d'accès, et droits de lecture laissés à d'anciens conteneurs. Le lien avec l'incident H23 n'est pas démontré.
- **Comportement attendu** : fail-closed et réversibilité (ADR 0036, 0038) ; aucune modification de l'hôte ne survit à un run, y compris après un arrêt brutal ; la récupération par propriétaire (`isoler-recuperation-appcontainer-par-proprietaire`) doit couvrir aussi les ACL.
- **Correction proposée** : journaliser chaque autorisation (chemin, SID) dans le répertoire de récupération **avant** de l'appliquer, et la retirer à la reprise d'un run mort (même verrou de propriété que A01) ; ou préférer des copies privées des runtimes plutôt que des droits sur les originaux ; outil de nettoyage des entrées orphelines pour les postes déjà touchés. **Validation** : test Windows qui tue le lanceur après l'octroi puis vérifie qu'une reprise retire l'entrée ; `icacls` sur le JDK sans entrée `S-1-15-2-…` après la suite de tests.
- **Reproduit (E28)** : pendant le PIT de `minos-cli`, après réparation du poste, 3 entrées `S-1-15-2-…` `(OI)(CI)RX` sont apparues sur la racine du JDK et y sont restées après l'arrêt de tous les lanceurs (aucun processus de bac à sable vivant).
- **Qualification** : défaut confirmé, reproduit en exécution ; **P2** après correction de diagnostic (production : seul l'ordre octroi/journal, corrigé ; environnement de test : fuite documentée). **Corrigé en partie** : journal avant octroi.

#### MINOS-AUD-H23 — Incident d'environnement pendant le PIT de `minos-runtime-local`

- **Preuve (E)** : depuis le 8 octobre vers 11 h 25, (1) 560 minions PIT n'ont pas démarré (11 h 25 à 11 h 40) ; (2) toute JVM échoue à charger `conf\security\java.security` du JDK (`AccessDeniedException`), Maven ne démarre plus ; (3) le DACL de `C:\Users\fturl` n'est plus lisible (`icacls` : accès refusé), alors que le profil était accessible à 10 h 12. Détail : § 7.1 de [code-audit-couverture.md](code-audit-couverture.md).
- **Cause** : **non établie**. Faits établis : la fenêtre coïncide avec le PIT de `minos-runtime-local`, qui exerce des lanceurs Windows modifiant des ACL réelles (H22) ; aucun code de MINOS lu ne réécrit le DACL d'un ancêtre de son répertoire ; un autre outil modifie des ACL sur ce poste (`CodexSandboxUsers`).
- **Impact** : poste de développement dégradé (JDK inutilisable, profil non lisible), audit interrompu.
- **Action** : réparation par l'utilisateur en administrateur (aucune réparation tentée par l'audit) ; ne plus muter `minos-runtime-local` ni lancer les tests de bac à sable hors d'une machine jetable tant que H22 n'est pas corrigé ; examiner le journal de sécurité Windows (événements 4670 si l'audit des objets est actif) pour attribuer le changement.
- **Qualification** : risque confirmé (pour l'outillage d'audit) ; **P1** tant que la cause n'est pas connue.

#### MINOS-AUD-H24 — Sous Windows, une JVM de test tuée laisse son conteneur PostgreSQL en vie

- **Preuve (E)** : le profil `windows-docker-desktop-testcontainers`, actif sur tout Windows, fixe `TESTCONTAINERS_RYUK_DISABLED=true` pour Surefire (`minos-storage-postgresql/pom.xml:132`, `minos-bootstrap/pom.xml:138`). Sans Ryuk, Testcontainers n'arrête un conteneur que par l'arrêt normal de la JVM. Pendant le PIT de `minos-storage-postgresql` (E27), les minions tués par timeout ont laissé une quarantaine de conteneurs `pgvector/pgvector:0.8.7-pg17` actifs (30 à 57 minutes d'âge, aucun conteneur Ryuk présent) ; Docker Desktop a cessé de répondre (`docker ps` et `docker info` sans réponse au-delà de 2 minutes).
- **Impact** : sur un poste Windows, un fork Surefire tué (Ctrl+C, délai de Surefire, plantage) ou une mutation laisse des bases PostgreSQL qui consomment mémoire et CPU jusqu'à un nettoyage manuel ; une exécution PIT les accumule jusqu'à bloquer Docker. Aucun effet sur le produit livré.
- **Comportement attendu** : un test qui démarre un conteneur ne doit pas en laisser après sa fin, normale ou non.
- **Correction proposée** : comprendre pourquoi Ryuk a été désactivé (commentaire du profil : compatibilité de l'API Docker Desktop) et le réactiver si la version actuelle de Docker Desktop le permet ; sinon, nommer les conteneurs ou les étiqueter par exécution et les supprimer au démarrage de la suite suivante. **Validation** : tuer un fork Surefire pendant `PostgresSchemaMigratorTest`, constater qu'aucun conteneur ne reste après 30 s (Ryuk) ou après la reprise.
- **Qualification** : risque confirmé (outillage de test, Windows) ; **P2**. Changement : `aligner-dependances-de-test-testcontainers`.

## 6. Revérification des constats antérieurs au HEAD `902e2bfa`

Revérifiés dans le code actuel, sans présumer qu'ils sont encore présents ni qu'ils sont corrigés. Statuts établis par lecture (niveau A, analyses déléguées) sauf mention. L'exécution de la ligne de base (2 271 tests verts) confirme que les tests cités passent, pas qu'ils couvrent tout le défaut.

| Famille | Corrigés au HEAD | Partiels | Toujours présents | Décisions ouvertes |
|---|---|---|---|---|
| A confinement | A01 (par lecture ; le test sur le vrai lanceur est sauté si l'hôte n'est pas qualifié) | A02 (résidus `appcontainer-probe-*` jamais balayés) | A03, A04, A05, A06, A08, A09 | A07, A10 |
| B plan de contrôle et secrets | B01, B02, B03, B04 (classement), B07 (Java ; installateur sans test), B08, B09 (Linux non exécuté), B13 | — | B10, B11, B12 (la documentation contredit toujours `HostedAuditDelivery`) | B04 (clé retirée), B05 (rejeu de l'état chiffré, limite non documentée), B06 |
| C surfaces | C01, C02 (`NOT_INSPECTED` à confirmer), C04 (par lecture), C05, C06, C16 | C03 (`project list` et le plugin découvrent encore chaque projet) | C09, C10, C11, C12, C13, C14, C15 | C07, C08 |
| D cycle de vie | D01 (répertoires ignorés ou durcis), D06 | D02 (décodage complet du snapshot), D10 (casse non traitée) | D03, D04, D05, D07, D08, D09, D13, D14 | D01 (non ignorés), D11, D12 |
| E persistance et frontières | — | — | E01 à E11 | E12 |
| F providers et requêtes | F04 (réindexation requise) | F01 (trois sorties sans la limitation : graphe de programme, workspace, recherche), F02 | F03, F05, F07, F08, F09 à F14 | F06 |
| G documentation | G02 | — | G04 à G08, G20 (décisions) | — |

Points vérifiés par l'auteur pendant l'audit :

- **Chaîne d'audit (B01–B04)** : la ligne de base exécute `HostedAuditChainCanonicalFormTest`, `HostedRefusalTraceTest`, `HostedKeyRotationRefusalTest`, `HostedDenialByteBudgetTest` (verts). Le constructeur de `HostedTenantState` (`HostedTenantState.java:60-72`) refuse déjà les événements non chaînés, d'un autre tenant, à chaînage rompu ou à ancre incohérente : quatre des cinq gardes de `HostedAuditChain.verify` (lignes 94, 100, 103, 110) sont **inatteignables** et leurs mutants équivalents ; la garde de **séquence contiguë** (ligne 97) reste atteignable, le constructeur n'exigeant qu'un ordre strictement croissant (ligne 66). Voir H07.
- **Diagnostics MCP (C01–C02)** et **refus d'accès** : tests de `minos-mcp` et `minos-api` verts dans la ligne de base.
- **Stockage PostgreSQL** : 78 tests exécutés contre un vrai PostgreSQL/pgvector (Docker), JaCoCo `m30-postgresql-pgvector` vert.
- **Lanceurs** : H10 vérifié ; scripts `.sh` et `.ps1` analysés syntaxiquement (§ 6 de la couverture).
- **Limites des résultats d'analyse (F01)** : partiel, trois sorties sans déclaration (lecture déléguée, non réexécutée).

Le détail ligne par ligne des familles A à F est conservé hors dépôt (§ 4 de la couverture) ; il alimentera la mise à jour du § 6 de [`docs/audit/constats.md`](../audit/constats.md).

## 7. Plan de correction priorisé

| Ordre | Lot | Constats | Nature | Prérequis | Validation |
|---|---|---|---|---|---|
| 0 | Rétablir le poste (DACL du profil, entrées AppContainer du JDK) | H23 | action de l'utilisateur, en administrateur | — | `java -XshowSettings:security -version` et `./mvnw -v` réussissent |
| 1 | Retrait des droits AppContainer après arrêt brutal | H22 | test d'abord (lanceur tué), puis journal des octrois et reprise | lot 0 ; machine jetable pour les tests | aucune entrée `S-1-15-2-…` orpheline après la suite de tests |
| 1 bis | Clé NVD valide | H04 | action de l'utilisateur | — | E04 rc = 0 |
| 2 | Tests révélés par la mutation (autorisation et chaîne d'audit d'abord) | H07, H14, H15 | tests d'abord ; code de production seulement si un test révèle un défaut | — | PIT ciblé : survivants retenus `KILLED` ou démontrés équivalents |
| 3 | Documentation du plugin et suivi de l'audit | H10, H11, H19 | documentation | — | gates documentaires vertes |
| 4 | Reprise après interruption du stockage | H12, H13 | tests, puis décision de récupération | — | nouveaux tests rouges avant correction, verts après |
| 5 | Outillage d'audit sur tout le périmètre | H01, H02, H06, H09, H21 | configuration d'audit et CI manuelle | lot 1 | run complet de `code-audit.yml` |
| 6 | Dépendances et conteneurs de test | H03, H05, H24 | build et configuration de test | — | E18 sans CVE ; arbre sans versions mixtes ; aucun conteneur après un fork tué |
| 7 | Frontières de modules | H08 | décision (ADR) puis règle | — | mode strict vert ou règle retirée |
| 8 | Risques Linux et Windows | H17, H18, H20 | exécution sur la plateforme, puis décision | runners Linux et Windows qualifiés | journaux d'exécution |

Les lots SpotBugs du § 4 (1 à 6) restent valables : les 197 alertes du réacteur sont inchangées au 8 octobre.

## 8. Changements OpenSpec

| Changement | Constats | Statut |
|---|---|---|
| `ajouter-audit-spotbugs-pitest` (existant, 14/18) | intégration initiale | tâches 5.1 à 5.4 ouvertes ; 5.3 reprise par `renforcer-tests-revelees-par-mutation` |
| `etendre-audit-outille-a-tout-le-perimetre` | H01, H02, H04, H06, H08 (mesure), H09, H21 | 11/13 au 2026-10-09 ; restent la clé NVD (H04) et un run de `code-audit.yml` sur GitHub |
| `renforcer-tests-revelees-par-mutation` | H07, H14, H15 | **archivé** le 2026-10-09 (`archive/2026-10-09-…`), exigences reportées dans `specs/controle-tenant-heberge` |
| `couvrir-reprise-apres-interruption-du-stockage` | H12, H13 | **archivé** le 2026-10-09, nouvelle spec `specs/persistance-snapshots` |
| `corriger-documentation-plugin-et-suivi-audit` | H10, H11, H19 | **archivé** le 2026-10-09, exigence reportée dans `specs/client-intellij` |
| `aligner-dependances-de-test-testcontainers` | H03, H05, H24 | 6/8 au 2026-10-09 ; restent la décision `dependencyConvergence` et le rejeu Dependency-Check (H03, attend docker-java) |
| `revoquer-droits-appcontainer-apres-arret-brutal` | H22, H23 | 6/7 au 2026-10-09 ; reste le PIT de `minos-runtime-local` sur un runner jetable |

Traçabilité : constat (§ 5) → preuve (fiche, exécution E.. de la couverture) → exigence (spec du changement) → tâche (`tasks.md`) → validation (commande et rapport à rejouer).
