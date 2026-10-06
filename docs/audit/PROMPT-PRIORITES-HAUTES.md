# Prompt — les 3 constats de priorité haute restants (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **priorités hautes** de l'audit `docs/audit/AUDIT-2026-09.md` sur le dépôt `minos-code-intelligence` (Java 24, Maven multi-module, wrapper `./mvnw` / `.\mvnw.cmd`).

Les cinq autres constats hauts (S1, S2, S3, S4, Q1) ont été corrigés au sprint 1 — voir `docs/audit/SPRINT-1-SUIVI.md`. Il reste **A1, A2 et R1**, les trois plus structurants.

Tu travailles avec des **sous-agents** : des agents d'implémentation, **et au moins un agent qui inspecte l'implémentation en continu, en parallèle**, pour détecter tout bug nouveau ou toute régression pendant que le code s'écrit — pas seulement à la fin.

## Règles non négociables

1. **Aucune CI GitHub.** Ne pousse rien, n'ouvre aucune PR, ne déclenche aucun workflow sans me le demander et attendre ma réponse.
2. **Builds locaux ciblés** (`./mvnw -pl <module> -am test -Dtest=<classes>`). Un `./mvnw clean verify` complet ne se lance qu'à la fin de chaque phase, après mon autorisation.
3. **Branche** `hautes/audit-remediation` depuis `develop`, un worktree git par agent, un commit par lot, message `<type>(<zone>): <constat> — <résumé>` avec l'identifiant (A1, A2, R1) dans le corps.
4. **Test rouge avant correctif**, vérifié rouge puis vert, preuve jointe au commit.
5. **Aucun élargissement de périmètre.** Ce qui sort du périmètre part dans `docs/audit/SPRINT-2-SUIVI.md`, section « à traiter plus tard ». Les résidus du sprint 1 (S12, S13, R2, R3, Q19) ne sont **pas** dans ce chantier.
6. **Réponds en français.**

## Ordre imposé : deux phases

**Phase 1 — R1 et A1 en parallèle** (zones de fichiers disjointes).
**Phase 2 — A2 seul**, une fois la phase 1 fusionnée et verte. A2 déplace des fichiers et des dépendances Maven : le mener en même temps que R1 garantirait des conflits ingérables.

| Phase | Agent | Constat | Zone |
|---|---|---|---|
| 1 | `impl-resume` | R1 | `minos-application/**/orchestration/**`, `**/incremental/**`, `minos-runtime-local/**`, `minos-storage-local/**`, `minos-cli` (drapeaux), `minos-mcp`/`minos-api` (statut) |
| 1 | `impl-sandbox` | A1 | `minos-runtime-local/**` (qualification, backends), `docs/**`, ADR |
| 1 et 2 | `verif-qualite` | — | inspection continue, n'écrit pas de code de production |
| 1 et 2 | `verif-build` | — | compilation et tests, n'écrit pas de code de production |
| 2 | `impl-hexagone` | A2 | POM du reactor, `minos-application`, `minos-app`, `minos-storage-local`, `minos-storage-postgresql`, `scripts/architecture` |

⚠️ `impl-resume` et `impl-sandbox` touchent tous deux `minos-runtime-local`. `impl-sandbox` ne modifie que `WorkerSandbox*`, `LinuxBubblewrap*`, `WindowsAppContainer*`, `WorkerResourceContainment` ; `impl-resume` ne modifie que `ProcessIndexerExecutor`, `RunDirectoryRetention`, `LocalIsolatedIndexWorker`. Toute intersection remonte à l'orchestrateur avant d'être écrite.

---

## R1 — Reprendre une indexation interrompue (🔴 Haute, fiabilité)

**Conception déjà validée** : `docs/adr/0039-reprise-indexation-apres-interruption.md`. Lis-la en entier avant d'écrire une ligne ; elle fixe le modèle, la machine à états et le découpage en 5 lots. Ne la réinvente pas ; si tu veux t'en écarter, demande d'abord.

