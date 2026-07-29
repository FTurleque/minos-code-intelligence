# M27 — Team / Hosted Mode — exécution

Statut : **IMPLÉMENTATION CANDIDATE — qualification exact-head Windows + Linux requise avant promotion — 8/9.**

```text
Issue          : #90 — OPEN
PR             : #91 — OPEN / DRAFT
Branche        : m27-team-hosted-mode
Base           : develop @ 5db06f2a778b60b318ae6d83ad76928c24672810
Qualified HEAD : PENDING
Merge develop  : PENDING
Date           : 29 juillet 2026
```

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. M27 ne modifie, n’exécute et n’utilise aucun workflow CI comme preuve.

## Question produit

> MINOS peut-il fournir un contrôle partagé multi-tenant sans abandonner son mode local, ses snapshots autoritatifs ni ses frontières de sécurité ?

## Invariants

- team/hosted opt-in ; mode local inchangé par défaut ;
- tenant et principal dérivés de l’identité authentifiée, jamais crus depuis une requête ;
- RBAC explicite et isolement tenant fail-closed ;
- état au repos AES-256-GCM, clés maîtres externes, rotation explicite ;
- audit HMAC-SHA-256 chaîné et vérifié ;
- aucune suppression implicite : rétention planifiée puis appliquée ;
- bindings vers le snapshot actif exact, sans mutation des facts structurés ;
- secrets absents des fichiers, logs, arguments CLI et schémas MCP ;
- MCP strictement read-only ;
- qualification locale exact-head Windows x86_64 + Linux x86_64 sur le même SHA.

## Sous-incréments

### M27-S1 — Cadrage, issue et ADR ✅ IMPLÉMENTÉ

Issue #90, draft PR #91, branche dédiée et ADR-0035 séparent contrôle embarqué qualifié et service SaaS opéré.

### M27-S2 — Modèle tenant et RBAC ✅ IMPLÉMENTÉ

Agrégats validés pour tenants, rôles, permissions, principals, shared workspaces et exact-snapshot bindings.

### M27-S3 — Authentification et clés externes ✅ IMPLÉMENTÉ

Tokens `mht1` HMAC bornés, claims authentifiés, master keys base64 256 bits externes et dérivation par tenant/purpose.

### M27-S4 — Store chiffré et isolation ✅ IMPLÉMENTÉ

Un fichier par tenant, AES-256-GCM avec AAD, codec borné, atomic move, verrou, optimistic concurrency, symlink/tamper fail-closed.

### M27-S5 — Audit, rotation et rétention ✅ IMPLÉMENTÉ

Chaîne HMAC vérifiée, décisions RBAC autorisées/refusées, rotation explicite et plan/apply sans éviction implicite.

### M27-S6 — Shared workspaces et snapshot authority ✅ IMPLÉMENTÉ

Les bindings exigent le snapshot actif exact et ne modifient ni le store structuré ni les capabilities provider.

### M27-S7 — CLI, API Java et MCP ✅ IMPLÉMENTÉ

CLI/API couvrent l’administration. Le MCP passe à 31 tools avec cinq vues team read-only et aucun argument secret.

### M27-S8 — Tests, docs et e2e local ✅ IMPLÉMENTÉ

Tests et e2e couvrent deux tenants, RBAC, token tamper/expiry, ciphertext tamper, rotation, audit, rétention, limites et compatibilité local mode.

### M27-S9 — Qualification et promotion exact-head ⏳ EN ATTENTE

Windows et Linux doivent valider le même SHA propre avant Ready, merge dans `develop`, fermeture #90 et réconciliation documentaire.

## Dispositions candidates

| Surface | Disposition candidate | Limite explicite |
|---|---|---|
| contrôle tenant embarqué | `CANDIDATE_FOR_QUALIFICATION` | pas un SaaS opéré ni un serveur réseau |
| auth HMAC de référence | `CANDIDATE_FOR_QUALIFICATION` | IdP/KMS et injection des secrets opérateur |
| store AES-256-GCM | `CANDIDATE_FOR_QUALIFICATION` | backup/disponibilité hors scope |
| shared workspaces | `CANDIDATE_FOR_QUALIFICATION` | bindings snapshot actif exact uniquement |
| audit et rétention | `CANDIDATE_FOR_QUALIFICATION` | anciennes clés conservées selon rétention |
| CLI/API team | `CANDIDATE_FOR_QUALIFICATION` | mutations explicites et authentifiées |
| MCP team | `CANDIDATE_FOR_QUALIFICATION` | cinq tools strictement read-only |

## Critères de sortie

1. gates structurel/documentaire et reactor Maven Java 24 verts ;
2. JaCoCo M27 vert sans réduction historique ;
3. shaded-JAR e2e détaillé `status: PASS` sur Windows et Linux ;
4. même exact HEAD propre, aucun workflow modifié ;
5. PR #91 revue, Ready puis fusionnée dans `develop` avec protection du HEAD ;
6. issue #90 fermée completed ;
7. réconciliation documentaire post-merge avec SHA réels et prochain jalon explicitement non défini.
