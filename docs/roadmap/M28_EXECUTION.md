# M28 — Production Convergence & Architectural Hardening — exécution

Statut final : **TERMINÉ / VALIDÉ / PROMU SUR `main` / ISSUE #93 CLOSED-completed.**

```text
Issue M28          : #93 CLOSED / completed
PR remediation     : #96 MERGED
Merge develop      : 53d6faa41579d3d01e7900c5c4b65fdcc42c5868
Promotion main     : PR #102 MERGED
Develop qualifié   : ce4b6ba5f28ecbe3273919318cd950adcf6a0d80
Merge production   : 71738c1d65cc0aae9fd5c5b34e898d72e164a4f4
Release v1.0.0     : PUBLIÉE
Tag/main release   : 1adbc45339efe37cd26d1937025bfa69d7b57811
M21                : #73 CLOSED / completed
Sandbox OS réelle  : #98 OPEN
Date cadrage       : 29 juillet 2026
Clôture             : 1er août 2026
```

M28 est un jalon de convergence et de hardening. Il n'introduit ni nouveau provider majeur, ni backend ANN/vectoriel, ni service SaaS opéré.

## Question produit

> MINOS peut-il garantir que les capacités qualifiées sont réellement câblées dans les compositions de production, que ses sources de vérité restent cohérentes et que ses chemins remote/hosted sont suffisamment durcis pour poursuivre l'évolution sans dette structurelle croissante ?

Disposition finale : **oui, avec les limites explicitement conservées**, notamment l'absence de sandbox OS réelle suivie dans #98.

## Invariants conservés

- aucun claim sans preuve comportementale depuis une composition de production réelle ;
- `FACTUAL`, `DERIVED`, `HEURISTIC` et `OBSERVED_PARTIAL` restent distincts ;
- snapshots structurés autoritatifs ;
- aucune capability extrapolée ;
- MCP read-only ;
- local-first par défaut ;
- remote/hosted fail-closed lorsque la plateforme ou l'opérateur ne fournit pas la preuve ;
- aucune revendication de sandbox OS réelle tant que #98 n'est pas qualifiée.

## Résultat par sous-incrément

### M28-S1 — Advanced-provider production wiring — TERMINÉ

`MinosApplication.open()` câble réellement `minos-java-source-v1` derrière les fingerprints/snapshots attendus. Les capabilities avancées ne sont plus seulement présentes dans un provider isolé : elles sont accessibles depuis la composition produit.

### M28-S2 — Vertical production capability gates — TERMINÉ

Les parcours composition/application/API/CLI-IDE/MCP couvrent les capabilities ProgramGraph avancées avec provenance et nature explicites.

### M28-S3 — Product facts / source unique — TERMINÉ

Le catalogue provider est dérivé de la source runtime autoritative et couvre le catalogue produit courant sans revenir au tuple historique Java/TypeScript/Python.

### M28-S4 — Architecture dependency fitness — TERMINÉ

Les fitness functions protègent les directions de dépendances et les frontières de modules.

### M28-S5 — ProgramGraph maintainability & performance — TERMINÉ

Le provider Java avancé est décomposé en responsabilités testables et le comportement cold/warm/cache est mesuré. La décision reste guidée par mesure.

### M28-S6 — Remote worker sandbox disposition — TERMINÉ AVEC CONTRAINTE EXPLICITE

Le backend natif conserve provenance, workspace éphémère, processus séparé et bundle vérifié, sans présenter cela comme une sandbox OS.

```text
network DENY     : FAIL_CLOSED_NOT_ENFORCED
untrusted code   : UNTRUSTED_CODE_UNSUPPORTED
sandbox claim    : PROHIBITED
Windows          : BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND
Linux            : BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND
```

L'implémentation d'une sandbox OS réelle reste suivie par **#98 OPEN**.

### M28-S7 — Team/Hosted production boundaries — TERMINÉ

La façade hosted reste embarquée/local-first. Les frontières IdP, clés, transport, disponibilité et audit sont explicites. Aucun SaaS opéré n'est revendiqué.

### M28-S8 — Quality/security gate hardening — TERMINÉ

Les gates de structure, capacités, sécurité, JaCoCo ciblé, documentation et exact-head ont été consolidés pour M28.

### M28-S9 — Governance, backlog & main convergence — TERMINÉ

Le 1er août 2026 :

- M21-S2 a été repris ;
- les gates GitHub requis du candidat de promotion ont été observés verts ;
- la branch protection est restée indisponible sur le plan GitHub privé courant et a été enregistrée comme contrainte de plateforme ;
- #73 a été fermé `completed` ;
- la PR #102 a promu `develop` vers `main` ;
- #93 a été fermé `completed` après publication 1.0.0 ;
- #98 est resté ouvert conformément à la réalité produit.

## Publication 1.0.0 et défaut post-publication

La publication 1.0.0 a bien figé la ligne C0→M28, mais l'audit post-publication a identifié un défaut du **packaging Windows**, distinct de la convergence fonctionnelle M28 : le runtime `jpackage` avait été construit avec une liste de modules trop étroite.

Symptôme du MCP natif :

```text
java.lang.NoClassDefFoundError: org/w3c/dom/Node
```

La correction est portée par **1.0.1** et ne modifie pas rétroactivement `v1.0.0`.

Le hardening 1.0.1 ajoute notamment :

- calcul des modules via `jdeps` depuis le JAR final ;
- vérification `java --list-modules` et non-régression `java.xml` ;
- handshake MCP réel sur ZIP installé ;
- handshake MCP réel sur setup isolé ;
- AppId de smoke distinct pour protéger l'installation utilisateur ;
- préflight graphique des clients MCP ;
- capability probes des CLI ;
- Codex Desktop via configuration utilisateur ;
- génération locale du setup avant autorisation de publication.

Voir [`../releases/1.0.1.md`](../releases/1.0.1.md).

## Hors périmètre toujours valide

- nouveau langage/provider majeur opportuniste ;
- ANN/vector database sans décision mesurée ;
- service SaaS opéré ;
- faux claim sandbox ;
- extension fonctionnelle masquant une dette de production.

## Critères de sortie M28 — disposition finale

1. S1→S9 fermés avec preuves ; ✅
2. capacités M22 câblées depuis la composition produit ; ✅
3. product facts et module fitness ; ✅
4. ProgramGraph mesuré ; ✅
5. disposition sandbox honnête ; ✅ — sandbox réelle non revendiquée, #98 reste ouverte
6. frontières hosted explicites ; ✅
7. M21-S2 fermé ; ✅ #73 closed/completed
8. `develop` promu vers `main` ; ✅ PR #102 merged
9. M28 fermé ; ✅ #93 closed/completed
10. release stable créée ; ✅ v1.0.0 publiée

M28 est donc **terminé**. Le correctif 1.0.1 est une maintenance de release Windows post-M28 et doit être qualifié séparément avant publication.
