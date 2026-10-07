<!-- Annexe B de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants B-NN y valent MINOS-AUD-BNN dans docs/audit/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit B — plan de contrôle tenant hébergé, stockage privé et secrets (+ sécurité PostgreSQL)

Dépôt : `N:\workspace-dev\minos-code-intelligence`, HEAD `bc1d3421` (branche `develop`), 6 octobre 2026.
Mode : lecture seule du dépôt. Aucun Maven lancé. Les reproductions ci-dessous ont été exécutées **hors dépôt**, dans le scratchpad (`scratchpad/repro/`), avec `javac`/`java` contre `minos-*/target/classes` (lus, jamais écrits) et le jar pgjdbc 42.7.13 de `~/.m2`. Les sorties citées sont celles observées.

## 0. Constats connus revérifiés au HEAD (pas de régression)

| Constat | Verdict au HEAD | Preuve |
|---|---|---|
| S1 escalade ADMIN→OWNER | Clos, tient | `HostedRole.canGovern` (`HostedRole.java:41`), usages `HostedMembershipService.java:50-53,85`, `HostedTokenService.java:48`; `HostedRoleGovernanceTest` |
| S2 / S12 / S13 audit saturé par les refus | Clos, tient | `HostedRetentionPolicy.admitsChainedDenial` (`:55`), `HostedAuthorizationService.recordDenial` (`:94-123`), `HostedAuditChain.unchainedRefusal` (`:67-84`) |
| S4 mot de passe PG dans `toString()` | Clos, tient | `StorageBackendConfiguration.toString` (`:119-130`) masque `***` |
| S6 verrous sans délai (magasin hébergé) | Clos, tient | `FileHostedControlPlaneStore.lock` (`:375-380`) passe par `BoundedFileLease`, 10 s |
| S10, 2e puce (séparateur `\0` sans préfixe de longueur) | **Non exploitable** au HEAD | Tous les champs hachés interdisent `\0` (`HostedPrincipal.text`, `safeId`, `HostedPrincipal.java:18-35`). Réserve : voir B-01, qui montre que ce n'est vrai qu'après normalisation |
| S10, 1re puce (AAD sans version) | **Toujours ouvert**, et le correctif suggéré par l'audit est inefficace | voir B-05 |

Les constats ci-dessous sont tous nouveaux au sens de `AUDIT-2026-09.md`, sauf B-05 qui précise S10.

---

### B-01 — Un refus RBAC dont l'identifiant de ressource n'est pas canonique corrompt définitivement la chaîne d'audit du tenant

