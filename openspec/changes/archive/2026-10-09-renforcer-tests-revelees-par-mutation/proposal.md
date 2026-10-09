# Proposal

## Why

PIT (audit du 8 octobre 2026, `docs/quality/code-audit-constats.md`, fiche MINOS-AUD-H07) montre que les gardes qui protègent l'intégrité de la chaîne d'audit hébergée peuvent être supprimées sans qu'aucun test n'échoue : les invariants du constructeur de `HostedTenantState` (lignes 31 à 80), seule défense atteignable contre une chaîne altérée, la garde de séquence contiguë de `HostedAuditChain.verify` (ligne 97) et la garde de rôle de `HostedAuthorizationService.authorizeRead` (ligne 54). Retirer un événement au milieu d'une chaîne stockée n'est couvert par aucun test, alors que l'ADR 0035 et l'exigence « Altération toujours détectée » de `controle-tenant-heberge` le promettent. Les bornes de décodage des jetons (`HmacHostedIdentityProvider`) ne sont pas testées non plus. Deux risques relevés à la revérification (H14, H15) touchent la même voie de refus. Ce changement reprend la tâche 5.3 de `ajouter-audit-spotbugs-pitest`.

## What Changes

- Tests de falsification de l'état stocké : événement supprimé au milieu, deux événements permutés, événement d'un autre tenant, lien `previousHash` rompu, ancre incohérente, chaîne vide avec ancre non genèse ; chacun refusé à la lecture.
- Test de `verify` sur une séquence strictement croissante mais non contiguë (1 puis 3, HMAC valides), et sur une chaîne vide.
- Tests de refus par rôle sur la voie de lecture (`audit()` et plan de rétention) pour VIEWER et CONTRIBUTOR.
- Tests de bornes exactes : `MAX_AUDIT_EVENTS`, quart du budget d'octets d'un refus chaîné, longueurs maximales et jetons tronqués de `HmacHostedIdentityProvider`, expiration exacte.
- Décisions sur H14 (identifiant de requête invalide refusé avant authentification, sans trace) et H15 (`HostedAuditDelivery` et les `RuntimeException` d'un sink), avec un test qui fixe le comportement retenu.
- Documentation des mutants équivalents de `verify` (lignes 94, 100, 103, 110).
- Code de production modifié **seulement** si un nouveau test révèle un défaut.

## Capabilities

### New Capabilities

(aucune)

### Modified Capabilities

- `controle-tenant-heberge` : exigences ajoutées sur la détection d'une chaîne stockée altérée et sur le refus par rôle en lecture.

## Hors périmètre

- Les autres survivants de `minos-engine` (E/S confinées, stockage, orchestration) : lots suivants, après leur qualification.
- Un seuil PIT bloquant.

## Impact

- Module : `minos-engine` (tests, paquet `com.minos.hosted`) ; `minos-api` si un code d'erreur public est concerné par H14.
- Surfaces publiques : aucune, sauf décision H14 (code d'erreur côté API, additif).
- ADR : aucun nouvel ADR ; conforme à l'ADR 0035.
