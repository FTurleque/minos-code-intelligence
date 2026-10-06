# Design

## Context

Voir `proposal.md` (Why) pour la motivation et `specs/controle-tenant-heberge/spec.md` pour les
exigences. État du code au HEAD `bc1d3421`, relu pour ce changement (les preuves de la fiche B
tiennent toutes) :

- `HostedMembershipService.grant`/`revoke` et `HostedTokenService.issue` passent l'argument BRUT
  (`principalId`, `targetPrincipalId`) comme `resourceId` à
  `HostedAuthorizationService.authorizeMutation` ; `HostedPrincipal.safeId(...)` n'est appelé
  qu'après. Les autres appelants (`HostedWorkspaceService`, `HostedRetentionService`, rotation)
  passent des constantes ou un `UUID` : ils ne sont pas exposés à l'entrée libre.
- `authorizeMutation` appelle `recordDenial`, qui appelle `HostedAuditChain.append` (refus
  chaîné) ou `HostedAuditChain.unchainedRefusal` (refus non chaîné). Dans les deux méthodes, le
  HMAC est calculé par `hash(...)` sur les arguments bruts, puis le constructeur compact de
  `HostedAuditEvent` normalise (`HostedPrincipal.safeId` / `text`, `trim()`, plafond 4 096 pour
  `resourceId`). `HostedAuditChain.verify` / `authenticate` recalculent sur les champs normalisés.
  C'est la divergence de B-01 ; elle lève aussi `IllegalArgumentException` au-delà de 4 096
  caractères, avant toute trace (B-03).
- `recordDenial` n'a ni `try/catch` autour de `append` + `HostedCommitRecovery.save` ni repli :
  un `IOException` (conflit de version de `FileHostedControlPlaneStore.save`, panne d'E/S) sort
  tel quel (B-03). `LocalMinosTeamApi` passe par `MinosApiSupport.execute`, qui traduit
  `SecurityException` en `ACCESS_DENIED`, `IllegalArgumentException` en `INVALID_REQUEST` et
  `IOException` en `IO_FAILURE`.
- Bornes en nombre seulement : `HostedRetentionPolicy.admitsChainedDenial(long, int)` (API
  publique, testée par `HostedModelTest` et `HostedDeniedAuditReserveTest`). Bornes en octets
  seulement à l'écriture : `FileHostedControlPlaneStore` (`DEFAULT_MAX_TENANT_BYTES`, 32 MiB,
  `IOException("encoded hosted tenant exceeds byte limit")`).
- `authorizeRead` rejette une clé non active par `SecurityException` (« inactive key ») ;
  `authorizeMutation` combine `claims.keyId().equals(state.keyId())` avec l'appartenance et la
  permission, donc un jeton à clé retirée devient un refus RBAC chaîné (B-04).
  `HmacHostedIdentityProvider.authenticate` accepte toute clé résolvable, y compris retirée
  (nécessaire à l'historique, ADR-0035).
- Reproductions rouges déjà dans le dépôt : `AuditReproHostedDenialChainTest` (3 tests, B-01 et
  B-03), activée par `-Dminos.audit.repro=true`. Absents et à écrire : budget d'octets (B-02),
  repli sur échec de sauvegarde (B-03 b), rotation (B-04).

## Goals / Non-Goals

**Goals:**
- Une seule source de vérité pour la valeur d'un champ d'événement : le HMAC est calculé sur
  l'enregistrement, jamais sur des arguments.
- La voie des refus ne peut ni lever d'erreur autre que `SecurityException`, ni perdre sa
  trace, ni dépasser un budget d'octets, quelle que soit l'entrée ou la persistance.
- Aligner la mutation sur la lecture pour une clé non active.

**Non-Goals:**
- Aucun changement de format du fichier chiffré, d'AAD, de version de magasin (B-05 reste
  ouvert).
- Pas de nouvelle règle de rétention ni de changement de rôle (B-06).
- Pas de budget global par tenant ni de refus d'authentification des clés retirées au niveau du
  fournisseur d'identité (voir Questions ouvertes).
- Aucune modification de la plage publique de `maxAuditEvents` / `MAX_AUDIT_EVENTS`.

## Décisions

### D1. Hacher l'enregistrement, pas les arguments (B-01)

`HostedAuditChain.append` et `unchainedRefusal` construisent d'abord un événement provisoire
(condensat de substitution valide, par exemple `HostedAuditEvent.GENESIS_HASH`), calculent le
HMAC avec `hash(...)` à partir des accesseurs de cet événement, puis construisent l'événement
final avec le vrai condensat. Le HMAC est ainsi calculé sur la valeur exactement
persistée et exportée ; `verify` et `authenticate` ne changent pas.

