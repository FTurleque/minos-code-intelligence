# M28 — Production Convergence & Architectural Hardening — exécution

Statut : **IMPLÉMENTATION CANDIDATE S1→S8 SUR PR BROUILLON — 0/9 qualifié ; S9 bloqué jusqu’au 1er août 2026.**

```text
Issue          : #93 — OPEN
Branche        : pre-m28-audit-remediation
PR             : #96 — OPEN / DRAFT
Base           : develop @ cfbb495fbca8ddaf2b4bd529985e702e02106505
Qualified HEAD : PENDING — tout ancien log est invalidé par les commits M28 courants
Merge develop  : PENDING
Promotion main : BLOCKED — M21-S2 / #73 requis en août 2026
Date cadrage   : 29 juillet 2026
Dernière mise  : 31 juillet 2026
```

M28 est un jalon de **convergence, correction et hardening**. Il n’ajoute ni nouveau provider majeur, ni ANN/vector database, ni service SaaS opéré.

M21-S2 / GitHub Actions reste **strictement en pause jusqu’au 1er août 2026**. En juillet, aucun workflow n’est modifié ou exécuté et aucune promotion vers `main` n’est autorisée.

## Question produit

> MINOS peut-il garantir que les capacités qualifiées sont réellement câblées dans les compositions de production, que ses sources de vérité restent cohérentes et que ses chemins remote/hosted sont suffisamment durcis pour poursuivre l’évolution sans dette structurelle croissante ?

## Invariants

- aucun claim sans preuve comportementale depuis une composition de production réelle ;
- `FACTUAL`, `DERIVED`, `HEURISTIC` et `OBSERVED_PARTIAL` restent distincts ;
- snapshots structurés autoritatifs ;
- aucune capability extrapolée ;
- MCP read-only ;
- local-first par défaut ;
- remote/hosted fail-closed lorsque la plateforme ou l’opérateur ne fournit pas la preuve ;
- qualifications Windows et Linux sur le même exact HEAD propre ;
- M21-S2 fermé avant toute promotion finale vers `main`.

## État des sous-incréments

### M28-S1 — Advanced-provider production wiring — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

La composition `MinosApplication.open()` câble `minos-java-source-v1` via le provider contraint par fingerprint. Le constructeur par défaut et la composition explicite de `ProgramGraphService` sont alignés. Les providers relation/fichier restent présents sans duplication.

Preuves candidates :

- composition root réelle dans `MinosApplicationTest` ;
- capabilities Java avancées et provenance du provider ;
- rejet fail-closed lorsque le working tree diverge du fingerprint du snapshot.

### M28-S2 — Vertical production capability gates — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

Les parcours verticaux utilisent une fixture Java réelle, une composition `MinosApplication.open()` et les surfaces livrées :

- API Java : `M28VerticalAdvancedApiTest` ;
- CLI / protocole IDE : `M28VerticalProgramGraphCliTest` ;
- MCP : `M28VerticalProgramGraphMcpTest` ;
- composition application : `MinosApplicationTest`.

Les tests couvrent `CONTROL_FLOW`, `LOCAL_DATA_FLOW`, `INTERPROCEDURAL_DATA_FLOW`, `SECURITY_TAINT`, `TAINT_FLOW`, nature `DERIVED`, évidence et `providerId=minos-java-source-v1`.

### M28-S3 — Product facts / source unique — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

`scripts/docs/product-facts.py` dérive le catalogue courant depuis la source runtime autoritative au lieu du tuple historique Java/TypeScript/Python. Le catalogue couvre les sept providers Java, TypeScript, Python, C/C++, C#, Go et Rust. Les gates documentaires vérifient le catalogue et la classification M26/M27 des surfaces publiques.

### M28-S4 — Architecture dependency fitness — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

`scripts/architecture/check-module-boundaries.py` protège :

```text
minos-domain ← minos-engine ← minos-application ← adapters / surfaces
```

Le gate extrait les dépendances Maven MINOS, interdit les directions inversées, détecte les cycles et conserve les contrôles de layout, duplication et package/path.

### M28-S5 — ProgramGraph maintainability & performance — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

`JavaSourceProgramGraphProvider` est désormais une façade stable. Les responsabilités sont séparées :

- `JavaSourceWorkspace` — discovery, confinement et fingerprint ;
- `JavaAstParser` — parsing public JDK AST ;
- `JavaDefUseAnalyzer` — def-use intraprocédural ;
- `JavaControlFlowAnalyzer` — CFG conservatif ;
- `JavaInterproceduralFlowResolver` — correspondance unique nom/arity ;
- `JavaTaintAnalyzer` — règles source/sink/sanitizer explicites ;
- `JavaProgramGraphContext` — émission déterministe avec preuves/confiance ;
- `JavaProgramGraphAssembler` — capabilities et limitations ;
- `JavaProgramGraphEngine` — orchestration.

`JavaSourceProgramGraphDecompositionTest` protège la déterminisme du corpus M22 et empêche la reconcentration de responsabilités.