- **Qualification** : DÉFAUT CONFIRMÉ (reproduit).
- **Priorité proposée** : **P1**. Un seul appel d'un membre au rôle le plus bas (VIEWER, AUDITOR, CONTRIBUTOR) ou d'un membre révoqué dont le jeton court encore rend le tenant inutilisable pour tous (lecture comprise), sans récupération sans outil manuel. Surface : API Java et CLI `minos team` (le MCP est en lecture seule et n'est pas concerné) ; exposée dès qu'un intégrateur place un transport devant `HostedControlPlaneService`, ce que l'ADR-0035 prévoit explicitement.
- **Preuves** :
  - `HostedMembershipService.java:42-44` (`grant`), `:77-79` (`revoke`) et `HostedTokenService.java:40-42` (`issue`) passent l'argument **brut** `principalId` / `targetPrincipalId` comme `resourceId` à `authorizeMutation`. La normalisation `HostedPrincipal.safeId(...)` n'intervient qu'ensuite (`HostedMembershipService.java:45,80` ; `HostedTokenService.java:43`).
  - `HostedAuthorizationService.authorizeMutation` (`:47-66`) transmet ce `resourceId` brut à `recordDenial` (`:62`), qui le transmet à `HostedAuditChain.append` (`:111-120`).
  - `HostedAuditChain.append` calcule le HMAC sur la valeur **brute** (`HostedAuditChain.java:51-52`, canonique construit en `:154-159`), puis construit `new HostedAuditEvent(...)` (`:53-54`). Le constructeur compact de `HostedAuditEvent` **normalise** (`HostedAuditEvent.java:58` : `resourceId = HostedPrincipal.text(resourceId, ..., 4096)`, qui fait `trim()`, `HostedPrincipal.java:26-35`). L'événement persisté porte donc `"ghost"` alors que son HMAC couvre `" ghost"`.
  - `HostedAuditChain.verify` (`:94-119`) recalcule le HMAC sur la valeur normalisée (`authenticate`, `:125-133`) : `SecurityException("hosted audit event authentication failed")`. `loadVerified` (`HostedAuthorizationService.java:142-147`) appelle `verify` à chaque requête authentifiée, lectures comprises.
  - Même racine pour l'événement non chaîné (`unchainedRefusal`, `:67-84`) : le HMAC exporté ne se vérifie pas par `verifyUnchained`.
- **Comportement actuel** (reproduction `scratchpad/repro/src/Repro.java`, tenant en mémoire, harnais équivalent à `HostedControlPlaneTestSupport`) :
  ```
  viewer call -> SecurityException: hosted permission denied: MEMBER_WRITE
  events=4
  owner read -> SecurityException: hosted audit event authentication failed
  ```
  Le VIEWER a appelé `grantMember(viewer, "r1", " ghost", "G", VIEWER)` ; ensuite **le propriétaire** ne peut plus rien lire ni muter.
- **Comportement attendu + source** : un refus RBAC est audité sans altérer la chaîne ; `docs/developer/team-hosted-mode.md` (« RBAC denials after successful token authentication are appended as `DENIED` ») et ADR-0035 (« audit events form an HMAC-SHA-256 chain, including allowed and RBAC-denied authenticated mutations ») ; `HostedAuditChain` Javadoc de classe (« anti-tampering »). Le fail-closed ne doit pas être déclenchable par une entrée d'un appelant faiblement privilégié.
- **Cause** : le HMAC est calculé avant la canonicalisation faite par le constructeur d'événement ; deux sources de vérité pour la valeur d'un champ (arguments bruts vs champs de l'enregistrement). Tout caractère de contrôle ou espace en tête/fin d'un identifiant tombe dans le piège (`trim()` retire tout caractère `<= U+0020`).
- **Impact** : déni de service permanent du tenant (fail-closed) par un acteur de rang inférieur ; la piste d'audit contient un événement non vérifiable, ce qui interdit aussi la rétention (`retention-apply` passe par `loadVerified`). Reprise : suppression/édition manuelle du fichier `.mht`, avec les clés.
- **Correction minimale proposée** : dans `HostedAuditChain.append` et `unchainedRefusal`, canonicaliser **avant** de hacher (`principalId = safeId(...)`, `resourceId = text(...)`, etc.) ou, plus robuste, construire l'événement d'abord et hacher les champs lus **depuis l'enregistrement** (`event.principalId()`, ...). En complément (voir B-03), normaliser/valider `resourceId` dès `authorizeMutation`. Test de non-régression de la famille « tout champ de l'événement est un point fixe de la canonicalisation ».
- **Validation (test)** : `minos-engine/src/test/java/com/minos/hosted/HostedAuditResourceIdCanonicalFormTest.java` (package `com.minos.hosted`, utilise `HostedControlPlaneTestSupport.harness()`) :
  ```java
  @Test
  void aRefusalWithANonCanonicalResourceIdKeepsTheChainVerifiable() throws Exception {
      var harness = HostedControlPlaneTestSupport.harness();
      String owner = harness.bootstrapOwner();
      String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);

      assertThrows(SecurityException.class,
              () -> harness.service().grantMember(viewer, "r1", " ghost", "G", HostedRole.VIEWER));

      // aujourd'hui : SecurityException("hosted audit event authentication failed")
      assertDoesNotThrow(() -> harness.service().tenant(owner));
      new HostedAuditChain(harness.keys(), java.time.Clock.systemUTC()).verify(harness.state());
  }
  ```
  Variantes à paramétrer : `"\tghost"`, `"ghost "`, `revokeMember` et `issueToken` (mêmes chemins).
- **Dépendances** : à traiter avec B-02 et B-03 (même point d'entrée `authorizeMutation`). Ni gate `scripts/remediation/check-*.py` ni nom de méthode à renommer ; vérifier par `grep` que `HostedAuditChain.append`/`unchainedRefusal` ne sont pas assertés littéralement avant de changer leur signature.

---

### B-02 — Les refus chaînés ne sont pas bornés en octets : un VIEWER peut saturer la limite de 32 MiB et bloquer les écritures autorisées

- **Qualification** : DÉFAUT CONFIRMÉ (arithmétique mesurée ; chemin de code sans ambiguïté).
- **Priorité proposée** : **P1** (même famille de DoS qu'avait S2, laissé ouvert par sa correction qui ne borne qu'en nombre d'événements).
- **Preuves** :
  - Le `resourceId` d'un refus est l'argument brut de l'appelant, accepté jusqu'à 4096 caractères (`HostedAuditEvent.java:58`), sans lien avec `safeId` (128). Cf. B-01 pour le chemin.
  - Capacité en nombre uniquement : `HostedRetentionPolicy.deniedAuditCapacity` (`:37-39`, 9 000 refus avec la politique par défaut `defaults()`, `:26-28`) et `HostedAuditChain.append` (`:45`, 100 000 événements).
  - Limite en octets : `FileHostedControlPlaneStore.DEFAULT_MAX_TENANT_BYTES = 32 MiB` (`:46`), appliquée seulement à l'écriture (`writeAtomically`, `:213`, `IOException("encoded hosted tenant exceeds byte limit")`).
  - Aucun lien entre les deux : ni `append`, ni `recordDenial`, ni `HostedTenantState` ne consultent la taille.
- **Comportement actuel** (mesure `scratchpad/repro/src/Size.java`, vrai `FileHostedControlPlaneStore`) : un refus à `resourceId` de 4 000 caractères coûte **4 244 octets** ; 32 MiB sont atteints après **≈ 7 906 refus**, soit moins que la réserve de 9 000. Un événement nominal pèse 282 octets (`Size2.java`) ; avec `requestId` et `principalId` de 128 caractères, 100 000 événements dépassent aussi 32 MiB : la capacité nominale annoncée (100 000) n'est pas atteignable.
  Cadence : le budget par principal est de 10 refus/min (`HostedDenialThrottle.DEFAULT_MAX_PER_WINDOW`, `:21`), donc ≈ 13 h pour un seul principal, plus vite avec plusieurs ; une durée inférieure à la validité maximale d'un jeton (24 h, `HmacHostedIdentityProvider.MAX_TOKEN_LIFETIME`, `:21`). Chaque refus réécrit l'état complet (jusqu'à 32 MiB, avec fsync).
  Une fois la limite franchie, toute sauvegarde échoue, **y compris les mutations autorisées et `RETENTION_SET`/`RETENTION_APPLY`** (qui sont des sauvegardes). La reprise par l'OWNER n'est possible que dans la marge restante, en course contre l'attaquant.
- **Comportement attendu + source** : « Refusals are bounded so that they can never starve authorized mutations » (`docs/developer/team-hosted-mode.md`, section « Concurrency, audit and retention »). Une borne en nombre sans borne en octets ne tient pas cette promesse.
- **Cause** : valeur de champ non bornée côté refus ; capacités exprimées en événements alors que la contrainte dure est en octets.
- **Impact** : DoS en écriture du tenant par un principal sans droit d'écriture ; amplification d'E/S (réécriture de ≤ 32 MiB par refus).
- **Correction minimale proposée** : (1) valider `resourceId` avec `safeId` (ou tronquer à 128 + condensat) avant tout audit, dans `authorizeMutation` ; (2) garantir l'invariant `MAX_AUDIT_EVENTS × taille_maximale_d'un_événement < maxTenantBytes` (taille maximale désormais bornée par construction) **ou** ajouter à `admitsChainedDenial` un test sur la taille encodée courante ; (3) aligner `MAX_AUDIT_EVENTS` sur le plafond d'octets.
- **Validation (test)** : `minos-storage-local/src/test/java/com/minos/storage/local/store/HostedDenialByteBudgetTest.java` (package `com.minos.storage.local.store` pour accéder au constructeur de paquet `FileHostedControlPlaneStore(root, keys, maxTenantBytes, SecureRandom)`, minimum 1 024 octets) : limite de 64 KiB, `DerivedTenantKeys.provider()` (module engine test-jar, déjà utilisé par `FileHostedControlPlaneStoreTest`), horloge mutable avancée de 7 s par appel, 200 appels `revokeMember(viewer, id, "a".repeat(4000))`, puis `assertDoesNotThrow(() -> service.grantMember(owner, "ok", "x", "X", VIEWER))`. Rouge aujourd'hui (la dernière écriture lève `exceeds byte limit`).
- **Dépendances** : B-01, B-03 (corrections au même endroit). Aucun script `check-*.py` concerné ; `check-hosted-control-plane-consistency.py` (cité par `team-hosted-mode.md`) est à rejouer car il affirme des contrats de ce package.

---

### B-03 — Un refus peut disparaître sans trace ou changer de nature d'erreur (identifiant invalide ; conflit de version)

- **Qualification** : DÉFAUT CONFIRMÉ pour l'identifiant invalide (reproduit) ; RISQUE pour le conflit de version (chemin de code sans ambiguïté, déclenchement dépendant de la concurrence).
- **Priorité proposée** : **P2**.
- **Preuves** :
  - Identifiant invalide : `authorizeMutation` ne valide pas `resourceId` ; `recordDenial` lève `IllegalArgumentException` dans le constructeur de `HostedAuditEvent` (`:58`, plus de 4 096 caractères, ou valeur blanche). Reproduit : `viewer 5000-char id -> IllegalArgumentException: resourceId is invalid or exceeds its limit` ; `events2=3` (aucun événement ajouté). Le refus n'est ni chaîné, ni journalisé (`HostedAuditDelivery.publishUnchained`, `:41-60`, jamais atteint), et `LocalMinosTeamApi` le publie comme `INVALID_REQUEST` (`MinosApiSupport.execute`, branche `IllegalArgumentException`) au lieu de `ACCESS_DENIED`.
  - Conflit de version : `recordDenial` appelle `HostedCommitRecovery.save(store, denied, state.version())` (`HostedAuthorizationService.java:121`) sans repli. Si un écrivain légitime a avancé la version entre le chargement et la sauvegarde, `FileHostedControlPlaneStore.save` lève `IOException("hosted tenant concurrent modification…")` (`:134-137`), propagée telle quelle : refus non audité, non journalisé, erreur `IO_FAILURE`. Idem pour toute panne d'E/S à l'écriture du refus.
  - Aucun test : `HostedDenialSaturationTest`, `HostedUnchainedRefusalTraceTest`, `HostedCommitRecoveryTest` ne couvrent ni le refus avec identifiant invalide, ni l'échec de `store.save` pendant un refus.
- **Comportement attendu + source** : `HostedAuthorizationService` Javadoc de classe (« central fail-closed ») et de `recordDenial` (« A refusal that is not chained is still enforced, journaled as a WARNING and delivered to `HostedAuditSink.publishUnchained` », `team-hosted-mode.md`). Un refus doit toujours aboutir à un `SecurityException` et à une trace (chaînée ou, à défaut, journalisée), quoi qu'il arrive à l'écriture.
- **Cause** : le chemin de refus suppose que la persistance réussit et que l'entrée est valide ; il n'a pas de repli vers le chemin « non chaîné » qui existe déjà.
- **Impact** : un attaquant (ou le hasard) échappe à la piste d'audit en envoyant un identifiant invalide ou en jouant sur la concurrence ; codes d'erreur incohérents côté API (`INVALID_REQUEST`/`IO_FAILURE` au lieu de `ACCESS_DENIED`).
- **Correction minimale proposée** : dans `recordDenial`, encadrer `append` + `HostedCommitRecovery.save` par `try { … } catch (IOException | RuntimeException failure)` qui bascule sur `unchainedRefusal` + `publishUnchained`, et lever toujours le `SecurityException`. Valider/normaliser `resourceId` en amont (B-01).
- **Validation (test)** : `minos-engine/src/test/java/com/minos/hosted/HostedRefusalTraceTest.java` : (a) refus avec `revokeMember(viewer, "r", "a".repeat(5000))` doit lever `SecurityException` et laisser une trace (`harness.sink().unchained` non vide ou événement chaîné) ; (b) magasin enveloppant `InMemoryStore` dont `save` lève `IOException("concurrent modification")` pour l'écriture du refus : `SecurityException` attendu et `sink.unchained` de taille 1.
- **Dépendances** : B-01, B-02.

---

### B-04 — Après une rotation, la clé retirée garde un pouvoir de pollution de l'audit sans limite effective, et un jeton périmé est traité comme un refus RBAC

- **Qualification** : DÉFAUT CONFIRMÉ pour le mauvais classement d'un jeton à clé inactive ; RISQUE pour l'abus par une clé retirée compromise (reproduit avec une clé retirée, mais le scénario suppose la fuite de l'ancienne clé maîtresse).
- **Priorité proposée** : **P2**.
- **Preuves** :
  - `authorizeRead` rejette un jeton dont `keyId` n'est pas la clé active par une `SecurityException("… inactive key")` (`HostedAuthorizationService.java:36-38`). `authorizeMutation` fait autre chose : `allowed = claims.keyId().equals(state.keyId()) && …` (`:59-60`), donc le jeton à clé périmée est un **refus RBAC audité** (`recordDenial`, `:62`) avec le message `hosted permission denied: <permission>`.
  - `HmacHostedIdentityProvider.authenticate` (`:54-82`) accepte toute signature valide sous la clé nommée **dans la charge utile non authentifiée**, y compris une clé retirée, que l'ADR-0035 oblige à garder disponible (« old key material must remain available while retained historical audit events reference it »).
  - Le principal de l'événement vient des revendications du jeton (`claims.principalId()`), donc d'un jeton forgé avec l'ancienne clé : nom arbitraire (`safeId`). Le budget de refus est indexé par `(tenant, principal)` (`HostedDenialThrottle.tryAcquire`, `:45-67`), borné à 1 024 principaux par éviction LRU (`:23`, `:52-56`) : changer de nom de principal à chaque requête contourne le budget.
- **Comportement actuel** (reproduction `scratchpad/repro/src/Rot.java`) : après `rotateKey` vers `key-b`, 40 jetons forgés avec `key-a` pour 40 noms distincts :
  ```
  error shown: hosted permission denied: WORKSPACE_WRITE
  forged-principal refusals=40 chained DENIED events=40
  distinct principals in chain: 40
  read with stale key: hosted bearer token uses an inactive key
  ```
  La même clé retirée est refusée en lecture avec un message distinct, mais en écriture elle fabrique des événements `DENIED` chaînés attribués à n'importe quel principal (y compris `owner`) et consomme la réserve de 9 000 refus, puis la limite d'octets (B-02). Côté légitime : tout détenteur d'un jeton antérieur à la rotation reçoit « permission denied » (trompeur) et écrit un faux `DENIED` à chaque essai.
- **Comportement attendu + source** : la rotation est présentée comme le moyen d'invalider un jeton ou une clé (`team-hosted-mode.md` : « Key rotation is explicit »), et `authorizeRead` donne déjà la bonne sémantique (échec d'authentification).
- **Cause** : le contrôle de la clé active est mêlé à l'autorisation RBAC dans `authorizeMutation`, donc traité comme un refus auditable ; l'authentification accepte les clés retirées.
- **Impact** : une clé retirée compromise ne donne pas d'accès, mais permet de polluer/saturer l'audit en usurpant l'attribution (réputation d'un principal), de contourner le budget par principal, et d'amplifier B-02. Pas d'élévation de privilège.
- **Correction minimale proposée** : en mutation, traiter `claims.keyId() != state.keyId()` comme `authorizeRead` (exception d'authentification, **sans** événement chaîné ; journal WARNING borné) ; ajouter un budget global par tenant (pas seulement par principal) pour les refus chaînés.
- **Validation (test)** : `HostedKeyRotationRefusalTest` : après `rotateKey`, un jeton ancien pour une mutation doit lever `SecurityException` et ne **pas** ajouter d'événement chaîné (aujourd'hui : +1 `DENIED`).
- **Dépendances** : B-02 (même plafond d'événements) ; ADR-0035 à amender (« une clé retirée n'authentifie plus rien »).

---

### B-05 — Rejeu de l'état chiffré (rollback) indétectable ; l'AAD avec version proposée par S10 ne le corrigerait pas

- **Qualification** : DÉCISION À CLARIFIER (le défaut technique est confirmé par reproduction ; le périmètre du modèle de menace est une décision).
- **Priorité proposée** : **P2**.
- **Preuves** : `FileHostedControlPlaneStore.aad` (`:256-258`) = `"MHT1\0" + VERSION + "\0" + tenantId + "\0" + keyId`, où `VERSION` est la version de **format** (1) et non la version d'état. `read` (`:147-186`) accepte tout chiffré valide du même `(tenant, keyId)`. L'état contient sa propre `version` et sa chaîne d'audit, mais rien d'externe ne les ancre. Reproduction `scratchpad/repro/src/Roll.java` (vrai magasin) : après `revokeMember(owner, …, "mallory")`, `mallory` est refusé ; puis on recopie l'ancien fichier `.mht` (un attaquant avec accès en écriture au fichier mais sans clé) :
  ```
  after revoke: authenticated principal is not a tenant member
  after restoring old file: mallory reads tenant version=2 members=2
  ```
  La révocation est annulée, `HostedAuditChain.verify` passe (la chaîne tronquée est cohérente : `auditSequence` est dans le même fichier).
- **Comportement attendu + source** : `team-hosted-mode.md` annonce « encrypted-at-rest », « AES-256-GCM authenticated encryption », rejet de la falsification (« encryption tamper rejection » dans `run-hosted-e2e.py`) ; l'ADR-0035 ne revendique pas la résistance au rollback, et `HostedAuditSink` externe est le seul témoin (inactif dans l'embarqué, `embeddedNoop`). L'audit S10 écrit « aucune protection contre le rollback » et propose « une version dans l'AAD » : insuffisant, car un ancien fichier complet porte sa propre version dans son AAD valide. Un anti-rollback exige un compteur monotone **hors du fichier** (ancre du dernier `version` + hash de tête chez l'opérateur, dans le sink, ou dans un fichier à part protégé par la clé d'audit mais comparé au moment du chargement).
- **Cause** : l'état est autoportant ; aucun témoin externe de la version maximale vue.
- **Impact** : un acteur avec accès en écriture au répertoire de contrôle (sauvegarde restaurée, volume partagé) peut annuler une révocation ou une rétention et effacer des événements d'audit récents sans clé. Hors périmètre si le modèle de menace suppose le système de fichiers de confiance (alors la chiffrement n'a d'intérêt que contre la lecture).
- **Correction minimale proposée** : décider du modèle de menace ; si le rollback est dans le périmètre, ancrer `(version, hash de tête)` dans un témoin séparé (fichier `<tenant>.anchor` MAC-é avec `AUDIT_CHAIN`, écrit avant le renommage, vérifié au `find`), et documenter la limite sinon. Corriger la formulation de S10.
- **Validation (test)** : `FileHostedControlPlaneStoreTest` : après `save(v1)` puis `save(v2)`, restaurer les octets de v1 doit lever `IOException` (rouge aujourd'hui).
- **Dépendances** : amende ADR-0035 ; STATUS.md (limites du mode Team).

---

### B-06 — Le rôle ADMIN peut effacer la piste d'audit (rétention) y compris ses propres actions

- **Qualification** : DÉCISION À CLARIFIER.
- **Priorité proposée** : **P2**.
- **Preuves** : `HostedRole.ADMIN = EnumSet.complementOf(of(KEY_ROTATE))` (`HostedRole.java:9`) donc détient `RETENTION_MANAGE`. `HostedRetentionService.set` (`:29-49`) autorise `HostedRetentionPolicy` minimale (100 événements, 1 jour : `HostedRetentionPolicy.java:9-12`), puis `apply` (`:57-83`) retire tout événement plus ancien que 1 jour ou au-delà de 100 et déplace `auditAnchorHash`. Aucune règle ne réserve la rétention des événements aux OWNER, ni ne protège les événements `MEMBER_*`, `TOKEN_ISSUE`, `KEY_ROTATE`. Seuls `RETENTION_SET` et `RETENTION_APPLY` restent comme traces. Aucun test (recherche dans `minos-engine/src/test/java/com/minos/hosted/*` : seuls l'OWNER règle la rétention).
- **Comportement attendu + source** : ADR-0035 « retention is plan/apply: no implicit deletion » ; `S1` a établi que l'ADMIN ne doit pas pouvoir agir sur ce que seul un OWNER gouverne. La clé (`KEY_ROTATE`) est réservée à l'OWNER, mais la destruction de preuve ne l'est pas.
- **Cause** : la permission `RETENTION_MANAGE` regroupe réglage (non destructif) et application (destructive).
- **Impact** : un ADMIN malveillant/compromis efface les preuves des actions plus anciennes de 24 h (ou au-delà des 100 derniers événements) ; hors sink externe, c'est l'unique exemplaire.
- **Correction minimale proposée** : réserver `retention-apply` et toute politique plus courte que l'actuelle aux OWNER (nouvelle permission `RETENTION_PURGE`) ou exiger des planchers (par exemple 30 jours/1 000 événements) pour ADMIN ; sinon documenter explicitement dans l'ADR que l'audit n'est pas inviolable face à un ADMIN.
- **Validation (test)** : `HostedRoleGovernanceTest` : un ADMIN fait `setRetention(100, 1, 1)`, avance l'horloge de 2 jours, `applyRetention` : attendre `SecurityException` (rouge aujourd'hui) et les événements antérieurs conservés.
- **Dépendances** : ADR-0035 ; `HostedRole`/`HostedPermission` sont affirmés par `check-hosted-control-plane-consistency.py` (à rejouer).

---

### B-07 — La politique d'URL PostgreSQL accepte `SSLMODE=verify-full` que le pilote ignore : TLS sans vérification

- **Qualification** : DÉFAUT CONFIRMÉ (reproduit contre pgjdbc 42.7.13, source lue).
- **Priorité proposée** : **P2** (fausse assurance de sécurité ; il faut une URL mal écrite par l'opérateur, mais c'est exactement le cas que la politique prétend empêcher).
- **Preuves** : `PostgresJdbcUrlPolicy.queryParameters` décode **et met en minuscules la clé** (`:95` : `URLDecoder.decode(rawKey, UTF_8).toLowerCase(Locale.ROOT)`), puis `validateJdbcHostPolicy` exige `verify-full` pour un hôte externe (`:78-81`). Le pilote, lui, n'applique ni décodage ni minuscule au nom de propriété : `org.postgresql.Driver.parseURL` (source 42.7.13, boucle « parse the args part of the url ») fait `PGPropertyUtil.translatePGServiceToPGProperty(token.substring(0, pos))` puis `setProperty(pName, …)`, et `SslMode.of` lit la clé exacte `sslmode`.
- **Comportement actuel** (`scratchpad/repro/src2/Pg.java`, politique réelle + `Driver.parseURL`) :
  ```
  ...?sslmode=verify-full      policy=ACCEPTED  driver sslmode=verify-full
  ...?SSLMODE=verify-full      policy=ACCEPTED  driver sslmode=null
  ...?ssl%6Dode=verify-full    policy=ACCEPTED  driver sslmode=null
  ```
  Avec `sslmode` absent, le pilote retombe sur `prefer` : TLS opportuniste sans vérification de certificat ni de nom, sous attaque de l'homme du milieu ou en clair.
- **Comportement attendu + source** : message de la politique (`PostgresJdbcUrlPolicy.java:80`, « external PostgreSQL requires sslmode=verify-full ») et `docs/` (frontière TLS). Le contrat est que la connexion externe est vérifiée.
- **Cause** : le contrôle analyse l'URL autrement que le consommateur (différentiel d'analyse).
- **Impact** : une connexion externe peut s'établir sans vérification d'hôte alors que MINOS affirme l'avoir imposée (fausse conformité). Aucun secret n'est exposé par la politique elle-même, mais le mot de passe PostgreSQL traverse alors un canal non authentifié.
- **Correction minimale proposée** : comparer la **clé brute** à `"sslmode"` exacte (sans décodage ni minuscule) et refuser toute autre ; ou, mieux, ne pas laisser l'URL porter `sslmode` mais le passer en `Properties` (`openRawConnection`, `PostgresConnectionFactory.java:462-472`), ce qui supprime le différentiel. Optionnel : passer aussi `sslmode` effectif en `Properties` quand l'hôte est externe.
- **Validation (test)** : `PostgresConnectionFactoryTest` (ou `PostgresJdbcUrlPolicyTest` à créer, même package) : `?SSLMODE=verify-full` et `?ssl%6Dode=verify-full` pour `db.example.com` doivent lever `IOException` ; aujourd'hui acceptés.
- **Dépendances** : aucune gate `check-*.py` connue sur ce fichier ; chercher par `grep` « external PostgreSQL requires » dans `scripts/` avant de reformuler le message.

---

### B-08 — Un BOM UTF-8 supprime silencieusement la première propriété et s'ajoute au mot de passe lu depuis un fichier

- **Qualification** : DÉFAUT CONFIRMÉ (reproduit).
- **Priorité proposée** : **P2** (propre à Windows en pratique : Windows PowerShell 5.1 `Out-File -Encoding utf8` et d'anciens Bloc-notes écrivent un BOM ; exigence « Windows + Linux » de `openspec/config.yaml`).
- **Preuves** : `BoundedProperties.strictUtf8Reader` (`:79-83`) décode en UTF-8 strict **sans retirer le BOM** (U+FEFF est un caractère valide). `Properties.load(Reader)` (`:73`) prend donc `\uFEFFminos.storage.backend` comme clé. `MinosRuntimeSettings.load` (`:60-77`) charge ainsi la configuration. `MinosRuntimeSettings.secret` (`:114`) applique `String.trim()`, qui ne retire pas U+FEFF. Aucun traitement du BOM dans `minos-engine` (seul `RuntimeObservationEnvelopeCodec.java:60-61` le refuse ailleurs).
- **Comportement actuel** (`scratchpad/repro/src5/Bom.java`) :
  ```
  backend=null (expected postgresql)       <- première ligne du fichier perdue, aucune erreur
  secret length=7 startsWithBOM=true       <- mot de passe « \uFEFFs3cret » au lieu de « s3cret »
  ```
  Conséquences : `StorageBackendConfiguration.resolve` (`:72-73`) retombe sur `local` sans avertissement si `minos.storage.backend=postgresql` est la première ligne ; un mot de passe de fichier avec BOM est refusé par PostgreSQL avec un message d'authentification opaque (et le BOM est invisible dans les diagnostics).
- **Comportement attendu + source** : `MinosRuntimeSettings` Javadoc (précédence JVM > env > fichier ; « durable MINOS runtime configuration ») : une propriété écrite n'est jamais ignorée en silence ; principe fail-closed du dépôt.
- **Cause** : le lecteur « strict » est strict sur les octets invalides, pas sur les marques d'ordre d'octets.
- **Impact** : configuration silencieusement ignorée (bascule du backend), secret altéré ; échecs difficiles à diagnostiquer.
- **Correction minimale proposée** : dans `BoundedProperties`, retirer **un** BOM initial (ou le refuser avec un message explicite, comme le fait `RuntimeObservationEnvelopeCodec`) pour `load`, `loadUtf8` et `readUtf8`. Retirer en plus le BOM avant `trim()` pour le secret.
- **Validation (test)** : `BoundedPropertiesTest` : fichier `"\uFEFFa=b"` → `getProperty("a")` vaut `"b"` ; `MinosRuntimeSettingsTest` : secret `"\uFEFFs3cret\r\n"` → `"s3cret"`.
- **Dépendances** : aucune gate ; les tests de `BoundedProperties` existants n'affirment pas le BOM.

---

### B-09 — `DurableAtomicFile.publish` remplace un fichier existant, contrairement à son contrat

- **Qualification** : DÉFAUT CONFIRMÉ (reproduit sous Windows ; sous Linux `Files.move(..., ATOMIC_MOVE)` s'appuie sur `rename(2)` qui remplace aussi).
- **Priorité proposée** : **P3** (latent : les deux appelants pré-vérifient sous verrou).
- **Preuves** : `DurableAtomicFile.java:66-69` (Javadoc « an existing target is never replaced ») appelle `move(..., replaceExisting=false, ...)`, et `atomicMove` (`:125-139`) utilise `StandardCopyOption.ATOMIC_MOVE` seul. Reproduction `scratchpad/repro/src3/Pub.java` :
  `publish returned normally; target now = NEW`. Appelants : `FileHostedControlPlaneStore.create` (`:103-106,220`, garde `Files.exists` sous verrou tenant) et `FileProjectFingerprintSnapshotStore.publishLocked` (`:121-160`, garde `filesForIdHash`). Aucun test de `DurableAtomicFileTest` ne publie sur une cible existante.
- **Comportement attendu + source** : Javadoc `DurableAtomicFile.publish` ; le nom « immutable » est celui du contrat du dépôt (ADR-0033 « immutable remote revisions »).
- **Cause** : sémantique de `ATOMIC_MOVE` du JDK (remplacement non spécifié) non neutralisée.
- **Impact** : toute future utilisation sans garde externe écraserait une publication « immuable ». Aujourd'hui, pas d'exploitation.
- **Correction minimale proposée** : publier par `Files.createLink(target, source)` puis supprimer la source (échoue si la cible existe sur les deux plateformes), avec repli documenté si les liens physiques sont indisponibles ; ou test préalable `Files.exists` avec le verrou documenté comme précondition.
- **Validation (test)** : `DurableAtomicFileTest` : cible existante → `FileAlreadyExistsException`, contenu inchangé. Rouge sous Windows et Linux.
- **Dépendances** : appelants à rejouer (`FileHostedControlPlaneStoreTest`, tests des fingerprints).

---

### B-10 — Hygiène de rotation et de jetons : alias d'identifiants de clé, réutilisation d'une ancienne clé, aucune révocation unitaire

- **Qualification** : RISQUE / AMÉLIORATION (chacun des trois points est confirmé par lecture du code, aucun n'est une exploitation à lui seul).
- **Priorité proposée** : **P3**.
- **Preuves** :
  1. **Alias** : `EnvironmentHostedTenantKeyProvider.resolve` (`:36-37`) transforme `keyId` en variable par `toUpperCase().replaceAll("[^A-Z0-9]", "_")`. `primary-1`, `primary.1`, `Primary_1`, `primary:1` désignent la **même variable** `MINOS_TEAM_KEY_PRIMARY_1`, donc le même secret maître (les clés dérivées diffèrent car `keyId` entre dans la dérivation, `:67`, mais pas le matériau). `HostedTokenService.rotate` (`:81-83`) n'interdit que l'égalité de chaîne : `rotate primary-1 → primary.1` « réussit » sans changer de secret maître. `requireKeys` (`:109-116`) ne détecte rien.
  2. **Retour à une ancienne clé** : rien n'interdit la rotation `A → B → A` ; les jetons signés sous `A` avant la rotation (≤ 24 h) redeviennent valides.
  3. **Pas de révocation unitaire** : `HostedAccessClaims.tokenId` (`:14,23`) est généré (`HostedTokenService.java:56`, `UUID.randomUUID()`) mais jamais consulté. Un jeton volé ne se révoque qu'en révoquant le membre ou en tournant la clé de tout le tenant ; et un principal révoqué puis ré-accordé sous le même identifiant (`grant` crée une nouvelle entrée, `HostedMembershipService.java:54-61`) **ressuscite** ses anciens jetons encore dans leur fenêtre de 24 h (le contrôle est sur l'appartenance courante, `HostedAuthorizationService.java:39-40`).
- **Comportement attendu + source** : ADR-0035 « key rotation is explicit » ; « short-lived bearer tokens » (`HostedTokenService` Javadoc de classe).
- **Impact** : une rotation peut être sans effet cryptographique réel (1) ; fenêtre de jetons ressuscités (2, 3).
- **Correction minimale proposée** : refuser une rotation vers un `keyId` dont la variable normalisée est déjà celle d'une clé citée dans la chaîne ou l'état ; refuser une clé déjà apparue dans les événements retenus ; ou, plus simple, injecter un `membership epoch` (instant d'ajout) dans la charge utile et refuser les jetons émis avant `HostedPrincipal.createdAt()`. Documenter l'absence de révocation unitaire.
- **Validation (test)** : `HostedTokenServiceRotationTest` (engine) : (1) `rotateKey(.., "primary.1")` quand l'actuelle est `primary-1` doit lever `IllegalArgumentException` ; (3) jeton de `bob` émis, `revoke bob`, `grant bob`, ancien jeton → `SecurityException`.
- **Dépendances** : ADR-0035 ; format de jeton `mht1` versionné (ne pas rompre : champ additif seulement).

---

### B-11 — Contrat « idempotence » du `--request-id` annoncé mais non tenu

- **Qualification** : DÉCISION À CLARIFIER.
- **Priorité proposée** : **P3**.
- **Preuves** : `TeamCommand.java:269-272` commente « the optional idempotency key that every mutation accepts ». `HostedPrincipal.safeId(requestId, "requestId")` (`HostedAuthorizationService.java:55`) est la seule exploitation : l'identifiant est écrit dans l'audit, jamais comparé aux événements existants. `docs/developer/team-hosted-mode.md` : « M27 does not silently retry non-idempotent mutations ». `token-issue` rejoué avec le même `--request-id` émet un second jeton valide et un second `ALLOWED` ; `member-grant` renouvelle `createdAt` du membre.
- **Comportement attendu + source** : le commentaire du CLI. Le comportement réel est « corrélation », pas « idempotence ».
- **Impact** : un client qui retente après un délai dépassé duplique les effets ; la chaîne peut contenir plusieurs événements au même `requestId`, ce qui trouble la corrélation.
- **Correction minimale proposée** : soit corriger le commentaire et l'aide (`USAGE`) en « identifiant de corrélation », soit refuser les `requestId` déjà présents dans la chaîne retenue (les événements portent `requestId`, pas de nouvel état).
- **Validation (test)** : choisir ; si refus : `grantMember` deux fois avec le même `requestId` → seconde tentative `IllegalArgumentException`.
- **Dépendances** : CLI/API/MCP versionnés additifs (`openspec/config.yaml`) : un refus de `requestId` dupliqué est un changement de comportement, à annoncer.

---

### B-12 — Documentation contredite par le code : l'échec d'export de l'audit n'est pas « remonté à l'appelant »

- **Qualification** : DÉFAUT CONFIRMÉ (documentaire).
- **Priorité proposée** : **P3** (même famille que G2).
- **Preuves** : `docs/developer/hosted-production-boundaries.md:43` : « Un échec d'export est remonté à l'appelant ». Le code avale l'exception après commit : `HostedAuditDelivery.publishAfterCommit` (`:22-34`, journal WARNING, jamais de relance) ; sa Javadoc (`:6-14`) explique que c'est voulu (un échec d'export ne doit pas faire croire à l'échec d'une mutation déjà persistée).
- **Comportement attendu** : celui du code (Javadoc), qui est aussi celui qui évite des retentatives non sûres.
- **Correction minimale proposée** : corriger la phrase (« est journalisé, l'événement reste rejouable depuis la chaîne »).
- **Validation (test)** : aucun (revue documentaire).

---

### B-13 — `PostgresJdbcUrlPolicy` ne reconnaît pas l'IPv6 de bouclage

- **Qualification** : DÉFAUT CONFIRMÉ (fonctionnel, fail-closed).
- **Priorité proposée** : **P3**.
- **Preuves** : `loopbackHost` compare à `"::1"` et `"0:0:0:0:0:0:0:1"` (`PostgresJdbcUrlPolicy.java:112-116`), mais `java.net.URI.getHost()` renvoie l'adresse IPv6 **entre crochets** (`[::1]`). Reproduit : `jdbc:postgresql://[::1]:5432/minos` → `REJECTED: external PostgreSQL requires sslmode=verify-full`. Les tests de `PostgresConnectionFactoryTest` n'ont aucun cas IPv6. En outre `StorageBackendConfiguration.safePostgresUrl` (`:141-158`) ajoute des crochets autour d'un hôte qui en porte déjà (`[[::1]]` dans les diagnostics).
- **Comportement attendu + source** : le code liste explicitement le bouclage IPv6 comme intention.
- **Correction minimale proposée** : retirer les crochets avant comparaison ; idem dans `safePostgresUrl`.
- **Validation (test)** : `validate("jdbc:postgresql://[::1]:5432/minos", false)` ne lève pas ; `safePostgresUrl` produit `jdbc:postgresql://[::1]:5432/minos`.

---

## Observations non retenues comme constats (pour mémoire, aucun préjudice démontré)

- **Oracle sur les identifiants de clé** : une signature de jeton sous un `keyId` sans variable maîtresse lève « hosted token signing operation failed » (`HmacHostedIdentityProvider.sign`, `:84-93`) au lieu de « invalid hosted bearer token », ce qui révèle à un appelant non authentifié si un `keyId` est configuré. Faible ; à lisser avec B-04.
- **Fichier de secret absolu / `minos.properties` sans contrôle de mode** (`MinosRuntimeSettings.readAbsoluteSecret`, `:130-146`) : un secret lisible par le groupe ou les autres est accepté sans avertissement. Les montages de secrets Kubernetes sont en 0644 par défaut : un refus casserait un usage légitime ; un avertissement dans `minos doctor` serait la bonne option. Rattaché à S5 (colonne « permissions du secret non vérifiées »), non traité par S5.
- **Budget de refus par processus** : la CLI est un processus par appel, donc `HostedDenialThrottle` n'y limite rien ; seul le plafond de 9 000 refus joue (documenté dans la Javadoc de `HostedDenialThrottle` et `team-hosted-mode.md`, accepté).
- **Journal de refus sans limite de débit** (`HostedAuditDelivery.publishUnchained`, `:41-60`) : un refus non chaîné écrit un WARNING à chaque requête. Risque de volumétrie des journaux.
- **`PrivateLocalStorage.writePrivateFile`** (`:195-219`) tronque et réécrit en place (non atomique, sans `fsync`) ; correct pour le magasin hébergé qui l'emploie sur un fichier temporaire, mais `ToolOriginLedger.java:36`, `ManagedPolyglotScipRuntimeManager.java:337-339` et d'autres l'emploient sur le fichier final : une coupure laisse un fichier tronqué. Hors périmètre de cette capacité ; à relayer à l'audit « fiabilité ».
- **`ConfinedFileOpener.openRegularFileNoFollow`** (`:124-141`) n'applique `NOFOLLOW_LINKS` qu'à la **feuille** ; les ancêtres sont suivis. C'est dit honnêtement par la Javadoc et par le choix explicite (`openConfinedRegularFile` pour le confinement complet). Les jonctions Windows en ancêtre de `MINOS_HOME/hosted` sont refusées en pratique par `PrivateLocalStorage.harden` (`isOther()`), testé par `PrivateLocalStorageWindowsJunctionTest`.
- **Injection SQL (PostgreSQL)** : aucune trouvée. Requêtes en `PreparedStatement` ; le schéma passe par `enquoteIdentifier` et `format('%I', …)` (`PostgresSchemaMigrator.java:23,44-52`) après validation `[A-Za-z_][A-Za-z0-9_]{0,62}` (`StorageBackendConfiguration.java:104-110`). `currentSchema = schema + ",public"` (`PostgresConnectionFactory.java:466`) garde `public` dans le `search_path`, ce qui expose à un objet homonyme dans `public` sur un PostgreSQL < 15 aux droits par défaut : théorique, non démontré.
- **Verrou consultatif PostgreSQL par projet** : clé 64 bits `MSB ^ LSB` (`PostgresProjectMutationLock.java:18-21`), globale à la base et non au schéma ; collision sans conséquence de sûreté (sérialisation superflue).

---

## 1. Carte réelle du périmètre

### 1.1 Plan de contrôle hébergé (`minos-engine/src/main/java/com/minos/hosted/`, 33 fichiers)

| Classe | Rôle | Dépend de (sortant) |
|---|---|---|
| `HostedControlPlaneService` | façade publique ; assemble les services ; seule entrée d'API/CLI/MCP | tous les services ci-dessous |
| `HostedAuthorizationService` | authentifie (jeton), charge+vérifie le tenant, RBAC, enregistre les refus (`recordDenial`) | `HostedControlPlaneStore`, `HostedIdentityProvider`, `HostedAuditChain`, `HostedAuditSink`, `HostedDenialThrottle`, `HostedCommitRecovery` |
| `HostedMembershipService` | grant/revoke, dernier OWNER, `canGovern` | `HostedAuthorizationService`, `HostedTenantMutationWriter` |
| `HostedTokenService` | émission de jeton, rotation de clé, `requireKeys` | idem + `HostedTenantKeyProvider`, `HostedIdentityProvider` |
| `HostedWorkspaceService` | workspaces, bindings exacts de snapshot | `HostedBindingVerifier` |
| `HostedRetentionService` / `HostedRetentionPolicy` / `HostedRetentionPlan` | plan/apply de rétention, capacités d'audit | idem |
| `HostedTenantService` | bootstrap, lecture, audit borné | `HostedControlPlaneStore`, `HostedAuditChain` |
| `HostedTenantMutationWriter` | mutation autorisée : append audit → sauvegarde → export | `HostedAuditChain`, store, sink |
| `HostedAuditChain` / `HostedAuditEvent` | HMAC chaîné (HMAC-SHA256, clé `AUDIT_CHAIN`), vérification, refus non chaînés (domaine HMAC séparé) | `HostedTenantKeyProvider` |
| `HostedDenialThrottle` | budget glissant 10/min par `(tenant, principal)`, 1 024 principaux, LRU | — |
| `HostedCommitRecovery` | ré-observation après `CommitUncertainException` | store |
| `HmacHostedIdentityProvider` | jetons `mht1` (HMAC-SHA256, 24 h max, comparaison `MessageDigest.isEqual`) | `HostedTenantKeyProvider` |
| `HostedRole`, `HostedPermission`, `HostedPrincipal`, `HostedTenantState`, `SharedWorkspace`, `HostedProjectBinding`, `HostedAccessClaims` | modèle et invariants | — |
| `HostedProductionBoundary`, `HostedAvailabilityPort`, `HostedTransportSecurityPort`, `HostedAuditDelivery`, `HostedAuditSink` | frontières déclarées « non qualifiées » ; export d'audit | — |

Adaptateurs (module `minos-storage-local`) : `FileHostedControlPlaneStore` (fichier `<tenant>.mht`, AES-256-GCM, AAD `tenant+keyId+format`, nonce 96 bits aléatoire, verrou `BoundedFileLease` 10 s, version optimiste, publication `DurableAtomicFile`) ; `EnvironmentHostedTenantKeyProvider` (`MINOS_TEAM_KEY_<ID>` base64 32 octets, dérivation HMAC-SHA256 par `tenant|keyId|purpose`). Composition : `DefaultMinosApplicationComposer.hostedControlPlaneStore` (`minos-bootstrap`). Surfaces : `TeamCommand` (CLI), `LocalMinosTeamApi` (API Java), `MinosApplicationMcpBackend.team*` + `MinosMcpTools` (MCP lecture seule, jeton par `MINOS_TEAM_TOKEN`).

Tests existants : `HostedControlPlaneServiceTest`, `HostedRoleGovernanceTest`, `HostedDenialSaturationTest`, `HostedDenialThrottleTest`, `HostedDeniedAuditReserveTest`, `HostedUnchainedRefusalIdentityTest`, `HostedUnchainedRefusalTraceTest`, `HostedCommitRecoveryTest`, `HostedCredentialAtomicityTest`, `HostedModelTest`, `HostedProductionBoundaryTest` (engine) ; `FileHostedControlPlaneStoreTest`, `FileHostedControlPlaneStoreLockTest` (storage-local) ; `LocalMinosTeamApiTest` (api) ; `TeamCommandTest`, `TeamOperationGuardTest`, `TeamCommandsArgumentRulesTest` (cli) ; `MinosApplicationMcpBackendM27Test` (mcp).

**Lacunes de test relevées** : identifiants de ressource non canoniques ou longs sur le chemin de refus (B-01, B-02, B-03) ; panne de `store.save` pendant un refus (B-03) ; mutation avec jeton à clé retirée (B-04) ; rollback du fichier (B-05) ; `retention-set`/`retention-apply` par un ADMIN (B-06) ; alias de `keyId` dans `EnvironmentHostedTenantKeyProvider` (B-10) ; aucun test dédié de `HmacHostedIdentityProvider` (expiration, dérive, jeton tronqué) en dehors des tests de service.

### 1.2 Stockage privé et secrets (`minos-engine/src/main/java/com/minos/io/`, 15 fichiers ; `com/minos/storage/`)

| Classe | Rôle |
|---|---|
| `PrivateLocalStorage` | politique propriétaire seul (POSIX 0700/0600 ; ACL Windows = ACE propriétaire + DACL protégée via `icacls /inheritance:d`, DENY conservés) ; création, durcissement, vérification |
| `ConfinedFileOpener` | ouverture sans suivre les liens (`SecureDirectoryStream` POSIX ; revalidation Windows), `openRegularFileNoFollow` |
| `DurableAtomicFile` | remplacement atomique, `fsync` fichier (+ répertoire hors Windows), `CommitUncertainException`, retries Windows |
| `BoundedFileLease`, `LeaseDeadline`, `SharedCacheLeaseRegistry` | verrous JVM + fichier bornés (10 s), baux de cache comptés |
| `BoundedInputStream/OutputStream/LineReader/Properties/FileDigest`, `FixedTsv`, `Sha256` | lectures bornées UTF-8 strict, empreintes exactes |
| `FileTreeOperations` | suppression d'arbre sans suivre liens/jonctions |
| `MinosRuntimeSettings`, `StorageBackendConfiguration` | précédence JVM > env > fichier, lecture de secret (relatif confiné / absolu), `toString` masqué |

Tests : `PrivateLocalStorageTest`, `PrivateLocalStorageWindowsAclTest`, `PrivateLocalStorageWindowsJunctionTest`, `PrivateLocalStorageWritesTest`, `ConfinedFileOpenerTest`, `ConfinedFileOpenerWindowsJunctionTest`, `OpenRegularFileNoFollowTest`, `DurableAtomicFileTest`, `BoundedFileLeaseTest`, `BoundedFileLeaseSymlinkTest`, `BoundedPropertiesTest`, `BoundedPropertiesSymlinkTest`, `SharedCacheLeaseRegistryTest`, `FileTreeOperationsTest`, `WriteDenyClassificationTest`, `StorageBackendConfigurationTest`, `AbsoluteSecretFileTest` (cité par `SEC-SUIVI.md`).
Lacunes : BOM (B-08) ; `publish` sur cible existante (B-09) ; propriété d'un secret/permissions sur un chemin absolu (observation).

### 1.3 `minos-storage-postgresql` (bonus, 17 classes, 3 241 lignes)

`PostgresJdbcUrlPolicy` (validation de l'URL avant remise des identifiants), `PostgresConnectionFactory` (pool de 8 connexions, délais 10 s/120 s, transaction imbriquée, `CommitUncertain` sur perte d'accusé de `COMMIT`, connexions dédiées aux verrous de cycle de vie), `PostgresSchemaMigrator` (verrou consultatif global transactionnel, schéma cité), stores/queries en `PreparedStatement`. Tests : 17 classes dont `PostgresConnectionFactoryTest` (politique d'URL), `PostgresSchemaMigratorTest`, `PostgresLifecycleLeaseOwnershipTest`. Lacunes : casse/encodage de `sslmode` (B-07), IPv6 (B-13).

---

## 2. Ce que je n'ai PAS examiné

- Tests exécutés : aucun `mvnw` ; seules des reproductions ciblées hors dépôt. Je n'ai pas rejoué la suite ni les gates `scripts/remediation/check-*.py` / `scripts/quality/check-hosted-control-plane-consistency.py`.
- Comportements spécifiques à Windows non reproductibles ici : `icacls` en jeton élevé, ACE de refus conditionnelles (S16), repli `discover` SID inconnu ; `SecureDirectoryStream` (Linux) non exercé (poste Windows). La sémantique `rename(2)` de B-09 sous Linux est déduite, pas mesurée.
- `HostedProductionBoundary`, `HostedIdentityVerifier`, `HostedBindingVerifier` (implémentation réelle côté application), le rendu `HostedControlPlaneRenderer`, `MinosMcpTools` (schémas d'arguments), `CliCommandSupport.failureMessage` (assainissement des messages d'erreur du CLI : je me suis fié à `MinosApiSupport.execute` pour l'API).
- `PrivateLocalStorage` côté POSIX réel (permissions Linux), TOCTOU `chmod` après `readAttributes`, ACL POSIX étendues, NFS/SMB.
- `BoundedOutputStream`, `FixedTsv`, `Sha256` : survol seulement. `SharedCacheLeaseRegistry` : lu, aucune anomalie.
- Modules PostgreSQL hors sécurité : `PostgresCodeKnowledgeSnapshotStore`, `PostgresProjectRegistry`, `PostgresSemantic*`, `PostgresStorageRetentionService` (cohérence transactionnelle, isolement par `project_id`) : lecture ciblée des requêtes uniquement ; pas d'analyse des niveaux d'isolation ni des interblocages. Pas d'essai contre une vraie base.
- Intégration Git (`JGitRemoteRepositoryMaterializer`, jetons Git), bac à sable (`SandboxLauncherScript`, AppContainer), `ProviderProcessEnvironment` : hors capacité, seulement croisés par grep.
- Non vérifié : que `minos-engine/target/classes` reflète exactement le HEAD (build concurrent en cours) ; les reproductions s'appuient sur ces classes et sur le source lu, qui concordent sur tous les points cités.
