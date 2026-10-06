# Prompt — Q25 et Q26 : les deux derniers résidus du sprint 7 (à coller dans une session Claude Code à la racine du dépôt)

---

Tu es l'orchestrateur du chantier **Q25 et Q26** de l'audit `docs/audit/AUDIT-2026-09.md` sur `minos-code-intelligence`. Deux constats de sévérité basse, tous deux résidus du sprint 7 (la tolérance aux registres abîmés), et tous deux de la même famille : **le sprint 7 a rendu l'inventaire tolérant, et le reste du produit ne le sait pas encore.**

| ID | Constat |
|---|---|
| Q25 | quatre scripts traitent encore tout code de sortie non nul comme un échec, alors qu'ils lancent une commande qui peut rendre **3** (résultat partiel) : `scripts/m29/run-s5.ps1`, `scripts/m14/validate-local.ps1`, `scripts/m24/run-provider-e2e.py`, `scripts/history/m17/run-final.ps1` |
| Q26 | `listWorkspaces` et `findWorkspace` (`LocalMinosMultiRepositoryApi`) restent stricts : un seul fichier de projet abîmé les fait échouer. Leurs seuls consommateurs sont l'**API** et **MCP**, dont les listes n'ont aucun champ où porter le compte des entrées écartées |

**Q26 est la moitié de Q24 qui n'a pas été fermée, et pour une bonne raison.** Au sprint 7, l'agent a refusé de rendre ces deux méthodes tolérantes : sans champ pour dire « N entrées sont illisibles », la tolérance aurait produit exactement le silence que la doctrine interdit. Fermer le constat demande donc **un changement de contrat public**, pas un correctif. C'est le vrai travail de ce chantier.

**La doctrine est déjà écrite, ne la réinvente pas.** `CLI-SUIVI.md` § 2 la porte, et elle fait foi : une commande n'écarte une entrée illisible que si sa réponse reste vraie pour ce qu'elle a lu, qu'elle ne tire aucune conclusion négative d'une lecture incomplète, qu'elle dit ce qu'elle a écarté, et qu'écarter ne peut rien casser. Appliquée ici : **`listWorkspaces` est un inventaire** (tolérant, compte et affiche), **`findWorkspace` est une résolution** (tolérante dans sa réponse, pas dans son verdict).

**Attention, les chemins cités par l'audit sont datés.** Neuf sprints ont déplacé des paquets entiers. Deux gates à cliquet gouvernent désormais tout code neuf : `check-private-io.py` (liste blanche de 37 entrées qui ne peut que rétrécir) et `check-compose-limits.py`. **Relocalise chaque cible avant d'y toucher.**

Tu travailles avec **un agent d'implémentation** et **un agent de supervision qui tourne en parallèle du début à la fin**.

| Agent | Rôle |
|---|---|
| `impl-q` | implémente les deux lots dans l'ordre, par commits réversibles |
| `verif-q` | supervision continue : bug nouveau, régression, convention non respectée, duplication, contrat cassé. N'écrit pas de code de production |

`verif-q` démarre **en même temps** qu'`impl-q`. Un constat bloquant renvoie l'implémenteur au travail avant qu'il n'avance.

## Règles non négociables

1. **Un contrat public ne se casse pas en silence.** Pour le lot 2 : le champ est **additif**, un client qui l'ignore continue de fonctionner, et tu écris noir sur blanc ce qu'un client existant voit avant et après. Si tu ne peux pas l'ajouter sans rupture, tu t'arrêtes et tu me le dis.
2. **Pas de troisième signal de « résultat partiel ».** Le code 3 de la CLI a un sens unique depuis le sprint 7 ; l'API et MCP doivent exprimer la même chose **une fois**, à un seul endroit. Trois dialectes pour une même notion est le défaut que ce chantier doit éviter, pas créer.
3. **La CI s'utilise avec parcimonie, et tu demandes avant d'enchaîner.** Un seul passage de validation par lot ; un second seulement sur demande motivée. Pas de boucle « pousse et regarde ».
4. **Builds locaux ciblés** pendant le travail ; un `clean verify` complet par lot avant d'ouvrir sa PR.
5. **Test rouge avant correctif**, preuve jointe au commit. **Branche et worktree par lot**, lots séquentiels.
6. **Pas d'élargissement** : ce qui sort du périmètre part dans `docs/audit/Q25-Q26-SUIVI.md`, section « à traiter plus tard ».
7. **Consigne tout dans `docs/audit/Q25-Q26-SUIVI.md`** : inventaire daté, décisions écrites avant le code, journal par commit, preuves, résultats de `clean verify` et des gates, tableau des constats de `verif-q`, et **numéros de PR**. Deux chantiers sur neuf ont laissé ces preuves ailleurs : ne refais pas ça.
8. **Réponds en français.**

