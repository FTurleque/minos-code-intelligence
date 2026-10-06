# Constats de l'audit 2026-10

Ce document consolide les constats de l'audit du 6 octobre 2026 (HEAD `bc1d3421`, branche `develop`).
Il contient : (1) la lecture des qualifications, priorités et niveaux de vérification ; (2) les **fiches détaillées des constats P1** ; (3) le **registre complet** des 101 constats avec leur changement OpenSpec ; (4) les ajustements de priorité par rapport aux annexes.

Les fiches complètes de **tous** les constats (preuves `chemin:ligne`, comportement attendu et sa source, cause, correction minimale, test de validation) sont dans les [annexes A à G](annexes/). Les identifiants `X-NN` des annexes valent `MINOS-AUD-XNN` ici (annexe `B`, fiche `B-01` = `MINOS-AUD-B01`).

Les constats déjà connus de [`AUDIT-2026-09.md`](AUDIT-2026-09.md) ne sont pas répétés : seuls les écarts nouveaux, ou l'état au HEAD des constats ouverts qu'une analyse a revérifiés, figurent ici. Au HEAD, **16 constats clos de l'audit précédent ont été revérifiés** (S1, S3, S4, S5, S9, Q1, Q10, Q11, Q13, A1-A4, A6, A7, C1/C2, R1, G3, D1) : aucun n'est contredit par le code (annexe G). Les constats ouverts R8, R10 et R12 ont été confirmés au HEAD ; R8 est **plus large** que décrit (annexe D).

## 1. Lecture des qualifications

| Terme | Sens dans ce document |
|---|---|
| **Défaut confirmé** | Écart démontré par un test, une reproduction ou un chemin de code sans ambiguïté. |
| **Risque** | Problème plausible dont le déclenchement dépend d'un contexte ou d'une mesure non faite. |
| **Amélioration** | Évolution dont la nécessité est argumentée, sans écart avec une exigence. |
| **Décision à clarifier** | Intention métier ou technique que les sources ne permettent pas de trancher. Ne figure jamais dans un changement comme tâche bloquante. |
| **Dérive doc/contrat** | Documentation ou catalogue qui contredit le code (le code fait foi tant qu'aucune décision n'a été prise). |

**Priorités.** P0 : perte de données, faille exploitable à distance ou arrêt du produit, sans contournement. P1 : défaut confirmé dont l'impact touche une garantie centrale (sécurité, intégrité, invariant d'un ADR) ou rend une surface majeure inutilisable. P2 : défaut ou risque réel à impact borné ou à déclenchement conditionnel. P3 : durcissement, dérive mineure, dormant. **Aucun P0 n'a été trouvé.**

**Niveaux de vérification** (colonne « Vérif. » du registre).

| Code | Signification |
|---|---|
| **E** | Exécuté par l'auteur de ces livrables dans ce dépôt (test rouge actif avec `-Dminos.audit.repro=true`, ou commande sur le jar construit au HEAD). |
| **L** | Preuves clés relues par l'auteur dans le code au HEAD. |
| **R** | Reproduit ou mesuré hors dépôt par l'analyse de la capacité (scratchpad jetable) ; fiche relue seulement. |
| **A** | Établi par lecture de code par l'analyse de la capacité ; non rejoué. |

## 2. Fiches détaillées des constats P1

