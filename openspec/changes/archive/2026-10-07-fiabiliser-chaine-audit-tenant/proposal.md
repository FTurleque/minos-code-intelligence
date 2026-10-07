# Proposal

## Why

Le plan de contrôle tenant hébergé (ADR-0035) promet une chaîne d'audit HMAC qui contient les
mutations autorisées ET les refus RBAC, des refus « bornés de sorte qu'ils ne puissent jamais
affamer les mutations autorisées », et un échec fermé central. L'audit 2026-10 (annexe B) montre
que la voie des refus ne tient pas ces promesses au HEAD `bc1d3421` :

- un refus dont l'identifiant de ressource n'est pas canonique (espace ou tabulation en tête ou
  en fin) est haché sur la valeur brute puis persisté sur la valeur normalisée : la chaîne devient
  invérifiable et le tenant entier est inutilisable, lectures comprises, par un simple VIEWER
  (reproduit) ;
- les refus chaînés sont bornés en nombre mais pas en octets : un VIEWER peut atteindre la limite
  de 32 MiB du fichier chiffré et bloquer les écritures autorisées ;
- un refus peut disparaître sans trace, ou changer de nature d'erreur (identifiant invalide,
  conflit de version, panne d'E/S à l'écriture du refus) ;
- un jeton signé par une clé retirée est traité comme un refus RBAC audité en mutation alors que
  la lecture le rejette comme un échec d'authentification : l'audit est pollué et la
  protection par principal contournée.

Ces quatre défauts partagent un même point d'entrée (l'autorisation d'une mutation et
l'enregistrement d'un refus) : les corriger ensemble évite quatre retouches du même code et un
état intermédiaire où un correctif en expose un autre (par exemple, borner l'identifiant sans
repli non chaîné transforme B-02 en B-03).

## What Changes

- Un refus ne peut plus altérer la chaîne : l'événement d'audit est construit d'abord et son
  HMAC est calculé sur les champs de l'enregistrement, jamais sur les arguments bruts. Tout
  champ d'un événement est un point fixe de la canonicalisation.
- Le champ « identifiant de ressource » d'un refus est ramené à une forme bornée (identifiant sûr
  de 128 caractères au plus, sinon représentation déterministe par condensat) avant l'audit :
  un refus ne peut plus échouer ni grossir à cause de ce champ.
- L'admission d'un refus dans la chaîne tient compte d'un budget d'octets (en plus du budget en
  nombre) : au-delà, le refus reste appliqué, journalisé et livré en non chaîné, et les
  mutations autorisées restent possibles.