---

## Lot 1 — `cli/q25-consommateurs` : quatre scripts, et une décision par script

Les quatre scripts lancent une commande par nom (`project list`, `project inspect <nom>`, `index-status <nom>`) et traitent tout code non nul comme un échec. Aucun n'est exécuté par un workflow, et chacun travaille sur un registre qu'il vient de créer : le risque est **latent**, et l'échec serait visible plutôt que silencieux.

**Ce lot n'est pas « corriger quatre scripts ». C'est décider, pour chacun, s'il doit encore exister.** Écris la décision avant le code, dans le suivi, script par script. Trois issues possibles :

- **corriger** : le script est vivant et utile, il accepte `{0, 3}` pour les commandes concernées et dit en clair quand il a reçu un résultat partiel ;
- **archiver franchement** (ADR 0043) : le script décrit un jalon passé, rien ne le rejoue, et il est déjà irrejouable. L'archiver vaut mieux que le réparer — le dépôt a déjà deux `run-final.ps1` de jalon dont la liste de fichiers pointe vers des chemins disparus, et G4 est le constat de ce qu'il advient d'un gate que plus rien n'exécute ;
- **laisser et nommer** : si tu ne peux ni l'exercer ni trancher, dis-le, et dis ce qu'il faudrait pour trancher.

**Vérifie d'abord que chaque script tourne encore.** `scripts/history/m17/run-final.ps1` est dans le répertoire d'archive ; `scripts/m29/run-s5.ps1` et `scripts/m14/validate-local.ps1` sont peut-être dans le même état. Un script qui échoue déjà pour une autre raison n'a pas besoin d'un correctif de code de sortie : il a besoin d'une décision.

**Points de vigilance** :

- **trois dialectes, une seule règle.** PowerShell (×3) et Python (×1) : la liste des commandes qui peuvent rendre 3 doit venir d'**un** endroit, pas être recopiée dans quatre fichiers. Si cet endroit n'existe pas, c'est le livrable durable du lot ;
- **un gate serait la vraie fermeture**, et il est honnêtement difficile : il faudrait repérer les appels à une commande qui peut rendre 3 suivis d'un test « non nul = échec ». Tente-le si c'est contenu, et **dis dans la PR ce qu'il ne détecte pas**. Un gate heuristique annoncé comme tel vaut mieux qu'une fausse garantie ;
- **G6 : un gate neuf ne bloque rien aujourd'hui.** Le job `Static invariants (single run)` n'est pas un check exigé par le ruleset du dépôt. Si tu ajoutes un gate, écris dans la PR qu'il est **consultatif** jusqu'à ce que le ruleset change. Ne dis pas qu'il protège.

---

## Lot 2 — `api/q26-workspaces-tolerants` : dire ce qu'on n'a pas pu lire

`listWorkspaces` et `findWorkspace` lisent tous les projets pour établir l'appartenance d'un espace de travail ; un seul fichier de projet abîmé les fait échouer. Leurs consommateurs sont l'API et MCP.

**Écris la décision avant le code.** Ma position, appliquée à la doctrine du sprint 7, que tu peux contredire avec un argument :

