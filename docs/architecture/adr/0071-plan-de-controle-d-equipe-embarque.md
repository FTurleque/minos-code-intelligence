---
id: ADR-0071
title: "Plan de contrôle d'équipe embarqué"
domain: security
rules: false
---

# ADR-0071 — Plan de contrôle d'équipe embarqué

- **Statut** : Accepted
- **Date** : 2026-10-23
- **Origine** : consolidation du registre (décision du propriétaire du 2026-10-23)
- **Remplace** : [ADR-0035 — Opt-in tenant control plane with external keys](archive/0035-opt-in-tenant-control-plane-with-external-keys.md), [ADR-0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 4
- **Remplacé par** : —

## Context

- MINOS est local-first, alors qu'une équipe a besoin de métadonnées de workspace partagées, d'appartenance, d'autorisation, de rétention et d'audit. Faire du processus existant un service réseau implicite affaiblirait le mode local, brouillerait les frontières entre tenants et obligerait MINOS à détenir des credentials ou à garder des clés (ADR 0035, Context).
- Le plan de contrôle embarqué peut être pris pour un SaaS opéré complet, alors que le transport, la disponibilité et les intégrations opérateur ne sont pas fournis (ADR 0036, Context).

## Decision

### 1. Un plan de contrôle opt-in et embarquable, pas un SaaS hébergé (ADR 0035, Decision)

- `MINOS_HOSTED_MODE=enabled` compose le service d'équipe ; son absence laisse le mode local inchangé.
- L'identité du tenant est présente dans chaque agrégat d'état et dérive du jeton porteur authentifié ; elle n'est jamais acceptée comme paramètre d'opération.
- Le fournisseur d'identité de référence signe des jetons porteurs `mht1` bornés par HMAC-SHA-256 ; un intégrateur de production peut remplacer le port d'identité.
- Les rôles OWNER, ADMIN, CONTRIBUTOR, VIEWER et AUDITOR correspondent à des permissions explicites.
- Un fichier chiffré par tenant est authentifié par AES-256-GCM, avec versionnement optimiste.
- La matière maîtresse de 256 bits est fournie de l'extérieur (`MINOS_TEAM_KEY_<KEY_ID>`) ; les clés de chiffrement, de jeton et d'audit propres à un usage et à un tenant sont dérivées en mémoire.
- Les événements d'audit forment une chaîne HMAC-SHA-256, mutations authentifiées autorisées et refusées par le RBAC comprises.
- La rotation de clé est explicite ; l'ancienne matière reste disponible tant que des événements d'audit conservés y renvoient.
- La rétention est en plan/apply : aucune suppression ni éviction implicite.
- Les liaisons de projets partagés conservent l'identité exacte du snapshot structuré actif.
- La CLI et l'API Java exposent l'administration ; le MCP expose cinq vues strictement en lecture seule ([ADR 0017](0017-mcp-stdio-read-only.md)) et lit son jeton dans `MINOS_TEAM_TOKEN`, jamais dans un argument d'outil.

### 2. Une façade et des frontières opérateur explicites (ADR 0036 § 4)

- `HostedControlPlaneService` est une façade sur des services cohésifs : tenant, autorisation, appartenance, workspace, rétention, jeton, audit et mutation.
- Les frontières opérateur restent des ports explicites : `HostedIdentityProvider`, `HostedTenantKeyProvider`, `HostedAuditSink`, `HostedTransportSecurityPort`, `HostedAvailabilityPort`.
- La base embarquée expose `HostedProductionBoundary.Mode.EMBEDDED_LOCAL_FIRST` et ne peut revendiquer ni transport ou TLS qualifié, ni sauvegarde ou disponibilité, ni exploitation SaaS, ni isolation de processus qui n'ont pas été fournis et qualifiés indépendamment.

### 3. Partage des responsabilités (ADR 0035, Consequences)

- Les opérateurs possèdent l'isolation des processus, l'injection des secrets, la sauvegarde et la disponibilité.
- Le snapshot statique reste autoritatif : l'appartenance et les métadonnées de workspace ne promeuvent aucune capacité de provider et ne changent aucun fait de code intelligence.

## Do's and Don'ts

### Do

- N'activer le plan de contrôle d'équipe que par `MINOS_HOSTED_MODE=enabled` ; sans lui, garder le mode local inchangé.
- Dériver l'identité du tenant du jeton porteur authentifié.
- Fournir de l'extérieur la matière maîtresse de 256 bits (`MINOS_TEAM_KEY_<KEY_ID>`) et dériver en mémoire les clés par usage et par tenant.
- Chiffrer l'état de chaque tenant par AES-256-GCM avec versionnement optimiste.
- Chaîner les événements d'audit par HMAC-SHA-256, mutations refusées par le RBAC comprises.
- Appliquer la rétention en plan/apply, sans suppression ni éviction implicite.
- Faire lire au MCP son jeton dans `MINOS_TEAM_TOKEN`, jamais dans un argument d'outil.
- Garder explicites, sous forme de ports, les frontières opérateur (`HostedIdentityProvider`, `HostedTenantKeyProvider`, `HostedAuditSink`, `HostedTransportSecurityPort`, `HostedAvailabilityPort`).
- Garder `HostedControlPlaneService` comme façade sur des services cohésifs.

### Don't

- Ne pas accepter un identifiant de tenant ou de principal fourni par l'appelant.
- Ne pas stocker de jeton porteur ni de clé maîtresse dans l'état du tenant.
- Ne pas accepter de secret en argument de CLI ou de MCP.
- Ne pas stocker l'état du plan de contrôle en JSON non chiffré.
- Ne pas supprimer de données par rétention automatique.
- Ne pas ajouter de serveur HTTP propre au plan de contrôle ni de dépendance cloud obligatoire.
- Ne pas présenter le mode embarqué comme un service multi-tenant opéré par MINOS ni comme un SaaS de production.

## Consequences

### Positives

- L'implémentation de référence est portable sous Windows et Linux et peut être embarquée derrière un autre transport (ADR 0035).
- Les revendications hébergées sont lisibles par machine et échouent fermé (ADR 0036).
- De futurs adaptateurs KMS, IdP, transport et disponibilité gardent des contrats explicites (ADR 0036).

### Négatives

- Le mode embarqué reste volontairement insuffisant pour un service SaaS opéré : fédération d'identité, KMS, transport TLS, isolation des tenants et des processus, sauvegarde et restauration, disponibilité exigent des adaptateurs propres à l'opérateur et une qualification indépendante (ADR 0036).
- MINOS ne fournit ni IdP, ni KMS, ni TLS, ni isolation réseau ; l'opérateur les apporte (ADR 0035).

### Risques

- L'export vers un puits d'audit exige encore, dans un adaptateur opéré, des reprises idempotentes (ADR 0036, Costs).
- Une matière de clé retirée trop tôt rend illisibles les événements d'audit conservés qui y renvoient (ADR 0035).

## Compliance and Enforcement

- Gate `scripts/quality/check-hosted-control-plane-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — vérifie notamment les rôles et bornes de l'état du tenant, le préfixe `MINOS_TEAM_KEY_`, le chiffrement `AES/GCM` sans sérialisation Java du magasin (ni `ObjectInputStream` ni `ObjectOutputStream`), la chaîne d'audit `HmacSHA256`, la rétention plan/apply et la rotation de clé, le jeton lu dans `MINOS_TEAM_TOKEN`, le catalogue MCP d'équipe limité aux vues en lecture seule sans `bearerToken` ni outil de mutation, et que la roadmap M27, les guides et le texte de l'ADR énoncent les mêmes faits (opt-in, tenant, AES-256-GCM, audit, rétention).
- Gate `scripts/quality/check-vertical-decomposition-consistency.py`, exécuté par `scripts/remediation/check-post-mne.py` (job `invariants`) — `HostedControlPlaneService` reste une façade d'au plus 260 lignes qui délègue aux services cohésifs, les cinq ports opérateur existent, `HostedProductionBoundary` porte `EMBEDDED_LOCAL_FIRST` et les refus `HOSTED_NETWORK_TRANSPORT_NOT_PROVIDED`, `HOSTED_BACKUP_AVAILABILITY_NOT_PROVIDED`, `HOSTED_SAAS_OPERATION_NOT_CLAIMED` et `HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED`, et `HostedProductionBoundaryTest` garde ses trois cas.
- Tests de `minos-engine` (lancés par `mvnw verify`) : `HostedProductionBoundaryTest` (aucune revendication de transport, disponibilité, SaaS ni isolation ; le puits d'audit externe reçoit les événements autorisés et refusés), `HostedReadPermissionTest` (lecture de l'audit et du plan de rétention soumise aux permissions, sans événement ajouté à la chaîne).
- Pas de règle Archgate pour l'instant : les chaînes vérifiables par lecture de fichiers sont tenues par les deux gates ci-dessus.
- Revue : aucune nouvelle opération ne reçoit d'identifiant de tenant ni de secret en paramètre ; aucune nouvelle frontière opérateur n'est implémentée dans le cœur à la place d'un port.

## References

- ADR remplacés : [0035](archive/0035-opt-in-tenant-control-plane-with-external-keys.md) (preuves de qualification M27 : [`M27_EXECUTION.md`](../../project/roadmap/M27_EXECUTION.md)), [0036](archive/0036-fail-closed-production-boundaries-and-measured-program-graph.md) § 4 (preuves M28 : [`M28_EXECUTION.md`](../../project/roadmap/M28_EXECUTION.md)).
- ADR liés : [0017](0017-mcp-stdio-read-only.md) (MCP en lecture seule), [0042](0042-racine-de-composition.md) (racine de composition), [0061](0061-deux-niveaux-de-confidentialite-pour-l-ecriture-de-fichiers-prives.md) (E/S privées), [0068](0068-modules-dependances-et-propriete-des-packages.md) (emplacement du package `hosted`), [0070](0070-code-non-fiable-confinement-et-indexation-distante.md) et [0072](0072-graphe-de-programme-honnete-sur-ses-capacites.md) (autres parts de l'ADR 0036).
- Spécification courante : [`openspec/specs/controle-tenant-heberge/spec.md`](../../../openspec/specs/controle-tenant-heberge/spec.md).
- Documentation : [`docs/user/team-hosted-mode.md`](../../user/team-hosted-mode.md), [`docs/developer/team-hosted-mode.md`](../../developer/team-hosted-mode.md).
- arc42 : [§ 8 Concepts transverses](../arc42/08-concepts-transverses.md), [§ 9 Décisions](../arc42/09-decisions.md).