- Le chemin de refus bascule sur le refus non chaîné en cas d'échec de la persistance (conflit de
  version, E/S) ou d'identifiant invalide, et lève toujours un refus d'autorisation (jamais
  une erreur de requête ou d'E/S).
- Une mutation présentée avec un jeton à clé retirée est un échec d'authentification
  (comportement déjà celui de la lecture) : aucun événement chaîné, journal borné, aucune
  attribution à un principal non authentifié.
- Les tests de reproduction rouges existants sont rendus actifs, puis renommés selon la
  convention du module ; les tests rouges manquants (budget d'octets, trace de refus,
  rotation) sont écrits avant le correctif.

Aucun changement de format persistant : un événement canonique garde le même HMAC, donc les
chaînes et exports existants restent vérifiables. Aucun changement **BREAKING** de contrat
public ; l'ajout éventuel au port de stockage est une méthode à valeur par défaut (additif).

## Capabilities

### New Capabilities
- `controle-tenant-heberge`: garanties observables du plan de contrôle tenant hébergé sur la
  voie des refus et de la chaîne d'audit (intégrité et vérifiabilité de la chaîne, bornes en
  nombre et en octets des refus, trace obligatoire d'un refus, classement authentification
  contre autorisation d'un jeton à clé retirée).

### Modified Capabilities
<!-- Aucune : openspec/specs/ est vide, la capacité est créée par ce changement. -->

## Constats d'audit couverts

Source : `docs/audit/annexes/B-tenant-secrets.md` (identifiant `B-NN` = `MINOS-AUD-BNN`).

| Constat | Qualification | Priorité | Traité par |
|---|---|---|---|
| MINOS-AUD-B01 — refus à identifiant non canonique : HMAC sur valeur brute, chaîne corrompue | DÉFAUT CONFIRMÉ (reproduit) | P1 | exigences « Un refus ne corrompt jamais la chaîne d'audit » et « Les chaînes d'audit existantes restent vérifiables », tâches 1.1, 2.1, 2.2 |
| MINOS-AUD-B02 — refus non bornés en octets, saturation des 32 MiB | DÉFAUT CONFIRMÉ (arithmétique mesurée) | P1 | exigences « Le champ de ressource d'un refus est borné » et « Les refus chaînés sont bornés en octets », tâches 1.2, 3.1, 4.1 |
| MINOS-AUD-B03 — refus perdu ou mauvais code d'erreur | DÉFAUT CONFIRMÉ (identifiant invalide) ; RISQUE (conflit de version) | P2 | exigences « Tout refus laisse une trace et reste un refus d'autorisation » et « Un identifiant invalide d'un appelant autorisé reste une requête invalide », tâches 1.1, 1.2, 3.1, 5.1, 5.2 |
| MINOS-AUD-B04 — jeton à clé retirée classé refus RBAC, pollution de l'audit | DÉFAUT CONFIRMÉ (classement) ; RISQUE (abus par clé retirée compromise) | P2 | exigence « Un jeton à clé retirée est un échec d'authentification », tâches 1.2, 6.1 |

## Impact

**Modules du reactor touchés**

- `minos-engine` (package `com.minos.hosted`) : voie des refus, chaîne d'audit, politique de
  rétention, port de stockage (méthode additive éventuelle).
- `minos-storage-local` : implémentation du port de stockage (limite d'octets exposée) et test de
  budget d'octets sur le vrai magasin chiffré.
- `minos-api` : test uniquement (code d'erreur d'un refus), pas de changement de production.
- Aucun changement dans `minos-cli`, `minos-mcp`, `minos-app`, `minos-bootstrap`.

**Surfaces publiques impactées**

- API Java (`HostedControlPlaneService`, `HostedControlPlaneStore`, `MinosTeamApi`) : codes
  d'erreur plus justes (un refus est toujours un refus d'autorisation), méthode additive à valeur
  par défaut sur le port de stockage. Pas de rupture.
- CLI `minos team` : mêmes commandes ; seuls les codes et messages d'erreur d'un refus changent.
- MCP : non concerné (cinq vues en lecture seule, ADR-0017) ; les lectures continuent de vérifier
  la chaîne à chaque requête.
- IntelliJ et NEXUS : non concernés.

**ADR**

- ADR-0035 est **amendé** (note de clarification, sa décision ne change pas) : « un jeton signé
  par une clé retirée est un échec d'authentification, jamais un refus RBAC audité ; la clé
  retirée reste disponible pour vérifier l'historique mais n'ouvre aucune mutation ». La
  rédaction de cet amendement est hors périmètre de ce changement (à proposer à l'utilisateur).
- Aucun nouvel ADR requis.

**Gates à rejouer** : `python scripts/quality/check-hosted-control-plane-consistency.py`
(affirme des chaînes littérales de `HostedAuthorizationService.java`, `HostedAuditChain.java`,
du magasin et de `HostedControlPlaneServiceTest.java`), `python scripts/remediation/check-p0-p2.py`
(affirme `authorizeMutation`, `hosted audit event authentication failed` et
`rejectsPersistedAuditEventWithInvalidHmac`), et le scope JaCoCo `m27-team-hosted-control-plane`
de `scripts/quality/check-jacoco.py`.

## Hors périmètre

- MINOS-AUD-B05 (rejeu / rollback du fichier chiffré), MINOS-AUD-B06 (ADMIN et rétention de la
  piste d'audit), MINOS-AUD-B10 (hygiène de rotation : alias d'identifiants de clé, réutilisation
  d'une ancienne clé, révocation unitaire) et MINOS-AUD-B11 (contrat d'idempotence de
  `--request-id`) : ce sont des **décisions à clarifier** par l'utilisateur, non tranchées ici.
- MINOS-AUD-B07, B08, B09, B13 (sécurité PostgreSQL, BOM, publication atomique, IPv6) et B12
  (dérive documentaire) : autres changements.
- Réparation d'un tenant dont la chaîne est déjà corrompue par B-01 (voir questions ouvertes du
  design).
- Toute modification de la plage publique de `maxAuditEvents` ou de `MAX_AUDIT_EVENTS`.
- Changement de format de fichier ou de version d'AAD du magasin chiffré.
