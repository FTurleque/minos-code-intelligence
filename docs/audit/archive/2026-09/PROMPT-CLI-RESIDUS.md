# Prompt — Q21, Q22, Q23 et Q24 : les résidus CLI (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **résidus CLI** de l'audit `docs/audit/archive/2026-09/AUDIT-2026-09.md` sur `minos-code-intelligence`. Quatre constats de sévérité basse, tous ouverts, tous sur la frontière entre la ligne de commande et le reste :

| ID | Constat | Né de |
|---|---|---|
| Q21 | une commande de lecture écrit dans `MINOS_HOME` : `project list` crée `distributed-artifacts/.leases`, parce que `MinosCliRunner` construit **toutes** les opérations pour n'importe quelle commande | chantier Code (sprint 5) |
| Q22 | une erreur d'usage ouvre encore `MINOS_HOME` : `find-symbol --bogus` l'ouvre avant de refuser l'option, et un `--help` placé au-delà du troisième argument reste une option inconnue | chantier Code (sprint 5) |
| Q23 | le plugin IntelliJ n'accepte que le code 0 de `project list`, qui sort désormais en **3** sur un inventaire partiel : avec un seul projet abîmé il ne résout plus le projet sain ouvert | chantier Fiabilité (sprint 6) |
| Q24 | le listage strict reste strict : avec une entrée de registre abîmée, `project add` et `inspect <nom>` échouent en 1, et `listWorkspaces` avec eux ; `inspect <identifiant>` passe | chantier Fiabilité (sprint 6) |

**Ces quatre constats ne sont pas quatre bricoles indépendantes.** Q21 et Q22 sont le même défaut vu de deux endroits : le câblage est construit avant que la commande ne soit analysée. Q23 et Q24 sont les deux moitiés d'une même question : **jusqu'où la tolérance du sprint 6 doit-elle aller, et qui doit la comprendre ?** Traite-les dans cet ordre, et écris la réponse à cette question **avant** de coder.

**Attention, les chemins cités par l'audit sont datés.** A2, A3, A4, le chantier Code et le chantier Fiabilité ont déplacé et renommé des paquets entiers : le câblage vit sous `minos-bootstrap`, l'analyse d'options dans `CliOptions`, `DegradedEntry` sous `com.minos.registry` dans `minos-engine`. **Relocalise chaque cible avant d'y toucher** et corrige la référence dans ton suivi.

**Vérifie d'abord ta base.** Au 1er octobre, les cinq branches `fiab/*` sont poussées mais je ne sais pas lesquelles sont fusionnées dans `develop`. Q23 et Q24 **dépendent du lot 5 du chantier Fiabilité** (le code de sortie 3 et `DegradedEntry` viennent de là). Commence par le vérifier : si `fiab/q8-q9-tolerance` est fusionnée, pars de `develop` à jour ; sinon, pars de `fiab/q8-q9-tolerance` et dis-le-moi clairement, parce que les PR de ce chantier dépendront alors de la sienne.

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-cli` | implémente les lots dans l'ordre, par commits réversibles |
| `verif-cli` | supervision continue : bug nouveau, régression, convention non respectée, duplication. N'écrit pas de code de production |

`verif-cli` démarre **en même temps** qu'`impl-cli`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Aucun changement de comportement non voulu.** Les 12 golden de caractérisation sont le filet, et ce chantier touche précisément ce qu'ils figent : sorties CLI et codes de sortie. Un golden qui bouge doit être justifié **avant** d'être régénéré, et la justification est dans le commit.
2. **Les codes de sortie sont un contrat.** Aucun code de sortie ne change dans ce chantier, sauf celui que Q22 corrige explicitement (une erreur d'usage sort en 2, et non en 1). Dresse en début de chantier le tableau « commande → codes possibles » tel qu'il est, et confronte-le à la fin.
3. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR. Le plugin IntelliJ est **hors réacteur Maven** (Gradle) : son lot se construit et se teste avec Gradle, et tu dis explicitement ce que la CI couvre ou non pour lui.
4. **Test rouge avant correctif**, preuve jointe au commit.
5. **Branche et worktree par lot**, lots **séquentiels**, chacun rebasé sur le précédent.
6. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/archive/2026-09/CLI-SUIVI.md`, section « à traiter plus tard ».
7. **Réponds en français.**

---

## Lot 1 — `cli/q21-q22-cablage-paresseux` : rien ne s'ouvre avant d'avoir compris la commande (Q21, Q22)

