# M28 — Production Convergence & Architectural Hardening — exécution

Statut : **PLANIFIÉ — 0/9 ; aucun sous-incrément n’est encore qualifié.**

```text
Issue          : #93 — OPEN
Branche        : à créer lors du démarrage d’implémentation
Base           : develop post-M27 réconcilié
Qualified HEAD : PENDING
Merge develop  : PENDING
Date cadrage   : 29 juillet 2026
```

M28 est issu de l’audit post-M27. Il est volontairement un jalon de **convergence, correction et hardening** avant toute nouvelle expansion fonctionnelle majeure.

M21-S2 / GitHub Actions reste **strictement en pause jusqu’en août 2026**. La définition de M28 en juillet ne déclenche aucun travail CI, aucune modification de workflow et aucune promotion vers `main`.

## Question produit

> MINOS peut-il garantir que les capacités qualifiées sont réellement câblées dans les compositions de production, que ses sources de vérité restent cohérentes et que ses chemins remote/hosted sont suffisamment durcis pour poursuivre l’évolution sans dette structurelle croissante ?

## Motivations issues de l’audit

L’audit a identifié les catégories de risque suivantes, toutes obligatoirement couvertes par M28 :

1. **écart de composition M22** : le provider Java avancé `minos-java-source-v1` est qualifié isolément mais la composition par défaut `MinosApplication.open()` configure explicitement une liste de `ProgramGraphProvider` qui peut l’omettre ;
2. **gates trop textuels** : certaines validations prouvent la présence d’une construction dans les sources sans prouver son activation par le composition root de production ;
3. **facts produit incomplets** : `scripts/docs/product-facts.py` connaît explicitement seulement `scip-java`, `scip-typescript` et `scip-python` alors que le catalogue M24 courant contient sept providers ;
4. **cohérence documentaire sémantique insuffisante** : une chaîne peut être présente tout en étant classée dans la mauvaise section, comme les tools team M27 sous M26 dans `public-surfaces.md` ;
5. **fitness functions de modules incomplètes** : le gate M21 protège layout/duplications mais ne formalise pas encore toutes les directions de dépendances autorisées entre domain, engine, application et adapters ;
6. **hotspots de maintenabilité** : `JavaSourceProgramGraphProvider` et `HostedControlPlaneService` concentrent trop de responsabilités ;
7. **coût potentiel du cache ProgramGraph Java** : le calcul de clé peut relire/re-hasher les sources avant de déterminer qu’un résultat est déjà en cache ;
8. **sandbox remote incomplet** : M25 qualifie `ALLOW`, tandis que `DENY` reste fail-closed faute d’isolation réseau OS prouvée ;
9. **Hosted ≠ SaaS** : M27 fournit un contrôle tenant embarqué qualifié avec contraintes mais ne qualifie ni transport réseau/TLS, ni IdP/KMS opéré, ni sauvegarde/disponibilité, ni isolation processus ;
10. **seuils qualité à renforcer par responsabilité** : les scopes ciblés existent mais certains minima restent faibles et doivent progresser là où le risque le justifie ;
11. **gouvernance** : M21-S2 reste le blocage de promotion vers `main` et l’issue historique C0 #2 reste ouverte malgré C0 déclaré terminé.

## Invariants M28

- aucun nouveau claim produit sans preuve verticale depuis une composition de production réelle ;
- `FACTUAL`, `DERIVED`, `HEURISTIC` et `OBSERVED_PARTIAL` restent distincts ;
- snapshots structurés toujours autoritatifs ;
- aucun ANN, Lucene, HNSW ou vector database sans mesure démontrant un nouveau bottleneck ;
- aucune capability provider extrapolée depuis une capability plus faible ;
- MCP reste strictement read-only ;
- local-first reste le mode par défaut ;
- remote/hosted restent fail-closed lorsque l’isolation ou une dépendance opérateur n’est pas prouvée ;
- M21-S2/CI doit être repris en août 2026 avant toute promotion finale vers `main` ;
- les qualifications finales multiplateformes portent le même exact HEAD avec worktrees propres.

## Sous-incréments

### M28-S1 — P0 Advanced-provider production wiring ⏳ PLANIFIÉ

Objectif : fermer l’écart entre qualification M22 et composition de production.

Livrables obligatoires :

- corriger la composition par défaut `MinosApplication.open()` pour que le provider Java avancé soit réellement actif sur les parcours attendus ;
- supprimer l’écart entre le constructeur par défaut de `ProgramGraphService` et la liste explicite injectée par `MinosApplication` ;
- conserver `RelationshipProgramGraphProvider` et `FileProgramGraphProvider` sans duplication ;
- prouver depuis le runtime réel `CONTROL_FLOW`, `DEF_USE`, `ARGUMENT_FLOW`/`RETURN_FLOW` et `SECURITY_TAINT` lorsque les règles sécurité sont configurées ;
- prouver l’absence explicite de claims lorsque les préconditions ne sont pas remplies.

