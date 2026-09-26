# Prompt — Sprint 1 de l'audit MINOS (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du **Sprint 1** de remédiation de l'audit `docs/audit/AUDIT-2026-09.md` sur le dépôt `minos-code-intelligence` (Java 24, Maven multi-module, wrapper `./mvnw` / `.\mvnw.cmd`).

Tu travailles avec des **sous-agents** : plusieurs agents d'implémentation en parallèle, **et au moins un agent de vérification qui tourne en parallèle d'eux**, en continu, pour détecter les bugs et les régressions pendant que le code s'écrit — pas seulement à la fin.

## Règles non négociables

1. **Aucune CI GitHub.** Ne pousse rien, n'ouvre aucune PR, ne déclenche aucun workflow sans me le demander explicitement et attendre ma réponse. La validation se fait en local.
2. **Builds locaux ciblés.** Utilise `./mvnw -pl <module> -am test -Dtest=<classes>` pour les boucles de travail. Un `./mvnw clean verify` complet (long) ne se lance qu'une fois, à la fin, et seulement après m'avoir demandé l'autorisation.
3. **Branche de travail** : `sprint1/audit-remediation`, créée depuis `develop`. Un commit par constat, message `fix(<zone>): <constat> — <résumé>` avec l'identifiant du constat (S1, S2, …) dans le corps.
4. **Chaque correction vient avec son test de non-régression**, écrit *avant* le correctif et vérifié rouge, puis vert. Un correctif sans test rouge préalable est refusé.
5. **Pas d'élargissement de périmètre.** Aucun refactor opportuniste, aucun reformatage de fichier, aucune mise à jour de dépendance. Tout ce qui sort du périmètre part dans `docs/audit/SPRINT-1-SUIVI.md`, section « à traiter plus tard ».
6. **Réponds en français.**

## Périmètre : 6 constats

### S1 — Un ADMIN peut s'attribuer le rôle OWNER (Haute, sécurité)
- **Où** : `minos-application/.../hosted/HostedMembershipService.grant`, `HostedTokenService.issue`, `minos-domain/.../hosted/HostedRole.java`.
- **Défaut** : `grant` n'exige que `MEMBER_WRITE` et ne borne pas le rôle attribué par rapport à celui de l'appelant. `ADMIN` possède toutes les permissions sauf `KEY_ROTATE` : il se promeut `OWNER` et obtient la rotation de clé. `requireOwner` vérifie seulement qu'il reste au moins un owner.
- **Attendu** : nul ne peut attribuer, à lui-même ou à autrui, un rôle supérieur au sien ; seul un `OWNER` peut créer ou révoquer un `OWNER` ; l'émission d'un jeton au nom d'un principal de rôle supérieur est refusée. Refus = `SecurityException`, audité comme les autres refus, message sans détail interne.
- **Tests** : ADMIN → OWNER refusé ; ADMIN → ADMIN autorisé ; OWNER → OWNER autorisé ; dernier OWNER non rétrogradable ; `issue` au nom d'un OWNER par un non-OWNER refusé.

### S2 — Des refus en boucle saturent l'audit du tenant (Haute, sécurité)
- **Où** : `minos-application/.../hosted/HostedAuthorizationService.authorizeMutation` (branche refusée), `HostedAuditChain.append`, `HostedRetentionPolicy.MAX_AUDIT_EVENTS`.
- **Défaut** : chaque refus persiste un événement `DENIED` et incrémente la version du tenant. Un membre révoqué dont le jeton est encore valide, ou un simple `VIEWER`, sature l'audit ; `append` lève alors « hard capacity reached » et **plus aucune mutation légitime ne passe**.
- **Attendu** : les refus ne peuvent plus empêcher les mutations autorisées. Mécanisme au choix, à justifier dans le commit : limitation de débit par principal, agrégation des refus consécutifs identiques en un seul événement avec compteur, ou réserve de capacité garantie aux événements autorisés. La traçabilité d'un refus doit rester réelle — ne te contente pas de supprimer l'événement.
- **Tests** : N refus consécutifs au-delà du seuil, puis une mutation autorisée qui doit réussir ; la chaîne d'audit reste vérifiable (contiguïté, HMAC) après agrégation ; un refus isolé reste visible dans l'audit.

