# Team / Hosted — frontières de production M28

Le mode Team/Hosted de MINOS reste un **control plane embarqué, local-first et opt-in**. M28 le décompose en responsabilités cohésives et formalise ses ports opérateur sans le présenter comme un SaaS complet.

## Responsabilités internes

`HostedControlPlaneService` est une façade stable. Les responsabilités exécutées sont séparées :

| Composant | Responsabilité |
|---|---|
| `HostedTenantService` | bootstrap tenant, lecture authentifiée et audit borné |
| `HostedAuthorizationService` | authentification, membership et RBAC fail-closed |
| `HostedMembershipService` | grants, révocations, capacité et invariant du dernier owner |
| `HostedWorkspaceService` | workspaces et exact-snapshot bindings |
| `HostedRetentionService` | politique, plan et application explicite de rétention |
| `HostedTokenService` | émission de tokens courts et rotation des clés |
| `HostedAuditChain` | HMAC chaîné et vérification anti-tampering |
| `HostedTenantMutationWriter` | persistance optimiste, append audit puis export vers l’audit sink |

## Ports formalisés

| Port | Baseline embarquée | Claim autorisé |
|---|---|---|
| `HostedIdentityProvider` | `HmacHostedIdentityProvider` | implémentation de référence locale, pas IdP opéré |
| `HostedTenantKeyProvider` | provider de clés local/environnement | dérivation et résolution locales, pas KMS managé |
| `HostedAuditSink` | no-op par défaut ou adapter explicite | export d’événements persistés, pas SIEM managé |
| `HostedTransportSecurityPort` | non fourni | aucun listener réseau/TLS qualifié |
| `HostedAvailabilityPort` | non fourni | aucune HA, sauvegarde ou restauration opérée qualifiée |

## Disposition exposée

`HostedProductionBoundary` expose le mode `EMBEDDED_LOCAL_FIRST` et les limitations suivantes :

- `HOSTED_NETWORK_TRANSPORT_NOT_PROVIDED` ;
- `HOSTED_BACKUP_AVAILABILITY_NOT_PROVIDED` ;
- `HOSTED_SAAS_OPERATION_NOT_CLAIMED` ;
- `HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED`.

La baseline embarquée ne peut pas déclarer un transport ou une disponibilité `QUALIFIED_OPERATED`. Cette contrainte est vérifiée par construction et par `HostedProductionBoundaryTest`.

## Audit sink

Un audit sink externe reçoit uniquement l’événement correspondant à un état tenant déjà persisté. Les mutations autorisées et les refus RBAC audités sont exportés. Un échec d’export est remonté à l’appelant ; il ne doit pas être interprété comme une annulation de la mutation déjà persistée. Un déploiement opéré doit donc fournir une stratégie de reprise/idempotence adaptée au sink.

## Conditions avant un claim SaaS

Un service hosted opéré exige au minimum des adapters et des preuves indépendantes pour :

- identité fédérée et lifecycle des sessions ;
- KMS/HSM, rotation et contrôle d’accès aux clés ;
- transport TLS, authentification mutuelle ou politique équivalente ;
- isolation de processus et de tenants ;
- sauvegarde, restauration, disponibilité et objectifs opératoires ;
- collecte durable de l’audit et procédures de réponse ;
- qualification sécurité et charge sur un exact HEAD.

Tant que ces éléments ne sont pas fournis et qualifiés, MINOS revendique uniquement le control plane embarqué M27/M28.