`ProgramGraphPerformanceQualificationTest` et les runners Windows/Linux mesurent : cold, warm identity cache-hit, fingerprint, analyse, source modifiée et corpus paramétrable jusqu’à 2 000 fichiers. La décision candidate reste :

```text
KEEP_FINGERPRINT_CONSTRAINED_IN_MEMORY_CACHE
```

Cette décision ne devient qualifiée qu’après exécution exact-head sur Windows et Linux.

### M28-S6 — Remote worker sandbox hardening — DISPOSITION CANDIDATE / NON QUALIFIÉE

Le backend natif conserve les garanties M25 de provenance, workspace éphémère, process séparé et bundle vérifié. Il ne revendique pas une sandbox OS.

`WorkerSandboxQualification` expose :

```text
network deny     : FAIL_CLOSED_NOT_ENFORCED
untrusted code   : UNTRUSTED_CODE_UNSUPPORTED
sandbox claim    : PROHIBITED
Windows          : BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND
Linux            : BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND
```

`DENY` est rejeté avant exécution par le backend natif. Cette disposition satisfait l’alternative fail-closed prévue par M28 ; elle ne constitue pas une qualification d’isolation OS.

Guide : `docs/developer/remote-worker-sandbox-disposition.md`.

### M28-S7 — Team/Hosted production boundaries — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

`HostedControlPlaneService` devient une façade stable et délègue à : tenant, authorization, membership, workspaces/bindings, retention, tokens/rotation, audit chain et mutation writer.

Ports formalisés :

- `HostedIdentityProvider` ;
- `HostedTenantKeyProvider` ;
- `HostedAuditSink` ;
- `HostedTransportSecurityPort` ;
- `HostedAvailabilityPort`.

`HostedProductionBoundary` maintient le mode `EMBEDDED_LOCAL_FIRST` et interdit les faux claims :

```text
HOSTED_NETWORK_TRANSPORT_NOT_PROVIDED
HOSTED_BACKUP_AVAILABILITY_NOT_PROVIDED
HOSTED_SAAS_OPERATION_NOT_CLAIMED
HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED
```

Guide : `docs/developer/hosted-production-boundaries.md`.

### M28-S8 — Quality/security gate hardening — CANDIDAT IMPLÉMENTÉ / NON QUALIFIÉ

Les runners Windows/Linux exécutent le même ensemble :

1. invariants P0-P2 ;
2. cohérence M28 structurelle et sémantique ;
3. product facts et documentation courante ;
4. fitness functions de modules ;
5. reactor Maven ;
6. JaCoCo ciblé ;
7. profil ProgramGraph volumétrique ;
8. exact HEAD et worktree propre.

Le scope JaCoCo couvre les composants Java décomposés, `WorkerSandboxQualification` et toutes les classes hosted. Les tests négatifs ciblent wiring, fingerprint stale, RBAC refusé, audit tampering, claims sandbox invalides et frontières hosted.

### M28-S9 — Governance, backlog & main convergence — BLOQUÉ PAR DATE

À reprendre à partir du **1er août 2026** :

- exécuter les qualifications exact-head Windows et Linux sur le HEAD final de la PR #96 ;
- corriger tout échec sans changer le HEAD qualifié silencieusement ;
- reprendre M21-S2 / issue #73 : CI recovery, diagnostics, required checks et readiness de branch protection ;
- réconcilier l’issue historique C0 #2 ;
- rendre la PR #96 reviewable puis fusionner dans `develop` uniquement si tous les gates passent ;
- qualifier le `develop` consolidé ;
- promouvoir vers `main` uniquement après les gates de production applicables ;
- fermer #93, #97, #98 selon leur disposition réelle ;
- réconcilier README, STATUS, roadmap, ADR, preuves et release/supply-chain evidence.

## Gates exact-head à exécuter

Windows :

```powershell
.\scripts\remediation\run-final.ps1 -ProgramGraphFiles 1000
```

Linux :

```bash
bash scripts/remediation/run-final.sh "" 1000
```

Le succès doit porter le même SHA, sur deux worktrees propres, avec un diff vide sous `.github/workflows` pendant la phase juillet-safe.

## Hors périmètre

- nouveau langage/provider majeur ;
- ANN, Lucene, HNSW ou vector database sans mesure ;
- service SaaS opéré ;
- élargissement des claims polyglottes avancés ;
- contournement de M21-S2.

## Critères de sortie

M28 ne sera déclaré terminé que lorsque :

1. S1→S9 sont tous fermés avec preuves reproductibles ;
2. les quatre surfaces publiques prouvent les capabilities M22 depuis la composition réelle ;
3. product facts et module fitness passent ;
4. ProgramGraph est mesuré sur Windows/Linux ;
5. la disposition sandbox est honnête et testée ;
6. les frontières hosted sont explicites et testées ;
7. M21-S2 est fermé ;
8. `develop`, puis `main`, sont qualifiés selon la politique du dépôt ;
9. documentation, issues et preuves sont réconciliées.

Aucun commit présent sur la branche ne vaut qualification tant que les runners exact-head n’ont pas produit leurs logs sur Windows et Linux.