**État actuel à connaître** : un run abandonné est finalisé en `FAILED` par `AuthoritativeProjectStateReconciler` sans consulter le disque ; `IndexingRunExecutor.execute` tire un `UUID.randomUUID()` neuf, donc perd le lien avec `runs/<runId>/` ; `RunDirectoryRetention.prune` se déclenche au début de chaque exécution provider et peut effacer le run à reprendre.

**Critères d'acceptation** :
- un run interrompu pendant la phase provider reprend sur le même `runId`, ne réexécute que les cibles `(indexerId, providerVersion, projectRelativeRoot)` sans point de contrôle valide, et produit un index **identique octet pour octet** à celui d'un run complet ;
- une interruption en phase `STAGING` ne relance aucun provider ; en phase `PROMOTION`, le snapshot déjà préparé est promu ;
- la reprise est refusée, avec la raison journalisée, dès qu'un artefact manque, que son SHA-256 ou sa taille diffèrent, que la version du provider ou le mode ont changé, que l'empreinte du scope a bougé, ou que le point de contrôle dépasse le TTL ; le repli est alors un run complet, jamais un index partiel ;
- la rétention ne détruit plus le run reprenable avant la fin de son TTL ;
- `minos index` reprend par défaut, `--no-resume` force un run complet, la sortie texte et JSON dit combien de cibles ont été réutilisées, et `minos_index_status` expose le run reprenable ;
- aucun chemin absolu dans les messages ajoutés.

**Tests exigés** (au minimum) : exécuteur qui échoue après N cibles puis reprise ; artefact tronqué ; fichier modifié dans un seul scope ; version de provider changée ; répertoire de run supprimé ; TTL dépassé ; interruption entre stage et promote ; et le test d'intégration du lot 5 — une JVM fille tuée par `destroyForcibly()` en pleine indexation, relancée dans une nouvelle JVM, qui vérifie que les providers déjà terminés ne sont pas réexécutés.

**Gate de couverture** : ajoute à `scripts/quality/check-jacoco.py` le scope `resume-orchestration` (`IndexingResumePlanner`, `AuthoritativeProjectStateReconciler`, `IndexingRunExecutor`) à 75 % lignes / 55 % branches, comme le prévoit l'ADR.

**Livre lot par lot** : le lot 1 (modèle et points de contrôle) ne change aucun comportement et doit être commité séparément, vert, avant le lot 2.

---

## A1 — L'indexation distante est refusée sur tous les OS (🔴 Haute, architecture)

**État actuel** : les deux backends déclarent `filesystemWriteBytes/Entries = SUPERVISED_HARD_KILL`, `WorkerSandboxQualification` les rétrograde donc en `UNTRUSTED_CODE_UNSUPPORTED`, et `LocalIsolatedIndexWorker` échoue en mode fermé avant tout provider — sur Linux comme sur Windows. Environ 2 000 lignes de sandbox sont inatteignables et le repli se fait sans aucun log. Le README a été corrigé au sprint 1 (G2), mais le fond ne l'est pas.