**Q21** — `MinosCliRunner` construit toutes les opérations (indexation distante, plateforme de providers, indexation autonome) quelle que soit la commande. `project list`, une lecture, crée donc `distributed-artifacts/.leases` dans `MINOS_HOME`. Ce n'est pas une écriture gratuite : c'est une commande de lecture qui modifie l'état du poste, et qui échouera sur un `MINOS_HOME` en lecture seule ou monté sur un volume plein.

**Q22** — seule l'aide est désormais traitée sans état. `find-symbol --bogus` ouvre `MINOS_HOME` **avant** de refuser l'option, et un `--help` placé au-delà du troisième argument est traité comme une option inconnue.

**Attendu** :

- **rien n'est construit avant que la commande et ses options ne soient analysées** : l'analyse est déjà sans service depuis le chantier Code (`CliOptions`), il reste à faire en sorte que le dispatcher analyse d'abord et ne construise qu'ensuite, et seulement ce que la commande demande ;
- une **erreur d'usage** (option inconnue, valeur manquante, borne documentée violée) sort en 2 **sans avoir ouvert `MINOS_HOME`** — pas un fichier, pas un répertoire, pas un bail ;
- `--help` est reconnu **à n'importe quelle position** et reste sans état ;
- une commande de **lecture** ne crée ni ne modifie rien sous `MINOS_HOME`.

**Le livrable central du lot est la garde, pas le correctif.** Un test qui, pour **chaque** sous-commande — énumérée par réflexion ou par la table unique du dispatcher, jamais par une liste recopiée —, lance la commande avec une option inconnue sur un `MINOS_HOME` vide, et échoue si **un seul** fichier ou répertoire y apparaît. Même garde pour `--help` à chaque position, et pour les commandes de lecture avec des arguments valides. Une dix-neuvième sous-commande qui oublierait la règle doit faire rougir la suite. Sans cette garde, le correctif se défera au prochain ajout de commande.

**Points de vigilance** :

- la construction paresseuse ne doit pas réintroduire ce que **l'ADR 0042** a fermé : la racine de composition reste dans `minos-bootstrap`, le `ServiceLoader` garde son point unique, et `check-module-boundaries.py` reste vert. Rendre un câblage paresseux en déplaçant une construction vers l'appelant serait une régression d'architecture déguisée en optimisation ;
- un `Supplier` ou un `Lazy` par opération est vite une **quinzaine de nouveaux chemins d'initialisation**, chacun avec sa course possible au premier appel. Préfère **un** point de construction différée, pas quinze ; si le nombre de nouveaux types dépasse ce qu'on peut tenir dans la tête, c'est la conception qui est fausse ;
- vérifie ce qu'une opération construite tôt faisait d'**utile** : si l'ouverture précoce de `MINOS_HOME` servait à valider son existence ou ses droits, cette validation doit survivre pour les commandes qui en dépendent — et seulement pour elles.

---

## Lot 2 — `cli/q24-resolution-tolerante` : la tolérance jusqu'où (Q24)

Le sprint 6 a rendu **l'inventaire** tolérant : `project list` affiche les projets sains, compte les entrées dégradées et sort en 3. Tout le reste est resté strict : `project add`, `inspect <nom>`, `NexusExportService`, `findWorkspace`, `listWorkspaces` échouent en 1 dès qu'un seul fichier de registre est abîmé.

**Écris la décision avant de coder**, dans `docs/audit/archive/2026-09/CLI-SUIVI.md`, commande par commande. Mon avis, que tu peux contredire avec un argument :

- **les mutations restent strictes.** `project add` ne peut pas savoir si l'entrée illisible est celle qu'il s'apprête à écraser : échouer est le bon comportement. Mais son **message** doit dire la vérité — « N entrées du registre sont illisibles, je ne peux pas garantir l'unicité » et non une erreur générique ;
- **la résolution par nom devient tolérante dans sa réponse, pas dans son verdict.** `inspect <nom>` qui ne trouve pas le nom doit distinguer « ce nom n'existe pas » de « ce nom est introuvable, et N entrées sont illisibles » : un code de sortie et un message différents, parce que l'utilisateur doit savoir que sa réponse est incomplète. Il ne doit **pas** deviner ;
- **`listWorkspaces` suit l'inventaire** : il lit tous les projets pour établir l'appartenance, donc il dégrade et compte comme `project list`.

