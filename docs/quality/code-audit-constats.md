# Audit de code : constats de la première exécution

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