*Alternatives écartées.* Canonicaliser les arguments avant `hash(...)` (`safeId` / `text`
répétés dans `HostedAuditChain`) : fonctionne, mais conserve deux sources de vérité
(l'avertissement de la fiche B-01 : « deux sources de vérité »). Interdire l'identifiant non
canonique à la porte de `authorizeMutation` en lançant `IllegalArgumentException` : cela
reproduit B-03 (refus perdu, `INVALID_REQUEST`).

*Compatibilité.* Pour un événement dont tous les champs sont déjà canoniques, l'entrée du HMAC
est identique octet pour octet à l'actuelle : aucune migration, exports existants valides
(exigence « Les chaînes d'audit existantes restent vérifiables »). Test de famille : tout champ
d'un événement construit par la chaîne est un point fixe de la canonicalisation (reconstruire
l'événement depuis ses accesseurs donne un événement égal).

### D2. Forme bornée du champ de ressource d'un refus (B-02, B-03)

Une fonction unique, appelée par `recordDenial` (donc par les chemins `authorizeMutation` et
`deny`), produit l'identifiant de ressource du refus : valeur sans espaces de bord si elle
satisfait la grammaire d'identifiant sûr (jusqu'à 128 caractères), sinon
`invalid:` suivi du SHA-256 hexadécimal de la valeur brute (72 caractères, conforme à la
grammaire, ASCII). La valeur brute n'est jamais écrite dans l'audit ni dans le journal.
Conséquences : taille d'événement bornée par construction, ASCII donc octets = caractères,
construction de l'événement non faillible pour ce champ (B-03).

L'autorisation précède toujours la validation : un appelant autorisé qui fournit un
identifiant invalide reçoit toujours `IllegalArgumentException` (`INVALID_REQUEST`) sans
événement de refus (exigence dédiée) ; seul un appelant refusé voit sa valeur transformée.

*Alternatives écartées.* Tronquer à 128 sans condensat : deux valeurs distinctes
deviennent indiscernables dans l'audit. Rejeter l'identifiant avant l'audit : B-03.
La collision volontaire (un appelant qui écrit lui-même `invalid:<hex>`) est inoffensive :
elle ne produit qu'un identifiant sûr, aucune confusion de privilège.

### D3. Budget d'octets sur les refus chaînés (B-02)

Le moteur ne dépend pas du stockage (ADR-0022 : `domain -> engine -> storage`). La limite
d'octets étant celle du magasin, `HostedControlPlaneStore` (port du moteur, public) reçoit une
méthode additive à valeur par défaut qui retourne la limite d'octets du tenant (par défaut :
aucune limite). `FileHostedControlPlaneStore` la redéfinit avec son plafond existant
(`DEFAULT_MAX_TENANT_BYTES` par défaut). `HostedAuthorizationService.recordDenial` ajoute à
l'admission actuelle (`admitsChainedDenial`, signature publique inchangée) une condition
d'octets : la taille estimée des événements `DENIED` déjà chaînés (borne supérieure calculée
dans le même parcours que `chainedDenials`) plus celle du nouvel événement reste sous une
fraction de la limite du magasin, qui laisse le reste aux mutations autorisées. Au-delà : voie
non chaînée existante (journal WARNING + `publishUnchained`), état intact.

