# Prompt — les quatre constats d'échéance « sprints 2–3 » encore ouverts (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du dernier chantier d'échéance **sprints 2–3** de l'audit `docs/audit/AUDIT-2026-09.md` sur `minos-code-intelligence`. Quatre constats, les seuls de cette échéance encore ouverts — tout le reste est fermé. Après ce chantier, il ne reste que des constats d'échéance **Trimestre**.

| ID | Sév. | Axe | En une ligne |
|---|---|---|---|
| S11 | 🟠 | Release | chaîne d'approvisionnement et conteneurs : pas de Dependabot docker, images épinglées par tag, aucune limite de ressources, Ollama en root, **et le plan admin compile du code non fiable sans bac à sable** |
| D1 | 🟠 | Release | le paquet n'embarque aucun indexeur : sur un poste neuf ou sans réseau, MINOS s'installe mais n'indexe rien |
| C2 | 🟠 | CI | trois `clean verify` complets subsistent sur une PR qui touche le cœur |
| S14 | 🟡 | Sécurité | le motif de refus de reprise est écrit sur stdout sans assainissement |

**D1 est la demande que le propriétaire du produit a formulée lui-même**, deux fois, dans ces termes : « je ne veux plus être obligé d'installer les tools de MINOS pour pouvoir l'exécuter ». Sa conception est arrêtée par [ADR 0040](../adr/0040-distribution-auto-portante-indexeurs-embarques.md). Ce n'est pas un constat d'audit parmi d'autres : c'est une exigence produit, et son critère d'acceptation est une machine neuve **sans réseau**.

**Attention, les chemins cités par l'audit sont datés.** Huit sprints ont déplacé et renommé des paquets entiers, et le sprint 8 a introduit un gate d'E/S privées (`scripts/architecture/check-private-io.py`) à **cliquet** : sa liste blanche ne peut que rétrécir, donc tout nouveau code qui crée un fichier passe par les primitives, sans exception à demander. **Relocalise chaque cible avant d'y toucher** et corrige la référence dans ton suivi.

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-s23` | implémente les lots dans l'ordre, par commits réversibles |
| `verif-s23` | supervision continue : bug nouveau, régression, convention non respectée, duplication, durcissement affaibli. N'écrit pas de code de production |

`verif-s23` démarre **en même temps** qu'`impl-s23`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **La CI s'utilise avec parcimonie, et tu demandes avant d'enchaîner.** C'est une consigne du propriétaire du produit, et le lot 3 touche précisément les workflows. Tu valides une modification de workflow **hors CI** d'abord (`actionlint` s'il est disponible, lecture du YAML, `act` si tu l'as), tu ne lances **qu'un seul** passage de validation par lot, et si tu as besoin d'un deuxième tu me le demandes en disant pourquoi. Pas de boucle « pousse et regarde ».
2. **Un correctif de sécurité se prouve par l'attaque, pas par l'intention** (S11, S14) : le test rouge exerce le défaut — il fait compiler du code non fiable, il fait écrire un motif qui porte un chemin.
3. **Aucun durcissement existant ne peut être affaibli pour faire passer un test.** En particulier : le gate d'E/S privées du sprint 8 reste vert sans nouvelle entrée de liste blanche, et [ADR 0041](../adr/0041-indexation-distante-de-code-non-fiable.md) (refus fermé du code non fiable sans bac à sable qualifié) ne se contourne pas.
4. **D1 se prouve hors ligne, pour de vrai.** Pas « les fichiers sont dans le zip », mais : machine ou conteneur neuf, **réseau coupé**, installation depuis le zip, puis une indexation qui réussit. Si tu ne peux pas couper le réseau, dis-le et décris précisément ce que tu as pu prouver à la place.
5. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR.
6. **Branche et worktree par lot**, lots **séquentiels**, chacun rebasé sur le précédent. **Test rouge avant correctif**, preuve jointe au commit.
7. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/S23-SUIVI.md`, section « à traiter plus tard ».
8. **Consigne tout dans `docs/audit/S23-SUIVI.md`** : inventaire daté, décisions écrites avant le code, journal par commit, preuves, résultats de `clean verify` et des gates à chaque fin de lot, tableau des constats de `verif-s23`, et **numéros de PR**.
9. **Réponds en français.**

---

## Lot 1 — `sec/s11-chaine-et-conteneurs` : ce que MINOS embarque et ce qu'il exécute (S11)