### S3 — Le nettoyage cgroup tue les jobs d'un autre processus MINOS (Haute, fiabilité/sécurité)
- **Où** : `minos-runtime-local/.../LinuxCgroupJob.reclaimStaleJobs`, `relocateSelf`.
- **Défaut** : tout cgroup enfant `minos-*` contenant des processus vivants est tué au moment de la découverte, sans vérifier à quel processus MINOS il appartient. Lancer la CLI pendant une indexation MCP interrompt cette indexation (noms `minos-<runId>-…`, `minos-provider-<runId>`).
- **Attendu** : un cgroup n'est récupéré que si son propriétaire est mort. Ajoute une marque d'appartenance (PID du processus MINOS propriétaire + jeton d'instance) dans le nom ou dans un fichier du cgroup, et ne récupère que les cgroups orphelins. Les cgroups d'un autre processus vivant sont laissés intacts.
- **Tests** : Linux uniquement, avec `assumeTrue` sur la disponibilité de cgroup v2 — deux instances simulées, la seconde ne doit pas tuer les jobs de la première ; un cgroup dont le propriétaire est mort est bien récupéré ; aucun test ne doit échouer sur une machine sans cgroup délégué (il doit se skipper proprement).

### S4 — Le mot de passe PostgreSQL apparaît dans `toString()` (Haute, sécurité)
- **Où** : `minos-application/.../storage/StorageBackendConfiguration`.
- **Défaut** : le record contient `postgresPassword` sans `toString()` redéfini ; `safeDescription()` existe mais rien ne l'impose.
- **Attendu** : aucune représentation textuelle du record n'expose le secret. Vérifie en passant qu'aucun autre record ou classe du dépôt ne porte un secret sans masquage (`grep` sur `password`, `secret`, `token`, `key` dans les composants de records) et corrige les cas identiques trouvés — c'est le seul élargissement autorisé.
- **Tests** : `toString()` ne contient pas la valeur du mot de passe ; un message d'exception construit avec la configuration ne le contient pas non plus.

### Q1 — `team …` mute avant de valider les options (Haute, correction)
- **Où** : `minos-cli/.../TeamCommand.run`.
- **Défaut** : `rejectUnknown(options)` n'est appelé **qu'après** le `switch`. `team token-issue --principal p --request_id x` émet un jeton (valide 24 h) sans jamais l'afficher, sort en exit 2, et l'utilisateur relance — double mutation.
- **Attendu** : toutes les options sont validées avant tout appel de service, pour **chaque** sous-commande (`bootstrap`, `workspace-create`, `member-grant`, `member-revoke`, `project-bind`, `project-unbind`, `token-issue`, `key-rotate`, `retention-*`, `audit`). Vérifie aussi le code de sortie : une erreur venant du service ne doit pas être rendue comme une erreur d'usage (exit 2).
- **Tests** : pour chaque sous-commande mutante, une option inconnue sort en exit 2 **sans** que le service ait été appelé (service espionné) ; une erreur de service sort avec le code d'exécution, pas le code d'usage.

### G2 — La documentation affirme ce que le code ne tient pas (Moyenne, gouvernance)
- **Où** : `README.md`, `docs/user/production-installation.md`, `docs/user/installation.md`, commentaires de `minos-storage-postgresql/pom.xml`.
- **Défaut** : le README annonce la sandbox worker OS « qualifiée Linux + Windows » (#98) alors que `WorkerSandboxQualification` rétrograde les deux backends et que l'indexation distante est fermée sur tous les OS (constat A1) ; les guides d'installation présentent `tools install` comme une étape de démarrage alors que l'ADR 0040 fait de l'auto-portance une exigence ; un commentaire cite une version de testcontainers qui n'est plus celle utilisée.
- **Attendu** : la documentation décrit l'état réel du code, avec un renvoi aux ADR 0039 et 0040 pour ce qui est décidé mais pas encore implémenté. Ne corrige **pas** le code de la sandbox dans ce sprint — c'est A1, hors périmètre.
- **Vérification** : l'agent de vérification relit chaque affirmation modifiée **contre le code**, pas contre l'ancienne documentation.

## Organisation des agents

Lance les agents d'implémentation **en parallèle**, répartis par zone du code pour qu'ils ne se marchent pas dessus, chacun dans son propre worktree git :

| Agent | Périmètre | Zone de fichiers |
|---|---|---|
| `impl-hosted` | S1 + S2 | `minos-application/src/**/hosted/**`, `minos-domain/src/**/hosted/**` + tests |
| `impl-runtime` | S3 | `minos-runtime-local/src/**` + tests |
| `impl-surface` | S4 + Q1 | `minos-application/src/**/storage/**`, `minos-cli/src/**` + tests |
| `impl-docs` | G2 | `README.md`, `docs/**`, commentaires de POM |

Et **en parallèle de ceux-ci, en continu** :

| Agent | Rôle |
|---|---|
| `verif-qualite` | Agent de vérification adversarial. Il ne code pas. |
| `verif-build` | Agent d'intégration. Il compile et exécute les tests, il ne code pas. |

### Consigne pour `verif-qualite` (agent de vérification, en parallèle)

> Tu vérifies le travail des agents d'implémentation **pendant** qu'ils travaillent. Toutes les 10 minutes environ, et après chaque commit annoncé, relis les diffs (`git diff`, `git log -p`) de chaque worktree et cherche activement ce qui casse. Tu n'écris pas de code de production ; tu écris des constats.
>
> Cherche en priorité :
> - **un correctif qui ne corrige pas** : rejoue mentalement le scénario d'attaque du constat sur le nouveau code (un ADMIN peut-il encore atteindre OWNER par un autre chemin — `issue`, `bootstrap`, une migration, un import ?) ;
> - **une régression fonctionnelle** : un cas légitime devenu refusé, un code de sortie changé, un format de sortie JSON modifié, un message qui fuit un chemin absolu ou un secret ;
> - **la concurrence** : un verrou ajouté sans délai, un verrou pris dans un ordre différent ailleurs, un état statique mutable, une exception avalée, une ressource non fermée ;
> - **l'intégrité de l'audit hosted** : après toute modification de S2, la chaîne reste-t-elle contiguë et vérifiable ? Un refus peut-il encore être effacé sans trace ?
> - **la portabilité** : chemins Windows, `toLowerCase` sans `Locale`, tests qui exigent cgroup v2 sans `assumeTrue` ;
> - **le test de complaisance** : le test aurait-il échoué avant le correctif ? Demande à l'agent la preuve du rouge ; s'il ne l'a pas, exige-la ;
> - **le périmètre** : tout fichier touché hors de la zone de l'agent, tout reformatage, toute dépendance modifiée ;
> - **les frontières de modules** : exécute `python scripts/architecture/check-module-boundaries.py`.
>
> Pour chaque problème : identifiant du constat concerné, fichier et ligne, ce qui casse, un scénario concret d'échec, et une sévérité (bloquant / à corriger / remarque). Transmets-le à l'agent concerné **immédiatement**, sans attendre la fin. Tiens à jour `docs/audit/SPRINT-1-SUIVI.md`.

### Consigne pour `verif-build`

> Après chaque commit annoncé, exécute le build ciblé du module touché (`./mvnw -pl <module> -am test`), puis, quand deux lots au moins sont terminés, un build croisé des modules impactés ensemble pour détecter les conflits d'intégration. Signale immédiatement toute compilation cassée, tout test devenu rouge, tout test devenu instable (relance deux fois un test qui échoue pour distinguer flaky et régression). Ne corrige rien toi-même : rapporte à l'agent propriétaire du lot. Ne lance jamais le build complet du reactor sans autorisation explicite de l'utilisateur.

## Déroulé attendu

1. Crée la branche et les worktrees, puis lance les 4 agents d'implémentation **et** les 2 agents de vérification en parallèle.
2. Chaque agent d'implémentation : écrit d'abord le test rouge, montre le rouge, corrige, montre le vert, commite, annonce son commit.
3. `verif-qualite` et `verif-build` tournent en continu ; un constat bloquant renvoie l'agent au travail avant qu'il ne passe au suivant.
4. Quand les 6 constats sont verts et qu'aucun bloquant ne reste : fusionne les worktrees dans `sprint1/audit-remediation`, relance les tests ciblés des modules touchés, puis **demande-moi l'autorisation** avant un `clean verify` complet.
5. Rends un rapport final : un tableau constat par constat (statut, fichiers, tests ajoutés, preuve rouge→vert), la liste des problèmes trouvés par la vérification et leur résolution, ce qui a été volontairement laissé de côté, et les commandes exactes que je dois lancer pour valider moi-même.

## Définition de terminé

- les 6 constats corrigés, chacun avec au moins un test qui échouait avant le correctif ;
- aucun test existant cassé dans les modules touchés ;
- `check-module-boundaries.py` vert ;
- aucun secret ni chemin absolu ajouté dans un message, un log ou une sortie JSON ;
- `docs/audit/SPRINT-1-SUIVI.md` à jour ;
- rien n'a été poussé et aucune CI n'a été déclenchée.

---

*Constats de référence : `docs/audit/AUDIT-2026-09.md`, filtre « Échéance = Sprint 1 ». Conceptions liées, hors périmètre de ce sprint : ADR 0039 (reprise d'indexation) et ADR 0040 (distribution auto-portante).*
