# M27 — Team / Hosted Mode — exécution

Statut : **TERMINÉ, VALIDÉ EXACT-HEAD WINDOWS + LINUX ET FUSIONNÉ DANS develop — 9/9.**

```text
Issue          : #90 — CLOSED / completed
PR             : #91 — MERGED
Branche        : m27-team-hosted-mode
Base           : develop @ 5db06f2a778b60b318ae6d83ad76928c24672810
Qualified HEAD : d4bd51ef52cb329ab75b70b32bc22e2b236bd65d
Merge develop  : ee22c3b39b9cd891c18cb61188eb8e973fc7e822
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

### M27-S9 — Qualification et promotion exact-head ✅ TERMINÉ

Windows et Linux ont validé `d4bd51ef52cb329ab75b70b32bc22e2b236bd65d` sur des worktrees propres. La PR #91 a ensuite été passée Ready et fusionnée dans `develop` avec protection du HEAD attendu ; l’issue #90 est fermée completed.

## Dispositions finales

| Surface | Disposition finale | Limite explicite |
|---|---|---|
| contrôle tenant embarqué | `QUALIFIED_WITH_CONSTRAINTS` | pas un SaaS opéré ni un serveur réseau |
| auth HMAC de référence | `QUALIFIED_WITH_CONSTRAINTS` | IdP/KMS et injection des secrets opérateur |
| store AES-256-GCM | `QUALIFIED_WITH_CONSTRAINTS` | backup/disponibilité hors scope |
| shared workspaces | `QUALIFIED_WITH_CONSTRAINTS` | bindings snapshot actif exact uniquement |
| audit et rétention | `QUALIFIED_WITH_CONSTRAINTS` | anciennes clés conservées selon rétention |
| CLI/API team | `QUALIFIED_WITH_CONSTRAINTS` | mutations explicites et authentifiées |
| MCP team | `QUALIFIED_WITH_CONSTRAINTS` | cinq tools strictement read-only |

## Preuves exact-head

```text
Windows x86_64
M27 TEAM HOSTED END-TO-END SUCCESS
M27 FINAL TEAM HOSTED MODE VALIDATION SUCCESS
Validated HEAD: d4bd51ef52cb329ab75b70b32bc22e2b236bd65d

Linux x86_64
M27 TEAM HOSTED END-TO-END SUCCESS
M27 LINUX TEAM HOSTED MODE VALIDATION SUCCESS
Validated HEAD: d4bd51ef52cb329ab75b70b32bc22e2b236bd65d
```

Les deux évidences détaillées portent `status: PASS` et le même `commit`. Elles prouvent deux tenants isolés, le refus RBAC audité, le rejet d’un snapshot obsolète, la rotation de clé, le chiffrement AES-256-GCM sans plaintext, le rejet du tampering, l’absence de suppression implicite et le catalogue MCP de 31 tools dont cinq vues team read-only.

## Jalon suivant

Aucun M28 n’est défini dans la roadmap courante. Un prochain jalon devra être cadré explicitement ; M21-S2/CI reste inchangé et en pause jusqu’en août 2026.

## Critères de sortie

1. gates structurel/documentaire et reactor Maven Java 24 verts ;
2. JaCoCo M27 vert sans réduction historique ;
3. shaded-JAR e2e détaillé `status: PASS` sur Windows et Linux ;
4. même exact HEAD propre, aucun workflow modifié ;
5. PR #91 revue, Ready puis fusionnée dans `develop` avec protection du HEAD ;
6. issue #90 fermée completed ;
7. réconciliation documentaire post-merge avec SHA réels et prochain jalon explicitement non défini.