Gate de sortie minimal : un test/e2e utilisant `MinosApplication.open()` doit échouer avant correction sur le cas audit et passer après correction.

### M28-S2 — Vertical production capability gates ⏳ PLANIFIÉ

Objectif : empêcher qu’une capacité soit qualifiée dans un composant mais absente de la composition livrée.

Livrables obligatoires :

- contract tests depuis le composition root réel ;
- parcours verticaux CLI, API Java, MCP et protocole IDE pour les capabilities majeures ;
- fixture Java réelle indexée puis interrogée depuis les surfaces publiques ;
- contrôle de provenance/providerId dans les résultats ;
- remplacement ou complément des assertions de simple présence textuelle lorsqu’elles portent sur un claim comportemental ;
- matrice `claim → surface → composition → provider → preuve` maintenue comme source vérifiable.

### M28-S3 — Product facts & documentation single source of truth ⏳ PLANIFIÉ

Objectif : supprimer les angles morts des facts générés et renforcer la cohérence sémantique de la documentation.

Livrables obligatoires :

- supprimer le tuple codé en dur `scipJava/scipTypeScript/scipPython` du générateur de product facts ;
- dériver le catalogue complet depuis une source runtime autoritative ou un manifeste unique partagé ;
- couvrir les sept providers actuellement présents : Java, TypeScript, Python, C/C++, C#, Go et Rust ;
- dériver, lorsque c’est mécaniquement possible, versions, dispositions, plateformes et capability profiles ;
- détecter les divergences entre catalogue runtime et documentation générée ;
- corriger la classification des tools M27 actuellement mélangés à M26 dans `docs/developer/public-surfaces.md` ;
- faire évoluer `check-current-docs.py` pour valider la cohérence sémantique des jalons, pas seulement la présence de chaînes ;
- réconcilier README, STATUS, ROADMAP et guides après chaque changement de jalon.

### M28-S4 — Architecture dependency fitness ⏳ PLANIFIÉ

Objectif : protéger les frontières de modules contre l’érosion future.

Politique cible minimale :

```text
minos-domain
  ← minos-engine
  ← minos-application
  ← adapters / surfaces
```

Livrables obligatoires :

- extraire le graphe réel des dépendances Maven MINOS ;
- définir explicitement les dépendances autorisées/interdites ;
- interdire les retours d’adapters vers le cœur et les dépendances de domain vers infrastructure/surfaces ;
- couvrir `minos-domain`, `minos-engine`, `minos-runtime-local`, `minos-storage-local`, `minos-provider-scip`, `minos-integration-git`, `minos-application`, `minos-nexus`, `minos-cli`, `minos-api`, `minos-mcp`, `minos-app` ;
- conserver les contrôles existants de layout, duplication et package/path ;
- rendre le gate reproductible et bloquant.

### M28-S5 — ProgramGraph maintainability & performance ⏳ PLANIFIÉ

Objectif : réduire la dette de complexité M22 et qualifier le coût du chemin Java avancé.

Livrables obligatoires :

- benchmark cold, warm, cache-hit, un fichier modifié et projet Java volumineux ;
- mesurer bytes relus, temps de fingerprint, temps d’analyse et mémoire ;
- éliminer ou justifier le re-hash intégral des sources avant cache hit ;
- privilégier les fingerprints déjà calculés par MINOS lorsque leur provenance est suffisante ;
- découper `JavaSourceProgramGraphProvider` en responsabilités cohésives, par exemple discovery, parsing, CFG, def-use, résolution d’appels, taint, assemblage et fingerprint ;
- conserver exactement les limitations et niveaux de confiance existants tant qu’une nouvelle preuve ne les améliore pas ;
- aucune adoption ANN/vector DB dans ce sous-incrément sans mesure indépendante démontrant un bottleneck sémantique.

### M28-S6 — Remote worker sandbox hardening ⏳ PLANIFIÉ

Objectif : distinguer clairement workspace éphémère et véritable sandbox d’exécution.

Livrables obligatoires :

- conserver ref + SHA exact, workspace éphémère, exclusion `.git`, rejet des symlinks/entrées spéciales et bundle vérifié M25 ;
- introduire une isolation de processus/OS réellement mesurable ;
- Linux : évaluer/qualifier namespaces, sandbox dédiée ou mécanisme équivalent adapté ;
- Windows : évaluer/qualifier Job Objects, restricted token/AppContainer ou mécanisme équivalent adapté ;
- appliquer une politique réseau `DENY` effectivement prouvée lorsque supportée ;
- si une plateforme ne peut pas prouver `DENY`, conserver `BLOCKED/NOT_RUN` fail-closed et l’exposer explicitement ;
- ajouter tests d’évasion filesystem/process/network adaptés ;
- ne jamais présenter le worker comme sandbox pour code non fiable avant preuve e2e.

