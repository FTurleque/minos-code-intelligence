# Prompt — les 12 constats de l'axe Fiabilité (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **Fiabilité opérationnelle** de l'audit `docs/audit/AUDIT-2026-09.md` sur `minos-code-intelligence`. Douze constats, tous ouverts :

| ID | Sév. | Statut | En une ligne |
|---|---|---|---|
| R4 | 🟠 | CONFIRMÉ | la promotion reprise ne vérifie pas que le snapshot préparé couvre les cibles courantes |
| R5 | 🟠 | CONFIRMÉ | la rétention peut supprimer un run reprenable, et le marqueur raccourcit sa durée de vie |
| R2 | 🟠 | PLAUSIBLE | un saut d'horloge peut faire conclure à tort à une réutilisation de PID |
| P1 | 🟠 | CONFIRMÉ | `minos_index_status` échoue pendant une indexation |
| Q3 | 🟠 | CONFIRMÉ | publication d'empreintes non sérialisée |
| Q4 | 🟠 | PLAUSIBLE | la rétention peut supprimer un snapshot préparé |
| Q5 | 🟠 | CONFIRMÉ | `InterruptedException` avalée, chemin d'artefact non confiné |
| Q8 | 🟠 | CONFIRMÉ | un seul fichier corrompu fait échouer `listProjects` |
| Q9 | 🟠 | PLAUSIBLE | une clé sémantique en double fait échouer toute l'indexation |
| R3 | 🟡 | CONFIRMÉ | les résidus cgroup non récupérables ne sont pas signalés |
| R6 | 🟡 | CONFIRMÉ | une interruption pendant l'attente de l'artefact dégrade le run en FAILED |
| R7 | 🟡 | CONFIRMÉ | la réparation « snapshot stable » perd la référence de run reprenable |

**Ne redessine pas la reprise.** R1 a été fermé au sprint 2 et la machinerie de reprise est spécifiée par [ADR 0039](../adr/0039-reprise-indexation-apres-interruption.md). R4, R5, R6 et R7 sont ses **résidus** : ce sont des trous dans une conception déjà arrêtée, pas une invitation à la refaire. De même R2 et R3 sont des résidus du correctif S3 : la marque d'appartenance des cgroups reste le mécanisme, il s'agit de la rendre insensible à l'horloge.

**Attention, les chemins cités par l'audit sont datés.** Les chantiers A2, A3, A4 et le chantier Code ont déplacé et renommé des paquets entiers : `minos-storage-local` vit sous `com.minos.storage.local.*`, `minos-runtime-local` sous `com.minos.runtime.local`, le câblage sous `minos-bootstrap`. **Relocalise chaque cible avant d'y toucher** et corrige la référence dans ton suivi.

**Vérifie d'abord ta base.** Les branches `code/*` du chantier Code ne sont **pas** fusionnées dans `develop` au moment où j'écris ceci. Commence par regarder si elles le sont ; si oui, pars de `develop` à jour ; si non, dis-le-moi et pars quand même de `develop`, en signalant tout conflit prévisible avec `code/q10-q14-cycle-de-vie` (il touche `IndexingRunExecutor`, que ce chantier touche aussi).

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-fiab` | implémente les lots dans l'ordre, par commits réversibles |
| `verif-fiab` | supervision continue : bug nouveau, régression, convention non respectée, duplication, test instable. N'écrit pas de code de production |

`verif-fiab` démarre **en même temps** qu'`impl-fiab`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Aucun changement de comportement non voulu.** Les tests de caractérisation et les 12 golden sont le filet. Un golden qui bouge doit être justifié **avant** d'être régénéré, et la justification est dans le commit.
2. **Un test instable est un échec, pas un aléa.** C'est un chantier de concurrence et de cycle de vie : tout test de course se synchronise par `CountDownLatch`, `CyclicBarrier`, horloge injectée ou point d'entrée déterministe — **jamais** par `Thread.sleep` ni par une attente calibrée « au pif ». Chaque nouveau test de concurrence est rejoué **50 fois** (`-Dsurefire.rerunFailingTestsCount=0`, boucle explicite) avant d'être considéré comme acquis, et le nombre de passages est écrit dans le commit.
3. **Les trois PLAUSIBLE (R2, Q4, Q9) se prouvent avant de se corriger.** Écris d'abord le test qui reproduit le scénario — horloge reculée pour R2, rétention concurrente d'une promotion pour Q4, deux documents de même clé pour Q9. **Si le scénario se révèle non reproductible, ne corrige pas** : écris dans `docs/audit/FIAB-SUIVI.md` pourquoi le constat ne tient pas, avec la preuve, et passe. Un correctif défensif sans scénario démontré ajoute du code et ne ferme rien.
4. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR. Les lots 1, 2 et 4 touchent du code sensible à la plateforme : si tu as accès à Windows, relance-les là aussi ; sinon dis explicitement ce qui n'a été vérifié que sous Linux.
5. **Branche et worktree par lot**, lots **séquentiels**, chacun rebasé sur le précédent.
6. **Test rouge avant correctif**, preuve jointe au commit.
7. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/FIAB-SUIVI.md`, section « à traiter plus tard ».
8. **Réponds en français.**