**Étape 1 — décision, avant tout code.** Produis une note de décision courte (ADR 0041, ou une révision de l'ADR 0038) qui tranche entre :

- **(a) rendre le quota d'écriture réellement appliqué par l'OS**, et donc pouvoir revendiquer `UNTRUSTED_CODE_SUPPORTED` : quota de projet XFS/btrfs, `tmpfs` de taille fixe monté dans le profil bwrap, ou image disque à taille fixe côté Windows — avec les prérequis d'installation que cela impose ;
- **(b) assumer que l'indexation distante de code non fiable reste fermée**, et alors : rendre le refus explicite et diagnosticable, supprimer ou marquer clairement le code mort, et documenter la limite comme une décision et non comme un défaut.

Chiffre pour chaque option le travail, les prérequis opérateur, ce qui devient qualifiable et ce qui ne l'est pas. **Puis arrête-toi et demande-moi de choisir.** N'écris le code qu'après ma réponse.

**Critères d'acceptation communs aux deux options** :
- le repli est journalisé : quand un backend est écarté, la raison exacte (dimension non OS-enforced) apparaît en WARNING, sans chemin absolu ;
- `minos doctor` dit clairement si l'indexation distante est disponible et, sinon, pourquoi ;
- la revendication affichée (README, docs, `WorkerSandboxQualification`) correspond exactement à ce que l'OS applique — aucune dimension supervisée ne doit être présentée comme garantie ;
- le test `currentOsBackendsFailClosedUntilStorageIsOsEnforced` est mis à jour en cohérence avec la décision, jamais supprimé en silence.

---

## A2 — La couche application dépend d'adaptateurs concrets (🔴 Haute, architecture)

**État actuel** : `minos-application` dépend dans son POM de `runtime-local`, `storage-local`, `provider-scip` et `integration-git` ; `MinosApplicationAssembler` et `LocalStorageBackend` instancient des classes concrètes (`ManagedScip*`, `FileHostedControlPlaneStore`, `FileSymbolSnapshotStore`, `FileSemanticVectorStore`, `FileProjectFingerprintSnapshotStore`) ; des adaptateurs fichiers vivent dans le module application (`FileProjectFingerprintSnapshotStore`, `FileIndexStateStore`, `LocalProjectRegistry`) ; `ProviderPlatformService` appelle `ScipIndexerCatalog` ; `SemanticIndexService` teste les implémentations par `instanceof` ; et `minos-storage-postgresql` dépend en retour de `minos-application`.

**Cible** :
- `minos-application` n'expose que des ports (interfaces) et ne dépend plus d'aucun module adaptateur dans son POM ;
- la racine de composition (le câblage concret) vit dans `minos-app` ;
- les adaptateurs fichiers déménagent dans `minos-storage-local` ;
- le catalogue de providers est atteint par un port, et le choix d'implémentation sémantique ne se fait plus par `instanceof` ;
- `minos-storage-postgresql` ne dépend plus de `minos-application` ;
- `scripts/architecture/check-module-boundaries.py` **interdit** désormais `application → adaptateur` et `adaptateur → application`, et échoue si la règle est violée.

**Contrainte absolue : aucun changement de comportement.** C'est un déplacement de dépendances, pas une réécriture. Avant de déplacer quoi que ce soit, capture le comportement actuel : une série de tests de caractérisation sur les sorties CLI, MCP et API (mêmes commandes, mêmes JSON, octet pour octet) qui doit rester verte du début à la fin. Si une sortie change, c'est une régression, pas une amélioration.

**Critères d'acceptation** :
- le graphe de dépendances Maven est acyclique et conforme à la cible, vérifié par le script ;
- l'API publique (`minos-api`) est inchangée, ou les ruptures sont listées et justifiées ;
- l'ordre d'initialisation et la fermeture des ressources sont préservés (`MinosApplication` reste fermable, rien n'est ouvert plus tôt ni fermé plus tard) ;
- la suite complète des modules touchés est verte, sans test désactivé, sans `@Disabled` ajouté ;
- aucun package n'est déplacé « pour faire propre » hors de la cible ci-dessus (les packages éclatés, c'est A3, hors périmètre).

**Procède par petits commits réversibles** : un port extrait, un adaptateur déplacé, une dépendance retirée — chacun compilant et vert. Jamais un gros commit de déplacement.

---

## Consigne pour `verif-qualite` (inspection continue, en parallèle)

> Tu inspectes le travail des agents d'implémentation **pendant** qu'ils écrivent. Après chaque commit annoncé, et au moins toutes les 10 minutes, relis les diffs (`git diff`, `git log -p`) de chaque worktree. Tu n'écris pas de code de production ; tu écris des constats, transmis immédiatement à l'agent concerné, et tu tiens à jour `docs/audit/SPRINT-2-SUIVI.md`.
>
> Cherche activement, par constat :
>
> **R1** — un artefact périmé réutilisé (l'empreinte du scope couvre-t-elle vraiment les fichiers qui entrent dans l'index ? les fichiers ignorés ? les liens symboliques ?) ; un TOCTOU entre la vérification du SHA-256 et l'ingestion ; un chemin d'artefact non confiné au répertoire de run ; la rétention qui supprime le run pendant sa reprise ; une reprise qui aboutit à un index partiel présenté comme complet ; un `runId` rouvert alors que le run était réellement terminé ; la compatibilité de lecture des runs écrits par la version précédente ; les interruptions non rejouées (`InterruptedException`) ; les états `INTERRUPTED` qui bloqueraient un nouveau run.
>
> **A1** — toute revendication qui s'élargit sans garantie OS correspondante : c'est le seul défaut inacceptable ici. Vérifie que la qualification ne peut pas passer `UNTRUSTED_CODE_SUPPORTED` par une dimension merely supervisée, et qu'aucun chemin ne contourne le refus.
>
> **A2** — un changement de comportement déguisé en déplacement : compare les sorties de caractérisation avant/après, l'ordre d'initialisation, les `close()`, les dépendances Maven ajoutées « pour compiler », les cycles, un port qui fuit un type d'adaptateur dans sa signature, un `instanceof` déplacé au lieu d'être supprimé, et le script de frontières qui serait assoupli au lieu d'être durci.
>
> **Transverse** — concurrence (verrous sans délai, ordre des verrous, état statique mutable), exceptions avalées, ressources non fermées, messages qui fuient un chemin absolu ou un secret, `toLowerCase` sans `Locale`, tests portables Windows/Linux (`assumeTrue` où il faut), test de complaisance (exige la preuve du rouge), fichiers touchés hors zone, dépendances modifiées, et `python scripts/architecture/check-module-boundaries.py` vert.
>
> Pour chaque problème : constat concerné, fichier et ligne, ce qui casse, un scénario concret d'échec, une sévérité (bloquant / à corriger / remarque). Un bloquant renvoie l'agent au travail avant qu'il n'avance. **Vérifie tes propres constats contre le fichier réel du dépôt avant de les déclarer** — un diff lu sur une copie périmée produit de faux bloquants.

## Consigne pour `verif-build`

> Établis d'abord une baseline verte (tests des modules concernés, nombres relevés). Après chaque commit annoncé, relance le build ciblé du module touché, puis un build croisé des modules impactés dès que deux lots sont finis. Un test rouge est relancé deux fois pour distinguer un flaky d'une régression. Tu ne corriges rien : tu rapportes à l'agent propriétaire. Jamais de build complet du reactor sans autorisation explicite de l'utilisateur.

---

## Déroulé attendu

1. Lis l'ADR 0039 et les sections A1, A2 de l'audit. Crée la branche, les worktrees, `docs/audit/SPRINT-2-SUIVI.md`.
2. **Phase 1** : lance `impl-resume` et `impl-sandbox` en parallèle, avec `verif-qualite` et `verif-build` en continu. `impl-sandbox` s'arrête après sa note de décision et attend ma réponse ; pendant ce temps `impl-resume` continue ses lots.
3. Fusionne la phase 1, relance les tests ciblés, demande-moi l'autorisation pour un `clean verify`.
4. **Phase 2** : `impl-hexagone` sur A2, toujours sous inspection continue, par petits commits.
5. Rapport final : un tableau par constat (statut, fichiers, tests ajoutés, preuve rouge→vert), tous les problèmes trouvés par l'inspection et leur résolution, ce qui est laissé de côté, et les commandes exactes que je dois lancer pour valider moi-même.

## Définition de terminé

- R1 : reprise fonctionnelle et prouvée par le test d'interruption réelle, index identique à un run complet, gate JaCoCo `resume-orchestration` en place et vert ;
- A1 : décision écrite, validée par moi, implémentée, revendications alignées sur ce que l'OS applique, repli journalisé ;
- A2 : frontières de modules conformes et verrouillées par le script, sorties de caractérisation inchangées ;
- aucun test existant cassé, aucun `@Disabled` ajouté, `check-module-boundaries.py` vert ;
- aucun secret ni chemin absolu ajouté dans un message, un log ou une sortie JSON ;
- `docs/audit/SPRINT-2-SUIVI.md` à jour ; rien n'a été poussé, aucune CI déclenchée.

---

*Références : `docs/audit/AUDIT-2026-09.md` (filtre Sévérité = Haute, État = Ouverts), `docs/adr/0039-reprise-indexation-apres-interruption.md`, `docs/adr/0038-aggregate-worker-resource-containment.md`, `docs/audit/SPRINT-1-SUIVI.md`.*