- **`listWorkspaces` est un inventaire** : il liste les espaces qu'il a pu établir, **compte** les entrées de projet illisibles, et porte ce compte dans sa réponse. Un consommateur qui ignore le champ voit la même liste qu'avant, en mieux (elle n'échoue plus) ;
- **`findWorkspace` est une résolution** : si l'espace est trouvé parmi les projets lisibles, c'est un succès, **assorti du compte** ; s'il n'est pas trouvé **et** que des entrées sont illisibles, la réponse n'est plus « il n'existe pas » mais « introuvable, et N entrées sont illisibles ». La distinction entre « absent » et « indéterminable » est le cœur du constat ;
- **le compte n'est pas un message.** C'est une donnée structurée — un entier, et le choix d'y joindre ou non les identifiants des entrées écartées est à écrire. Un texte libre dans un champ de données serait un troisième assainisseur déguisé.

**Points de vigilance** :

- **l'API : un champ, pas un code HTTP.** Je recommande de ne **pas** employer `206 Partial Content` : ses semantiques de plage sont autres, et un client générique le traiterait mal. Un champ additif dans le corps, documenté, avec `200`. Si tu choisis autrement, argumente ;
- **MCP : la description de l'outil fait partie du contrat.** Le compte va dans la sortie structurée, et la description de l'outil doit dire ce que signifie un inventaire partiel — sinon un client (humain ou modèle) lira une liste incomplète comme une liste complète, ce qui est précisément le silence que la doctrine interdit. Les outils concernés sont ceux qui exposent les espaces de travail ; relocalise-les, ne te fie pas à un nom ;
- **cherche les consommateurs, comme le sprint 7 l'a fait pour les codes de sortie.** Le plugin IntelliJ a déjà dû apprendre le code 3 (Q23) ; vérifie s'il consomme ces deux méthodes, et tout autre appelant interne — `NexusExportService`, la CLI, les tests d'intégration. La liste complète est un livrable ;
- **`DegradedEntry` existe déjà** (`com.minos.registry`, `minos-engine`) et porte la notion d'entrée illisible, avec son assainissement. **Réutilise-la** ; une seconde notion côté API serait bloquante ;
- **ne rends pas tolérant ce qui doit rester strict.** Le sprint 7 a tranché : les mutations restent strictes, la résolution par nom de la CLI reste à son comportement, et `project add` échoue. Ce lot ne touche qu'`listWorkspaces` et `findWorkspace` ;
- **un registre entièrement illisible n'est pas un inventaire partiel**, c'est une panne. Teste le cas où toutes les entrées sont abîmées, et celui où le répertoire du registre lui-même est illisible.

---

## Consigne pour `verif-q` (supervision continue, en parallèle)

> Tu inspectes après chaque commit annoncé, et au moins toutes les dix minutes, avec `git diff` et `git log -p`. Tu n'écris pas de code de production. **Vérifie chaque constat contre le fichier réel du dépôt avant de le déclarer** — un diff lu sur une copie périmée produit de faux bloquants, c'est arrivé deux fois sur les chantiers précédents.
>
> **Bug nouveau et régression** — dans l'ordre de gravité :
> - **un client existant est-il cassé ?** C'est la question numéro un du lot 2. Prends la forme de réponse d'avant le lot, passe-la au code d'après, et vérifie qu'un consommateur qui ignore le champ neuf lit la même chose. Toute rupture non annoncée est bloquante.
> - **la tolérance cache-t-elle une panne ?** Un registre entièrement illisible, un répertoire de registre inaccessible, un disque plein ne doivent pas se présenter comme un inventaire partiel. Exerce-les toi-même.
> - **une entrée est-elle écartée en silence ?** Pour chaque chemin rendu tolérant, vérifie que le compte est réellement porté jusqu'au consommateur — pas calculé puis perdu dans une couche intermédiaire. C'est le défaut le plus probable de ce lot.
> - **un script « corrigé » accepte-t-il désormais un vrai échec ?** Pour le lot 1 : accepter `{0, 3}` ne doit pas faire passer un échec réel pour un succès. Vérifie que 1 et 2 restent des échecs partout.
> - un code de sortie modifié pour une commande hors périmètre ; un golden régénéré sans justification.
>
> **Conventions** : la convention qui fait foi est celle du module touché. Vérifie le nommage, l'emplacement port/adaptateur (A2), la racine de composition dans `minos-bootstrap` (ADR 0042), le placement des classes dans le bon module (A3), un seul constructeur public par classe neuve (A4), la langue de la Javadoc sans mélange dans un même fichier, les messages par `PublicErrorMessages` et sans chemin absolu, `Locale.ROOT` sur toute comparaison de casse, aucun nouveau numéro de jalon dans un nom de fichier (ADR 0043).
>
> **Duplication** — le risque est précis :
> - **une seconde notion d'entrée dégradée** à côté de `DegradedEntry` : bloquant.
> - **un troisième signal de résultat partiel** : compte les façons dont le produit dit « partiel » avant et après. Le nombre ne doit pas monter.
> - **la liste des commandes qui peuvent rendre 3 recopiée** dans plusieurs scripts : bloquant pour le lot 1.
> - **le compte des entrées écartées recalculé** à deux endroits plutôt que porté depuis le registre.
>
> **Après chaque commit** : rejoue les gates (`check-module-boundaries.py`, `check-private-io.py` et son auto-test, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`, `check-single-execution.py`, `check-jacoco.py`) et compare aux chiffres relevés avant le premier commit. **La liste blanche d'E/S privées doit rester à 37 entrées** : elle est à cliquet. Vérifie les 12 golden de `characterization/` à chaque commit.
>
> Chaque constat : identifiant, fichier et ligne, ce qui casse, un scénario concret, une sévérité (bloquant / à corriger / remarque). Tenue à jour de `docs/audit/Q25-Q26-SUIVI.md`.

---

## Fin de chantier : ouvrir les PR, puis les mener au vert

**Tu ouvres les pull requests — c'est demandé explicitement et ce n'est pas optionnel.** Une PR par lot, vers `develop`, en brouillon, dans l'ordre, chacune ouverte quand son lot est complet, inspecté sans constat ouvert, et son `clean verify` vert en local.

Corps de chaque PR, dans cet ordre :

1. **ce qu'un consommateur voit avant et après**, en tête et en clair : forme de réponse de l'API, sortie structurée MCP, comportement des scripts ;
2. la décision écrite du lot — pour le lot 1, l'issue retenue script par script ; pour le lot 2, la position sur le champ et sur le code HTTP ;
3. **la liste complète des consommateurs** de `listWorkspaces` et `findWorkspace` ;
4. ce qui a changé par commit ; la preuve (tests rouges→verts, gates, golden) ;
5. si un gate a été ajouté : ce qu'il ne détecte pas, et le fait qu'il est **consultatif** tant que le ruleset ne l'exige pas (G6) ;
6. les constats de `verif-q` et leur résolution ; ce qui est laissé de côté.

**Si la CI échoue, tu corriges** — mais la parcimonie prime : deux tentatives par PR, puis tu t'arrêtes et tu me rends l'analyse. Ne relance jamais un job « pour voir ».

**Interdit pour faire passer la CI** : désactiver ou marquer `@Disabled` un test, assouplir un gate ou élargir la liste blanche d'E/S privées, régénérer un golden sans justification écrite, ou faire accepter un code d'échec par un script pour qu'il cesse de se plaindre. Si le vert n'est atteignable qu'à ce prix, le correctif est faux.

Puis rends-moi la main avec les liens des PR, l'état de leur CI, et ce qui reste ouvert. Tu ne fusionnes rien.

## Définition de terminé

- Q25 et Q26 traités, chacun avec un test qui échouait avant — ou, pour un script, une décision d'archivage écrite et appliquée ;
- chacun des quatre scripts a une issue écrite : corrigé, archivé, ou nommé avec ce qu'il faudrait pour trancher ;
- la liste des commandes qui peuvent rendre 3 vient d'un seul endroit ;
- `listWorkspaces` ne échoue plus sur une entrée abîmée, et le compte des entrées écartées atteint le consommateur ;
- `findWorkspace` distingue « cet espace n'existe pas » de « introuvable, et N entrées sont illisibles » ;
- le champ est additif et un client qui l'ignore fonctionne à l'identique, démontré ;
- la description des outils MCP concernés dit ce que signifie un inventaire partiel ;
- un registre entièrement illisible, et un registre non listable, restent des **échecs** ;
- aucune seconde notion d'entrée dégradée, aucun troisième signal de résultat partiel, aucune liste de commandes recopiée ;
- tous les gates verts, liste blanche d'E/S privées à 37 entrées, 12 golden inchangés ;
- `docs/audit/Q25-Q26-SUIVI.md` à jour, avec `clean verify`, les gates, les constats de `verif-q` et les numéros de PR ;
- **les PR sont ouvertes et vertes**, leurs liens me sont donnés.
