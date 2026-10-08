# Tasks

## 1. Intégrité de la chaîne d'audit (P1)

- [x] 1.1 Tests de falsification de l'état stocké (retrait, permutation, autre tenant, lien rompu, ancre) avec événements re-signés ; un test unitaire par garde du constructeur de `HostedTenantState`.
- [x] 1.2 Tests `verify` : séquence non contiguë, chaîne vide (genèse et non genèse).
- [x] 1.3 Consigner les mutants équivalents de `verify` (94, 100, 103, 110) dans `docs/quality/code-audit-constats.md`.

## 2. Autorisation (P1)

- [x] 2.1 Tests de refus par rôle sur `audit()` et le plan de rétention (VIEWER, CONTRIBUTOR, AUDITOR) : `HostedReadPermissionTest` (un refus en lecture n'est pas chaîné, ADR 0035).

## 2 bis. Nonce du stockage chiffré des tenants (P1)

- [x] 2b.1 Test dans `minos-storage-local` : deux écritures successives de l'état d'un même tenant produisent deux nonces différents (lecture de l'en-tête du fichier) ; le survivant `FileHostedControlPlaneStore.writeAtomically:197` passe à `KILLED` (PIT `-DtargetClasses=com.minos.storage.local.store.FileHostedControlPlaneStore`).

## 3. Bornes (P2)

- [x] 3.1 Bornes exactes : `MAX_AUDIT_EVENTS` (`append:45`), quart du budget d'octets (`admitsChainedDenial:171`).
- [x] 3.2 `HmacHostedIdentityProvider` : jetons tronqués, longueurs maximales de chaîne, expiration exacte, émission refusée.

## 4. Décisions H14 et H15

- [ ] 4.1 Décision de l'utilisateur sur H14 et H15 ; test qui fixe le comportement retenu ; correctif seulement si décidé (rejouer `check-audit-remediation-v2.py`, `check-p0-p2.py`, `check-minos-01.py`, et les scopes JaCoCo du plan de contrôle).

## 5. Validation

- [x] 5.1 `./mvnw -B -ntp -Paudit-mutation -pl minos-engine -am -DfailWhenNoMutations=false -DtargetClasses='com.minos.hosted.*' -DtargetTests='com.minos.hosted.*' test-compile org.pitest:pitest-maven:mutationCoverage` : les survivants retenus passent à `KILLED` ; `audit-report-summary.py pit --module minos-engine`.
- [ ] 5.2 `clean verify` sous Windows et CI Ubuntu.

## Évidence d'implémentation (2026-10-08)

- Tests ajoutés : `HostedAuditChainTamperingTest` (10), `HostedReadPermissionTest` (3), `HostedAuditBoundaryTest` (2), `HmacHostedIdentityProviderBoundaryTest` (6) dans `minos-engine` ; `HostedTenantNonceTest` (1) dans `minos-storage-local`. Aucun code de production modifié.
- PIT ciblé (`-pl minos-storage-local -am`, `targetClasses=com.minos.hosted.*,…FileHostedControlPlaneStore`) : tués désormais `HostedTenantState` 64, 65, 66, 70, 73 ; `HostedAuditChain.verify` 90, 97 ; `append` 45 ; `HostedAuthorizationService.authorizeRead` 54, `admitsChainedDenial` 171 ; `HmacHostedIdentityProvider` 42, 73, 120, 129 ; `FileHostedControlPlaneStore.writeAtomically` 197. `com.minos.hosted.*` : 266 tués, 117 survivants, 14 sans couverture.
- Survivants restants hors de ce lot : gardes de capacité et d'unicité de `HostedTenantState` (31 à 56), `requireHash` 80, bornes de longueur de `HmacHostedIdentityProvider` (58, 160, 165) ; équivalents : 116 (charge utile > 8 Kio impossible, le jeton étant borné à 8 Kio) et 139 (le contrôle `=` est redondant avec la ré-encodage canonique).
- Spec corrigée : un refus en lecture n'est pas chaîné (ADR 0035), la première version exigeait à tort une trace.