Cinq points, d'inégale gravité. **Commence par le dernier, c'est le seul qui soit une exécution de code :**

1. **le plan admin compile du code non fiable sans bac à sable.** C'est le point grave : un chemin d'exécution contourne ce qu'ADR 0041 a établi — le refus fermé quand le bac à sable n'est pas qualifié. **Attendu** : ce chemin passe par la même décision que les autres, ou il refuse ; aucune exception « parce que c'est l'admin ». Le test rouge montre qu'avant, du code non fiable était compilé sans bac à sable.
2. **Dependabot ne surveille pas les images Docker** : ajoute l'écosystème `docker` à `dependabot.yml`.
3. **`pgvector` et `ollama` sont épinglés par tag**, donc mutables : épingle par **digest**. Le dépôt impose déjà cette discipline aux actions GitHub (`check-workflow-pins.py` garde 70 `uses`) — c'est la même règle, appliquée aux images.
4. **aucune limite de CPU, de mémoire ni de PID** dans les `compose.mcp.*` : pose-les.
5. **Ollama tourne en root** : passe-le en utilisateur non privilégié.

**Points de vigilance** :

- **une limite mal choisie casse une indexation réelle.** Une limite mémoire trop basse fait tuer le conteneur par l'OOM killer au milieu d'un run, ce qui ressemble à un bug de MINOS et non à une configuration. **Mesure** l'empreinte réelle d'une indexation de ce dépôt avant de choisir les valeurs, et garde une marge écrite. Un PID limit trop bas casse les providers qui lancent des processus fils ;
- **épingler par digest casse les mises à jour manuelles** : c'est précisément pour ça que le point 2 vient avec le point 3. Vérifie que Dependabot docker est en place et qu'il sait mettre à jour un digest **avant** d'épingler, sinon tu crées une dette de maintenance ;
- **Ollama en non-root** butera probablement sur les droits du volume de modèles : c'est le vrai travail de ce point, pas la ligne `user:`. Prouve que le modèle se télécharge et se charge encore ;
- un gate qui garde l'épinglage des images serait la suite logique de `check-workflow-pins.py`. Si c'est contenu, fais-le ; sinon nomme-le.

---

## Lot 2 — `rel/d1-distribution-auto-portante` : le zip installe tout (D1)

Aujourd'hui la distribution ne contient que le runtime Java et `minos.jar`. Le `README.txt` généré demande encore `minos.cmd tools install scip-java`, et l'installeur n'a aucune étape d'outil. **Sur un poste neuf ou sans réseau, MINOS s'installe et n'indexe rien.** L'image Docker de release, elle, embarque déjà ses chaînes d'outils.

**Attendu, et c'est le critère d'acceptation, pas une liste de tâches** : sur une machine neuve **sans réseau**, l'installation depuis le zip suffit pour qu'une indexation réussisse. Le `README.txt` généré ne demande plus d'installer quoi que ce soit. `minos tools verify` rapporte les outils embarqués comme présents, sans réseau.

**Points de vigilance** :

- **l'image Docker sait déjà le faire.** Pars de son mécanisme et de son manifeste : il doit y avoir **une** description des outils embarqués, partagée par l'image et par le zip. En écrire une seconde pour Windows est le piège principal de ce lot ;
- **la taille du zip va exploser** (des centaines de mégaoctets). Dis combien, avant et après, et si une distribution « sans outils » doit rester disponible à côté — c'est une décision produit, écris-la et signale-la-moi plutôt que de la prendre en silence ;
- **licences** : embarquer des chaînes d'outils tierces, c'est les redistribuer. Vérifie que chacune le permet et que les mentions obligatoires sont dans le paquet. Si une licence l'interdit, dis-le immédiatement : cela change la conception, pas un détail d'emballage ;
- **l'intégrité des outils embarqués doit être vérifiée** comme celle des outils téléchargés : même empreinte, même refus fermé. Un outil embarqué n'est pas un outil de confiance par naissance ;
- **le chemin `tools install` doit continuer de marcher** pour mettre à jour un outil ; embarquer n'est pas remplacer ;
- **par plateforme** : les chaînes d'outils ne sont pas les mêmes selon l'OS et l'architecture. Dis ce que le zip Windows embarque, et ce qu'il advient des autres cibles ;
- le gate d'E/S privées du sprint 8 s'applique à tout code neuf qui écrit sur disque : extraction des outils comprise.

