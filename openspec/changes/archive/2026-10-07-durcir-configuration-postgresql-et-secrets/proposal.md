# Proposal

## Why

Quatre défauts de la couche de configuration et de stockage privé font croire à une garantie qui
n'est pas tenue, sans erreur visible :

- La politique d'URL PostgreSQL accepte `?SSLMODE=verify-full` et `?ssl%6Dode=verify-full` pour un
  hôte externe, parce qu'elle décode et met en minuscules le nom du paramètre, alors que le pilote
  (pgjdbc 42.7.13, `Driver.parseURL`, mesuré pendant la rédaction de ce changement) ne reconnaît que
  la clé exacte `sslmode`. Le pilote retombe alors sur `prefer` : TLS opportuniste, sans
  vérification de certificat ni de nom d'hôte. MINOS affirme avoir imposé `verify-full` et ne l'a pas
  fait ; le mot de passe PostgreSQL traverse un canal non authentifié.
- Un BOM UTF-8 en tête de `minos.properties` (Windows PowerShell 5.1 `Out-File -Encoding utf8`,
  anciens Bloc-notes) est décodé comme un caractère valide : la première propriété est perdue sans
  erreur (`minos.storage.backend=postgresql` en première ligne bascule silencieusement sur `local`),
  et le BOM s'ajoute au mot de passe lu d'un fichier, que PostgreSQL refuse avec un message opaque.
- L'adresse de bouclage IPv6 `[::1]` est rejetée par la politique (le JDK renvoie l'hôte entre
  crochets, la comparaison attend `::1`), et les diagnostics affichent `[[::1]]`. Le défaut est
  fail-closed mais rend l'IPv6 local inutilisable.
- `DurableAtomicFile.publish`, dont la Javadoc promet qu'« an existing target is never replaced »,
  remplace en réalité une cible existante (reproduit sous Windows, et `rename(2)` fait de même sous
  Linux). Latent aujourd'hui, car les deux appelants vérifient avant sous verrou, mais une
  publication « immuable » sans garde externe serait écrasée.

## What Changes

- **B07** : la politique compare le nom de chaque paramètre d'URL **brut**, sans décodage ni mise en
  minuscules, et refuse tout autre ; un test de parité contre le vrai pilote garantit qu'un URL
  accepté est interprété par le pilote avec le mode TLS validé. Le test de reproduction rouge existant
  devient un test de la suite. **La fiche est incomplète sur ce point** : l'assertion jumelle de
  l'installateur Windows (`Assert-ExternalPostgresUrl` dans
  `scripts/install/configure-runtime-settings.ps1`) décode et met aussi la clé en minuscules ; elle
  est alignée dans la même tâche.
- **B13** : la politique et le diagnostic d'URL reconnaissent `[::1]` et `[0:0:0:0:0:0:0:1]` comme
  bouclage et n'ajoutent plus de crochets à un hôte qui en porte déjà.
- **B08** : les lectures bornées de fichiers de configuration et de secrets retirent **un** BOM UTF-8
  initial ; un BOM répété est refusé explicitement. Documentation utilisateur mise à jour.
- **B09** : `publish` échoue avec « existe déjà » sur une cible existante, sans la modifier, sous
  Windows et Linux, et reste fail-closed si le système de fichiers ne sait pas le garantir ;
  `replace` conserve son comportement.
- Aucun changement de contrat public (CLI, API Java, MCP, IntelliJ, NEXUS) : seule la conformité
  d'un comportement déjà promis est rétablie. Les messages de refus existants sont conservés.

## Capabilities

### New Capabilities

- `stockage-prive-et-secrets` : politique TLS qualifiée d'un PostgreSQL distant, lecture sûre des
  fichiers de configuration et de secrets, publication atomique et immuable des fichiers privés.

### Modified Capabilities

<!-- Aucune : openspec/specs/ est vide. -->

## Constats d'audit couverts