---

## Lot 1 — `fiab/r4-r5-r7-reprise` : l'intégrité de la reprise (R4, R5, R7)

**R4** — `promoteOnly` exige seulement que rien ne reste à exécuter, que la phase soit PROMOTION et qu'un snapshot soit préparé ; il ne compare jamais les cibles réutilisées à celles du run interrompu. Scénario : run interrompu en promotion sur deux scopes, l'utilisateur supprime un module, relance ; la découverte ne négocie plus que le scope restant, son point de contrôle est valide, et MINOS promeut un snapshot qui contient encore l'index du module supprimé.

**R5** — `overCount = retainedCount > maxEntries || scan.truncated()` : dès que `runs/` dépasse le budget de parcours, la condition devient vraie pour **chaque** entrée, y compris celles portant `.resumable`. Seul le run du processus courant est protégé, donc une seconde indexation concurrente peut supprimer le répertoire de run de la première. Et un run marqué expire à 24 h là où un run ordinaire vit 7 jours : **marquer un run interrompu divise sa durée de vie par sept** au lieu de la prolonger.

**R7** — la branche de réparation « snapshot stable » réécrit l'état projet sans `resumableRunId` et sans appeler `supersede`, contrairement aux autres chemins de récupération. Le run interrompu garde son marqueur : son répertoire échappe indéfiniment à la rétention, et il peut être re-proposé plus tard alors qu'il avait été logiquement supplanté.

**Attendu** : l'égalité des clés de cible est une **condition de la promotion** — à défaut, le snapshot est re-préparé, jamais promu tel quel ; un run marqué reprenable est exclu de la suppression y compris quand le parcours est tronqué, et son marqueur **allonge** sa durée de vie ; tous les chemins de récupération, celui-ci compris, passent par le même point qui écrit `resumableRunId` et appelle `supersede`.

**Point de vigilance** : R5 protège des répertoires de la suppression — c'est une **fuite de disque potentielle**. Prouve qu'un run marqué finit malgré tout par expirer (borne supérieure explicite), et que le marqueur ne peut pas immobiliser `runs/` indéfiniment. R7 est le pendant exact : un marqueur jamais levé est le même bug vu de l'autre côté. Traite les deux ensemble et écris la règle de durée de vie **une seule fois**, à un seul endroit.

---

## Lot 2 — `fiab/p1-q3-q4-verrous` : un seul régime de verrous (P1, Q3, Q4)

**P1** — une lecture d'état prend le bail exclusif de 10 s tenu par `run()`, puis réécrit l'état : `minos_index_status` échoue pendant une indexation au lieu de répondre `INDEXING`.

**Q3** — la publication d'empreintes n'est pas sérialisée : deux publications concurrentes rendent l'état « multiple fingerprint snapshots » permanent, et `compact` peut supprimer un snapshot pendant une promotion.

**Q4** — la rétention tourne sous un verrou distinct du bail de cycle de vie, donc un snapshot préparé peut être supprimé avant sa promotion.

**Attendu** : la lecture d'état est **sans bail exclusif et sans écriture** — une lecture ne mute rien, et répond `INDEXING` pendant une indexation au lieu d'échouer ; publication, compaction et promotion d'empreintes sont mutuellement exclusives ; la rétention et le cycle de vie partagent le même régime d'exclusion, ou la rétention s'interdit explicitement ce que le cycle de vie protège.

**Point de vigilance** : ces trois constats sont le même problème vu de trois endroits — plusieurs mécanismes d'exclusion coexistent et ne se connaissent pas. **Commence par écrire l'inventaire** des verrous, baux et répertoires de travail sous `MINOS_HOME`, qui les prend, dans quel ordre, et pour quelle durée. Mets-le dans `docs/audit/FIAB-SUIVI.md` avant de toucher au code. Un ordre de prise unique et documenté est le livrable qui empêche le prochain interblocage ; ajouter un quatrième verrou serait un échec. Et attention au sens de la correction : rendre une lecture non bloquante ne doit pas la rendre **incohérente** — dis quelle vue elle garantit (dernier état publié, éventuellement en retard) et teste-la sous écriture concurrente.

---

## Lot 3 — `fiab/r2-r3-cgroups` : la propriété des cgroups (R2, R3)