**Attendu** : chaque commande concernée a un comportement **écrit** devant une entrée abîmée, un test qui le fige, et un message qui ne mélange jamais « absent » et « illisible ». Aucune entrée écartée en silence : si une commande ignore quelque chose, elle le compte et l'affiche.

**Points de vigilance** :

- **le code 3 ne doit pas proliférer.** Si chaque commande invente son propre code pour « résultat partiel », le contrat devient illisible. Décide **une** convention (le même 3 pour « résultat partiel », quelle que soit la commande) et écris-la dans `docs/user/cli.md` ;
- la tolérance ne doit pas devenir du silence, ni masquer une vraie panne : un `MINOS_HOME` entièrement illisible n'est pas « un inventaire partiel », c'est un échec. Teste le cas où **toutes** les entrées sont abîmées, et dis ce que la commande rend ;
- `DegradedEntry` existe déjà (sprint 6) avec son assainissement des caractères de contrôle : **réutilise-le**, n'écris pas une seconde notion d'entrée dégradée. C'est le risque de duplication numéro un de ce lot.

---

## Lot 3 — `plugin/q23-code-3` : le consommateur qui n'a pas suivi (Q23)

`MinosCliClient.resolveProject` du plugin IntelliJ n'accepte que le code 0 de `project list`. Depuis le sprint 6, un seul projet abîmé fait sortir la commande en 3, avec une **sortie JSON valide et complète** pour les projets sains : le plugin ne résout alors plus le projet sain ouvert. Ce n'est pas une régression — il échouait déjà, avec le code 1 — mais c'est le seul consommateur connu qui ignore la nouvelle convention.

**Attendu** : le plugin accepte le résultat partiel pour cette commande (`Set.of(0, 3)`, ou la convention arrêtée au lot 2), résout le projet sain, et **le dit à l'utilisateur** — une notification discrète vaut mieux qu'un succès silencieux qui cache un registre abîmé. Un test côté plugin sur une sortie de code 3.

**Points de vigilance** :

- **`minos-intellij` est hors réacteur Maven** : il se construit avec Gradle, et il est probable qu'aucun workflow CI ne le vérifie. Dis-le explicitement dans la PR, et si la CI ne le construit pas, dis ce qu'il faudrait pour qu'elle le fasse — sans l'ajouter dans ce lot ;
- **cherche les autres consommateurs.** Le plugin est celui qu'on connaît ; avant de clore, passe le dépôt au peigne pour tout appelant qui compare un code de sortie de la CLI à 0 — scripts de `scripts/`, exemples de la documentation, tests d'intégration, serveur MCP, API. Tout consommateur qui traite « non-zéro » comme « échec » pour une commande qui peut rendre 3 est le même constat ailleurs, et c'est le vrai livrable de ce lot : la liste complète ;
- n'élargis pas le plugin : ce lot change sa lecture d'un code de sortie, pas son architecture.

---