---

## Lot 3 — `ci/c2-un-seul-build` : chaque gate exactement une fois (C2)

Une PR qui touche `minos-domain`, `minos-application`, `minos-api` ou `minos-mcp` déclenche encore **trois** `./mvnw clean verify` complets sous Linux — `pr-ci`, M19, M20 — plus un sous Windows. `product-facts.py` et `check-jacoco.py` tournent trois fois chacun, et chaque workflow réinstalle sa propre chaîne de bac à sable Linux.

**Attendu** : chaque gate s'exécute **exactement une fois** par PR, sans perdre une seule assertion. Les deux voies raisonnables sont de faire de M19 et M20 des workflows réutilisables appelés par `pr-ci`, ou d'absorber leurs gates comme jobs de `pr-ci` qui réutilisent le build déjà fait. Choisis, écris pourquoi, et garde trace de ce que chaque workflow supprimé affirmait.

**Points de vigilance** :

- **l'inventaire des assertions est le livrable**, pas la suppression des fichiers. Avant de toucher un workflow, liste ce qu'il vérifie et qui le vérifiera après. Un gate perdu dans une consolidation est exactement le genre de régression qu'on ne voit que six mois plus tard — et le dépôt en a déjà un exemple : `scripts/history/m21/check-m21-parity.py` est cassé depuis des semaines parce qu'aucun workflow ne le rejoue plus (constat G4) ;
- **renommer un job casse le contrôle de branche.** Les noms de checks exigés sont une configuration du dépôt, invisible depuis le code. Si un nom change, **dis-le-moi explicitement dans la PR**, avec la liste avant/après, sinon les PR suivantes ne pourront plus fusionner ;
- **la consigne de parcimonie s'applique ici en premier** : un chantier qui réduit la CI ne peut pas se valider en multipliant les passages. Un seul passage de validation ; si tu en veux un second, demande ;
- M19 et M20 ne se sont déclenchés sur aucune des dix dernières PR : vérifie que c'est toujours vrai avant de décider, le choix en dépend.

---

## Lot 4 — `sec/s14-assainissement` : un motif de refus ne porte rien sur stdout (S14)

`refusalReason` est concaténé tel quel dans la sortie texte **et** dans la sortie JSON de `minos index`, alors que tous les autres diagnostics de la commande passent par `publicDiagnostic`. Un motif qui embarque un chemin de run ou un message d'exception atterrit donc sur stdout.

**Attendu** : le motif passe par le même assainissement que les autres diagnostics, dans les deux sorties. Un test rouge avec un motif qui porte un chemin absolu **et** un caractère de contrôle.

**Point de vigilance** : **n'écris pas un troisième assainisseur.** Le dépôt en a déjà deux à leur place : `PublicErrorMessages` et `DegradedEntry.printable` (rendue publique au sprint 7, c'est la règle unique pour les caractères de contrôle). Réutilise, et vérifie que la sortie JSON reste du JSON valide après assainissement. Ce lot est délibérément minuscule — un ou deux commits — et il est en dernier parce qu'il ne dépend de rien.

---