### M28-S7 — Team/Hosted production boundaries ⏳ PLANIFIÉ

Objectif : préparer l’évolution M27 sans transformer le contrôle embarqué en faux service hosted.

Livrables obligatoires :

- décomposer `HostedControlPlaneService` en services cohésifs de membership/authorization, workspaces/bindings, tokens, audit, rétention et rotation ;
- formaliser ou confirmer les ports pour identité/IdP, KMS/key provider, transport/TLS, backup/disponibilité et audit sink ;
- préserver RBAC fail-closed, AES-256-GCM, audit HMAC chaîné, rotation et exact-snapshot bindings ;
- conserver secrets hors schémas MCP/arguments/logs ;
- documenter précisément la frontière entre `embedded team control plane` et SaaS opéré ;
- aucun claim de service hosted complet avant qualification transport, identité, isolation, sauvegarde et disponibilité.

### M28-S8 — Quality, security & semantic gate hardening ⏳ PLANIFIÉ

Objectif : augmenter la capacité des gates à détecter les défauts réels plutôt que seulement la présence structurelle.

Livrables obligatoires :

- augmenter progressivement les seuils ciblés lorsque justifié par les risques et la mesure ;
- priorité à hosted security, remote/distributed, persistence, provider runtime, API/MCP mappings et composition roots ;
- tests de tampering, invalid input, stale snapshot, permission denial et fail-closed ;
- évaluer mutation tests ou tests négatifs ciblés sur les responsabilités critiques ;
- conserver JaCoCo comme signal et non comme preuve suffisante ;
- prouver qu’une suppression du wiring provider, d’une validation de provenance ou d’un contrôle RBAC fait échouer un gate pertinent ;
- documenter les seuils et raisons de chaque scope.

### M28-S9 — Governance, backlog reconciliation & main convergence ⏳ PLANIFIÉ

Objectif : fermer la dette de promotion accumulée après M20.

Livrables obligatoires :

- en août 2026, reprendre M21-S2 : CI recovery, diagnostics exploitables, required checks et branch-protection readiness ;
- ne pas contourner #73 ni déclarer M21 fermé avant S2 ;
- réconcilier l’issue historique C0 #2 avec l’état réel : fermer completed si ses critères sont satisfaits, ou expliciter les reliquats dans un backlog dédié ;
- rejouer les gates consolidés sur `develop` ;
- qualifier Windows + Linux selon le périmètre exact final ;
- promouvoir vers `main` uniquement après les gates de production applicables ;
- produire release/supply-chain evidence requise ;
- réconcilier README, STATUS, ROADMAP, issue #93, ADR éventuels et preuves exact-head post-merge.

## Hors périmètre M28

M28 n’est pas un jalon d’expansion fonctionnelle. Sont explicitement différés tant que S1→S9 ne sont pas fermés :

- nouveau langage majeur ou nouveau provider majeur ;
- nouveau moteur de recherche/vector DB/ANN sans mesure ;
- nouveau SaaS MINOS opéré ;
- nouvelles fonctions d’intelligence qui masqueraient les écarts de production identifiés ;
- élargissement de claims polyglottes avancés sans provider et preuve dédiés.

## Critères de sortie M28

1. M28-S1→S9 sont tous fermés avec preuves reproductibles ;
2. le provider Java avancé est prouvé depuis `MinosApplication.open()` et les surfaces publiques ;
3. les gates verticaux détectent une rupture volontaire du wiring ;
4. les product facts reflètent le catalogue runtime complet sans liste historique codée en dur ;
5. les fitness functions protègent les directions de dépendances inter-modules ;
6. le coût ProgramGraph cold/warm/cache-hit est mesuré et la stratégie de fingerprint est justifiée ;
7. la disposition sandbox/réseau M25 est prouvée honnêtement sur chaque plateforme ;
8. les frontières hosted M27 sont cohésives, explicites et sans faux claim SaaS ;
9. les gates qualité/sécurité critiques ont été renforcés sans objectifs artificiels ;
10. M21-S2 est fermé avant promotion finale vers `main` ;
11. l’issue C0 #2 est réconciliée ;
12. qualification finale exact-head, worktrees propres, documentation et gouvernance réconciliées.

## Règle de promotion

La création de cette roadmap **ne vaut pas implémentation ni qualification**. M28 reste `PLANIFIÉ` jusqu’au démarrage explicite de son premier sous-incrément.

La promotion finale de M28 vers `main` est impossible tant que M21-S2 n’est pas fermé et que les gates applicables ne sont pas verts. En juillet 2026, aucun workflow GitHub Actions ne doit être exécuté ou modifié au titre de M28.
