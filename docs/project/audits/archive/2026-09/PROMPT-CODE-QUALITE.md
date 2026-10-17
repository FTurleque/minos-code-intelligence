# Prompt — Q6, Q7, Q10 à Q14, Q19 et Q20 : l'axe Code (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **Code et dette technique** de l'audit `docs/audit/archive/2026-09/AUDIT-2026-09.md` sur `minos-code-intelligence`. Neuf constats : **Q6** (sorties « déterministes » qui ne le sont pas), **Q7** (JSON invalide possible), **Q10** (ressources jamais fermées, code mort), **Q11** (parsing d'arguments CLI réimplémenté partout), **Q12** (heuristiques de tests liés fragiles), **Q13** (duplication), **Q14** (exceptions avalées), **Q19** (l'invariant « valider avant muter » n'est gardé par rien), **Q20** (`--no-resume` ne force pas un run complet).

**Attention, les chemins cités par l'audit sont datés.** Les chantiers A2, A3 et A4 ont déplacé et renommé des paquets entiers : `minos-storage-local` vit sous `com.minos.storage.local.*`, `minos-runtime-local` sous `com.minos.runtime.local`, le câblage sous `minos-bootstrap`. **Relocalise chaque cible avant d'y toucher** et corrige la référence dans ton suivi.

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-code` | implémente les lots dans l'ordre, par commits réversibles |
| `verif-code` | supervision continue : bug nouveau, régression, convention non respectée, duplication. N'écrit pas de code de production |

`verif-code` démarre **en même temps** qu'`impl-code`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Aucun changement de comportement non voulu.** Les tests de caractérisation et les 12 golden sont le filet. Un golden qui bouge doit être justifié **avant** d'être régénéré, et la justification est dans le commit.
2. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR.
3. **Branche et worktree par lot** : `code/q6-q7-sorties`, `code/q11-cli`, `code/q10-q14-cycle-de-vie`, `code/q12-q13-duplication`. Lots **séquentiels**, chacun rebasé sur le précédent.
4. **Test rouge avant correctif**, preuve jointe au commit.
5. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/archive/2026-09/CODE-SUIVI.md`, section « à traiter plus tard ».
6. **Réponds en français.**

---

## Lot 1 — `code/q6-q7-sorties` : les sorties JSON (Q6, Q7)

**Q6** : des renderers qui se disent déterministes construisent leur réponse avec `Map.of(...)` à deux entrées ou plus, dont l'ordre d'itération est tiré au hasard à chaque démarrage de JVM (au moins le renderer du plan de contrôle d'équipe et celui de l'intelligence runtime). L'ordre des clés JSON change donc d'un processus à l'autre.

**Q7** : `DeterministicJson` écrit un `Number` via `toString()`, donc `NaN` et `Infinity` produisent un JSON invalide ; le `jsonEscape` de la commande `team` n'échappe pas les caractères de contrôle.

