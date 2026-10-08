# Design

## Context

Rapport PIT du 8 octobre 2026 (`minos-engine`, 3 402 mutants) : survivants de `HostedTenantState.<init>` lignes 31, 34, 39, 40, 41, 43, 54, 56, 64, 65, 66 (×2), 70 (×2), 73 (×3), `requireHash:80` ; `HostedAuditChain.verify:90, 94, 97, 100, 103, 110 (×2)`, `verify:91` sans couverture ; `append:45` (×2) ; `HostedAuthorizationService.authorizeRead:54`, `admitsChainedDenial:171`, `recordDenial:147` ; `HmacHostedIdentityProvider` 19 survivants.

## Decisions

1. **Tester à travers le magasin, pas seulement le record.** Les falsifications sont écrites dans l'état stocké (fichier du magasin local) puis relues par le service : c'est le chemin qu'emprunterait une altération réelle. Un test unitaire du constructeur complète, pour la précision du message.
2. **Événements valides cryptographiquement.** Pour viser la garde de structure et non l'authentification, chaque événement falsifié est re-signé avec la clé de test (sinon `authenticate` tuerait le mutant à la place de la garde visée).
3. **Mutants équivalents documentés, pas exclus.** Les gardes 94, 100, 103 et 110 de `verify` sont inatteignables tant que le constructeur valide ; elles restent (défense en profondeur) et le fait est consigné dans `code-audit-constats.md`.
4. **H14 et H15 : décision avant test.** Option recommandée pour H14 : authentifier avant de valider la forme de `requestId`, puis tracer le refus (cohérent avec « l'autorisation précède la validation » de `HostedAuthorizedInvalidIdentifierTest`). Pour H15 : attraper toute exception d'un sink après persistance et la journaliser, sans changer le résultat de la mutation.

## Risks / Trade-offs

- Tests liés au format du magasin local → passer par l'API du magasin pour écrire l'état falsifié quand c'est possible.
- Plateformes : tests purement Java, identiques sous Windows et Linux.

## Direction des dépendances

Aucune dépendance ajoutée ; tests dans `minos-engine` (et `minos-storage-local` pour la voie par fichier si nécessaire, qui dépend déjà de `minos-engine`).