**R2** (PLAUSIBLE, à prouver d'abord) — la marque d'appartenance compare deux instants de démarrage dérivés de l'horloge murale (`ProcessHandle.Info.startInstant`, calculé à partir de `btime`). Un pas NTP ou une reprise de veille décale cette base : deux JVM lisent alors un instant différent pour le même processus, la dérive dépasse les 2 s de tolérance, le balayeur conclut « pid réutilisé » et tue le cgroup d'un processus MINOS **vivant** — le symptôme même de S3, la faille haute fermée au sprint 1.

**R3** — `qualifyRoot` appelle `reclaimStaleJobs(root)` en jetant le `StaleSweep` retourné ; un résidu marqué laissé intact n'est journalisé qu'en DEBUG ; les cgroups non marqués encore peuplés (versions antérieures) ne sont désormais **plus jamais** récupérés et consomment `pids`/`memory` de la racine sans remonter nulle part. Au passage, le message d'échec de suppression journalise un chemin absolu, alors que le fichier affirme deux fois ne jamais journaliser de chemin.

**Attendu** : la décision d'appartenance ne dépend plus de l'horloge murale — compare les ticks de `/proc/<pid>/stat`, qui sont relatifs au démarrage de la machine, ou renonce à récupérer sur le seul écart d'instant ; le résultat du balayage est **exploité** et non jeté ; un résidu non récupérable remonte à un niveau visible, avec un compteur ; aucun chemin absolu dans les messages.

**Point de vigilance — c'est le lot le plus dangereux du chantier.** Ce code tue des processus. La règle est asymétrique et non négociable : **en cas de doute, ne pas récupérer**. Un résidu laissé derrière coûte de la mémoire ; un processus vivant tué est S3 qui revient. Écris explicitement, dans le code et dans la PR, quelle est la conclusion par défaut quand la preuve d'appartenance est indisponible ou ambiguë. Le test de R2 injecte l'horloge — il ne modifie pas l'horloge de la machine.

---

## Lot 4 — `fiab/q5-r6-interruption` : interruption et confinement (Q5, R6)

**Q5** — le `catch(Exception)` de l'exécuteur de run ne rétablit pas le drapeau d'interruption, et `validateArtifact` ne vérifie pas que le chemin d'artefact reste dans le répertoire de run.

**R6** — l'attente de lisibilité rétablit bien le drapeau, mais lève une `IllegalStateException` **sans cause** ; la détection d'interruption en aval remonte `getCause()` et conclut donc à un échec ordinaire. Un arrêt du service dans la seconde qui suit la sortie d'un provider jette tous les points de contrôle déjà acquis. Même famille : `ResumeAborted` ne chaîne jamais sa cause.

**Attendu** : une interruption reste une interruption de bout en bout — drapeau rétabli, cause chaînée, run terminé en **interrompu et reprenable**, jamais en FAILED ; le chemin d'artefact est confiné au répertoire de run par la primitive existante, comme partout ailleurs (S5), pas par une comparaison de chaînes maison.

**Point de vigilance** : le confinement de Q5 est un correctif de **sécurité** déguisé en fiabilité — passe par `PrivateLocalStorage`/`NOFOLLOW` comme le reste du dépôt, avec un test sur un lien symbolique sortant et sur un `..`. Pour R6, la preuve attendue est un test qui interrompt le thread pendant l'attente et constate un run reprenable, points de contrôle conservés : c'est le comptage des points de contrôle survivants qui fait la preuve, pas seulement le statut final.

---

## Lot 5 — `fiab/q8-q9-tolerance` : tolérance aux données abîmées (Q8, Q9)

**Q8** — une erreur d'`Instant.parse`, un UUID invalide ou un `visitFileFailed` ne sont pas isolés par projet : **un seul** fichier corrompu fait échouer tout `listProjects`.

**Q9** (PLAUSIBLE, à prouver d'abord) — la fabrique de documents sémantiques lève une `IllegalStateException` sur une clé en double au lieu de dédupliquer, ce qui fait échouer toute l'indexation sémantique.

**Attendu** : une entrée illisible dégrade **cette** entrée et pas l'inventaire — le projet abîmé apparaît avec un état explicite (et non disparaît silencieusement), et la commande sort avec un code qui distingue « tout va bien » de « inventaire partiel » ; une clé en double est résolue par une règle écrite (première gagne, dernière gagne, ou fusion) et journalisée, jamais par un échec global.

**Point de vigilance** : la tolérance ne doit pas devenir du silence. Un projet ignoré sans trace est pire que l'échec actuel, parce qu'il est invisible. Chaque entrée écartée est **comptée et affichée**. Et pour Q9 : prouve d'abord que deux clés identiques sont réellement atteignables depuis une indexation normale — si ce n'est atteignable que par un appel direct à la fabrique, le constat tombe et se documente au lieu de se corriger.

---

## Consigne pour `verif-fiab` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants, c'est arrivé deux fois sur les chantiers précédents.
>
> **Bug nouveau et régression** — dans l'ordre de gravité :
> - **un processus vivant peut-il être tué ?** C'est la question numéro un du lot 3. Relis la décision d'appartenance en supposant l'horloge cassée, le `/proc` partiellement illisible, le PID recyclé. Toute branche qui récupère sans preuve positive d'appartenance est bloquante.
> - **un interblocage est-il devenu possible ?** Dresse toi-même le graphe de prise des verrous après le lot 2 et cherche un cycle. Un ordre de prise non documenté est bloquant.
> - **une fuite de disque** : un marqueur de reprise qui n'expire jamais, un run exclu de la rétention sans borne supérieure.
> - **une perte de données** : un snapshot promu alors que les cibles ont changé, un point de contrôle jeté sur une interruption, une entrée écartée sans trace.
> - un code de sortie modifié pour une commande hors périmètre ; un golden régénéré sans justification.
>
> **Tests** : tout nouveau test de concurrence est rejoué **50 fois** par tes soins, indépendamment de l'implémenteur. Un seul échec sur 50 est bloquant et le test est réécrit, pas retenté. Refuse tout `Thread.sleep` dans un test de course. Vérifie qu'aucun correctif PLAUSIBLE n'a été écrit **avant** son test rouge : `git log` doit montrer le test d'abord.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), le placement des classes dans le bon module (A3), la langue de la Javadoc sans mélange dans un même fichier, les messages passés par `PublicErrorMessages` et **sans chemin absolu** (R3 en est un cas), `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043), aucun constructeur télescopique réintroduit (A4), aucune I/O dans un constructeur de record (Q15).
>
> **Duplication** : ce chantier touche cinq endroits qui font la même chose — décider qu'un run est reprenable, décider qu'un répertoire est supprimable, décider qu'un processus nous appartient. Le risque est d'écrire une sixième variante de ces règles. Après chaque lot, compte les endroits qui prennent chacune de ces décisions : le nombre doit **baisser ou rester constant**, jamais monter. Une règle de durée de vie ou d'exclusion écrite à deux endroits est bloquante.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-jacoco.py`) et compare aux chiffres relevés avant le premier commit. Rejoue deux mutations témoins — une violation de frontière, un package éclaté — et vérifie qu'elles sortent en erreur, puis `git checkout -- .`.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/FIAB-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre des lots, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les l'une après l'autre, chaque branche rebasée sur la précédente une fois celle-ci verte.