<a id="minos-aud-b01"></a>
### MINOS-AUD-B01 — Un refus RBAC à identifiant non canonique corrompt définitivement la chaîne d'audit du tenant

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-B01 |
| Titre | Refus RBAC avec identifiant de ressource non canonique : chaîne d'audit invalide pour tout le tenant |
| Qualification | **Défaut confirmé**, reproduit (vérification **E**) |
| Priorité | **P1** — un appelant de rang minimal (VIEWER, AUDITOR, CONTRIBUTOR, ou membre révoqué dont le jeton court encore) rend le tenant inutilisable pour tous, lectures comprises ; sans transport devant le service (le MCP est en lecture seule) l'exposition reste conditionnée à l'ADR 0035 |
| Preuves | `minos-engine/.../hosted/HostedMembershipService.java:42-44,77-79` et `HostedTokenService.java:40-42` passent l'argument **brut** comme `resourceId` à `authorizeMutation` ; `HostedAuditChain.append` (`:51-54`) calcule le HMAC sur la valeur brute puis construit l'événement dont le constructeur (`HostedAuditEvent.java:58`) fait `trim()` ; `HostedAuditChain.verify` (`:94-119`) recalcule sur la valeur normalisée ; `HostedAuthorizationService.loadVerified` (`:142-147`) vérifie la chaîne à chaque requête authentifiée. Test rouge : `minos-engine/src/test/java/com/minos/hosted/AuditReproHostedDenialChainTest.java` |
| Comportement actuel | `grantMember(viewer, "r1", " ghost", …)` lève bien `SecurityException` (refus), puis **toute** requête suivante, du propriétaire aussi, lève `SecurityException: hosted audit event authentication failed`. Observé : 2 tests sur 3 de la classe de reproduction (révocation avec tabulation initiale incluse) |
| Comportement attendu | Un refus RBAC est audité sans altérer la chaîne. Sources : `docs/developer/team-hosted-mode.md` (« RBAC denials after successful token authentication are appended as `DENIED` ») ; ADR 0035 (chaîne HMAC-SHA-256 incluant les refus) ; fail-closed non déclenchable par un appelant faiblement privilégié |
| Cause | Deux sources de vérité pour la valeur d'un champ d'audit : le HMAC est calculé sur l'argument brut, le champ stocké est canonicalisé ensuite |
| Impact | Déni de service permanent du tenant ; la rétention (`retention-apply`) passe aussi par la vérification, donc le tenant ne peut plus s'auto-réparer ; reprise manuelle par édition du fichier avec les clés |
| Correction proposée | Canonicaliser avant de hacher (ou hacher les champs lus depuis l'enregistrement construit) ; valider `resourceId` dès `authorizeMutation` (voir B02, B03) |
| **Correction réalisée** (2026-10-06, **PR ouverte, CI de la PR à observer**) | `HostedAuditChain.append` et `unchainedRefusal` construisent l'événement d'abord puis calculent le HMAC depuis ses accesseurs (`authenticated`/`expectedHash`) ; `verify` et `authenticate` sont inchangés. L'entrée du HMAC d'un événement déjà canonique est identique : deux vecteurs calculés en Python (hors du code Java) restent verts avant et après. Une chaîne **déjà corrompue** reste illisible en fail-closed : sa réparation est une décision distincte. Tests : `HostedAuditChainCanonicalFormTest`, `HostedAuditResourceIdCanonicalFormTest` (13 cas). Détail : [tâches 2.1, 2.2](../../openspec/changes/fiabiliser-chaine-audit-tenant/tasks.md) |
| Validation | Les tests de reproduction deviennent verts ; propriété « tout champ d'événement est un point fixe de la canonicalisation » |
| Dépendances | B02, B03 (même point d'entrée) — changement OpenSpec `fiabiliser-chaine-audit-tenant` |

<a id="minos-aud-b02"></a>
### MINOS-AUD-B02 — Les refus chaînés ne sont pas bornés en octets : un VIEWER peut saturer la limite de 32 MiB

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-B02 |
| Titre | Refus non bornés en octets : les écritures autorisées finissent par échouer |
| Qualification | **Défaut confirmé** (arithmétique mesurée hors dépôt, vérification **R**) |
| Priorité | **P1** — même famille de déni de service que S2, que la correction de S2 a laissée ouverte en ne bornant que le **nombre** d'événements |
| Preuves | `HostedAuditEvent.java:58` (identifiant jusqu'à 4 096 caractères) ; capacités en nombre seulement : `HostedRetentionPolicy.deniedAuditCapacity` (`:37-39`, 9 000 refus) et `HostedAuditChain.append` (`:45`, 100 000 événements) ; limite en octets : `FileHostedControlPlaneStore.DEFAULT_MAX_TENANT_BYTES = 32 MiB` (`:46`), appliquée seulement à l'écriture (`:213`) |
| Comportement actuel | Un refus à identifiant de 4 000 caractères pèse 4 244 octets : 32 MiB sont atteints après ≈ 7 906 refus, soit **moins** que la réserve de 9 000. Au budget de 10 refus/min par principal, une seule identité y parvient en ≈ 13 h, inférieur à la validité maximale d'un jeton (24 h). Ensuite toute sauvegarde échoue, mutations autorisées et rétention comprises |
| Comportement attendu | « Refusals are bounded so that they can never starve authorized mutations » (`docs/developer/team-hosted-mode.md`) |
| Cause | Capacités exprimées en événements alors que la contrainte dure est en octets ; valeur de champ non bornée côté refus |
| Impact | Déni de service en écriture du tenant par un principal sans droit d'écriture ; amplification d'E/S (réécriture jusqu'à 32 MiB avec fsync par refus) |
| **Correction réalisée** (2026-10-06, **PR ouverte, CI de la PR à observer**) | Forme bornée du `resourceId` d'un refus (identifiant sûr conservé, sinon `invalid:` + SHA-256) ; budget d'octets : les refus chaînés ne dépassent pas **un quart** de la limite du magasin, déclarée par `HostedControlPlaneStore.tenantByteLimit()` (méthode additive, vide par défaut ; le magasin local déclare 32 MiB), estimée par `HostedAuditEvent.estimatedEncodedBytes()`. Au-delà : refus appliqué, journalisé, livré non chaîné, état persistant intact. Reproduction : 400 refus à 4 000 caractères sur un magasin de 64 KiB, puis une mutation autorisée et la rétention réussissent ; test de mutation : sans la limite, l'échec `encoded hosted tenant exceeds byte limit` revient. Limite : un tenant rempli par des événements **autorisés** reste hors périmètre. Tests : `HostedDenialByteBudgetTest`, `HostedDenialByteBudgetPortTest`, `HostedEventSizeEstimateTest`, `HostedRefusalResourceIdTest` |
| Correction proposée | Valider/tronquer `resourceId` (128 caractères) avant audit ; admission des refus **par octets** via une méthode additive à valeur par défaut sur le port de stockage (le moteur ne connaît pas la limite d'octets du magasin, ADR 0022). Aligner `MAX_AUDIT_EVENTS` sur le plafond d'octets changerait un contrat public (plage de `maxAuditEvents` visible par le CLI et l'API) : écarté, voir erratum § 4 |
| Validation | `HostedDenialByteBudgetTest` (fiche annexe B, B-02) : 200 refus de 4 000 caractères sur un magasin à 64 KiB, puis une mutation autorisée réussit |
| Dépendances | B01, B03 — `fiabiliser-chaine-audit-tenant` |

<a id="minos-aud-c01"></a>
### MINOS-AUD-C01 — Les erreurs de statut sont opaques (« MINOS tool execution failed »), y compris pour une référence de projet de type chemin

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-C01 |
| Titre | Erreur opaque des surfaces de statut (MCP, CLI, API) |
| Qualification | **Défaut confirmé** (CLI reproduite par l'auteur, vérification **E** ; MCP par lecture) |
| Priorité | **P1** — l'outil de diagnostic ne diagnostique rien ; touche les trois surfaces ; cause probable du symptôme observé pendant cet audit : `minos_index_status` a échoué avec « MINOS tool execution failed » sans autre information |
| Preuves | `minos-mcp/.../MinosMcpTools.java:156-174` (seule `IllegalArgumentException` donne un message ; tout autre `Exception` donne `GENERIC_TOOL_ERROR`, `:47`), `:204-225` (le journal ne porte que le type) ; `ProjectResolver.java:21,124-126` (`ResolutionException extends IllegalArgumentException`, message recopiant la valeur de l'appelant) ; `PublicErrorMessages.java:53-87` (toute sous-chaîne `X:\` ou `/…` est classée « chemin sensible ») ; `CliCommandSupport.java:166-169` et `MinosApiSupport.java:173-175` (repli sur le nom simple de la classe) ; `MinosMcpErrorRedactionTest.java:19-30` épingle le message générique pour `IOException` et n'a aucun cas de référence de type chemin |
| Comportement actuel | Reproduit sur le jar construit au HEAD, `MINOS_HOME` isolé : `index-status unknown-name` → `error: index-status failed: unknown project: unknown-name` ; `index-status 'N:\workspace-dev\minos-code-intelligence'` → `error: index-status failed: ResolutionException` (code 1). Côté MCP, le même cas devient « MINOS tool execution failed » |
| Comportement attendu | Une erreur qui permet à l'appelant de se corriger, sans fuite de chemin absolu, de secret ni de JDBC (cas déjà verrouillés par `MinosMcpErrorRedactionTest`) ; contrats additifs (ADR 0016, 0017, 0018) |
| Cause | Politique de redaction par liste noire heuristique appliquée à des messages qui recopient l'entrée de l'appelant ; absence de canal d'erreur typé hors `IllegalArgumentException` |
| Impact | Les agents (qui passent volontiers un chemin comme `project`) ne peuvent pas se corriger ; support impossible ; le plugin IntelliJ affiche `ResolutionException` |
| **Correction réalisée** (2026-10-07, **PR ouverte, CI de la PR à observer**) | `ProjectResolver.ResolutionException.publicMessage()` : le message actuel est conservé tel quel si la politique de redaction ne le juge pas sensible, sinon un texte fixe sans écho (« unknown project (the reference is not shown); pass a registered project name or UUID, see minos project list ») ; utilisé par la CLI, l'API Java et le MCP. Le MCP rapporte aussi par message fixe le registre partiellement illisible, le mode équipe désactivé et le jeton d'équipe absent ; le journal d'opérateur nomme l'outil, la classe de l'exception et la classe de sa cause racine, jamais le message. Preuve : reproduction manuelle sur le jar reconstruit ; `MinosMcpProjectReferenceErrorTest`, `MinosMcpTypedErrorsTest`, `MinosMcpFailureLoggingTest`, `ProjectReferenceErrorTest`, `ProjectReferenceErrorApiTest`. `MinosMcpErrorRedactionTest` reste vert sans modification de ses deux premiers cas |
| Correction proposée | Catégories d'erreur publiques typées avec message sûr et fixe (« référence de projet non reconnue : utiliser le nom ou l'identifiant ») sans recopier la valeur ; conserver la redaction pour les erreurs d'E/S ; test de non-fuite explicite |
| Validation | Test sur `PublicErrorMessages` et le backend MCP : une référence de type chemin donne un message actionnable ne contenant pas le chemin ; les deux premiers cas de `MinosMcpErrorRedactionTest` restent verts |
| Dépendances | Q27 (même zone), C02 — `diagnostiquer-statut-mcp-et-erreurs` |

<a id="minos-aud-c02"></a>
### MINOS-AUD-C02 — Le MCP « lecture seule » inspecte les runtimes providers à chaque appel de statut

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-C02 |
| Titre | Effets de bord d'un outil MCP déclaré en lecture seule |
| Qualification | **Défaut confirmé** pour le chemin d'appel (vérification **L**) ; les effets de bord sous-jacents (écritures dans `MINOS_HOME` et `%LOCALAPPDATA%`, hachage d'arbres, sonde AppContainer) relèvent de l'analyse **A** ; la durée et l'échec en production sont un **risque** non chronométré |
| Priorité | **P1** — viole l'invariant central de l'ADR 0017 et la formulation de l'ADR 0052 (« lecture MCP sans effet de bord ») |
| Preuves | `MinosApplicationMcpBackend.java:86-91,94-101` : `projectStructure` et `indexStatus` appellent `providerProfiles()` (`:306-320`) → `ProviderPlatformService.listProviders()` (`:34-41`) → `runtimes.list()`. Selon l'analyse C : `ManagedScipProviderRuntimeManager.inspect` peut extraire/écrire `MINOS_HOME/tools` et recalcule un SHA-256 de tout l'arbre à chaque appel ; sous Windows, `WindowsAppContainerWorkerSandboxBackend.discover` matérialise un script et lance une sonde PowerShell |
| Comportement actuel | Un outil MCP censé lire seulement peut écrire, lancer un processus, hacher des centaines de Mio et dépasser le délai du client ; la CLI `index-status` n'ajoute pas `providerProfiles` |
| Comportement attendu | ADR 0017 (aucune mutation), `docs/user/mcp.md` (« Le MCP reste read-only »), ADR 0052 |
| Cause | `providerProfiles` réutilise un service conçu pour `providers`/`doctor`, sans mode d'inspection passive |
| Impact | Latence et échecs intermittents de `minos_index_status` ; écritures hors périmètre annoncé ; inutilisable sur `MINOS_HOME` en lecture seule (R12) |
| **Correction réalisée** (2026-10-07, **PR ouverte, CI de la PR à observer**) | `ProviderPlatformService.listStaticProfiles()` : profils statiques issus des descripteurs et du kit de conformité, `runtimeState` = `NOT_INSPECTED` (hypothèse de conception à confirmer, voir le design du changement), jamais d'appel au gestionnaire de runtimes ; `minos_index_status` et `minos_project_structure` l'utilisent, `providers`, `doctor` et l'API des providers continuent d'inspecter. Preuve : `MinosMcpReadOnlyHomeTest` (compteur nul, `MINOS_HOME` identique octet pour octet, variante Windows avec le gestionnaire réel exécutée sur ce poste), `ProviderPlatformStaticProfilesTest` |
| Correction proposée | Inspection **passive** : conserver le champ `providerProfiles` (retirer un champ publié casserait le contrat additif) avec des profils statiques et un état d'exécution non inspecté (valeur exacte : question ouverte du changement), sans amorçage, sans qualification de propriété, sans découverte de sandbox |
| Validation | `MinosMcpReadOnlyHomeTest` : empreinte de l'arborescence de `MINOS_HOME` identique avant/après chaque outil ; il n'existe aucune garde équivalente à `LazyWiringGuardTest` (CLI) côté MCP |
| Dépendances | C01, A07 — `diagnostiquer-statut-mcp-et-erreurs` |

<a id="minos-aud-f01"></a>
### MINOS-AUD-F01 — Impact, appelants et dépendances inter-modules sont aveugles aux occurrences SCIP, sans que la sortie le dise

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-F01 |
| Titre | Faux négatifs silencieux de l'analyse d'impact sur tout snapshot issu de SCIP |
| Qualification | **Défaut confirmé** d'honnêteté de capacité (vérification **L** : aucun producteur de `CALLS`/`IMPORTS`/`EXTENDS`/`INSTANTIATES` dans `src/main`, relu par l'auteur) ; l'effet sur un index réel n'a pas été exécuté |
| Priorité | **P1** — pour un outil d'impact, le faux négatif est le défaut grave ; il est ici silencieux. La limite est déclarée au niveau du **fournisseur** (`ScipIndexerCatalog` : « CALLS relations are not emitted explicitly ») mais n'atteint ni la sortie d'impact, ni celle d'architecture, ni `find_callers`. Le correctif de premier lot (déclarer la limite) est peu coûteux |
| Preuves | `ScipIngestionAdapter.java:168-184` (seules relations : celles de `SymbolInformation.relationships`, plus `DEPENDS_ON` dérivées) ; `ScipRelationshipNormalizer.java:85-115` ; occurrences stockées sans arête symbole→symbole (`:113-123,153-162`) ; `ScipSymbolNormalizer.java:149` passe `null` comme `parentSymbolId` ; `ImpactAnalysisService.java:27-42,198-220` (ne lit que `snapshot.relationships()`) ; `ImpactLimitation.java:6-14` ne nomme aucune limite d'occurrences ; `ArchitectureDependencyService.java:49-52` n'agrège que `DEPENDS_ON` ; `RelationshipQueryService.java:60-67` (`findCallers` lit `CALLS`, jamais produit par SCIP) |
| Comportement actuel | Sur un snapshot SCIP, `minos impact <symbole>` ne remonte que implémenteurs/surcharges, `is_reference` rares et tests liés ; un appelant ordinaire ou un module consommateur n'apparaît pas. Pour Java, `minos_impact_v2` complète par l'AST (name+arity), pas pour les autres langages |
| Comportement attendu | ADR 0015 (impact conservateur), ADR 0013 (architecture factuelle), ADR 0028 (capability-honest), règle `openspec/config.yaml` : « une capacité absente ou non qualifiée n'est jamais présentée comme acquise » |
| Cause | Aucune étape de dérivation occurrence→relation ; l'information (`enclosing_range`, `enclosing_symbol`) est lue puis jetée |
| Impact | Faux négatifs systématiques des surfaces CLI/API/MCP d'impact, d'appelants, de dépendances et d'architecture sur Java, TypeScript, Go, Rust, C#, C/C++ |
| Correction proposée | Lot 1 : déclarer explicitement la limite dans les sorties (limitation d'impact, message d'architecture, résultat vide d'appelants). Lot 2, conditionnel et **soumis à décision** : dériver des relations `REFERENCES` de nature dérivée depuis les occurrences ; exige l'amendement des ADR 0010 et 0015 |
| Validation | Lot 1 : test de sortie contenant la limite pour un snapshot SCIP avec occurrences non définitionnelles. Lot 2 : test bout-en-bout `ImpactAnalysisRealFixtureTest` voisin, `impacts()` contient l'appelant |
| Dépendances | F03 (même cause racine), F04 — `declarer-limites-impact-scip` |

<a id="minos-aud-a01"></a>
### MINOS-AUD-A01 — Windows : le lanceur AppContainer détruit les ACL et le profil d'un autre sandbox encore vivant

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-A01 |
| Titre | Récupération Windows de sandbox sans preuve de mort du propriétaire (équivalent Windows de S3) |
| Qualification | **Défaut confirmé** (chemin de code relu par l'auteur, vérification **L** ; aucun test) ; impact en concurrence **plausible**, non reproduit |
| Priorité | **P1** — S3 (Linux) a été classé haute et corrigé par une marque de propriétaire ; aucun correctif équivalent n'existe côté Windows |
| Preuves | `minos-runtime-local/src/main/resources/com/minos/runtime/local/windows-appcontainer-sandbox-v4.ps1.template` : `Recover-Stale` (≈ `:498-513`) parcourt **tous** les `*.json` du répertoire de reprise, retire l'ACE du SID de chaque chemin listé, supprime le profil AppContainer et le journal ; appelé inconditionnellement au démarrage de chaque lanceur (≈ `:535`) ; `Write-Recovery` (≈ `:491-496`) n'écrit que `profile`, `sid`, `paths` (ni PID, ni instant de démarrage, ni verrou) ; répertoire partagé par `MINOS_HOME` (`WindowsAppContainerWorkerSandboxBackend.java:315`) |
| Comportement actuel | Un second processus MINOS (autre `index`, serveur MCP/IDE, sonde de `doctor`) qui lance un lanceur supprime les droits et le profil du provider vivant de l'autre processus : échec « accès refusé » ou artefact illisible, sans rapport apparent avec la cause |
| Comportement attendu | Ne récupérer qu'un sandbox dont le propriétaire est prouvé mort. Sources : correctif S3 (`AUDIT-2026-09.md`), `CgroupJobOwnership` côté Linux, Javadoc de `ProcessTreeTermination` (« two concurrent jobs can never terminate one another ») |
| Cause | La reprise sur incident a été conçue pour un lanceur unique ; la concurrence inter-processus n'a pas été traitée côté PowerShell |
| Impact | Indexation Windows concurrente (CLI + MCP/IDE) non fiable ; diagnostic trompeur ; pas de fuite de confidentialité |
| Correction proposée | Marque de propriétaire dans le journal (PID + instant de démarrage + jeton d'instance) et récupération seulement si le propriétaire est prouvé mort ; en s'inspirant de `CgroupJobOwnership` sans reprendre ses défauts connus (R2 saut d'horloge, R11) |
| Validation | Test Windows seul (`@EnabledOnOs(OS.WINDOWS)`) : un second lanceur ne touche ni le journal ni l'ACL d'un premier lanceur vivant ; prouvé seulement par le runner Windows (voir T6) |
| Dépendances | A07 (la sonde déclenche le lanceur depuis des commandes de lecture) — `isoler-recuperation-appcontainer-par-proprietaire` |

<a id="minos-aud-c04"></a>
### MINOS-AUD-C04 — Plugin IntelliJ sous Windows : le lanceur `minos.cmd` par défaut ne démarre pas

| Champ | Contenu |
|---|---|
| Identifiant | MINOS-AUD-C04 |
| Titre | Double échappement `cmd /c` + sérialisation CRT du lanceur par défaut |
| Qualification | **Défaut confirmé par reconstitution** du plan de lancement avec le vrai script (vérification **R**) ; **non exécuté dans l'IDE** |
| Priorité | **P1 à confirmer** : le lanceur `minos.cmd` est le défaut documenté (`MinosSettingsState.java:81-83`, `docs/user/intellij-plugin.md:61,69`) ; contournement : configurer `minos.exe`. À rétrograder en P2 si le plugin livré se révèle fonctionnel |
| Preuves | `MinosCommandLine.java:12-58` (pour un `.cmd` sous Windows : `cmd.exe /d /v:off /s /c <rendu>` avec `^` devant `^ % & | < > "`) ; `windows-cli-job-owner-v1.ps1:244-282` (`BuildCommandLine`/`QuoteArgument` : échappe les guillemets par `\"`, que `cmd /s /c` ne comprend pas) ; `MinosCommandLineTest.java:22-32` vérifie seulement `contains(...)` sur la chaîne |
| Comportement actuel | Sur le plan reconstitué : `'\"C:\Users\…\echoargs.cmd\"' n'est pas reconnu en tant que commande interne ou externe`, code 1, pour des arguments simples, une `List<String>` et `a&b`. Aucun test existant ne lance un vrai `.cmd` |
| Comportement attendu | Le lanceur documenté fonctionne ; le plugin est un client du protocole CLI JSON (ADR 0027, `public-surfaces.md`) |
| Cause | Deux couches de quoting superposées (rendu façon `cmd` et sérialisation façon CRT), la seconde invisible à la première |
| Impact | Plugin inutilisable avec le lanceur par défaut sous Windows ; effet secondaire à vérifier : `%` et `^` doublés, qui altèrent les arguments libres (recherche sémantique, symboles) |
| Correction proposée | Écrire la chaîne `cmd /c` brute sans passer par `QuoteArgument`, ou résoudre `minos.cmd` vers le `minos.exe` voisin quand il existe ; refuser les arguments contenant `"`, `%`, `^` pour un `.cmd` |
| Validation | `MinosCommandLineBatchLaunchTest` Windows seul : un `echoargs.cmd` reçoit `simple`, `50% off`, `a&b` à l'identique |
| Dépendances | G5 (le gate du plugin n'est exigé par rien), T6 — `corriger-lancement-plugin-intellij-windows` |

## 3. Registre complet

**101 constats** : P1 = 7, P2 = 39, P3 = 55. Aucun P0.

Tri : priorité puis identifiant. « Fiche » renvoie à la fiche complète dans l'annexe ; les P1 ont aussi leur fiche détaillée en § 2. Les titres sont ceux des annexes (tronqués si longs).

| ID | Titre | Qualification | Prio | Vérif. | Changement OpenSpec | Fiche |
|---|---|---|---|---|---|---|
| MINOS-AUD-A01 | Windows : le lanceur AppContainer détruit les ACL et le profil d'un autre sandbox encore vivant | Défaut confirmé | P1 | L | `isoler-recuperation-appcontainer-par-proprietaire` | [A-01](annexes/A-confinement.md) |
| MINOS-AUD-B01 | Un refus RBAC dont l'identifiant de ressource n'est pas canonique corrompt définitivement la chaîne d'audit du tenant | Défaut confirmé | P1 | E | `fiabiliser-chaine-audit-tenant` | [B-01](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B02 | Les refus chaînés ne sont pas bornés en octets : un VIEWER peut saturer la limite de 32 MiB et bloquer les écritures autorisées | Défaut confirmé | P1 | R | `fiabiliser-chaine-audit-tenant` | [B-02](annexes/B-tenant-secrets.md) |
| MINOS-AUD-C01 | `minos_index_status` / `minos_project_structure` : l'erreur est opaque (« MINOS tool execution failed ») y compris pour une référence de projet inc… | Défaut confirmé | P1 | E | `diagnostiquer-statut-mcp-et-erreurs` | [C-01](annexes/C-surfaces.md) |
| MINOS-AUD-C02 | Le MCP « lecture seule » inspecte les runtimes providers à chaque appel de statut : effets de bord, hachage de gros arbres, sonde AppContainer (Win… | Défaut confirmé + Risque | P1 | L | `diagnostiquer-statut-mcp-et-erreurs` | [C-02](annexes/C-surfaces.md) |
| MINOS-AUD-C04 | Plugin IntelliJ sous Windows : le lanceur `minos.cmd` (défaut documenté) ne démarre pas, double échappement `cmd /c` + `CommandLineToArgvW` | Défaut confirmé | P1 | R | `corriger-lancement-plugin-intellij-windows` | [C-04](annexes/C-surfaces.md) |
| MINOS-AUD-F01 | Impact, appelants et dépendances inter-modules aveugles aux occurrences SCIP, sans limitation déclarée | Défaut confirmé | P1 | L | `declarer-limites-impact-scip` | [F-01](annexes/F-providers-requetes.md) |
| MINOS-AUD-A02 | Répertoires de travail et de transit jamais récupérés après un arrêt brutal | Défaut confirmé | P2 | A | `isoler-recuperation-appcontainer-par-proprietaire` | [A-02](annexes/A-confinement.md) |
| MINOS-AUD-A03 | Linux : `prlimit --cpu` et `pids.max` plus serrés que le budget agrégé annoncé | Risque | P2 | R | — | [A-03](annexes/A-confinement.md) |
| MINOS-AUD-B03 | Un refus peut disparaître sans trace ou changer de nature d'erreur (identifiant invalide ; conflit de version) | Défaut confirmé + Risque | P2 | E | `fiabiliser-chaine-audit-tenant` | [B-03](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B04 | Après une rotation, la clé retirée garde un pouvoir de pollution de l'audit sans limite effective, et un jeton périmé est traité comme un refus RBAC | Défaut confirmé + Risque | P2 | R | `fiabiliser-chaine-audit-tenant` | [B-04](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B05 | Rejeu de l'état chiffré (rollback) indétectable ; l'AAD avec version proposée par S10 ne le corrigerait pas | Décision à clarifier | P2 | A | — | [B-05](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B06 | Le rôle ADMIN peut effacer la piste d'audit (rétention) y compris ses propres actions | Décision à clarifier | P2 | A | — | [B-06](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B07 | La politique d'URL PostgreSQL accepte `SSLMODE=verify-full` que le pilote ignore : TLS sans vérification | Défaut confirmé | P2 | E | `durcir-configuration-postgresql-et-secrets` | [B-07](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B08 | Un BOM UTF-8 supprime silencieusement la première propriété et s'ajoute au mot de passe lu depuis un fichier | Défaut confirmé | P2 | R | `durcir-configuration-postgresql-et-secrets` | [B-08](annexes/B-tenant-secrets.md) |
| MINOS-AUD-C03 | Le statut d'un projet (CLI `project list`/`index-status`, MCP `minos_index_status`, plugin) paie une découverte complète du dépôt ; un seul réperto… | Défaut confirmé + Risque | P2 | A | `diagnostiquer-statut-mcp-et-erreurs` | [C-03](annexes/C-surfaces.md) |
| MINOS-AUD-C05 | `minos_hybrid_context` (et `ide hybrid-context`) : un `maxTokens` < 800 est refusé alors que le schéma l'autorise | Défaut confirmé | P2 | A | `diagnostiquer-statut-mcp-et-erreurs` | [C-05](annexes/C-surfaces.md) |
| MINOS-AUD-C06 | `MinosApiSupport.openApplication` ne traduit que `IOException` : les `RuntimeException` d'ouverture sortent brutes de `new LocalMinosApi(Path)` & c… | Défaut confirmé | P2 | A | `diagnostiquer-statut-mcp-et-erreurs` | [C-06](annexes/C-surfaces.md) |
| MINOS-AUD-C07 | Plugin : « Index », « Reindex Full » et « Synchronize semantic index » tournent sous le délai global de 30 s (max 300 s) | Défaut de conception + Décision à clarifier | P2 | A | `corriger-lancement-plugin-intellij-windows` | [C-07](annexes/C-surfaces.md) |
| MINOS-AUD-D01 | Un répertoire illisible, même ignoré (`.gitignore`) ou « durci » (`node_modules`, `target`…), fait échouer découverte, empreinte et statut du projet | Défaut confirmé + Décision à clarifier | P2 | A | `tolerer-repertoires-illisibles-a-la-decouverte` | [D-01](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D02 | Le statut d'index dépend d'une découverte complète du système de fichiers et d'un décodage complet du snapshot actif | Risque + Amélioration | P2 | A | `diagnostiquer-statut-mcp-et-erreurs` | [D-02](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D03 | Observer le snapshot actif décode le snapshot entier ; au-delà de 512 MiB de poids estimé, aucun cache : au moins six décodages complets par `minos… | Amélioration | P2 | A | — | [D-03](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D04 | Une reprise est « offerte » et affichée pour des indexeurs qui ne déclarent pas `RESUMABLE_ARTIFACT` | Défaut confirmé | P2 | A | — | [D-04](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D05 | La capture d'empreinte post-run n'est pas protégée : une erreur d'E/S rapporte un échec alors que le snapshot est déjà promu | Défaut confirmé | P2 | A | — | [D-05](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D11 | Les budgets (100 000 fichiers, 2 Gio) comptent tous les fichiers non ignorés, binaires inclus, et l'échec est sans issue actionnable | Décision à clarifier | P2 | A | — | [D-11](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-E01 | Le backend PostgreSQL ne persiste jamais `resumableRunId` : la reprise ADR 0039 est inopérante avec PostgreSQL, et la rétention PG ne protège pas l… | Défaut confirmé | P2 | A | — | [E-01](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E02 | Le garde `check-module-boundaries.py` : règles A2 sans auto-test (écart avec l'ADR 0042 §8.4) et trois contournements prouvés | Défaut confirmé + Risque | P2 | A | — | [E-02](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E03 | Aucun garde-fou mémoire avant le décodage d'un snapshot : un snapshot valide au plafond de 256 Mio peut provoquer un `OutOfMemoryError` | Risque | P2 | R | — | [E-03](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E04 | Le backlog et l'ADR 0055/0056 ne décrivent pas tout le couplage réel (4 écarts avec le code) | Décision à clarifier | P2 | A | — | [E-04](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-F02 | Tests liés : classification « test » non polyglotte et ancre unique par fichier | Défaut confirmé | P2 | A | `declarer-limites-impact-scip` | [F-02](annexes/F-providers-requetes.md) |
| MINOS-AUD-F03 | Corrélation runtime ligne→symbole : seule la plage de l'identifiant est connue | Défaut confirmé | P2 | A | `declarer-limites-impact-scip` | [F-03](annexes/F-providers-requetes.md) |
| MINOS-AUD-F04 | `Symbol.moduleId` jamais renseigné par l'indexation autonome | Défaut confirmé | P2 | A | `declarer-limites-impact-scip` | [F-04](annexes/F-providers-requetes.md) |
| MINOS-AUD-F05 | Recherche hybride : pas de repli quand le provider d'embeddings échoue, limitations sémantiques perdues | Défaut confirmé | P2 | A | — | [F-05](annexes/F-providers-requetes.md) |
| MINOS-AUD-F06 | Aucun garde de fraîcheur entre source sur disque et snapshot (documents sémantiques, extraits) | Décision à clarifier | P2 | A | — | [F-06](annexes/F-providers-requetes.md) |
| MINOS-AUD-G01 | En-têtes de date de STATUS/ROADMAP/architecture inconsistants avec leur contenu | ? | P2 | A | `reconcilier-documentation-courante` | [G-01](annexes/G-adr-documentation.md) |
| MINOS-AUD-G02 | ADR-0036 : « Proposed » dans l'index et arc42, « Accepted » dans son fichier, et implémenté | ? | P2 | A | `reconcilier-documentation-courante` | [G-02](annexes/G-adr-documentation.md) |
| MINOS-AUD-G03 | ADR-0021 : en-tête « Accepted » alors que l'index, arc42 et ADR-0037 le disent partiellement supersédé | ? | P2 | A | `reconcilier-documentation-courante` | [G-03](annexes/G-adr-documentation.md) |
| MINOS-AUD-G07 | ADR-0031 §2 « loopback only » contredit par l'endpoint Docker managé | ? | P2 | A | `reconcilier-documentation-courante` | [G-07](annexes/G-adr-documentation.md) |
| MINOS-AUD-G08 | Backend PostgreSQL/pgvector livré sans ADR, en tension avec 0025 et 0031 §8 | ? | P2 | A | `reconcilier-documentation-courante` | [G-08](annexes/G-adr-documentation.md) |
| MINOS-AUD-G09 | `arc42/09-decisions.md` arrêté à ADR-0037 ; dates divergentes ; SYNTHESE à « 37 ADR » | ? | P2 | A | `reconcilier-documentation-courante` | [G-09](annexes/G-adr-documentation.md) |
| MINOS-AUD-G11 | Maven Wrapper annoncé 3.9.16 ; le dépôt est en 3.10.0 | ? | P2 | L | `reconcilier-documentation-courante` | [G-11](annexes/G-adr-documentation.md) |
| MINOS-AUD-G12 | ROADMAP : « ligne de développement courante 1.1.0-SNAPSHOT » | ? | P2 | A | `reconcilier-documentation-courante` | [G-12](annexes/G-adr-documentation.md) |
| MINOS-AUD-G13 | ROADMAP : ADR-0039 listé en « conception proposée » | ? | P2 | A | `reconcilier-documentation-courante` | [G-13](annexes/G-adr-documentation.md) |
| MINOS-AUD-G14 | ROADMAP : « Post-228 Hardening Invariants … exécute » alors que ce workflow n'existe plus | ? | P2 | A | `reconcilier-documentation-courante` | [G-14](annexes/G-adr-documentation.md) |
| MINOS-AUD-G16 | README racine : état arrêté à 1.0.1 ; `minos-app` « composition root » | ? | P2 | A | `reconcilier-documentation-courante` | [G-16](annexes/G-adr-documentation.md) |
| MINOS-AUD-G17 | Docs d'architecture/ADR avec références de modules périmées | ? | P2 | A | `reconcilier-documentation-courante` | [G-17](annexes/G-adr-documentation.md) |
| MINOS-AUD-A04 | Windows : une variable de toolchain ouvre en lecture récursive tout son répertoire à l'AppContainer (ex. `CARGO_HOME` et `credentials.toml`) | Risque | P3 | A | — | [A-04](annexes/A-confinement.md) |
| MINOS-AUD-A05 | `DistributedArtifactBundleStore` : clé de cache indépendante du run, mais « hit » exige un manifeste identique (run, horodatages) — le cache n'est… | Défaut confirmé | P3 | A | — | [A-05](annexes/A-confinement.md) |
| MINOS-AUD-A06 | Résidus de provider conservés par leur nom sans contrôle de type ; `index.scip` périmé non nettoyé avant exécution | Risque + Amélioration | P3 | A | — | [A-06](annexes/A-confinement.md) |
| MINOS-AUD-A07 | Des commandes de lecture/diagnostic mutent le système (cgroup, ACL, script lanceur) pour qualifier le sandbox | Décision à clarifier | P3 | A | — | [A-07](annexes/A-confinement.md) |
| MINOS-AUD-A08 | `RemoteRepositoryRequest.projectSubdirectory` : rejet tardif (après un clone complet) des formes Windows non absolues mais ancrées | Amélioration | P3 | A | — | [A-08](annexes/A-confinement.md) |
| MINOS-AUD-A09 | Cache distant : une entrée épinglée au contenu illisible bloque toute nouvelle matérialisation ; entrées valides re-clonées sur la foi de `git status` | Risque | P3 | A | — | [A-09](annexes/A-confinement.md) |
| MINOS-AUD-A10 | Prérequis à lever avant de rouvrir ADR 0041 : `ALLOW` n'a pas la même portée sous Linux et Windows | Décision à clarifier | P3 | A | — | [A-10](annexes/A-confinement.md) |
| MINOS-AUD-B09 | `DurableAtomicFile.publish` remplace un fichier existant, contrairement à son contrat | Défaut confirmé | P3 | R | `durcir-configuration-postgresql-et-secrets` | [B-09](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B10 | Hygiène de rotation et de jetons : alias d'identifiants de clé, réutilisation d'une ancienne clé, aucune révocation unitaire | Risque + Amélioration | P3 | A | — | [B-10](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B11 | Contrat « idempotence » du `--request-id` annoncé mais non tenu | Décision à clarifier | P3 | A | — | [B-11](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B12 | Documentation contredite par le code : l'échec d'export de l'audit n'est pas « remonté à l'appelant » | Défaut confirmé | P3 | A | — | [B-12](annexes/B-tenant-secrets.md) |
| MINOS-AUD-B13 | `PostgresJdbcUrlPolicy` ne reconnaît pas l'IPv6 de bouclage | Défaut confirmé | P3 | A | `durcir-configuration-postgresql-et-secrets` | [B-13](annexes/B-tenant-secrets.md) |
| MINOS-AUD-C08 | Plugin : la persistance du plan Windows écrit l'environnement complet de l'IDE ; le `.ps1` est réécrit à chaque appel | Infirmé en grande partie (erratum § 4) | P3 | A | — | [C-08](annexes/C-surfaces.md) |
| MINOS-AUD-C09 | Plugin : un handshake (processus JVM) précède chaque action M21 ; la fenêtre de versions négociée est ignorée | Dérive infirmée (conforme ADR 0027) + Amélioration | P3 | A | — | [C-09](annexes/C-surfaces.md) |
| MINOS-AUD-C10 | `index-status`/`inspect <nom>` : code 3 avec stdout vide quand un nom est introuvable devant des entrées illisibles | Décision à clarifier | P3 | A | — | [C-10](annexes/C-surfaces.md) |
| MINOS-AUD-C11 | `minos_architecture_graph` : `format=json` + `module` ne rend pas un graphe de voisinage (description et doc divergent du code) | Dérive doc/contrat | P3 | A | — | [C-11](annexes/C-surfaces.md) |
| MINOS-AUD-C12 | Inventaire CLI incomplet : `mcp` et les huit opérations `ide <op>` absentes de `minos --help` et de `product-facts` | Dérive doc/contrat | P3 | A | — | [C-12](annexes/C-surfaces.md) |
| MINOS-AUD-C13 | MCP : la validation des arguments dépend du validateur de schéma du SDK (fail-open s'il est absent) et n'est testée par aucun test de bout en bout | Risque | P3 | A | — | [C-13](annexes/C-surfaces.md) |
| MINOS-AUD-C14 | NEXUS : la résolution des `fileId` de forme `file:<sha256>` parcourt tout le dépôt sans politique d'ignore, et la moindre erreur d'accès fait échou… | Risque | P3 | A | — | [C-14](annexes/C-surfaces.md) |
| MINOS-AUD-C15 | MCP : bornes de réponse et de durée trop larges, pas d'annulation, et aucune mention que le contenu renvoyé est non fiable | Amélioration | P3 | A | — | [C-15](annexes/C-surfaces.md) |
| MINOS-AUD-C16 | `ide <op>` : un `IOException` d'exécution est rapporté « MINOS bootstrap failed » | Amélioration | P3 | A | `diagnostiquer-statut-mcp-et-erreurs` | [C-16](annexes/C-surfaces.md) |
| MINOS-AUD-D06 | TOCTOU : la découverte précède l'empreinte, donc une structure créée entre les deux est promue en baseline sans avoir été indexée | Défaut confirmé | P3 | A | `tolerer-repertoires-illisibles-a-la-decouverte` | [D-06](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D07 | Deux chemins écrivent un état `READY` sans clore l'offre de reprise (même motif que R7, non corrigé ici) | Défaut confirmé | P3 | A | — | [D-07](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D08 | Observation `UNSUPPORTED` + bail exclusif : un état `INDEXING` laissé par un run mort n'est jamais récupéré (blocage permanent) | Risque | P3 | A | — | [D-08](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D09 | Une `Error` (OOM, `StackOverflowError`) pendant le staging laisse run `RUNNING` et projet `INDEXING` sans cause | Amélioration | P3 | A | — | [D-09](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D10 | Sémantique d'ignore divergente de Git sur Windows : BOM UTF-8 et casse | Défaut confirmé + Risque | P3 | A | `tolerer-repertoires-illisibles-a-la-decouverte` | [D-10](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D12 | `NO_CHANGES` silencieux face aux liens symboliques et aux répertoires durcis | Décision à clarifier | P3 | A | `tolerer-repertoires-illisibles-a-la-decouverte` | [D-12](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D13 | La reprise ne fige ni la version de MINOS ni celle de l'importateur : un snapshot préparé périmé peut être promu tel quel | Risque | P3 | A | — | [D-13](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-D14 | Deux sources de vérité pour les capacités : `IndexerDescriptor.capabilities` (négociation) et `ProviderCapabilityProfile` (public) | Amélioration | P3 | A | — | [D-14](annexes/D-cycle-de-vie.md) |
| MINOS-AUD-E05 | `minos doctor` inspecte des répertoires qui n'existent pas et n'inspecte pas les vrais magasins | Défaut confirmé | P3 | A | — | [E-05](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E06 | PostgreSQL : un seul verrou advisory sert à la fois de bail de cycle de vie et de verrou de mutation, avec une attente serveur non bornée | Risque | P3 | A | — | [E-06](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E07 | Hybride : snapshot et index sémantique lus à des instants différents ; l'identité de snapshot n'est pas garantie pendant une promotion | Risque | P3 | A | — | [E-07](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E08 | PostgreSQL : le répertoire scratch n'est jamais balayé, et chaque lecture non mise en cache passe trois fois par le disque | Risque | P3 | A | — | [E-08](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E09 | Toute lecture d'un snapshot actif construit l'index complet, même quand l'appelant n'a besoin que du snapshot | Amélioration | P3 | A | — | [E-09](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E10 | Quatre cycles de dépendances entre packages, non gardés | Amélioration | P3 | A | — | [E-10](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E11 | ADR « Accepted » partiellement périmés et code mort public | Décision à clarifier | P3 | A | — | [E-11](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-E12 | PostgreSQL exécute des DDL (extension, schéma, migrations) à chaque ouverture du backend, y compris pour une commande de lecture | Décision à clarifier | P3 | A | — | [E-12](annexes/E-persistance-frontieres.md) |
| MINOS-AUD-F07 | Entrées SCIP anormales : politique incohérente (abandon, saut silencieux, identifiant opaque) | Risque | P3 | A | — | [F-07](annexes/F-providers-requetes.md) |
| MINOS-AUD-F08 | `occurrenceId` sans les rôles : fusion silencieuse d'occurrences | Défaut de conception + Risque | P3 | R | — | [F-08](annexes/F-providers-requetes.md) |
| MINOS-AUD-F09 | Descripteur vs profil de capacités : dérive concrète qui change la négociation (complète A5) | Défaut confirmé | P3 | A | — | [F-09](annexes/F-providers-requetes.md) |
| MINOS-AUD-F10 | Extracteurs de nom/genre : surcharges `(+n).` et genres SCIP non mappés | Défaut confirmé + Amélioration | P3 | A | — | [F-10](annexes/F-providers-requetes.md) |
| MINOS-AUD-F11 | Binaires opérés acceptés sans vérification de leur épinglage | Décision à clarifier | P3 | A | — | [F-11](annexes/F-providers-requetes.md) |
| MINOS-AUD-F12 | Impact : chemin explicatif arbitraire, tests tronqués par `maxResults` | Amélioration | P3 | A | — | [F-12](annexes/F-providers-requetes.md) |
| MINOS-AUD-F13 | Recherche de symboles : rang 6 sur `symbolKey` (hash) et coût de tri | Amélioration | P3 | A | — | [F-13](annexes/F-providers-requetes.md) |
| MINOS-AUD-F14 | Index vectoriel : budget d'écriture plus large que le budget de lecture | Risque | P3 | A | — | [F-14](annexes/F-providers-requetes.md) |
| MINOS-AUD-G04 | ADR-0037 : « parité Docker non acquise » alors que M29 est clos | ? | P3 | A | `reconcilier-documentation-courante` | [G-04](annexes/G-adr-documentation.md) |
| MINOS-AUD-G05 | ADR-0022 : liste de modules périmée, amendements 0042/0044/0055 non annotés | ? | P3 | A | `reconcilier-documentation-courante` | [G-05](annexes/G-adr-documentation.md) |
| MINOS-AUD-G06 | ADR-0033 et ADR-0038 : amendement par ADR-0041 non reflété | ? | P3 | A | `reconcilier-documentation-courante` | [G-06](annexes/G-adr-documentation.md) |
| MINOS-AUD-G10 | Note « PR #333 non fusionnée » périmée | ? | P3 | A | `reconcilier-documentation-courante` | [G-10](annexes/G-adr-documentation.md) |
| MINOS-AUD-G15 | STATUS : « deux jobs » alors que `pr-ci.yml` en compte trois | ? | P3 | A | `reconcilier-documentation-courante` | [G-15](annexes/G-adr-documentation.md) |
| MINOS-AUD-G18 | Liens morts | ? | P3 | A | `reconcilier-documentation-courante` | [G-18](annexes/G-adr-documentation.md) |
| MINOS-AUD-G19 | STATUS/ROADMAP : « snapshots v1/v2 plafond 256 MiB » sans V3 | ? | P3 | A | `reconcilier-documentation-courante` | [G-19](annexes/G-adr-documentation.md) |
| MINOS-AUD-G20 | Index ADR : titres divergents et vocabulaire de statut | ? | P3 | A | `reconcilier-documentation-courante` | [G-20](annexes/G-adr-documentation.md) |
| MINOS-AUD-G21 | Tag local `v1.0.1` différent du tag distant | ? | P3 | A | `reconcilier-documentation-courante` | [G-21](annexes/G-adr-documentation.md) |
| MINOS-AUD-G22 | `docs/audit/CAPACITES.md` (non suivi) : « 50 ADR » | ? | P3 | A | `reconcilier-documentation-courante` | [G-22](annexes/G-adr-documentation.md) |

## 4. Ajustements de priorité et errata par rapport aux annexes

Les annexes sont conservées telles que remises ; ce tableau fait foi quand il les contredit.

| Constat | Annexe | Retenu | Raison |
|---|---|---|---|
| MINOS-AUD-D01 | P1 | **P2** | Un contournement existe (changer les droits du répertoire) ; la prémisse fail-closed est défendable pour un répertoire **non ignoré** (décision à clarifier) ; l'échec pour un répertoire ignoré ou durci reste un défaut confirmé, traité dans `tolerer-repertoires-illisibles-a-la-decouverte` |
| MINOS-AUD-F01 | « sans limitation déclarée » | P1 maintenu, **nuancé** | La limite est déclarée au niveau fournisseur (`ScipIndexerCatalog`) mais pas dans les sorties ; le premier lot ne corrige que cela |
| MINOS-AUD-C04 | P1 | P1 **à confirmer** | Reproduit par reconstitution, non exécuté dans l'IDE |
| MINOS-AUD-C08 | P2 | **P3, infirmé en grande partie** | `windows-cli-job-owner-v1.ps1:327` supprime le plan (`Remove-Item -LiteralPath $Plan`) avant de démarrer la CLI, et `MinosStrongProcessLauncherTest.ownershipPlanIsDeletedBeforeOwnedChildCompletes` le vérifie. L'environnement ne reste donc sur disque que pendant la fenêtre entre l'écriture et la lecture du plan. Relevé et vérifié pendant la rédaction du changement `corriger-lancement-plugin-intellij-windows` ; aucune exigence n'en est tirée |
| MINOS-AUD-C09 | « dérive de contrat » | **Dérive infirmée** | La fenêtre de versions ignorée est conforme à l'ADR 0027 (`protocolVersion = "1"` strict). Il reste une amélioration de performance (handshake à chaque action) ; accepter une fenêtre exigerait de modifier l'ADR 0027 |
| MINOS-AUD-C04 (fiche) | fixture `echo [%~2]` | **fixture à corriger** | Pour `a&b`, `%~2` développé est relu comme séparateur : copier d'abord chaque argument dans une variable entre guillemets (`set "A2=%~2"`). Le contournement `app\minos.exe` est documenté dans `docs/user/mcp.md` et `docs/user/README.md`, pas dans `intellij-plugin.md` |
| MINOS-AUD-G11 (fiche) | incomplet | **étendu** | `docs/user/docker-runtime.md:79` affirme que le SHA-256 Maven de l'image est « identique au checksum du Maven Wrapper », ce qui est faux (image 3.9.16, wrapper 3.10.0). D'autres documents répètent « 3.9.x » : `docs/TOOLCHAIN_POLICY.md`, `docs/developer/README.md`, `docs/user/troubleshooting.md`, arc42/02 et arc42/04 |
| MINOS-AUD-G18 (fiche) | incomplet | **étendu** | Six liens de `docs/audit/CI-HYGIENE-SUIVI.md` pointent vers des workflows supprimés, et `PROMPT-SPRINT-2-3-RESTANT.md` contient un lien `docs/adr/` mort |
| MINOS-AUD-B02 (correction) | « aligner `MAX_AUDIT_EVENTS` » | **correction remplacée** | Changement de contrat public et inconnaissable côté moteur ; retenu : admission par octets additive (changement `fiabiliser-chaine-audit-tenant`) |
| MINOS-AUD-B04 (fiche) | « une clé retirée n'authentifie plus rien (ADR 0035 à amender) » | **inexact au HEAD** | `authenticate` accepte volontairement les clés retirées ; le changement ne traite que le classement (échec d'authentification) et laisse le reste en question ouverte |
| MINOS-AUD-B03 (fiche) | échec de `save` | **incomplet** | `CommitUncertainException` peut produire une double trace ; ajouté au design |
| MINOS-AUD-C02 (fiche) | « aucun golden ne mentionne `providerProfiles` » | **inexact** | `mcp.golden` et `mcp-all-tools.golden` le contiennent, sans conséquence : `CharacterizationNormalizer` remplace `runtimeState` et `runtimeDiagnostics` par `<host>` |
| MINOS-AUD-C05 (fiche) | « 127 ≤ maxTokens ≤ 799 » | **borne corrigée** | Le minimum du schéma est 128 : valeurs refusées 128 à 799 |
| MINOS-AUD-C01 (contrainte) | — | **ajout** | Les goldens `api`, `cli-text`, `cli-json`, `mcp` et deux tests épinglent `unknown project: a2-missing-project` : ce texte est conservé pour les références non sensibles ; seul un texte fixe sans écho sert pour les références de type chemin ou sensibles |
| MINOS-AUD-D01 (fiche) | trois visiteurs | **cinq sites** | S'ajoutent `ProviderWorkspaceFiles.copyWorkspace` (copie provider : sans lui, `minos index` échoue encore) et `ProjectFingerprintService.toolingDirectory` ; `BoundedProviderSourceProbe` et le staging scip-java sont à vérifier. Aucun scope JaCoCo ne cible `com/minos/discovery/` ni `com/minos/incremental/` |
| MINOS-AUD-A01 (fiche) | renommer le gabarit `-v4` | **non retenu** | Le script est placé par empreinte SHA-256 ; un renommage toucherait 4 gates, 3 tests et un golden. `WindowsContainmentScriptTest` compare l'assemblage à un golden pré-remédiation : la reprise passe par un fragment épinglé par SHA-256 |
| MINOS-AUD-A02 (fiche) | balayage par âge | **réserve** | `distributed-workers/` et `.accept-*` sont dormants (ADR 0041) ; un balayage par âge seul est sensible aux sauts d'horloge ; un résidu `appcontainer-probe-*` n'était pas listé |
| MINOS-AUD-F01 (fiche) | `findCallers` | **précision** | `findCallers` n'est appelé par aucune surface : CLI et MCP passent par `ProjectSymbolQuery.findRelationships`. Les sorties d'architecture n'ont aucun champ de limitations (il faut un champ additif, pas seulement un message) ; `ArchitectureDto` est un record public (constructeur historique conservé) ; l'API Java reste non couverte au premier lot, la liste de `MinosApi.findRelationships` ne pouvant porter de limitations |
| MINOS-AUD-F04 (fiche) | module dérivé de `projectRelativeRoot` | **correction fausse** | Un réacteur Maven `MULTI_MODULE` s'indexe en une seule portée à la racine (`IndexerExecutionScopeResolver`) : l'attribution doit se faire par symbole selon le chemin du fichier, avec la règle de `ArchitectureModuleResolver`, aujourd'hui dans `minos-application`, donc à extraire vers `minos-engine` ; l'identifiant de module est un condensat `module:<sha256>`, pas un nom |
| MINOS-AUD-B07 (fiche) | politique Java | **jumeau non cité** | `Assert-ExternalPostgresUrl` dans `scripts/install/configure-runtime-settings.ps1` décode aussi la clé et la met en minuscules ; sans test automatique |
| MINOS-AUD-B08 (fiche) | `MinosRuntimeSettingsTest` | **test inexistant** | Les tests vont dans `AbsoluteSecretFileTest` et `StorageBackendConfigurationTest` |
| MINOS-AUD-B09 (fiche) | publication atomique | **couture de test** | `Platform` expose une couture `mover` ; un test existant suppose que la publication passe par elle : une couture `linker` est à ajouter. Sans lien physique (exFAT, certains partages réseau), le défaut retenu est un refus explicite : régression possible pour un `MINOS_HOME` sur ces supports |
| MINOS-AUD-G21, G22 | P3 | **non retenus** | G-21 décrit l'état d'une machine (tag local) ; G-22 concerne un fichier alors non versionné, désormais réglé par le présent audit |

## 5. Constats connus revérifiés au HEAD (audit précédent)

| Constat | Résultat au HEAD | Source |
|---|---|---|
| S1, S2/S12/S13, S4, S6 (magasin hébergé) | Clos et tenu | annexe B, § 0 |
| S10 (AAD sans version) | **Toujours ouvert** ; le correctif suggéré par l'audit précédent (ajouter la version à l'AAD) est **inefficace** contre le rejeu : voir MINOS-AUD-B05 | annexe B |
| R8 | Confirmé, **plus large** : `staged-snapshots/<runId>` fuit aussi pour tout run échoué par exception après le staging, pas seulement un run tué | annexe D |
| R10, R12 | Confirmés | annexe D |
| A1 (indexation distante fermée) | Pas de régression | annexe A |
| 16 constats clos échantillonnés | Aucun contredit | annexe G |

## 6. Suivi des corrections

| Constat | Changement | État au 2026-10-06 | Preuve |
|---|---|---|---|
| MINOS-AUD-B01 | `fiabiliser-chaine-audit-tenant` | **Corrigé en local**, PR ouverte, CI de la PR à observer | tests rouges (3) devenus verts ; vecteurs HMAC inchangés ; `HostedAuditResourceIdCanonicalFormTest` |
| MINOS-AUD-B02 | idem | **Corrigé en local** (refus bornés en octets ; réserve : événements autorisés non bornés) | `HostedDenialByteBudgetTest` (+ mutation), `HostedEventSizeEstimateTest` |
| MINOS-AUD-B03 | idem | **Corrigé en local** | `HostedRefusalTraceTest` (5), `HostedAuthorizedInvalidIdentifierTest`, `LocalMinosTeamApiTest` (code `ACCESS_DENIED`) |
| MINOS-AUD-B04 | idem | **Corrigé en local pour le classement** (clé retirée = échec d'authentification, journal borné) ; l'authentification par une clé retirée au niveau du fournisseur d'identité reste une décision ouverte | `HostedKeyRotationRefusalTest` (6) |

Vérification : `./mvnw -B -ntp clean verify` vert (15 min 46 s, ≈ 2 079 tests, 0 échec), 20 gates statiques verts, gate JaCoCo vert (`m27-team-hosted-control-plane` : lignes 0,9155, branches 0,7004). Voir « Évidence d'implémentation » dans les [tâches du changement](../../openspec/changes/fiabiliser-chaine-audit-tenant/tasks.md). **Non fait** : exécution sur Ubuntu 24.04 et Windows Server 2022 par la CI de qualification ; les décisions ouvertes du changement (réparation d'un tenant déjà corrompu, alignement de `MAX_AUDIT_EVENTS`, budget global par tenant, refus des clés retirées par le fournisseur d'identité) ne sont pas tranchées.

### Suivi du changement `diagnostiquer-statut-mcp-et-erreurs` (2026-10-07)

| Constat | État | Preuve |
|---|---|---|
| MINOS-AUD-C01 | **Corrigé en local**, PR ouverte, CI de la PR à observer | tests listés dans la fiche ; commande manuelle sur le jar reconstruit |
| MINOS-AUD-C02 | **Corrigé en local** (la valeur `NOT_INSPECTED` reste une décision à confirmer) | `MinosMcpReadOnlyHomeTest`, `ProviderPlatformStaticProfilesTest` |
| MINOS-AUD-C03, MINOS-AUD-D02 | **Corrigé en local** pour la découverte : `index-status` (CLI) et `minos_index_status` (MCP) ne parcourent plus le dépôt. Le décodage complet du snapshot (D03) reste hors périmètre | `ProjectStatusWithoutDiscoveryTest`, `MinosMcpIndexStatusWithoutDiscoveryTest`, `ProjectCommandResumeStatusTest` |
| MINOS-AUD-C05 | **Corrigé en local** (cas symétrique `maxTokensPerDocument` seul > défaut non traité) | `MinosMcpHybridContextBoundsTest`, `IdeIntelligenceCommandTest` |
| MINOS-AUD-C06 | **Corrigé en local** | `OpenFailureTranslationTest` |
| MINOS-AUD-C16 | **Corrigé en local** | `IdeIntelligenceCommandTest` |

Vérification : `clean verify` vert (16 min 44 s, 2 119 tests, 0 échec), 21 gates verts, gate JaCoCo vert. Le golden `mcp-all-tools.golden` change de 5 lignes (outils d'équipe, mode équipe désactivé), voulu par la spec. **Non fait** : CI Ubuntu/Windows ; les quatre questions ouvertes du design.