| Constat | Titre | Qualification | Priorité | Traitement |
|---|---|---|---|---|
| MINOS-AUD-B07 | La politique d'URL PostgreSQL accepte `SSLMODE=verify-full` que le pilote ignore : TLS sans vérification | DÉFAUT CONFIRMÉ (reproduit : test rouge existant et `Driver.parseURL` mesuré) | P2 | Tâche 1 (test rouge existant rendu vert, test de parité) |
| MINOS-AUD-B08 | Un BOM UTF-8 supprime silencieusement la première propriété et s'ajoute au mot de passe lu depuis un fichier | DÉFAUT CONFIRMÉ (reproduit) | P2 (propre à Windows en pratique) | Tâche 3 |
| MINOS-AUD-B13 | `PostgresJdbcUrlPolicy` ne reconnaît pas l'IPv6 de bouclage | DÉFAUT CONFIRMÉ (fonctionnel, fail-closed) | P3 | Tâche 2 |
| MINOS-AUD-B09 | `DurableAtomicFile.publish` remplace un fichier existant, contrairement à son contrat | DÉFAUT CONFIRMÉ (latent : les appelants pré-vérifient sous verrou) | P3 | Tâche 4 |

## Hors périmètre

- **B12** (documentation contredite par le code sur l'export de l'audit) : correction documentaire
  d'un autre axe.
- **E12** (DDL exécuté à l'ouverture de la base) : décision à prendre, pas un défaut à corriger
  ici.
- **E06**, et les autres constats de l'annexe B (B01 à B06, B10, B11) : traités par d'autres
  changements.
- Vérification de l'équivalent du BOM dans le script d'installation PowerShell
  `scripts/install/configure-runtime-settings.ps1` (lecture du secret source par `Read-BoundedUtf8`) :
  suspicion non vérifiée, à instruire séparément (question ouverte du design) ; `check-post-mne.py`
  assert des chaînes littérales sur ce script.
- Le BOM dans les enveloppes produites par MINOS (manifeste de bundle ZIP lu par
  `loadUtf8`) : comportement inchangé.
- Le mode de permission des fichiers de secret (rattaché à S5), le refus d'un `search_path` incluant
  `public`, la durabilité des écritures non atomiques de `PrivateLocalStorage.writePrivateFile`.
- Toute rédaction d'ADR.

## Impact

- **Modules du reactor touchés** : `minos-storage-postgresql` (politique d'URL, tests),
  `minos-engine` (`BoundedProperties`, `MinosRuntimeSettings`, `StorageBackendConfiguration`,
  `DurableAtomicFile` ; tests associés). Aucun autre module : les appelants de `publish`
  (`FileHostedControlPlaneStore`, `FileProjectFingerprintSnapshotStore`, dans `minos-storage-local`)
  sont rejoués, pas modifiés. Hors reactor : `scripts/install/configure-runtime-settings.ps1`
  (Windows uniquement).
- **Surfaces publiques impactées** : aucune directement (capacité interne). Comportement
  observable : refus d'URL PostgreSQL plus strict à la configuration (CLI, MCP, Docker, API Java
  partagent la même politique), IPv6 de bouclage accepté, `minos.properties` avec BOM accepté,
  diagnostics `StorageBackendConfiguration` corrigés. IntelliJ et NEXUS : aucun impact.
- **Gates** : `scripts/architecture/check-private-io.py` (aucune E/S brute hors primitives :
  `BoundedProperties` et `DurableAtomicFile` en sont ; aucun nouvel appel brut n'est ajouté
  ailleurs), `scripts/remediation/check-post-mne.py` (littéraux sur la politique d'URL, sur
  `MinosRuntimeSettings` et `StorageBackendConfiguration`),
  `scripts/remediation/check-audit-remediation-v2.py` (littéraux sur `BoundedProperties` et
  `MinosRuntimeSettings`), `scripts/quality/check-hosted-control-plane-consistency.py` (littéraux
  `DurableAtomicFile.replace` et `DurableAtomicFile.publish`),
  `scripts/quality/check-runtime-dynamic-consistency.py`. Scopes JaCoCo `m30-postgresql-pgvector`,
  `m30-storage-backend-selection`, `persistence-cache-indexes`.
- **ADR** : aucun nouvel ADR et aucun amendement requis : les quatre correctifs rétablissent un
  comportement déjà énoncé (message de la politique, Javadoc de `publish`, garanties de
  `STATUS.md`). Si la question ouverte sur le repli de B09 devait aboutir à retirer le support de
  systèmes de fichiers sans lien physique, une note d'ADR serait à proposer (statut Proposed) ; elle
  n'est pas rédigée ici.