## Consigne pour `verif-s23` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants, c'est arrivé deux fois sur les chantiers précédents.
>
> **Bug nouveau et régression** — dans l'ordre de gravité :
> - **du code non fiable peut-il encore être compilé ou exécuté hors bac à sable ?** C'est la question numéro un. Après le lot 1, cherche toi-même tous les chemins qui compilent ou lancent du code venu d'un projet indexé, et vérifie que chacun passe par la décision d'ADR 0041. Une exception « admin » est bloquante.
> - **un gate a-t-il disparu ?** Après le lot 3, dresse la liste des contrôles exécutés par PR avant et après, et compare-les un à un. Tout contrôle qui n'a plus d'exécutant est bloquant. Rejoue aussi `check-milestone-artifact-references.py` : il est là pour ça.
> - **une limite de ressources casse-t-elle un cas réel ?** Lance une indexation de ce dépôt sous les limites posées au lot 1 et regarde la marge réelle. Un conteneur tué par l'OOM killer est bloquant.
> - **le zip tient-il sa promesse ?** Pour le lot 2, installe-le toi-même, réseau coupé si tu le peux, et indexe. Ne te contente pas de lister le contenu de l'archive. Vérifie aussi qu'un outil embarqué altéré est refusé.
> - **un secret, un chemin absolu ou un fragment de fichier peut-il atteindre une sortie ?** Pour le lot 4, relis les deux sorties, texte et JSON, et vérifie que le JSON reste valide.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), la racine de composition toujours dans `minos-bootstrap` (ADR 0042), le placement des classes dans le bon module (A3), un seul constructeur public par classe neuve (A4), la langue de la Javadoc sans mélange dans un même fichier, les messages passés par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043).
>
> **Duplication** — le risque est précis sur ce chantier :
> - **une seconde description des outils embarqués** (une pour l'image Docker, une pour le zip) : bloquant. C'est le piège central du lot 2.
> - **un troisième assainisseur de sortie** à côté de `PublicErrorMessages` et `DegradedEntry.printable` : bloquant.
> - **une seconde liste des images à épingler**, ou une seconde définition des limites de ressources recopiée entre fichiers `compose.*` : compte les occurrences, elles doivent venir d'un seul endroit.
> - **un quatrième chemin d'extraction de fichiers** : le gate d'E/S privées du sprint 8 doit rester vert **sans nouvelle entrée de liste blanche**. Une entrée ajoutée est un bloquant à discuter, pas une formalité — la liste est à cliquet.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-private-io.py` et son auto-test, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-workflow-pins.py`, `check-jacoco.py`) et compare aux chiffres relevés avant le premier commit. Vérifie que les 12 golden de `characterization/` sont inchangés **à chaque commit**.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret d'attaquant quand c'en est un, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/S23-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre des lots, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local. Ouvre-les l'une après l'autre, chaque branche rebasée sur la précédente une fois celle-ci verte.

Corps de chaque PR, **dans cet ordre** :

1. ce que le constat permettait et ne permet plus — pour S11 et S14, ce qu'un attaquant pouvait faire ;
2. les changements observables : taille et contenu du paquet, ce que le `README.txt` ne demande plus, limites de ressources et marge mesurée, **liste avant/après des noms de checks exigés par le contrôle de branche** ;
3. la décision écrite du lot (distribution « sans outils » maintenue ou non, voie retenue pour M19/M20, valeurs de limites et leur justification) ;
4. ce qui a changé par commit ; la preuve (tests rouges→verts, gates, golden, mesures, **le test hors ligne du lot 2 décrit précisément**) ;
5. les constats de `verif-s23` et leur résolution ;
6. ce qui est laissé de côté, et toute licence ou contrainte qui a changé la conception.

**Si la CI échoue, tu corriges.** Diagnostique, corrige sur la même branche, repousse — mais **la consigne de parcimonie prime** : deux tentatives par PR, puis tu t'arrêtes et tu me rends l'analyse. Ne relance jamais un job « pour voir ».

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, assouplir un gate ou élargir la liste blanche du gate d'E/S privées, retirer un contrôle en consolidant les workflows, régénérer un golden sans justification écrite, élargir un filtre de chemins, ou relever une limite de ressources pour masquer une fuite. Si le vert n'est atteignable qu'à ce prix, c'est que le correctif est faux : reviens au code.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- S11, D1, C2 et S14 corrigés, chacun avec un test qui échouait avant ;
- **aucun chemin ne compile ni n'exécute du code non fiable hors bac à sable**, plan admin compris ;
- Dependabot surveille les images, les images sont épinglées par digest, les conteneurs ont des limites mesurées avec leur marge écrite, et Ollama ne tourne plus en root — modèle téléchargé et chargé à l'appui ;
- **sur une machine neuve sans réseau, l'installation depuis le zip suffit pour indexer**, le `README.txt` ne demande plus rien, `tools verify` est vert hors ligne, et un outil embarqué altéré est refusé ;
- une seule description des outils embarqués, partagée par l'image Docker et par le zip ;
- chaque gate s'exécute exactement une fois par PR, l'inventaire avant/après le prouve, et les noms de checks qui changent me sont listés ;
- le motif de refus passe par l'assainissement commun dans les deux sorties, et le JSON reste valide ;
- aucun troisième assainisseur, aucune seconde description d'outils, aucune nouvelle entrée dans la liste blanche du gate d'E/S privées ;
- tous les gates verts, aucun test désactivé, les 12 golden inchangés ou leur modification justifiée ;
- `docs/audit/S23-SUIVI.md` à jour, avec `clean verify`, les chiffres des gates, les constats de `verif-s23` et les numéros de PR ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.