## Consigne pour `verif-cli` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants, c'est arrivé deux fois sur les chantiers précédents.
>
> **Bug nouveau et régression** — dans l'ordre de gravité :
> - **un code de sortie a-t-il changé sans être demandé ?** C'est la question numéro un. Construis le tableau « commande → codes possibles » avant le premier commit, et refais-le à la fin, en lançant toi-même chaque commande dans ses cas d'erreur. Toute différence non justifiée est bloquante.
> - **une commande de lecture écrit-elle encore ?** Lance chaque commande de lecture sur un `MINOS_HOME` vide, puis compare l'arborescence avant/après, octet par octet. Fais-le aussi sur un `MINOS_HOME` en **lecture seule** : une commande de lecture doit y réussir.
> - **la construction paresseuse a-t-elle créé une course ?** Deux appels concurrents à une même opération différée ne doivent pas construire deux instances ni laisser une instance à moitié initialisée. Si l'implémenteur a introduit un verrou ou un `volatile`, relis-le ; s'il n'en a pas introduit, demande-toi pourquoi ce n'est pas nécessaire et vérifie-le.
> - **une erreur d'usage est-elle encore bruyante ?** Un message d'usage ne doit jamais contenir de chemin absolu, ni de trace, ni un texte venu d'un fichier abîmé sans assainissement (`DegradedEntry` le fait déjà : vérifie qu'il est réutilisé et non recopié).
> - **la tolérance cache-t-elle une panne ?** Un `MINOS_HOME` inaccessible, un disque plein, un registre entièrement illisible ne doivent pas se présenter comme un résultat partiel.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), la racine de composition toujours dans `minos-bootstrap` (ADR 0042), le placement des classes dans le bon module (A3), un seul constructeur public par classe neuve (A4), la langue de la Javadoc sans mélange dans un même fichier, les messages passés par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043).
>
> **Duplication** — c'est le risque principal de ce chantier, et il est précis :
> - **une seconde notion d'« entrée dégradée »** à côté de `DegradedEntry` : bloquant.
> - **un second analyseur d'options** à côté de `CliOptions`, ou une règle d'analyse recopiée dans le dispatcher : bloquant (le chantier Code vient de les unifier).
> - **une seconde liste des sous-commandes** : la garde du lot 1 doit énumérer depuis la table unique du dispatcher. Une liste recopiée dans un test est le bug que la garde est censée empêcher.
> - **un second code pour « résultat partiel »** : compte les codes de sortie distincts avant et après ; le nombre ne doit pas monter au-delà de ce que le lot 2 a décidé par écrit.
> - compte les points de construction du câblage avant et après : le but est de construire **moins**, pas d'ajouter quinze indirections. Si le nombre de types nouveaux dépasse cinq, dis-le.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-jacoco.py`) et compare aux chiffres relevés avant le premier commit. Rejoue deux mutations témoins — une violation de frontière, un package éclaté — et vérifie qu'elles sortent en erreur, puis `git checkout -- .`. Vérifie que les 12 golden de `characterization/` sont inchangés à chaque commit, et non seulement en fin de lot.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/archive/2026-09/CLI-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre des lots, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les l'une après l'autre, chaque branche rebasée sur la précédente une fois celle-ci verte. Si la base de ce chantier est `fiab/q8-q9-tolerance` et non `develop`, dis-le dans chaque PR et cible la bonne branche.

Corps de chaque PR : le ou les constats traités ; **le tableau « commande → codes possibles » avant et après, en tête et en clair**, puis les autres changements observables (messages d'usage, ce qu'une commande écrit ou n'écrit plus dans `MINOS_HOME`, ce que le plugin affiche) ; la décision écrite du lot 2 ; la liste complète des consommateurs de codes de sortie trouvée au lot 3 ; ce qui a changé par commit ; la preuve (tests rouges→verts, gates, golden) ; les constats de `verif-cli` et leur résolution ; ce qui est laissé de côté.

**Si la CI échoue, tu corriges.** Diagnostique, corrige sur la même branche, repousse, et recommence jusqu'au vert — trois tentatives par PR au maximum. Au-delà, tu t'arrêtes et tu me rends l'analyse plutôt que de continuer à pousser.

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, assouplir un gate, régénérer un golden pour qu'il corresponde au nouveau comportement sans justification écrite, élargir un filtre de chemins, retirer une assertion, ou faire sortir une commande en 0 pour qu'un test cesse de se plaindre. Si le vert n'est atteignable qu'à ce prix, c'est que le correctif est faux : reviens au code.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- Q21, Q22, Q23 et Q24 corrigés, chacun avec au moins un test qui échouait avant ;
- aucune commande de lecture n'écrit sous `MINOS_HOME`, et une commande de lecture réussit sur un `MINOS_HOME` en lecture seule ;
- une erreur d'usage sort en 2 sans rien ouvrir, pour **toutes** les sous-commandes, et `--help` est reconnu à n'importe quelle position ;
- la garde du lot 1 énumère les sous-commandes depuis la table unique du dispatcher et rougit si l'une d'elles ouvre `MINOS_HOME` sur une erreur d'usage ;
- le comportement de chaque commande devant une entrée abîmée est **écrit** et testé, « absent » et « illisible » ne sont jamais confondus, et la convention du code « résultat partiel » est dans `docs/user/cli.md` ;
- la liste complète des consommateurs de codes de sortie de la CLI est établie, et ceux qui traitent le résultat partiel comme un échec sont corrigés ou nommés ;
- le tableau « commande → codes possibles » est identique avant/après, sauf les changements justifiés ;
- aucune seconde notion d'entrée dégradée, aucun second analyseur d'options, aucune seconde liste de sous-commandes ;
- tous les gates verts, aucun test désactivé, les 12 golden inchangés ou leur modification justifiée ;
- `docs/audit/archive/2026-09/CLI-SUIVI.md` à jour ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.