Corps de chaque PR : le ou les constats traités ; **les changements observables pour un utilisateur en tête et en clair** (codes de sortie de `listProjects` en inventaire partiel, statut renvoyé pendant une indexation, durée de vie des répertoires de run, statut d'un run interrompu) ; l'inventaire des verrous pour le lot 2 et la règle de décision par défaut pour le lot 3 ; ce qui a changé par commit ; la preuve (tests rouges→verts, nombre de répétitions des tests de concurrence, gates, golden) ; les constats de `verif-fiab` et leur résolution ; ce qui est laissé de côté, et en particulier **tout constat PLAUSIBLE non reproduit, avec sa preuve de non-reproduction**.

**Si la CI échoue, tu corriges.** Diagnostique, corrige sur la même branche, repousse, et recommence jusqu'au vert — trois tentatives par PR au maximum. Au-delà, tu t'arrêtes et tu me rends l'analyse plutôt que de continuer à pousser. **Un échec intermittent en CI ne se relance pas** : c'est précisément le bug que ce chantier traite, tu le diagnostiques.

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, le marquer `@Flaky` ou le retenter automatiquement, assouplir un gate, régénérer un golden sans justification écrite, élargir un filtre de chemins, retirer une assertion, ou allonger un délai d'attente pour masquer une course. Si le vert n'est atteignable qu'à ce prix, c'est que le correctif est faux : reviens au code.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- R2 à R7, P1, Q3, Q4, Q5, Q8 et Q9 traités — corrigés avec un test qui échouait avant, **ou** documentés comme non reproductibles avec leur preuve ;
- la promotion refuse un snapshot dont les cibles ne correspondent plus ; un run reprenable survit à la rétention **et** finit par expirer ;
- un ordre de prise des verrous unique, écrit, sans cycle ; la lecture d'état ne bloque plus et ne mute plus ;
- la décision d'appartenance d'un cgroup ne dépend plus de l'horloge murale, et ne récupère jamais sans preuve positive ;
- une interruption reste une interruption de bout en bout, points de contrôle conservés ;
- une entrée corrompue dégrade cette entrée seulement, et elle est comptée et affichée ;
- chaque test de concurrence rejoué 50 fois sans échec, aucun `Thread.sleep` de synchronisation ;
- tous les gates verts, aucun test désactivé ou retenté, aucun golden régénéré sans justification ;
- `docs/audit/FIAB-SUIVI.md` à jour, inventaire des verrous inclus ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.