**Attendu** : un ordre de clés stable et reproductible (structure ordonnée, jamais `Map.of` au-delà d'une entrée dans un renderer) ; `NaN` et `Infinity` refusés ou sérialisés par une forme explicite et documentée, jamais écrits tels quels ; tout échappement JSON passe par un seul endroit.

**Point de vigilance** : rendre l'ordre stable **est** un changement observable. Prouve que seul l'ordre change, jamais l'ensemble des clés ni les valeurs : compare les golden avant/après clé par clé, et exécute la suite deux fois avec `-XX:+UseCompressedOops` puis sans, ou sur deux JVM différentes, pour montrer que la sortie ne bouge plus d'un lancement à l'autre. Si un consommateur MCP ou API dépend de l'ordre actuel, dis-le dans la PR.

---

## Lot 2 — `code/q11-cli` : un seul parseur d'arguments (Q11, Q19, Q20)

**Q11** : le parsing est réimplémenté une quinzaine de fois avec des règles divergentes — une valeur commençant par `--` acceptée ici et refusée là, `--format` en double toléré par certaines commandes, `toLowerCase()` sans `Locale`, un message d'erreur faux pour `--format` sans valeur. S'y ajoutent : `doctor --help` qui ouvre `MINOS_HOME` et lance le diagnostic complet au lieu d'afficher l'aide, `doctor` et `tools verify` qui divergent sur `UNSUPPORTED_BY_BACKEND`, et `index --dry-run` qui écrit sur disque (bail pris, état sauvegardé).

**Q19** : l'invariant « valider avant muter » de la commande `team` ne tient que par dix-sept répétitions de `rejectUnknown(options)`, qu'aucun test ni garde ne verrouille ; et `team audit --limit 0` sort en 1 (erreur d'exécution) au lieu de 2 (erreur d'usage), alors que la borne est documentée dans l'usage.

**Q20** : `--no-resume` n'entraîne pas un run complet — le drapeau ne pilote que la politique de reprise — alors que l'aide l'annonce ; `--dry-run` accepte les drapeaux de reprise, les ignore, et n'affiche aucune cible réutilisable.

**Attendu** : un parseur commun, avec des règles uniformes et testées (valeur manquante, valeur qui ressemble à une option, option répétée, option inconnue, casse) ; les bornes documentées dans l'usage validées **à l'analyse**, donc en code 2 ; `--help` traité avant toute ouverture de `MINOS_HOME`, pour **toutes** les commandes ; `--dry-run` sans effet de bord observable ; `--no-resume` et l'aide qui disent la même chose ; `doctor` et `tools verify` réconciliés sur un verdict unique.

**La garde de Q19 est le livrable central du lot** : un test qui énumère les sous-commandes — par réflexion ou par une table unique, pas une liste recopiée — et qui échoue si l'une d'elles atteint un service avant validation. Une dix-huitième sous-commande qui oublierait la règle doit faire rougir la suite.

---

## Lot 3 — `code/q10-q14-cycle-de-vie` : ressources et exceptions (Q10, Q14)

**Q10** : le routeur de backend MCP et le lanceur ouvrent une `MinosApplication` sans jamais la fermer ; `LazyAutonomousIndexOperations` est du code mort qui, s'il servait, fuirait l'application qu'il possède.

**Q14** : exceptions avalées dans l'écriture d'historique de projet, dans la détection de fichiers visibles de la découverte, dans la migration des runs hérités et dans la capture de chemin d'exécution.

**Attendu** : chaque application ouverte est fermée sur tous les chemins de sortie, y compris en erreur ; le code mort est supprimé, pas rendu `AutoCloseable` pour la forme.

**Pour chaque exception avalée, choisis explicitement** : propager, ou journaliser et continuer — et écris pourquoi dans le commit. Certaines de ces captures sont délibérées (un fichier illisible pendant la découverte ne doit pas faire échouer la découverte) : dans ce cas le correctif est de **journaliser** sans changer le flux, pas de propager. Transformer un silence en échec dans un chemin chaud est une régression, pas une correction.

---

## Lot 4 — `code/q12-q13-duplication` : heuristiques et duplication (Q12, Q13)

**Q12** : le suffixe `it` de la dérivation de tests liés retire le « it » de noms comme `Audit`, `Commit` ou `Limit` ; et tout répertoire `/test/` est traité comme un répertoire de tests.

**Attendu** : des règles qui ne tronquent que de vrais suffixes (frontière de mot, casse), un répertoire de tests reconnu par la convention du projet et non par la présence du mot n'importe où dans le chemin, et des tests portant nommément sur `Audit`, `Commit`, `Limit`. **Mesure l'effet** : lance la dérivation sur ce dépôt avant et après, et joins le nombre de tests liés gagnés et perdus. Un gain silencieux qui casse des associations existantes n'en est pas un.

**Q13** : `requireText` recopié une quinzaine de fois, `sha256` quatre fois, l'écriture JSON à la main quatre fois alors que `DeterministicJson` existe, le mapping DTO dupliqué entre API, MCP et CLI, et `ProjectView` en double.

**Attendu** : une implémentation par helper, à l'endroit qui respecte les frontières de modules établies par A2 — **un helper partagé ne doit jamais créer une dépendance interdite** ; si la factorisation exigerait une dépendance que `check-module-boundaries.py` refuse, garde deux implémentations et écris pourquoi. Le mapping DTO et `ProjectView` ne sont dédupliqués que si l'API publique n'en est pas changée.

---

## Consigne pour `verif-code` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants.
>
> **Bug nouveau et régression** : une sortie dont l'ensemble des clés ou les valeurs changent alors que seul l'ordre devait changer ; un golden régénéré sans justification ; un code de sortie modifié pour une commande hors périmètre ; une exception désormais propagée dans un chemin où le silence était délibéré ; une ressource fermée trop tôt (une application fermée alors qu'un serveur MCP l'utilise encore) ; un parseur unifié qui refuse une invocation aujourd'hui valide — teste toi-même quelques invocations réelles de chaque commande touchée.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), le placement des classes dans le bon module (A3), la langue de la Javadoc sans mélange dans un même fichier, les messages passés par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043), aucun constructeur télescopique réintroduit (A4).
>
> **Duplication** — c'est le cœur de ce chantier, et le risque est double :
> - une factorisation qui **ajoute** une copie au lieu d'en retirer : compte les occurrences de chaque helper avant et après, le nombre doit strictement baisser ;
> - une factorisation qui **laisse l'ancienne implémentation en place** « au cas où » : l'ancienne disparaît dans le même commit, sinon c'est un bloquant ;
> - un helper commun placé dans un module qui crée une dépendance interdite : `check-module-boundaries.py` doit rester vert, et tu le rejoues toi-même.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`) et compare aux chiffres relevés avant le premier commit. Rejoue deux mutations témoins — une violation de frontière, un package éclaté — et vérifie qu'elles sortent en erreur, puis `git checkout -- .`.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/archive/2026-09/CODE-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre des lots, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les l'une après l'autre, chaque branche rebasée sur la précédente une fois celle-ci verte.

Corps de chaque PR : le ou les constats traités ; les changements observables pour un consommateur (ordre des clés, codes de sortie, résultats de dérivation de tests) en tête et en clair ; ce qui a changé par commit ; la preuve (golden, gates, tests rouges→verts, mesures pour Q12) ; les constats de `verif-code` et leur résolution ; ce qui est laissé de côté.

**Si la CI échoue, tu corriges.** Diagnostique, corrige sur la même branche, repousse, et recommence jusqu'au vert — trois tentatives par PR au maximum. Au-delà, tu t'arrêtes et tu me rends l'analyse plutôt que de continuer à pousser.

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, assouplir un gate, régénérer un golden pour qu'il corresponde au nouveau comportement sans justification écrite, élargir un filtre de chemins, ou retirer une assertion. Si le vert n'est atteignable qu'à ce prix, c'est que le correctif est faux : reviens au code.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- Q6, Q7, Q10 à Q14, Q19 et Q20 corrigés, chacun avec au moins un test qui échouait avant ;
- ordre des clés JSON stable et prouvé sur deux lancements ; aucun JSON invalide possible ;
- un seul parseur d'arguments CLI, et une garde qui verrouille « valider avant muter » ;
- chaque application ouverte est fermée ; chaque exception autrefois avalée est traitée par un choix écrit ;
- le nombre d'occurrences de chaque helper dupliqué a strictement baissé, aucune ancienne implémentation laissée derrière ;
- mesure avant/après pour Q12 ;
- tous les gates verts, aucun test désactivé, aucun golden régénéré sans justification ;
- `docs/audit/archive/2026-09/CODE-SUIVI.md` à jour ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.

---

*Références : `docs/audit/archive/2026-09/AUDIT-2026-09.md` (§ Code et dette technique), `docs/adr/0042`, `0043`, `0044`, `0045`, `scripts/architecture/check-module-boundaries.py`. Méthode et format de suivi : `ARCHI-SUIVI.md` et `CI-HYGIENE-SUIVI.md`.*