La fraction (proposition : un quart de la limite) et l'estimation de taille sont des constantes
documentées par Javadoc et testées ; l'estimation est une majoration (octets UTF-8 des champs
plus un cadrage fixe) et non un duplicata du codage du magasin, pour ne pas coupler le moteur
au format fichier. Avec des événements bornés par D2 (de l'ordre de 1 KiB au pire), un quart de 32 MiB admet au
plus de l'ordre de 8 000 refus de taille maximale, soit moins que les 9 000 de la politique par
défaut : le budget d'octets est la contrainte qui lie en cas d'abus, le budget en nombre dans le
cas nominal (événements d'environ 300 octets).

*Alternatives écartées.* (a) Abaisser `MAX_AUDIT_EVENTS` pour que
`MAX_AUDIT_EVENTS × taille_max_d'un_événement < limite` : change la plage publique de
`maxAuditEvents` (contrat CLI `retention-set` et API), voir Questions ouvertes.
(b) Mesurer la taille réelle chiffrée avant chaque refus : couple le moteur au codage du magasin
et réencode 32 MiB à chaque refus. (c) Compter seulement D2 : borne la taille d'un refus mais
pas leur somme (B-02 reste reproductible avec des identifiants de 128 caractères sur la
durée).

### D4. Repli sur le refus non chaîné (B-03)

`recordDenial` encadre « admission + `append` + `HostedCommitRecovery.save` +
`publishAfterCommit` » par `try { … } catch (IOException | RuntimeException failure)` : en cas
d'échec avant la persistance, il construit l'événement non chaîné (D1/D2 le rendent non
faillible), le publie par `HostedAuditDelivery.publishUnchained` (journal WARNING d'abord) et
journalise la cause. L'appelant reçoit toujours le `SecurityException` de l'appelant
(`authorizeMutation` / `deny`). Cas particulier : une `CommitUncertainException` non
résolue par `HostedCommitRecovery` peut avoir persisté le refus ; la double trace (chaînée
puis non chaînée) est acceptée plutôt qu'une trace perdue. Un échec de `publishAfterCommit`
après sauvegarde reste du ressort de `HostedAuditDelivery` (déjà non propagé).

La structure existante de `recordDenial` est conservée (mêmes chaînes littérales : le gate
`check-hosted-control-plane-consistency.py` exige `authorizeMutation`,
`HostedAuditEvent.Outcome.DENIED`, `hosted permission denied` et `auditChain.verify(state)` dans
ce fichier ; `check-p0-p2.py` exige `hosted audit event authentication failed` dans
`HostedAuditChain.java`).

### D5. Une clé non active n'est jamais un refus RBAC (B-04)

Dans `authorizeMutation`, le test `claims.keyId().equals(state.keyId())` est sorti de
`allowed` : s'il échoue, la méthode lève `SecurityException("hosted bearer token uses an
inactive key")` (même message que `authorizeRead`) avant `recordDenial`, sans événement chaîné et
sans non chaîné attribué au nom présenté. Elle écrit une entrée de journal WARNING bornée : une
seconde instance de `HostedDenialThrottle` (classe existante) indexée par
(tenant, identifiant de clé du jeton) limite le débit des entrées ; l'entrée contient le tenant et
l'identifiant de clé, pas le jeton, pas le nom de principal comme acteur authentifié. Pas de
nouvelle classe.

Un membre à clé active sans droit reste un refus RBAC chaîné (inchangé).

*Alternative écartée.* Refuser les clés retirées dans le fournisseur d'identité : exige que
`HmacHostedIdentityProvider` connaisse la clé active du tenant (couplage avec l'état) et
change le contrat de `HostedIdentityProvider` ; question ouverte.

### Direction des dépendances (ADR-0022)

Tout le code de production reste dans `minos-engine` (`com.minos.hosted`) sauf la
redéfinition de la méthode additive du port dans `minos-storage-local`
(`storage -> engine`, sens autorisé). `minos-api` ne reçoit qu'un test. Aucune dépendance
nouvelle, aucun module créé. Aucune exception à signaler.

### Qualification des capacités (capability-honesty)

| Capacité / garantie | Qualification visée | Remarque |
|---|---|---|
| Chaîne vérifiable après tout refus (B-01) | Qualifiée sur Windows et Linux par les tests moteur (JVM pure, aucune dépendance de plateforme) | |
| Refus bornés en octets (B-02) | Partielle | borne les refus ; la saturation par volume d'événements AUTORISÉS (100 000 × ~282 octets dépasse 32 MiB) reste à traiter avec la question ouverte sur `MAX_AUDIT_EVENTS` |
| Trace de tout refus (B-03) | Qualifiée | une `CommitUncertainException` peut produire une double trace |
| Jeton à clé retirée = authentification (B-04) | Partielle | la clé retirée authentifie encore au niveau du fournisseur ; elle n'ouvre plus aucune mutation ni événement chaîné |
| Détection de rejeu / rollback du fichier (B-05) | Non supportée | hors périmètre, décision à clarifier |
| Budget global de refus par tenant | Non supportée | question ouverte |

### Windows et Linux

Aucun comportement propre à une plateforme : code JVM pur, `FileHostedControlPlaneStore`
écrit déjà par remplacement atomique durable sur les deux OS. Le test de budget d'octets utilise
un `@TempDir` et un magasin à limite basse (le constructeur de paquet exige au moins 1 024 octets) ;
il doit passer sur Ubuntu 24.04 et Windows Server 2022. Les identifiants de test sont ASCII (octets
= caractères), donc aucun effet de page de code. Sous Windows, un échec transitoire
`Move-Item` du packaging est un faux positif connu de la CI, hors sujet ici.

## Risks / Trade-offs

- [Un tenant déjà corrompu par B-01 reste illisible : le correctif empêche de nouveaux cas mais
  n'affaiblit pas la vérification] → exigence explicite (« Chaîne antérieure déjà corrompue »),
  question ouverte sur l'outil de réparation, aucune tolérance ajoutée dans `verify`.
- [Le budget d'octets d'un quart peut faire basculer en non chaîné des refus légitimes plus tôt
  que le budget en nombre] → les refus restent appliqués et journalisés ; réserve documentée dans
  `docs/developer/team-hosted-mode.md` (section « Concurrency, audit and retention »).
- [Estimation de taille faussée si le codage du magasin change] → majoration prudente et test
  qui compare l'estimation à la taille réelle d'un événement encodé par le magasin (dans
  `minos-storage-local`).
- [Renommage ou restructuration cassant un gate littéral] → tâches nommant les gates à
  rejouer ; aucune chaîne littérale du gate n'est supprimée (D4, D5).
- [Journal WARNING des clés retirées volumineux] → limitation de débit par (tenant, clé) ; test.
- [Méthode additive sur un port public] → valeur par défaut, aucune implémentation tierce
  cassée ; mentionnée dans la documentation développeur.

## Migration Plan

Aucune migration de données (format et HMAC inchangés pour les événements canoniques).
Déploiement par fusion dans `develop` ; retour arrière par revert, sans état à nettoyer (les
événements écrits après le correctif sont canoniques et restent vérifiables par l'ancienne
version, car le HMAC des champs canoniques est identique).

## Questions ouvertes

Aucune ne bloque les tâches ; chacune est une décision de l'utilisateur.

1. **Réparation d'un tenant déjà corrompu par B-01** : fournir un outil ou une procédure
   documentée, ou accepter la reprise manuelle (suppression/édition du `.mht` avec les clés) ?
2. **Aligner `MAX_AUDIT_EVENTS`** (100 000) sur le plafond d'octets (la capacité nominale est
   inatteignable : 100 000 événements de taille maximale dépassent 32 MiB) : modifie la plage
   publique de `maxAuditEvents`. Hors périmètre tant que non tranché.
3. **Budget global de refus chaînés par tenant** (proposé par la fiche B-04, en plus du budget
   par principal) : utile contre une rotation de noms de principal à clé active ? Non retenu ici ;
   à ajouter en tâche conditionnelle si l'utilisateur le demande.
4. **Une clé retirée doit-elle cesser d'authentifier au niveau du fournisseur d'identité** (« une
   clé retirée n'authentifie plus rien », formulation de la fiche B-04), au prix d'un couplage
   avec l'état du tenant ? Le périmètre actuel traite le classement, pas l'authentification.
5. **Fraction du budget d'octets** (un quart proposé) : valeur de confort modifiable sans
   changer les exigences.

## Écarts constatés à l'implémentation (2026-10-06)

Aucun ne change les exigences ; ils précisent les décisions D1 à D5.

- **D3** : `HostedAuditEvent.estimatedEncodedBytes()` est une méthode **publique** (additive) et non de portée paquet : le test qui la confronte au codage réel du magasin vit dans `minos-storage-local` (`HostedEventSizeEstimateTest`). L'allocation de cadrage est de 128 octets (le codage local en consomme 72 hors champs texte). Le budget d'octets est calculé sur les seuls événements `DENIED` de la chaîne : un tenant dont les événements **autorisés** dépassent à eux seuls la limite reste hors de portée de ce changement (qualification « partielle » inchangée, question ouverte 2).
- **D2** : le prédicat `HostedPrincipal.isSafeId` est de portée paquet ; la fonction unique `HostedAuthorizationService.refusalResourceId` est de portée paquet et testée directement.
- **D4** : `recordDenial` et `deny` ne déclarent plus `throws IOException` puisqu'un refus n'échoue plus ; l'événement non chaîné est construit **avant** la tentative de chaînage (il sert aussi d'estimation de taille et de trace de repli), ce qui coûte un HMAC supplémentaire par refus.
- **D1** : l'événement provisoire porte `GENESIS_HASH` comme condensat de substitution ; seul l'événement final, authentifié depuis ses propres accesseurs, est publié ou persisté.
